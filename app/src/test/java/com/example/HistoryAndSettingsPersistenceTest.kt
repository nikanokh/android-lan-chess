package com.example

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.MonotonicTimeProvider
import com.example.core.database.ChessDatabase
import com.example.core.ui.board.BoardSquare
import com.example.data.local.SettingsDataStore
import com.example.data.repository.GameRepositoryImpl
import com.example.domain.model.AppSettings
import com.example.domain.model.BoardTheme
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import com.example.domain.model.PieceSet
import com.example.domain.repository.SettingsRepository
import com.example.domain.usecase.ObserveSettingsUseCase
import com.example.feature.game.GameViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoryAndSettingsPersistenceTest {

    private lateinit var context: Context
    private lateinit var database: ChessDatabase
    private lateinit var gameRepository: GameRepositoryImpl

    private class StubSettingsRepository(initial: AppSettings) : SettingsRepository {
        private val flow = MutableStateFlow(initial)
        override fun observeSettings(): Flow<AppSettings> = flow
        override suspend fun updatePlayerName(name: String) { flow.value = flow.value.copy(playerName = name) }
        override suspend fun updateBoardTheme(theme: BoardTheme) { flow.value = flow.value.copy(boardTheme = theme) }
        override suspend fun updatePieceSet(pieceSet: PieceSet) { flow.value = flow.value.copy(pieceSet = pieceSet) }
        override suspend fun updateSoundEnabled(enabled: Boolean) { flow.value = flow.value.copy(soundEnabled = enabled) }
        override suspend fun updateAnimationEnabled(enabled: Boolean) { flow.value = flow.value.copy(animationEnabled = enabled) }
        override suspend fun updateShowCoordinates(enabled: Boolean) { flow.value = flow.value.copy(showCoordinates = enabled) }
        override suspend fun updateHighlightLegalMoves(enabled: Boolean) { flow.value = flow.value.copy(highlightLegalMoves = enabled) }
        override suspend fun updateHighlightLastMove(enabled: Boolean) { flow.value = flow.value.copy(highlightLastMove = enabled) }
        override suspend fun updateConfirmResign(enabled: Boolean) { flow.value = flow.value.copy(confirmResign = enabled) }
        override suspend fun updateVibrationEnabled(enabled: Boolean) { flow.value = flow.value.copy(vibrationEnabled = enabled) }
        override suspend fun updatePremovesEnabled(enabled: Boolean) { flow.value = flow.value.copy(premovesEnabled = enabled) }
    }

    private class FakeClock(var nowMs: Long = 10_000L) : MonotonicTimeProvider {
        override fun elapsedRealtimeMs(): Long = nowMs
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ChessDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gameRepository = GameRepositoryImpl(database.gameDao(), database.moveDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun completedLocalGame_isPersistedAutomaticallyToRoomHistory() = runBlocking {
        val settingsRepo = StubSettingsRepository(
            AppSettings(playerName = "Дмитрий", confirmResign = false, animationEnabled = false)
        )
        val viewModel = GameViewModel(
            chessEngine = ChesslibChessEngine(),
            timeProvider = FakeClock(),
            observeSettingsUseCase = ObserveSettingsUseCase(settingsRepo),
            gameRepository = gameRepository
        )

        viewModel.initializeSession(
            timeControlId = "45+15",
            playerSide = PieceColor.WHITE,
            isLanMatch = false,
            playerName = "Дмитрий"
        )

        // Fool's mate: 1. f3 e5 2. g4 Qh4#
        fun playMove(from: String, to: String) {
            val moved = viewModel.onMoveAttempt(
                from = requireNotNull(BoardSquare.fromAlgebraic(from)),
                to = requireNotNull(BoardSquare.fromAlgebraic(to)),
                fromDrag = false
            )
            assertTrue("Expected legal move $from->$to", moved)
        }
        playMove("f2", "f3")
        playMove("e7", "e5")
        playMove("g2", "g4")
        playMove("d8", "h4")

        assertTrue(viewModel.uiState.value.isGameOver)
        assertEquals(GameResult.BLACK_WIN, viewModel.uiState.value.outcome?.result)

        // Allow viewModelScope coroutine to write to Room
        var history = emptyList<com.example.domain.model.GameSummary>()
        repeat(40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            history = gameRepository.observeGameHistory().first()
            if (history.isNotEmpty()) return@repeat
            delay(25)
        }

        assertEquals(1, history.size)
        val saved = history.first()
        assertEquals("Дмитрий", saved.whitePlayer)
        assertEquals("Соперник", saved.blackPlayer)
        assertEquals(GameResult.BLACK_WIN, saved.result)
        assertEquals("45+15", saved.timeControlLabel)
        assertEquals(4, saved.moveCount)
        assertTrue(saved.pgn.contains("Qh4#"))

        val latest = gameRepository.observeLatestGame().first()
        assertNotNull(latest)
        assertEquals(saved.id, latest?.id)

        val moves = database.moveDao().observeMovesForGame(saved.id).first()
        assertEquals(4, moves.size)
        assertEquals("Qh4#", moves.last().san)
    }

    @Test
    fun settingsDataStore_persistsCustomPlayerNameAndFallsBackWhenCleared() = runBlocking {
        val dataStore = SettingsDataStore(context)

        dataStore.updatePlayerName("  Максим  ")
        val updated = dataStore.settingsFlow.first()
        assertEquals("Максим", updated.playerName)

        dataStore.updatePlayerName("   ")
        val reset = dataStore.settingsFlow.first()
        assertEquals("Игрок", reset.playerName)
    }
}
