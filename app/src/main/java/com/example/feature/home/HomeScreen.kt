package com.example.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.network.ConnectionType
import com.example.core.ui.components.ChessPrimaryButton
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.components.SectionHeader
import com.example.core.ui.theme.ChessDimensions
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.toSquareColors
import com.example.domain.model.BoardTheme

@Composable
fun HomeRoute(
    onPlayClick: () -> Unit,
    onJoinGameClick: (ConnectionType) -> Unit,
    onHistoryClick: () -> Unit,
    onAnalysisClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        uiState = uiState,
        onPlayClick = onPlayClick,
        onJoinGameClick = onJoinGameClick,
        onHistoryClick = onHistoryClick,
        onAnalysisClick = onAnalysisClick,
        onProfileClick = onProfileClick,
        onSettingsClick = onSettingsClick
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onPlayClick: () -> Unit,
    onJoinGameClick: (ConnectionType) -> Unit,
    onHistoryClick: () -> Unit,
    onAnalysisClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = ChessDimensions.MaxContentWidth)
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                top = spacing.sm,
                bottom = spacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.lg)
        ) {
            item(key = "header") {
                HomeTopHeader(
                    playerName = uiState.playerName,
                    onProfileClick = onProfileClick,
                    onSettingsClick = onSettingsClick
                )
            }

            item(key = "hero") {
                HomeHeroPlayCard(
                    boardTheme = uiState.boardTheme,
                    onPlayClick = onPlayClick
                )
            }

            item(key = "primary_actions") {
                JoinGameActionCard(
                    onClick = { onJoinGameClick(ConnectionType.WIFI_LAN) },
                    testTag = "home_join_game_button"
                )
            }

            item(key = "quick_tools") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    SectionHeader(title = stringResource(R.string.home_quick_tools_title))
                    ChessSurfaceCard(
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        QuickToolRow(
                            title = stringResource(R.string.action_history),
                            subtitle = stringResource(R.string.action_history_desc),
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.accentAmberContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = colors.accentAmber,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            },
                            onClick = onHistoryClick,
                            testTag = "home_history_button"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(1.dp)
                                .background(colors.borderSubtle.copy(alpha = 0.6f))
                        )
                        QuickToolRow(
                            title = stringResource(R.string.action_analysis),
                            subtitle = stringResource(R.string.action_analysis_desc),
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.accentGreenContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.badge_brilliant),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            },
                            onClick = onAnalysisClick,
                            testTag = "home_analysis_button"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopHeader(
    playerName: String,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val colors = ChessTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = stringResource(R.string.cd_app_logo),
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(colors.accentGreen)
                    )
                    Text(
                        text = stringResource(R.string.home_offline_badge),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(
                shape = CircleShape,
                color = colors.surfaceCard,
                border = BorderStroke(1.dp, colors.borderSubtle)
            ) {
                IconButton(
                    onClick = onProfileClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("home_profile_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = stringResource(R.string.cd_profile),
                        tint = colors.textPrimary
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = colors.surfaceCard,
                border = BorderStroke(1.dp, colors.borderSubtle)
            ) {
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("home_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = colors.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeroPlayCard(
    boardTheme: BoardTheme,
    onPlayClick: () -> Unit
) {
    val colors = ChessTheme.colors
    val squareColors = boardTheme.toSquareColors()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_hero_card"),
        shape = RoundedCornerShape(ChessTheme.radii.lg),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            colors.surfaceCard,
                            colors.backgroundSecondary
                        )
                    )
                )
        ) {
            // Subtle architectural board texture accent in top-right corner
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(144.dp)
                    .alpha(0.22f)
            ) {
                val cell = size.width / 4f
                for (row in 0 until 4) {
                    for (col in 0 until 4) {
                        val isLight = (row + col) % 2 == 0
                        drawRect(
                            color = if (isLight) squareColors.light else squareColors.dark,
                            topLeft = Offset(col * cell, row * cell),
                            size = Size(cell, cell)
                        )
                    }
                }
            }

            // Layered chess pieces for signature visual craft
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.piece_bn),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .offset(x = (-32).dp, y = 10.dp)
                        .alpha(0.85f)
                )
                Image(
                    painter = painterResource(R.drawable.piece_wk),
                    contentDescription = null,
                    modifier = Modifier.size(66.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(0.72f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.home_hero_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.home_hero_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }

                ChessPrimaryButton(
                    text = stringResource(R.string.action_play),
                    onClick = onPlayClick,
                    leadingIcon = Icons.Rounded.PlayArrow,
                    testTag = "home_play_button"
                )
            }
        }
    }
}

@Composable
private fun JoinGameActionCard(
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors

    ChessSurfaceCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        onClick = onClick,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentBlueContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = colors.accentBlue,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.action_join_game),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.action_join_game_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textMuted
            )
        }
    }
}

@Composable
private fun QuickToolRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    testTag: String
) {
    val colors = ChessTheme.colors

    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            leading()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textMuted
            )
        }
    }
}
