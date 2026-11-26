package com.example.core.analysis

import com.example.domain.model.MoveClassification
import com.example.domain.model.PieceColor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class MoveWinProbLossResult(
    val winProbBefore: Double,
    val winProbAfter: Double,
    val winProbLoss: Double
)

object AccuracyCalculator {
    const val WIN_PROB_LOGISTIC_COEFFICIENT: Double = -0.00368208
    const val MAX_CLAMPED_CP: Double = 4000.0

    const val LICHESS_ACCURACY_MULTIPLIER: Double = 103.1668100711649
    const val LICHESS_ACCURACY_DECAY: Double = -0.04354415386753951
    const val LICHESS_ACCURACY_INTERCEPT: Double = -3.166924740191411
    const val LICHESS_UNCERTAINTY_BONUS: Double = 1.0

    private fun roundTo(value: Double, decimals: Int): Double {
        if (!value.isFinite()) return 0.0
        val factor = when (decimals) {
            1 -> 10.0
            2 -> 100.0
            else -> 1.0
        }
        return round(value * factor) / factor
    }

    /**
     * Converts a White-perspective evaluation (`cp` or `mate`) into White's Expected Win Probability (0..100%).
     */
    fun cpToWhiteWinProbability(cp: Double? = null, mate: Int? = null): Double {
        if (mate != null) {
            if (mate == 0) return 50.0
            val dist = max(1, abs(mate))
            val mateDiscount = min(2.4, (dist - 1) * 0.25)
            return if (mate > 0) 100.0 - mateDiscount else mateDiscount
        }

        if (cp == null || cp.isNaN()) return 50.0
        if (!cp.isFinite()) return if (cp > 0) 100.0 else 0.0
        if (cp == 0.0) return 50.0

        val clampedCp = cp.coerceIn(-MAX_CLAMPED_CP, MAX_CLAMPED_CP)
        val winProb = 50.0 + 50.0 * (2.0 / (1.0 + exp(WIN_PROB_LOGISTIC_COEFFICIENT * clampedCp)) - 1.0)
        return winProb.coerceIn(0.0, 100.0)
    }

    fun cpToWhiteWinProbability(cp: Int? = null, mate: Int? = null): Double =
        cpToWhiteWinProbability(cp?.toDouble(), mate)

    fun toPlayerWinProbability(whiteWinProb: Double, player: PieceColor): Double {
        val safeWhite = if (whiteWinProb.isFinite()) whiteWinProb.coerceIn(0.0, 100.0) else 50.0
        return if (player == PieceColor.WHITE) safeWhite else 100.0 - safeWhite
    }

