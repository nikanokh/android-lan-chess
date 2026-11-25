package com.example.core.ui.board

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import com.example.core.ui.theme.ChessMotion
import com.example.domain.model.BoardTheme
import com.example.domain.model.PieceColor
import com.example.domain.model.PieceSet

/**
 * Logical chess board coordinate using 0-indexed file (0 = 'a' .. 7 = 'h')
 * and 0-indexed rank (0 = '1' .. 7 = '8').
 */
@Immutable
data class BoardSquare(
    val file: Int,
    val rank: Int
) {
    init {
        require(file in 0..7) { "File must be in 0..7, got $file" }
        require(rank in 0..7) { "Rank must be in 0..7, got $rank" }
    }

    val fileChar: Char
        get() = ('a'.code + file).toChar()

    val rankChar: Char
        get() = ('1'.code + rank).toChar()

    val algebraic: String
        get() = "$fileChar$rankChar"

    /**
     * Standard chess geometry: a1 (0,0) is dark, h1 (7,0) is light, a8 (0,7) is light.
     */
    val isLightSquare: Boolean
        get() = (file + rank) % 2 == 1

    companion object {
        fun fromAlgebraic(notation: String): BoardSquare? {
            if (notation.length != 2) return null
            val file = notation[0].lowercaseChar() - 'a'
            val rank = notation[1] - '1'
            if (file !in 0..7 || rank !in 0..7) return null
            return BoardSquare(file = file, rank = rank)
        }
    }
}

enum class PieceType(val fenSymbol: Char) {
    PAWN('p'),
    KNIGHT('n'),
    BISHOP('b'),
    ROOK('r'),
    QUEEN('q'),
    KING('k');

    companion object {
        val PromotionOptions: List<PieceType> = listOf(QUEEN, ROOK, BISHOP, KNIGHT)

        fun fromFenSymbol(symbol: Char): PieceType? = when (symbol.lowercaseChar()) {
            'p' -> PAWN
            'n' -> KNIGHT
            'b' -> BISHOP
            'r' -> ROOK
            'q' -> QUEEN
            'k' -> KING
            else -> null
        }
    }
}

