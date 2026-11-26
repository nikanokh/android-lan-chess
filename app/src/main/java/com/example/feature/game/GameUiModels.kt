package com.example.feature.game

import androidx.compose.runtime.Immutable
import com.example.core.chess.ClockSnapshot
import com.example.core.chess.EngineMove
import com.example.core.chess.GameOutcome
import com.example.core.ui.board.BoardPiece
import com.example.core.ui.board.BoardPremove
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.ChessBoardState
import com.example.core.ui.board.PieceType
import com.example.domain.model.PieceColor
import com.example.domain.model.TimeControl

enum class DrawOfferState {
    NONE,
    SENT,
    RECEIVED,
    ACCEPTED,
    DECLINED
}

enum class ConnectionStatusUi {
    LOCAL_PASS_AND_PLAY,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

@Immutable
data class PendingPromotion(
    val from: BoardSquare,
    val to: BoardSquare,
    val color: PieceColor,
    val fromDrag: Boolean,
    val isPremove: Boolean = false
)

@Immutable
data class MoveHistoryPair(
    val moveNumber: Int,
    val whiteMove: EngineMove?,
    val blackMove: EngineMove?
)

@Immutable
data class MaterialBalance(
    val capturedByWhite: List<PieceType> = emptyList(),
    val capturedByBlack: List<PieceType> = emptyList(),
    val whiteAdvantage: Int = 0,
    val blackAdvantage: Int = 0
)

@Immutable
data class GameUiState(
    val timeControl: TimeControl = TimeControl.RAPID_10_0,
    val playerSide: PieceColor = PieceColor.WHITE,
    val restrictToLocalPlayer: Boolean = false,
    val isLanMatch: Boolean = false,
    val localPlayerName: String? = null,
    val opponentName: String? = null,
    val boardState: ChessBoardState = ChessBoardState(),
    val sideToMove: PieceColor = PieceColor.WHITE,
    val isCheck: Boolean = false,
    val outcome: GameOutcome? = null,
    val moveHistory: List<EngineMove> = emptyList(),
    val movePairs: List<MoveHistoryPair> = emptyList(),
    val viewedPlyIndex: Int? = null,
    val queuedPremoves: List<BoardPremove> = emptyList(),
    val premovesEnabled: Boolean = false,
    val clock: ClockSnapshot = ClockSnapshot(
        timeControl = TimeControl.RAPID_10_0,
        whiteRemainingMs = TimeControl.RAPID_10_0.initialSeconds * 1_000L,
        blackRemainingMs = TimeControl.RAPID_10_0.initialSeconds * 1_000L
    ),
    val drawOfferState: DrawOfferState = DrawOfferState.NONE,
    val drawOfferedBy: PieceColor? = null,
    val takebackOfferState: DrawOfferState = DrawOfferState.NONE,
    val takebackRequestedBy: PieceColor? = null,
    val rematchOfferState: DrawOfferState = DrawOfferState.NONE,
    val rematchRequestedBy: PieceColor? = null,
    val connectionStatus: ConnectionStatusUi = ConnectionStatusUi.LOCAL_PASS_AND_PLAY,
    val materialBalance: MaterialBalance = MaterialBalance(),
    val pendingPromotion: PendingPromotion? = null,
    val showResignConfirmDialog: Boolean = false,
    val showExitConfirmDialog: Boolean = false,
    val showGameOverDialog: Boolean = false,
    val pgnPreview: String? = null
) {
    val isGameOver: Boolean get() = outcome != null
    val isViewingHistory: Boolean get() = viewedPlyIndex != null && viewedPlyIndex < moveHistory.size
    val activeDisplayPly: Int get() = viewedPlyIndex ?: moveHistory.size
    val queuedPremove: BoardPremove? get() = queuedPremoves.firstOrNull()

    fun canInteractWithPiece(piece: BoardPiece): Boolean {
        if (isGameOver || isViewingHistory) return false
        if (restrictToLocalPlayer && piece.color != playerSide) return false
        if (piece.color != sideToMove && !premovesEnabled) return false
        return true
    }
}

object GameHistoryFormatter {

    fun toMovePairs(moves: List<EngineMove>): List<MoveHistoryPair> {
        if (moves.isEmpty()) return emptyList()
        val pairs = mutableListOf<MoveHistoryPair>()
        var index = 0
        while (index < moves.size) {
            val current = moves[index]
            if (current.color == PieceColor.WHITE) {
                val black = moves.getOrNull(index + 1)?.takeIf { it.color == PieceColor.BLACK }
                pairs.add(
                    MoveHistoryPair(
                        moveNumber = current.moveNumber,
                        whiteMove = current,
                        blackMove = black
                    )
                )
                index += if (black != null) 2 else 1
            } else {
                pairs.add(
                    MoveHistoryPair(
                        moveNumber = current.moveNumber,
                        whiteMove = null,
                        blackMove = current
                    )
                )
                index += 1
            }
        }
        return pairs
    }

    /**
     * Computes captured pieces and net centipawn-style material advantage from the current board.
     */
    fun computeMaterialBalance(pieces: Map<BoardSquare, BoardPiece>): MaterialBalance {
        val startingCounts = mapOf(
            PieceType.QUEEN to 1,
            PieceType.ROOK to 2,
            PieceType.BISHOP to 2,
            PieceType.KNIGHT to 2,
            PieceType.PAWN to 8
        )
        val pieceValues = mapOf(
            PieceType.QUEEN to 9,
            PieceType.ROOK to 5,
            PieceType.BISHOP to 3,
            PieceType.KNIGHT to 3,
            PieceType.PAWN to 1
        )
        val displayOrder = listOf(
            PieceType.QUEEN,
            PieceType.ROOK,
            PieceType.BISHOP,
            PieceType.KNIGHT,
            PieceType.PAWN
        )

        val whiteOnBoard = mutableMapOf<PieceType, Int>()
        val blackOnBoard = mutableMapOf<PieceType, Int>()
        var whiteScore = 0
        var blackScore = 0

        for (piece in pieces.values) {
            val value = pieceValues[piece.type] ?: 0
            if (piece.color == PieceColor.WHITE) {
                whiteOnBoard[piece.type] = (whiteOnBoard[piece.type] ?: 0) + 1
                whiteScore += value
            } else {
                blackOnBoard[piece.type] = (blackOnBoard[piece.type] ?: 0) + 1
                blackScore += value
            }
        }

        val capturedByWhite = mutableListOf<PieceType>()
        val capturedByBlack = mutableListOf<PieceType>()
        for (type in displayOrder) {
            val start = startingCounts[type] ?: 0
            val missingBlack = (start - (blackOnBoard[type] ?: 0)).coerceAtLeast(0)
            repeat(missingBlack) { capturedByWhite.add(type) }

            val missingWhite = (start - (whiteOnBoard[type] ?: 0)).coerceAtLeast(0)
            repeat(missingWhite) { capturedByBlack.add(type) }
        }

        val diff = whiteScore - blackScore
        return MaterialBalance(
            capturedByWhite = capturedByWhite,
            capturedByBlack = capturedByBlack,
            whiteAdvantage = diff.coerceAtLeast(0),
            blackAdvantage = (-diff).coerceAtLeast(0)
        )
    }
}
