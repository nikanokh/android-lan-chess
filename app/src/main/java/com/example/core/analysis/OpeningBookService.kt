package com.example.core.analysis

import com.example.domain.model.PieceColor
import javax.inject.Inject
import javax.inject.Singleton

data class OpeningBookEntry(
    val eco: String,
    val name: String,
    val variation: String,
    val pgnMoves: List<String>,
    val continuation: List<String> = emptyList()
)

data class CompiledTheoryNode(
    val fenKey: String,
    val eco: String,
    val name: String,
    val variation: String,
    val ply: Int,
    val continuationSan: List<String>,
    val isExactEndpoint: Boolean
)

object OpeningBookMeta {
    const val SOURCE = "Local ECO & Master Theory Database"
    const val VERSION = "v2026.4"
}

/**
 * Normalizes a FEN string to its 4-field position-identity key:
 * `piecePlacement activeColor castlingRights enPassantTarget`
 */
fun normalizeFenKey(fen: String): String {
    val parts = fen.trim().split(Regex("\\s+"))
    val placement = parts.getOrNull(0) ?: ""
    val turn = parts.getOrNull(1) ?: "w"
    val castling = parts.getOrNull(2) ?: "-"
    val ep = parts.getOrNull(3) ?: "-"
    return "$placement $turn $castling $ep"
}

/**
 * Returns the 4-field FEN key where the en passant square is retained ONLY if an
 * actual legal en passant capture exists in the position; otherwise normalizes `ep` to `-`.
 */
fun normalizeLegalEpFenKey(fen: String): String {
    val parts = fen.trim().split(Regex("\\s+"))
    val placement = parts.getOrNull(0) ?: ""
    val turn = parts.getOrNull(1) ?: "w"
    val castling = parts.getOrNull(2) ?: "-"
    val ep = parts.getOrNull(3) ?: "-"
    if (ep == "-") {
        return "$placement $turn $castling -"
    }
    return try {
        val board = ChesslibHelper.parseBoard(fen)
        val hasLegalEp = ChesslibHelper.legalMovesVerbose(board).any { it.isEnPassant }
        "$placement $turn $castling ${if (hasLegalEp) ep else "-"}"
    } catch (_: Exception) {
        "$placement $turn $castling $ep"
    }
}

@Singleton
class OpeningBookService @Inject constructor() {

    private val lock = Any()
    private val entries = mutableListOf<OpeningBookEntry>().apply {
        addAll(INITIAL_ECO_ENTRIES)
        addAll(EXTENDED_ECO_ENTRIES)
    }

    private var exactFenMap: Map<String, CompiledTheoryNode> = emptyMap()
    private var legalEpFenMap: Map<String, CompiledTheoryNode> = emptyMap()
    private var bookEdgesByParentKey: Map<String, Set<String>> = emptyMap()

    init {
        rebuildIndex()
    }

    fun getEntriesCount(): Int = synchronized(lock) { entries.size }

    fun rebuildIndex() {
        synchronized(lock) {
            val nextExact = HashMap<String, CompiledTheoryNode>(512)
            val nextLegal = HashMap<String, CompiledTheoryNode>(512)
            val nextEdges = HashMap<String, MutableSet<String>>(512)

            val sorted = entries.sortedBy { it.pgnMoves.size }

            fun recordEdge(parentKey: String, san: String, uci: String) {
                val set = nextEdges.getOrPut(parentKey) { HashSet() }
                set.add(san)
                set.add(uci)
            }

            for (entry in sorted) {
                val board = ChesslibHelper.parseBoard()
                val totalLen = entry.pgnMoves.size

                for (i in 0 until totalLen) {
                    val parentFen = board.fen
                    val parentExactKey = normalizeFenKey(parentFen)
                    val parentLegalKey = normalizeLegalEpFenKey(parentFen)
                    val moveSan = entry.pgnMoves[i]

                    val moved = try {
                        ChesslibHelper.applySan(board, moveSan)
                    } catch (_: Exception) {
                        null
                    } ?: break

                    recordEdge(parentExactKey, moved.san, moved.uci)
                    recordEdge(parentLegalKey, moved.san, moved.uci)

                    val currentFen = board.fen
                    val exactKey = normalizeFenKey(currentFen)
                    val legalKey = normalizeLegalEpFenKey(currentFen)
                    val isEndpoint = i == totalLen - 1
                    val remainingMoves = entry.pgnMoves.subList(i + 1, totalLen)
                    val fullContinuation = (remainingMoves + entry.continuation).take(6)

                    if (isEndpoint && entry.continuation.isNotEmpty()) {
                        val contBoard = try {
                            ChesslibHelper.parseBoard(currentFen)
                        } catch (_: Exception) {
                            null
                        }
                        if (contBoard != null) {
                            for (contSan in entry.continuation) {
                                val cParentFen = contBoard.fen
                                val cParentExact = normalizeFenKey(cParentFen)
                                val cParentLegal = normalizeLegalEpFenKey(cParentFen)
                                val cMoved = try {
                                    ChesslibHelper.applySan(contBoard, contSan)
                                } catch (_: Exception) {
                                    null
                                } ?: break
                                recordEdge(cParentExact, cMoved.san, cMoved.uci)
                                recordEdge(cParentLegal, cMoved.san, cMoved.uci)
                            }
                        }
                    }

                    val existingExact = nextExact[exactKey]
                    if (isEndpoint) {
                        val node = CompiledTheoryNode(
                            fenKey = exactKey,
                            eco = entry.eco,
                            name = entry.name,
                            variation = entry.variation,
                            ply = i + 1,
                            continuationSan = fullContinuation,
                            isExactEndpoint = true
                        )
                        nextExact[exactKey] = node
                        nextLegal[legalKey] = node
                    } else if (existingExact == null) {
                        val node = CompiledTheoryNode(
                            fenKey = exactKey,
                            eco = if (i + 1 >= 3) entry.eco else "A00",
                            name = if (i + 1 >= 3) entry.name else "Начальные ходы партии",
                            variation = if (i + 1 >= 3) {
                                "Основное продолжение"
                            } else {
                                "Дебют формируется (менее 2 полных ходов)"
                            },
                            ply = i + 1,
                            continuationSan = fullContinuation,
                            isExactEndpoint = false
                        )
                        nextExact[exactKey] = node
                        if (!nextLegal.containsKey(legalKey)) {
                            nextLegal[legalKey] = node
                        }
                    }
                }
            }

            exactFenMap = nextExact
            legalEpFenMap = nextLegal
            bookEdgesByParentKey = nextEdges
        }
    }

