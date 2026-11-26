package com.example.feature.analysis

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.analysis.EngineLine
import com.example.core.analysis.SquareHighlight
import com.example.core.analysis.UserArrow
import com.example.core.ui.board.BoardCoordinates
import com.example.core.ui.board.BoardSquare
import com.example.domain.model.MoveClassification
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class BoardDrawMode {
    MOVE,
    ARROW,
    HIGHLIGHT
}

@DrawableRes
fun badgeDrawableFor(category: MoveClassification): Int = when (category) {
    MoveClassification.BRILLIANT -> R.drawable.badge_brilliant
    MoveClassification.GREAT -> R.drawable.badge_great
    MoveClassification.BEST -> R.drawable.badge_best
    MoveClassification.EXCELLENT -> R.drawable.badge_excellent
    MoveClassification.GOOD -> R.drawable.badge_good
    MoveClassification.BOOK -> R.drawable.badge_book
    MoveClassification.FORCED -> R.drawable.badge_forced
    MoveClassification.INACCURACY -> R.drawable.badge_inaccuracy
    MoveClassification.MISTAKE -> R.drawable.badge_mistake
    MoveClassification.MISSED_CHANCE -> R.drawable.badge_missed_chance
    MoveClassification.BLUNDER -> R.drawable.badge_blunder
}

