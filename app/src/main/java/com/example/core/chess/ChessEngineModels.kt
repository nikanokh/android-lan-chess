package com.example.core.chess

import androidx.compose.runtime.Immutable
import com.example.core.ui.board.BoardMove
import com.example.core.ui.board.BoardPiece
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.PieceType
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor

enum class GameTerminationReason {
    CHECKMATE,
    STALEMATE,
    INSUFFICIENT_MATERIAL,
    FIFTY_MOVE_RULE,
    THREEFOLD_REPETITION,
    DRAW_BY_AGREEMENT,
    TIMEOUT,
    TIMEOUT_VS_INSUFFICIENT_MATERIAL,
    RESIGNATION,
    ABORTED
}

@Immutable
data class GameOutcome(
    val result: GameResult,
    val reason: GameTerminationReason
)

@Immutable
data class EngineLegalMove(
    val from: BoardSquare,
    val to: BoardSquare,
    val promotion: PieceType? = null,
    val uci: String,
    val isCapture: Boolean,
    val isEnPassant: Boolean = false,
    val isCastling: Boolean = false
)

@Immutable
data class EngineMove(
    val plyIndex: Int,
    val moveNumber: Int,
    val color: PieceColor,
    val from: BoardSquare,
    val to: BoardSquare,
    val promotion: PieceType? = null,
    val uci: String,
    val san: String,
    val fenAfter: String,
    val movedPiece: BoardPiece,
    val capturedPiece: BoardPiece? = null,
    val capturedSquare: BoardSquare? = null,
    val isEnPassant: Boolean = false,
    val isCastling: Boolean = false,
    val isCheck: Boolean = false,
    val isCheckmate: Boolean = false
) {
    fun toBoardMove(): BoardMove = BoardMove(from = from, to = to, promotion = promotion)
}

@Immutable
data class ChessPositionSnapshot(
    val fen: String,
    val pieces: Map<BoardSquare, BoardPiece>,
    val sideToMove: PieceColor,
    val fullMoveNumber: Int,
    val halfMoveClock: Int,
    val isCheck: Boolean,
    val checkedKingSquare: BoardSquare?,
    val isCheckmate: Boolean,
    val isStalemate: Boolean,
    val isInsufficientMaterial: Boolean,
    val isFiftyMoveRule: Boolean,
    val isThreefoldRepetition: Boolean,
    val isDraw: Boolean,
    val isGameOver: Boolean,
    val outcome: GameOutcome?,
    val lastMove: EngineMove?,
    val moveHistory: List<EngineMove>
)

@Immutable
data class PgnMetadata(
    val event: String = "ChessLAN Local Match",
    val site: String = "ChessLAN",
    val date: String = "????.??.??",
    val round: String = "1",
    val whitePlayer: String = "White",
    val blackPlayer: String = "Black",
    val timeControl: String = "-"
)

sealed interface EngineMoveResult {
    data class Success(
        val move: EngineMove,
        val snapshot: ChessPositionSnapshot
    ) : EngineMoveResult

    data class RequiresPromotion(
        val from: BoardSquare,
        val to: BoardSquare,
        val color: PieceColor
    ) : EngineMoveResult

    data class Illegal(
        val reason: String
    ) : EngineMoveResult
}

fun PieceColor.opposite(): PieceColor =
    if (this == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE
