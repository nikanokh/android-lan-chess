package com.example.core.network

import com.example.core.chess.GameTerminationReason
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import com.example.domain.model.SideSelection
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.UUID

const val CHESS_LAN_PROTOCOL_VERSION: Int = 1

/**
 * Complete set of 19 mandatory ChessLAN protocol message types shared across
 * Wi-Fi LAN, Hotspot, and Wi-Fi Direct transports.
 */
@Serializable
enum class NetworkMessageType {
    ROOM_CREATED,
    ROOM_INFO,
    PLAYER_CONNECTED,
    GAME_START,
    MOVE_REQUEST,
    MOVE_ACCEPTED,
    MOVE_REJECTED,
    GAME_STATE,
    CLOCK_UPDATE,
    DRAW_OFFER,
    DRAW_ACCEPTED,
    DRAW_REJECTED,
    TAKEBACK_REQUEST,
    TAKEBACK_ACCEPTED,
    TAKEBACK_REJECTED,
    REMATCH_REQUEST,
    REMATCH_ACCEPTED,
    REMATCH_REJECTED,
    RESIGN,
    GAME_FINISHED,
    PING,
    PONG,
    RECONNECT_REQUEST,
    RECONNECT_RESPONSE,
    DISCONNECT
}

/**
 * Authoritative wire envelope for every WebSocket message in ChessLAN.
 */
@Serializable
data class NetworkEnvelope(
    val protocolVersion: Int = CHESS_LAN_PROTOCOL_VERSION,
    val gameUuid: String,
    val senderUuid: String,
    val messageUuid: String = UUID.randomUUID().toString(),
    val type: NetworkMessageType,
    val sequenceNumber: Long,
    val roomSecret: String = "",
    val payload: String = "{}"
)

@Serializable
data class RoomCreatedPayload(
    val roomId: String,
    val roomCode: String,
    val hostUuid: String,
    val hostName: String,
    val timeControlId: String,
    val hostSideSelection: SideSelection,
    val hostAssignedColor: PieceColor,
    val connectionType: ConnectionType
)

@Serializable
data class RoomInfoPayload(
    val roomId: String,
    val roomCode: String,
    val gameUuid: String,
    val hostUuid: String,
    val hostName: String,
    val hostColor: PieceColor,
    val hostReady: Boolean,
    val clientUuid: String? = null,
    val clientName: String? = null,
    val clientColor: PieceColor,
    val clientReady: Boolean = false,
    val timeControlId: String,
    val connectionType: ConnectionType,
    val gameStarted: Boolean = false
)

@Serializable
data class PlayerConnectedPayload(
    val playerUuid: String,
    val playerName: String,
    val ready: Boolean = false,
    val roomSecret: String = ""
)

@Serializable
data class GameStartPayload(
    val gameUuid: String,
    val whitePlayerUuid: String,
    val whitePlayerName: String,
    val blackPlayerUuid: String,
    val blackPlayerName: String,
    val timeControlId: String,
    val initialFen: String,
    val whiteRemainingMs: Long,
    val blackRemainingMs: Long,
    val startedAtEpochMs: Long
)

@Serializable
data class MoveRequestPayload(
    val from: String,
    val to: String,
    val promotion: String? = null,
    val expectedPly: Int,
    val expectedMoveNumber: Int
)

@Serializable
data class MoveAcceptedPayload(
    val plyIndex: Int,
    val moveNumber: Int,
    val color: PieceColor,
    val uci: String,
    val san: String,
    val fenAfter: String,
    val whiteRemainingMs: Long,
    val blackRemainingMs: Long,
    val activeClockColor: PieceColor?,
    val clockRunning: Boolean
)

@Serializable
data class MoveRejectedPayload(
    val reason: String,
    val requestedUci: String,
    val currentPly: Int,
    val currentFen: String
)

@Serializable
data class GameStatePayload(
    val gameUuid: String,
    val fen: String,
    val pgn: String,
    val uciMoves: List<String>,
    val plyCount: Int,
    val sideToMove: PieceColor,
    val whiteRemainingMs: Long,
    val blackRemainingMs: Long,
    val activeClockColor: PieceColor?,
    val clockRunning: Boolean,
    val drawOfferedBy: PieceColor? = null,
    val takebackRequestedBy: PieceColor? = null,
    val rematchRequestedBy: PieceColor? = null,
    val result: GameResult? = null,
    val terminationReason: GameTerminationReason? = null
)

@Serializable
data class ClockUpdatePayload(
    val whiteRemainingMs: Long,
    val blackRemainingMs: Long,
    val activeClockColor: PieceColor?,
    val clockRunning: Boolean,
    val serverTimestampMs: Long
)

@Serializable
data class DrawOfferPayload(
    val offeredBy: PieceColor
)

@Serializable
data class DrawDecisionPayload(
    val decidedBy: PieceColor,
    val accepted: Boolean
)

@Serializable
data class TakebackRequestPayload(
    val requestedBy: PieceColor,
    val targetPly: Int
)

@Serializable
data class TakebackDecisionPayload(
    val decidedBy: PieceColor,
    val accepted: Boolean
)

@Serializable
data class RematchRequestPayload(
    val requestedBy: PieceColor
)

@Serializable
data class RematchDecisionPayload(
    val decidedBy: PieceColor,
    val accepted: Boolean,
    val newGameUuid: String? = null,
    val swapColors: Boolean = true
)

@Serializable
data class ResignPayload(
    val resigningColor: PieceColor
)

@Serializable
data class GameFinishedPayload(
    val gameUuid: String,
    val result: GameResult,
    val reason: GameTerminationReason,
    val finalFen: String,
    val pgn: String,
    val whiteRemainingMs: Long,
    val blackRemainingMs: Long
)

@Serializable
data class PingPayload(
    val clientTimestampMs: Long
)

@Serializable
data class PongPayload(
    val echoedTimestampMs: Long,
    val serverTimestampMs: Long
)

@Serializable
data class ReconnectRequestPayload(
    val playerUuid: String,
    val playerName: String,
    val lastKnownPly: Int,
    val roomSecret: String
)

@Serializable
data class ReconnectResponsePayload(
    val accepted: Boolean,
    val reason: String? = null,
    val roomInfo: RoomInfoPayload? = null,
    val gameState: GameStatePayload? = null
)

@Serializable
data class DisconnectPayload(
    val playerUuid: String,
    val reason: String
)

object NetworkSecurityTokens {
    private val secureRandom = SecureRandom()

    fun generateRoomCode(): String {
        val number = 100_000 + secureRandom.nextInt(900_000)
        val raw = number.toString()
        return "${raw.substring(0, 3)}-${raw.substring(3, 6)}"
    }

    fun generateRoomSecret(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
