package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.ChessDatabase
import com.example.core.database.entity.GameEntity
import com.example.core.database.entity.MoveEntity
import com.example.core.network.ConnectionType
import com.example.core.ui.theme.ChessLanTheme
import com.example.data.repository.GameRepositoryImpl
import com.example.domain.model.GameResult
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appNameResource_matchesChessLAN() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ChessLAN", context.getString(R.string.app_name))
    }

    @Test
    fun roomDatabase_insertsAndReadsGameWithMoveCount() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ChessDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val gameId = db.gameDao().insertGame(
                GameEntity(
                    gameUuid = "test-uuid-001",
                    createdAt = 1_700_000_000_000L,
                    finishedAt = 1_700_000_600_000L,
                    whitePlayer = "Анна",
                    blackPlayer = "Иван",
                    result = GameResult.WHITE_WIN.name,
                    timeControl = "5+3",
                    initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                    finalFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                    pgn = "1. e4",
                    connectionType = ConnectionType.WIFI_LAN.name
                )
            )
            db.moveDao().insertMove(
                MoveEntity(
                    gameId = gameId,
                    moveNumber = 1,
                    color = "WHITE",
                    san = "e4",
                    uci = "e2e4",
                    fenAfter = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                    timestamp = 1_700_000_010_000L
                )
            )

            val repo = GameRepositoryImpl(db.gameDao())
            val latest = repo.observeLatestGame().first()
            assertNotNull(latest)
            assertEquals("Анна", latest?.whitePlayer)
            assertEquals("Иван", latest?.blackPlayer)
            assertEquals(1, latest?.moveCount)
            assertEquals(GameResult.WHITE_WIN, latest?.result)
            assertEquals(ConnectionType.WIFI_LAN, latest?.connectionType)
        } finally {
            db.close()
        }
    }

    @Test
    fun homeScreen_displaysCoreActionsAndTriggersCallbacks() {
        var playClicked = false
        var joinMode: ConnectionType? = null

        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                HomeScreen(
                    uiState = HomeUiState(),
                    onPlayClick = { playClicked = true },
                    onJoinGameClick = { joinMode = it },
                    onHistoryClick = {},
                    onAnalysisClick = {},
                    onProfileClick = {},
                    onSettingsClick = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_play_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("home_join_game_button").assertIsDisplayed().performClick()

        assertTrue(playClicked)
        assertEquals(ConnectionType.WIFI_LAN, joinMode)
    }
}
