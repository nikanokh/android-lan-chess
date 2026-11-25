package com.example

import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.MonotonicTimeProvider
import com.example.core.chess.PremoveCalculator
import com.example.core.ui.board.BoardPiece
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.PieceType
import com.example.domain.model.AppSettings
import com.example.domain.model.BoardTheme
import com.example.domain.model.PieceColor
import com.example.domain.model.PieceSet
import com.example.domain.model.TimeControl
import com.example.domain.repository.SettingsRepository
import com.example.domain.usecase.ObserveSettingsUseCase
import com.example.feature.game.GameViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class PremoveFakeSettingsRepository(
    premovesEnabled: Boolean = true
) : SettingsRepository {
    val state = MutableStateFlow(
        AppSettings(animationEnabled = false, premovesEnabled = premovesEnabled)
    )
    override fun observeSettings(): Flow<AppSettings> = state
    override suspend fun updatePlayerName(name: String) {}
    override suspend fun updateBoardTheme(theme: BoardTheme) {}
    override suspend fun updatePieceSet(pieceSet: PieceSet) {}
    override suspend fun updateSoundEnabled(enabled: Boolean) {}
    override suspend fun updateAnimationEnabled(enabled: Boolean) {}
    override suspend fun updateShowCoordinates(enabled: Boolean) {}
    override suspend fun updateHighlightLegalMoves(enabled: Boolean) {}
    override suspend fun updateHighlightLastMove(enabled: Boolean) {}
    override suspend fun updateConfirmResign(enabled: Boolean) {}
    override suspend fun updateVibrationEnabled(enabled: Boolean) {}
    override suspend fun updatePremovesEnabled(enabled: Boolean) {
        state.value = state.value.copy(premovesEnabled = enabled)
    }
}

private class PremoveFakeClock(var nowMs: Long = 1_000L) : MonotonicTimeProvider {
    override fun elapsedRealtimeMs(): Long = nowMs
}

class PremoveTest {

    private fun sq(algebraic: String): BoardSquare = BoardSquare.fromAlgebraic(algebraic)!!

    @Test
    fun premoves_disabledByDefaultInSettingsAndIgnoredWhenOff() {
        assertFalse(AppSettings().premovesEnabled)

        val clock = PremoveFakeClock()
        val vm = GameViewModel(
            chessEngine = ChesslibChessEngine(),
            timeProvider = clock,
            observeSettingsUseCase = ObserveSettingsUseCase(
                PremoveFakeSettingsRepository(premovesEnabled = false)
            )
        )
        vm.initializeSession(TimeControl.BLITZ_5_0.id)

        // White's turn: Black cannot select or queue a premove while premovesEnabled == false
        vm.onSquareTap(sq("e7"))
        assertFalse(vm.uiState.value.boardState.isPremoveSelection)
        assertNull(vm.uiState.value.boardState.selectedSquare)
        assertFalse(vm.onMoveAttempt(sq("e7"), sq("e5"), fromDrag = false))
        assertTrue(vm.uiState.value.queuedPremoves.isEmpty())
        assertEquals(
            BoardPiece(PieceType.PAWN, PieceColor.BLACK),
            vm.uiState.value.boardState.pieces[sq("e7")]
        )
        assertNull(vm.uiState.value.boardState.pieces[sq("e5")])
    }

    @Test
    fun premoveCalculator_generatesExpectedTargetsForAllPieceTypes() {
        val whitePawnTargets = PremoveCalculator.premoveTargetsFor(
            sq("e2"),
            BoardPiece(PieceType.PAWN, PieceColor.WHITE)
        ).map { it.square }.toSet()
        assertEquals(setOf(sq("e3"), sq("e4"), sq("d3"), sq("f3")), whitePawnTargets)

        val knightTargets = PremoveCalculator.premoveTargetsFor(
            sq("b1"),
            BoardPiece(PieceType.KNIGHT, PieceColor.WHITE)
        ).map { it.square }.toSet()
        assertEquals(setOf(sq("a3"), sq("c3"), sq("d2")), knightTargets)

        val kingTargets = PremoveCalculator.premoveTargetsFor(
            sq("e1"),
            BoardPiece(PieceType.KING, PieceColor.WHITE)
        ).map { it.square }.toSet()
        assertTrue(kingTargets.contains(sq("g1")))
        assertTrue(kingTargets.contains(sq("c1")))
        assertTrue(kingTargets.contains(sq("e2")))

        assertTrue(
            PremoveCalculator.isPremovePromotion(
                from = sq("e7"),
                to = sq("e8"),
                piece = BoardPiece(PieceType.PAWN, PieceColor.WHITE)
            )
        )
        assertFalse(
            PremoveCalculator.isPremovePromotion(
                from = sq("e2"),
                to = sq("e4"),
                piece = BoardPiece(PieceType.PAWN, PieceColor.WHITE)
            )
        )
    }

