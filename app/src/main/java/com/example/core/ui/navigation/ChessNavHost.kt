package com.example.core.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.core.ui.theme.ChessTheme
import com.example.feature.analysis.AnalysisScreen
import com.example.feature.game.GameScreen
import com.example.feature.history.HistoryScreen
import com.example.feature.home.HomeRoute
import com.example.feature.lobby.CreateGameScreen
import com.example.feature.lobby.JoinGameScreen
import com.example.feature.lobby.LobbyScreen
import com.example.feature.profile.ProfileScreen
import com.example.feature.settings.SettingsScreen

private enum class TopLevelTab(
    val destination: AppDestination,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME(
        destination = AppDestination.Home,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_tab_home"
    ),
    PLAY(
        destination = AppDestination.CreateGame,
        labelRes = R.string.nav_play,
        selectedIcon = Icons.Rounded.AddBox,
        unselectedIcon = Icons.Outlined.AddBox,
        testTag = "nav_tab_play"
    ),
    HISTORY(
        destination = AppDestination.History,
        labelRes = R.string.nav_history,
        selectedIcon = Icons.Rounded.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_tab_history"
    ),
    SETTINGS(
        destination = AppDestination.Settings,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings"
    )
}

@Composable
fun ChessLanAppRoot(
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isTopLevel = currentDestination.isTopLevelDestination()

    val navigateToTab: (AppDestination) -> Unit = { destination ->
        navigateTopLevelTab(navController = navController, destination = destination)
    }

    val density = LocalDensity.current
    var measuredNavBarHeightPx by remember { mutableIntStateOf(0) }
    var measuredNavRailWidthPx by remember { mutableIntStateOf(0) }
    val fallbackBottomBarHeight = 80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val useNavRail = maxWidth >= 600.dp
        val topLevelBottomPadding = if (useNavRail) {
            0.dp
        } else if (measuredNavBarHeightPx > 0) {
            with(density) { measuredNavBarHeightPx.toDp() }
        } else {
            fallbackBottomBarHeight
        }
        val topLevelStartPadding = if (!useNavRail) {
            0.dp
        } else if (measuredNavRailWidthPx > 0) {
            with(density) { measuredNavRailWidthPx.toDp() }
        } else {
            80.dp
        }

        Box(modifier = Modifier.fillMaxSize()) {
            ChessNavHost(
                navController = navController,
                showTopLevelBack = false,
                onSelectTopLevelTab = navigateToTab,
                topLevelStartPadding = topLevelStartPadding,
                topLevelBottomPadding = topLevelBottomPadding
            )

            if (useNavRail) {
                AnimatedVisibility(
                    visible = isTopLevel,
                    enter = fadeIn(animationSpec = tween(durationMillis = 90)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 70)),
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    ChessNavigationRail(
                        currentDestination = currentDestination,
                        onSelectTab = navigateToTab,
                        modifier = Modifier.onSizeChanged { size ->
                            if (size.width > 0) measuredNavRailWidthPx = size.width
                        }
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = isTopLevel,
                    enter = fadeIn(animationSpec = tween(durationMillis = 90)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 70)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    ChessNavigationBar(
                        currentDestination = currentDestination,
                        onSelectTab = navigateToTab,
                        modifier = Modifier.onSizeChanged { size ->
                            if (size.height > 0) measuredNavBarHeightPx = size.height
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChessNavigationBar(
    currentDestination: NavDestination?,
    onSelectTab: (AppDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    NavigationBar(
        modifier = modifier,
        containerColor = colors.backgroundSecondary,
        tonalElevation = 0.dp
    ) {
        TopLevelTab.entries.forEach { tab ->
            val selected = currentDestination?.hasRoute(tab.destination::class) == true
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(tab.destination) },
                modifier = Modifier.testTag(tab.testTag),
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = stringResource(tab.labelRes)
                    )
                },
                label = {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.accentGreen,
                    selectedTextColor = colors.accentGreen,
                    indicatorColor = colors.accentGreenContainer,
                    unselectedIconColor = colors.textSecondary,
                    unselectedTextColor = colors.textSecondary
                )
            )
        }
    }
}

@Composable
private fun ChessNavigationRail(
    currentDestination: NavDestination?,
    onSelectTab: (AppDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    NavigationRail(
        modifier = modifier,
        containerColor = colors.backgroundSecondary
    ) {
        TopLevelTab.entries.forEach { tab ->
            val selected = currentDestination?.hasRoute(tab.destination::class) == true
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(tab.destination) },
                modifier = Modifier.testTag(tab.testTag),
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = stringResource(tab.labelRes)
                    )
                },
                label = {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.accentGreen,
                    selectedTextColor = colors.accentGreen,
                    indicatorColor = colors.accentGreenContainer,
                    unselectedIconColor = colors.textSecondary,
                    unselectedTextColor = colors.textSecondary
                )
            )
        }
    }
}

