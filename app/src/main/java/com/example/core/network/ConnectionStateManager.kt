package com.example.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized reactive connection and room discovery state holder shared by
 * Lobby, JoinGame, and Game screens across all local transports.
 */
@Singleton
class ConnectionStateManager @Inject constructor() {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    val discoveredRooms: StateFlow<List<DiscoveredRoom>> = _discoveredRooms.asStateFlow()

    private val _localIpAddress = MutableStateFlow<String?>(null)
    val localIpAddress: StateFlow<String?> = _localIpAddress.asStateFlow()

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun setLocalIpAddress(address: String?) {
        _localIpAddress.value = address
    }

    fun upsertDiscoveredRoom(room: DiscoveredRoom) {
        _discoveredRooms.update { current ->
            val filtered = current.filterNot {
                (room.roomId.isNotBlank() && it.roomId == room.roomId) ||
                    (room.serviceName.isNotBlank() && it.serviceName == room.serviceName) ||
                    (it.hostAddress == room.hostAddress && it.port == room.port)
            }
            (filtered + room).sortedBy { it.hostName.lowercase() }
        }
    }

    fun removeDiscoveredRoomByServiceName(serviceName: String) {
        _discoveredRooms.update { current ->
            current.filterNot { it.serviceName == serviceName }
        }
    }

    fun clearDiscoveredRooms() {
        _discoveredRooms.value = emptyList()
    }

    fun reset() {
        _connectionState.value = ConnectionState.Idle
        _discoveredRooms.value = emptyList()
    }
}
