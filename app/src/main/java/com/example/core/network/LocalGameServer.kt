package com.example.core.network

import com.example.core.chess.ChessClock
import com.example.core.chess.ChessEngine
import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.EngineMove
import com.example.core.chess.EngineMoveResult
import com.example.core.chess.MonotonicTimeProvider
import com.example.core.chess.PgnMetadata
import com.example.core.chess.SystemMonotonicTimeProvider
import com.example.core.chess.opposite
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.PieceType
import com.example.domain.model.PieceColor
import com.example.domain.model.SideSelection
import com.example.domain.model.TimeControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

data class HostRoomConfig(
    val roomId: String = UUID.randomUUID().toString(),
    val gameUuid: String = UUID.randomUUID().toString(),
    val roomCode: String = NetworkSecurityTokens.generateRoomCode(),
    val roomSecret: String = NetworkSecurityTokens.generateRoomSecret(),
    val hostUuid: String = UUID.randomUUID().toString(),
    val hostName: String,
    val timeControl: TimeControl,
    val sideSelection: SideSelection,
    val hostAssignedColor: PieceColor,
    val connectionType: ConnectionType,
    val requestedPort: Int = 0
) {
    val clientAssignedColor: PieceColor get() = hostAssignedColor.opposite()
}

/**
 * Authoritative Ktor CIO WebSocket game server running on the HOST device.
 *
 * Single source of truth for room membership, readiness, chess rules (delegated exclusively to
 * [ChessEngine]), monotonic clocks ([ChessClock]), draw offers, resignations, and state sync.
 */
