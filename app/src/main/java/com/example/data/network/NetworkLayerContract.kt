package com.example.data.network

/**
 * Package boundary reserved for Stages 4–5 networking implementations:
 * - LanDiscoveryManager (Android NSD)
 * - WifiDirectManager (Android WifiP2pManager)
 * - LocalGameServer (Ktor Server + WebSocket)
 * - WebSocketClient (Ktor Client + WebSocket)
 * - NetworkMessageSerializer (kotlinx.serialization protocol)
 */
object NetworkLayerContract {
    const val PROTOCOL_VERSION: Int = 1
    const val DEFAULT_PORT: Int = 8988
    const val NSD_SERVICE_TYPE: String = "_chesslan._tcp."
}
