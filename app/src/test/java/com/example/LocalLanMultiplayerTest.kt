package com.example

import com.example.core.chess.GameTerminationReason
import com.example.core.chess.MonotonicTimeProvider
import com.example.core.network.ConnectionType
import com.example.core.network.DrawDecisionPayload
import com.example.core.network.DrawOfferPayload
import com.example.core.network.GameFinishedPayload
import com.example.core.network.GameStartPayload
import com.example.core.network.GameStatePayload
import com.example.core.network.HostRoomConfig
import com.example.core.network.LocalGameServer
import com.example.core.network.MoveAcceptedPayload
import com.example.core.network.MoveRejectedPayload
import com.example.core.network.MoveRequestPayload
import com.example.core.network.NetworkEnvelope
import com.example.core.network.NetworkMessageSerializer
import com.example.core.network.NetworkMessageType
import com.example.core.network.PingPayload
import com.example.core.network.PlayerConnectedPayload
import com.example.core.network.PongPayload
import com.example.core.network.ReconnectRequestPayload
import com.example.core.network.ReconnectResponsePayload
import com.example.core.network.ResignPayload
import com.example.core.network.RoomInfoPayload
import com.example.core.network.WebSocketClient
import com.example.core.ui.board.BoardSquare
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import com.example.domain.model.SideSelection
import com.example.domain.model.TimeControl
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LocalLanMultiplayerTest {

    private class FakeTimeProvider(var nowMs: Long = 10_000L) : MonotonicTimeProvider {
        override fun elapsedRealtimeMs(): Long = nowMs
    }

    private suspend fun awaitType(
        channel: Channel<NetworkEnvelope>,
        expected: NetworkMessageType,
        predicate: (NetworkEnvelope) -> Boolean = { true }
    ): NetworkEnvelope = withTimeout(5_000L) {
        while (true) {
            val env = channel.receive()
            if (env.type == expected && predicate(env)) {
                return@withTimeout env
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    @Test
    fun fullLanMatchLifecycleOverRealKtorWebSockets() = runBlocking {
        val clock = FakeTimeProvider()
        val config = HostRoomConfig(
            hostName = "Хост Белый",
            timeControl = TimeControl.BLITZ_5_3,
            sideSelection = SideSelection.WHITE,
            hostAssignedColor = PieceColor.WHITE,
            connectionType = ConnectionType.WIFI_LAN,
            requestedPort = 0
        )
        val server = LocalGameServer(config = config, timeProvider = clock)
        val client = WebSocketClient()
        val clientUuid = UUID.randomUUID().toString()
        val inbox = Channel<NetworkEnvelope>(Channel.UNLIMITED)
        val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.incomingMessages.collect { inbox.send(it) }
        }

        try {
            val port = server.start()
            assertTrue("Server must bind ephemeral port > 0", port > 0)

            // 1. Verify /health HTTP room probe
            val probed = client.probeRoomHealth("127.0.0.1", port).getOrThrow()
            assertEquals(config.roomId, probed.roomId)
            assertEquals(config.roomCode, probed.roomCode)
            assertEquals("Хост Белый", probed.hostName)
            assertEquals(PieceColor.BLACK, probed.clientColor)

            // 2. Connect real WebSocket client & send PLAYER_CONNECTED (ready = false)
            assertTrue(client.connect("127.0.0.1", port).isSuccess)
            assertTrue(
                client.send(
                    NetworkMessageSerializer.createEnvelope(
                        gameUuid = config.gameUuid,
                        senderUuid = clientUuid,
                        type = NetworkMessageType.PLAYER_CONNECTED,
                        sequenceNumber = 1L,
                        roomSecret = config.roomSecret,
                        payload = PlayerConnectedPayload(
                            playerUuid = clientUuid,
                            playerName = "Гость Чёрный",
                            ready = false,
                            roomSecret = config.roomSecret
                        )
                    )
                )
            )
            val infoNotReady = NetworkMessageSerializer.decodeEnvelopePayload<RoomInfoPayload>(
                awaitType(inbox, NetworkMessageType.ROOM_INFO)
            ).getOrThrow()
            assertEquals("Гость Чёрный", infoNotReady.clientName)
            assertFalse(infoNotReady.clientReady)

            // Host cannot start game until client is ready
            assertFalse(server.startGame())

            // 3. Client marks ready -> Host starts match -> GAME_START + GAME_STATE broadcast
            client.send(
                NetworkMessageSerializer.createEnvelope(
                    gameUuid = config.gameUuid,
                    senderUuid = clientUuid,
                    type = NetworkMessageType.PLAYER_CONNECTED,
                    sequenceNumber = 2L,
                    roomSecret = config.roomSecret,
                    payload = PlayerConnectedPayload(
                        playerUuid = clientUuid,
                        playerName = "Гость Чёрный",
                        ready = true,
                        roomSecret = config.roomSecret
                    )
                )
            )
            val infoReady = NetworkMessageSerializer.decodeEnvelopePayload<RoomInfoPayload>(
                awaitType(inbox, NetworkMessageType.ROOM_INFO)
            ).getOrThrow()
            assertTrue(infoReady.clientReady)
            assertTrue(server.canStartGame())

            assertTrue(server.startGame())
            val gameStart = NetworkMessageSerializer.decodeEnvelopePayload<GameStartPayload>(
                awaitType(inbox, NetworkMessageType.GAME_START)
            ).getOrThrow()
            assertEquals("Хост Белый", gameStart.whitePlayerName)
            assertEquals("Гость Чёрный", gameStart.blackPlayerName)
            // Consume initial GAME_STATE
            awaitType(inbox, NetworkMessageType.GAME_STATE)

            // 4. Client trying to move out-of-turn (Black before White) must receive MOVE_REJECTED
            client.send(
                NetworkMessageSerializer.createEnvelope(
                    gameUuid = config.gameUuid,
                    senderUuid = clientUuid,
                    type = NetworkMessageType.MOVE_REQUEST,
                    sequenceNumber = 3L,
                    roomSecret = config.roomSecret,
                    payload = MoveRequestPayload("e7", "e5", null, expectedPly = 0, expectedMoveNumber = 1)
                )
            )
            val outOfTurnRejected = NetworkMessageSerializer.decodeEnvelopePayload<MoveRejectedPayload>(
                awaitType(inbox, NetworkMessageType.MOVE_REJECTED)
            ).getOrThrow()
            assertEquals(0, outOfTurnRejected.currentPly)

            // 5. Host plays 1. e4 -> Client receives MOVE_ACCEPTED + GAME_STATE
            assertTrue(server.submitHostMove(BoardSquare.fromAlgebraic("e2")!!, BoardSquare.fromAlgebraic("e4")!!, null))
            val whiteAccepted = NetworkMessageSerializer.decodeEnvelopePayload<MoveAcceptedPayload>(
                awaitType(inbox, NetworkMessageType.MOVE_ACCEPTED)
            ).getOrThrow()
            assertEquals("e2e4", whiteAccepted.uci)
            assertEquals("e4", whiteAccepted.san)
            assertEquals(PieceColor.BLACK, whiteAccepted.activeClockColor)
            assertTrue(whiteAccepted.clockRunning)
            awaitType(inbox, NetworkMessageType.GAME_STATE)

            // 6. Client sends illegal move (e7 -> e4) -> rejected
            client.send(
                NetworkMessageSerializer.createEnvelope(
                    gameUuid = config.gameUuid,
                    senderUuid = clientUuid,
                    type = NetworkMessageType.MOVE_REQUEST,
                    sequenceNumber = 4L,
                    roomSecret = config.roomSecret,
                    payload = MoveRequestPayload("e7", "e4", null, expectedPly = 1, expectedMoveNumber = 1)
                )
            )
            val illegalRejected = NetworkMessageSerializer.decodeEnvelopePayload<MoveRejectedPayload>(
                awaitType(inbox, NetworkMessageType.MOVE_REJECTED)
            ).getOrThrow()
            assertEquals(1, illegalRejected.currentPly)

            // 7. Client sends legal Black move 1... e5 -> accepted and synced (with dedup check)
            clock.nowMs += 2_000L
            val moveUuid = UUID.randomUUID().toString()
            val validBlackEnvelope = NetworkMessageSerializer.createEnvelope(
                gameUuid = config.gameUuid,
                senderUuid = clientUuid,
                type = NetworkMessageType.MOVE_REQUEST,
                sequenceNumber = 5L,
                roomSecret = config.roomSecret,
                messageUuid = moveUuid,
                payload = MoveRequestPayload("e7", "e5", null, expectedPly = 1, expectedMoveNumber = 1)
            )
            client.send(validBlackEnvelope)
            // Re-send exact same messageUuid to verify server deduplication ignores it safely
            client.send(validBlackEnvelope)

            val blackAccepted = NetworkMessageSerializer.decodeEnvelopePayload<MoveAcceptedPayload>(
                awaitType(inbox, NetworkMessageType.MOVE_ACCEPTED)
            ).getOrThrow()
            assertEquals("e7e5", blackAccepted.uci)
            assertEquals("e5", blackAccepted.san)

            val syncedState = NetworkMessageSerializer.decodeEnvelopePayload<GameStatePayload>(
                awaitType(inbox, NetworkMessageType.GAME_STATE)
            ).getOrThrow()
            assertEquals(listOf("e2e4", "e7e5"), syncedState.uciMoves)
            assertEquals(PieceColor.WHITE, syncedState.sideToMove)
            assertEquals(PieceColor.WHITE, syncedState.activeClockColor)
            // 5+3 blitz: 300_000 - 2_000 + 3_000 increment = 301_000 ms for Black
            assertEquals(301_000L, syncedState.blackRemainingMs)

            // 8. Verify PING -> PONG roundtrip
            client.send(
                NetworkMessageSerializer.createEnvelope(
                    gameUuid = config.gameUuid,
                    senderUuid = clientUuid,
                    type = NetworkMessageType.PING,
                    sequenceNumber = 6L,
                    roomSecret = config.roomSecret,
                    payload = PingPayload(clientTimestampMs = 777_777L)
                )
            )
            val pong = NetworkMessageSerializer.decodeEnvelopePayload<PongPayload>(
                awaitType(inbox, NetworkMessageType.PONG)
            ).getOrThrow()
            assertEquals(777_777L, pong.echoedTimestampMs)

            // 9. Disconnect & RECONNECT_REQUEST restores full authoritative GameState
            client.disconnect()
            assertTrue(client.connect("127.0.0.1", port).isSuccess)
            client.send(
                NetworkMessageSerializer.createEnvelope(
                    gameUuid = config.gameUuid,
                    senderUuid = clientUuid,
                    type = NetworkMessageType.RECONNECT_REQUEST,
                    sequenceNumber = 7L,
                    roomSecret = config.roomSecret,
                    payload = ReconnectRequestPayload(
                        playerUuid = clientUuid,
                        playerName = "Гость Чёрный",
                        lastKnownPly = 1,
                        roomSecret = config.roomSecret
                    )
                )
            )
            val reconnectResp = NetworkMessageSerializer.decodeEnvelopePayload<ReconnectResponsePayload>(
                awaitType(inbox, NetworkMessageType.RECONNECT_RESPONSE)
            ).getOrThrow()
            assertTrue(reconnectResp.accepted)
            assertNotNull(reconnectResp.gameState)
            assertEquals(listOf("e2e4", "e7e5"), reconnectResp.gameState?.uciMoves)

            // 10. Draw offer -> Draw accepted -> GAME_FINISHED
            server.submitHostDrawOffer()
            val receivedOffer = NetworkMessageSerializer.decodeEnvelopePayload<DrawOfferPayload>(
                awaitType(inbox, NetworkMessageType.DRAW_OFFER)
            ).getOrThrow()
            assertEquals(PieceColor.WHITE, receivedOffer.offeredBy)

            client.send(
                NetworkMessageSerializer.createEnvelope(
                    gameUuid = config.gameUuid,
                    senderUuid = clientUuid,
                    type = NetworkMessageType.DRAW_ACCEPTED,
                    sequenceNumber = 8L,
                    roomSecret = config.roomSecret,
                    payload = DrawDecisionPayload(decidedBy = PieceColor.BLACK, accepted = true)
                )
            )
            val finished = NetworkMessageSerializer.decodeEnvelopePayload<GameFinishedPayload>(
                awaitType(inbox, NetworkMessageType.GAME_FINISHED)
            ).getOrThrow()
            assertEquals(GameResult.DRAW, finished.result)
            assertEquals(GameTerminationReason.DRAW_BY_AGREEMENT, finished.reason)
        } finally {
            collectorJob.cancel()
            inbox.close()
            client.disconnect()
            server.stop()
        }
    }

    @Test
    fun clientResignationFinishesMatchAuthoritatively() = runBlocking {
        val config = HostRoomConfig(
            hostName = "Host",
            timeControl = TimeControl.RAPID_10_0,
            sideSelection = SideSelection.WHITE,
            hostAssignedColor = PieceColor.WHITE,
            connectionType = ConnectionType.WIFI_LAN,
            requestedPort = 0
        )
        val server = LocalGameServer(config = config, timeProvider = FakeTimeProvider())
        val client = WebSocketClient()
        val clientUuid = UUID.randomUUID().toString()
        val inbox = Channel<NetworkEnvelope>(Channel.UNLIMITED)
        val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.incomingMessages.collect { inbox.send(it) }
        }

        try {
            val port = server.start()
            assertTrue(client.connect("127.0.0.1", port).isSuccess)

            client.send(
                NetworkMessageSerializer.createEnvelope(
                    gameUuid = config.gameUuid,
                    senderUuid = clientUuid,
                    type = NetworkMessageType.PLAYER_CONNECTED,
                    sequenceNumber = 1L,
                    roomSecret = config.roomSecret,
                    payload = PlayerConnectedPayload(clientUuid, "Guest", ready = true, roomSecret = config.roomSecret)
                )
            )
            awaitType(inbox, NetworkMessageType.ROOM_INFO)

            assertTrue(server.startGame())
            awaitType(inbox, NetworkMessageType.GAME_START)

            client.send(
                NetworkMessageSerializer.createEnvelope(
                    gameUuid = config.gameUuid,
                    senderUuid = clientUuid,
                    type = NetworkMessageType.RESIGN,
                    sequenceNumber = 2L,
                    roomSecret = config.roomSecret,
                    payload = ResignPayload(resigningColor = PieceColor.BLACK)
                )
            )
            val finished = NetworkMessageSerializer.decodeEnvelopePayload<GameFinishedPayload>(
                awaitType(inbox, NetworkMessageType.GAME_FINISHED)
            ).getOrThrow()
            assertEquals(GameResult.WHITE_WIN, finished.result)
            assertEquals(GameTerminationReason.RESIGNATION, finished.reason)
        } finally {
            collectorJob.cancel()
            inbox.close()
            client.disconnect()
            server.stop()
        }
    }
}
