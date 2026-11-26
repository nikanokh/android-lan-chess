package com.example.feature.analysis

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.core.analysis.AccuracyCalculator
import com.example.core.analysis.ChesslibHelper
import com.example.core.analysis.EvalBarPosition
import com.example.core.analysis.EvalScoreDisplay
import com.example.core.analysis.TerminalBoardOutcome
import com.example.core.ui.theme.ChessTheme
import com.example.domain.model.PieceColor
import java.util.Locale
import kotlin.math.abs

fun formatEvalScore(
    cp: Int? = null,
    mate: Int? = null,
    decimals: Int = 2,
    fen: String? = null
): String {
    val terminal = ChesslibHelper.terminalOutcomeFromFen(fen)
    if (terminal != null) return terminal.scoreLabel

    if (mate != null) {
        if (mate == 0) return "1/2-1/2"
        return "${if (mate > 0) "" else "-"}M${abs(mate)}"
    }
    if (cp == null) {
        return String.format(Locale.US, "%.${decimals}f", 0.0)
    }
    val pawns = cp / 100.0
    if (abs(pawns) < 0.005) {
        return String.format(Locale.US, "%.${decimals}f", 0.0)
    }
    val effectiveDecimals = if (abs(pawns) >= 10.0) 1 else decimals
    val formatted = String.format(Locale.US, "%.${effectiveDecimals}f", abs(pawns))
    return when {
        pawns > 0 -> "+$formatted"
        pawns < 0 -> "-$formatted"
        else -> String.format(Locale.US, "%.${decimals}f", 0.0)
    }
}

