package com.example

import android.os.Looper
import androidx.compose.ui.geometry.Offset
import com.example.core.audio.ChessSoundEffect
import com.example.core.audio.ChessSoundPlayer
import com.example.core.audio.ChessSoundSelector
import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.MonotonicTimeProvider
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.ChessBoardRenderer
import com.example.core.ui.board.PieceType
import com.example.domain.model.AppSettings
import com.example.domain.model.BoardTheme
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import com.example.domain.model.PieceSet
import com.example.domain.repository.SettingsRepository
import com.example.domain.usecase.ObserveSettingsUseCase
import com.example.feature.game.GameViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SoundAndPieceAlignmentTest {

    private class RecordingSoundPlayer : ChessSoundPlayer {
        val played = mutableListOf<ChessSoundEffect>()
        override fun play(effect: ChessSoundEffect) {
            played.add(effect)
        }
    }

    private class MutableSettingsRepo(initial: AppSettings) : SettingsRepository {
        val state = MutableStateFlow(initial)
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
    }

    private class ManualClock(var nowMs: Long = 10_000L) : MonotonicTimeProvider {
        override fun elapsedRealtimeMs(): Long = nowMs
    }

    @Test
    fun pieceAlignment_queenIsVerticallyCenteredAndAllOtherPiecesShareQueenBaseline() {
        assertEquals(0.97f, ChessBoardRenderer.BASE_PIECE_SCALE, 0.001f)

        val squareSize = 120f
        val squareTop = 240f
        val center = Offset(x = 180f, y = squareTop + squareSize / 2f)

        val queenRect = ChessBoardRenderer.computePieceDrawRect(
            pieceType = PieceType.QUEEN,
            center = center,
            squareSize = squareSize
        )
        val queenMetrics = ChessBoardRenderer.spriteMetricsFor(PieceType.QUEEN)
        val queenVisibleTop = queenRect.top + queenMetrics.top * queenRect.size
        val queenVisibleBottom = queenRect.top + queenMetrics.bottom * queenRect.size

        val queenTopMargin = queenVisibleTop - squareTop
        val queenBottomMargin = (squareTop + squareSize) - queenVisibleBottom

        // Queen must be vertically centered inside the square
        assertEquals(queenTopMargin, queenBottomMargin, 0.01f)
        assertTrue("Queen should remain inside square vertically", queenTopMargin > 0f && queenBottomMargin > 0f)

        // Every other piece must sit on the exact same baseline as the Queen and not clip the square
        for (pieceType in PieceType.entries) {
            val rect = ChessBoardRenderer.computePieceDrawRect(
                pieceType = pieceType,
                center = center,
                squareSize = squareSize
            )
            val metrics = ChessBoardRenderer.spriteMetricsFor(pieceType)
            val visibleTop = rect.top + metrics.top * rect.size
            val visibleBottom = rect.top + metrics.bottom * rect.size

            assertEquals(
                "Piece $pieceType bottom must align with Queen baseline",
                queenVisibleBottom,
                visibleBottom,
                0.01f
            )
            assertTrue(
                "Piece $pieceType top ($visibleTop) must not clip above squareTop ($squareTop)",
                visibleTop > squareTop
            )
            assertTrue(
                "Piece $pieceType bottom ($visibleBottom) must not clip below square bottom",
                visibleBottom < squareTop + squareSize
            )
        }
    }

    @Test
    fun soundSelector_coversAllOutcomesAndSpecialMoves() {
        assertEquals(
            ChessSoundEffect.GAME_WIN,
            ChessSoundSelector.selectGameOverSound(GameResult.WHITE_WIN, PieceColor.WHITE)
        )
        assertEquals(
            ChessSoundEffect.GAME_LOSE,
            ChessSoundSelector.selectGameOverSound(GameResult.WHITE_WIN, PieceColor.BLACK)
        )
        assertEquals(
            ChessSoundEffect.GAME_DRAW,
            ChessSoundSelector.selectGameOverSound(GameResult.DRAW, PieceColor.WHITE)
        )
        assertEquals(
            ChessSoundEffect.GAME_END,
            ChessSoundSelector.selectGameOverSound(GameResult.ABORTED, PieceColor.WHITE)
        )

        val engine = ChesslibChessEngine()
        val e4 = engine.attemptMove(
            BoardSquare.fromAlgebraic("e2")!!,
            BoardSquare.fromAlgebraic("e4")!!
        ) as com.example.core.chess.EngineMoveResult.Success
        assertEquals(
            ChessSoundEffect.MOVE_SELF,
            ChessSoundSelector.selectMoveSound(e4.move, isOpponentMove = false)
        )
        assertEquals(
            ChessSoundEffect.MOVE_OPPONENT,
            ChessSoundSelector.selectMoveSound(e4.move, isOpponentMove = true)
        )

        val d5 = engine.attemptMove(
            BoardSquare.fromAlgebraic("d7")!!,
            BoardSquare.fromAlgebraic("d5")!!
        ) as com.example.core.chess.EngineMoveResult.Success
        val exd5 = engine.attemptMove(
            BoardSquare.fromAlgebraic("e4")!!,
            BoardSquare.fromAlgebraic("d5")!!
        ) as com.example.core.chess.EngineMoveResult.Success
        assertEquals(
            ChessSoundEffect.CAPTURE,
            ChessSoundSelector.selectMoveSound(exd5.move, isOpponentMove = false)
        )
        assertTrue(d5.move.san == "d5")

        // Verify all 16 distinct raw audio files are referenced by ChessSoundEffect
        val distinctRawIds = ChessSoundEffect.entries.map { it.rawRes }.distinct()
        assertEquals(16, distinctRawIds.size)
    }

    @Test
    fun gameViewModel_playsExpectedSoundsForEventsAndRespectsSoundToggle() {
        val recorder = RecordingSoundPlayer()
        val clock = ManualClock()
        val settingsRepo = MutableSettingsRepo(AppSettings(soundEnabled = true, confirmResign = false))
        val vm = GameViewModel(
            chessEngine = ChesslibChessEngine(),
            timeProvider = clock,
            observeSettingsUseCase = ObserveSettingsUseCase(settingsRepo),
            soundPlayer = recorder
        )

        vm.initializeSession(timeControlId = "1+0", playerSide = PieceColor.WHITE)
        assertEquals(ChessSoundEffect.GAME_START, recorder.played.last())

        // Selecting a piece must NOT play any sound
        val countBeforeTap = recorder.played.size
        vm.onSquareTap(BoardSquare.fromAlgebraic("f2")!!)
        assertEquals(countBeforeTap, recorder.played.size)

        // Regular move + capture + illegal + premove + draw offer + 10s warning + game lose
        assertTrue(vm.onMoveAttempt(BoardSquare.fromAlgebraic("f2")!!, BoardSquare.fromAlgebraic("f3")!!, false))
        assertEquals(ChessSoundEffect.MOVE_SELF, recorder.played.last())

        // Illegal move triggers ILLEGAL
        vm.onMoveAttempt(BoardSquare.fromAlgebraic("a7")!!, BoardSquare.fromAlgebraic("a4")!!, false)
        assertEquals(ChessSoundEffect.ILLEGAL, recorder.played.last())

        // Advance Black's 60s clock by 52 seconds -> 8s remaining -> triggers TEN_SECONDS once
        clock.nowMs += 52_000L
        vm.refreshClockNow()
        assertEquals(ChessSoundEffect.TEN_SECONDS, recorder.played.last())
        val countAfterFirstWarn = recorder.played.count { it == ChessSoundEffect.TEN_SECONDS }
        clock.nowMs += 1_000L
        vm.refreshClockNow()
        assertEquals(countAfterFirstWarn, recorder.played.count { it == ChessSoundEffect.TEN_SECONDS })

        // Draw offer triggers DRAW_OFFER
        clock.nowMs += 500L
        vm.offerDraw()
        assertEquals(ChessSoundEffect.DRAW_OFFER, recorder.played.last())

        // Complete Fool's mate -> White loses -> GAME_LOSE
        assertTrue(vm.onMoveAttempt(BoardSquare.fromAlgebraic("e7")!!, BoardSquare.fromAlgebraic("e5")!!, false))
        assertEquals(ChessSoundEffect.MOVE_OPPONENT, recorder.played.last())
        assertTrue(vm.onMoveAttempt(BoardSquare.fromAlgebraic("g2")!!, BoardSquare.fromAlgebraic("g4")!!, false))
        assertTrue(vm.onMoveAttempt(BoardSquare.fromAlgebraic("d8")!!, BoardSquare.fromAlgebraic("h4")!!, false))
        assertEquals(ChessSoundEffect.GAME_LOSE, recorder.played.last())

        // Muting soundEnabled suppresses all subsequent sounds
        val beforeMuteCount = recorder.played.size
        settingsRepo.state.value = settingsRepo.state.value.copy(soundEnabled = false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        vm.onInvalidDrop(BoardSquare.fromAlgebraic("e2")!!, BoardSquare.fromAlgebraic("e5")!!)
        vm.onBoardFlipped()
        assertEquals(beforeMuteCount, recorder.played.size)
    }
}
