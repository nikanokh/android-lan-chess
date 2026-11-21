package com.example.core.ui.navigation

import com.example.core.network.ConnectionType
import com.example.domain.model.PieceColor
import com.example.domain.model.SideSelection
import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation Compose route definitions.
 */
sealed interface AppDestination {
    @Serializable
    data object Home : AppDestination

    @Serializable
    data object CreateGame : AppDestination

    @Serializable
    data class JoinGame(
        val initialMode: ConnectionType = ConnectionType.WIFI_LAN
    ) : AppDestination

    @Serializable
    data class Lobby(
        val timeControlId: String = "10+0",
        val side: SideSelection = SideSelection.RANDOM,
        val playerName: String = "",
        val connectionType: ConnectionType = ConnectionType.WIFI_LAN,
        val isHost: Boolean = true
    ) : AppDestination

    @Serializable
    data class Game(
        val timeControlId: String = "10+0",
        val playerSide: PieceColor = PieceColor.WHITE,
        val playerName: String = "",
        val isLanMatch: Boolean = false
    ) : AppDestination

    @Serializable
    data object History : AppDestination

    @Serializable
    data object Analysis : AppDestination

    @Serializable
    data object Profile : AppDestination

    @Serializable
    data object Settings : AppDestination
}
