package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.GameTerminationReason
import com.example.core.chess.MonotonicTimeProvider
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.theme.ChessLanTheme
import com.example.domain.model.AppSettings
import com.example.domain.model.BoardTheme
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import com.example.domain.model.PieceSet
import com.example.domain.model.TimeControl
import com.example.domain.repository.SettingsRepository
import com.example.domain.usecase.ObserveSettingsUseCase
import com.example.feature.game.DrawOfferState
import com.example.feature.game.GameHistoryFormatter
import com.example.feature.game.GameScreenContent
import com.example.feature.game.GameViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observeSettings(): Flow<AppSettings> = state
    override suspend fun updatePlayerName(name: String) { state.value = state.value.copy(playerName = name) }
    override suspend fun updateBoardTheme(theme: BoardTheme) { state.value = state.value.copy(boardTheme = theme) }
    override suspend fun updatePieceSet(pieceSet: PieceSet) { state.value = state.value.copy(pieceSet = pieceSet) }
    override suspend fun updateSoundEnabled(enabled: Boolean) { state.value = state.value.copy(soundEnabled = enabled) }
    override suspend fun updateAnimationEnabled(enabled: Boolean) { state.value = state.value.copy(animationEnabled = enabled) }
    override suspend fun updateShowCoordinates(enabled: Boolean) { state.value = state.value.copy(showCoordinates = enabled) }
    override suspend fun updateHighlightLegalMoves(enabled: Boolean) { state.value = state.value.copy(highlightLegalMoves = enabled) }
    override suspend fun updateHighlightLastMove(enabled: Boolean) { state.value = state.value.copy(highlightLastMove = enabled) }
    override suspend fun updateConfirmResign(enabled: Boolean) { state.value = state.value.copy(confirmResign = enabled) }
    override suspend fun updateVibrationEnabled(enabled: Boolean) { state.value = state.value.copy(vibrationEnabled = enabled) }
    override suspend fun updatePremovesEnabled(enabled: Boolean) { state.value = state.value.copy(premovesEnabled = enabled) }
}

private class TestElapsedClock(var nowMs: Long = 10_000L) : MonotonicTimeProvider {
    override fun elapsedRealtimeMs(): Long = nowMs
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class GameScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createViewModel(
        clock: TestElapsedClock = TestElapsedClock(),
        settings: AppSettings = AppSettings(confirmResign = true, animationEnabled = false, premovesEnabled = true)
    ): GameViewModel {
        val repo = FakeSettingsRepository(settings)
        return GameViewModel(
            chessEngine = ChesslibChessEngine(),
            timeProvider = clock,
            observeSettingsUseCase = ObserveSettingsUseCase(repo)
        )
    }

