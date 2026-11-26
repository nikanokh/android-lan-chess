package com.example.core.analysis

import com.example.core.analysis.ChesslibHelper.toPieceColor
import com.example.core.analysis.ChesslibHelper.toPieceType
import com.example.core.ui.board.PieceType
import com.example.domain.model.MoveClassification
import com.example.domain.model.PieceColor
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MoveClassificationOutcome(
    val cpl: Int,
    val winProbBefore: Double,
    val winProbAfter: Double,
    val winProbLoss: Double,
    val moveAccuracy: Double,
    val category: MoveClassification
)

object MoveClassifier {
    val DEFAULT_THRESHOLDS = ClassificationThresholds()

    private val PIECE_VALUES: Map<PieceType, Int> = mapOf(
        PieceType.PAWN to 100,
        PieceType.KNIGHT to 300,
        PieceType.BISHOP to 300,
        PieceType.ROOK to 500,
        PieceType.QUEEN to 900,
        PieceType.KING to 10000
    )

    private fun pieceValue(type: PieceType?): Int = if (type != null) PIECE_VALUES[type] ?: 0 else 0

    /**
     * Computes net material balance (in centipawns) from `color`'s perspective.
     */
    fun computeMaterialBalance(fen: String, color: PieceColor): Int {
        val board = ChesslibHelper.parseBoard(fen)
        var score = 0
        for (sq in Square.entries) {
            if (sq == Square.NONE) continue
            val piece = board.getPiece(sq)
            val type = piece.toPieceType() ?: continue
            if (type == PieceType.KING) continue
            val pColor = piece.toPieceColor() ?: continue
            val value = pieceValue(type)
            if (pColor == color) score += value else score -= value
        }
        return score
    }

    /**
     * Recursive Static Exchange Evaluation (SEE) on a target `square` from the side-to-move's perspective.
     */
    fun evaluateSeeOnSquare(board: Board, targetSquare: String, depth: Int = 0): Int {
        if (depth >= 8) return 0
        val legalCaptures = ChesslibHelper.legalMovesVerbose(board)
            .filter { it.to == targetSquare && it.captured != null }
        if (legalCaptures.isEmpty()) return 0

        val chosen = legalCaptures.minBy { pieceValue(it.piece) }
        val capturedVal = pieceValue(chosen.captured)
        val promoGain = chosen.promotion?.let { promo ->
            pieceValue(PieceType.fromFenSymbol(promo)) - 100
        } ?: 0

        val nextBoard = ChesslibHelper.parseBoard(board.fen)
        ChesslibHelper.applyUci(nextBoard, chosen.uci) ?: return 0

        val opponentReplyGain = evaluateSeeOnSquare(nextBoard, targetSquare, depth + 1)
        return max(0, capturedVal + promoGain - opponentReplyGain)
    }

