package com.example.core.analysis

import com.example.domain.model.GameResult
import com.example.domain.model.GameSummary
import com.example.domain.model.MoveClassification
import com.example.domain.model.PieceColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt

object RepertoireConstants {
    const val REPERTOIRE_ALGORITHM_VERSION = "repertoire_v2.1"
    const val MAX_REPERTOIRE_OPENING_PLY = 24
    const val UNDEFINED_OPENING_NAME = "Неопределённый дебют"
}

@Singleton
class OpeningAnalyzer @Inject constructor(
    private val openingBook: OpeningBookService
) {

    fun extractPlayerMetricsFromAnalyses(
        analyses: List<MoveAnalysis>?,
        playerColor: PieceColor
    ): Pair<Double?, Int?> {
        if (analyses.isNullOrEmpty()) return null to null
        val playerMoves = analyses.filter { it.player == playerColor }
        if (playerMoves.isEmpty()) return null to null
        val stats = AccuracyCalculator.calculateGameStatistics(analyses)
        val accuracy = if (playerColor == PieceColor.WHITE) stats.whiteAccuracy else stats.blackAccuracy
        val avgCpl = if (playerColor == PieceColor.WHITE) stats.whiteAvgCpl else stats.blackAvgCpl
        return accuracy to avgCpl
    }

    fun analyzeGameOpeningForRepertoire(
        game: RepertoireSourceGame,
        existingMoveAnalyses: List<MoveAnalysis>? = null
    ): OpeningAnalysisCacheRecord {
        val headers = OpeningBookService.parsePgnHeaders(game.pgn)
        val initialFen = headers["FEN"]?.takeIf { it.isNotBlank() } ?: VariationTree.DEFAULT_START_FEN
        val root = VariationTree.parsePgnMovetextToTree(game.pgn, initialFen)
        val mainlineNodes = VariationTree.getMainlineNodes(root)
        val moveNodes = mainlineNodes.filter { it.ply > 0 }

        val openingSteps = mutableListOf<OpeningMoveStep>()
        var bestEco = "A00"
        var bestName = RepertoireConstants.UNDEFINED_OPENING_NAME
        var bestVariation = "Вне известной теории"
        var bestPly = 0
        var bestIsExact = false

        var theoryDepthPly = 0
        var firstOutOfBookPly: Int? = null
        var firstOutOfBookMoveSan: String? = null
        var stillUnbrokenTheory = true
        var transposed = false

        val maxPliesToInspect = minOf(moveNodes.size, RepertoireConstants.MAX_REPERTOIRE_OPENING_PLY)

        for (i in 0 until maxPliesToInspect) {
            val node = moveNodes[i]
            val fenBefore = if (i == 0) initialFen else moveNodes[i - 1].fen
            val fenAfter = node.fen
            val positionKey = normalizeLegalEpFenKey(fenAfter)

            val posTheory = openingBook.isPositionInTheory(fenAfter)
            val edgeInBook = openingBook.isBookMove(fenBefore, node.san, fenAfter) ||
                openingBook.isBookMove(fenBefore, node.uci, fenAfter)

            var stepIsTheoretical = false

            if (stillUnbrokenTheory && posTheory != null && edgeInBook) {
                stepIsTheoretical = true
                theoryDepthPly = node.ply
                if (posTheory.name != "Начальные ходы партии" &&
                    (posTheory.isExactEndpoint || bestPly == 0 || node.ply >= bestPly)
                ) {
                    bestEco = posTheory.eco
                    bestName = posTheory.name
                    bestVariation = posTheory.variation
                    bestPly = node.ply
                    bestIsExact = posTheory.isExactEndpoint
                }
            } else if (stillUnbrokenTheory) {
                stillUnbrokenTheory = false
                firstOutOfBookPly = node.ply
                firstOutOfBookMoveSan = "${node.moveNumber}${if (node.color == PieceColor.WHITE) "." else "..."} ${node.san}"
            } else if (posTheory != null && posTheory.name != "Начальные ходы партии") {
                transposed = true
                stepIsTheoretical = true
                theoryDepthPly = maxOf(theoryDepthPly, node.ply)
                if (posTheory.isExactEndpoint || bestPly == 0 || node.ply > bestPly) {
                    bestEco = posTheory.eco
                    bestName = posTheory.name
                    bestVariation = posTheory.variation
                    bestPly = node.ply
                    bestIsExact = posTheory.isExactEndpoint
                }
            }

            openingSteps.add(
                OpeningMoveStep(
                    ply = node.ply,
                    moveNumber = node.moveNumber,
                    color = node.color,
                    san = node.san,
                    uci = node.uci,
                    fenBefore = fenBefore,
                    fenAfter = fenAfter,
                    positionKey = positionKey,
                    isTheoretical = stepIsTheoretical,
                    ecoAtStep = if (bestPly > 0) bestEco else null,
                    openingNameAtStep = if (bestPly > 0) bestName else null,
                    variationAtStep = if (bestPly > 0) bestVariation else null
                )
            )
        }

        val isUndefinedOpening = bestPly < 1
        val normalizedMovesKey = openingSteps.joinToString(" ") { it.uci }
        val analysesToUse = existingMoveAnalyses ?: game.precomputedAnalyses
        val (playerAccuracy, playerAvgCpl) = extractPlayerMetricsFromAnalyses(analysesToUse, game.playerColor)
        val hasEngineAnalysis = !analysesToUse.isNullOrEmpty()

        val status = when {
            !game.hasValidPgn || moveNodes.isEmpty() -> OpeningCacheStatus.ERROR
            hasEngineAnalysis -> OpeningCacheStatus.ENGINE_ANALYZED
            else -> OpeningCacheStatus.BASIC
        }

        return OpeningAnalysisCacheRecord(
            id = "${game.id}_${game.playerColor.name}",
            gameId = game.id,
            source = game.source,
            pgnHash = game.pgnHash,
            normalizedMovesKey = normalizedMovesKey,
            playerUsername = game.playerUsername,
            playerColor = game.playerColor,
            opponentName = game.opponentName,
            playerRating = game.playerRating,
            opponentRating = game.opponentRating,
            whiteName = game.whiteName,
            blackName = game.blackName,
            outcome = game.outcome,
            result = game.result,
            timeClass = game.timeClass,
            timeControl = game.timeControl,
            timestamp = game.timestamp,
            dateStr = game.dateStr,
            eco = if (isUndefinedOpening) "A00" else bestEco,
            openingName = if (isUndefinedOpening) RepertoireConstants.UNDEFINED_OPENING_NAME else bestName,
            variationName = if (isUndefinedOpening) "Вне известной теории" else bestVariation,
            isUndefinedOpening = isUndefinedOpening,
            theoryDepthPly = theoryDepthPly,
            firstOutOfBookPly = firstOutOfBookPly,
            firstOutOfBookMoveSan = firstOutOfBookMoveSan,
            transposed = transposed,
            totalPlies = moveNodes.size,
            totalMoves = ceil(moveNodes.size / 2.0).toInt(),
            openingMoves = openingSteps,
            moveAnalyses = analysesToUse,
            playerAccuracy = playerAccuracy,
            playerAvgCpl = playerAvgCpl,
            algorithmVersion = RepertoireConstants.REPERTOIRE_ALGORITHM_VERSION,
            analyzedAt = System.currentTimeMillis(),
            status = status,
            pgn = game.pgn
        )
    }

    fun hasValidMainlineMoves(pgn: String): Boolean {
        if (pgn.isBlank()) return false
        return try {
            val headers = OpeningBookService.parsePgnHeaders(pgn)
            val initialFen = headers["FEN"]?.takeIf { it.isNotBlank() } ?: VariationTree.DEFAULT_START_FEN
            val root = VariationTree.parsePgnMovetextToTree(pgn, initialFen)
            root.children.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}

object PlayerGameSelectionService {

    private val PGN_HEADER_LINE_REGEX = Regex("^\\s*\\[[A-Za-z0-9_]+\\s+\"[^\"]*\"\\]\\s*$")
    private val TIME_CONTROL_NUMERIC_REGEX = Regex("^(\\d+)(?:\\+(\\d+))?")
    private val DATE_TAG_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIME_TAG_REGEX = Regex("^\\d{2}:\\d{2}:\\d{2}$")

    fun splitMultiGamePgn(rawPgnText: String): List<String> {
        val trimmed = rawPgnText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val lines = trimmed.split(Regex("\\r?\\n"))
        val games = mutableListOf<String>()
        val currentLines = mutableListOf<String>()
        var seenMovesInCurrent = false

        for (line in lines) {
            val isHeader = PGN_HEADER_LINE_REGEX.matches(line)
            if (isHeader && seenMovesInCurrent) {
                val joined = currentLines.joinToString("\n").trim()
                if (joined.isNotEmpty()) games.add(joined)
                currentLines.clear()
                currentLines.add(line)
                seenMovesInCurrent = false
            } else {
                currentLines.add(line)
                val t = line.trim()
                if (!isHeader && t.isNotEmpty() && !t.startsWith("%")) {
                    seenMovesInCurrent = true
                }
            }
        }
        if (currentLines.isNotEmpty()) {
            val joined = currentLines.joinToString("\n").trim()
            if (joined.isNotEmpty()) games.add(joined)
        }
        return games
    }

    fun classifyTimeControl(timeControl: String?, explicitTimeClass: String? = null): RepertoireTimeClass {
        val tcLower = (explicitTimeClass ?: "").trim().lowercase(Locale.ROOT)
        when (tcLower) {
            "bullet" -> return RepertoireTimeClass.BULLET
            "blitz" -> return RepertoireTimeClass.BLITZ
            "rapid" -> return RepertoireTimeClass.RAPID
            "daily", "classical", "standard" -> return RepertoireTimeClass.CLASSICAL
        }

        val raw = (timeControl ?: "").trim().lowercase(Locale.ROOT)
        if (raw.isEmpty() || raw == "-" || "классика" in raw || "classical" in raw || "unlimited" in raw || "∞" in raw) {
            return RepertoireTimeClass.CLASSICAL
        }
        if ("bullet" in raw || "пуля" in raw) return RepertoireTimeClass.BULLET
        if ("blitz" in raw || "блиц" in raw) return RepertoireTimeClass.BLITZ
        if ("rapid" in raw || "рапид" in raw) return RepertoireTimeClass.RAPID
        if ("/" in raw) return RepertoireTimeClass.CLASSICAL

        val match = TIME_CONTROL_NUMERIC_REGEX.find(raw)
        if (match != null) {
            val firstNum = match.groupValues[1].toIntOrNull() ?: 600
            val incSec = match.groupValues[2].toIntOrNull() ?: 0
            // Support both Chess.com seconds ("180+2") and ChessLAN minutes+inc ("10+0")
            val baseSec = if (firstNum <= 60 && "+" in raw) firstNum * 60 else firstNum
            val estimatedTotalSec = baseSec + 40 * incSec
            return when {
                estimatedTotalSec < 180 -> RepertoireTimeClass.BULLET
                estimatedTotalSec < 600 -> RepertoireTimeClass.BLITZ
                estimatedTotalSec <= 1800 -> RepertoireTimeClass.RAPID
                else -> RepertoireTimeClass.CLASSICAL
            }
        }
        return RepertoireTimeClass.RAPID
    }

    fun parsePgnDateAndTime(
        dateHeader: String? = null,
        utcDateHeader: String? = null,
        utcTimeHeader: String? = null,
        fallbackEpochSec: Long? = null
    ): Pair<String, Long> {
        val utcFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        if (fallbackEpochSec != null && fallbackEpochSec > 0L) {
            val ms = fallbackEpochSec * 1000L
            return utcFmt.format(Date(ms)) to ms
        }
        val rawDate = (utcDateHeader ?: dateHeader ?: "").trim().replace('.', '-')
        if (DATE_TAG_REGEX.matches(rawDate) && '?' !in rawDate) {
            val timePart = utcTimeHeader?.trim()?.takeIf { TIME_TAG_REGEX.matches(it) } ?: "12:00:00"
            val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsedMs = try {
                isoFmt.parse("${rawDate}T$timePart")?.time
            } catch (_: Exception) {
                null
            }
            if (parsedMs != null) {
                return rawDate to parsedMs
            }
        }
        val now = System.currentTimeMillis()
        return utcFmt.format(Date(now)) to now
    }

    fun inferPrimaryPlayerFromGames(games: List<Pair<String, String>>): String {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for ((w, b) in games) {
            for (name in listOf(w, b)) {
                val clean = name.trim()
                if (clean.isEmpty() || clean == "Белые" || clean == "Чёрные" || clean == "?") continue
                val key = clean.lowercase(Locale.ROOT)
                val prev = counts[key]
                counts[key] = if (prev != null) prev.first to (prev.second + 1) else clean to 1
            }
        }
        return counts.values.maxByOrNull { it.second }?.first ?: ""
    }

    fun resolvePlayerColorAndOutcome(
        whiteName: String,
        blackName: String,
        result: String,
        targetUsername: String
    ): Pair<PieceColor, RepertoireOutcome> {
        val target = targetUsername.trim().lowercase(Locale.ROOT)
        val wLower = whiteName.trim().lowercase(Locale.ROOT)
        val bLower = blackName.trim().lowercase(Locale.ROOT)

        val playerColor = if (target.isNotEmpty() && (bLower == target || (target in bLower && wLower != target))) {
            PieceColor.BLACK
        } else {
            PieceColor.WHITE
        }

        val outcome = when (result) {
            "1-0" -> if (playerColor == PieceColor.WHITE) RepertoireOutcome.WIN else RepertoireOutcome.LOSS
            "0-1" -> if (playerColor == PieceColor.BLACK) RepertoireOutcome.WIN else RepertoireOutcome.LOSS
            else -> RepertoireOutcome.DRAW
        }
        return playerColor to outcome
    }

    fun convertChessComGamesToSourceGames(
        items: List<ChessComGameItem>,
        username: String,
        openingAnalyzer: OpeningAnalyzer
    ): List<RepertoireSourceGame> {
        val result = mutableListOf<RepertoireSourceGame>()
        val seenIds = HashSet<String>()

        for (item in items) {
            val pgn = (item.pgn ?: "").trim()
            val mapped = ChessComApiService.mapResult(item, username)
            val id = when {
                !item.uuid.isNullOrBlank() -> "chesscom_${item.uuid}"
                item.url.isNotBlank() -> "chesscom_${PgnService.computeStableHash(item.url.lowercase(Locale.ROOT))}"
                else -> "chesscom_${PgnService.computeStableHash(pgn.ifEmpty { item.endTime.toString() })}"
            }
            if (!seenIds.add(id)) continue

            val (dateStr, timestamp) = parsePgnDateAndTime(fallbackEpochSec = item.endTime)
            val hasValid = pgn.isNotEmpty() && openingAnalyzer.hasValidMainlineMoves(pgn)
            val playerSide = if (mapped.userColor == PieceColor.WHITE) item.white else item.black
            val oppSide = if (mapped.userColor == PieceColor.WHITE) item.black else item.white

            result.add(
                RepertoireSourceGame(
                    id = id,
                    source = RepertoireSourceType.CHESS_COM,
                    pgn = pgn,
                    pgnHash = PgnService.computeStableHash(pgn),
                    whiteName = item.white.username,
                    blackName = item.black.username,
                    whiteRating = item.white.rating,
                    blackRating = item.black.rating,
                    playerUsername = playerSide.username,
                    playerColor = mapped.userColor,
                    playerRating = playerSide.rating,
                    opponentName = oppSide.username,
                    opponentRating = oppSide.rating,
                    result = mapped.resultString,
                    outcome = mapped.outcome,
                    timeClass = classifyTimeControl(item.timeControl, item.timeClass),
                    timeControl = item.timeControl,
                    timestamp = timestamp,
                    dateStr = dateStr,
                    hasValidPgn = hasValid,
                    siteUrl = item.url
                )
            )
        }
        return result.sortedByDescending { it.timestamp }
    }

    fun convertPgnTextToSourceGames(
        rawPgnText: String,
        preferredUsername: String? = null
    ): Pair<List<RepertoireSourceGame>, String> {
        val chunks = splitMultiGamePgn(rawPgnText)
        val parsedItems = chunks.map { chunk ->
            val headers = OpeningBookService.parsePgnHeaders(chunk)
            val parsed = try {
                PgnService.parsePgnToTree(chunk, GamePlatform.PGN)
            } catch (_: Exception) {
                null
            }
            Triple(chunk, headers, parsed)
        }

        val inferredUsername = preferredUsername?.trim()?.takeIf { it.isNotEmpty() }
            ?: inferPrimaryPlayerFromGames(
                parsedItems.map { (_, headers, parsed) ->
                    (parsed?.metadata?.whiteName ?: headers["White"] ?: "Белые") to
                        (parsed?.metadata?.blackName ?: headers["Black"] ?: "Чёрные")
                }
            ).ifEmpty { "Белые" }

        val result = mutableListOf<RepertoireSourceGame>()
        val seenIds = HashSet<String>()
        val batchFallbackSec = System.currentTimeMillis() / 1000L

        for ((chunk, headers, parsed) in parsedItems) {
            val whiteName = parsed?.metadata?.whiteName ?: headers["White"] ?: "Белые"
            val blackName = parsed?.metadata?.blackName ?: headers["Black"] ?: "Чёрные"
            val resStr = parsed?.metadata?.result ?: headers["Result"] ?: "*"
            val (playerColor, outcome) = resolvePlayerColorAndOutcome(whiteName, blackName, resStr, inferredUsername)
            val id = parsed?.metadata?.id ?: "pgn_${PgnService.computeStableHash(chunk)}"
            if (!seenIds.add(id)) continue

            val hasExplicitDate = !headers["Date"].isNullOrBlank() || !headers["UTCDate"].isNullOrBlank()
            val (dateStr, timestamp) = parsePgnDateAndTime(
                dateHeader = headers["Date"],
                utcDateHeader = headers["UTCDate"],
                utcTimeHeader = headers["UTCTime"],
                fallbackEpochSec = if (hasExplicitDate) null else batchFallbackSec
            )

            result.add(
                RepertoireSourceGame(
                    id = id,
                    source = RepertoireSourceType.PGN,
                    pgn = chunk,
                    pgnHash = PgnService.computeStableHash(chunk),
                    whiteName = whiteName,
                    blackName = blackName,
                    whiteRating = parsed?.metadata?.whiteRating,
                    blackRating = parsed?.metadata?.blackRating,
                    playerUsername = if (playerColor == PieceColor.WHITE) whiteName else blackName,
                    playerColor = playerColor,
                    playerRating = if (playerColor == PieceColor.WHITE) parsed?.metadata?.whiteRating else parsed?.metadata?.blackRating,
                    opponentName = if (playerColor == PieceColor.WHITE) blackName else whiteName,
                    opponentRating = if (playerColor == PieceColor.WHITE) parsed?.metadata?.blackRating else parsed?.metadata?.whiteRating,
                    result = resStr,
                    outcome = outcome,
                    timeClass = classifyTimeControl(headers["TimeControl"], headers["Event"]),
                    timeControl = headers["TimeControl"] ?: "Классика",
                    timestamp = timestamp,
                    dateStr = dateStr,
                    hasValidPgn = (parsed?.root?.children?.isNotEmpty() == true),
                    siteUrl = headers["Link"] ?: headers["Site"]
                )
            )
        }
        return result.sortedByDescending { it.timestamp } to inferredUsername
    }

    fun convertSavedRecordsToSourceGames(
        analyzerRecords: List<SavedAnalyzerGameRecord>,
        roomGames: List<GameSummary>,
        preferredUsername: String? = null,
        openingAnalyzer: OpeningAnalyzer
    ): Pair<List<RepertoireSourceGame>, String> {
        val namePairs = analyzerRecords.map { it.metadata.whiteName to it.metadata.blackName } +
            roomGames.map { it.whitePlayer to it.blackPlayer }

        val inferredUsername = preferredUsername?.trim()?.takeIf { it.isNotEmpty() }
            ?: inferPrimaryPlayerFromGames(namePairs).ifEmpty { "Белые" }

        val result = mutableListOf<RepertoireSourceGame>()
        val seenIds = HashSet<String>()

        for (rec in analyzerRecords) {
            if (!seenIds.add(rec.id)) continue
            val (playerColor, outcome) = resolvePlayerColorAndOutcome(
                rec.metadata.whiteName,
                rec.metadata.blackName,
                rec.metadata.result,
                inferredUsername
            )
            val mainline = VariationTree.getMainlineNodes(rec.rootNode).filter { it.ply > 0 }
            val precomputed = mainline.mapNotNull { it.analysis }.takeIf { it.isNotEmpty() }
            val (dateStr, timestamp) = parsePgnDateAndTime(
                dateHeader = rec.metadata.date,
                fallbackEpochSec = (rec.updatedAt / 1000L).coerceAtLeast(1L)
            )

            result.add(
                RepertoireSourceGame(
                    id = rec.id,
                    source = RepertoireSourceType.SAVED,
                    pgn = rec.pgn,
                    pgnHash = PgnService.computeStableHash(rec.pgn),
                    whiteName = rec.metadata.whiteName,
                    blackName = rec.metadata.blackName,
                    whiteRating = rec.metadata.whiteRating,
                    blackRating = rec.metadata.blackRating,
                    playerUsername = if (playerColor == PieceColor.WHITE) rec.metadata.whiteName else rec.metadata.blackName,
                    playerColor = playerColor,
                    playerRating = if (playerColor == PieceColor.WHITE) rec.metadata.whiteRating else rec.metadata.blackRating,
                    opponentName = if (playerColor == PieceColor.WHITE) rec.metadata.blackName else rec.metadata.whiteName,
                    opponentRating = if (playerColor == PieceColor.WHITE) rec.metadata.blackRating else rec.metadata.whiteRating,
                    result = rec.metadata.result,
                    outcome = outcome,
                    timeClass = classifyTimeControl(rec.metadata.timeControl, rec.metadata.timeClass),
                    timeControl = rec.metadata.timeControl ?: "Классика",
                    timestamp = timestamp,
                    dateStr = dateStr,
                    hasValidPgn = mainline.isNotEmpty(),
                    siteUrl = rec.metadata.site,
                    precomputedAnalyses = precomputed
                )
            )
        }

        for (g in roomGames) {
            val id = "room_${g.gameUuid}"
            if (!seenIds.add(id)) continue
            val resStr = when (g.result) {
                GameResult.WHITE_WIN -> "1-0"
                GameResult.BLACK_WIN -> "0-1"
                GameResult.DRAW -> "1/2-1/2"
                else -> "*"
            }
            val (playerColor, outcome) = resolvePlayerColorAndOutcome(
                g.whitePlayer,
                g.blackPlayer,
                resStr,
                inferredUsername
            )
            val tsSec = ((g.finishedAt ?: g.createdAt) / 1000L).coerceAtLeast(1L)
            val (dateStr, timestamp) = parsePgnDateAndTime(fallbackEpochSec = tsSec)
            val hasValid = openingAnalyzer.hasValidMainlineMoves(g.pgn)

            result.add(
                RepertoireSourceGame(
                    id = id,
                    source = RepertoireSourceType.SAVED,
                    pgn = g.pgn,
                    pgnHash = PgnService.computeStableHash(g.pgn),
                    whiteName = g.whitePlayer,
                    blackName = g.blackPlayer,
                    playerUsername = if (playerColor == PieceColor.WHITE) g.whitePlayer else g.blackPlayer,
                    playerColor = playerColor,
                    opponentName = if (playerColor == PieceColor.WHITE) g.blackPlayer else g.whitePlayer,
                    result = resStr,
                    outcome = outcome,
                    timeClass = classifyTimeControl(g.timeControlLabel, null),
                    timeControl = g.timeControlLabel,
                    timestamp = timestamp,
                    dateStr = dateStr,
                    hasValidPgn = hasValid
                )
            )
        }

        return result.sortedByDescending { it.timestamp } to inferredUsername
    }

    fun rebindPlayerPerspective(
        games: List<RepertoireSourceGame>,
        targetUsername: String
    ): List<RepertoireSourceGame> {
        if (targetUsername.isBlank()) return games
        return games.map { g ->
            val (playerColor, outcome) = resolvePlayerColorAndOutcome(
                g.whiteName,
                g.blackName,
                g.result,
                targetUsername
            )
            g.copy(
                playerUsername = if (playerColor == PieceColor.WHITE) g.whiteName else g.blackName,
                playerColor = playerColor,
                playerRating = if (playerColor == PieceColor.WHITE) g.whiteRating else g.blackRating,
                opponentName = if (playerColor == PieceColor.WHITE) g.blackName else g.whiteName,
                opponentRating = if (playerColor == PieceColor.WHITE) g.blackRating else g.whiteRating,
                outcome = outcome
            )
        }
    }

    fun filterAndPreviewGames(
        pool: List<RepertoireSourceGame>,
        filters: RepertoireFilterConfig,
        cacheMap: Map<String, OpeningAnalysisCacheRecord>
    ): Pair<List<RepertoireSourceGame>, SelectionPreviewSummary> {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        var incompleteOrMissingCount = 0
        val matching = mutableListOf<RepertoireSourceGame>()

        for (game in pool) {
            if (!game.hasValidPgn) {
                incompleteOrMissingCount++
                if (filters.excludeIncompletePgn) continue
            }

            when (filters.periodPreset) {
                RepertoirePeriodPreset.DAYS_7 -> if (now - game.timestamp > 7 * dayMs) continue
                RepertoirePeriodPreset.DAYS_30 -> if (now - game.timestamp > 30 * dayMs) continue
                RepertoirePeriodPreset.DAYS_90 -> if (now - game.timestamp > 90 * dayMs) continue
                RepertoirePeriodPreset.MONTH -> {
                    if (filters.selectedMonth != "all" && !game.dateStr.startsWith(filters.selectedMonth)) continue
                }
                RepertoirePeriodPreset.CUSTOM -> {
                    if (filters.dateFrom.isNotBlank() && game.dateStr < filters.dateFrom) continue
                    if (filters.dateTo.isNotBlank() && game.dateStr > filters.dateTo) continue
                }
                RepertoirePeriodPreset.ALL -> Unit
            }

            if (filters.colorFilter != null && game.playerColor != filters.colorFilter) continue
            if (filters.timeClasses.isNotEmpty() && game.timeClass !in filters.timeClasses) continue
            if (filters.manualSelectedGameIds != null && game.id !in filters.manualSelectedGameIds) continue

            val cacheKey = "${game.id}_${game.playerColor.name}"
            val cached = cacheMap[cacheKey]
            val isFreshEngineCached = (cached != null &&
                cached.status == OpeningCacheStatus.ENGINE_ANALYZED &&
                cached.pgnHash == game.pgnHash &&
                cached.algorithmVersion == RepertoireConstants.REPERTOIRE_ALGORITHM_VERSION) ||
                !game.precomputedAnalyses.isNullOrEmpty()

            if (filters.cacheMode == RepertoireCacheMode.EXCLUDE_ANALYZED && isFreshEngineCached) {
                continue
            }
            matching.add(game)
        }

        var whiteCount = 0
        var blackCount = 0
        var alreadyAnalyzedCount = 0
        var basicCachedCount = 0
        var needsEngineCount = 0

        for (game in matching) {
            if (game.playerColor == PieceColor.WHITE) whiteCount++ else blackCount++
            val cacheKey = "${game.id}_${game.playerColor.name}"
            val cached = cacheMap[cacheKey]
            val hasEngine = filters.cacheMode != RepertoireCacheMode.FORCE_REANALYZE && (
                (cached != null &&
                    cached.status == OpeningCacheStatus.ENGINE_ANALYZED &&
                    cached.pgnHash == game.pgnHash &&
                    cached.algorithmVersion == RepertoireConstants.REPERTOIRE_ALGORITHM_VERSION) ||
                    !game.precomputedAnalyses.isNullOrEmpty()
                )
            if (hasEngine) {
                alreadyAnalyzedCount++
            } else if (game.hasValidPgn) {
                needsEngineCount++
                if (cached != null && cached.pgnHash == game.pgnHash) {
                    basicCachedCount++
                }
            }
        }

        return matching to SelectionPreviewSummary(
            totalLoadedPool = pool.size,
            totalMatching = matching.size,
            whiteCount = whiteCount,
            blackCount = blackCount,
            alreadyAnalyzedCount = alreadyAnalyzedCount,
            basicCachedCount = basicCachedCount,
            needsEngineAnalysisCount = needsEngineCount,
            incompleteOrMissingCount = incompleteOrMissingCount
        )
    }
}

object OpeningStatisticsService {

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0

    fun computeColorSplitStats(records: List<OpeningAnalysisCacheRecord>): ColorSplitStats {
        val gamesCount = records.size
        if (gamesCount == 0) return ColorSplitStats()

        var wins = 0
        var draws = 0
        var losses = 0
        val accuracies = mutableListOf<Double>()
        val cpls = mutableListOf<Int>()
        val oppRatings = mutableListOf<Int>()
        var totalMovesSum = 0

        for (r in records) {
            when (r.outcome) {
                RepertoireOutcome.WIN -> wins++
                RepertoireOutcome.DRAW -> draws++
                RepertoireOutcome.LOSS -> losses++
            }
            r.playerAccuracy?.takeIf { it.isFinite() }?.let { accuracies.add(it) }
            r.playerAvgCpl?.let { cpls.add(it) }
            r.opponentRating?.takeIf { it > 0 }?.let { oppRatings.add(it) }
            totalMovesSum += if (r.totalMoves > 0) r.totalMoves else ceil(r.totalPlies / 2.0).toInt()
        }

        return ColorSplitStats(
            gamesCount = gamesCount,
            wins = wins,
            draws = draws,
            losses = losses,
            winRate = round1((wins.toDouble() / gamesCount) * 100.0),
            drawRate = round1((draws.toDouble() / gamesCount) * 100.0),
            lossRate = round1((losses.toDouble() / gamesCount) * 100.0),
            avgAccuracy = if (accuracies.isNotEmpty()) round1(accuracies.sum() / accuracies.size) else null,
            avgCpl = if (cpls.isNotEmpty()) (cpls.sum().toDouble() / cpls.size).roundToInt() else null,
            avgOpponentRating = if (oppRatings.isNotEmpty()) (oppRatings.sum().toDouble() / oppRatings.size).roundToInt() else null,
            avgMovesLength = (totalMovesSum.toDouble() / gamesCount).roundToInt()
        )
    }

    fun buildOpeningStatsList(records: List<OpeningAnalysisCacheRecord>): List<OpeningStatsSummary> {
        val totalGames = records.size
        if (totalGames == 0) return emptyList()

        val groups = LinkedHashMap<String, MutableList<OpeningAnalysisCacheRecord>>()
        for (rec in records) {
            val key = if (rec.isUndefinedOpening) {
                "UNDEFINED__${RepertoireConstants.UNDEFINED_OPENING_NAME}"
            } else {
                rec.openingName
            }
            groups.getOrPut(key) { mutableListOf() }.add(rec)
        }

        val summaries = mutableListOf<OpeningStatsSummary>()

        for ((_, groupRecords) in groups) {
            val overall = computeColorSplitStats(groupRecords)
            val whiteStats = computeColorSplitStats(groupRecords.filter { it.playerColor == PieceColor.WHITE })
            val blackStats = computeColorSplitStats(groupRecords.filter { it.playerColor == PieceColor.BLACK })

            val primaryEco = groupRecords.groupingBy { it.eco }.eachCount().maxByOrNull { it.value }?.key
                ?: groupRecords[0].eco

            val varMap = LinkedHashMap<String, MutableList<OpeningAnalysisCacheRecord>>()
            for (r in groupRecords) {
                val vKey = r.variationName.ifBlank { "Основное продолжение" }
                varMap.getOrPut(vKey) { mutableListOf() }.add(r)
            }

            val variations = varMap.map { (vName, vRecs) ->
                val vStats = computeColorSplitStats(vRecs)
                VariationStatsSummary(
                    variationName = vName,
                    eco = vRecs.firstOrNull()?.eco ?: primaryEco,
                    gamesCount = vStats.gamesCount,
                    wins = vStats.wins,
                    draws = vStats.draws,
                    losses = vStats.losses,
                    winRate = vStats.winRate,
                    drawRate = vStats.drawRate,
                    lossRate = vStats.lossRate,
                    avgAccuracy = vStats.avgAccuracy,
                    avgCpl = vStats.avgCpl,
                    sampleMovesSan = vRecs.firstOrNull()?.openingMoves?.take(10)?.map { it.san } ?: emptyList(),
                    gameIds = vRecs.map { it.gameId }
                )
            }.sortedByDescending { it.gamesCount }

            data class ContBucket(
                val movesSan: List<String>,
                val ply: Int,
                val color: PieceColor,
                val recs: MutableList<OpeningAnalysisCacheRecord>
            )
            val contMap = LinkedHashMap<String, ContBucket>()
            for (r in groupRecords) {
                val slice = r.openingMoves.take(8)
                if (slice.isEmpty()) continue
                val movesSan = slice.map { it.san }
                val lineKey = "${r.playerColor.name}:${movesSan.joinToString(" ")}"
                val bucket = contMap[lineKey]
                if (bucket != null) {
                    bucket.recs.add(r)
                } else {
                    contMap[lineKey] = ContBucket(movesSan, slice.last().ply, r.playerColor, mutableListOf(r))
                }
            }

            val allContinuations = contMap.map { (lineKey, cData) ->
                val cStats = computeColorSplitStats(cData.recs)
                ContinuationPerformanceSummary(
                    lineKey = lineKey,
                    movesSan = cData.movesSan,
                    ply = cData.ply,
                    color = cData.color,
                    gamesCount = cStats.gamesCount,
                    winRate = cStats.winRate,
                    avgAccuracy = cStats.avgAccuracy,
                    avgCpl = cStats.avgCpl
                )
            }

            val bestContinuations = allContinuations
                .filter { it.winRate >= 50.0 }
                .sortedWith(compareByDescending<ContinuationPerformanceSummary> { it.winRate }.thenByDescending { it.gamesCount })
                .take(3)

            val problematicContinuations = allContinuations
                .filter { it.winRate < 50.0 || (it.avgCpl != null && it.avgCpl >= 45) }
                .sortedWith(compareBy<ContinuationPerformanceSummary> { it.winRate }.thenByDescending { it.gamesCount })
                .take(3)

            summaries.add(
                OpeningStatsSummary(
                    key = "${primaryEco}__${groupRecords[0].openingName}",
                    eco = primaryEco,
                    openingName = groupRecords[0].openingName,
                    isUndefinedOpening = groupRecords[0].isUndefinedOpening,
                    gamesCount = overall.gamesCount,
                    frequencyPercent = round1((overall.gamesCount.toDouble() / totalGames) * 100.0),
                    wins = overall.wins,
                    draws = overall.draws,
                    losses = overall.losses,
                    winRate = overall.winRate,
                    drawRate = overall.drawRate,
                    lossRate = overall.lossRate,
                    avgAccuracy = overall.avgAccuracy,
                    avgCpl = overall.avgCpl,
                    avgOpponentRating = overall.avgOpponentRating,
                    avgMovesLength = overall.avgMovesLength,
                    analyzedGamesCount = groupRecords.count { it.playerAccuracy != null },
                    lowSampleConfidence = overall.gamesCount < 5,
                    whiteStats = whiteStats,
                    blackStats = blackStats,
                    variations = variations,
                    bestContinuations = bestContinuations,
                    problematicContinuations = problematicContinuations,
                    gameIds = groupRecords.map { it.gameId }
                )
            )
        }

        return summaries.sortedWith(
            compareByDescending<OpeningStatsSummary> { it.gamesCount }.thenByDescending { it.winRate }
        )
    }

    private class MutableTreeBuilder(
        val id: String,
        val ply: Int,
        val moveNumber: Int,
        val color: PieceColor,
        val san: String,
        val uci: String,
        val fen: String,
        val positionKey: String,
        val eco: String?,
        val openingName: String?,
        val variationName: String?,
        val isTheoretical: Boolean
    ) {
        var gamesCount = 0
        var wins = 0
        var draws = 0
        var losses = 0
        var analyzedCount = 0
        val gameIds = mutableListOf<String>()
        val accValues = mutableListOf<Double>()
        val cplValues = mutableListOf<Int>()
        val childMap = LinkedHashMap<String, MutableTreeBuilder>()

        fun build(): RepertoireTreeNode {
            val builtChildren = childMap.values.map { it.build() }
                .sortedWith(compareByDescending<RepertoireTreeNode> { it.gamesCount }.thenByDescending { it.winRate })

            return RepertoireTreeNode(
                id = id,
                ply = ply,
                moveNumber = moveNumber,
                color = color,
                san = san,
                uci = uci,
                fen = fen,
                positionKey = positionKey,
                eco = eco,
                openingName = openingName,
                variationName = variationName,
                isTheoretical = isTheoretical,
                gamesCount = gamesCount,
                wins = wins,
                draws = draws,
                losses = losses,
                winRate = if (gamesCount > 0) round1((wins.toDouble() / gamesCount) * 100.0) else 0.0,
                drawRate = if (gamesCount > 0) round1((draws.toDouble() / gamesCount) * 100.0) else 0.0,
                lossRate = if (gamesCount > 0) round1((losses.toDouble() / gamesCount) * 100.0) else 0.0,
                avgAccuracy = if (accValues.isNotEmpty()) round1(accValues.sum() / accValues.size) else null,
                avgCpl = if (cplValues.isNotEmpty()) (cplValues.sum().toDouble() / cplValues.size).roundToInt() else null,
                analyzedCount = analyzedCount,
                gameIds = gameIds.toList(),
                children = builtChildren
            )
        }
    }

    fun buildRepertoireMoveTree(
        records: List<OpeningAnalysisCacheRecord>,
        targetColor: PieceColor,
        maxDepthPly: Int = 20
    ): RepertoireTreeNode {
        val colorRecords = records.filter { it.playerColor == targetColor }
        val rootStats = computeColorSplitStats(colorRecords)

        val rootBuilder = MutableTreeBuilder(
            id = "tree_root_${targetColor.name}",
            ply = 0,
            moveNumber = 0,
            color = PieceColor.BLACK,
            san = "Начальная позиция",
            uci = "",
            fen = VariationTree.DEFAULT_START_FEN,
            positionKey = normalizeLegalEpFenKey(VariationTree.DEFAULT_START_FEN),
            eco = null,
            openingName = null,
            variationName = null,
            isTheoretical = true
        ).apply {
            gamesCount = rootStats.gamesCount
            wins = rootStats.wins
            draws = rootStats.draws
            losses = rootStats.losses
            analyzedCount = colorRecords.count { it.playerAccuracy != null }
            gameIds.addAll(colorRecords.map { it.gameId })
            colorRecords.mapNotNull { it.playerAccuracy }.let { accValues.addAll(it) }
            colorRecords.mapNotNull { it.playerAvgCpl }.let { cplValues.addAll(it) }
        }

        for (rec in colorRecords) {
            var curr = rootBuilder
            val steps = rec.openingMoves.take(maxDepthPly)
            val moveAnalysesByPly = rec.moveAnalyses?.associateBy { it.ply }.orEmpty()

            for (step in steps) {
                val childKey = "${step.ply}_${step.san}"
                val next = curr.childMap.getOrPut(childKey) {
                    MutableTreeBuilder(
                        id = "node_${targetColor.name}_${step.ply}_${step.uci}_${curr.childMap.size}",
                        ply = step.ply,
                        moveNumber = step.moveNumber,
                        color = step.color,
                        san = step.san,
                        uci = step.uci,
                        fen = step.fenAfter,
                        positionKey = step.positionKey,
                        eco = step.ecoAtStep ?: rec.eco,
                        openingName = step.openingNameAtStep ?: rec.openingName,
                        variationName = step.variationAtStep ?: rec.variationName,
                        isTheoretical = step.isTheoretical
                    )
                }
                next.gamesCount++
                when (rec.outcome) {
                    RepertoireOutcome.WIN -> next.wins++
                    RepertoireOutcome.DRAW -> next.draws++
                    RepertoireOutcome.LOSS -> next.losses++
                }
                next.gameIds.add(rec.gameId)

                val plyMetric = moveAnalysesByPly[step.ply]
                if (plyMetric != null && step.color == targetColor && plyMetric.moveAccuracy.isFinite()) {
                    next.accValues.add(plyMetric.moveAccuracy)
                    next.cplValues.add(plyMetric.cpl)
                    next.analyzedCount++
                } else if (rec.playerAccuracy != null) {
                    next.accValues.add(rec.playerAccuracy)
                    rec.playerAvgCpl?.let { next.cplValues.add(it) }
                    next.analyzedCount++
                }
                curr = next
            }
        }

        return rootBuilder.build()
    }

    fun detectProblematicAndNotableContinuations(
        records: List<OpeningAnalysisCacheRecord>
    ): List<ProblematicContinuationItem> {
        data class MoveBucket(
            val playerColor: PieceColor,
            val eco: String,
            val openingName: String,
            val variationName: String,
            val ply: Int,
            val moveNumber: Int,
            val playedSan: String,
            val fenBefore: String,
            val fenAfter: String,
            val positionKey: String,
            val isOutOfBook: Boolean,
            val recommendedSanCounts: MutableMap<String, Int> = LinkedHashMap(),
            var wins: Int = 0,
            var draws: Int = 0,
            var losses: Int = 0,
            val cpls: MutableList<Int> = mutableListOf(),
            val winProbLosses: MutableList<Double> = mutableListOf(),
            val evalAfterPlayerCps: MutableList<Int> = mutableListOf(),
            var errorCount: Int = 0,
            var advantageLostCount: Int = 0,
            val examples: MutableList<ProblematicGameExample> = mutableListOf()
        )

        val buckets = LinkedHashMap<String, MoveBucket>()

        for (rec in records) {
            val analyses = rec.moveAnalyses ?: continue
            val stepByPly = rec.openingMoves.associateBy { it.ply }

            for (ma in analyses) {
                if (ma.ply > 24 || ma.player != rec.playerColor) continue

                val step = stepByPly[ma.ply]
                val posKey = step?.let { normalizeLegalEpFenKey(it.fenBefore) } ?: ma.fenBefore
                val bucketKey = "${rec.playerColor.name}__${posKey}__${ma.san}"

                val sign = if (rec.playerColor == PieceColor.WHITE) 1 else -1
                val evalBeforePlayer = ma.evalBeforeCp * sign
                val evalAfterPlayer = ma.evalAfterCp * sign

                val isErrorCategory = ma.category == MoveClassification.INACCURACY ||
                    ma.category == MoveClassification.MISTAKE ||
                    ma.category == MoveClassification.MISSED_CHANCE ||
                    ma.category == MoveClassification.BLUNDER

                val lostAdvantage = evalBeforePlayer >= 110 && evalAfterPlayer <= 15 && ma.cpl >= 90
                val isOutOfBookMove = rec.firstOutOfBookPly != null &&
                    ma.ply >= rec.firstOutOfBookPly &&
                    !ma.isTheoretical

                val b = buckets.getOrPut(bucketKey) {
                    MoveBucket(
                        playerColor = rec.playerColor,
                        eco = rec.eco,
                        openingName = rec.openingName,
                        variationName = rec.variationName,
                        ply = ma.ply,
                        moveNumber = ma.moveNumber,
                        playedSan = ma.san,
                        fenBefore = ma.fenBefore,
                        fenAfter = ma.fenAfter,
                        positionKey = posKey,
                        isOutOfBook = isOutOfBookMove
                    )
                }

                when (rec.outcome) {
                    RepertoireOutcome.WIN -> b.wins++
                    RepertoireOutcome.DRAW -> b.draws++
                    RepertoireOutcome.LOSS -> b.losses++
                }
                if (ma.bestMoveSan.isNotBlank()) {
                    b.recommendedSanCounts[ma.bestMoveSan] = (b.recommendedSanCounts[ma.bestMoveSan] ?: 0) + 1
                }
                b.cpls.add(ma.cpl)
                b.winProbLosses.add(ma.winProbLoss)
                b.evalAfterPlayerCps.add(evalAfterPlayer)
                if (isErrorCategory) b.errorCount++
                if (lostAdvantage) b.advantageLostCount++

                b.examples.add(
                    ProblematicGameExample(
                        gameId = rec.gameId,
                        whiteName = rec.whiteName,
                        blackName = rec.blackName,
                        playerColor = rec.playerColor,
                        opponentName = rec.opponentName,
                        dateStr = rec.dateStr,
                        result = rec.result,
                        outcome = rec.outcome,
                        ply = ma.ply,
                        moveNumber = ma.moveNumber,
                        playedSan = ma.san,
                        bestMoveSan = ma.bestMoveSan.ifBlank { ma.san },
                        cpl = ma.cpl,
                        winProbLoss = ma.winProbLoss,
                        evalBeforeCp = ma.evalBeforeCp,
                        evalAfterCp = ma.evalAfterCp,
                        category = ma.category
                    )
                )
            }
        }

        val results = mutableListOf<ProblematicContinuationItem>()

        for ((key, b) in buckets) {
            val occurrences = b.cpls.size
            val avgCpl = (b.cpls.sum().toDouble() / occurrences).roundToInt()
            val avgWinProbLoss = round1(b.winProbLosses.sum() / occurrences)
            val avgEvalAfterPlayerCp = (b.evalAfterPlayerCps.sum().toDouble() / occurrences).roundToInt()
            val winRate = round1((b.wins.toDouble() / occurrences) * 100.0)

            val bestRecSan = b.recommendedSanCounts.maxByOrNull { it.value }?.key ?: b.playedSan
            val moveLabel = "${b.moveNumber}${if (b.playerColor == PieceColor.WHITE) "." else "..."} ${b.playedSan}"
            val lowSampleWarning = occurrences < 3

            if (b.errorCount >= 1 && (avgCpl >= 75 || avgWinProbLoss >= 9.0)) {
                val issueType = when {
                    b.advantageLostCount >= 1 -> ProblematicIssueType.LOST_ADVANTAGE
                    b.errorCount >= 2 || avgCpl >= 120 -> ProblematicIssueType.RECURRING_MISTAKE
                    else -> ProblematicIssueType.SYSTEMATIC_EVAL_DROP
                }
                val summaryTitle = when (issueType) {
                    ProblematicIssueType.LOST_ADVANTAGE -> "Потеря перевеса после $moveLabel"
                    else -> if (occurrences >= 2) {
                        "Регулярная потеря оценки на ходе $moveLabel"
                    } else {
                        "Дебютная ошибка на ходе $moveLabel"
                    }
                }
                val summaryDescription = if (occurrences >= 2) {
                    "Встретилось в $occurrences партиях (ошибок: ${b.errorCount}). Средняя потеря: $avgCpl cp ($avgWinProbLoss% Win%). Рекомендуемое продолжение Stockfish: $bestRecSan."
                } else {
                    "Единичный случай в выборке (потеря $avgCpl cp, $avgWinProbLoss% Win%). Требуется больше партий для подтверждения тенденции. Лучший ход: $bestRecSan."
                }
                results.add(
                    ProblematicContinuationItem(
                        id = "prob_$key",
                        issueType = issueType,
                        playerColor = b.playerColor,
                        eco = b.eco,
                        openingName = b.openingName,
                        variationName = b.variationName,
                        ply = b.ply,
                        moveNumber = b.moveNumber,
                        playedSan = b.playedSan,
                        recommendedSan = bestRecSan,
                        fenBefore = b.fenBefore,
                        fenAfter = b.fenAfter,
                        positionKey = b.positionKey,
                        occurrences = occurrences,
                        errorCount = b.errorCount,
                        wins = b.wins,
                        draws = b.draws,
                        losses = b.losses,
                        winRate = winRate,
                        avgCpl = avgCpl,
                        avgWinProbLoss = avgWinProbLoss,
                        avgEvalAfterPlayerCp = avgEvalAfterPlayerCp,
                        isOutOfBook = b.isOutOfBook,
                        isObjectiveError = true,
                        lowSampleWarning = lowSampleWarning,
                        summaryTitle = summaryTitle,
                        summaryDescription = summaryDescription,
                        examples = b.examples.take(5)
                    )
                )
                continue
            }

            if (b.isOutOfBook && b.errorCount == 0 && avgCpl <= 40 && occurrences >= 1) {
                results.add(
                    ProblematicContinuationItem(
                        id = "sideline_$key",
                        issueType = ProblematicIssueType.THEORETICAL_SIDELINE,
                        playerColor = b.playerColor,
                        eco = b.eco,
                        openingName = b.openingName,
                        variationName = b.variationName,
                        ply = b.ply,
                        moveNumber = b.moveNumber,
                        playedSan = b.playedSan,
                        recommendedSan = bestRecSan,
                        fenBefore = b.fenBefore,
                        fenAfter = b.fenAfter,
                        positionKey = b.positionKey,
                        occurrences = occurrences,
                        errorCount = 0,
                        wins = b.wins,
                        draws = b.draws,
                        losses = b.losses,
                        winRate = winRate,
                        avgCpl = avgCpl,
                        avgWinProbLoss = avgWinProbLoss,
                        avgEvalAfterPlayerCp = avgEvalAfterPlayerCp,
                        isOutOfBook = true,
                        isObjectiveError = false,
                        lowSampleWarning = lowSampleWarning,
                        summaryTitle = "Корректное отклонение от теории: $moveLabel",
                        summaryDescription = "Ход выходит за рамки стандартной дебютной книги, но НЕ является ошибкой (средняя потеря всего $avgCpl cp, результативность $winRate% побед).",
                        examples = b.examples.take(5)
                    )
                )
                continue
            }

            if (occurrences >= 2 && winRate >= 60.0 && avgCpl <= 25 && b.errorCount == 0) {
                results.add(
                    ProblematicContinuationItem(
                        id = "strong_$key",
                        issueType = ProblematicIssueType.STRONG_CONTINUATION,
                        playerColor = b.playerColor,
                        eco = b.eco,
                        openingName = b.openingName,
                        variationName = b.variationName,
                        ply = b.ply,
                        moveNumber = b.moveNumber,
                        playedSan = b.playedSan,
                        recommendedSan = bestRecSan,
                        fenBefore = b.fenBefore,
                        fenAfter = b.fenAfter,
                        positionKey = b.positionKey,
                        occurrences = occurrences,
                        errorCount = 0,
                        wins = b.wins,
                        draws = b.draws,
                        losses = b.losses,
                        winRate = winRate,
                        avgCpl = avgCpl,
                        avgWinProbLoss = avgWinProbLoss,
                        avgEvalAfterPlayerCp = avgEvalAfterPlayerCp,
                        isOutOfBook = b.isOutOfBook,
                        isObjectiveError = false,
                        lowSampleWarning = lowSampleWarning,
                        summaryTitle = "Успешное продолжение репертуара: $moveLabel",
                        summaryDescription = "Стабильная точная игра в $occurrences партиях ($winRate% побед, средний CPL $avgCpl).",
                        examples = b.examples.take(5)
                    )
                )
            }
        }

        return results.sortedWith(
            compareByDescending<ProblematicContinuationItem> { it.isObjectiveError }
                .thenByDescending { it.occurrences * it.avgCpl }
        )
    }
}

@Singleton
class RepertoireAnalyzerService @Inject constructor(
    private val openingBook: OpeningBookService,
    private val openingAnalyzer: OpeningAnalyzer,
    @Named("repertoire") private val repertoireStockfish: StockfishUciClient
) {

    fun computeAggregationCacheId(
        filters: RepertoireFilterConfig,
        selectedGameIds: List<String>
    ): String {
        val raw = listOf(
            RepertoireConstants.REPERTOIRE_ALGORITHM_VERSION,
            filters.playerUsername.trim().lowercase(Locale.ROOT),
            filters.periodPreset.name,
            filters.selectedMonth,
            filters.dateFrom,
            filters.dateTo,
            filters.colorFilter?.name ?: "ALL",
            filters.timeClasses.map { it.name }.sorted().joinToString(","),
            selectedGameIds.sorted().joinToString(",")
        ).joinToString("|")
        return "rep_${PgnService.computeStableHash(raw)}"
    }

    fun buildInstantRepertoireFromSelection(
        selectedGames: List<RepertoireSourceGame>,
        filters: RepertoireFilterConfig,
        cacheMap: MutableMap<String, OpeningAnalysisCacheRecord>
    ): Pair<List<OpeningAnalysisCacheRecord>, OpeningRepertoireAggregation> {
        val activeRecords = mutableListOf<OpeningAnalysisCacheRecord>()

        for (game in selectedGames) {
            if (!game.hasValidPgn) continue
            val cacheKey = "${game.id}_${game.playerColor.name}"
            val existing = cacheMap[cacheKey]

            if (existing != null &&
                existing.pgnHash == game.pgnHash &&
                existing.algorithmVersion == RepertoireConstants.REPERTOIRE_ALGORITHM_VERSION &&
                existing.status != OpeningCacheStatus.ERROR
            ) {
                if (existing.status != OpeningCacheStatus.ENGINE_ANALYZED &&
                    !game.precomputedAnalyses.isNullOrEmpty()
                ) {
                    val upgraded = openingAnalyzer.analyzeGameOpeningForRepertoire(game, game.precomputedAnalyses)
                    cacheMap[cacheKey] = upgraded
                    activeRecords.add(upgraded)
                } else {
                    activeRecords.add(existing)
                }
                continue
            }

            val classified = openingAnalyzer.analyzeGameOpeningForRepertoire(game, game.precomputedAnalyses)
            cacheMap[cacheKey] = classified
            activeRecords.add(classified)
        }

        val aggregated = aggregateRepertoireRecords(activeRecords, filters)
        return activeRecords to aggregated
    }

    fun aggregateRepertoireRecords(
        records: List<OpeningAnalysisCacheRecord>,
        filters: RepertoireFilterConfig
    ): OpeningRepertoireAggregation {
        val valid = records.filter { it.status != OpeningCacheStatus.ERROR }
        val whiteRecords = valid.filter { it.playerColor == PieceColor.WHITE }
        val blackRecords = valid.filter { it.playerColor == PieceColor.BLACK }
        val analyzedGames = valid.count { it.status == OpeningCacheStatus.ENGINE_ANALYZED }

        return OpeningRepertoireAggregation(
            id = computeAggregationCacheId(filters, valid.map { it.gameId }),
            playerIdentifier = filters.playerUsername.ifBlank { "Белые" }.trim(),
            filterSnapshot = filters,
            totalGames = valid.size,
            whiteGames = whiteRecords.size,
            blackGames = blackRecords.size,
            analyzedGames = analyzedGames,
            openings = OpeningStatisticsService.buildOpeningStatsList(valid),
            whiteOpenings = OpeningStatisticsService.buildOpeningStatsList(whiteRecords),
            blackOpenings = OpeningStatisticsService.buildOpeningStatsList(blackRecords),
            whiteTree = OpeningStatisticsService.buildRepertoireMoveTree(valid, PieceColor.WHITE, 20),
            blackTree = OpeningStatisticsService.buildRepertoireMoveTree(valid, PieceColor.BLACK, 20),
            problematicItems = OpeningStatisticsService.detectProblematicAndNotableContinuations(valid),
            createdAt = System.currentTimeMillis(),
            algorithmVersion = RepertoireConstants.REPERTOIRE_ALGORITHM_VERSION
        )
    }

    suspend fun evaluateSingleGameOpeningWithStockfish(
        record: OpeningAnalysisCacheRecord,
        depth: Int = 12,
        maxOpeningPly: Int = 16,
        thresholds: ClassificationThresholds = MoveClassifier.DEFAULT_THRESHOLDS,
        positionEvalCache: MutableMap<String, CachedFenEval> = HashMap()
    ): OpeningAnalysisCacheRecord {
        repertoireStockfish.init()
        val evalDepth = repertoireStockfish.normalizeDepth(depth.coerceIn(8, 18))

        val headers = OpeningBookService.parsePgnHeaders(record.pgn)
        val initialFen = headers["FEN"]?.takeIf { it.isNotBlank() } ?: VariationTree.DEFAULT_START_FEN
        val root = VariationTree.parsePgnMovetextToTree(record.pgn, initialFen)
        val mainlineNodes = VariationTree.getMainlineNodes(root)
        val moveNodes = mainlineNodes.filter { it.ply > 0 }.take(maxOpeningPly)
        if (moveNodes.isEmpty()) return record

        val openingSummary = openingBook.analyzeMainlineOpening(mainlineNodes)

        suspend fun getOrEvalPosition(fen: String): CachedFenEval {
            positionEvalCache[fen]?.let { return it }
            val res = repertoireStockfish.evaluateFenAsync(fen, evalDepth, 2)
            val primary = res.lines.firstOrNull()
            val entry = CachedFenEval(
                bestMove = res.bestMove.ifEmpty { primary?.pv?.firstOrNull().orEmpty() },
                lines = res.lines,
                cp = primary?.cp,
                mate = primary?.mate
            )
            positionEvalCache[fen] = entry
            return entry
        }

        val analyses = mutableListOf<MoveAnalysis>()
        for (i in moveNodes.indices) {
            currentCoroutineContext().ensureActive()
            val currNode = moveNodes[i]
            val fenBefore = if (i == 0) initialFen else moveNodes[i - 1].fen
            val fenAfter = currNode.fen

            val evalBefore = getOrEvalPosition(fenBefore)
            currentCoroutineContext().ensureActive()
            val evalAfter = getOrEvalPosition(fenAfter)
            currentCoroutineContext().ensureActive()

            val bestMoveUci = evalBefore.bestMove
            val bestLineUci = evalBefore.lines.firstOrNull()?.pv
                ?: if (bestMoveUci.isNotEmpty()) listOf(bestMoveUci) else emptyList()
            val bestLineSan = ChesslibHelper.uciLineToSan(fenBefore, bestLineUci, 6)
            val bestMoveSan = bestLineSan.firstOrNull() ?: currNode.san
            val opponentBestPvUci = evalAfter.lines.firstOrNull()?.pv ?: emptyList()

            val isTheoretical = openingSummary.lastTheoreticalPly > 0 &&
                currNode.ply <= openingSummary.lastTheoreticalPly &&
                (openingSummary.firstOutOfBookPly == null || currNode.ply < openingSummary.firstOutOfBookPly)

            val secondLine = evalBefore.lines.firstOrNull { it.multipv == 2 }
            val classification = MoveClassifier.classifyMoveFromEvaluations(
                player = currNode.color,
                uci = currNode.uci,
                fenBefore = fenBefore,
                fenAfter = fenAfter,
                bestMoveUci = bestMoveUci,
                evalBeforeCp = evalBefore.cp,
                evalAfterCp = evalAfter.cp,
                mateBefore = evalBefore.mate,
                mateAfter = evalAfter.mate,
                secondBestCp = secondLine?.cp,
                secondBestMate = secondLine?.mate,
                alternativeLines = evalBefore.lines,
                opponentBestPvUci = opponentBestPvUci,
                isTheoretical = isTheoretical,
                thresholds = thresholds,
                openingBook = openingBook
            )

            analyses.add(
                MoveAnalysis(
                    nodeId = currNode.id,
                    ply = currNode.ply,
                    moveNumber = currNode.moveNumber,
                    player = currNode.color,
                    san = currNode.san,
                    uci = currNode.uci,
                    fenBefore = fenBefore,
                    fenAfter = fenAfter,
                    bestMoveUci = bestMoveUci,
                    bestMoveSan = bestMoveSan,
                    evalBeforeCp = evalBefore.cp ?: 0,
                    evalAfterCp = evalAfter.cp ?: 0,
                    mateBefore = evalBefore.mate,
                    mateAfter = evalAfter.mate,
                    winProbBefore = classification.winProbBefore,
                    winProbAfter = classification.winProbAfter,
                    cpl = classification.cpl,
                    winProbLoss = classification.winProbLoss,
                    moveAccuracy = classification.moveAccuracy,
                    category = classification.category,
                    bestLineUci = bestLineUci,
                    bestLineSan = bestLineSan,
                    alternativeLines = evalBefore.lines,
                    depth = evalDepth,
                    isTheoretical = isTheoretical
                )
            )
        }

        val (playerAccuracy, playerAvgCpl) = openingAnalyzer.extractPlayerMetricsFromAnalyses(
            analyses,
            record.playerColor
        )

        return record.copy(
            moveAnalyses = analyses,
            playerAccuracy = playerAccuracy,
            playerAvgCpl = playerAvgCpl,
            algorithmVersion = RepertoireConstants.REPERTOIRE_ALGORITHM_VERSION,
            analyzedAt = System.currentTimeMillis(),
            status = OpeningCacheStatus.ENGINE_ANALYZED
        )
    }

    fun stopRepertoireAnalysis() {
        repertoireStockfish.stop()
    }
}