@Composable
private fun ChessNavHost(
    navController: NavHostController,
    showTopLevelBack: Boolean,
    onSelectTopLevelTab: (AppDestination) -> Unit,
    topLevelStartPadding: Dp = 0.dp,
    topLevelBottomPadding: Dp = 0.dp
) {
    val topLevelModifier = Modifier
        .fillMaxSize()
        .padding(start = topLevelStartPadding, bottom = topLevelBottomPadding)

    NavHost(
        navController = navController,
        startDestination = AppDestination.Home,
        enterTransition = { fadeIn(animationSpec = tween(durationMillis = 90)) },
        exitTransition = { fadeOut(animationSpec = tween(durationMillis = 70)) },
        popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 90)) },
        popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 70)) }
    ) {
        composable<AppDestination.Home> {
            Box(modifier = topLevelModifier) {
                HomeRoute(
                    onPlayClick = { onSelectTopLevelTab(AppDestination.CreateGame) },
                    onJoinGameClick = { mode ->
                        navController.navigate(AppDestination.JoinGame(initialMode = mode))
                    },
                    onHistoryClick = { onSelectTopLevelTab(AppDestination.History) },
                    onAnalysisClick = { navController.navigate(AppDestination.Analysis) },
                    onProfileClick = { navController.navigate(AppDestination.Profile) },
                    onSettingsClick = { onSelectTopLevelTab(AppDestination.Settings) }
                )
            }
        }

        composable<AppDestination.CreateGame> {
            Box(modifier = topLevelModifier) {
                CreateGameScreen(
                    onNavigateBack = if (showTopLevelBack) {
                        { navController.popBackStack() }
                    } else {
                        null
                    },
                    onCreateRoom = { timeControlId, side, playerName, connectionType ->
                        navController.navigate(
                            AppDestination.Lobby(
                                timeControlId = timeControlId,
                                side = side,
                                playerName = playerName,
                                connectionType = connectionType,
                                isHost = true
                            )
                        )
                    }
                )
            }
        }

        composable<AppDestination.JoinGame> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.JoinGame>()
            JoinGameScreen(
                onNavigateBack = { navController.popBackStack() },
                onJoinedLobby = {
                    navController.navigate(
                        AppDestination.Lobby(
                            connectionType = route.initialMode,
                            isHost = false
                        )
                    )
                },
                onOpenLocalGame = {
                    navController.navigate(AppDestination.Game(isLanMatch = false))
                }
            )
        }

        composable<AppDestination.Lobby> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.Lobby>()
            val resolvedSide = when (route.side) {
                com.example.domain.model.SideSelection.WHITE -> com.example.domain.model.PieceColor.WHITE
                com.example.domain.model.SideSelection.BLACK -> com.example.domain.model.PieceColor.BLACK
                com.example.domain.model.SideSelection.RANDOM -> com.example.domain.model.PieceColor.WHITE
            }
            LobbyScreen(
                timeControlId = route.timeControlId,
                side = route.side,
                playerName = route.playerName,
                connectionType = route.connectionType,
                isHost = route.isHost,
                onNavigateBack = { navController.popBackStack() },
                onStartLanGame = { tcId, localColor, localName ->
                    navController.navigate(
                        AppDestination.Game(
                            timeControlId = tcId,
                            playerSide = localColor,
                            playerName = localName,
                            isLanMatch = true
                        )
                    ) {
                        popUpTo<AppDestination.Lobby> { inclusive = true }
                    }
                },
                onOpenLocalGame = {
                    navController.navigate(
                        AppDestination.Game(
                            timeControlId = route.timeControlId,
                            playerSide = resolvedSide,
                            playerName = route.playerName,
                            isLanMatch = false
                        )
                    )
                }
            )
        }

        composable<AppDestination.Game> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.Game>()
            GameScreen(
                timeControlId = route.timeControlId,
                playerSide = route.playerSide,
                playerName = route.playerName,
                isLanMatch = route.isLanMatch,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<AppDestination.History> {
            Box(modifier = topLevelModifier) {
                HistoryScreen(
                    onNavigateBack = if (showTopLevelBack) {
                        { navController.popBackStack() }
                    } else {
                        null
                    },
                    onAnalyzeGame = { gameId ->
                        navController.navigate(AppDestination.AnalysisForGame(gameId))
                    }
                )
            }
        }

        composable<AppDestination.Analysis> {
            AnalysisScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<AppDestination.AnalysisForGame> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDestination.AnalysisForGame>()
            AnalysisScreen(
                initialGameId = route.gameId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<AppDestination.Profile> {
            ProfileScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<AppDestination.Settings> {
            Box(modifier = topLevelModifier) {
                SettingsScreen(
                    onNavigateBack = if (showTopLevelBack) {
                        { navController.popBackStack() }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

private fun NavDestination?.isTopLevelDestination(): Boolean {
    if (this == null) return true
    return TopLevelTab.entries.any { tab -> hasRoute(tab.destination::class) }
}

internal fun navigateTopLevelTab(
    navController: NavHostController,
    destination: AppDestination
) {
    val currentDestination = navController.currentBackStackEntry?.destination
    if (destination == AppDestination.Home) {
        if (currentDestination?.hasRoute(AppDestination.Home::class) == true) {
            return
        }
        val poppedToHome = navController.popBackStack<AppDestination.Home>(inclusive = false)
        if (!poppedToHome) {
            navController.navigate(AppDestination.Home) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
        return
    }
    if (currentDestination?.hasRoute(destination::class) == true) {
        return
    }
    navController.navigate(destination) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}