    /**
     * Determines whether the move from `fenBefore` to `fenAfter` loses significant material (>= 250 cp)
     * after accounting for immediate gain, full SEE recaptures, and opponent's best engine PV.
     */
    fun doesMoveLosePiece(
        fenBefore: String,
        fenAfter: String,
        player: PieceColor,
        opponentBestPvUci: List<String>? = null
    ): Boolean {
        try {
            val matBefore = computeMaterialBalance(fenBefore, player)
            val matAfter = computeMaterialBalance(fenAfter, player)
            val immediateGain = matAfter - matBefore

            val boardAfter = ChesslibHelper.parseBoard(fenAfter)
            val opponentCaptures = ChesslibHelper.legalMovesVerbose(boardAfter)
                .filter { it.captured != null }

            val checkedSquares = HashSet<String>()
            var maxOpponentSeeGain = 0

            for (cap in opponentCaptures) {
                if (!checkedSquares.add(cap.to)) continue
                val capturedVal = pieceValue(cap.captured)
                val attackerVal = pieceValue(cap.piece)
                val promoGain = cap.promotion?.let { promo ->
                    pieceValue(PieceType.fromFenSymbol(promo)) - 100
                } ?: 0

                val sim = ChesslibHelper.parseBoard(fenAfter)
                ChesslibHelper.applyUci(sim, cap.uci) ?: continue

                val playerRecaptureGain = evaluateSeeOnSquare(sim, cap.to, 1)
                val opponentNetOnSquare = capturedVal + promoGain - playerRecaptureGain

                if (opponentNetOnSquare > maxOpponentSeeGain && (capturedVal >= 300 || attackerVal < capturedVal)) {
                    maxOpponentSeeGain = opponentNetOnSquare
                }
            }

            val netSeeMaterialLoss = maxOpponentSeeGain - immediateGain
            if (netSeeMaterialLoss >= 250) {
                return true
            }

            if (!opponentBestPvUci.isNullOrEmpty()) {
                val sim = ChesslibHelper.parseBoard(fenAfter)
                val maxPlies = min(opponentBestPvUci.size, 4)
                var pliesPlayed = 0
                for (i in 0 until maxPlies) {
                    val uci = opponentBestPvUci[i]
                    if (uci.length < 4) break
                    ChesslibHelper.applyUci(sim, uci) ?: break
                    pliesPlayed++
                }

                if (pliesPlayed > 0) {
                    var afterPvMaterial = computeMaterialBalance(sim.fen, player)
                    if (pliesPlayed % 2 == 1) {
                        val lastUci = opponentBestPvUci[pliesPlayed - 1]
                        val lastTo = lastUci.substring(2, 4)
                        val playerRecovery = evaluateSeeOnSquare(sim, lastTo, 0)
                        afterPvMaterial += playerRecovery
                    }
                    if (matBefore - afterPvMaterial >= 250) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
            // ignore invalid FEN
        }
        return false
    }

    fun isMaterialSacrificeMove(
        fenBefore: String,
        fenAfter: String,
        uci: String,
        player: PieceColor
    ): Boolean {
        return try {
            if (uci.length < 4 || fenBefore == fenAfter) return false
            val matBefore = computeMaterialBalance(fenBefore, player)
            val matAfter = computeMaterialBalance(fenAfter, player)
            val immediateDelta = matAfter - matBefore

            val boardAfter = ChesslibHelper.parseBoard(fenAfter)
            val toSqName = uci.substring(2, 4).uppercase()
            val toSq = Square.fromValue(toSqName)
            val movedPiece = boardAfter.getPiece(toSq)
            if (movedPiece.toPieceColor() != player) return false

            val opponentCaptures = ChesslibHelper.legalMovesVerbose(boardAfter)
                .filter { it.captured != null && it.captured != PieceType.PAWN }

            var bestOpponentSee = 0
            val checked = HashSet<String>()
            for (cap in opponentCaptures) {
                if (!checked.add(cap.to)) continue
                val capturedVal = pieceValue(cap.captured)
                if (capturedVal < 300) continue

                val sim = ChesslibHelper.parseBoard(fenAfter)
                ChesslibHelper.applyUci(sim, cap.uci) ?: continue
                val recaptureGain = evaluateSeeOnSquare(sim, cap.to, 1)
                val netGain = capturedVal - recaptureGain
                if (netGain > bestOpponentSee) {
                    bestOpponentSee = netGain
                }
            }

            bestOpponentSee >= 200 && bestOpponentSee - max(0, immediateDelta) >= 150
        } catch (_: Exception) {
            false
        }
    }

    fun classifyMoveFromEvaluations(
        player: PieceColor,
        uci: String,
        fenBefore: String,
        fenAfter: String,
        bestMoveUci: String,
        evalBeforeCp: Int? = null,
        evalAfterCp: Int? = null,
        mateBefore: Int? = null,
        mateAfter: Int? = null,
        secondBestCp: Int? = null,
        secondBestMate: Int? = null,
        alternativeLines: List<EngineLine>? = null,
        opponentBestPvUci: List<String>? = null,
        isTheoretical: Boolean,
        thresholds: ClassificationThresholds,
        openingBook: OpeningBookService? = null
    ): MoveClassificationOutcome {
        val sign = if (player == PieceColor.WHITE) 1 else -1

        val playerMateBefore = mateBefore?.takeIf { it != 0 }?.let { it * sign }
        val playerMateAfter = mateAfter?.takeIf { it != 0 }?.let { it * sign }

        val hasValidCpBefore = evalBeforeCp != null && playerMateBefore == null
        val hasValidCpAfter = evalAfterCp != null && playerMateAfter == null

        val playerEvalBefore = if (hasValidCpBefore) evalBeforeCp * sign else 0
        val playerEvalAfter = if (hasValidCpAfter) evalAfterCp * sign else 0

        val isExactBestMove = bestMoveUci.isNotEmpty() && uci.isNotEmpty() && uci == bestMoveUci
        var isTiedTopMultiPv = false
        if (!isExactBestMove && !alternativeLines.isNullOrEmpty() && alternativeLines.size > 1 && uci.isNotEmpty()) {
            val matchingLine = alternativeLines.firstOrNull { it.pv.firstOrNull() == uci }
            val topLine = alternativeLines.firstOrNull()
            if (matchingLine != null && topLine != null &&
                matchingLine.depth == topLine.depth &&
                topLine.mate == null && matchingLine.mate == null &&
                topLine.cp != null && matchingLine.cp != null
            ) {
                if (abs(topLine.cp - matchingLine.cp) <= 3) {
                    isTiedTopMultiPv = true
                }
            }
        }

        val isBestMoveChoice = isExactBestMove || isTiedTopMultiPv

        var cpl = 0
        if (!isBestMoveChoice && hasValidCpBefore && hasValidCpAfter) {
            val matchingPreLine = alternativeLines?.firstOrNull { it.pv.firstOrNull() == uci }
            cpl = if (matchingPreLine != null && matchingPreLine.cp != null && matchingPreLine.mate == null) {
                val playedLinePlayerCp = matchingPreLine.cp * sign
                max(0, playerEvalBefore - playedLinePlayerCp)
            } else {
                max(0, playerEvalBefore - playerEvalAfter)
            }
        }

        val winProbLossResult = AccuracyCalculator.computeMoveWinProbLoss(
            player = player,
            evalBeforeCp = if (hasValidCpBefore) evalBeforeCp else null,
            evalAfterCp = if (hasValidCpAfter) evalAfterCp else null,
            mateBefore = mateBefore,
            mateAfter = mateAfter,
            isBestMove = isBestMoveChoice
        )
        val winProbBefore = winProbLossResult.winProbBefore
        val winProbAfter = winProbLossResult.winProbAfter
        val winProbLoss = winProbLossResult.winProbLoss

        val playerWinProbBefore = AccuracyCalculator.toPlayerWinProbability(winProbBefore, player)
        val playerWinProbAfter = AccuracyCalculator.toPlayerWinProbability(winProbAfter, player)
        val moveAccuracy = AccuracyCalculator.calculateSingleMoveAccuracy(winProbLoss)

        // 1. Check if Forced (strictly 1 legal move in non-terminal position)
        try {
            if (fenBefore.isNotBlank()) {
                val boardBefore = ChesslibHelper.parseBoard(fenBefore)
                if (!ChesslibHelper.isGameOver(boardBefore)) {
                    val legalMoves = ChesslibHelper.legalMovesVerbose(boardBefore)
                    if (legalMoves.size == 1) {
                        val singleUci = legalMoves[0].uci
                        if (uci.isEmpty() || uci == singleUci) {
                            return MoveClassificationOutcome(
                                cpl = 0,
                                winProbBefore = winProbBefore,
                                winProbAfter = winProbAfter,
                                winProbLoss = 0.0,
                                moveAccuracy = 100.0,
                                category = MoveClassification.FORCED
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // ignore invalid FEN
        }

        // 2. Check if Book (Opening Theory)
        if (isTheoretical) {
            val isGenuineBookEdge = fenBefore.isBlank() ||
                fenAfter.isBlank() ||
                fenBefore == fenAfter ||
                openingBook == null ||
                openingBook.isBookMove(fenBefore, uci, fenAfter)

            val hasNoSeriousError = winProbLoss <= 10.0 &&
                cpl <= thresholds.goodMaxCpl &&
                (playerMateAfter == null || playerMateAfter > 0)

            if (isGenuineBookEdge && hasNoSeriousError) {
                return MoveClassificationOutcome(
                    cpl = cpl,
                    winProbBefore = winProbBefore,
                    winProbAfter = winProbAfter,
                    winProbLoss = winProbLoss,
                    moveAccuracy = moveAccuracy,
                    category = MoveClassification.BOOK
                )
            }
        }

        // 3. Dedicated Mate Threat & Forced Mate Sequence Classification
        if (playerMateBefore != null || playerMateAfter != null) {
            // Case A: Opponent did NOT have forced mate before, and now HAS forced mate against player
            if ((playerMateBefore == null || playerMateBefore > 0) && playerMateAfter != null && playerMateAfter < 0) {
                val mateDist = abs(playerMateAfter)
                if (playerMateBefore == null && hasValidCpBefore && playerEvalBefore <= -600 && mateDist >= 5) {
                    return MoveClassificationOutcome(
                        cpl = max(cpl, 250),
                        winProbBefore = winProbBefore,
                        winProbAfter = winProbAfter,
                        winProbLoss = winProbLoss,
                        moveAccuracy = moveAccuracy,
                        category = MoveClassification.MISTAKE
                    )
                }
                return MoveClassificationOutcome(
                    cpl = max(cpl, 600),
                    winProbBefore = winProbBefore,
                    winProbAfter = winProbAfter,
                    winProbLoss = winProbLoss,
                    moveAccuracy = moveAccuracy,
                    category = MoveClassification.BLUNDER
                )
            }

            // Case B: Opponent ALREADY had forced mate against player
            if (playerMateBefore != null && playerMateBefore < 0 && playerMateAfter != null && playerMateAfter < 0) {
                val distBefore = abs(playerMateBefore)
                val distAfter = abs(playerMateAfter)
                val stepsShortened = distBefore - distAfter

                if (isBestMoveChoice || distAfter >= distBefore) {
                    return MoveClassificationOutcome(0, winProbBefore, winProbAfter, 0.0, 100.0, MoveClassification.BEST)
                }
                if ((distAfter == 1 && distBefore >= 2) || stepsShortened >= 3) {
                    return MoveClassificationOutcome(stepsShortened * 120, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.BLUNDER)
                }
                if (stepsShortened == 2) {
                    return MoveClassificationOutcome(220, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.MISTAKE)
                }
                return MoveClassificationOutcome(120, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.INACCURACY)
            }

            // Case C: Player HAD forced mate (playerMateBefore > 0)
            if (playerMateBefore != null && playerMateBefore > 0) {
                if (playerMateAfter != null && playerMateAfter > 0) {
                    val optimalAfterDist = max(1, playerMateBefore - 1)
                    if (isBestMoveChoice || playerMateAfter <= optimalAfterDist) {
                        if (isBestMoveChoice && secondBestCp != null && secondBestMate == null) {
                            return MoveClassificationOutcome(0, winProbBefore, winProbAfter, 0.0, 100.0, MoveClassification.GREAT)
                        }
                        return MoveClassificationOutcome(0, winProbBefore, winProbAfter, 0.0, 100.0, MoveClassification.BEST)
                    }
                    val extraMoves = playerMateAfter - optimalAfterDist
                    return if (extraMoves >= 3) {
                        MoveClassificationOutcome(160 + extraMoves * 25, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.MISSED_CHANCE)
                    } else {
                        MoveClassificationOutcome(110 + extraMoves * 25, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.INACCURACY)
                    }
                }

                // Lost forced mate completely
                return when {
                    playerEvalAfter < 120 -> MoveClassificationOutcome(max(cpl, 550), winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.BLUNDER)
                    playerEvalAfter < 500 || playerMateBefore <= 6 -> MoveClassificationOutcome(max(cpl, 220), winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.MISSED_CHANCE)
                    else -> MoveClassificationOutcome(max(cpl, 130), winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.INACCURACY)
                }
            }

            // Case D: Player did NOT have forced mate before, and found a forced mate
            if (playerMateBefore == null && playerMateAfter != null && playerMateAfter > 0) {
                val category = if (isMaterialSacrificeMove(fenBefore, fenAfter, uci, player)) {
                    MoveClassification.BRILLIANT
                } else {
                    MoveClassification.BEST
                }
                return MoveClassificationOutcome(0, winProbBefore, winProbAfter, 0.0, 100.0, category)
            }
        }

        // 4. Standard Non-Mate Evaluation Classification
        val hasSecondOption = secondBestCp != null || secondBestMate != null
        val secondBestWhiteWinProb = if (hasSecondOption) {
            AccuracyCalculator.cpToWhiteWinProbability(secondBestCp, secondBestMate)
        } else null
        val playerSecondBestWinProb = secondBestWhiteWinProb?.let {
            AccuracyCalculator.toPlayerWinProbability(it, player)
        }
        val playerSecondBestCp = secondBestCp?.let { it * sign }

        // 4.1 Brilliant, Great, Best
        if (isBestMoveChoice && winProbLoss <= 2.0) {
            if (playerWinProbAfter >= 50.0 && playerEvalBefore < 550 && isMaterialSacrificeMove(fenBefore, fenAfter, uci, player)) {
                val advCp = if (playerSecondBestCp != null) playerEvalBefore - playerSecondBestCp else 120
                val advWinProb = if (playerSecondBestWinProb != null) playerWinProbBefore - playerSecondBestWinProb else 10.0
                if (advCp >= 90 || advWinProb >= 8.0) {
                    return MoveClassificationOutcome(0, winProbBefore, winProbAfter, 0.0, 100.0, MoveClassification.BRILLIANT)
                }
            }

            if (playerSecondBestCp != null && playerSecondBestWinProb != null && playerWinProbAfter >= 35.0) {
                val cpGap = playerEvalBefore - playerSecondBestCp
                val winProbGap = playerWinProbBefore - playerSecondBestWinProb
                if (cpGap >= 120 && winProbGap >= 11.0) {
                    return MoveClassificationOutcome(0, winProbBefore, winProbAfter, 0.0, 100.0, MoveClassification.GREAT)
                }
            }

            return MoveClassificationOutcome(0, winProbBefore, winProbAfter, 0.0, 100.0, MoveClassification.BEST)
        }

        // 4.2 Near-Best / Excellent
        if (cpl <= thresholds.excellentMaxCpl && winProbLoss <= 6.0) {
            return MoveClassificationOutcome(cpl, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.EXCELLENT)
        }

        // 4.3 Good Move
        if (cpl <= thresholds.goodMaxCpl && winProbLoss <= 11.5) {
            return MoveClassificationOutcome(cpl, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.GOOD)
        }
        if (winProbLoss <= 5.0) {
            return MoveClassificationOutcome(cpl, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.GOOD)
        }

        // 4.4 Missed Chance
        val pieceLost = cpl >= 200 && doesMoveLosePiece(fenBefore, fenAfter, player, opponentBestPvUci)
        if (!pieceLost &&
            playerWinProbBefore >= 72.0 &&
            playerEvalBefore >= 200 &&
            playerWinProbAfter in 38.0..62.0 &&
            winProbLoss in 14.0..35.0
        ) {
            return MoveClassificationOutcome(cpl, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.MISSED_CHANCE)
        }

        // Dynamic Win-Probability boundaries scaled by user-configured CPL thresholds
        val inaccuracyWinProbMax = max(10, ((thresholds.inaccuracyMaxCpl / 175.0) * 19.0).roundToInt())
        val mistakeWinProbMax = max(inaccuracyWinProbMax + 6, ((thresholds.mistakeMaxCpl / 300.0) * 32.0).roundToInt())

        // 4.5 Inaccuracy
        if (!pieceLost &&
            winProbLoss <= inaccuracyWinProbMax &&
            (cpl <= thresholds.inaccuracyMaxCpl || winProbLoss <= 12.0)
        ) {
            return MoveClassificationOutcome(cpl, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, MoveClassification.INACCURACY)
        }

        // 4.6 Mistake vs Blunder
        val isVerifiedMaterialBlunder = pieceLost && winProbLoss >= 16.0 && cpl >= 200
        val isCatastrophicEvalBlunder = winProbLoss > mistakeWinProbMax ||
            (cpl > thresholds.mistakeMaxCpl && winProbLoss >= 20.0)

        val category = if (isVerifiedMaterialBlunder || isCatastrophicEvalBlunder) {
            MoveClassification.BLUNDER
        } else {
            MoveClassification.MISTAKE
        }
        return MoveClassificationOutcome(cpl, winProbBefore, winProbAfter, winProbLoss, moveAccuracy, category)
    }

    fun reclassifyMoveAnalyses(
        nodes: List<MoveNode>,
        thresholds: ClassificationThresholds,
        openingBook: OpeningBookService? = null
    ) {
        for (node in nodes) {
            val a = node.analysis ?: continue
            val secondLine = a.alternativeLines.firstOrNull { it.multipv == 2 }
            val updated = classifyMoveFromEvaluations(
                player = a.player,
                uci = a.uci,
                fenBefore = a.fenBefore,
                fenAfter = a.fenAfter,
                bestMoveUci = a.bestMoveUci,
                evalBeforeCp = a.evalBeforeCp,
                evalAfterCp = a.evalAfterCp,
                mateBefore = a.mateBefore,
                mateAfter = a.mateAfter,
                secondBestCp = secondLine?.cp,
                secondBestMate = secondLine?.mate,
                alternativeLines = a.alternativeLines,
                isTheoretical = a.isTheoretical,
                thresholds = thresholds,
                openingBook = openingBook
            )
            node.analysis = a.copy(
                cpl = updated.cpl,
                winProbBefore = updated.winProbBefore,
                winProbAfter = updated.winProbAfter,
                winProbLoss = updated.winProbLoss,
                moveAccuracy = updated.moveAccuracy,
                category = updated.category
            )
        }
    }
}

data class CachedFenEval(
    val bestMove: String,
    val lines: List<EngineLine>,
    val cp: Int?,
    val mate: Int?
)

@Singleton
class MoveAnalyzerService @Inject constructor(
    private val stockfishClient: StockfishUciClient,
    private val openingBook: OpeningBookService
) {

    @Volatile
    private var batchCancelled = false

    fun cancelBatchAnalysis() {
        batchCancelled = true
        stockfishClient.stop()
    }

    suspend fun evaluateSingleMoveNode(
        prevNode: MoveNode,
        currNode: MoveNode,
        depth: Int = 12,
        thresholds: ClassificationThresholds = MoveClassifier.DEFAULT_THRESHOLDS
    ): MoveAnalysis {
        stockfishClient.init()
        val evalDepth = stockfishClient.normalizeDepth(depth)

        val beforeRes = stockfishClient.evaluateFenAsync(prevNode.fen, evalDepth, 2)
        val evalBeforeLines = beforeRes.lines
        val bestMoveBefore = beforeRes.bestMove.ifEmpty {
            evalBeforeLines.firstOrNull()?.pv?.firstOrNull().orEmpty()
        }
        val cpBefore = evalBeforeLines.firstOrNull()?.cp
        val mateBefore = evalBeforeLines.firstOrNull()?.mate

        val afterRes = stockfishClient.evaluateFenAsync(currNode.fen, evalDepth, 2)
        val primaryAfter = afterRes.lines.firstOrNull()
        val cpAfter = primaryAfter?.cp
        val mateAfter = primaryAfter?.mate
        val opponentBestPvUci = primaryAfter?.pv ?: emptyList()

        val bestLineUci = evalBeforeLines.firstOrNull()?.pv
            ?: if (bestMoveBefore.isNotEmpty()) listOf(bestMoveBefore) else emptyList()
        val bestLineSan = ChesslibHelper.uciLineToSan(prevNode.fen, bestLineUci, 6)
        val bestMoveSan = bestLineSan.firstOrNull() ?: currNode.san

        val isTheoretical = currNode.ply <= 24 && (
            openingBook.isBookMove(prevNode.fen, currNode.san, currNode.fen) ||
                openingBook.isBookMove(prevNode.fen, currNode.uci, currNode.fen)
            )
        val secondLine = evalBeforeLines.firstOrNull { it.multipv == 2 }

        val classification = MoveClassifier.classifyMoveFromEvaluations(
            player = currNode.color,
            uci = currNode.uci,
            fenBefore = prevNode.fen,
            fenAfter = currNode.fen,
            bestMoveUci = bestMoveBefore,
            evalBeforeCp = cpBefore,
            evalAfterCp = cpAfter,
            mateBefore = mateBefore,
            mateAfter = mateAfter,
            secondBestCp = secondLine?.cp,
            secondBestMate = secondLine?.mate,
            alternativeLines = evalBeforeLines,
            opponentBestPvUci = opponentBestPvUci,
            isTheoretical = isTheoretical,
            thresholds = thresholds,
            openingBook = openingBook
        )

        return MoveAnalysis(
            nodeId = currNode.id,
            ply = currNode.ply,
            moveNumber = currNode.moveNumber,
            player = currNode.color,
            san = currNode.san,
            uci = currNode.uci,
            fenBefore = prevNode.fen,
            fenAfter = currNode.fen,
            bestMoveUci = bestMoveBefore,
            bestMoveSan = bestMoveSan,
            evalBeforeCp = cpBefore ?: 0,
            evalAfterCp = cpAfter ?: 0,
            mateBefore = mateBefore,
            mateAfter = mateAfter,
            winProbBefore = classification.winProbBefore,
            winProbAfter = classification.winProbAfter,
            cpl = classification.cpl,
            winProbLoss = classification.winProbLoss,
            moveAccuracy = classification.moveAccuracy,
            category = classification.category,
            bestLineUci = bestLineUci,
            bestLineSan = bestLineSan,
            alternativeLines = evalBeforeLines,
            depth = evalDepth,
            isTheoretical = isTheoretical
        )
    }

    suspend fun analyzeGameNodes(
        rootNode: MoveNode,
        depth: Int,
        thresholds: ClassificationThresholds,
        onProgress: ((completedPlies: Int, totalPlies: Int, analyzedNodeId: String) -> Unit)? = null
    ): List<MoveAnalysis> {
        batchCancelled = false
        stockfishClient.init()

        val mainlineNodes = VariationTree.getMainlineNodes(rootNode)
        val openingSummary = openingBook.analyzeMainlineOpening(mainlineNodes)

        val edges = mutableListOf<Pair<MoveNode, MoveNode>>()
        fun collectEdges(node: MoveNode) {
            for (child in node.children) {
                edges.add(node to child)
                collectEdges(child)
            }
        }
        collectEdges(rootNode)

        val totalPlies = edges.size
        if (totalPlies == 0) return emptyList()

        val evalCache = HashMap<String, CachedFenEval>(edges.size + 4)
        val batchDepth = stockfishClient.normalizeDepth(depth)

        suspend fun getOrEvaluateFen(fen: String): CachedFenEval {
            evalCache[fen]?.let { return it }
            val evalResult = stockfishClient.evaluateFenAsync(fen, batchDepth, 2)
            val primaryLine = evalResult.lines.firstOrNull()
            val entry = CachedFenEval(
                bestMove = evalResult.bestMove.ifEmpty { primaryLine?.pv?.firstOrNull().orEmpty() },
                lines = evalResult.lines,
                cp = primaryLine?.cp,
                mate = primaryLine?.mate
            )
            evalCache[fen] = entry
            return entry
        }

        val mainlineAnalyses = mutableListOf<MoveAnalysis>()

        for (i in edges.indices) {
            currentCoroutineContext().ensureActive()
            if (batchCancelled) {
                throw CancellationException("Анализ отменён")
            }

            val (prevNode, currNode) = edges[i]
            val evalBefore = getOrEvaluateFen(prevNode.fen)

            currentCoroutineContext().ensureActive()
            if (batchCancelled) {
                throw CancellationException("Анализ отменён")
            }

            val evalAfter = getOrEvaluateFen(currNode.fen)

            currentCoroutineContext().ensureActive()
            if (batchCancelled) {
                throw CancellationException("Анализ отменён")
            }

            val bestMoveUci = evalBefore.bestMove
            val bestLineUci = evalBefore.lines.firstOrNull()?.pv
                ?: if (bestMoveUci.isNotEmpty()) listOf(bestMoveUci) else emptyList()
            val bestLineSan = ChesslibHelper.uciLineToSan(prevNode.fen, bestLineUci, 6)
            val bestMoveSan = bestLineSan.firstOrNull() ?: currNode.san
            val opponentBestPvUci = evalAfter.lines.firstOrNull()?.pv ?: emptyList()

            val isMainlineBookMove = currNode.isMainline &&
                openingSummary.lastTheoreticalPly > 0 &&
                currNode.ply <= openingSummary.lastTheoreticalPly &&
                (openingSummary.firstOutOfBookPly == null || currNode.ply < openingSummary.firstOutOfBookPly)

            val isVariationBookMove = !currNode.isMainline &&
                currNode.ply <= 24 && (
                openingBook.isBookMove(prevNode.fen, currNode.san, currNode.fen) ||
                    openingBook.isBookMove(prevNode.fen, currNode.uci, currNode.fen)
                )

            val isTheoretical = isMainlineBookMove || isVariationBookMove
            val secondLine = evalBefore.lines.firstOrNull { it.multipv == 2 }

            val classification = MoveClassifier.classifyMoveFromEvaluations(
                player = currNode.color,
                uci = currNode.uci,
                fenBefore = prevNode.fen,
                fenAfter = currNode.fen,
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

            val moveAnalysis = MoveAnalysis(
                nodeId = currNode.id,
                ply = currNode.ply,
                moveNumber = currNode.moveNumber,
                player = currNode.color,
                san = currNode.san,
                uci = currNode.uci,
                fenBefore = prevNode.fen,
                fenAfter = currNode.fen,
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
                depth = batchDepth,
                isTheoretical = isTheoretical
            )

            currNode.analysis = moveAnalysis
            if (currNode.isMainline) {
                mainlineAnalyses.add(moveAnalysis)
            }
            onProgress?.invoke(i + 1, totalPlies, currNode.id)
        }

        openingBook.analyzeMainlineOpening(mainlineNodes)
        return mainlineAnalyses
    }
}