    fun addCustomEntries(newEntries: List<OpeningBookEntry>) {
        if (newEntries.isEmpty()) return
        synchronized(lock) {
            entries.addAll(newEntries)
            rebuildIndex()
        }
    }

    fun importPgnOpening(
        pgn: String,
        fallbackEco: String = "A00",
        fallbackName: String = "Пользовательский дебют",
        fallbackVariation: String = "Основная линия"
    ): OpeningBookEntry? {
        return try {
            val headers = parsePgnHeaders(pgn)
            val root = VariationTree.parsePgnMovetextToTree(pgn, headers["FEN"] ?: VariationTree.DEFAULT_START_FEN)
            val history = VariationTree.getMainlineNodes(root).filter { it.ply > 0 }.map { it.san }
            if (history.isEmpty()) return null
            val entry = OpeningBookEntry(
                eco = headers["ECO"]?.takeIf { it.isNotBlank() } ?: fallbackEco,
                name = headers["Opening"]?.takeIf { it.isNotBlank() }
                    ?: headers["Event"]?.takeIf { it.isNotBlank() }
                    ?: fallbackName,
                variation = headers["Variation"]?.takeIf { it.isNotBlank() } ?: fallbackVariation,
                pgnMoves = history.take(30)
            )
            addCustomEntries(listOf(entry))
            entry
        } catch (_: Exception) {
            null
        }
    }

    fun isPositionInTheory(fen: String, strictEnPassant: Boolean = false): CompiledTheoryNode? {
        val exactKey = normalizeFenKey(fen)
        val exactMatch = exactFenMap[exactKey]
        if (exactMatch != null || strictEnPassant) {
            return exactMatch
        }
        return legalEpFenMap[normalizeLegalEpFenKey(fen)]
    }

    fun isBookMove(fenBefore: String, moveSanOrUci: String, fenAfter: String? = null): Boolean {
        val parentExact = normalizeFenKey(fenBefore)
        val parentLegal = normalizeLegalEpFenKey(fenBefore)

        if (bookEdgesByParentKey[parentExact]?.contains(moveSanOrUci) == true) {
            return true
        }
        if (bookEdgesByParentKey[parentLegal]?.contains(moveSanOrUci) == true) {
            return true
        }

        if (fenAfter != null) {
            val parentInBook = parentExact == normalizeFenKey(VariationTree.DEFAULT_START_FEN) ||
                isPositionInTheory(fenBefore) != null
            val childInBook = isPositionInTheory(fenAfter) != null
            if (parentInBook && childInBook) {
                return true
            }
        }
        return false
    }

