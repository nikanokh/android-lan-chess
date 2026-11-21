package com.example.feature.lobby

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.core.network.ConnectionType
import com.example.core.network.QrBitmapGenerator
import com.example.core.network.RoomQrCodec
import com.example.core.network.RoomQrPayload
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily

@Composable
fun HotspotHostGuideCard(
    onOpenHotspotSettings: () -> Unit,
    onRefreshIp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    ChessSurfaceCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lobby_hotspot_guide_card"),
        borderColor = colors.accentGreen.copy(alpha = 0.55f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WifiTethering,
                    contentDescription = null,
                    tint = colors.accentGreen,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(R.string.hotspot_host_guide_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
            }
            Text(
                text = stringResource(R.string.hotspot_host_guide_steps),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenHotspotSettings,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("lobby_open_hotspot_settings_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.hotspot_open_settings),
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = onRefreshIp,
                    modifier = Modifier.testTag("lobby_refresh_ip_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.hotspot_refresh_ip),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LobbyRoomQrCard(
    hostAddress: String?,
    port: Int,
    roomCode: String,
    roomSecret: String,
    connectionType: ConnectionType,
    hostName: String,
    timeControlId: String,
    onRefreshIp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    var showFullQrDialog by rememberSaveable { mutableStateOf(false) }

    val qrUri = remember(hostAddress, port, roomCode, roomSecret, connectionType, hostName, timeControlId) {
        if (!hostAddress.isNullOrBlank() && port > 0) {
            RoomQrCodec.encode(
                RoomQrPayload(
                    hostAddress = hostAddress,
                    port = port,
                    roomCode = roomCode,
                    roomSecret = roomSecret,
                    connectionType = connectionType,
                    hostName = hostName,
                    timeControlId = timeControlId
                )
            )
        } else {
            null
        }
    }

    val qrBitmap = remember(qrUri) {
        qrUri?.let { uri ->
            runCatching { QrBitmapGenerator.generateImageBitmap(uri, sizePx = 520) }.getOrNull()
        }
    }

    if (showFullQrDialog && qrBitmap != null) {
        Dialog(onDismissRequest = { showFullQrDialog = false }) {
            Surface(
                shape = RoundedCornerShape(ChessTheme.radii.lg),
                color = colors.surfaceElevated,
                border = BorderStroke(1.dp, colors.borderSubtle),
                modifier = Modifier.testTag("lobby_qr_fullscreen_dialog")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.lobby_qr_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        IconButton(onClick = { showFullQrDialog = false }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.game_action_close),
                                tint = colors.textSecondary
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .padding(12.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = stringResource(R.string.lobby_qr_title),
                            modifier = Modifier.size(250.dp)
                        )
                    }
                    if (!hostAddress.isNullOrBlank()) {
                        Text(
                            text = "$hostAddress:$port • $roomCode",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = colors.accentGreen
                        )
                    }
                    Text(
                        text = stringResource(R.string.lobby_qr_scan_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    ChessSurfaceCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lobby_room_qr_card")
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = null,
                        tint = colors.accentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.lobby_qr_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                }
                Text(
                    text = if (qrBitmap != null) {
                        stringResource(R.string.lobby_qr_tap_to_enlarge)
                    } else {
                        stringResource(R.string.lobby_qr_no_ip)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                if (qrBitmap == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onRefreshIp,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(stringResource(R.string.hotspot_refresh_ip))
                    }
                }
            }

            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .clickable { showFullQrDialog = true }
                        .padding(6.dp)
                        .testTag("lobby_qr_thumbnail")
                ) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = stringResource(R.string.lobby_qr_title),
                        modifier = Modifier.size(92.dp)
                    )
                }
            }
        }
    }
}