fun parseHexColor(hex: String, fallback: Color = Color(0xFFF39C36)): Color {
    return try {
        val clean = hex.trim().removePrefix("#")
        val value = clean.toLong(16)
        when (clean.length) {
            6 -> Color(0xFF000000L or value)
            8 -> Color(value)
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

@Composable
fun BoardAnnotationOverlay(
    flipped: Boolean,
    drawMode: BoardDrawMode,
    arrowStartSquare: BoardSquare?,
    activeBrushHex: String,
    userArrows: List<UserArrow>,
    squareHighlights: List<SquareHighlight>,
    engineLines: List<EngineLine>,
    showUserArrows: Boolean,
    showEngineArrows: Boolean,
    enginePrimaryHex: String,
    engineSecondaryHex: String,
    moveBadgeSquare: BoardSquare?,
    moveBadgeCategory: MoveClassification?,
    onAnnotationSquareTap: (BoardSquare) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val boardSizePx = with(density) { minOf(maxWidth, maxHeight).toPx() }
        val squareSizePx = boardSizePx / 8f

        val tapModifier = if (drawMode != BoardDrawMode.MOVE) {
            Modifier.pointerInput(boardSizePx, flipped, drawMode) {
                detectTapGestures { offset ->
                    val sq = BoardCoordinates.squareAtOffset(offset, boardSizePx, flipped)
                    if (sq != null) {
                        onAnnotationSquareTap(sq)
                    }
                }
            }
        } else {
            Modifier
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(tapModifier)
        ) {
            val sqSize = size.width / 8f

            // 1. User square highlights
            if (showUserArrows) {
                for (hl in squareHighlights) {
                    val sq = hl.boardSquare ?: continue
                    val topLeft = BoardCoordinates.squareTopLeft(sq, sqSize, flipped)
                    drawRect(
                        color = parseHexColor(hl.color).copy(alpha = 0.42f),
                        topLeft = topLeft,
                        size = Size(sqSize, sqSize)
                    )
                }
            }

            // 2. Pending arrow start indicator
            if (drawMode == BoardDrawMode.ARROW && arrowStartSquare != null) {
                val topLeft = BoardCoordinates.squareTopLeft(arrowStartSquare, sqSize, flipped)
                drawRect(
                    color = parseHexColor(activeBrushHex).copy(alpha = 0.32f),
                    topLeft = topLeft,
                    size = Size(sqSize, sqSize)
                )
            }

            // 3. Engine arrows (draw secondary first, primary on top)
            if (showEngineArrows && engineLines.isNotEmpty()) {
                val seen = HashSet<String>()
                for (line in engineLines.asReversed()) {
                    val uci = line.pv.firstOrNull() ?: continue
                    if (uci.length < 4) continue
                    val fromSq = BoardSquare.fromAlgebraic(uci.substring(0, 2)) ?: continue
                    val toSq = BoardSquare.fromAlgebraic(uci.substring(2, 4)) ?: continue
                    val pairKey = "${fromSq.algebraic}-${toSq.algebraic}"
                    if (!seen.add(pairKey)) continue

                    val isPrimary = line.multipv == 1
                    val color = parseHexColor(if (isPrimary) enginePrimaryHex else engineSecondaryHex)
                    val alpha = if (isPrimary) 0.85f else 0.58f
                    val widthFactor = if (isPrimary) 0.21f else 0.16f
                    drawChessArrow(
                        from = fromSq,
                        to = toSq,
                        squareSize = sqSize,
                        flipped = flipped,
                        color = color.copy(alpha = alpha),
                        strokeWidth = sqSize * widthFactor
                    )
                }
            }

            // 4. User arrows
            if (showUserArrows) {
                for (arrow in userArrows) {
                    val fromSq = arrow.fromSquare ?: continue
                    val toSq = arrow.toSquare ?: continue
                    val baseColor = parseHexColor(arrow.color)
                    drawChessArrow(
                        from = fromSq,
                        to = toSq,
                        squareSize = sqSize,
                        flipped = flipped,
                        color = baseColor.copy(alpha = arrow.opacity.coerceIn(0.35f, 0.9f)),
                        strokeWidth = sqSize * 0.20f
                    )
                }
            }
        }

        // 5. Move Quality Badge on destination square corner
        if (moveBadgeSquare != null && moveBadgeCategory != null && squareSizePx > 0f) {
            val topLeft = BoardCoordinates.squareTopLeft(moveBadgeSquare, squareSizePx, flipped)
            val badgeSizePx = squareSizePx * 0.40f
            val badgeSizeDp = with(density) { badgeSizePx.toDp() }
            val offsetX = (topLeft.x + squareSizePx - badgeSizePx * 0.86f).roundToInt()
            val offsetY = (topLeft.y - badgeSizePx * 0.12f).roundToInt().coerceAtLeast(0)

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX, offsetY) }
                    .size(badgeSizeDp)
            ) {
                Image(
                    painter = painterResource(badgeDrawableFor(moveBadgeCategory)),
                    contentDescription = moveBadgeCategory.title,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun DrawScope.drawChessArrow(
    from: BoardSquare,
    to: BoardSquare,
    squareSize: Float,
    flipped: Boolean,
    color: Color,
    strokeWidth: Float
) {
    if (from == to) return
    val start = BoardCoordinates.squareCenter(from, squareSize, flipped)
    val end = BoardCoordinates.squareCenter(to, squareSize, flipped)

    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx, dy)
    if (length <= 1f) return

    val ux = dx / length
    val uy = dy / length
    val px = -uy
    val py = ux

    val headLength = maxOf(squareSize * 0.40f, strokeWidth * 2.15f)
    val headWidth = maxOf(squareSize * 0.48f, strokeWidth * 2.55f)

    val lineStart = Offset(start.x + ux * (squareSize * 0.18f), start.y + uy * (squareSize * 0.18f))
    val baseCenter = Offset(end.x - ux * headLength, end.y - uy * headLength)
    val tip = Offset(end.x - ux * (squareSize * 0.04f), end.y - uy * (squareSize * 0.04f))
    val leftCorner = Offset(baseCenter.x + px * (headWidth / 2f), baseCenter.y + py * (headWidth / 2f))
    val rightCorner = Offset(baseCenter.x - px * (headWidth / 2f), baseCenter.y - py * (headWidth / 2f))

    drawLine(
        color = color,
        start = lineStart,
        end = baseCenter,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )

    val headPath = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(leftCorner.x, leftCorner.y)
        lineTo(rightCorner.x, rightCorner.y)
        close()
    }
    drawPath(headPath, color = color)
}
