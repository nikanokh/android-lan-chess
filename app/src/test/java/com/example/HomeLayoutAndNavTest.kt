package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.core.ui.navigation.AppDestination
import com.example.core.ui.navigation.navigateTopLevelTab
import com.example.core.ui.theme.ChessLanTheme
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeLayoutAndNavTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreen_displaysPlayJoinAndQuickToolsWithoutRemovedSections() {
        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                HomeScreen(
                    uiState = HomeUiState(),
                    onPlayClick = {},
                    onJoinGameClick = {},
                    onHistoryClick = {},
                    onAnalysisClick = {},
                    onProfileClick = {},
                    onSettingsClick = {}
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Логотип ChessLAN").assertIsDisplayed()
        composeRule.onNodeWithTag("home_play_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_join_game_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_history_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_analysis_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_create_game_button").assertDoesNotExist()
        composeRule.onNodeWithTag("home_mode_wifi_lan").assertDoesNotExist()
        composeRule.onNodeWithTag("home_mode_hotspot").assertDoesNotExist()
        composeRule.onNodeWithTag("home_mode_wifi_direct").assertDoesNotExist()
        composeRule.onNodeWithTag("home_last_game_card").assertDoesNotExist()
    }

    @Test
    fun topLevelNavigation_homeTabAlwaysReturnsToHomeAfterSubRoutesAndTabs() {
        composeRule.setContent {
            ChessLanTheme(darkTheme = true) {
                val navController = rememberNavController()
                Column(modifier = Modifier.fillMaxSize()) {
                    Row {
                        Button(
                            onClick = { navigateTopLevelTab(navController, AppDestination.Home) },
                            modifier = Modifier.testTag("test_nav_home")
                        ) { Text("Home") }
                        Button(
                            onClick = { navigateTopLevelTab(navController, AppDestination.CreateGame) },
                            modifier = Modifier.testTag("test_nav_play")
                        ) { Text("Play") }
                        Button(
                            onClick = { navigateTopLevelTab(navController, AppDestination.History) },
                            modifier = Modifier.testTag("test_nav_history")
                        ) { Text("History") }
                        Button(
                            onClick = { navigateTopLevelTab(navController, AppDestination.Settings) },
                            modifier = Modifier.testTag("test_nav_settings")
                        ) { Text("Settings") }
                    }
                    NavHost(
                        navController = navController,
                        startDestination = AppDestination.Home
                    ) {
                        composable<AppDestination.Home> {
                            HomeScreen(
                                uiState = HomeUiState(),
                                onPlayClick = { navigateTopLevelTab(navController, AppDestination.CreateGame) },
                                onJoinGameClick = { mode ->
                                    navController.navigate(AppDestination.JoinGame(initialMode = mode))
                                },
                                onHistoryClick = { navigateTopLevelTab(navController, AppDestination.History) },
                                onAnalysisClick = { navController.navigate(AppDestination.Analysis) },
                                onProfileClick = { navController.navigate(AppDestination.Profile) },
                                onSettingsClick = { navigateTopLevelTab(navController, AppDestination.Settings) }
                            )
                        }
                        composable<AppDestination.CreateGame> {
                            Text("Create Screen", modifier = Modifier.testTag("screen_create"))
                        }
                        composable<AppDestination.History> {
                            Text("History Screen", modifier = Modifier.testTag("screen_history"))
                        }
                        composable<AppDestination.Settings> {
                            Text("Settings Screen", modifier = Modifier.testTag("screen_settings"))
                        }
                        composable<AppDestination.JoinGame> {
                            Text("Join Screen", modifier = Modifier.testTag("screen_join"))
                        }
                        composable<AppDestination.Analysis> {
                            Text("Analysis Screen", modifier = Modifier.testTag("screen_analysis"))
                        }
                        composable<AppDestination.Profile> {
                            Text("Profile Screen", modifier = Modifier.testTag("screen_profile"))
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_hero_card").assertIsDisplayed()

        // 1. Navigate via Home Play button -> CreateGame, then press Home tab -> must return to Home
        composeRule.onNodeWithTag("home_play_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_create").assertIsDisplayed()

        composeRule.onNodeWithTag("test_nav_home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_hero_card").assertIsDisplayed()

        // 2. Navigate to nested JoinGame, then switch tabs and return Home -> must always land on Home
        composeRule.onNodeWithTag("home_join_game_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_join").assertIsDisplayed()

        composeRule.onNodeWithTag("test_nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()

        composeRule.onNodeWithTag("test_nav_home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_hero_card").assertIsDisplayed()

        // 3. Switch Play -> History -> Home repeatedly
        composeRule.onNodeWithTag("test_nav_play").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_create").assertIsDisplayed()

        composeRule.onNodeWithTag("test_nav_history").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_history").assertIsDisplayed()

        composeRule.onNodeWithTag("test_nav_home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_hero_card").assertIsDisplayed()
    }
}