    fun analyzeMainlineOpening(pathNodes: List<MoveNode>): OpeningAnalysisSummary {
        val moveNodesOnly = pathNodes.filter { it.ply > 0 }

        for (node in pathNodes) {
            node.analysis?.let { a ->
                node.analysis = a.copy(
                    isTheoretical = false,
                    isBookTransposition = false,
                    isLastTheoretical = false,
                    isFirstOutOfTheory = false
                )
            }
        }

        if (moveNodesOnly.size <= 2) {
            var wBook = 0
            var bBook = 0
            var lastBookPly = 0
            var lastBookSan: String? = null
            var firstOutPly: Int? = null
            var firstOutSan: String? = null
            var unbrokenBook = true

            for (i in moveNodesOnly.indices) {
                val n = moveNodesOnly[i]
                val prevFen = if (i == 0) {
                    pathNodes.firstOrNull()?.fen ?: VariationTree.DEFAULT_START_FEN
                } else {
                    moveNodesOnly[i - 1].fen
                }
                val inBook = unbrokenBook && (
                    isBookMove(prevFen, n.san, n.fen) ||
                        isBookMove(prevFen, n.uci, n.fen)
                    )
                if (inBook) {
                    if (n.color == PieceColor.WHITE) wBook++ else bBook++
                    lastBookPly = n.ply
                    lastBookSan = "${n.moveNumber}${if (n.color == PieceColor.WHITE) "." else "..."} ${n.san}"
                    n.analysis = n.analysis?.copy(isTheoretical = true)
                } else if (unbrokenBook) {
                    unbrokenBook = false
                    firstOutPly = n.ply
                    firstOutSan = "${n.moveNumber}${if (n.color == PieceColor.WHITE) "." else "..."} ${n.san}"
                    n.analysis = n.analysis?.copy(isFirstOutOfTheory = true)
                }
            }

            return OpeningAnalysisSummary(
                eco = "—",
                name = "Дебют ещё не определён",
                variation = if (moveNodesOnly.isEmpty()) {
                    "Начальная позиция"
                } else {
                    "Сыграно менее 2 полных ходов — сделайте ещё ход для определения дебюта"
                },
                source = OpeningBookMeta.SOURCE,
                version = OpeningBookMeta.VERSION,
                theoreticalMovesWhite = wBook,
                theoreticalMovesBlack = bBook,
                lastTheoreticalPly = lastBookPly,
                lastTheoreticalMoveSan = lastBookSan,
                firstOutOfBookPly = firstOutPly,
                firstOutOfBookMoveSan = firstOutSan,
                transposedBackToBookPly = null,
                transposedOpeningName = null,
                mainBookContinuationSan = listOf("Nf3", "Nc6", "Bc4", "Bc5")
            )
        }

        var bestNamedMatch = CompiledTheoryNode(
            fenKey = "",
            eco = "A00",
            name = "Нестандартное начало",
            variation = "Редкое продолжение",
            ply = 0,
            continuationSan = emptyList(),
            isExactEndpoint = false
        )

        var lastTheoreticalPly = 0
        var lastTheoreticalMoveSan: String? = null
        var firstOutOfBookPly: Int? = null
        var firstOutOfBookMoveSan: String? = null
        var transposedBackToBookPly: Int? = null
        var transposedOpeningName: String? = null
        var theoreticalMovesWhite = 0
        var theoreticalMovesBlack = 0
        var stillInUnbrokenTheory = true

        val rootFen = pathNodes.firstOrNull()?.fen ?: VariationTree.DEFAULT_START_FEN

        for (i in moveNodesOnly.indices) {
            val node = moveNodesOnly[i]
            val parentFen = if (i == 0) rootFen else moveNodesOnly[i - 1].fen

            val posMatch = isPositionInTheory(node.fen)
            val isMoveBook = isBookMove(parentFen, node.san, node.fen) ||
                isBookMove(parentFen, node.uci, node.fen)

            if (stillInUnbrokenTheory && posMatch != null && isMoveBook) {
                if (node.ply >= 3 && (posMatch.isExactEndpoint || bestNamedMatch.ply == 0)) {
                    bestNamedMatch = posMatch
                }
                lastTheoreticalPly = node.ply
                lastTheoreticalMoveSan = "${node.moveNumber}${if (node.color == PieceColor.WHITE) "." else "..."} ${node.san}"
                if (node.color == PieceColor.WHITE) theoreticalMovesWhite++ else theoreticalMovesBlack++
                node.analysis = node.analysis?.copy(isTheoretical = true)
            } else if (stillInUnbrokenTheory) {
                stillInUnbrokenTheory = false
                firstOutOfBookPly = node.ply
                firstOutOfBookMoveSan = "${node.moveNumber}${if (node.color == PieceColor.WHITE) "." else "..."} ${node.san}"
                node.analysis = node.analysis?.copy(isTheoretical = false, isFirstOutOfTheory = true)
            } else if (posMatch != null) {
                if (transposedBackToBookPly == null) {
                    transposedBackToBookPly = node.ply
                    transposedOpeningName = "${posMatch.name}: ${posMatch.variation}"
                }
                if (node.ply >= 3 && (posMatch.isExactEndpoint || bestNamedMatch.ply == 0)) {
                    bestNamedMatch = posMatch
                }
                node.analysis = node.analysis?.copy(isBookTransposition = true)
            }
        }

        for (node in pathNodes) {
            val a = node.analysis ?: continue
            val isLast = lastTheoreticalPly > 0 && node.ply == lastTheoreticalPly
            val isFirstOut = firstOutOfBookPly != null && node.ply == firstOutOfBookPly
            if (isLast || isFirstOut) {
                node.analysis = a.copy(
                    isLastTheoretical = isLast,
                    isFirstOutOfTheory = isFirstOut
                )
            }
        }

        return OpeningAnalysisSummary(
            eco = bestNamedMatch.eco,
            name = bestNamedMatch.name,
            variation = bestNamedMatch.variation,
            source = OpeningBookMeta.SOURCE,
            version = OpeningBookMeta.VERSION,
            theoreticalMovesWhite = theoreticalMovesWhite,
            theoreticalMovesBlack = theoreticalMovesBlack,
            lastTheoreticalPly = lastTheoreticalPly,
            lastTheoreticalMoveSan = lastTheoreticalMoveSan,
            firstOutOfBookPly = firstOutOfBookPly,
            firstOutOfBookMoveSan = firstOutOfBookMoveSan,
            transposedBackToBookPly = transposedBackToBookPly,
            transposedOpeningName = transposedOpeningName,
            mainBookContinuationSan = bestNamedMatch.continuationSan
        )
    }

