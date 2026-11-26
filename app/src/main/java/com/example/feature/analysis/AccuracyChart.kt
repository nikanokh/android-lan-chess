package com.example.feature.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.analysis.MoveNode
import com.example.core.analysis.styleMeta
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.MoveClassification
import com.example.domain.model.PieceColor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

data class EvalChartPoint(
    val nodeId: String,
    val ply: Int,
    val moveLabel: String,
    val san: String,
    val visualEval: Float, // -8f..+8f
    val rawScoreText: String,
    val category: MoveClassification,
    val isMajorError: Boolean
)

@Composable
fun AccuracyChart(
    branchNodes: List<MoveNode>,
    currentNodeId: String,
    onSelectNode: (String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false
) {
    val points = remember(branchNodes) {
        val analyzed = branchNodes.filter { it.ply > 0 && it.analysis != null }
        val rawVisuals = analyzed.map { node ->
            val a = node.analysis!!
            if (a.mateAfter != null) {
                if (a.mateAfter > 0) 8f else -8f
            } else {
                (7.6 * tanh(a.evalAfterCp / 360.0)).toFloat()
            }
        }

        val smoothed = rawVisuals.mapIndexed { idx, value ->
            if (idx == 0 || idx == rawVisuals.lastIndex) {
                value
            } else {
                val prev = analyzed[idx - 1].analysis!!
                val curr = analyzed[idx].analysis!!
                val next = analyzed[idx + 1].analysis!!
                if (prev.mateAfter != null || curr.mateAfter != null || next.mateAfter != null) {
                    value
                } else {
                    val dPrev = abs(curr.evalAfterCp - prev.evalAfterCp)
                    val dNext = abs(next.evalAfterCp - curr.evalAfterCp)
                    if (dPrev < 85 && dNext < 85) {
                        rawVisuals[idx - 1] * 0.25f + value * 0.5f + rawVisuals[idx + 1] * 0.25f
                    } else {
                        value
                    }
                }
            }
        }

        analyzed.mapIndexed { idx, node ->
            val a = node.analysis!!
            val label = "${node.moveNumber}${if (node.color == PieceColor.WHITE) "." else "..."}"
            EvalChartPoint(
                nodeId = node.id,
                ply = node.ply,
                moveLabel = label,
                san = node.san,
                visualEval = smoothed[idx],
                rawScoreText = formatEvalScore(a.evalAfterCp, a.mateAfter, 2, node.fen),
                category = a.category,
                isMajorError = a.category == MoveClassification.BLUNDER ||
                    a.category == MoveClassification.MISTAKE ||
                    a.category == MoveClassification.MISSED_CHANCE
            )
        }
    }

    if (points.isEmpty()) return

    val activePoint = points.firstOrNull { it.nodeId == currentNodeId } ?: points.last()
    val colors = ChessTheme.colors

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF3D3A36),
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "График оценки",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFFD4D0C8)
                )
                Text(
                    text = "${activePoint.moveLabel} ${activePoint.san} (${activePoint.rawScoreText})",
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.White
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (expanded) 170.dp else 115.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF4A4641)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(points) {
                            detectTapGestures { offset ->
                                if (points.isEmpty() || size.width <= 0) return@detectTapGestures
                                val idx = if (points.size == 1) {
                                    0
                                } else {
                                    ((offset.x / size.width) * (points.size - 1))
                                        .roundToInt()
                                        .coerceIn(0, points.lastIndex)
                                }
                                onSelectNode(points[idx].nodeId)
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val midY = h / 2f

                    fun xFor(index: Int): Float =
                        if (points.size <= 1) w / 2f else (index.toFloat() / (points.size - 1)) * w

                    fun yFor(eval: Float): Float {
                        val clamped = eval.coerceIn(-8f, 8f)
                        return midY - (clamped / 8f) * (h * 0.46f)
                    }

                    // Build line and areas split at 0
                    val whiteAreaPath = Path().apply { moveTo(xFor(0), midY) }
                    val blackAreaPath = Path().apply { moveTo(xFor(0), midY) }
                    val strokePath = Path()

                    points.forEachIndexed { idx, pt ->
                        val x = xFor(idx)
                        val y = yFor(pt.visualEval)
                        if (idx == 0) strokePath.moveTo(x, y) else strokePath.lineTo(x, y)

                        val whiteY = yFor(pt.visualEval.coerceAtLeast(0f))
                        val blackY = yFor(pt.visualEval.coerceAtMost(0f))
                        whiteAreaPath.lineTo(x, whiteY)
                        blackAreaPath.lineTo(x, blackY)
                    }

                    whiteAreaPath.lineTo(xFor(points.lastIndex), midY)
                    whiteAreaPath.close()
                    blackAreaPath.lineTo(xFor(points.lastIndex), midY)
                    blackAreaPath.close()

                    drawPath(whiteAreaPath, color = Color.White.copy(alpha = 0.90f))
                    drawPath(blackAreaPath, color = Color(0xFF11100F).copy(alpha = 0.94f))

                    // Equilibrium 0.00 dashed orange line
                    drawLine(
                        color = Color(0xFFF39C36),
                        start = Offset(0f, midY),
                        end = Offset(w, midY),
                        strokeWidth = 2.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    )

                    // Active vertical indicator
                    val activeIdx = points.indexOfFirst { it.nodeId == activePoint.nodeId }
                    if (activeIdx >= 0) {
                        val ax = xFor(activeIdx)
                        drawLine(
                            color = Color(0xFF36A9E1),
                            start = Offset(ax, 0f),
                            end = Offset(ax, h),
                            strokeWidth = 3f
                        )
                    }

                    // Outline curve
                    drawPath(
                        path = strokePath,
                        color = Color(0xFFDFDCD5),
                        style = Stroke(width = 2f)
                    )

                    // Error dots
                    points.forEachIndexed { idx, pt ->
                        if (pt.isMajorError) {
                            val center = Offset(xFor(idx), yFor(pt.visualEval))
                            drawCircle(
                                color = Color(pt.category.styleMeta().colorHex),
                                radius = 7f,
                                center = center
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 7f,
                                center = center,
                                style = Stroke(width = 2f)
                            )
                        }
                    }
                }
            }

            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(badgeDrawableFor(activePoint.category)),
                            contentDescription = activePoint.category.title,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${activePoint.moveLabel} ${activePoint.san} — ${activePoint.category.styleMeta().labelRu}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE5E1D8)
                        )
                    }
                    Text(
                        text = activePoint.rawScoreText,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}
