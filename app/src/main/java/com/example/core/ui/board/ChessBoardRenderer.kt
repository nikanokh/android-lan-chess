package com.example.core.ui.board

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.core.ui.theme.BoardSquareColors
import kotlin.math.roundToInt

/**
 * Pure Canvas DrawScope rendering pipeline for ChessBoard.
 * Designed to avoid heavy per-frame allocations: bitmaps, styles, and colors are supplied pre-cached.
 */
internal object ChessBoardRenderer {

    fun DrawScope.drawSquares(
        squareSize: Float,
        colors: BoardSquareColors,
        flipped: Boolean
    ) {
        val cellSize = Size(squareSize, squareSize)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val square = BoardCoordinates.fromDisplayCell(col = col, row = row, flipped = flipped)
                drawRect(
                    color = if (square.isLightSquare) colors.light else colors.dark,
                    topLeft = Offset(col * squareSize, row * squareSize),
                    size = cellSize
                )
            }
        }
    }

    fun DrawScope.drawLastMoveHighlight(
        lastMove: BoardMove?,
        squareSize: Float,
        colors: BoardSquareColors,
        flipped: Boolean
    ) {
        if (lastMove == null) return
        val cellSize = Size(squareSize, squareSize)
        val squares = arrayOf(lastMove.from, lastMove.to)
        for (square in squares) {
            val topLeft = BoardCoordinates.squareTopLeft(square, squareSize, flipped)
            drawRect(
                color = if (square.isLightSquare) colors.lastMoveLight else colors.lastMoveDark,
                topLeft = topLeft,
                size = cellSize
            )
        }
    }

    fun DrawScope.drawPremoveHighlight(
        premove: BoardPremove?,
        squareSize: Float,
        colors: BoardSquareColors,
        flipped: Boolean
    ) {
        if (premove == null) return
        val cellSize = Size(squareSize, squareSize)
        val squares = arrayOf(premove.from, premove.to)
        for (square in squares) {
            val topLeft = BoardCoordinates.squareTopLeft(square, squareSize, flipped)
            drawRect(
                color = if (square.isLightSquare) colors.premoveLight else colors.premoveDark,
                topLeft = topLeft,
                size = cellSize
            )
        }
    }

    fun DrawScope.drawSelectedSquareHighlight(
        selectedSquare: BoardSquare?,
        squareSize: Float,
        colors: BoardSquareColors,
        flipped: Boolean,
        alpha: Float
    ) {
        if (selectedSquare == null || alpha <= 0.01f) return
        val topLeft = BoardCoordinates.squareTopLeft(selectedSquare, squareSize, flipped)
        val base = if (selectedSquare.isLightSquare) colors.selectedLight else colors.selectedDark
        drawRect(
            color = base.copy(alpha = base.alpha * alpha),
            topLeft = topLeft,
            size = Size(squareSize, squareSize)
        )
    }

    fun DrawScope.drawCheckHighlight(
        checkedKingSquare: BoardSquare?,
        squareSize: Float,
        colors: BoardSquareColors,
        flipped: Boolean
    ) {
        if (checkedKingSquare == null) return
        val topLeft = BoardCoordinates.squareTopLeft(checkedKingSquare, squareSize, flipped)
        val center = Offset(topLeft.x + squareSize / 2f, topLeft.y + squareSize / 2f)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(colors.check, colors.check.copy(alpha = 0.55f), colors.checkEdge),
                center = center,
                radius = squareSize * 0.62f
            ),
            topLeft = topLeft,
            size = Size(squareSize, squareSize)
        )
    }

    fun DrawScope.drawDragTargetHighlight(
        hoveredSquare: BoardSquare?,
        originSquare: BoardSquare?,
        squareSize: Float,
        colors: BoardSquareColors,
        flipped: Boolean
    ) {
        if (hoveredSquare == null || hoveredSquare == originSquare) return
        val topLeft = BoardCoordinates.squareTopLeft(hoveredSquare, squareSize, flipped)
        val strokeWidth = (squareSize * 0.075f).coerceAtLeast(2.5f)
        drawRect(
            color = colors.dragHoverOverlay,
            topLeft = topLeft,
            size = Size(squareSize, squareSize)
        )
        drawRoundRect(
            color = colors.dragHoverBorder,
            topLeft = Offset(topLeft.x + strokeWidth / 2f, topLeft.y + strokeWidth / 2f),
            size = Size(squareSize - strokeWidth, squareSize - strokeWidth),
            cornerRadius = CornerRadius(squareSize * 0.08f, squareSize * 0.08f),
            style = Stroke(width = strokeWidth)
        )
    }

    fun DrawScope.drawCoordinates(
        squareSize: Float,
        colors: BoardSquareColors,
        flipped: Boolean,
        textMeasurer: TextMeasurer,
        baseTextStyle: TextStyle
    ) {
        val padding = squareSize * 0.07f

        // Rank numbers (1–8) along left column (col == 0)
        for (row in 0 until 8) {
            val square = BoardCoordinates.fromDisplayCell(col = 0, row = row, flipped = flipped)
            val label = BoardCoordinates.rankLabelForDisplayRow(row = row, flipped = flipped)
            val ink = if (square.isLightSquare) colors.dark else colors.light
            val measured = textMeasurer.measure(
                text = label,
                style = baseTextStyle.copy(color = ink)
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = padding,
                    y = row * squareSize + padding * 0.7f
                )
            )
        }

        // File letters (a–h) along bottom row (row == 7)
        for (col in 0 until 8) {
            val square = BoardCoordinates.fromDisplayCell(col = col, row = 7, flipped = flipped)
            val label = BoardCoordinates.fileLabelForDisplayCol(col = col, flipped = flipped)
            val ink = if (square.isLightSquare) colors.dark else colors.light
            val measured = textMeasurer.measure(
                text = label,
                style = baseTextStyle.copy(color = ink)
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = (col + 1) * squareSize - measured.size.width - padding,
                    y = 8 * squareSize - measured.size.height - padding * 0.6f
                )
            )
        }
    }

    fun DrawScope.drawLegalTargets(
        legalTargets: List<LegalTarget>,
        squareSize: Float,
        colors: BoardSquareColors,
        flipped: Boolean,
        alpha: Float,
        isPremove: Boolean = false
    ) {
        if (legalTargets.isEmpty() || alpha <= 0.01f) return
        val baseIndicator = if (isPremove) colors.premoveTargetIndicator else colors.legalMoveIndicator
        val indicatorColor = baseIndicator.copy(
            alpha = baseIndicator.alpha * alpha
        )
        val dotRadius = squareSize * 0.165f
        val ringStroke = squareSize * 0.088f
        val ringRadius = squareSize * 0.44f - ringStroke / 2f

        for (target in legalTargets) {
            val center = BoardCoordinates.squareCenter(target.square, squareSize, flipped)
            if (target.isCapture) {
                drawCircle(
                    color = indicatorColor,
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = ringStroke)
                )
            } else {
                drawCircle(
                    color = indicatorColor,
                    radius = dotRadius,
                    center = center
                )
            }
        }
    }

    fun DrawScope.drawPieceAtCenter(
        bitmap: ImageBitmap,
        center: Offset,
        squareSize: Float,
        scale: Float = 1f,
        alpha: Float = 1f,
        drawShadow: Boolean = false,
        colorFilter: ColorFilter? = null
    ) {
        val drawDim = (squareSize * 0.90f * scale).roundToInt().coerceAtLeast(1)
        val left = (center.x - drawDim / 2f).roundToInt()
        val top = (center.y - drawDim / 2f).roundToInt()

        if (drawShadow) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x55000000), Color.Transparent),
                    center = Offset(center.x, center.y + drawDim * 0.24f),
                    radius = drawDim * 0.46f
                ),
                radius = drawDim * 0.46f,
                center = Offset(center.x, center.y + drawDim * 0.24f)
            )
        }

        drawImage(
            image = bitmap,
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(drawDim, drawDim),
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = FilterQuality.High
        )
    }
}
