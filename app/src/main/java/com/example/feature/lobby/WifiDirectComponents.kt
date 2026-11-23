package com.example.feature.lobby

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.network.WifiDirectManager
import com.example.core.network.WifiDirectPeer
import com.example.core.network.WifiDirectState
import com.example.core.network.WifiDirectStatus
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily

@Composable
fun WifiDirectHostCard(
    wifiDirectState: WifiDirectState,
    onPermissionResult: (Boolean) -> Unit,
    onRestartGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onPermissionResult(results.values.all { it })
    }

    ChessSurfaceCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lobby_wifi_direct_host_card"),
        borderColor = colors.accentGreen.copy(alpha = 0.55f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Devices,
                    contentDescription = null,
                    tint = colors.accentGreen,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(R.string.wifi_direct_host_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
            }

            when {
                !wifiDirectState.isSupported -> {
                    Text(
                        text = stringResource(R.string.wifi_direct_unsupported_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accentCoral
                    )
                }

                !wifiDirectState.hasPermissions -> {
                    Text(
                        text = stringResource(R.string.wifi_direct_permission_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    Button(
                        onClick = {
                            permissionLauncher.launch(
                                WifiDirectManager.requiredRuntimePermissions(Build.VERSION.SDK_INT)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wifi_direct_host_permission_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.wifi_direct_grant_permission))
                    }
                }

                !wifiDirectState.isWifiP2pEnabled -> {
                    Text(
                        text = stringResource(R.string.wifi_direct_disabled_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accentAmber
                    )
                }

                else -> {
                    Text(
                        text = if (!wifiDirectState.groupOwnerAddress.isNullOrBlank()) {
                            stringResource(R.string.wifi_direct_group_ready, wifiDirectState.groupOwnerAddress)
                        } else {
                            stringResource(R.string.wifi_direct_host_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!wifiDirectState.groupOwnerAddress.isNullOrBlank()) {
                            colors.accentGreen
                        } else {
                            colors.textSecondary
                        }
                    )
                    if (!wifiDirectState.groupNetworkName.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.wifi_direct_group_ssid, wifiDirectState.groupNetworkName),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            color = colors.textPrimary
                        )
                    }
                    if (!wifiDirectState.lastError.isNullOrBlank()) {
                        Text(
                            text = wifiDirectState.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.accentCoral
                        )
                    }
                    OutlinedButton(
                        onClick = onRestartGroup,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wifi_direct_restart_group_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.wifi_direct_start_group))
                    }
                }
            }
        }
    }
}

@Composable
fun WifiDirectClientSection(
    wifiDirectState: WifiDirectState,
    isConnectingSocket: Boolean,
    onPermissionResult: (Boolean) -> Unit,
    onRefreshPeers: () -> Unit,
    onConnectPeer: (WifiDirectPeer) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onPermissionResult(results.values.all { it })
    }

    ChessSurfaceCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("join_wifi_direct_section"),
        borderColor = colors.accentGreen.copy(alpha = 0.55f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Devices,
                        contentDescription = null,
                        tint = colors.accentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.wifi_direct_peers_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                }
                if (wifiDirectState.status == WifiDirectStatus.DISCOVERING ||
                    wifiDirectState.status == WifiDirectStatus.CONNECTING_PEER
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.accentGreen
                    )
                }
            }

            when {
                !wifiDirectState.isSupported -> {
                    Text(
                        text = stringResource(R.string.wifi_direct_unsupported_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accentCoral
                    )
                }

                !wifiDirectState.hasPermissions -> {
                    Text(
                        text = stringResource(R.string.wifi_direct_permission_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    Button(
                        onClick = {
                            permissionLauncher.launch(
                                WifiDirectManager.requiredRuntimePermissions(Build.VERSION.SDK_INT)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wifi_direct_client_permission_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.wifi_direct_grant_permission))
                    }
                }

                !wifiDirectState.isWifiP2pEnabled -> {
                    Text(
                        text = stringResource(R.string.wifi_direct_disabled_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accentAmber
                    )
                }

                else -> {
                    if (wifiDirectState.groupFormed && !wifiDirectState.groupOwnerAddress.isNullOrBlank()) {
                        Text(
                            text = stringResource(
                                R.string.wifi_direct_connected_go,
                                wifiDirectState.groupOwnerAddress
                            ),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = colors.accentGreen
                        )
                    }

                    if (!wifiDirectState.lastError.isNullOrBlank()) {
                        Text(
                            text = wifiDirectState.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.accentCoral
                        )
                    }

                    if (wifiDirectState.peers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.wifi_direct_peers_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            wifiDirectState.peers.forEach { peer ->
                                WifiDirectPeerRow(
                                    peer = peer,
                                    busy = isConnectingSocket || wifiDirectState.status == WifiDirectStatus.CONNECTING_PEER,
                                    onConnect = { onConnectPeer(peer) }
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onRefreshPeers,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wifi_direct_scan_peers_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.wifi_direct_scan_peers))
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiDirectPeerRow(
    peer: WifiDirectPeer,
    busy: Boolean,
    onConnect: () -> Unit
) {
    val colors = ChessTheme.colors
    val record = peer.serviceRecord
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wifi_direct_peer_${peer.deviceAddress}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = record?.hostName ?: peer.deviceName,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (record != null) {
                    "${record.timeControlLabel} • ${record.roomCode}"
                } else {
                    peer.deviceAddress
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                color = if (record != null) colors.accentGreen else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onConnect,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = if (busy) {
                    stringResource(R.string.wifi_direct_connecting_peer)
                } else {
                    stringResource(R.string.wifi_direct_connect_peer)
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