@Composable
fun EvaluationBar(
    cp: Int?,
    mate: Int?,
    fen: String?,
    flipped: Boolean,
    modifier: Modifier = Modifier,
    position: EvalBarPosition = EvalBarPosition.LEFT,
    scoreDisplay: EvalScoreDisplay = EvalScoreDisplay.INSIDE_BAR
) {
    val terminal = ChesslibHelper.terminalOutcomeFromFen(fen)
    val rawWhitePercent = when {
        terminal is TerminalBoardOutcome.Checkmate ->
            if (terminal.winner == PieceColor.WHITE) 100f else 0f
        terminal is TerminalBoardOutcome.Draw -> 50f
        mate != null -> if (mate > 0) 100f else 0f
        else -> AccuracyCalculator.cpToWhiteWinProbability(cp, mate).toFloat().coerceIn(4f, 96f)
    }

    val animatedWhiteFraction by animateFloatAsState(
        targetValue = (rawWhitePercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 260),
        label = "evalBarWhiteFraction"
    )

    val isWhiteAdvantage = when {
        terminal is TerminalBoardOutcome.Checkmate -> terminal.winner == PieceColor.WHITE
        terminal is TerminalBoardOutcome.Draw -> true
        mate != null -> mate >= 0
        else -> (cp ?: 0) >= 0
    }

    val compactLabel = formatEvalScore(cp = cp, mate = mate, decimals = 2, fen = fen)
    val isDraw = terminal is TerminalBoardOutcome.Draw
    val isHorizontal = position == EvalBarPosition.TOP

    when {
        isHorizontal && scoreDisplay == EvalScoreDisplay.SEPARATE -> {
            Row(
                modifier = modifier.testTag("evaluation_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EvalBarTrack(
                    animatedWhiteFraction = animatedWhiteFraction,
                    isHorizontal = true,
                    flipped = flipped,
                    isWhiteAdvantage = isWhiteAdvantage,
                    isDraw = isDraw,
                    compactLabel = compactLabel,
                    showInsideScore = false,
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                )
                EvalScoreBadge(
                    label = compactLabel,
                    isWhiteAdvantage = isWhiteAdvantage,
                    isDraw = isDraw,
                    modifier = Modifier
                        .width(46.dp)
                        .height(22.dp)
                        .testTag("eval_bar_separate_badge")
                )
            }
        }
        !isHorizontal && scoreDisplay == EvalScoreDisplay.SEPARATE -> {
            Column(
                modifier = modifier.testTag("evaluation_bar"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EvalBarTrack(
                    animatedWhiteFraction = animatedWhiteFraction,
                    isHorizontal = false,
                    flipped = flipped,
                    isWhiteAdvantage = isWhiteAdvantage,
                    isDraw = isDraw,
                    compactLabel = compactLabel,
                    showInsideScore = false,
                    modifier = Modifier
                        .width(24.dp)
                        .weight(1f)
                )
                EvalScoreBadge(
                    label = compactLabel,
                    isWhiteAdvantage = isWhiteAdvantage,
                    isDraw = isDraw,
                    modifier = Modifier
                        .width(40.dp)
                        .height(20.dp)
                        .testTag("eval_bar_separate_badge")
                )
            }
        }
        isHorizontal -> {
            val barHeight = if (scoreDisplay == EvalScoreDisplay.HIDDEN) 14.dp else 20.dp
            EvalBarTrack(
                animatedWhiteFraction = animatedWhiteFraction,
                isHorizontal = true,
                flipped = flipped,
                isWhiteAdvantage = isWhiteAdvantage,
                isDraw = isDraw,
                compactLabel = compactLabel,
                showInsideScore = scoreDisplay == EvalScoreDisplay.INSIDE_BAR,
                modifier = modifier
                    .height(barHeight)
                    .testTag("evaluation_bar")
            )
        }
        else -> {
            val barWidth = if (scoreDisplay == EvalScoreDisplay.HIDDEN) 20.dp else 28.dp
            EvalBarTrack(
                animatedWhiteFraction = animatedWhiteFraction,
                isHorizontal = false,
                flipped = flipped,
                isWhiteAdvantage = isWhiteAdvantage,
                isDraw = isDraw,
                compactLabel = compactLabel,
                showInsideScore = scoreDisplay == EvalScoreDisplay.INSIDE_BAR,
                modifier = modifier
                    .width(barWidth)
                    .testTag("evaluation_bar")
            )
        }
    }
}

@Composable
private fun EvalBarTrack(
    animatedWhiteFraction: Float,
    isHorizontal: Boolean,
    flipped: Boolean,
    isWhiteAdvantage: Boolean,
    isDraw: Boolean,
    compactLabel: String,
    showInsideScore: Boolean,
    modifier: Modifier = Modifier
) {
    val whiteBg = Color(0xFFFFFFFF)
    val blackBg = Color(0xFF1F1D1B)
    val tickColor = ChessTheme.colors.accentGreen.copy(alpha = 0.85f)
    val shape = RoundedCornerShape(6.dp)
    val whitePrimarySide = !flipped // bottom when vertical, left when horizontal

    Box(
        modifier = modifier
            .clip(shape)
            .background(blackBg)
            .border(1.dp, ChessTheme.colors.borderSubtle, shape)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
        ) {
            val w = size.width
            val h = size.height
            val fraction = animatedWhiteFraction.coerceIn(0f, 1f)

            if (isHorizontal) {
                val whiteW = w * fraction
                val blackW = w - whiteW
                if (whitePrimarySide) {
                    if (whiteW > 0f) drawRect(color = whiteBg, topLeft = Offset.Zero, size = Size(whiteW, h))
                    if (blackW > 0f) drawRect(color = blackBg, topLeft = Offset(whiteW, 0f), size = Size(blackW, h))
                } else {
                    if (blackW > 0f) drawRect(color = blackBg, topLeft = Offset.Zero, size = Size(blackW, h))
                    if (whiteW > 0f) drawRect(color = whiteBg, topLeft = Offset(blackW, 0f), size = Size(whiteW, h))
                }
                val midX = w / 2f
                drawLine(
                    color = tickColor,
                    start = Offset(midX, 0f),
                    end = Offset(midX, h),
                    strokeWidth = 1.5.dp.toPx()
                )
            } else {
                val whiteH = h * fraction
                val blackH = h - whiteH
                if (whitePrimarySide) {
                    if (blackH > 0f) drawRect(color = blackBg, topLeft = Offset.Zero, size = Size(w, blackH))
                    if (whiteH > 0f) drawRect(color = whiteBg, topLeft = Offset(0f, blackH), size = Size(w, whiteH))
                } else {
                    if (whiteH > 0f) drawRect(color = whiteBg, topLeft = Offset.Zero, size = Size(w, whiteH))
                    if (blackH > 0f) drawRect(color = blackBg, topLeft = Offset(0f, whiteH), size = Size(w, blackH))
                }
                val midY = h / 2f
                drawLine(
                    color = tickColor,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }

        if (showInsideScore) {
            if (isDraw) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(10f)
                        .background(blackBg, RoundedCornerShape(3.dp))
                        .padding(horizontal = if (isHorizontal) 4.dp else 1.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = compactLabel,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (isHorizontal) 10.sp else 6.5.sp,
                            letterSpacing = if (isHorizontal) 0.sp else (-0.5).sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag("eval_bar_label")
                    )
                }
            } else {
                val labelAtWhiteSide = (isWhiteAdvantage && whitePrimarySide) || (!isWhiteAdvantage && !whitePrimarySide)
                val alignment = if (isHorizontal) {
                    if (labelAtWhiteSide) Alignment.CenterStart else Alignment.CenterEnd
                } else {
                    if (labelAtWhiteSide) Alignment.BottomCenter else Alignment.TopCenter
                }
                val labelBg = if (isWhiteAdvantage) whiteBg else blackBg
                val labelColor = if (isWhiteAdvantage) Color.Black else Color.White
                val scoreFontSize = when {
                    isHorizontal -> 10.5.sp
                    compactLabel.length >= 6 -> 7.5.sp
                    compactLabel.length == 5 -> 8.5.sp
                    else -> 9.5.sp
                }
                Box(
                    modifier = Modifier
                        .align(alignment)
                        .zIndex(10f)
                        .padding(
                            vertical = if (isHorizontal) 1.dp else 3.dp,
                            horizontal = if (isHorizontal) 5.dp else 1.dp
                        )
                        .background(labelBg, RoundedCornerShape(3.dp))
                        .padding(horizontal = if (isHorizontal) 3.dp else 1.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = compactLabel,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = scoreFontSize,
                            letterSpacing = if (isHorizontal) 0.sp else (-0.4).sp,
                            color = labelColor,
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag("eval_bar_label")
                    )
                }
            }
        }
    }
}

@Composable
private fun EvalScoreBadge(
    label: String,
    isWhiteAdvantage: Boolean,
    isDraw: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = when {
        isDraw -> Color(0xFF3A3D42)
        isWhiteAdvantage -> Color(0xFFFFFFFF)
        else -> Color(0xFF1F1D1B)
    }
    val fg = when {
        isDraw -> Color.White
        isWhiteAdvantage -> Color.Black
        else -> Color.White
    }
    val border = ChessTheme.colors.borderSubtle
    val shape = RoundedCornerShape(5.dp)
    val fontSize = when {
        label.length >= 6 -> 8.5.sp
        label.length == 5 -> 9.5.sp
        else -> 10.5.sp
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.ExtraBold,
                fontSize = fontSize,
                letterSpacing = (-0.3).sp,
                color = fg,
                textAlign = TextAlign.Center
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag("eval_bar_label")
        )
    }
}