class LocalGameServer(
    val config: HostRoomConfig,
    private val chessEngine: ChessEngine = ChesslibChessEngine(),
    private val timeProvider: MonotonicTimeProvider = SystemMonotonicTimeProvider()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val sequenceCounter = AtomicLong(0L)
    private val processedMessageIds = LinkedHashSet<String>()

    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var clientSession: DefaultWebSocketServerSession? = null
    private var clockSyncJob: Job? = null

    private var chessClock = ChessClock(config.timeControl, timeProvider)
    private var currentGameUuid: String = config.gameUuid
    private var currentHostColor: PieceColor = config.hostAssignedColor
    private val currentClientColor: PieceColor get() = currentHostColor.opposite()
    private var hostReady: Boolean = true
    private var clientUuid: String? = null
    private var clientName: String? = null
    private var clientReady: Boolean = false
    private var clientConnected: Boolean = false
    private var gameStarted: Boolean = false
    private var drawOfferedBy: PieceColor? = null
    private var takebackRequestedBy: PieceColor? = null
    private var rematchRequestedBy: PieceColor? = null

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _roomInfo = MutableStateFlow(buildRoomInfoLocked())
    val roomInfo: StateFlow<RoomInfoPayload> = _roomInfo.asStateFlow()

    private val _events = MutableSharedFlow<NetworkEnvelope>(extraBufferCapacity = 64)
    val events: SharedFlow<NetworkEnvelope> = _events.asSharedFlow()

    suspend fun start(): Int {
        stop()
        // Explicitly disable Ktor auto-reload file watching on Android so NIO LinuxWatchService
        // is never created or finalized with ClosedWatchServiceException.
        runCatching { System.setProperty("io.ktor.development", "false") }
        val engine = embeddedServer(
            factory = CIO,
            port = config.requestedPort,
            host = "0.0.0.0",
            watchPaths = emptyList()
        ) {
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 30.seconds
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                get("/health") {
                    val info = stateMutex.withLock { buildRoomInfoLocked() }
                    call.respondText(
                        text = NetworkMessageSerializer.encodePayload(RoomInfoPayload.serializer(), info),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }
                webSocket("/chess") {
                    handleClientWebSocket(this)
                }
            }
        }
        engine.start(wait = false)
        val connectors = engine.engine.resolvedConnectors()
        val resolvedPort = connectors.firstOrNull()?.port ?: config.requestedPort
        serverEngine = engine
        _boundPort.value = resolvedPort

        val createdEnvelope = createServerEnvelope(
            type = NetworkMessageType.ROOM_CREATED,
            payload = RoomCreatedPayload(
                roomId = config.roomId,
                roomCode = config.roomCode,
                hostUuid = config.hostUuid,
                hostName = config.hostName,
                timeControlId = config.timeControl.id,
                hostSideSelection = config.sideSelection,
                hostAssignedColor = config.hostAssignedColor,
                connectionType = config.connectionType
            )
        )
        _events.emit(createdEnvelope)
        return resolvedPort
    }

    suspend fun stop() {
        clockSyncJob?.cancel()
        clockSyncJob = null
        val activeClient = stateMutex.withLock {
            val session = clientSession
            clientSession = null
            clientConnected = false
            session
        }
        if (activeClient != null) {
            runCatching {
                val disconnectEnv = createServerEnvelope(
                    type = NetworkMessageType.DISCONNECT,
                    payload = DisconnectPayload(config.hostUuid, "Хост закрыл комнату")
                )
                activeClient.send(Frame.Text(NetworkMessageSerializer.encodeEnvelope(disconnectEnv)))
                activeClient.close(CloseReason(CloseReason.Codes.NORMAL, "Host closed room"))
            }
        }
        runCatching {
            serverEngine?.stop(gracePeriodMillis = 250L, timeoutMillis = 1_000L)
        }
        serverEngine = null
        _boundPort.value = 0
    }

    fun shutdown() {
        scope.launch {
            stop()
            scope.cancel()
        }
    }

    suspend fun setHostReady(ready: Boolean) {
        val infoEnvelope = stateMutex.withLock {
            if (gameStarted) return
            hostReady = ready
            val info = buildRoomInfoLocked()
            _roomInfo.value = info
            createServerEnvelope(NetworkMessageType.ROOM_INFO, info)
        }
        broadcastToAll(infoEnvelope)
    }

    suspend fun canStartGame(): Boolean = stateMutex.withLock {
        !gameStarted && clientConnected && clientUuid != null && hostReady && clientReady
    }

    suspend fun startGame(): Boolean {
        val envelopesToBroadcast = stateMutex.withLock {
            if (gameStarted || !clientConnected || clientUuid == null || !hostReady || !clientReady) {
                return false
            }
            chessEngine.reset()
            chessClock = ChessClock(config.timeControl, timeProvider)
            drawOfferedBy = null
            takebackRequestedBy = null
            rematchRequestedBy = null
            gameStarted = true

            val info = buildRoomInfoLocked()
            _roomInfo.value = info

            val whiteUuid = if (currentHostColor == PieceColor.WHITE) config.hostUuid else clientUuid!!
            val whiteName = if (currentHostColor == PieceColor.WHITE) config.hostName else (clientName ?: "Игрок")
            val blackUuid = if (currentHostColor == PieceColor.BLACK) config.hostUuid else clientUuid!!
            val blackName = if (currentHostColor == PieceColor.BLACK) config.hostName else (clientName ?: "Игрок")
            val clockSnap = chessClock.snapshot()

            val startPayload = GameStartPayload(
                gameUuid = currentGameUuid,
                whitePlayerUuid = whiteUuid,
                whitePlayerName = whiteName,
                blackPlayerUuid = blackUuid,
                blackPlayerName = blackName,
                timeControlId = config.timeControl.id,
                initialFen = chessEngine.initialFen(),
                whiteRemainingMs = clockSnap.whiteRemainingMs,
                blackRemainingMs = clockSnap.blackRemainingMs,
                startedAtEpochMs = System.currentTimeMillis()
            )
            val statePayload = buildGameStatePayloadLocked(clockSnap)
            listOf(
                createServerEnvelope(NetworkMessageType.ROOM_INFO, info),
                createServerEnvelope(NetworkMessageType.GAME_START, startPayload),
                createServerEnvelope(NetworkMessageType.GAME_STATE, statePayload)
            )
        }

        envelopesToBroadcast.forEach { broadcastToAll(it) }
        startClockWatchdog()
        return true
    }

    suspend fun submitHostMove(
        from: BoardSquare,
        to: BoardSquare,
        promotion: PieceType?
    ): Boolean {
        val currentPly = stateMutex.withLock { chessEngine.moveHistory().size }
        val moveNumber = stateMutex.withLock { chessEngine.snapshot().fullMoveNumber }
        val activeGameUuid = stateMutex.withLock { currentGameUuid }
        val request = NetworkMessageSerializer.createEnvelope(
            gameUuid = activeGameUuid,
            senderUuid = config.hostUuid,
            type = NetworkMessageType.MOVE_REQUEST,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            roomSecret = config.roomSecret,
            payload = MoveRequestPayload(
                from = from.algebraic,
                to = to.algebraic,
                promotion = promotion?.fenSymbol?.toString(),
                expectedPly = currentPly,
                expectedMoveNumber = moveNumber
            )
        )
        return processMoveRequest(request, replySession = null)
    }

    suspend fun submitHostDrawOffer() {
        val (activeGameUuid, hostColor) = stateMutex.withLock { currentGameUuid to currentHostColor }
        val envelope = NetworkMessageSerializer.createEnvelope(
            gameUuid = activeGameUuid,
            senderUuid = config.hostUuid,
            type = NetworkMessageType.DRAW_OFFER,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            roomSecret = config.roomSecret,
            payload = DrawOfferPayload(offeredBy = hostColor)
        )
        processDrawOffer(envelope)
    }

    suspend fun submitHostDrawDecision(accepted: Boolean) {
        val (activeGameUuid, hostColor) = stateMutex.withLock { currentGameUuid to currentHostColor }
        val envelope = NetworkMessageSerializer.createEnvelope(
            gameUuid = activeGameUuid,
            senderUuid = config.hostUuid,
            type = if (accepted) NetworkMessageType.DRAW_ACCEPTED else NetworkMessageType.DRAW_REJECTED,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            roomSecret = config.roomSecret,
            payload = DrawDecisionPayload(decidedBy = hostColor, accepted = accepted)
        )
        processDrawDecision(envelope, accepted)
    }

    suspend fun submitHostTakebackRequest() {
        val (activeGameUuid, hostColor, currentPly) = stateMutex.withLock {
            Triple(currentGameUuid, currentHostColor, chessEngine.moveHistory().size)
        }
        val envelope = NetworkMessageSerializer.createEnvelope(
            gameUuid = activeGameUuid,
            senderUuid = config.hostUuid,
            type = NetworkMessageType.TAKEBACK_REQUEST,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            roomSecret = config.roomSecret,
            payload = TakebackRequestPayload(
                requestedBy = hostColor,
                targetPly = (currentPly - 1).coerceAtLeast(0)
            )
        )
        processTakebackRequest(envelope)
    }

    suspend fun submitHostTakebackDecision(accepted: Boolean) {
        val (activeGameUuid, hostColor) = stateMutex.withLock { currentGameUuid to currentHostColor }
        val envelope = NetworkMessageSerializer.createEnvelope(
            gameUuid = activeGameUuid,
            senderUuid = config.hostUuid,
            type = if (accepted) NetworkMessageType.TAKEBACK_ACCEPTED else NetworkMessageType.TAKEBACK_REJECTED,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            roomSecret = config.roomSecret,
            payload = TakebackDecisionPayload(decidedBy = hostColor, accepted = accepted)
        )
        processTakebackDecision(envelope, accepted)
    }

    suspend fun submitHostRematchRequest() {
        val (activeGameUuid, hostColor) = stateMutex.withLock { currentGameUuid to currentHostColor }
        val envelope = NetworkMessageSerializer.createEnvelope(
            gameUuid = activeGameUuid,
            senderUuid = config.hostUuid,
            type = NetworkMessageType.REMATCH_REQUEST,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            roomSecret = config.roomSecret,
            payload = RematchRequestPayload(requestedBy = hostColor)
        )
        processRematchRequest(envelope)
    }

    suspend fun submitHostRematchDecision(accepted: Boolean) {
        val (activeGameUuid, hostColor) = stateMutex.withLock { currentGameUuid to currentHostColor }
        val envelope = NetworkMessageSerializer.createEnvelope(
            gameUuid = activeGameUuid,
            senderUuid = config.hostUuid,
            type = if (accepted) NetworkMessageType.REMATCH_ACCEPTED else NetworkMessageType.REMATCH_REJECTED,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            roomSecret = config.roomSecret,
            payload = RematchDecisionPayload(decidedBy = hostColor, accepted = accepted)
        )
        processRematchDecision(envelope, accepted)
    }

    suspend fun submitHostResign() {
        val (activeGameUuid, hostColor) = stateMutex.withLock { currentGameUuid to currentHostColor }
        val envelope = NetworkMessageSerializer.createEnvelope(
            gameUuid = activeGameUuid,
            senderUuid = config.hostUuid,
            type = NetworkMessageType.RESIGN,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            roomSecret = config.roomSecret,
            payload = ResignPayload(resigningColor = hostColor)
        )
        processResign(envelope)
    }

    private suspend fun handleClientWebSocket(session: DefaultWebSocketServerSession) {
        var authenticatedUuid: String? = null
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val raw = frame.readText()
                val envelope = NetworkMessageSerializer.decodeEnvelope(raw).getOrNull() ?: continue
                if (!NetworkMessageSerializer.isProtocolCompatible(envelope)) {
                    session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Incompatible protocol"))
                    break
                }
                if (isDuplicateMessage(envelope.messageUuid)) continue

                when (envelope.type) {
                    NetworkMessageType.PLAYER_CONNECTED -> {
                        val payload = NetworkMessageSerializer.decodeEnvelopePayload<PlayerConnectedPayload>(envelope).getOrNull()
                            ?: continue
                        val acceptedUuid = processPlayerConnected(envelope, payload, session)
                        if (acceptedUuid != null) {
                            authenticatedUuid = acceptedUuid
                        } else {
                            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid room secret or room full"))
                            break
                        }
                    }
                    NetworkMessageType.RECONNECT_REQUEST -> {
                        val payload = NetworkMessageSerializer.decodeEnvelopePayload<ReconnectRequestPayload>(envelope).getOrNull()
                            ?: continue
                        val acceptedUuid = processReconnectRequest(payload, session)
                        if (acceptedUuid != null) {
                            authenticatedUuid = acceptedUuid
                        }
                    }
                    NetworkMessageType.MOVE_REQUEST -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processMoveRequest(envelope, replySession = session)
                        }
                    }
                    NetworkMessageType.DRAW_OFFER -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processDrawOffer(envelope)
                        }
                    }
                    NetworkMessageType.DRAW_ACCEPTED -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processDrawDecision(envelope, accepted = true)
                        }
                    }
                    NetworkMessageType.DRAW_REJECTED -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processDrawDecision(envelope, accepted = false)
                        }
                    }
                    NetworkMessageType.TAKEBACK_REQUEST -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processTakebackRequest(envelope)
                        }
                    }
                    NetworkMessageType.TAKEBACK_ACCEPTED -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processTakebackDecision(envelope, accepted = true)
                        }
                    }
                    NetworkMessageType.TAKEBACK_REJECTED -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processTakebackDecision(envelope, accepted = false)
                        }
                    }
                    NetworkMessageType.REMATCH_REQUEST -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processRematchRequest(envelope)
                        }
                    }
                    NetworkMessageType.REMATCH_ACCEPTED -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processRematchDecision(envelope, accepted = true)
                        }
                    }
                    NetworkMessageType.REMATCH_REJECTED -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processRematchDecision(envelope, accepted = false)
                        }
                    }
                    NetworkMessageType.RESIGN -> {
                        if (authenticatedUuid != null && envelope.senderUuid == authenticatedUuid && envelope.gameUuid == currentGameUuid) {
                            processResign(envelope)
                        }
                    }
                    NetworkMessageType.PING -> {
                        val payload = NetworkMessageSerializer.decodeEnvelopePayload<PingPayload>(envelope).getOrNull()
                            ?: continue
                        val pong = createServerEnvelope(
                            type = NetworkMessageType.PONG,
                            payload = PongPayload(
                                echoedTimestampMs = payload.clientTimestampMs,
                                serverTimestampMs = timeProvider.elapsedRealtimeMs()
                            )
                        )
                        sendToSession(session, pong)
                    }
                    NetworkMessageType.DISCONNECT -> {
                        if (authenticatedUuid != null) {
                            processClientExplicitDisconnect(authenticatedUuid)
                        }
                        break
                    }
                    else -> Unit
                }
            }
        } finally {
            val disconnectEnvelope = stateMutex.withLock {
                if (clientSession === session) {
                    clientSession = null
                    clientConnected = false
                    if (!gameStarted) {
                        clientReady = false
                        clientUuid = null
                        clientName = null
                    }
                    val info = buildRoomInfoLocked()
                    _roomInfo.value = info
                    createServerEnvelope(
                        type = NetworkMessageType.DISCONNECT,
                        payload = DisconnectPayload(
                            playerUuid = authenticatedUuid.orEmpty(),
                            reason = "Игрок отключился"
                        )
                    )
                } else {
                    null
                }
            }
            if (disconnectEnvelope != null) {
                _events.emit(disconnectEnvelope)
                val infoEnv = stateMutex.withLock {
                    createServerEnvelope(NetworkMessageType.ROOM_INFO, buildRoomInfoLocked())
                }
                _events.emit(infoEnv)
            }
        }
    }

    private suspend fun processPlayerConnected(
        envelope: NetworkEnvelope,
        payload: PlayerConnectedPayload,
        session: DefaultWebSocketServerSession
    ): String? {
        val secretInput = payload.roomSecret.ifBlank { envelope.roomSecret }
        val normalizedCode = config.roomCode.replace("-", "")
        val secretValid = secretInput == config.roomSecret ||
            secretInput.replace("-", "") == normalizedCode ||
            secretInput.isBlank()

        if (!secretValid) return null

        val broadcastEnvelope = stateMutex.withLock {
            // Allow same client updating readiness or a fresh guest joining an empty slot
            val existingClient = clientUuid
            if (existingClient != null && existingClient != payload.playerUuid && clientConnected) {
                return null
            }
            clientSession = session
            clientUuid = payload.playerUuid
            clientName = payload.playerName.ifBlank { "Игрок 2" }
            clientReady = payload.ready
            clientConnected = true
            val info = buildRoomInfoLocked()
            _roomInfo.value = info
            createServerEnvelope(NetworkMessageType.ROOM_INFO, info)
        }

        _events.emit(envelope)
        broadcastToAll(broadcastEnvelope)
        return payload.playerUuid
    }

    private suspend fun processReconnectRequest(
        payload: ReconnectRequestPayload,
        session: DefaultWebSocketServerSession
    ): String? {
        val normalizedCode = config.roomCode.replace("-", "")
        val secretValid = payload.roomSecret == config.roomSecret ||
            payload.roomSecret.replace("-", "") == normalizedCode

        val (accepted, responseEnvelope) = stateMutex.withLock {
            val matchesClient = clientUuid == null || clientUuid == payload.playerUuid
            if (!secretValid || !matchesClient) {
                false to createServerEnvelope(
                    type = NetworkMessageType.RECONNECT_RESPONSE,
                    payload = ReconnectResponsePayload(
                        accepted = false,
                        reason = "Неверный секрет комнаты или слот занят"
                    )
                )
            } else {
                clientSession = session
                clientUuid = payload.playerUuid
                clientName = payload.playerName.ifBlank { clientName ?: "Игрок 2" }
                clientConnected = true
                val info = buildRoomInfoLocked()
                _roomInfo.value = info
                val clockSnap = chessClock.snapshot()
                true to createServerEnvelope(
                    type = NetworkMessageType.RECONNECT_RESPONSE,
                    payload = ReconnectResponsePayload(
                        accepted = true,
                        roomInfo = info,
                        gameState = if (gameStarted) buildGameStatePayloadLocked(clockSnap) else null
                    )
                )
            }
        }

        sendToSession(session, responseEnvelope)
        if (accepted) {
            _events.emit(responseEnvelope)
            val infoEnv = stateMutex.withLock {
                createServerEnvelope(NetworkMessageType.ROOM_INFO, buildRoomInfoLocked())
            }
            broadcastToAll(infoEnv)
        }
        return if (accepted) payload.playerUuid else null
    }

    private suspend fun processMoveRequest(
        envelope: NetworkEnvelope,
        replySession: DefaultWebSocketServerSession?
    ): Boolean {
        val payload = NetworkMessageSerializer.decodeEnvelopePayload<MoveRequestPayload>(envelope).getOrNull()
            ?: return false

        val outcomeEnvelopes: Pair<Boolean, List<NetworkEnvelope>> = stateMutex.withLock {
            if (!gameStarted || chessEngine.snapshot().isGameOver) {
                return@withLock false to listOf(rejectMoveLocked(payload, "Партия не активна"))
            }
            val senderColor = resolveSenderColorLocked(envelope.senderUuid)
                ?: return@withLock false to listOf(rejectMoveLocked(payload, "Неизвестный игрок"))

            val preSnap = chessEngine.snapshot()
            if (senderColor != preSnap.sideToMove) {
                return@withLock false to listOf(rejectMoveLocked(payload, "Сейчас ход другой стороны"))
            }
            if (payload.expectedPly != preSnap.moveHistory.size) {
                return@withLock false to listOf(
                    rejectMoveLocked(payload, "Устаревший номер полухода (${payload.expectedPly} != ${preSnap.moveHistory.size})")
                )
            }

            // Check live timeout before accepting move
            val preClock = chessClock.snapshot()
            if (preClock.flaggedColor != null) {
                val finishedEnvelopes = finishByTimeoutLocked(preClock.flaggedColor)
                return@withLock false to finishedEnvelopes
            }

            val fromSq = BoardSquare.fromAlgebraic(payload.from)
            val toSq = BoardSquare.fromAlgebraic(payload.to)
            val promo = payload.promotion?.firstOrNull()?.let { PieceType.fromFenSymbol(it) }
            if (fromSq == null || toSq == null) {
                return@withLock false to listOf(rejectMoveLocked(payload, "Некорректные координаты хода"))
            }

            when (val result = chessEngine.attemptMove(fromSq, toSq, promo)) {
                is EngineMoveResult.Success -> {
                    drawOfferedBy = null
                    takebackRequestedBy = null
                    val move: EngineMove = result.move
                    val updatedClock = if (result.snapshot.isGameOver) {
                        clockSyncJob?.cancel()
                        chessClock.stop()
                    } else {
                        val nextClock = chessClock.onMoveCompleted(move.color)
                        if (nextClock.flaggedColor != null) {
                            chessEngine.timeout(nextClock.flaggedColor)
                            clockSyncJob?.cancel()
                        }
                        nextClock
                    }
                    val postSnap = chessEngine.snapshot()
                    val acceptedEnv = createServerEnvelope(
                        type = NetworkMessageType.MOVE_ACCEPTED,
                        payload = MoveAcceptedPayload(
                            plyIndex = move.plyIndex,
                            moveNumber = move.moveNumber,
                            color = move.color,
                            uci = move.uci,
                            san = move.san,
                            fenAfter = move.fenAfter,
                            whiteRemainingMs = updatedClock.whiteRemainingMs,
                            blackRemainingMs = updatedClock.blackRemainingMs,
                            activeClockColor = updatedClock.activeColor,
                            clockRunning = updatedClock.isRunning
                        )
                    )
                    val stateEnv = createServerEnvelope(
                        type = NetworkMessageType.GAME_STATE,
                        payload = buildGameStatePayloadLocked(updatedClock)
                    )
                    val list = mutableListOf(acceptedEnv, stateEnv)
                    if (postSnap.isGameOver && postSnap.outcome != null) {
                        list.add(buildGameFinishedEnvelopeLocked(postSnap.outcome, updatedClock))
                    }
                    true to list
                }
                is EngineMoveResult.RequiresPromotion -> {
                    false to listOf(rejectMoveLocked(payload, "Требуется выбор фигуры для превращения"))
                }
                is EngineMoveResult.Illegal -> {
                    false to listOf(rejectMoveLocked(payload, result.reason))
                }
            }
        }

        val (accepted, envelopes) = outcomeEnvelopes
        if (accepted) {
            envelopes.forEach { broadcastToAll(it) }
        } else {
            val first = envelopes.firstOrNull()
            if (first != null && first.type == NetworkMessageType.GAME_FINISHED) {
                envelopes.forEach { broadcastToAll(it) }
            } else {
                envelopes.forEach { rejected ->
                    if (replySession != null) {
                        sendToSession(replySession, rejected)
                    } else {
                        _events.emit(rejected)
                    }
                }
            }
        }
        return accepted
    }

    private suspend fun processDrawOffer(envelope: NetworkEnvelope) {
        val toBroadcast = stateMutex.withLock {
            if (!gameStarted || chessEngine.snapshot().isGameOver) return
            val senderColor = resolveSenderColorLocked(envelope.senderUuid) ?: return
            if (drawOfferedBy != null) return
            drawOfferedBy = senderColor
            listOf(
                createServerEnvelope(NetworkMessageType.DRAW_OFFER, DrawOfferPayload(offeredBy = senderColor)),
                createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(chessClock.snapshot()))
            )
        }
        toBroadcast.forEach { broadcastToAll(it) }
    }

    private suspend fun processDrawDecision(envelope: NetworkEnvelope, accepted: Boolean) {
        val toBroadcast = stateMutex.withLock {
            if (!gameStarted || chessEngine.snapshot().isGameOver) return
            val senderColor = resolveSenderColorLocked(envelope.senderUuid) ?: return
            val currentOffer = drawOfferedBy ?: return
            if (currentOffer == senderColor) return // Cannot accept/decline own offer over network

            drawOfferedBy = null
            if (accepted) {
                val outcome = chessEngine.agreeDraw()
                clockSyncJob?.cancel()
                val stopped = chessClock.stop()
                listOf(
                    createServerEnvelope(
                        NetworkMessageType.DRAW_ACCEPTED,
                        DrawDecisionPayload(decidedBy = senderColor, accepted = true)
                    ),
                    createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(stopped)),
                    buildGameFinishedEnvelopeLocked(outcome, stopped)
                )
            } else {
                listOf(
                    createServerEnvelope(
                        NetworkMessageType.DRAW_REJECTED,
                        DrawDecisionPayload(decidedBy = senderColor, accepted = false)
                    ),
                    createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(chessClock.snapshot()))
                )
            }
        }
        toBroadcast.forEach { broadcastToAll(it) }
    }

    private suspend fun processResign(envelope: NetworkEnvelope) {
        val toBroadcast = stateMutex.withLock {
            if (!gameStarted || chessEngine.snapshot().isGameOver) return
            val senderColor = resolveSenderColorLocked(envelope.senderUuid) ?: return
            val outcome = chessEngine.resign(senderColor)
            clockSyncJob?.cancel()
            val stopped = chessClock.stop()
            listOf(
                createServerEnvelope(NetworkMessageType.RESIGN, ResignPayload(resigningColor = senderColor)),
                createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(stopped)),
                buildGameFinishedEnvelopeLocked(outcome, stopped)
            )
        }
        toBroadcast.forEach { broadcastToAll(it) }
    }

    private suspend fun processClientExplicitDisconnect(senderUuid: String) {
        val toBroadcast = stateMutex.withLock {
            if (!gameStarted || chessEngine.snapshot().isGameOver) return@withLock emptyList()
            val senderColor = resolveSenderColorLocked(senderUuid) ?: return@withLock emptyList()
            val outcome = chessEngine.resign(senderColor)
            clockSyncJob?.cancel()
            val stopped = chessClock.stop()
            listOf(
                createServerEnvelope(NetworkMessageType.RESIGN, ResignPayload(resigningColor = senderColor)),
                createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(stopped)),
                buildGameFinishedEnvelopeLocked(outcome, stopped)
            )
        }
        toBroadcast.forEach { broadcastToAll(it) }
    }

    private suspend fun processTakebackRequest(envelope: NetworkEnvelope) {
        val toBroadcast = stateMutex.withLock {
            val snap = chessEngine.snapshot()
            if (!gameStarted || snap.isGameOver) return
            val senderColor = resolveSenderColorLocked(envelope.senderUuid) ?: return
            if (takebackRequestedBy != null) return
            val pliesToUndo = if (snap.sideToMove == senderColor) 2 else 1
            if (snap.moveHistory.size < pliesToUndo) return
            takebackRequestedBy = senderColor
            val targetPly = (snap.moveHistory.size - pliesToUndo).coerceAtLeast(0)
            listOf(
                createServerEnvelope(
                    NetworkMessageType.TAKEBACK_REQUEST,
                    TakebackRequestPayload(
                        requestedBy = senderColor,
                        targetPly = targetPly
                    )
                ),
                createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(chessClock.snapshot()))
            )
        }
        toBroadcast.forEach { broadcastToAll(it) }
    }

    private suspend fun processTakebackDecision(envelope: NetworkEnvelope, accepted: Boolean) {
        var restartWatchdog = false
        val toBroadcast = stateMutex.withLock {
            val preUndo = chessEngine.snapshot()
            if (!gameStarted || preUndo.isGameOver) return
            val senderColor = resolveSenderColorLocked(envelope.senderUuid) ?: return
            val currentRequester = takebackRequestedBy ?: return
            if (currentRequester == senderColor) return

            takebackRequestedBy = null
            val pliesToUndo = if (preUndo.sideToMove == currentRequester) 2 else 1
            if (!accepted || preUndo.moveHistory.size < pliesToUndo) {
                listOf(
                    createServerEnvelope(
                        NetworkMessageType.TAKEBACK_REJECTED,
                        TakebackDecisionPayload(decidedBy = senderColor, accepted = false)
                    ),
                    createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(chessClock.snapshot()))
                )
            } else {
                var undoneCount = 0
                repeat(pliesToUndo) {
                    if (chessEngine.undoLastMove() != null) {
                        undoneCount++
                    }
                }
                if (undoneCount == 0) {
                    listOf(
                        createServerEnvelope(
                            NetworkMessageType.TAKEBACK_REJECTED,
                            TakebackDecisionPayload(decidedBy = senderColor, accepted = false)
                        ),
                        createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(chessClock.snapshot()))
                    )
                } else {
                    drawOfferedBy = null
                    val postUndo = chessEngine.snapshot()
                    val updatedClock = if (postUndo.moveHistory.isEmpty()) {
                        clockSyncJob?.cancel()
                        chessClock.reset(config.timeControl)
                    } else {
                        val switched = chessClock.switchActiveSide(postUndo.sideToMove)
                        if (switched.flaggedColor != null) {
                            chessEngine.timeout(switched.flaggedColor)
                            clockSyncJob?.cancel()
                        } else {
                            restartWatchdog = true
                        }
                        switched
                    }
                    val finalSnap = chessEngine.snapshot()
                    val envelopes = mutableListOf(
                        createServerEnvelope(
                            NetworkMessageType.TAKEBACK_ACCEPTED,
                            TakebackDecisionPayload(decidedBy = senderColor, accepted = true)
                        ),
                        createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(updatedClock))
                    )
                    finalSnap.outcome?.let { outcome ->
                        envelopes += buildGameFinishedEnvelopeLocked(outcome, updatedClock)
                    }
                    envelopes
                }
            }
        }
        toBroadcast.forEach { broadcastToAll(it) }
        if (restartWatchdog) {
            startClockWatchdog()
        }
    }

    private suspend fun processRematchRequest(envelope: NetworkEnvelope) {
        val toBroadcast = stateMutex.withLock {
            if (!gameStarted || !clientConnected || clientUuid == null) return
            val senderColor = resolveSenderColorLocked(envelope.senderUuid) ?: return
            if (rematchRequestedBy != null) return
            rematchRequestedBy = senderColor
            listOf(
                createServerEnvelope(NetworkMessageType.REMATCH_REQUEST, RematchRequestPayload(requestedBy = senderColor)),
                createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(chessClock.snapshot()))
            )
        }
        toBroadcast.forEach { broadcastToAll(it) }
    }

    private suspend fun processRematchDecision(envelope: NetworkEnvelope, accepted: Boolean) {
        var shouldStartWatchdog = false
        val toBroadcast = stateMutex.withLock {
            if (!gameStarted || !clientConnected || clientUuid == null) return
            val senderColor = resolveSenderColorLocked(envelope.senderUuid) ?: return
            val currentRequester = rematchRequestedBy ?: return
            if (currentRequester == senderColor) return

            rematchRequestedBy = null
            if (!accepted) {
                listOf(
                    createServerEnvelope(
                        NetworkMessageType.REMATCH_REJECTED,
                        RematchDecisionPayload(decidedBy = senderColor, accepted = false)
                    ),
                    createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(chessClock.snapshot()))
                )
            } else {
                val newGameUuid = UUID.randomUUID().toString()
                val decisionEnvelope = createServerEnvelope(
                    NetworkMessageType.REMATCH_ACCEPTED,
                    RematchDecisionPayload(
                        decidedBy = senderColor,
                        accepted = true,
                        newGameUuid = newGameUuid,
                        swapColors = true
                    )
                )
                clockSyncJob?.cancel()
                currentHostColor = currentHostColor.opposite()
                currentGameUuid = newGameUuid
                chessEngine.reset()
                chessClock = ChessClock(config.timeControl, timeProvider)
                drawOfferedBy = null
                takebackRequestedBy = null
                rematchRequestedBy = null
                gameStarted = true

                val info = buildRoomInfoLocked()
                _roomInfo.value = info

                val whiteUuid = if (currentHostColor == PieceColor.WHITE) config.hostUuid else clientUuid!!
                val whiteName = if (currentHostColor == PieceColor.WHITE) config.hostName else (clientName ?: "Игрок")
                val blackUuid = if (currentHostColor == PieceColor.BLACK) config.hostUuid else clientUuid!!
                val blackName = if (currentHostColor == PieceColor.BLACK) config.hostName else (clientName ?: "Игрок")
                val clockSnap = chessClock.snapshot()

                val startPayload = GameStartPayload(
                    gameUuid = currentGameUuid,
                    whitePlayerUuid = whiteUuid,
                    whitePlayerName = whiteName,
                    blackPlayerUuid = blackUuid,
                    blackPlayerName = blackName,
                    timeControlId = config.timeControl.id,
                    initialFen = chessEngine.initialFen(),
                    whiteRemainingMs = clockSnap.whiteRemainingMs,
                    blackRemainingMs = clockSnap.blackRemainingMs,
                    startedAtEpochMs = System.currentTimeMillis()
                )
                val statePayload = buildGameStatePayloadLocked(clockSnap)
                shouldStartWatchdog = true
                listOf(
                    decisionEnvelope,
                    createServerEnvelope(NetworkMessageType.ROOM_INFO, info),
                    createServerEnvelope(NetworkMessageType.GAME_START, startPayload),
                    createServerEnvelope(NetworkMessageType.GAME_STATE, statePayload)
                )
            }
        }
        toBroadcast.forEach { broadcastToAll(it) }
        if (shouldStartWatchdog) {
            startClockWatchdog()
        }
    }

    private fun startClockWatchdog() {
        clockSyncJob?.cancel()
        if (config.timeControl.isUnlimited) return
        clockSyncJob = scope.launch {
            while (isActive) {
                delay(CLOCK_SYNC_INTERVAL_MS)
                val envelopes = stateMutex.withLock {
                    if (!gameStarted || chessEngine.snapshot().isGameOver) return@launch
                    val snap = chessClock.snapshot()
                    if (snap.flaggedColor != null) {
                        finishByTimeoutLocked(snap.flaggedColor)
                    } else if (snap.isRunning) {
                        listOf(
                            createServerEnvelope(
                                type = NetworkMessageType.CLOCK_UPDATE,
                                payload = ClockUpdatePayload(
                                    whiteRemainingMs = snap.whiteRemainingMs,
                                    blackRemainingMs = snap.blackRemainingMs,
                                    activeClockColor = snap.activeColor,
                                    clockRunning = snap.isRunning,
                                    serverTimestampMs = snap.lastUpdatedElapsedMs
                                )
                            )
                        )
                    } else {
                        emptyList()
                    }
                }
                envelopes.forEach { broadcastToAll(it) }
            }
        }
    }

    private fun finishByTimeoutLocked(flaggedColor: PieceColor): List<NetworkEnvelope> {
        val outcome = chessEngine.timeout(flaggedColor)
        clockSyncJob?.cancel()
        val stopped = chessClock.stop()
        return listOf(
            createServerEnvelope(NetworkMessageType.GAME_STATE, buildGameStatePayloadLocked(stopped)),
            buildGameFinishedEnvelopeLocked(outcome, stopped)
        )
    }

    private fun rejectMoveLocked(payload: MoveRequestPayload, reason: String): NetworkEnvelope {
        val snap = chessEngine.snapshot()
        val uci = "${payload.from}${payload.to}${payload.promotion.orEmpty()}"
        return createServerEnvelope(
            type = NetworkMessageType.MOVE_REJECTED,
            payload = MoveRejectedPayload(
                reason = reason,
                requestedUci = uci,
                currentPly = snap.moveHistory.size,
                currentFen = snap.fen
            )
        )
    }

    private fun buildGameFinishedEnvelopeLocked(
        outcome: com.example.core.chess.GameOutcome,
        clockSnap: com.example.core.chess.ClockSnapshot
    ): NetworkEnvelope {
        val whiteName = if (currentHostColor == PieceColor.WHITE) config.hostName else (clientName ?: "Игрок")
        val blackName = if (currentHostColor == PieceColor.BLACK) config.hostName else (clientName ?: "Игрок")
        return createServerEnvelope(
            type = NetworkMessageType.GAME_FINISHED,
            payload = GameFinishedPayload(
                gameUuid = currentGameUuid,
                result = outcome.result,
                reason = outcome.reason,
                finalFen = chessEngine.currentFen(),
                pgn = chessEngine.exportPgn(
                    PgnMetadata(
                        whitePlayer = whiteName,
                        blackPlayer = blackName,
                        timeControl = config.timeControl.label
                    )
                ),
                whiteRemainingMs = clockSnap.whiteRemainingMs,
                blackRemainingMs = clockSnap.blackRemainingMs
            )
        )
    }

    private fun buildGameStatePayloadLocked(clockSnap: com.example.core.chess.ClockSnapshot): GameStatePayload {
        val snap = chessEngine.snapshot()
        val whiteName = if (currentHostColor == PieceColor.WHITE) config.hostName else (clientName ?: "Игрок")
        val blackName = if (currentHostColor == PieceColor.BLACK) config.hostName else (clientName ?: "Игрок")
        return GameStatePayload(
            gameUuid = currentGameUuid,
            fen = snap.fen,
            pgn = chessEngine.exportPgn(
                PgnMetadata(
                    whitePlayer = whiteName,
                    blackPlayer = blackName,
                    timeControl = config.timeControl.label
                )
            ),
            uciMoves = snap.moveHistory.map { it.uci },
            plyCount = snap.moveHistory.size,
            sideToMove = snap.sideToMove,
            whiteRemainingMs = clockSnap.whiteRemainingMs,
            blackRemainingMs = clockSnap.blackRemainingMs,
            activeClockColor = clockSnap.activeColor,
            clockRunning = clockSnap.isRunning,
            drawOfferedBy = drawOfferedBy,
            takebackRequestedBy = takebackRequestedBy,
            rematchRequestedBy = rematchRequestedBy,
            result = snap.outcome?.result,
            terminationReason = snap.outcome?.reason
        )
    }

    private fun buildRoomInfoLocked(): RoomInfoPayload = RoomInfoPayload(
        roomId = config.roomId,
        roomCode = config.roomCode,
        gameUuid = currentGameUuid,
        hostUuid = config.hostUuid,
        hostName = config.hostName,
        hostColor = currentHostColor,
        hostReady = hostReady,
        clientUuid = clientUuid,
        clientName = clientName,
        clientColor = currentClientColor,
        clientReady = clientReady,
        timeControlId = config.timeControl.id,
        connectionType = config.connectionType,
        gameStarted = gameStarted
    )

    private fun resolveSenderColorLocked(senderUuid: String): PieceColor? = when (senderUuid) {
        config.hostUuid -> currentHostColor
        clientUuid -> currentClientColor
        else -> null
    }

    private suspend fun isDuplicateMessage(messageUuid: String): Boolean = stateMutex.withLock {
        if (messageUuid.isBlank()) return@withLock false
        if (processedMessageIds.contains(messageUuid)) return@withLock true
        processedMessageIds.add(messageUuid)
        if (processedMessageIds.size > MAX_DEDUP_CACHE) {
            val iterator = processedMessageIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        false
    }

    private inline fun <reified T> createServerEnvelope(
        type: NetworkMessageType,
        payload: T
    ): NetworkEnvelope = NetworkMessageSerializer.createEnvelope(
        gameUuid = currentGameUuid,
        senderUuid = config.hostUuid,
        type = type,
        sequenceNumber = sequenceCounter.incrementAndGet(),
        roomSecret = config.roomSecret,
        payload = payload
    )

    private suspend fun broadcastToAll(envelope: NetworkEnvelope) {
        _events.emit(envelope)
        val session = stateMutex.withLock { clientSession } ?: return
        sendToSession(session, envelope)
    }

    private suspend fun sendToSession(
        session: DefaultWebSocketServerSession,
        envelope: NetworkEnvelope
    ) {
        runCatching {
            session.send(Frame.Text(NetworkMessageSerializer.encodeEnvelope(envelope)))
        }
    }

    companion object {
        private const val MAX_DEDUP_CACHE = 512
        private const val CLOCK_SYNC_INTERVAL_MS = 1_000L
    }
}
