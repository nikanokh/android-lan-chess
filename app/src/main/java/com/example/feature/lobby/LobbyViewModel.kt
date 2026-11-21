package com.example.feature.lobby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.network.ConnectionState
import com.example.core.network.ConnectionType
import com.example.core.network.DiscoveredRoom
import com.example.core.network.GameSessionManager
import com.example.core.network.GameSessionState
import com.example.domain.model.SideSelection
import com.example.domain.model.TimeControl
import com.example.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared ViewModel for JoinGameScreen and LobbyScreen backed by the singleton [GameSessionManager].
 */
@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val gameSessionManager: GameSessionManager,
    observeSettingsUseCase: ObserveSettingsUseCase
) : ViewModel() {

    val sessionState: StateFlow<GameSessionState> = gameSessionManager.sessionState
    val connectionState: StateFlow<ConnectionState> = gameSessionManager.connectionState
    val discoveredRooms: StateFlow<List<DiscoveredRoom>> = gameSessionManager.discoveredRooms
    val localIpAddress: StateFlow<String?> = gameSessionManager.localIpAddress

    val defaultPlayerName: StateFlow<String> = observeSettingsUseCase()
        .map { it.playerName.ifBlank { "Игрок" } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = "Игрок"
        )

    fun ensureHostRoomCreated(
        timeControlId: String,
        sideSelection: SideSelection,
        hostName: String,
        connectionType: ConnectionType
    ) {
        val current = sessionState.value
        if (current.isActiveLanSession) return
        viewModelScope.launch {
            gameSessionManager.createHostRoom(
                timeControl = TimeControl.fromId(timeControlId),
                sideSelection = sideSelection,
                hostName = hostName,
                connectionType = connectionType
            )
        }
    }

    fun startDiscovery(mode: ConnectionType) {
        viewModelScope.launch {
            gameSessionManager.startDiscovery(mode)
        }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            gameSessionManager.stopDiscovery()
        }
    }

    fun connectToRoom(room: DiscoveredRoom, playerName: String, onConnected: () -> Unit) {
        viewModelScope.launch {
            val result = gameSessionManager.connectToRoom(
                room = room,
                playerName = playerName.ifBlank { defaultPlayerName.value }
            )
            if (result.isSuccess) {
                onConnected()
            }
        }
    }

    fun connectByIp(
        host: String,
        portText: String,
        roomCodeOrSecret: String,
        playerName: String,
        mode: ConnectionType,
        onConnected: () -> Unit
    ) {
        val port = portText.trim().toIntOrNull() ?: -1
        viewModelScope.launch {
            val result = gameSessionManager.connectByIp(
                host = host,
                port = port,
                roomSecretOrCode = roomCodeOrSecret,
                playerName = playerName.ifBlank { defaultPlayerName.value },
                connectionType = mode
            )
            if (result.isSuccess) {
                onConnected()
            }
        }
    }

    fun connectFromQr(
        rawQrContent: String,
        playerName: String,
        onConnected: () -> Unit
    ) {
        viewModelScope.launch {
            val result = gameSessionManager.connectFromQr(
                rawQrContent = rawQrContent,
                playerName = playerName.ifBlank { defaultPlayerName.value }
            )
            if (result.isSuccess) {
                onConnected()
            }
        }
    }

    fun refreshHostAddress() {
        gameSessionManager.refreshLocalNetworkAddress()
    }

    fun toggleLocalReady() {
        val nextReady = !sessionState.value.isLocalReady
        viewModelScope.launch {
            gameSessionManager.setLocalReady(nextReady)
        }
    }

    fun startHostedGame() {
        viewModelScope.launch {
            gameSessionManager.startHostedGame()
        }
    }

    fun leaveSession() {
        viewModelScope.launch {
            gameSessionManager.disconnect()
        }
    }

    fun clearError() {
        gameSessionManager.clearError()
    }
}