    companion object {
        private val HEADER_REGEX = Regex("\\[([A-Za-z0-9_]+)\\s+\"([^\"]*)\"\\]")

        fun parsePgnHeaders(pgn: String): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            for (match in HEADER_REGEX.findAll(pgn)) {
                headers[match.groupValues[1]] = match.groupValues[2]
            }
            return headers
        }

        val INITIAL_ECO_ENTRIES: List<OpeningBookEntry> = listOf(
            // Open Games (1.e4 e5) & transpositions
            OpeningBookEntry("C40", "Дебют королевского коня", "2.Nf3", listOf("e4", "e5", "Nf3"), listOf("Nc6", "Bb5", "a6")),
            OpeningBookEntry("C44", "Дебют королевского коня", "Транспозиция 1.Nf3 Nc6 2.e4 e5", listOf("Nf3", "Nc6", "e4", "e5"), listOf("Bc4", "Bc5", "c3")),
            OpeningBookEntry("C23", "Дебют слона", "2.Bc4", listOf("e4", "e5", "Bc4"), listOf("Nf6", "d3", "Nc6")),
            OpeningBookEntry("C25", "Венская партия", "2.Nc3", listOf("e4", "e5", "Nc3"), listOf("Nf6", "f4", "d5")),
            OpeningBookEntry("C21", "Центральный дебют", "2.d4 exd4", listOf("e4", "e5", "d4", "exd4"), listOf("Qxd4", "Nc6", "Qe3")),
            OpeningBookEntry("C30", "Королевский гамбит", "2.f4", listOf("e4", "e5", "f4"), listOf("exf4", "Nf3", "g5", "Bc4")),
            OpeningBookEntry("C33", "Принятый королевский гамбит", "2.f4 exf4 3.Nf3", listOf("e4", "e5", "f4", "exf4", "Nf3"), listOf("g5", "h4", "g4", "Ne5")),
            OpeningBookEntry("C41", "Защита Филидора", "2.Nf3 d6", listOf("e4", "e5", "Nf3", "d6"), listOf("d4", "exd4", "Nxd4", "Nf6")),
            OpeningBookEntry("C41", "Защита Филидора", "Разменный вариант в центре", listOf("e4", "e5", "Nf3", "d6", "d4"), listOf("exd4", "Nxd4", "Nf6", "Nc3", "Be7")),
            OpeningBookEntry("C41", "Защита Филидора", "Вариант 3...Bg4 (Опера в Париже)", listOf("e4", "e5", "Nf3", "d6", "d4", "Bg4", "dxe5", "Bxf3", "Qxf3", "dxe5", "Bc4", "Nf6", "Qb3", "Qe7"), listOf("Nc3", "c6", "Bg5", "b5")),
            OpeningBookEntry("C42", "Русская партия (Защита Петрова)", "2.Nf3 Nf6", listOf("e4", "e5", "Nf3", "Nf6"), listOf("Nxe5", "d6", "Nf3", "Nxe4", "d4")),
            OpeningBookEntry("C42", "Русская партия (Защита Петрова)", "Классический вариант 3.Nxe5 d6 4.Nf3 Nxe4", listOf("e4", "e5", "Nf3", "Nf6", "Nxe5", "d6", "Nf3", "Nxe4"), listOf("d4", "d5", "Bd3", "Nc6")),
            OpeningBookEntry("C44", "Дебют королевского коня", "2.Nf3 Nc6", listOf("e4", "e5", "Nf3", "Nc6"), listOf("Bb5", "a6", "Ba4", "Nf6")),
            OpeningBookEntry("C45", "Шотландская партия", "3.d4 exd4 4.Nxd4", listOf("e4", "e5", "Nf3", "Nc6", "d4", "exd4", "Nxd4"), listOf("Bc5", "Be3", "Qf6", "c3", "Nge7")),
            OpeningBookEntry("C47", "Дебют четырёх коней", "3.Nc3 Nf6", listOf("e4", "e5", "Nf3", "Nc6", "Nc3", "Nf6"), listOf("Bb5", "Bb4", "O-O", "O-O")),
            OpeningBookEntry("C50", "Итальянская партия", "3.Bc4", listOf("e4", "e5", "Nf3", "Nc6", "Bc4"), listOf("Bc5", "c3", "Nf6", "d4")),
            OpeningBookEntry("C50", "Итальянская партия", "Giuoco Piano (3...Bc5)", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5"), listOf("c3", "Nf6", "d3", "d6")),
            OpeningBookEntry("C53", "Итальянская партия", "Классический центр 4.c3", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "c3"), listOf("Nf6", "d4", "exd4", "cxd4", "Bb4+")),
            OpeningBookEntry("C54", "Итальянская партия", "Главный вариант Giuoco Piano", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "c3", "Nf6", "d4", "exd4", "cxd4", "Bb4+"), listOf("Nc3", "Nxe4", "O-O")),
            OpeningBookEntry("C54", "Итальянская партия", "Giuoco Pianissimo (d3 и c3)", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "d3", "Nf6", "c3", "d6"), listOf("O-O", "a6", "Re1", "O-O")),
            OpeningBookEntry("C55", "Защита двух коней", "3.Bc4 Nf6", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6"), listOf("Ng5", "d5", "exd5", "Na5")),
            OpeningBookEntry("C57", "Защита двух коней", "Атака 4.Ng5 d5 5.exd5", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "Ng5", "d5", "exd5"), listOf("Na5", "Bb5+", "c6", "dxc6", "bxc6")),
            OpeningBookEntry("C60", "Испанская партия (Ruy Lopez)", "3.Bb5", listOf("e4", "e5", "Nf3", "Nc6", "Bb5"), listOf("a6", "Ba4", "Nf6", "O-O")),
            OpeningBookEntry("C65", "Испанская партия", "Берлинская защита (3...Nf6)", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "Nf6"), listOf("O-O", "Nxe4", "d4", "Nd6", "Bxc6")),
            OpeningBookEntry("C65", "Испанская партия", "Берлинская защита 4.O-O Nxe4", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "Nf6", "O-O", "Nxe4"), listOf("d4", "Nd6", "Bxc6", "dxc6", "dxe5")),
            OpeningBookEntry("C65", "Испанская партия", "Берлинская защита с 5.Re1 Nd6", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "Nf6", "O-O", "Nxe4", "Re1", "Nd6", "Nxe5", "Be7", "Bf1", "Nxe5", "Rxe5", "O-O"), listOf("d4", "Bf6", "Re1", "Re8")),
            OpeningBookEntry("C67", "Испанская партия", "Берлинский эндшпиль", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "Nf6", "O-O", "Nxe4", "d4", "Nd6", "Bxc6", "dxc6", "dxe5", "Nf5", "Qxd8+", "Kxd8"), listOf("Nc3", "Ke8", "h3", "h5")),
            OpeningBookEntry("C68", "Испанская партия", "Разменный вариант (4.Bxc6)", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Bxc6", "dxc6"), listOf("O-O", "f6", "d4", "exd4")),
            OpeningBookEntry("C70", "Испанская партия", "Защита Морфи (3...a6 4.Ba4)", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4"), listOf("Nf6", "O-O", "Be7", "Re1")),
            OpeningBookEntry("C78", "Испанская партия", "Главный вариант 4...Nf6 5.O-O", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O"), listOf("Be7", "Re1", "b5", "Bb3", "d6")),
            OpeningBookEntry("C84", "Испанская партия", "Закрытая система (5...Be7)", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7", "Re1", "b5", "Bb3", "d6", "c3", "O-O"), listOf("h3", "Na5", "Bc2", "c5", "d4")),
            OpeningBookEntry("C89", "Испанская партия", "Контратака Маршалла (8...d5)", listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7", "Re1", "b5", "Bb3", "O-O", "c3", "d5"), listOf("exd5", "Nxd5", "Nxe5", "Nxe5", "Rxe5", "c6")),
            // Semi-Open Games
            OpeningBookEntry("B22", "Сицилианская защита", "Вариант Алапина (2.c3)", listOf("e4", "c5", "c3"), listOf("Nf6", "e5", "Nd5", "d4", "cxd4")),
            OpeningBookEntry("B23", "Сицилианская защита", "Закрытая система (2.Nc3)", listOf("e4", "c5", "Nc3"), listOf("Nc6", "g3", "g6", "Bg2", "Bg7")),
            OpeningBookEntry("B27", "Сицилианская защита", "Открытая подготовка 2.Nf3", listOf("e4", "c5", "Nf3"), listOf("d6", "d4", "cxd4", "Nxd4", "Nf6")),
            OpeningBookEntry("B30", "Сицилианская защита", "Вариант 2.Nf3 Nc6", listOf("e4", "c5", "Nf3", "Nc6"), listOf("d4", "cxd4", "Nxd4", "Nf6", "Nc3")),
            OpeningBookEntry("B30", "Сицилианская защита", "Система Россолимо (3.Bb5)", listOf("e4", "c5", "Nf3", "Nc6", "Bb5"), listOf("g6", "Bxc6", "dxc6", "d3", "Bg7")),
            OpeningBookEntry("B33", "Сицилианская защита", "Челябинский вариант (Свешникова)", listOf("e4", "c5", "Nf3", "Nc6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "e5"), listOf("Ndb5", "d6", "Bg5", "a6", "Na3", "b5")),
            OpeningBookEntry("B40", "Сицилианская защита", "Вариант 2.Nf3 e6", listOf("e4", "c5", "Nf3", "e6"), listOf("d4", "cxd4", "Nxd4", "Nc6")),
            OpeningBookEntry("B50", "Сицилианская защита", "Классическая 2.Nf3 d6", listOf("e4", "c5", "Nf3", "d6"), listOf("d4", "cxd4", "Nxd4", "Nf6", "Nc3")),
            OpeningBookEntry("B54", "Сицилианская защита", "Открытый вариант (3.d4 cxd4 4.Nxd4)", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4"), listOf("Nf6", "Nc3", "a6", "Be3", "e5")),
            OpeningBookEntry("B70", "Сицилианская защита", "Вариант Дракона (5...g6)", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "g6"), listOf("Be3", "Bg7", "f3", "O-O", "Qd2", "Nc6")),
            OpeningBookEntry("B90", "Сицилианская защита", "Вариант Найдорфа (5...a6)", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6"), listOf("Be3", "e5", "Nb3", "Be6", "f3", "Be7")),
            OpeningBookEntry("C00", "Французская защита", "2.d4 d5", listOf("e4", "e6", "d4", "d5"), listOf("Nc3", "Nf6", "Bg5", "Be7", "e5")),
            OpeningBookEntry("C02", "Французская защита", "Вариант Нимцовича (3.e5)", listOf("e4", "e6", "d4", "d5", "e5"), listOf("c5", "c3", "Nc6", "Nf3", "Qb6")),
            OpeningBookEntry("C11", "Французская защита", "Классическая система (3.Nc3 Nf6)", listOf("e4", "e6", "d4", "d5", "Nc3", "Nf6"), listOf("e5", "Nfd7", "f4", "c5", "Nf3")),
            OpeningBookEntry("C18", "Французская защита", "Вариант Винавера (3.Nc3 Bb4)", listOf("e4", "e6", "d4", "d5", "Nc3", "Bb4"), listOf("e5", "c5", "a3", "Bxc3+", "bxc3")),
            OpeningBookEntry("B10", "Защита Каро-Канн", "2.d4 d5", listOf("e4", "c6", "d4", "d5"), listOf("Nc3", "dxe4", "Nxe4", "Bf5")),
            OpeningBookEntry("B12", "Защита Каро-Канн", "Закрытый вариант (3.e5)", listOf("e4", "c6", "d4", "d5", "e5", "Bf5"), listOf("Nf3", "e6", "Be2", "Nd7", "O-O")),
            OpeningBookEntry("B18", "Защита Каро-Канн", "Классический вариант (4...Bf5)", listOf("e4", "c6", "d4", "d5", "Nc3", "dxe4", "Nxe4", "Bf5"), listOf("Ng3", "Bg6", "h4", "h6", "Nf3")),
            OpeningBookEntry("B01", "Скандинавская защита", "2.exd5 Qxd5", listOf("e4", "d5", "exd5", "Qxd5"), listOf("Nc3", "Qa5", "d4", "Nf6")),
            OpeningBookEntry("B02", "Защита Алехина", "1...Nf6 2.e5 Nd5", listOf("e4", "Nf6", "e5", "Nd5"), listOf("d4", "d6", "Nf3", "Bg4")),
            OpeningBookEntry("B07", "Защита Пирца-Уфимцева", "2.d4 Nf6 3.Nc3 g6", listOf("e4", "d6", "d4", "Nf6", "Nc3", "g6"), listOf("Nf3", "Bg7", "Be2", "O-O", "O-O")),
            OpeningBookEntry("B07", "Защита Пирца-Уфимцева", "Система с 4.Be3 Bg7 5.Qd2", listOf("e4", "d6", "d4", "Nf6", "Nc3", "g6", "Be3", "Bg7", "Qd2", "c6", "f3", "b5"), listOf("Nge2", "Nbd7", "Bh6")),
            // Closed Games & Indian Defenses
            OpeningBookEntry("D00", "Дебют ферзевых пешек", "2.Nf3 Nf6", listOf("d4", "d5", "Nf3", "Nf6"), listOf("c4", "e6", "Nc3", "Be7")),
            OpeningBookEntry("D02", "Лондонская система", "2.Bf4", listOf("d4", "d5", "Bf4"), listOf("Nf6", "e3", "c5", "c3", "Nc6")),
            OpeningBookEntry("D06", "Ферзевый гамбит", "2.c4", listOf("d4", "d5", "c4"), listOf("e6", "Nc3", "Nf6", "Bg5")),
            OpeningBookEntry("D10", "Славянская защита", "2.c4 c6", listOf("d4", "d5", "c4", "c6"), listOf("Nf3", "Nf6", "Nc3", "dxc4")),
            OpeningBookEntry("D20", "Принятый ферзевый гамбит", "2.c4 dxc4", listOf("d4", "d5", "c4", "dxc4"), listOf("Nf3", "Nf6", "e3", "e6", "Bxc4")),
            OpeningBookEntry("D30", "Отказанный ферзевый гамбит", "2.c4 e6", listOf("d4", "d5", "c4", "e6"), listOf("Nc3", "Nf6", "Bg5", "Be7", "e3")),
            OpeningBookEntry("D35", "Отказанный ферзевый гамбит", "Транспозиция 1.d4 Nf6 2.c4 e6 3.Nc3 d5", listOf("d4", "Nf6", "c4", "e6", "Nc3", "d5"), listOf("cxd5", "exd5", "Bg5", "Be7")),
            OpeningBookEntry("D37", "Отказанный ферзевый гамбит", "Классическая система с 5.Bf4", listOf("d4", "d5", "c4", "e6", "Nc3", "Nf6", "Nf3", "Be7", "Bf4"), listOf("O-O", "e3", "c5", "dxc5")),
            OpeningBookEntry("A45", "Индийская защита", "2.c4", listOf("d4", "Nf6", "c4"), listOf("e6", "Nc3", "Bb4")),
            OpeningBookEntry("A80", "Голландская защита", "1.d4 f5 2.c4", listOf("d4", "f5", "c4"), listOf("Nf6", "g3", "e6", "Bg2")),
            OpeningBookEntry("D85", "Защита Грюнфельда", "3.Nc3 d5", listOf("d4", "Nf6", "c4", "g6", "Nc3", "d5"), listOf("cxd5", "Nxd5", "e4", "Nxc3", "bxc3")),
            OpeningBookEntry("E20", "Защита Нимцовича", "3.Nc3 Bb4", listOf("d4", "Nf6", "c4", "e6", "Nc3", "Bb4"), listOf("e3", "O-O", "Bd3", "d5", "Nf3")),
            OpeningBookEntry("E60", "Староиндийская защита", "2.c4 g6 3.Nc3 Bg7", listOf("d4", "Nf6", "c4", "g6", "Nc3", "Bg7"), listOf("e4", "d6", "Nf3", "O-O", "Be2")),
            OpeningBookEntry("E97", "Староиндийская защита", "Вариант Мар-дель-Плата", listOf("d4", "Nf6", "c4", "g6", "Nc3", "Bg7", "e4", "d6", "Nf3", "O-O", "Be2", "e5", "O-O", "Nc6", "d5", "Ne7"), listOf("Ne1", "Nd7", "Be3", "f5", "f3")),
            OpeningBookEntry("A10", "Английское начало", "1.c4 e5 2.Nc3", listOf("c4", "e5", "Nc3"), listOf("Nf6", "Nf3", "Nc6", "g3")),
            OpeningBookEntry("A04", "Дебют Рети", "1.Nf3 d5 2.g3", listOf("Nf3", "d5", "g3"), listOf("Nf6", "Bg2", "c6", "O-O"))
        )

        val EXTENDED_ECO_ENTRIES: List<OpeningBookEntry> = listOf(
            OpeningBookEntry("B00", "Королевское пешечное начало", "1.e4", listOf("e4")),
            OpeningBookEntry("C20", "Открытые дебюты", "1.e4 e5", listOf("e4", "e5")),
            OpeningBookEntry("B20", "Сицилианская защита", "1.e4 c5", listOf("e4", "c5")),
            OpeningBookEntry("B21", "Сицилианская защита", "Гамбит Морра (2.d4 cxd4 3.c3)", listOf("e4", "c5", "d4", "cxd4", "c3")),
            OpeningBookEntry("B21", "Сицилианская защита", "Атака Гран-При (2.f4)", listOf("e4", "c5", "f4")),
            OpeningBookEntry("B32", "Сицилианская защита", "Вариант Лабурдонне-Лёвенталя (4...e5)", listOf("e4", "c5", "Nf3", "Nc6", "d4", "cxd4", "Nxd4", "e5")),
            OpeningBookEntry("B35", "Сицилианская защита", "Ускоренный дракон (4...g6)", listOf("e4", "c5", "Nf3", "Nc6", "d4", "cxd4", "Nxd4", "g6")),
            OpeningBookEntry("B42", "Сицилианская защита", "Вариант Кана (4...a6)", listOf("e4", "c5", "Nf3", "e6", "d4", "cxd4", "Nxd4", "a6")),
            OpeningBookEntry("B45", "Сицилианская защита", "Вариант Тайманова (4...Nc6)", listOf("e4", "c5", "Nf3", "e6", "d4", "cxd4", "Nxd4", "Nc6")),
            OpeningBookEntry("B52", "Сицилианская защита", "Московский вариант (3.Bb5+)", listOf("e4", "c5", "Nf3", "d6", "Bb5+")),
            OpeningBookEntry("B80", "Сицилианская защита", "Схевенингенский вариант (5...e6)", listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "e6")),
            OpeningBookEntry("C00", "Французская защита", "1.e4 e6", listOf("e4", "e6")),
            OpeningBookEntry("C01", "Французская защита", "Разменный вариант (3.exd5 exd5)", listOf("e4", "e6", "d4", "d5", "exd5", "exd5")),
            OpeningBookEntry("C03", "Французская защита", "Система Тарраша (3.Nd2)", listOf("e4", "e6", "d4", "d5", "Nd2")),
            OpeningBookEntry("B10", "Защита Каро-Канн", "1.e4 c6", listOf("e4", "c6")),
            OpeningBookEntry("B10", "Защита Каро-Канн", "Вариант двух коней (2.Nf3 d5 3.Nc3)", listOf("e4", "c6", "Nf3", "d5", "Nc3")),
            OpeningBookEntry("B13", "Защита Каро-Канн", "Разменный вариант (3.exd5 cxd5)", listOf("e4", "c6", "d4", "d5", "exd5", "cxd5")),
            OpeningBookEntry("B14", "Защита Каро-Канн", "Атака Панова-Ботвинника (4.c4)", listOf("e4", "c6", "d4", "d5", "exd5", "cxd5", "c4")),
            OpeningBookEntry("B15", "Защита Каро-Канн", "Система 3.Nc3 dxe4 4.Nxe4 Nf6", listOf("e4", "c6", "d4", "d5", "Nc3", "dxe4", "Nxe4", "Nf6")),
            OpeningBookEntry("B01", "Скандинавская защита", "1.e4 d5", listOf("e4", "d5")),
            OpeningBookEntry("B01", "Скандинавская защита", "Вариант 2...Nf6", listOf("e4", "d5", "exd5", "Nf6")),
            OpeningBookEntry("B06", "Современная защита (Модерн)", "1.e4 g6 2.d4 Bg7", listOf("e4", "g6", "d4", "Bg7")),
            OpeningBookEntry("B07", "Защита Пирца-Уфимцева", "1.e4 d6 2.d4 Nf6", listOf("e4", "d6", "d4", "Nf6")),
            OpeningBookEntry("B00", "Защита Нимцовича", "1.e4 Nc6", listOf("e4", "Nc6")),
            OpeningBookEntry("B00", "Защита Оуэна", "1.e4 b6", listOf("e4", "b6")),
            OpeningBookEntry("C22", "Центральный дебют", "3.Qxd4 Nc6", listOf("e4", "e5", "d4", "exd4", "Qxd4", "Nc6")),
            OpeningBookEntry("C21", "Северный гамбит", "2.d4 exd4 3.c3", listOf("e4", "e5", "d4", "exd4", "c3")),
            OpeningBookEntry("C44", "Гамбит Понциани", "3.c3", listOf("e4", "e5", "Nf3", "Nc6", "c3")),
            OpeningBookEntry("C51", "Гамбит Эванса", "4.b4", listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "b4")),
            OpeningBookEntry("A40", "Ферзевое пешечное начало", "1.d4", listOf("d4")),
            OpeningBookEntry("D00", "Закрытые дебюты", "1.d4 d5", listOf("d4", "d5")),
            OpeningBookEntry("A45", "Индийская защита", "1.d4 Nf6", listOf("d4", "Nf6")),
            OpeningBookEntry("A45", "Атака Тромповского", "1.d4 Nf6 2.Bg5", listOf("d4", "Nf6", "Bg5")),
            OpeningBookEntry("A46", "Атака Торре", "1.d4 Nf6 2.Nf3 e6 3.Bg5", listOf("d4", "Nf6", "Nf3", "e6", "Bg5")),
            OpeningBookEntry("A48", "Лондонская система (против 1...Nf6)", "1.d4 Nf6 2.Bf4", listOf("d4", "Nf6", "Bf4")),
            OpeningBookEntry("D02", "Лондонская система", "1.d4 d5 2.Nf3 Nf6 3.Bf4", listOf("d4", "d5", "Nf3", "Nf6", "Bf4")),
            OpeningBookEntry("D04", "Система Колле", "1.d4 d5 2.Nf3 Nf6 3.e3", listOf("d4", "d5", "Nf3", "Nf6", "e3")),
            OpeningBookEntry("D00", "Гамбит Блэкмара-Димера", "1.d4 d5 2.e4 dxe4 3.Nc3", listOf("d4", "d5", "e4", "dxe4", "Nc3")),
            OpeningBookEntry("D43", "Славяно-меранская защита (Полуславянская)", "4...e6", listOf("d4", "d5", "c4", "c6", "Nf3", "Nf6", "Nc3", "e6")),
            OpeningBookEntry("E00", "Каталонское начало", "1.d4 Nf6 2.c4 e6 3.g3", listOf("d4", "Nf6", "c4", "e6", "g3")),
            OpeningBookEntry("E04", "Каталонское начало", "Открытый вариант (4.Bg2 dxc4)", listOf("d4", "Nf6", "c4", "e6", "g3", "d5", "Bg2", "dxc4")),
            OpeningBookEntry("E11", "Защита Боголюбова", "3.Nf3 Bb4+", listOf("d4", "Nf6", "c4", "e6", "Nf3", "Bb4+")),
            OpeningBookEntry("E12", "Новоиндийская защита", "3.Nf3 b6", listOf("d4", "Nf6", "c4", "e6", "Nf3", "b6")),
            OpeningBookEntry("A56", "Защита Бенони", "1.d4 Nf6 2.c4 c5", listOf("d4", "Nf6", "c4", "c5")),
            OpeningBookEntry("A57", "Волжский гамбит (Бенко)", "3.d5 b5", listOf("d4", "Nf6", "c4", "c5", "d5", "b5")),
            OpeningBookEntry("A51", "Будапештский гамбит", "1.d4 Nf6 2.c4 e5", listOf("d4", "Nf6", "c4", "e5")),
            OpeningBookEntry("A10", "Английское начало", "1.c4", listOf("c4")),
            OpeningBookEntry("A20", "Английское начало", "1.c4 e5", listOf("c4", "e5")),
            OpeningBookEntry("A30", "Английское начало", "Симметричный вариант (1.c4 c5)", listOf("c4", "c5")),
            OpeningBookEntry("A15", "Английское начало", "Англо-индийская система (1.c4 Nf6)", listOf("c4", "Nf6")),
            OpeningBookEntry("A04", "Дебют Рети", "1.Nf3", listOf("Nf3")),
            OpeningBookEntry("A05", "Дебют Рети / Староиндийское начало", "1.Nf3 Nf6", listOf("Nf3", "Nf6")),
            OpeningBookEntry("A07", "Староиндийская атака", "1.Nf3 d5 2.g3 Nf6 3.Bg2", listOf("Nf3", "d5", "g3", "Nf6", "Bg2")),
            OpeningBookEntry("A02", "Дебют Бёрда", "1.f4", listOf("f4")),
            OpeningBookEntry("A01", "Дебют Ларсена", "1.b3", listOf("b3"))
        )
    }
}