    @Test
    fun premove_placesPieceVirtuallyOnTargetAndExecutesAutomaticallyWhenOpponentMoves() {
        val clock = PremoveFakeClock()
        val vm = GameViewModel(
            chessEngine = ChesslibChessEngine(),
            timeProvider = clock,
            observeSettingsUseCase = ObserveSettingsUseCase(PremoveFakeSettingsRepository(premovesEnabled = true))
        )
        vm.initializeSession(TimeControl.BLITZ_5_0.id)

        // White's turn: Black queues premove e7 -> e5
        vm.onSquareTap(sq("e7"))
        assertTrue(vm.uiState.value.boardState.isPremoveSelection)
        assertTrue(vm.onMoveAttempt(sq("e7"), sq("e5"), fromDrag = false))
        assertNotNull(vm.uiState.value.queuedPremove)
        assertEquals(sq("e7"), vm.uiState.value.queuedPremove?.from)
        assertEquals(sq("e5"), vm.uiState.value.queuedPremove?.to)

        // Virtually placed on target square e5 and removed from origin e7
        assertNull(vm.uiState.value.boardState.pieces[sq("e7")])
        assertEquals(
            BoardPiece(PieceType.PAWN, PieceColor.BLACK),
            vm.uiState.value.boardState.pieces[sq("e5")]
        )

        // White plays 1. e4 -> Black's premove 1... e5 fires automatically!
        assertTrue(vm.onMoveAttempt(sq("e2"), sq("e4"), fromDrag = false))

        val state = vm.uiState.value
        assertNull(state.queuedPremove)
        assertEquals(2, state.moveHistory.size)
        assertEquals("e4", state.moveHistory[0].san)
        assertEquals("e5", state.moveHistory[1].san)
        assertEquals(PieceColor.WHITE, state.sideToMove)
        assertEquals(PieceColor.WHITE, state.clock.activeColor)
        assertTrue(state.clock.isRunning)
    }

