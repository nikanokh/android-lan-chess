package com.example.feature.lobby

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.network.ConnectionType
import com.example.core.network.HotspotNetworkHelper
import com.example.core.network.SessionRole
import com.example.core.ui.components.ChessPrimaryButton
import com.example.core.ui.components.ChessSecondaryButton
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.components.ChessTopBar
import com.example.core.ui.components.SectionHeader
import com.example.core.ui.components.StagePillBadge
import com.example.core.ui.theme.ChessDimensions
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.PieceColor
import com.example.domain.model.SideSelection
import com.example.domain.model.TimeControl

@Composable
fun LobbyScreen(
    timeControlId: String,
    side: SideSelection,
    playerName: String,
    connectionType: ConnectionType = ConnectionType.WIFI_LAN,
    isHost: Boolean = true,
    onNavigateBack: () -> Unit,
    onStartLanGame: (timeControlId: String, localColor: PieceColor, localName: String) -> Unit,
    onOpenLocalGame: () -> Unit,
    viewModel: LobbyViewModel = hiltViewModel()
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing

    LaunchedEffect(isHost, timeControlId, side, playerName, connectionType) {
        if (isHost) {
            viewModel.ensureHostRoomCreated(
                timeControlId = timeControlId,
                sideSelection = side,
                hostName = playerName,
                connectionType = connectionType
            )
        }
    }

    LaunchedEffect(sessionState.isGameStarted) {
        if (sessionState.isGameStarted) {
            onStartLanGame(
                sessionState.timeControl.id,
                sessionState.localColor,
                sessionState.localPlayerName.ifBlank { playerName }
            )
        }
    }

    val leaveAndNavigateBack: () -> Unit = {
        viewModel.leaveSession()
        onNavigateBack()
    }

    BackHandler(onBack = leaveAndNavigateBack)

    val roomInfo = sessionState.roomInfo
    val activeTimeControl = sessionState.timeControl
    val isHostRole = sessionState.role == SessionRole.HOST || (sessionState.role == SessionRole.NONE && isHost)
    val opponentConnected = sessionState.isOpponentConnected

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChessTopBar(
                title = stringResource(R.string.lobby_title),
                onNavigateBack = leaveAndNavigateBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = ChessDimensions.MaxContentWidth),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                item(key = "status_card") {
                    ChessSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StagePillBadge(
                                text = if (isHostRole) {
                                    stringResource(R.string.lobby_role_host)
                                } else {
                                    stringResource(R.string.lobby_role_client)
                                }
                            )
                            Icon(
                                imageVector = if (opponentConnected) Icons.Rounded.Wifi else Icons.Rounded.HourglassTop,
                                contentDescription = null,
                                tint = colors.accentGreen,
                                modifier = Modifier.size(38.dp)
                            )
                            Text(
                                text = if (opponentConnected) {
                                    stringResource(R.string.lobby_opponent_connected_title)
                                } else {
                                    stringResource(R.string.lobby_waiting_title)
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                color = colors.textPrimary,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (opponentConnected) {
                                    stringResource(R.string.lobby_opponent_connected_desc)
                                } else {
                                    stringResource(R.string.lobby_waiting_desc)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                if (!sessionState.lastError.isNullOrBlank()) {
                    item(key = "lobby_error") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("lobby_error_banner"),
                            shape = RoundedCornerShape(12.dp),
                            color = colors.accentCoralContainer,
                            border = BorderStroke(1.dp, colors.accentCoral.copy(alpha = 0.7f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = colors.accentCoral
                                )
                                Text(
                                    text = sessionState.lastError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                val effectiveConnectionType = if (sessionState.isActiveLanSession) {
                    sessionState.connectionType
                } else {
                    connectionType
                }

                if (isHostRole && effectiveConnectionType == ConnectionType.HOTSPOT) {
                    item(key = "hotspot_host_guide") {
                        HotspotHostGuideCard(
                            onOpenHotspotSettings = { HotspotNetworkHelper.openHotspotSettings(context) },
                            onRefreshIp = { viewModel.refreshHostAddress() }
                        )
                    }
                }

                item(key = "room_network_info") {
                    ChessSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (sessionState.roomCode.isNotBlank()) {
                                LobbyInfoRow(
                                    label = stringResource(R.string.lobby_room_code),
                                    value = sessionState.roomCode,
                                    isMono = true,
                                    highlight = true
                                )
                            }
                            if (sessionState.port > 0) {
                                val addressText = sessionState.hostAddress?.let { "$it:${sessionState.port}" }
                                    ?: ":${sessionState.port}"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.lobby_address_port),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textSecondary
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = addressText,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontFamily = MonoFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = colors.textPrimary
                                        )
                                        if (isHostRole) {
                                            IconButton(
                                                onClick = { viewModel.refreshHostAddress() },
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .testTag("lobby_inline_refresh_ip_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Refresh,
                                                    contentDescription = stringResource(R.string.hotspot_refresh_ip),
                                                    tint = colors.accentGreen,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            LobbyInfoRow(
                                label = stringResource(R.string.create_game_time_control),
                                value = if (activeTimeControl.isUnlimited) {
                                    stringResource(R.string.time_control_unlimited)
                                } else {
                                    activeTimeControl.label
                                },
                                isMono = true
                            )
                        }
                    }
                }

                if (isHostRole) {
                    item(key = "room_qr_card") {
                        LobbyRoomQrCard(
                            hostAddress = sessionState.hostAddress,
                            port = sessionState.port,
                            roomCode = sessionState.roomCode,
                            roomSecret = sessionState.roomSecret,
                            connectionType = effectiveConnectionType,
                            hostName = sessionState.localPlayerName.ifBlank { playerName },
                            timeControlId = activeTimeControl.id,
                            onRefreshIp = { viewModel.refreshHostAddress() }
                        )
                    }
                }

                item(key = "players_list") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.lobby_players_section))
                        val hostColor = roomInfo?.hostColor ?: PieceColor.WHITE
                        val clientColor = roomInfo?.clientColor ?: PieceColor.BLACK

                        LobbyPlayerCard(
                            name = roomInfo?.hostName ?: playerName.ifBlank { stringResource(R.string.default_player_name) },
                            color = hostColor,
                            connected = true,
                            ready = roomInfo?.hostReady ?: true,
                            isYou = isHostRole,
                            testTag = "lobby_host_player_card"
                        )
                        LobbyPlayerCard(
                            name = roomInfo?.clientName ?: stringResource(R.string.lobby_player_waiting),
                            color = clientColor,
                            connected = roomInfo?.clientUuid != null,
                            ready = roomInfo?.clientReady ?: false,
                            isYou = !isHostRole,
                            testTag = "lobby_client_player_card"
                        )
                    }
                }

                item(key = "lobby_actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChessSecondaryButton(
                            text = if (sessionState.isLocalReady) {
                                stringResource(R.string.lobby_mark_not_ready)
                            } else {
                                stringResource(R.string.lobby_mark_ready)
                            },
                            leadingIcon = if (sessionState.isLocalReady) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.RadioButtonUnchecked
                            },
                            onClick = { viewModel.toggleLocalReady() },
                            testTag = "lobby_ready_toggle_button"
                        )

                        if (isHostRole) {
                            ChessPrimaryButton(
                                text = stringResource(R.string.lobby_start_match),
                                leadingIcon = Icons.Rounded.PlayArrow,
                                enabled = sessionState.canHostStartGame,
                                onClick = { viewModel.startHostedGame() },
                                testTag = "lobby_start_match_button"
                            )
                        } else {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("lobby_waiting_host_notice"),
                                shape = RoundedCornerShape(ChessTheme.radii.sm),
                                color = colors.surfaceCard,
                                border = BorderStroke(1.dp, colors.borderSubtle)
                            ) {
                                Text(
                                    text = stringResource(R.string.lobby_waiting_host_start),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp)
                                )
                            }
                        }

                        ChessSecondaryButton(
                            text = stringResource(R.string.lobby_cancel),
                            onClick = leaveAndNavigateBack,
                            testTag = "lobby_cancel_button"
                        )

                        if (isHostRole) {
                            TextButton(
                                onClick = {
                                    viewModel.leaveSession()
                                    onOpenLocalGame()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("lobby_preview_board_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.lobby_preview_board),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LobbyPlayerCard(
    name: String,
    color: PieceColor,
    connected: Boolean,
    ready: Boolean,
    isYou: Boolean,
    testTag: String
) {
    val colors = ChessTheme.colors
    ChessSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (color == PieceColor.WHITE) colors.squareLight else colors.surfaceElevated
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            if (color == PieceColor.WHITE) R.drawable.piece_wk else R.drawable.piece_bk
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column {
                    Text(
                        text = if (isYou) "$name (${stringResource(R.string.game_you_label)})" else name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (connected) colors.textPrimary else colors.textMuted
                    )
                    Text(
                        text = if (color == PieceColor.WHITE) {
                            stringResource(R.string.side_white)
                        } else {
                            stringResource(R.string.side_black)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            if (connected) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (ready) colors.accentGreenContainer else colors.backgroundSecondary,
                    border = BorderStroke(
                        1.dp,
                        if (ready) colors.accentGreen else colors.borderSubtle
                    )
                ) {
                    Text(
                        text = if (ready) {
                            stringResource(R.string.lobby_player_ready)
                        } else {
                            stringResource(R.string.lobby_player_not_ready)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ready) colors.accentGreen else colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LobbyInfoRow(
    label: String,
    value: String,
    isMono: Boolean = false,
    highlight: Boolean = false
) {
    val colors = ChessTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Text(
            text = value,
            style = if (isMono) {
                MaterialTheme.typography.titleSmall.copy(
                    fontFamily = MonoFontFamily,
                    fontWeight = if (highlight) FontWeight.Bold else FontWeight.SemiBold
                )
            } else {
                MaterialTheme.typography.titleSmall
            },
            color = if (highlight) colors.accentGreen else colors.textPrimary
        )
    }
}

@Composable
internal fun ModeToggleChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(64.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(ChessTheme.radii.sm),
        color = if (selected) colors.accentGreenContainer else colors.surfaceCard,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) colors.accentGreen else colors.borderSubtle
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colors.accentGreen else colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.accentGreen else colors.textPrimary,
                maxLines = 1
            )
        }
    }
}