    /**
     * Dedicated mate-aware calculation of Expected Win Probability Loss (0..100) for a single move.
     */
    fun computeMoveWinProbLoss(
        player: PieceColor,
        evalBeforeCp: Int? = null,
        evalAfterCp: Int? = null,
        mateBefore: Int? = null,
        mateAfter: Int? = null,
        isBestMove: Boolean = false,
        bestAlternativeMateAfter: Int? = null
    ): MoveWinProbLossResult {
        val winProbBefore = cpToWhiteWinProbability(evalBeforeCp, mateBefore)
        val winProbAfter = cpToWhiteWinProbability(evalAfterCp, mateAfter)

        val playerWinProbBefore = toPlayerWinProbability(winProbBefore, player)
        val playerWinProbAfter = toPlayerWinProbability(winProbAfter, player)
        val rawWinProbLoss = max(0.0, playerWinProbBefore - playerWinProbAfter)

        if (isBestMove) {
            return MoveWinProbLossResult(
                winProbBefore = winProbBefore,
                winProbAfter = winProbAfter,
                winProbLoss = 0.0
            )
        }

        val sign = if (player == PieceColor.WHITE) 1 else -1
        val playerEvalBefore = (evalBeforeCp ?: 0) * sign
        val playerEvalAfter = (evalAfterCp ?: 0) * sign

        val playerMateBefore = mateBefore?.takeIf { it != 0 }?.let { it * sign }
        val playerMateAfter = mateAfter?.takeIf { it != 0 }?.let { it * sign }
        val playerBestAltMate = bestAlternativeMateAfter?.takeIf { it != 0 }?.let { it * sign }

        if (playerMateBefore == null && playerMateAfter == null) {
            return MoveWinProbLossResult(
                winProbBefore = winProbBefore,
                winProbAfter = winProbAfter,
                winProbLoss = roundTo(rawWinProbLoss, 2)
            )
        }

        var effectiveLoss = rawWinProbLoss

        // Case 1: Opponent did NOT have forced mate before, and now HAS forced mate against player
        if ((playerMateBefore == null || playerMateBefore > 0) && playerMateAfter != null && playerMateAfter < 0) {
            val distAfter = abs(playerMateAfter)
            effectiveLoss = when {
                playerMateBefore != null && playerMateBefore > 0 -> max(effectiveLoss, 85.0)
                playerEvalBefore <= -600 && distAfter >= 5 -> max(effectiveLoss, 24.0)
                else -> max(effectiveLoss, if (distAfter == 1) 65.0 else 48.0)
            }
        }
        // Case 2: Opponent ALREADY had forced mate against player (playerMateBefore < 0)
        else if (playerMateBefore != null && playerMateBefore < 0) {
            if (playerMateAfter != null && playerMateAfter < 0) {
                val distBefore = abs(playerMateBefore)
                val distAfter = abs(playerMateAfter)
                val optimalDist = if (playerBestAltMate != null && playerBestAltMate < 0) {
                    abs(playerBestAltMate)
                } else {
                    distBefore
                }
                val stepsShortened = max(0, optimalDist - distAfter)
                effectiveLoss = when {
                    stepsShortened == 0 -> 0.0
                    (distAfter == 1 && optimalDist >= 2) || stepsShortened >= 3 ->
                        max(effectiveLoss, 38.0 + min(25, stepsShortened * 4))
                    stepsShortened == 2 -> max(effectiveLoss, 24.0)
                    else -> max(effectiveLoss, 13.0)
                }
            } else {
                effectiveLoss = 0.0
            }
        }
        // Case 3: Player HAD forced mate (playerMateBefore > 0)
        else if (playerMateBefore != null && playerMateBefore > 0) {
            val distBefore = playerMateBefore
            val optimalDistAfter = if (playerBestAltMate != null && playerBestAltMate > 0) {
                playerBestAltMate
            } else {
                max(1, distBefore - 1)
            }

            if (playerMateAfter != null && playerMateAfter > 0) {
                val delaySteps = playerMateAfter - optimalDistAfter
                effectiveLoss = when {
                    delaySteps <= 0 -> 0.0
                    delaySteps == 1 -> max(effectiveLoss, 9.0)
                    delaySteps == 2 -> max(effectiveLoss, 15.0)
                    else -> max(effectiveLoss, 20.0 + min(20, (delaySteps - 2) * 4))
                }
            } else {
                effectiveLoss = when {
                    playerEvalAfter < 120 -> max(effectiveLoss, 52.0)
                    playerEvalAfter < 500 || distBefore <= 6 -> max(effectiveLoss, 24.0)
                    else -> max(effectiveLoss, 14.0)
                }
            }
        }

        return MoveWinProbLossResult(
            winProbBefore = winProbBefore,
            winProbAfter = winProbAfter,
            winProbLoss = roundTo(effectiveLoss.coerceIn(0.0, 100.0), 2)
        )
    }

    fun calculateRawSingleMoveAccuracy(winProbLoss: Double): Double {
        if (winProbLoss.isNaN()) return 0.0
        if (winProbLoss <= 0.0) return 100.0
        if (!winProbLoss.isFinite()) return 0.0
        val raw = LICHESS_ACCURACY_MULTIPLIER * exp(LICHESS_ACCURACY_DECAY * winProbLoss) +
            LICHESS_ACCURACY_INTERCEPT +
            LICHESS_UNCERTAINTY_BONUS
        return raw.coerceIn(0.0, 100.0)
    }

    fun calculateSingleMoveAccuracy(winProbLoss: Double): Double =
        roundTo(calculateRawSingleMoveAccuracy(winProbLoss), 1)

    fun createEmptyCategoryCounts(): MutableMap<MoveClassification, Int> =
        MoveClassification.entries.associateWith { 0 }.toMutableMap()

    private fun computeStandardDeviation(values: List<Double>): Double {
        if (values.size <= 1) return 0.0
        val mean = values.sum() / values.size
        val variance = values.sumOf { val d = it - mean; d * d } / (values.size - 1)
        return sqrt(max(0.0, variance))
    }

    private fun computeWeightedMean(pairs: List<Pair<Double, Double>>): Double {
        if (pairs.isEmpty()) return 0.0
        var weightedSum = 0.0
        var totalWeight = 0.0
        for ((value, rawWeight) in pairs) {
            val w = if (rawWeight.isFinite() && rawWeight > 0.0) rawWeight else 0.5
            weightedSum += value * w
            totalWeight += w
        }
        return if (totalWeight > 0.0) weightedSum / totalWeight else 0.0
    }