    @Test
    fun multiPremove_supportsChainingSamePieceAndDifferentPiecesInOrder() {
        val clock = PremoveFakeClock()
        val vm = GameViewModel(
            chessEngine = ChesslibChessEngine(),
            timeProvider = clock,
            observeSettingsUseCase = ObserveSettingsUseCase(PremoveFakeSettingsRepository(premovesEnabled = true))
        )
        vm.initializeSession(TimeControl.BLITZ_5_0.id)

        // White's turn: Black chains 3 premoves including 2 with the SAME knight:
        // 1) g8 -> f6, 2) f6 -> e4 (same knight from its virtual square!), 3) b8 -> c6 (second knight)
        assertTrue(vm.onMoveAttempt(sq("g8"), sq("f6"), fromDrag = false))
        assertTrue(vm.onMoveAttempt(sq("f6"), sq("e4"), fromDrag = false))
        assertTrue(vm.onMoveAttempt(sq("b8"), sq("c6"), fromDrag = false))

        val queuedState = vm.uiState.value
        assertEquals(3, queuedState.queuedPremoves.size)
        assertEquals(3, queuedState.boardState.queuedPremoves.size)
        // Virtual board shows g8 knight already standing on e4, and b8 knight on c6
        assertNull(queuedState.boardState.pieces[sq("g8")])
        assertNull(queuedState.boardState.pieces[sq("f6")])
        assertEquals(BoardPiece(PieceType.KNIGHT, PieceColor.BLACK), queuedState.boardState.pieces[sq("e4")])
        assertNull(queuedState.boardState.pieces[sq("b8")])
        assertEquals(BoardPiece(PieceType.KNIGHT, PieceColor.BLACK), queuedState.boardState.pieces[sq("c6")])

        // White plays 1. a3 -> Black's premove #1 (g8->f6) executes; premoves #2 and #3 remain queued & virtually placed
        assertTrue(vm.onMoveAttempt(sq("a2"), sq("a3"), fromDrag = false))
        val afterTurn1 = vm.uiState.value
        assertEquals(listOf("a3", "Nf6"), afterTurn1.moveHistory.map { it.san })
        assertEquals(2, afterTurn1.queuedPremoves.size)
        assertEquals(sq("f6"), afterTurn1.queuedPremoves[0].from)
        assertEquals(sq("e4"), afterTurn1.queuedPremoves[0].to)
        assertEquals(BoardPiece(PieceType.KNIGHT, PieceColor.BLACK), afterTurn1.boardState.pieces[sq("e4")])
        assertEquals(BoardPiece(PieceType.KNIGHT, PieceColor.BLACK), afterTurn1.boardState.pieces[sq("c6")])

        // White plays 2. h3 -> Black's premove #2 (f6->e4) executes; premove #3 (b8->c6) remains queued
        assertTrue(vm.onMoveAttempt(sq("h2"), sq("h3"), fromDrag = false))
        val afterTurn2 = vm.uiState.value
        assertEquals(listOf("a3", "Nf6", "h3", "Ne4"), afterTurn2.moveHistory.map { it.san })
        assertEquals(1, afterTurn2.queuedPremoves.size)
        assertEquals(sq("b8"), afterTurn2.queuedPremoves[0].from)
        assertEquals(sq("c6"), afterTurn2.queuedPremoves[0].to)

        // White plays 3. d3 -> Black's premove #3 (b8->c6) executes; queue now empty
        assertTrue(vm.onMoveAttempt(sq("d2"), sq("d3"), fromDrag = false))
        val afterTurn3 = vm.uiState.value
        assertEquals(listOf("a3", "Nf6", "h3", "Ne4", "d3", "Nc6"), afterTurn3.moveHistory.map { it.san })
        assertTrue(afterTurn3.queuedPremoves.isEmpty())
    }

    @Test
    fun illegalPremoveHead_cancelsEntireRemainingChainAndRestoresRealBoard() {
        val clock = PremoveFakeClock()
        val vm = GameViewModel(
            chessEngine = ChesslibChessEngine(),
            timeProvider = clock,
            observeSettingsUseCase = ObserveSettingsUseCase(PremoveFakeSettingsRepository(premovesEnabled = true))
        )
        vm.initializeSession(TimeControl.BLITZ_5_0.id)

        // White's turn: Black queues illegal diagonal capture d7->e6 followed by valid g8->f6
        assertTrue(vm.onMoveAttempt(sq("d7"), sq("e6"), fromDrag = true))
        assertTrue(vm.onMoveAttempt(sq("g8"), sq("f6"), fromDrag = true))
        assertEquals(2, vm.uiState.value.queuedPremoves.size)

        // White plays 1. e4 -> head premove d7->e6 is illegal -> entire chain cancelled, real pieces restored
        assertTrue(vm.onMoveAttempt(sq("e2"), sq("e4"), fromDrag = false))

        val state = vm.uiState.value
        assertTrue(state.queuedPremoves.isEmpty())
        assertNull(state.queuedPremove)
        assertEquals(1, state.moveHistory.size)
        assertEquals(PieceColor.BLACK, state.sideToMove)
        assertEquals(PieceColor.BLACK, state.clock.activeColor)
        assertEquals(BoardPiece(PieceType.PAWN, PieceColor.BLACK), state.boardState.pieces[sq("d7")])
        assertEquals(BoardPiece(PieceType.KNIGHT, PieceColor.BLACK), state.boardState.pieces[sq("g8")])
        assertNull(state.boardState.pieces[sq("f6")])
    }