@Immutable
data class BoardPiece(
    val type: PieceType,
    val color: PieceColor
) {
    val fenChar: Char
        get() = if (color == PieceColor.WHITE) {
            type.fenSymbol.uppercaseChar()
        } else {
            type.fenSymbol.lowercaseChar()
        }

    companion object {
        fun fromFenChar(char: Char): BoardPiece? {
            val type = PieceType.fromFenSymbol(char) ?: return null
            val color = if (char.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK
            return BoardPiece(type = type, color = color)
        }
    }
}

@Immutable
data class BoardMove(
    val from: BoardSquare,
    val to: BoardSquare,
    val promotion: PieceType? = null
) {
    val uci: String
        get() = buildString(5) {
            append(from.algebraic)
            append(to.algebraic)
            if (promotion != null) {
                append(promotion.fenSymbol)
            }
        }
}

@Immutable
data class LegalTarget(
    val square: BoardSquare,
    val isCapture: Boolean,
    val requiresPromotion: Boolean = false
)

@Immutable
data class BoardMoveAnimation(
    val id: Long,
    val piece: BoardPiece,
    val from: BoardSquare,
    val to: BoardSquare,
    val capturedPiece: BoardPiece? = null,
    val capturedSquare: BoardSquare = to,
    val skipSlideAnimation: Boolean = false
)

@Immutable
data class ChessBoardConfig(
    val boardTheme: BoardTheme = BoardTheme.CLASSIC_GREEN,
    val pieceSet: PieceSet = PieceSet.NEO,
    val showCoordinates: Boolean = true,
    val highlightLegalMoves: Boolean = true,
    val highlightLastMove: Boolean = true,
    val animationsEnabled: Boolean = true,
    val moveAnimationDurationMs: Int = ChessMotion.MOVE_ANIMATION_MS,
    val flipped: Boolean = false,
    val interactive: Boolean = true
)

@Immutable
data class BoardPremove(
    val from: BoardSquare,
    val to: BoardSquare,
    val piece: BoardPiece,
    val promotion: PieceType? = null
)

@Immutable
data class ChessBoardState(
    val pieces: Map<BoardSquare, BoardPiece> = emptyMap(),
    val selectedSquare: BoardSquare? = null,
    val legalTargets: List<LegalTarget> = emptyList(),
    val lastMove: BoardMove? = null,
    val checkedKingSquare: BoardSquare? = null,
    val lastMoveAnimation: BoardMoveAnimation? = null,
    val queuedPremoves: List<BoardPremove> = emptyList(),
    val isPremoveSelection: Boolean = false,
    val positionVersion: Long = 0L
) {
    val queuedPremove: BoardPremove?
        get() = queuedPremoves.firstOrNull()
}

/**
 * Pure coordinate projection math between logical BoardSquare and Canvas pixel/grid coordinates.
 */
object BoardCoordinates {

    fun toDisplayCol(square: BoardSquare, flipped: Boolean): Int =
        if (flipped) 7 - square.file else square.file

    fun toDisplayRow(square: BoardSquare, flipped: Boolean): Int =
        if (flipped) square.rank else 7 - square.rank

    fun fromDisplayCell(col: Int, row: Int, flipped: Boolean): BoardSquare {
        require(col in 0..7 && row in 0..7) { "Display cell out of range: ($col, $row)" }
        val file = if (flipped) 7 - col else col
        val rank = if (flipped) row else 7 - row
        return BoardSquare(file = file, rank = rank)
    }

    fun squareTopLeft(square: BoardSquare, squareSizePx: Float, flipped: Boolean): Offset {
        val col = toDisplayCol(square, flipped)
        val row = toDisplayRow(square, flipped)
        return Offset(col * squareSizePx, row * squareSizePx)
    }

    fun squareCenter(square: BoardSquare, squareSizePx: Float, flipped: Boolean): Offset {
        val topLeft = squareTopLeft(square, squareSizePx, flipped)
        return Offset(topLeft.x + squareSizePx / 2f, topLeft.y + squareSizePx / 2f)
    }

    /**
     * Converts a Canvas pointer position to a logical BoardSquare, or null if outside the board.
     */
    fun squareAtOffset(offset: Offset, boardSizePx: Float, flipped: Boolean): BoardSquare? {
        if (boardSizePx <= 0f) return null
        if (offset.x < 0f || offset.y < 0f || offset.x >= boardSizePx || offset.y >= boardSizePx) {
            return null
        }
        val squareSize = boardSizePx / 8f
        val col = (offset.x / squareSize).toInt().coerceIn(0, 7)
        val row = (offset.y / squareSize).toInt().coerceIn(0, 7)
        return fromDisplayCell(col = col, row = row, flipped = flipped)
    }

    fun fileLabelForDisplayCol(col: Int, flipped: Boolean): String {
        val file = if (flipped) 7 - col else col
        return ('a'.code + file).toChar().toString()
    }

    fun rankLabelForDisplayRow(row: Int, flipped: Boolean): String {
        val rank = if (flipped) row else 7 - row
        return ('1'.code + rank).toChar().toString()
    }
}

/**
 * Parses and serializes the piece-placement field of FEN strings for ChessBoard rendering.
 */
object FenBoardParser {
    const val INITIAL_FEN: String =
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    fun parsePlacement(fen: String): Map<BoardSquare, BoardPiece> {
        val placement = fen.trim().substringBefore(' ')
        val ranks = placement.split('/')
        require(ranks.size == 8) { "Invalid FEN placement: expected 8 ranks, got ${ranks.size}" }

        val pieces = LinkedHashMap<BoardSquare, BoardPiece>(32)
        for (fenRowIndex in 0 until 8) {
            val rank = 7 - fenRowIndex
            var file = 0
            for (ch in ranks[fenRowIndex]) {
                if (ch.isDigit()) {
                    file += ch.digitToInt()
                } else {
                    val piece = BoardPiece.fromFenChar(ch)
                        ?: throw IllegalArgumentException("Unexpected FEN symbol '$ch'")
                    require(file in 0..7) { "FEN rank overflow at rank ${rank + 1}" }
                    pieces[BoardSquare(file = file, rank = rank)] = piece
                    file++
                }
            }
            require(file == 8) { "FEN rank ${rank + 1} has $file files instead of 8" }
        }
        return pieces
    }

    fun toPlacement(pieces: Map<BoardSquare, BoardPiece>): String = buildString {
        for (rank in 7 downTo 0) {
            var emptyRun = 0
            for (file in 0 until 8) {
                val piece = pieces[BoardSquare(file = file, rank = rank)]
                if (piece == null) {
                    emptyRun++
                } else {
                    if (emptyRun > 0) {
                        append(emptyRun)
                        emptyRun = 0
                    }
                    append(piece.fenChar)
                }
            }
            if (emptyRun > 0) append(emptyRun)
            if (rank > 0) append('/')
        }
    }
}