    private fun computeHarmonicMean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        var reciprocalSum = 0.0
        for (v in values) {
            val safeVal = max(1.0, v)
            reciprocalSum += 1.0 / safeVal
        }
        return if (reciprocalSum > 0.0) values.size / reciprocalSum else 0.0
    }

    fun computeLichessPlayerAccuracy(entries: List<Pair<Double, Double>>): Double {
        if (entries.isEmpty()) return 0.0
        if (entries.size == 1) {
            return roundTo(entries[0].first.coerceIn(0.0, 100.0), 1)
        }
        val weightedMean = computeWeightedMean(entries)
        val harmonicMean = computeHarmonicMean(entries.map { it.first })
        val combined = (weightedMean + harmonicMean) / 2.0
        return roundTo(combined.coerceIn(0.0, 100.0), 1)
    }

    fun calculateGameStatistics(analyses: List<MoveAnalysis>): GameStatistics {
        val whiteCounts = createEmptyCategoryCounts()
        val blackCounts = createEmptyCategoryCounts()

        if (analyses.isEmpty()) {
            return GameStatistics(
                whiteAccuracy = 0.0,
                blackAccuracy = 0.0,
                whiteAvgCpl = 0,
                blackAvgCpl = 0,
                whiteCounts = whiteCounts,
                blackCounts = blackCounts
            )
        }

        val allWhiteWinPercents = mutableListOf<Double>()
        val firstBefore = analyses[0].winProbBefore.takeIf { it.isFinite() } ?: 50.0
        allWhiteWinPercents.add(firstBefore)
        for (item in analyses) {
            allWhiteWinPercents.add(item.winProbAfter.takeIf { it.isFinite() } ?: 50.0)
        }

        val totalPlies = analyses.size
        val windowSize = max(2, min(8, floor(totalPlies / 10.0).toInt()))

        val slidingWindows = mutableListOf<List<Double>>()
        if (allWhiteWinPercents.size >= windowSize) {
            for (i in 0..(allWhiteWinPercents.size - windowSize)) {
                slidingWindows.add(allWhiteWinPercents.subList(i, i + windowSize))
            }
        } else if (allWhiteWinPercents.isNotEmpty()) {
            slidingWindows.add(allWhiteWinPercents.toList())
        }

        val paddedWindows = mutableListOf<List<Double>>()
        if (slidingWindows.isNotEmpty()) {
            val padCount = max(0, windowSize - 2)
            repeat(padCount) {
                paddedWindows.add(slidingWindows[0])
            }
            paddedWindows.addAll(slidingWindows)
        }

        val plyVolatilityWeights = analyses.indices.map { idx ->
            val winSlice = paddedWindows.getOrNull(min(idx, max(0, paddedWindows.size - 1)))
                ?: listOf(50.0, 50.0)
            val sd = computeStandardDeviation(winSlice)
            sd.coerceIn(0.5, 12.0)
        }

        val whitePrimaryEntries = mutableListOf<Pair<Double, Double>>()
        val blackPrimaryEntries = mutableListOf<Pair<Double, Double>>()
        val whiteFallbackEntries = mutableListOf<Pair<Double, Double>>()
        val blackFallbackEntries = mutableListOf<Pair<Double, Double>>()

        val whiteCpls = mutableListOf<Int>()
        val blackCpls = mutableListOf<Int>()

        for (i in analyses.indices) {
            val item = analyses[i]
            val weight = plyVolatilityWeights.getOrElse(i) { 0.5 }
            val counts = if (item.player == PieceColor.WHITE) whiteCounts else blackCounts
            counts[item.category] = (counts[item.category] ?: 0) + 1

            val isBookOrForced = item.category == MoveClassification.BOOK ||
                item.category == MoveClassification.FORCED
            val hasValidAccuracy = item.moveAccuracy.isFinite() && item.moveAccuracy in 0.0..100.0

            if (hasValidAccuracy) {
                val entry = item.moveAccuracy to weight
                if (item.player == PieceColor.WHITE) {
                    whiteFallbackEntries.add(entry)
                    if (!isBookOrForced) whitePrimaryEntries.add(entry)
                } else {
                    blackFallbackEntries.add(entry)
                    if (!isBookOrForced) blackPrimaryEntries.add(entry)
                }
            }

            val isMateMove = item.mateBefore != null || item.mateAfter != null
            if (item.cpl >= 0 && !isBookOrForced && !isMateMove) {
                if (item.player == PieceColor.WHITE) {
                    whiteCpls.add(min(item.cpl, 1000))
                } else {
                    blackCpls.add(min(item.cpl, 1000))
                }
            }
        }

        fun computePlayerAcc(
            primary: List<Pair<Double, Double>>,
            fallback: List<Pair<Double, Double>>
        ): Double = computeLichessPlayerAccuracy(if (primary.isNotEmpty()) primary else fallback)

        fun computeAvgCpl(arr: List<Int>): Int =
            if (arr.isEmpty()) 0 else (arr.sum().toDouble() / arr.size).roundToInt()

        return GameStatistics(
            whiteAccuracy = computePlayerAcc(whitePrimaryEntries, whiteFallbackEntries),
            blackAccuracy = computePlayerAcc(blackPrimaryEntries, blackFallbackEntries),
            whiteAvgCpl = computeAvgCpl(whiteCpls),
            blackAvgCpl = computeAvgCpl(blackCpls),
            whiteCounts = whiteCounts,
            blackCounts = blackCounts
        )
    }
}