    @Test
    fun undoMove_alwaysSwitchesActiveClockToSideToMoveAndClearsPremove() {
        val clock = PremoveFakeClock(nowMs = 1_000L)
        val vm = GameViewModel(
            chessEngine = ChesslibChessEngine(),
            timeProvider = clock,
            observeSettingsUseCase = ObserveSettingsUseCase(PremoveFakeSettingsRepository(premovesEnabled = true))
        )
        vm.initializeSession(TimeControl.BLITZ_5_0.id)

        // 1. e4 -> Black's clock runs
        assertTrue(vm.onMoveAttempt(sq("e2"), sq("e4"), false))
        assertEquals(PieceColor.BLACK, vm.uiState.value.sideToMove)
        assertEquals(PieceColor.BLACK, vm.uiState.value.clock.activeColor)

        clock.nowMs += 3_000L
        // 1... e5 -> White's clock runs
        assertTrue(vm.onMoveAttempt(sq("e7"), sq("e5"), false))
        assertEquals(PieceColor.WHITE, vm.uiState.value.sideToMove)
        assertEquals(PieceColor.WHITE, vm.uiState.value.clock.activeColor)

        clock.nowMs += 2_000L
        // 2. Nf3 -> Black's clock runs
        assertTrue(vm.onMoveAttempt(sq("g1"), sq("f3"), false))
        assertEquals(PieceColor.BLACK, vm.uiState.value.sideToMove)
        assertEquals(PieceColor.BLACK, vm.uiState.value.clock.activeColor)

        // White queues a premove, then user undoes 2. Nf3
        assertTrue(vm.onMoveAttempt(sq("d2"), sq("d4"), false))
        assertNotNull(vm.uiState.value.queuedPremove)

        clock.nowMs += 1_000L
        vm.undoMove()

        // After undoing 2. Nf3: it is WHITE's turn AND WHITE's clock must tick (NOT Black's!), premove cleared
        val afterUndo = vm.uiState.value
        assertEquals(PieceColor.WHITE, afterUndo.sideToMove)
        assertEquals(PieceColor.WHITE, afterUndo.clock.activeColor)
        assertTrue(afterUndo.clock.isRunning)
        assertNull(afterUndo.queuedPremove)

        val blackMsBeforeWait = afterUndo.clock.blackRemainingMs
        val whiteMsBeforeWait = afterUndo.clock.whiteRemainingMs
        clock.nowMs += 4_000L
        vm.refreshClockNow()

        assertEquals(blackMsBeforeWait, vm.uiState.value.clock.blackRemainingMs)
        assertEquals(whiteMsBeforeWait - 4_000L, vm.uiState.value.clock.whiteRemainingMs)
    }

    @Test
    fun premovePromotion_promptsAndExecutesChosenPiece() {
        val engine = ChesslibChessEngine()
        // White pawn on a7 ready to promote, Black to move first (King on h8, pawn on h6)
        engine.loadFromFen("7k/P7/7p/8/8/8/8/4K3 b - - 0 1")
        val clock = PremoveFakeClock()
        val vm = GameViewModel(
            chessEngine = engine,
            timeProvider = clock,
            observeSettingsUseCase = ObserveSettingsUseCase(PremoveFakeSettingsRepository(premovesEnabled = true))
        )
        // Do NOT call initializeSession (would reset engine) — interact directly on loaded position
        // Black is to move; White queues premove a7 -> a8
        assertTrue(vm.onMoveAttempt(sq("a7"), sq("a8"), fromDrag = false))
        assertNotNull(vm.uiState.value.pendingPromotion)
        assertTrue(vm.uiState.value.pendingPromotion?.isPremove == true)

        vm.onPromotionSelected(PieceType.KNIGHT)
        assertNull(vm.uiState.value.pendingPromotion)
        assertEquals(PieceType.KNIGHT, vm.uiState.value.queuedPremove?.promotion)
        // Promoted knight is already virtually visible on a8
        assertEquals(BoardPiece(PieceType.KNIGHT, PieceColor.WHITE), vm.uiState.value.boardState.pieces[sq("a8")])
        assertNull(vm.uiState.value.boardState.pieces[sq("a7")])

        // Black plays h6 -> h5 -> White's premove a7->a8=N executes automatically!
        assertTrue(vm.onMoveAttempt(sq("h6"), sq("h5"), fromDrag = false))
        val promotedPiece = vm.uiState.value.boardState.pieces[sq("a8")]
        assertEquals(BoardPiece(PieceType.KNIGHT, PieceColor.WHITE), promotedPiece)
    }
}
