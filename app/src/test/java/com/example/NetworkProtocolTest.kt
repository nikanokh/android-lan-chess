package com.example

import com.example.core.chess.GameTerminationReason
import com.example.core.network.CHESS_LAN_PROTOCOL_VERSION
import com.example.core.network.ClockUpdatePayload
import com.example.core.network.ConnectionType
import com.example.core.network.DisconnectPayload
import com.example.core.network.DrawDecisionPayload
import com.example.core.network.DrawOfferPayload
import com.example.core.network.GameFinishedPayload
import com.example.core.network.GameStartPayload
import com.example.core.network.GameStatePayload
import com.example.core.network.MoveAcceptedPayload
import com.example.core.network.MoveRejectedPayload
import com.example.core.network.MoveRequestPayload
import com.example.core.network.NetworkEnvelope
import com.example.core.network.NetworkMessageSerializer
import com.example.core.network.NetworkMessageType
import com.example.core.network.NetworkSecurityTokens
import com.example.core.network.PingPayload
import com.example.core.network.PlayerConnectedPayload
import com.example.core.network.PongPayload
import com.example.core.network.ReconnectRequestPayload
import com.example.core.network.ReconnectResponsePayload
import com.example.core.network.RematchDecisionPayload
import com.example.core.network.RematchRequestPayload
import com.example.core.network.ResignPayload
import com.example.core.network.RoomCreatedPayload
import com.example.core.network.RoomInfoPayload
import com.example.core.network.TakebackDecisionPayload
import com.example.core.network.TakebackRequestPayload
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import com.example.domain.model.SideSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProtocolTest {

    @Test
    fun allProtocolMessageTypesAreDefined() {
        assertEquals(25, NetworkMessageType.entries.size)
        val expectedNames = setOf(
            "ROOM_CREATED",
            "ROOM_INFO",
            "PLAYER_CONNECTED",
            "GAME_START",
            "MOVE_REQUEST",
            "MOVE_ACCEPTED",
            "MOVE_REJECTED",
            "GAME_STATE",
            "CLOCK_UPDATE",
            "DRAW_OFFER",
            "DRAW_ACCEPTED",
            "DRAW_REJECTED",
            "TAKEBACK_REQUEST",
            "TAKEBACK_ACCEPTED",
            "TAKEBACK_REJECTED",
            "REMATCH_REQUEST",
            "REMATCH_ACCEPTED",
            "REMATCH_REJECTED",
            "RESIGN",
            "GAME_FINISHED",
            "PING",
            "PONG",
            "RECONNECT_REQUEST",
            "RECONNECT_RESPONSE",
            "DISCONNECT"
        )
        assertEquals(expectedNames, NetworkMessageType.entries.map { it.name }.toSet())
    }

    @Test
    fun envelopeRoundTripPreservesAllProtocolHeadersAndPayloads() {
        val roomInfo = RoomInfoPayload(
            roomId = "room-1",
            roomCode = "482-915",
            gameUuid = "game-1",
            hostUuid = "host-1",
            hostName = "Алексей",
            hostColor = PieceColor.WHITE,
            hostReady = true,
            clientUuid = "client-1",
            clientName = "Мария",
            clientColor = PieceColor.BLACK,
            clientReady = true,
            timeControlId = "5+3",
            connectionType = ConnectionType.WIFI_LAN,
            gameStarted = false
        )

        val cases: List<Pair<NetworkEnvelope, Any>> = listOf(
            createEnv(
                NetworkMessageType.ROOM_CREATED,
                RoomCreatedPayload("room-1", "482-915", "host-1", "Алексей", "5+3", SideSelection.WHITE, PieceColor.WHITE, ConnectionType.WIFI_LAN)
            ),
            createEnv(NetworkMessageType.ROOM_INFO, roomInfo),
            createEnv(NetworkMessageType.PLAYER_CONNECTED, PlayerConnectedPayload("client-1", "Мария", true, "secret-hex")),
            createEnv(
                NetworkMessageType.GAME_START,
                GameStartPayload("game-1", "host-1", "Алексей", "client-1", "Мария", "5+3", "start-fen", 300_000L, 300_000L, 12345L)
            ),
            createEnv(NetworkMessageType.MOVE_REQUEST, MoveRequestPayload("e2", "e4", null, 0, 1)),
            createEnv(
                NetworkMessageType.MOVE_ACCEPTED,
                MoveAcceptedPayload(1, 1, PieceColor.WHITE, "e2e4", "e4", "fen-after", 299_500L, 300_000L, PieceColor.BLACK, true)
            ),
            createEnv(NetworkMessageType.MOVE_REJECTED, MoveRejectedPayload("Нелегальный ход", "e2e5", 0, "start-fen")),
            createEnv(
                NetworkMessageType.GAME_STATE,
                GameStatePayload("game-1", "fen-after", "1. e4", listOf("e2e4"), 1, PieceColor.BLACK, 299_500L, 300_000L, PieceColor.BLACK, true)
            ),
            createEnv(NetworkMessageType.CLOCK_UPDATE, ClockUpdatePayload(295_000L, 298_000L, PieceColor.BLACK, true, 50_000L)),
            createEnv(NetworkMessageType.DRAW_OFFER, DrawOfferPayload(PieceColor.WHITE)),
            createEnv(NetworkMessageType.DRAW_ACCEPTED, DrawDecisionPayload(PieceColor.BLACK, true)),
            createEnv(NetworkMessageType.DRAW_REJECTED, DrawDecisionPayload(PieceColor.BLACK, false)),
            createEnv(NetworkMessageType.TAKEBACK_REQUEST, TakebackRequestPayload(PieceColor.WHITE, 0)),
            createEnv(NetworkMessageType.TAKEBACK_ACCEPTED, TakebackDecisionPayload(PieceColor.BLACK, true)),
            createEnv(NetworkMessageType.TAKEBACK_REJECTED, TakebackDecisionPayload(PieceColor.BLACK, false)),
            createEnv(NetworkMessageType.REMATCH_REQUEST, RematchRequestPayload(PieceColor.WHITE)),
            createEnv(NetworkMessageType.REMATCH_ACCEPTED, RematchDecisionPayload(PieceColor.BLACK, true, "game-2", true)),
            createEnv(NetworkMessageType.REMATCH_REJECTED, RematchDecisionPayload(PieceColor.BLACK, false)),
            createEnv(NetworkMessageType.RESIGN, ResignPayload(PieceColor.WHITE)),
            createEnv(
                NetworkMessageType.GAME_FINISHED,
                GameFinishedPayload("game-1", GameResult.BLACK_WIN, GameTerminationReason.RESIGNATION, "final-fen", "1. e4 0-1", 200_000L, 250_000L)
            ),
            createEnv(NetworkMessageType.PING, PingPayload(1000L)),
            createEnv(NetworkMessageType.PONG, PongPayload(1000L, 1005L)),
            createEnv(NetworkMessageType.RECONNECT_REQUEST, ReconnectRequestPayload("client-1", "Мария", 4, "secret-hex")),
            createEnv(
                NetworkMessageType.RECONNECT_RESPONSE,
                ReconnectResponsePayload(true, null, roomInfo, null)
            ),
            createEnv(NetworkMessageType.DISCONNECT, DisconnectPayload("client-1", "Пользователь вышел"))
        )

        assertEquals(25, cases.size)
        cases.forEach { (envelope, _) ->
            val encoded = NetworkMessageSerializer.encodeEnvelope(envelope)
            val decoded = NetworkMessageSerializer.decodeEnvelope(encoded).getOrThrow()
            assertEquals(CHESS_LAN_PROTOCOL_VERSION, decoded.protocolVersion)
            assertEquals(envelope.gameUuid, decoded.gameUuid)
            assertEquals(envelope.senderUuid, decoded.senderUuid)
            assertEquals(envelope.messageUuid, decoded.messageUuid)
            assertEquals(envelope.type, decoded.type)
            assertEquals(envelope.sequenceNumber, decoded.sequenceNumber)
            assertEquals(envelope.roomSecret, decoded.roomSecret)
            assertTrue(NetworkMessageSerializer.isProtocolCompatible(decoded))
        }

        // Verify typed payload decoding for a complex message
        val gameStateWire = NetworkMessageSerializer.encodeEnvelope(cases[7].first)
        val decodedEnv = NetworkMessageSerializer.decodeEnvelope(gameStateWire).getOrThrow()
        val decodedPayload = NetworkMessageSerializer.decodeEnvelopePayload<GameStatePayload>(decodedEnv).getOrThrow()
        assertEquals(listOf("e2e4"), decodedPayload.uciMoves)
        assertEquals(PieceColor.BLACK, decodedPayload.sideToMove)
    }

    @Test
    fun incompatibleProtocolVersionIsRejected() {
        val futureEnvelope = NetworkEnvelope(
            protocolVersion = CHESS_LAN_PROTOCOL_VERSION + 99,
            gameUuid = "game-1",
            senderUuid = "sender-1",
            type = NetworkMessageType.PING,
            sequenceNumber = 1L
        )
        assertFalse(NetworkMessageSerializer.isProtocolCompatible(futureEnvelope))
    }

    @Test
    fun securityTokensGenerateFormattedRoomCodeAndUniqueSecrets() {
        val code = NetworkSecurityTokens.generateRoomCode()
        assertTrue("Expected XXX-XXX format, got $code", code.matches(Regex("\\d{3}-\\d{3}")))

        val secretA = NetworkSecurityTokens.generateRoomSecret()
        val secretB = NetworkSecurityTokens.generateRoomSecret()
        assertEquals(32, secretA.length)
        assertNotEquals(secretA, secretB)
    }

    private inline fun <reified T : Any> createEnv(
        type: NetworkMessageType,
        payload: T
    ): Pair<NetworkEnvelope, T> = NetworkMessageSerializer.createEnvelope(
        gameUuid = "game-1",
        senderUuid = "host-1",
        type = type,
        sequenceNumber = 42L,
        roomSecret = "secret-hex",
        payload = payload
    ) to payload
}
