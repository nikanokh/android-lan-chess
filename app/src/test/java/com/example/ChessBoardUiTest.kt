package com.example

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.EngineMoveResult
import com.example.core.ui.board.BoardCoordinates
import com.example.core.ui.board.BoardMoveAnimation
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.ChessBoard
import com.example.core.ui.board.ChessBoardConfig
import com.example.core.ui.board.ChessBoardState
import com.example.core.ui.board.PawnPromotionDialog
import com.example.core.ui.board.PieceType
import com.example.core.ui.theme.ChessLanTheme
import com.example.domain.model.BoardTheme
import com.example.domain.model.PieceColor
import com.example.domain.model.PieceSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChessBoardUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class EngineBoardController(val engine: ChesslibChessEngine = ChesslibChessEngine()) {
        private var animId = 0L
        private var version = 0L
        var state by mutableStateOf(snapshotToBoardState())

        fun onTap(square: BoardSquare) {
            if (state.selectedSquare == square) {
                state = state.copy(selectedSquare = null, legalTargets = emptyList())
                return
            }
            val piece = state.pieces[square]
            if (piece != null && piece.color == engine.sideToMove()) {
                state = state.copy(
                    selectedSquare = square,
                    legalTargets = engine.legalTargetsFrom(square)
                )
            } else {
                state = state.copy(selectedSquare = null, legalTargets = emptyList())
            }
        }

        fun onMoveAttempt(from: BoardSquare, to: BoardSquare, fromDrag: Boolean): Boolean {
            return when (val result = engine.attemptMove(from, to)) {
                is EngineMoveResult.Success -> {
                    state = snapshotToBoardState(
                        animation = BoardMoveAnimation(
                            id = ++animId,
                            piece = result.move.movedPiece,
                            from = result.move.from,
                            to = result.move.to,
                            capturedPiece = result.move.capturedPiece,
                            capturedSquare = result.move.capturedSquare ?: result.move.to,
                            skipSlideAnimation = fromDrag
                        )
                    )
                    true
                }
                else -> false
            }
        }

        private fun snapshotToBoardState(animation: BoardMoveAnimation? = null): ChessBoardState {
            val snap = engine.snapshot()
            return ChessBoardState(
                pieces = snap.pieces,
                selectedSquare = null,
                legalTargets = emptyList(),
                lastMove = snap.lastMove?.toBoardMove(),
                checkedKingSquare = snap.checkedKingSquare,
                lastMoveAnimation = animation,
                positionVersion = ++version
            )
        }
    }

    @Test
    fun chessBoard_rendersSquareAndHandlesTapToMove() {
        val controller = EngineBoardController()
        var moveFromDrag: Boolean? = null

        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                ChessBoard(
                    state = controller.state,
                    config = ChessBoardConfig(animationsEnabled = false),
                    onSquareTap = controller::onTap,
                    onMoveAttempt = { from, to, fromDrag ->
                        moveFromDrag = fromDrag
                        controller.onMoveAttempt(from, to, fromDrag)
                    },
                    modifier = Modifier.size(320.dp)
                )
            }
        }

        composeRule.waitForIdle()
        val boardNode = composeRule.onNodeWithTag("chess_board")
        boardNode.assertIsDisplayed()
        boardNode.assertWidthIsEqualTo(320.dp)
        boardNode.assertHeightIsEqualTo(320.dp)

        val sqPx = boardNode.fetchSemanticsNode().boundsInRoot.width / 8f
        val e2 = BoardSquare.fromAlgebraic("e2")!!
        val e4 = BoardSquare.fromAlgebraic("e4")!!

        boardNode.performTouchInput { click(BoardCoordinates.squareCenter(e2, sqPx, flipped = false)) }
        composeRule.waitForIdle()
        assertEquals(e2, controller.state.selectedSquare)

        boardNode.performTouchInput { click(BoardCoordinates.squareCenter(e4, sqPx, flipped = false)) }
        composeRule.waitForIdle()

        assertEquals(false, moveFromDrag)
        assertNull(controller.state.pieces[e2])
        assertNotNull(controller.state.pieces[e4])
        assertEquals(e4, controller.state.lastMove?.to)
    }

    @Test
    fun chessBoard_dragAndDropLegalMoveAndInvalidDropSnapBack() {
        val controller = EngineBoardController()
        var lastInvalidOrigin: BoardSquare? = null
        var lastInvalidTarget: BoardSquare? = BoardSquare(0, 0)
        var lastMoveWasDrag = false

        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                ChessBoard(
                    state = controller.state,
                    config = ChessBoardConfig(animationsEnabled = false),
                    onSquareTap = controller::onTap,
                    onMoveAttempt = { from, to, fromDrag ->
                        val accepted = controller.onMoveAttempt(from, to, fromDrag)
                        if (accepted) lastMoveWasDrag = fromDrag
                        accepted
                    },
                    onInvalidDrop = { from, dropped ->
                        lastInvalidOrigin = from
                        lastInvalidTarget = dropped
                    },
                    modifier = Modifier.size(320.dp)
                )
            }
        }

        composeRule.waitForIdle()
        val boardNode = composeRule.onNodeWithTag("chess_board")
        val sqPx = boardNode.fetchSemanticsNode().boundsInRoot.width / 8f

        val e2 = BoardSquare.fromAlgebraic("e2")!!
        val e6 = BoardSquare.fromAlgebraic("e6")!!
        boardNode.performTouchInput {
            down(BoardCoordinates.squareCenter(e2, sqPx, false))
            moveTo(BoardCoordinates.squareCenter(e6, sqPx, false))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(e2, lastInvalidOrigin)
        assertEquals(e6, lastInvalidTarget)
        assertNotNull(controller.state.pieces[e2])
        assertNull(controller.state.pieces[e6])

        boardNode.performTouchInput {
            down(BoardCoordinates.squareCenter(e2, sqPx, false))
            moveTo(Offset(-40f, -40f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(e2, lastInvalidOrigin)
        assertNull(lastInvalidTarget)
        assertNotNull(controller.state.pieces[e2])

        val g1 = BoardSquare.fromAlgebraic("g1")!!
        val f3 = BoardSquare.fromAlgebraic("f3")!!
        boardNode.performTouchInput {
            down(BoardCoordinates.squareCenter(g1, sqPx, false))
            moveTo(BoardCoordinates.squareCenter(f3, sqPx, false))
            up()
        }
        composeRule.waitForIdle()
        assertTrue(lastMoveWasDrag)
        assertNull(controller.state.pieces[g1])
        assertNotNull(controller.state.pieces[f3])
    }

    @Test
    fun chessBoard_flippedOrientationMapsTouchCorrectlyAndSupportsAllThemes() {
        val controller = EngineBoardController()
        var activeTheme by mutableStateOf(BoardTheme.WALNUT_WOOD)
        var activePieceSet by mutableStateOf(PieceSet.TOURNAMENT)

        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                ChessBoard(
                    state = controller.state,
                    config = ChessBoardConfig(
                        boardTheme = activeTheme,
                        pieceSet = activePieceSet,
                        flipped = true,
                        animationsEnabled = false
                    ),
                    onSquareTap = controller::onTap,
                    onMoveAttempt = controller::onMoveAttempt,
                    modifier = Modifier.size(320.dp)
                )
            }
        }

        composeRule.waitForIdle()
        val boardNode = composeRule.onNodeWithTag("chess_board")
        val sqPx = boardNode.fetchSemanticsNode().boundsInRoot.width / 8f

        val d2 = BoardSquare.fromAlgebraic("d2")!!
        val d4 = BoardSquare.fromAlgebraic("d4")!!
        boardNode.performTouchInput { click(BoardCoordinates.squareCenter(d2, sqPx, flipped = true)) }
        composeRule.waitForIdle()
        assertEquals(d2, controller.state.selectedSquare)

        boardNode.performTouchInput { click(BoardCoordinates.squareCenter(d4, sqPx, flipped = true)) }
        composeRule.waitForIdle()
        assertNull(controller.state.pieces[d2])
        assertNotNull(controller.state.pieces[d4])

        activeTheme = BoardTheme.OCEAN_BLUE
        activePieceSet = PieceSet.ALPHA
        composeRule.waitForIdle()
        boardNode.assertIsDisplayed()
    }

    @Test
    fun pawnPromotionDialog_selectsRequestedPiece() {
        var chosen: PieceType? = null

        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                PawnPromotionDialog(
                    color = PieceColor.WHITE,
                    onPieceSelected = { chosen = it },
                    onDismiss = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pawn_promotion_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("promotion_option_queen").assertIsDisplayed()
        composeRule.onNodeWithTag("promotion_option_rook").assertIsDisplayed()
        composeRule.onNodeWithTag("promotion_option_bishop").assertIsDisplayed()
        composeRule.onNodeWithTag("promotion_option_knight").assertIsDisplayed().performClick()

        composeRule.waitForIdle()
        assertEquals(PieceType.KNIGHT, chosen)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h915dp")
    fun chessBoard_animatedTapAndDragCompleteCleanlyAtFullWidth() {
        val controller = EngineBoardController()

        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                ChessBoard(
                    state = controller.state,
                    config = ChessBoardConfig(animationsEnabled = true),
                    onSquareTap = controller::onTap,
                    onMoveAttempt = controller::onMoveAttempt,
                    cornerRadius = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        composeRule.waitForIdle()
        val boardNode = composeRule.onNodeWithTag("chess_board")
        boardNode.assertWidthIsEqualTo(412.dp)
        boardNode.assertHeightIsEqualTo(412.dp)

        val sqPx = boardNode.fetchSemanticsNode().boundsInRoot.width / 8f

        val e2 = BoardSquare.fromAlgebraic("e2")!!
        val e4 = BoardSquare.fromAlgebraic("e4")!!
        boardNode.performTouchInput { click(BoardCoordinates.squareCenter(e2, sqPx, false)) }
        composeRule.waitForIdle()
        boardNode.performTouchInput { click(BoardCoordinates.squareCenter(e4, sqPx, false)) }
        composeRule.waitForIdle()
        assertNotNull(controller.state.pieces[e4])
        assertEquals(false, controller.state.lastMoveAnimation?.skipSlideAnimation)

        val e7 = BoardSquare.fromAlgebraic("e7")!!
        val e5 = BoardSquare.fromAlgebraic("e5")!!
        boardNode.performTouchInput {
            down(BoardCoordinates.squareCenter(e7, sqPx, false))
            moveTo(BoardCoordinates.squareCenter(e5, sqPx, false))
            up()
        }
        composeRule.waitForIdle()
        assertNull(controller.state.pieces[e7])
        assertNotNull(controller.state.pieces[e5])
        assertEquals(true, controller.state.lastMoveAnimation?.skipSlideAnimation)
    }
}
