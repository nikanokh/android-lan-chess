package com.example.feature.lobby

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.network.ConnectionState
import com.example.core.network.ConnectionType
import com.example.core.network.DiscoveredRoom
import com.example.core.ui.components.ChessPrimaryButton
import com.example.core.ui.components.ChessSecondaryButton
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.components.ChessTopBar
import com.example.core.ui.components.SectionHeader
import com.example.core.ui.theme.ChessDimensions
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily

@Composable
fun JoinGameScreen(
    onNavigateBack: () -> Unit,
    onJoinedLobby: () -> Unit,
    onOpenLocalGame: () -> Unit,
    viewModel: LobbyViewModel = hiltViewModel()
) {
    val discoveredRooms by viewModel.discoveredRooms.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val defaultPlayerName by viewModel.defaultPlayerName.collectAsStateWithLifecycle()

    var playerName by rememberSaveable { mutableStateOf("") }
    var hasSyncedDefaultName by rememberSaveable { mutableStateOf(false) }
    var showManualIpDialog by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(defaultPlayerName) {
        if (defaultPlayerName != null && !hasSyncedDefaultName) {
            playerName = defaultPlayerName.orEmpty()
            hasSyncedDefaultName = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.clearError()
        viewModel.startDiscovery(ConnectionType.WIFI_LAN)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopDiscovery()
        }
    }

    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing
    val isDiscovering = connectionState is ConnectionState.Discovering
    val isConnecting = sessionState.isConnecting || connectionState is ConnectionState.Connecting
    val resolvedPlayerName = playerName.trim().ifEmpty { defaultPlayerName ?: "Игрок" }

    if (showQrScanner) {
        QrScannerDialog(
            onQrDecoded = { rawQr ->
                showQrScanner = false
                viewModel.connectFromQr(
                    rawQrContent = rawQr,
                    playerName = resolvedPlayerName,
                    onConnected = onJoinedLobby
                )
            },
            onDismiss = { showQrScanner = false }
        )
    }

    if (showManualIpDialog) {
        ManualIpConnectDialog(
            initialIp = "",
            isConnecting = isConnecting,
            onDismiss = { showManualIpDialog = false },
            onConnect = { ip, port, roomCode ->
                viewModel.connectByIp(
                    host = ip,
                    portText = port,
                    roomCodeOrSecret = roomCode,
                    playerName = resolvedPlayerName,
                    mode = ConnectionType.WIFI_LAN,
                    onConnected = {
                        showManualIpDialog = false
                        onJoinedLobby()
                    }
                )
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChessTopBar(
                title = stringResource(R.string.join_game_title),
                onNavigateBack = onNavigateBack
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
                item(key = "player_name") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.create_game_player_name))
                        OutlinedTextField(
                            value = playerName,
                            onValueChange = {
                                hasSyncedDefaultName = true
                                playerName = it
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("join_player_name_input"),
                            shape = RoundedCornerShape(ChessTheme.radii.sm),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentGreen,
                                unfocusedBorderColor = colors.borderSubtle,
                                focusedContainerColor = colors.surfaceCard,
                                unfocusedContainerColor = colors.surfaceCard,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            )
                        )
                    }
                }

                val errorText = sessionState.lastError ?: (connectionState as? ConnectionState.Error)?.message
                if (!errorText.isNullOrBlank()) {
                    item(key = "error_banner") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("join_error_banner"),
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
                                    text = errorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                item(key = "rooms_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.join_game_rooms_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            if (isDiscovering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accentGreen
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.startDiscovery(ConnectionType.WIFI_LAN) },
                            modifier = Modifier.testTag("join_refresh_discovery_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.join_game_refresh),
                                tint = colors.accentGreen
                            )
                        }
                    }
                }

                if (discoveredRooms.isEmpty()) {
                    item(key = "empty_rooms") {
                        ChessSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Router,
                                    contentDescription = null,
                                    tint = colors.accentGreen,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = stringResource(R.string.join_game_searching_lan),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = stringResource(R.string.join_game_no_rooms_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(discoveredRooms, key = { "${it.roomId}_${it.hostAddress}_${it.port}" }) { room ->
                        DiscoveredRoomCard(
                            room = room,
                            isConnecting = isConnecting,
                            onConnect = {
                                viewModel.connectToRoom(
                                    room = room,
                                    playerName = resolvedPlayerName,
                                    onConnected = onJoinedLobby
                                )
                            }
                        )
                    }
                }

                item(key = "qr_and_manual_actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChessPrimaryButton(
                            text = stringResource(R.string.join_game_scan_qr),
                            leadingIcon = Icons.Rounded.QrCodeScanner,
                            enabled = !isConnecting,
                            testTag = "join_scan_qr_button",
                            onClick = { showQrScanner = true }
                        )
                        ChessSecondaryButton(
                            text = stringResource(R.string.join_game_manual_ip),
                            leadingIcon = Icons.Rounded.Lan,
                            enabled = !isConnecting,
                            testTag = "join_manual_ip_button",
                            onClick = { showManualIpDialog = true }
                        )
                    }
                }

                item(key = "local_fallback_action") {
                    TextButton(
                        onClick = onOpenLocalGame,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("join_open_game_preview_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.join_game_open_demo_game),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredRoomCard(
    room: DiscoveredRoom,
    isConnecting: Boolean,
    onConnect: () -> Unit
) {
    val colors = ChessTheme.colors
    ChessSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("discovered_room_card_${room.roomId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = room.hostName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = room.timeControlLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = colors.accentGreen
                    )
                    Text(
                        text = stringResource(R.string.join_game_players_count, room.connectedPlayers),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
                Text(
                    text = buildString {
                        append("${room.hostAddress}:${room.port}")
                        if (room.roomCode.isNotBlank()) {
                            append(" • ${room.roomCode}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                onClick = onConnect,
                enabled = !isConnecting && room.connectedPlayers < 2,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("connect_room_button_${room.roomId}")
            ) {
                Text(
                    text = if (isConnecting) {
                        stringResource(R.string.join_game_connecting)
                    } else {
                        stringResource(R.string.join_game_connect)
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ManualIpConnectDialog(
    initialIp: String = "",
    isConnecting: Boolean,
    onDismiss: () -> Unit,
    onConnect: (ip: String, port: String, roomCode: String) -> Unit
) {
    val colors = ChessTheme.colors
    var ipAddress by rememberSaveable(initialIp) { mutableStateOf(initialIp) }
    var port by rememberSaveable { mutableStateOf("") }
    var roomCode by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        modifier = Modifier.testTag("manual_ip_dialog"),
        containerColor = colors.surfaceElevated,
        title = {
            Text(
                text = stringResource(R.string.join_game_manual_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.join_game_manual_ip_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text(stringResource(R.string.join_game_ip_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_ip_input")
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.join_game_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_port_input")
                )
                OutlinedTextField(
                    value = roomCode,
                    onValueChange = { roomCode = it },
                    label = { Text(stringResource(R.string.join_game_room_code_optional)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_room_code_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(ipAddress.trim(), port.trim(), roomCode.trim()) },
                enabled = !isConnecting && ipAddress.isNotBlank() && port.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                modifier = Modifier.testTag("manual_ip_confirm_button")
            ) {
                Text(
                    text = if (isConnecting) {
                        stringResource(R.string.join_game_connecting)
                    } else {
                        stringResource(R.string.join_game_connect)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isConnecting
            ) {
                Text(stringResource(R.string.game_action_cancel))
            }
        }
    )
}
