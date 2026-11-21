package com.example.core.network

import kotlinx.coroutines.flow.StateFlow

data class DiscoveredRoom(
    val roomId: String,
    val hostName: String,
    val hostAddress: String,
    val port: Int,
    val timeControlLabel: String,
    val connectedPlayers: Int,
    val connectionType: ConnectionType,
    val roomCode: String = "",
    val roomSecret: String = "",
    val timeControlId: String = "10+0",
    val serviceName: String = ""
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Discovering(val mode: ConnectionType) : ConnectionState
    data class Hosting(val roomId: String, val port: Int, val mode: ConnectionType) : ConnectionState
    data class Connecting(val hostAddress: String, val mode: ConnectionType) : ConnectionState
    data class Connected(val roomId: String, val peerName: String, val mode: ConnectionType) : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * Unified local network connection contract shared by Wi-Fi LAN (NSD), Hotspot, and Wi-Fi Direct.
 */
interface NetworkConnectionManager {
    val connectionState: StateFlow<ConnectionState>
    val discoveredRooms: StateFlow<List<DiscoveredRoom>>

    suspend fun startDiscovery(mode: ConnectionType)
    suspend fun stopDiscovery()
    suspend fun connectToRoom(room: DiscoveredRoom)
    suspend fun disconnect()
}