    @Test
    fun gameScreen_portraitFullWidthBoardAndHistoryReviewWithoutMutatingGame() {
        val clock = TestElapsedClock()
        val viewModel = createViewModel(clock)
        viewModel.initializeSession(TimeControl.BLITZ_5_3.id)

        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
                val settings = viewModel.settings.collectAsStateWithLifecycle().value
                GameScreenContent(
                    uiState = uiState,
                    settings = settings,
                    onNavigateBack = {},
                    onSquareTap = viewModel::onSquareTap,
                    onMoveAttempt = viewModel::onMoveAttempt,
                    onPromotionSelected = viewModel::onPromotionSelected,
                    onPromotionDismissed = viewModel::onPromotionDismissed,
                    onSelectPly = viewModel::viewPly,
                    onJumpStart = viewModel::jumpToHistoryStart,
                    onStepBack = viewModel::stepHistoryBackward,
                    onStepForward = viewModel::stepHistoryForward,
                    onReturnLive = viewModel::returnToLivePosition,
                    onOfferDraw = viewModel::offerDraw,
                    onAcceptDraw = viewModel::acceptDraw,
                    onDeclineDraw = viewModel::declineDraw,
                    onRequestResign = viewModel::requestResign,
                    onConfirmResign = viewModel::confirmResignNow,
                    onDismissResign = viewModel::dismissResignConfirm,
                    onUndo = viewModel::undoMove,
                    onNewGame = viewModel::resetGame,
                    onDismissGameOver = viewModel::dismissGameOverDialog,
                    onOpenPgn = viewModel::openPgnPreview,
                    onDismissPgn = viewModel::dismissPgnPreview,
                    onCancelPremove = viewModel::cancelPremove
                )
            }
        }

        composeRule.waitForIdle()
        // Board spans 100% full width (412dp) on portrait phone
        val boardNode = composeRule.onNodeWithTag("chess_board")
        boardNode
            .assertIsDisplayed()
            .assertWidthIsEqualTo(412.dp)
        val initialBoardBounds = boardNode.fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithTag("top_player_clock").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_player_clock").assertIsDisplayed()
        composeRule.onNodeWithTag("compact_move_history_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("compact_history_prev").assertIsDisplayed()
        composeRule.onNodeWithTag("compact_history_next").assertIsDisplayed()

        // Make two moves: 1. e4 e5
        assertTrue(viewModel.onMoveAttempt(BoardSquare.fromAlgebraic("e2")!!, BoardSquare.fromAlgebraic("e4")!!, false))
        clock.nowMs += 2_000L
        assertTrue(viewModel.onMoveAttempt(BoardSquare.fromAlgebraic("e7")!!, BoardSquare.fromAlgebraic("e5")!!, false))
        composeRule.waitForIdle()

        assertEquals(2, viewModel.uiState.value.moveHistory.size)

        // Step backward directly with < button on main screen (NO menu/sheet needed!)
        composeRule.onNodeWithTag("compact_history_prev").performClick()
        composeRule.waitForIdle()

        // Reviewing ply 1 shows inline review badge, hides e5 pawn, preserves 2-ply history, AND NEVER shifts the board!
        composeRule.onNodeWithTag("history_review_banner").assertIsDisplayed()
        assertTrue(viewModel.uiState.value.isViewingHistory)
        assertEquals(1, viewModel.uiState.value.activeDisplayPly)
        assertNull(viewModel.uiState.value.boardState.pieces[BoardSquare.fromAlgebraic("e5")])
        assertEquals(2, viewModel.uiState.value.moveHistory.size)
        val historyBoardBounds = boardNode.fetchSemanticsNode().boundsInRoot
        assertEquals(initialBoardBounds, historyBoardBounds)

        // Step backward again to ply 0 (starting position), then forward with > button
        composeRule.onNodeWithTag("compact_history_prev").performClick()
        composeRule.waitForIdle()
        assertEquals(0, viewModel.uiState.value.activeDisplayPly)
        assertEquals(initialBoardBounds, boardNode.fetchSemanticsNode().boundsInRoot)

        composeRule.onNodeWithTag("compact_history_next").performClick()
        composeRule.waitForIdle()
        assertEquals(1, viewModel.uiState.value.activeDisplayPly)

        // Return to live position via >| button restores e5 pawn with ZERO board jump
        composeRule.onNodeWithTag("return_to_live_button").performClick()
        composeRule.waitForIdle()
        assertFalse(viewModel.uiState.value.isViewingHistory)
        assertNotNull(viewModel.uiState.value.boardState.pieces[BoardSquare.fromAlgebraic("e5")])
        assertEquals(initialBoardBounds, boardNode.fetchSemanticsNode().boundsInRoot)

        // Queue premove for waiting side (Black) -> chip displays inline with ZERO board jump
        assertTrue(viewModel.onMoveAttempt(BoardSquare.fromAlgebraic("b8")!!, BoardSquare.fromAlgebraic("c6")!!, false))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cancel_premove_chip").assertIsDisplayed()
        assertEquals(initialBoardBounds, boardNode.fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithTag("cancel_premove_chip").performClick()
        composeRule.waitForIdle()
        assertNull(viewModel.uiState.value.queuedPremove)
    }

    @Test
    fun drawOfferStateMachine_andResignConfirmationFlowWorkEndToEnd() {
        val clock = TestElapsedClock()
        val viewModel = createViewModel(clock)
        viewModel.initializeSession(TimeControl.RAPID_10_0.id)

        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
                val settings = viewModel.settings.collectAsStateWithLifecycle().value
                GameScreenContent(
                    uiState = uiState,
                    settings = settings,
                    onNavigateBack = {},
                    onSquareTap = viewModel::onSquareTap,
                    onMoveAttempt = viewModel::onMoveAttempt,
                    onPromotionSelected = viewModel::onPromotionSelected,
                    onPromotionDismissed = viewModel::onPromotionDismissed,
                    onSelectPly = viewModel::viewPly,
                    onJumpStart = viewModel::jumpToHistoryStart,
                    onStepBack = viewModel::stepHistoryBackward,
                    onStepForward = viewModel::stepHistoryForward,
                    onReturnLive = viewModel::returnToLivePosition,
                    onOfferDraw = viewModel::offerDraw,
                    onAcceptDraw = viewModel::acceptDraw,
                    onDeclineDraw = viewModel::declineDraw,
                    onRequestResign = viewModel::requestResign,
                    onConfirmResign = viewModel::confirmResignNow,
                    onDismissResign = viewModel::dismissResignConfirm,
                    onUndo = viewModel::undoMove,
                    onNewGame = viewModel::resetGame,
                    onDismissGameOver = viewModel::dismissGameOverDialog,
                    onOpenPgn = viewModel::openPgnPreview,
                    onDismissPgn = viewModel::dismissPgnPreview
                )
            }
        }

        composeRule.waitForIdle()

        // 1. Offer draw -> SENT -> Decline -> DECLINED
        composeRule.onNodeWithTag("game_draw_button").performClick()
        composeRule.waitForIdle()
        assertEquals(DrawOfferState.SENT, viewModel.uiState.value.drawOfferState)
        composeRule.onNodeWithTag("draw_offer_banner").assertIsDisplayed()

        clock.nowMs += 500L
        composeRule.onNodeWithTag("decline_draw_button").performClick()
        composeRule.waitForIdle()
        assertEquals(DrawOfferState.DECLINED, viewModel.uiState.value.drawOfferState)
        assertFalse(viewModel.uiState.value.isGameOver)

        // 2. Receive draw offer -> Accept -> Game Over (DRAW_BY_AGREEMENT)
        clock.nowMs += 500L
        viewModel.onRemoteDrawOfferReceived(PieceColor.BLACK)
        composeRule.waitForIdle()
        assertEquals(DrawOfferState.RECEIVED, viewModel.uiState.value.drawOfferState)
        composeRule.onNodeWithTag("accept_draw_button").performClick()
        composeRule.waitForIdle()

        assertEquals(DrawOfferState.ACCEPTED, viewModel.uiState.value.drawOfferState)
        assertEquals(GameResult.DRAW, viewModel.uiState.value.outcome?.result)
        assertEquals(GameTerminationReason.DRAW_BY_AGREEMENT, viewModel.uiState.value.outcome?.reason)
        composeRule.onNodeWithTag("game_over_dialog").assertIsDisplayed()

        // 3. Start new game & test resign confirmation dialog
        composeRule.onNodeWithTag("game_over_new_game_button").performClick()
        composeRule.waitForIdle()
        assertFalse(viewModel.uiState.value.isGameOver)

        clock.nowMs += 500L
        composeRule.onNodeWithTag("game_resign_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("resign_confirm_dialog").assertIsDisplayed()

        composeRule.onNodeWithTag("confirm_resign_button").performClick()
        composeRule.waitForIdle()
        assertEquals(GameResult.BLACK_WIN, viewModel.uiState.value.outcome?.result)
        assertEquals(GameTerminationReason.RESIGNATION, viewModel.uiState.value.outcome?.reason)
        composeRule.onNodeWithTag("game_over_dialog").assertIsDisplayed()
    }

    @Test
    fun timeoutDuringGame_finishesMatchWithMaterialCheckAndComputesMaterialBalance() {
        val clock = TestElapsedClock(nowMs = 1_000L)
        val viewModel = createViewModel(clock)
        viewModel.initializeSession(TimeControl.BULLET_1_0.id) // 60s + 0s

        // White plays 1. e4 -> Black's clock starts at 60s
        assertTrue(viewModel.onMoveAttempt(BoardSquare.fromAlgebraic("e2")!!, BoardSquare.fromAlgebraic("e4")!!, false))

        // Black runs out of time
        clock.nowMs += 61_000L
        viewModel.refreshClockNow()

        assertTrue(viewModel.uiState.value.isGameOver)
        assertEquals(GameResult.WHITE_WIN, viewModel.uiState.value.outcome?.result)
        assertEquals(GameTerminationReason.TIMEOUT, viewModel.uiState.value.outcome?.reason)

        // Verify material balance computation helper
        val engine = ChesslibChessEngine()
        engine.applyMove("e2e4")
        engine.applyMove("d7d5")
        engine.applyMove("e4d5") // White captures Black's pawn
        val balance = GameHistoryFormatter.computeMaterialBalance(engine.snapshot().pieces)
        assertEquals(1, balance.whiteAdvantage)
        assertEquals(0, balance.blackAdvantage)
        assertEquals(1, balance.capturedByWhite.size)
    }
}
