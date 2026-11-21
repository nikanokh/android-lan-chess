package com.example.core.network

import com.example.core.chess.ChessClock
import com.example.core.chess.ChessEngine
import com.example.core.chess.ChessPositionSnapshot
import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.ClockSnapshot
import com.example.core.chess.EngineMove
import com.example.core.chess.GameOutcome
import com.example.core.chess.GameTerminationReason
import com.example.core.chess.MonotonicTimeProvider
import com.example.core.chess.PgnMetadata
import com.example.core.chess.opposite
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.LegalTarget
import com.example.core.ui.board.PieceType
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import com.example.domain.model.SideSelection
import com.example.domain.model.TimeControl
import com.example.feature.game.ConnectionStatusUi
import com.example.feature.game.DrawOfferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class SessionRole {
    NONE,
    HOST,
    CLIENT
}

data class GameSessionState(
    val role: SessionRole = SessionRole.NONE,
    val connectionType: ConnectionType = ConnectionType.WIFI_LAN,
    val roomId: String = "",
    val gameUuid: String = "",
    val roomCode: String = "",
    val roomSecret: String = "",
    val hostAddress: String? = null,
    val port: Int = 0,
    val localPlayerUuid: String = "",
    val localPlayerName: String = "",
    val localColor: PieceColor = PieceColor.WHITE,
    val opponentName: String? = null,
    val roomInfo: RoomInfoPayload? = null,
    val timeControl: TimeControl = TimeControl.RAPID_10_0,
    val isGameStarted: Boolean = false,
    val positionSnapshot: ChessPositionSnapshot? = null,
    val clockSnapshot: ClockSnapshot? = null,
    val drawOfferState: DrawOfferState = DrawOfferState.NONE,
    val drawOfferedBy: PieceColor? = null,
    val connectionStatus: ConnectionStatusUi = ConnectionStatusUi.LOCAL_PASS_AND_PLAY,
    val lastAnimatedMove: EngineMove? = null,
    val lastAnimatedMoveId: Long = 0L,
    val lastError: String? = null,
    val lastRejectionReason: String? = null,
    val isConnecting: Boolean = false
) {
    val isActiveLanSession: Boolean
        get() = role != SessionRole.NONE

    val isLocalReady: Boolean
        get() = when (role) {
            SessionRole.HOST -> roomInfo?.hostReady == true
            SessionRole.CLIENT -> roomInfo?.clientReady == true
            SessionRole.NONE -> false
        }

    val isOpponentConnected: Boolean
        get() = when (role) {
            SessionRole.HOST -> roomInfo?.clientUuid != null && connectionStatus == ConnectionStatusUi.CONNECTED
            SessionRole.CLIENT -> roomInfo != null && connectionStatus == ConnectionStatusUi.CONNECTED
            SessionRole.NONE -> false
        }

    val canHostStartGame: Boolean
        get() = role == SessionRole.HOST &&
            !isGameStarted &&
            connectionStatus == ConnectionStatusUi.CONNECTED &&
            roomInfo?.let { it.clientUuid != null && it.hostReady && it.clientReady } == true
}

/**
 * Coordinates the full local multiplayer session lifecycle across Host ([LocalGameServer]),
 * Client ([WebSocketClient]), LAN discovery ([LanDiscoveryManager]), and shared state
 * ([ConnectionStateManager]).
 *
 * Uses a single authoritative [ChessEngine] mirror updated exclusively from server-confirmed
 * protocol messages so Client never confirms moves locally and Host/Client stay 100% in sync.
 */
@Singleton
class GameSessionManager @Inject constructor(
    private val lanDiscoveryManager: LanDiscoveryManager,
    private val connectionStateManager: ConnectionStateManager,
    private val timeProvider: MonotonicTimeProvider
) : NetworkConnectionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val engineLock = Any()
    private val secureRandom = SecureRandom()
    private val clientSequence = AtomicLong(0L)
    private val animatedMoveCounter = AtomicLong(0L)

    private val mirrorEngine: ChessEngine = ChesslibChessEngine()
    private var mirrorClock: ChessClock = ChessClock(TimeControl.RAPID_10_0, timeProvider)

    private var activeServer: LocalGameServer? = null
    private var activeClient: WebSocketClient? = null
    private var serverEventsJob: Job? = null
    private var clientMessagesJob: Job? = null
    private var clientTransportJob: Job? = null

    private val _sessionState = MutableStateFlow(GameSessionState())
    val sessionState: StateFlow<GameSessionState> = _sessionState.asStateFlow()

    override val connectionState: StateFlow<ConnectionState>
        get() = connectionStateManager.connectionState

    override val discoveredRooms: StateFlow<List<DiscoveredRoom>>
        get() = connectionStateManager.discoveredRooms

    val localIpAddress: StateFlow<String?>
        get() = connectionStateManager.localIpAddress

    override suspend fun startDiscovery(mode: ConnectionType) {
        connectionStateManager.setLocalIpAddress(lanDiscoveryManager.resolveLocalIpv4Address())
        connectionStateManager.clearDiscoveredRooms()
        connectionStateManager.setConnectionState(ConnectionState.Discovering(mode))

        lanDiscoveryManager.startDiscovery(
            mode = mode,
            onRoomFound = { room ->
                connectionStateManager.upsertDiscoveredRoom(room)
            },
            onRoomLost = { serviceName ->
                connectionStateManager.removeDiscoveredRoomByServiceName(serviceName)
            },
            onError = { message ->
                connectionStateManager.setConnectionState(ConnectionState.Error(message))
                _sessionState.update { it.copy(lastError = message) }
            }
        )
    }

    override suspend fun stopDiscovery() {
        lanDiscoveryManager.stopDiscovery()
        if (connectionStateManager.connectionState.value is ConnectionState.Discovering) {
            connectionStateManager.setConnectionState(ConnectionState.Idle)
        }
    }

    override suspend fun connectToRoom(room: DiscoveredRoom) {
        val currentName = _sessionState.value.localPlayerName.ifBlank { "Игрок" }
        connectToRoom(room = room, playerName = currentName)
    }

    suspend fun createHostRoom(
        timeControl: TimeControl,
        sideSelection: SideSelection,
        hostName: String,
        connectionType: ConnectionType = ConnectionType.WIFI_LAN
    ): Result<GameSessionState> = sessionMutex.withLock {
        teardownLocked()

        val resolvedHostName = hostName.trim().ifBlank { "Игрок" }
        val assignedHostColor = when (sideSelection) {
            SideSelection.WHITE -> PieceColor.WHITE
            SideSelection.BLACK -> PieceColor.BLACK
            SideSelection.RANDOM -> if (secureRandom.nextBoolean()) PieceColor.WHITE else PieceColor.BLACK
        }

        val config = HostRoomConfig(
            hostName = resolvedHostName,
            timeControl = timeControl,
            sideSelection = sideSelection,
            hostAssignedColor = assignedHostColor,
            connectionType = connectionType,
            requestedPort = 0
        )

        return runCatching {
            val server = LocalGameServer(
                config = config,
                chessEngine = ChesslibChessEngine(),
                timeProvider = timeProvider
            )
            val boundPort = server.start()
            val localIp = HotspotNetworkHelper.resolveHostIpv4(
                preferHotspotInterface = connectionType == ConnectionType.HOTSPOT
            ) ?: lanDiscoveryManager.resolveLocalIpv4Address()
            connectionStateManager.setLocalIpAddress(localIp)

            synchronized(engineLock) {
                mirrorEngine.reset()
                mirrorClock = ChessClock(timeControl, timeProvider)
            }

            activeServer = server
            val initialRoomInfo = server.roomInfo.value
            val initialPosition = synchronized(engineLock) { mirrorEngine.snapshot() }
            val initialClock = synchronized(engineLock) { mirrorClock.snapshot() }

            val newState = GameSessionState(
                role = SessionRole.HOST,
                connectionType = connectionType,
                roomId = config.roomId,
                gameUuid = config.gameUuid,
                roomCode = config.roomCode,
                roomSecret = config.roomSecret,
                hostAddress = localIp,
                port = boundPort,
                localPlayerUuid = config.hostUuid,
                localPlayerName = resolvedHostName,
                localColor = assignedHostColor,
                opponentName = null,
                roomInfo = initialRoomInfo,
                timeControl = timeControl,
                isGameStarted = false,
                positionSnapshot = initialPosition,
                clockSnapshot = initialClock,
                drawOfferState = DrawOfferState.NONE,
                drawOfferedBy = null,
                connectionStatus = ConnectionStatusUi.DISCONNECTED,
                lastError = null,
                lastRejectionReason = null,
                isConnecting = false
            )
            _sessionState.value = newState
            connectionStateManager.setConnectionState(
                ConnectionState.Hosting(
                    roomId = config.roomId,
                    port = boundPort,
                    mode = connectionType
                )
            )

            serverEventsJob = scope.launch {
                server.events.collect { envelope ->
                    handleAuthoritativeEnvelope(envelope)
                }
            }

            lanDiscoveryManager.registerService(
                advertisement = LanServiceAdvertisement(
                    roomId = config.roomId,
                    roomCode = config.roomCode,
                    hostName = resolvedHostName,
                    timeControlId = timeControl.id,
                    timeControlLabel = if (timeControl.isUnlimited) "Без таймера" else timeControl.label,
                    connectedPlayers = 1,
                    roomSecret = config.roomSecret,
                    port = boundPort
                ),
                onError = { errorMsg ->
                    // Room server still remains reachable by IP:port even if mDNS fails on restrictive routers
                    _sessionState.update { it.copy(lastError = errorMsg) }
                }
            )

            newState
        }.onFailure { error ->
            teardownLocked()
            val message = error.message ?: "Не удалось запустить сервер комнаты"
            connectionStateManager.setConnectionState(ConnectionState.Error(message))
            _sessionState.update { it.copy(lastError = message, isConnecting = false) }
        }
    }

    suspend fun connectToRoom(
        room: DiscoveredRoom,
        playerName: String
    ): Result<Unit> = connectByIp(
        host = room.hostAddress,
        port = room.port,
        roomSecretOrCode = room.roomSecret.ifBlank { room.roomCode },
        playerName = playerName,
        connectionType = room.connectionType
    )

    suspend fun connectFromQr(
        rawQrContent: String,
        playerName: String
    ): Result<RoomQrPayload> {
        val decoded = RoomQrCodec.decode(rawQrContent).getOrElse { error ->
            val msg = error.message ?: "Не удалось прочитать QR-код комнаты"
            _sessionState.update { it.copy(lastError = msg) }
            return Result.failure(error)
        }
        val connectResult = connectByIp(
            host = decoded.hostAddress,
            port = decoded.port,
            roomSecretOrCode = decoded.roomSecret.ifBlank { decoded.roomCode },
            playerName = playerName,
            connectionType = decoded.connectionType
        )
        return connectResult.map { decoded }
    }

    fun refreshLocalNetworkAddress() {
        val current = _sessionState.value
        val resolved = HotspotNetworkHelper.resolveHostIpv4(
            preferHotspotInterface = current.connectionType == ConnectionType.HOTSPOT
        ) ?: lanDiscoveryManager.resolveLocalIpv4Address()
        connectionStateManager.setLocalIpAddress(resolved)
        if (current.role == SessionRole.HOST && !resolved.isNullOrBlank()) {
            _sessionState.update { it.copy(hostAddress = resolved) }
        }
    }

    suspend fun connectByIp(
        host: String,
        port: Int,
        roomSecretOrCode: String,
        playerName: String,
        connectionType: ConnectionType = ConnectionType.WIFI_LAN
    ): Result<Unit> = sessionMutex.withLock {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || port !in 1..65535) {
            val error = "Укажите корректный IP-адрес и порт"
            _sessionState.update { it.copy(lastError = error) }
            return Result.failure(IllegalArgumentException(error))
        }

        teardownLocked()
        val resolvedName = playerName.trim().ifBlank { "Игрок" }
        val localUuid = UUID.randomUUID().toString()
        val secretInput = roomSecretOrCode.trim()

        _sessionState.value = GameSessionState(
            role = SessionRole.CLIENT,
            connectionType = connectionType,
            hostAddress = cleanHost,
            port = port,
            localPlayerUuid = localUuid,
            localPlayerName = resolvedName,
            roomSecret = secretInput,
            connectionStatus = ConnectionStatusUi.RECONNECTING,
            isConnecting = true,
            lastError = null
        )
        connectionStateManager.setConnectionState(ConnectionState.Connecting(cleanHost, connectionType))

        val client = WebSocketClient()
        val probedInfo = client.probeRoomHealth(cleanHost, port).getOrElse { error ->
            val msg = "Комната $cleanHost:$port недоступна: ${error.message ?: "нет ответа"}"
            connectionStateManager.setConnectionState(ConnectionState.Error(msg))
            _sessionState.value = GameSessionState(lastError = msg)
            return Result.failure(error)
        }

        val connectResult = client.connect(cleanHost, port)
        if (connectResult.isFailure) {
            val error = connectResult.exceptionOrNull()
            val msg = error?.message ?: "Не удалось подключиться к $cleanHost:$port"
            connectionStateManager.setConnectionState(ConnectionState.Error(msg))
            _sessionState.value = GameSessionState(lastError = msg)
            return Result.failure(error ?: IllegalStateException(msg))
        }

        val control = TimeControl.fromId(probedInfo.timeControlId)
        synchronized(engineLock) {
            mirrorEngine.reset()
            mirrorClock = ChessClock(control, timeProvider)
        }

        activeClient = client
        clientSequence.set(0L)

        _sessionState.update {
            it.copy(
                roomId = probedInfo.roomId,
                gameUuid = probedInfo.gameUuid,
                roomCode = probedInfo.roomCode,
                localColor = probedInfo.clientColor,
                opponentName = probedInfo.hostName,
                roomInfo = probedInfo,
                timeControl = control,
                positionSnapshot = synchronized(engineLock) { mirrorEngine.snapshot() },
                clockSnapshot = synchronized(engineLock) { mirrorClock.snapshot() },
                connectionStatus = ConnectionStatusUi.CONNECTED,
                isConnecting = false,
                lastError = null
            )
        }

        clientMessagesJob = scope.launch {
            client.incomingMessages.collect { envelope ->
                handleAuthoritativeEnvelope(envelope)
            }
        }
        clientTransportJob = scope.launch {
            client.transportState.collect { transport ->
                when (transport) {
                    ClientTransportState.DISCONNECTED,
                    ClientTransportState.ERROR -> {
                        val current = _sessionState.value
                        if (current.role == SessionRole.CLIENT) {
                            val err = client.lastError.value
                            _sessionState.update {
                                it.copy(
                                    connectionStatus = ConnectionStatusUi.DISCONNECTED,
                                    isConnecting = false,
                                    lastError = err ?: it.lastError
                                )
                            }
                            if (err != null) {
                                connectionStateManager.setConnectionState(ConnectionState.Error(err))
                            }
                        }
                    }
                    ClientTransportState.CONNECTED -> {
                        _sessionState.update {
                            it.copy(
                                connectionStatus = ConnectionStatusUi.CONNECTED,
                                isConnecting = false
                            )
                        }
                    }
                    ClientTransportState.CONNECTING -> {
                        _sessionState.update {
                            it.copy(
                                connectionStatus = ConnectionStatusUi.RECONNECTING,
                                isConnecting = true
                            )
                        }
                    }
                }
            }
        }

        val handshakeEnvelope = NetworkMessageSerializer.createEnvelope(
            gameUuid = probedInfo.gameUuid,
            senderUuid = localUuid,
            type = NetworkMessageType.PLAYER_CONNECTED,
            sequenceNumber = clientSequence.incrementAndGet(),
            roomSecret = secretInput,
            payload = PlayerConnectedPayload(
                playerUuid = localUuid,
                playerName = resolvedName,
                ready = false,
                roomSecret = secretInput
            )
        )
        val sent = client.send(handshakeEnvelope)
        if (!sent) {
            teardownLocked()
            val msg = "Не удалось отправить приветствие хосту"
            connectionStateManager.setConnectionState(ConnectionState.Error(msg))
            _sessionState.value = GameSessionState(lastError = msg)
            return Result.failure(IllegalStateException(msg))
        }

        connectionStateManager.setConnectionState(
            ConnectionState.Connected(
                roomId = probedInfo.roomId,
                peerName = probedInfo.hostName,
                mode = connectionType
            )
        )
        return Result.success(Unit)
    }

    suspend fun setLocalReady(ready: Boolean) {
        val current = _sessionState.value
        when (current.role) {
            SessionRole.HOST -> activeServer?.setHostReady(ready)
            SessionRole.CLIENT -> {
                val client = activeClient ?: return
                val envelope = NetworkMessageSerializer.createEnvelope(
                    gameUuid = current.gameUuid,
                    senderUuid = current.localPlayerUuid,
                    type = NetworkMessageType.PLAYER_CONNECTED,
                    sequenceNumber = clientSequence.incrementAndGet(),
                    roomSecret = current.roomSecret,
                    payload = PlayerConnectedPayload(
                        playerUuid = current.localPlayerUuid,
                        playerName = current.localPlayerName,
                        ready = ready,
                        roomSecret = current.roomSecret
                    )
                )
                client.send(envelope)
            }
            SessionRole.NONE -> Unit
        }
    }

    suspend fun startHostedGame(): Boolean {
        val server = activeServer ?: return false
        val started = server.startGame()
        if (started) {
            // Stop advertising once both players enter active gameplay
            lanDiscoveryManager.unregisterService()
        }
        return started
    }

    suspend fun submitMove(
        from: BoardSquare,
        to: BoardSquare,
        promotion: PieceType? = null
    ): Boolean {
        val current = _sessionState.value
        if (!current.isGameStarted) return false

        return when (current.role) {
            SessionRole.HOST -> {
                activeServer?.submitHostMove(from, to, promotion) ?: false
            }
            SessionRole.CLIENT -> {
                val client = activeClient ?: return false
                val snapshot = synchronized(engineLock) { mirrorEngine.snapshot() }
                val envelope = NetworkMessageSerializer.createEnvelope(
                    gameUuid = current.gameUuid,
                    senderUuid = current.localPlayerUuid,
                    type = NetworkMessageType.MOVE_REQUEST,
                    sequenceNumber = clientSequence.incrementAndGet(),
                    roomSecret = current.roomSecret,
                    payload = MoveRequestPayload(
                        from = from.algebraic,
                        to = to.algebraic,
                        promotion = promotion?.fenSymbol?.toString(),
                        expectedPly = snapshot.moveHistory.size,
                        expectedMoveNumber = snapshot.fullMoveNumber
                    )
                )
                client.send(envelope)
            }
            SessionRole.NONE -> false
        }
    }

    suspend fun offerDraw() {
        val current = _sessionState.value
        if (!current.isGameStarted) return
        when (current.role) {
            SessionRole.HOST -> activeServer?.submitHostDrawOffer()
            SessionRole.CLIENT -> {
                val client = activeClient ?: return
                client.send(
                    NetworkMessageSerializer.createEnvelope(
                        gameUuid = current.gameUuid,
                        senderUuid = current.localPlayerUuid,
                        type = NetworkMessageType.DRAW_OFFER,
                        sequenceNumber = clientSequence.incrementAndGet(),
                        roomSecret = current.roomSecret,
                        payload = DrawOfferPayload(offeredBy = current.localColor)
                    )
                )
            }
            SessionRole.NONE -> Unit
        }
    }

    suspend fun respondToDrawOffer(accepted: Boolean) {
        val current = _sessionState.value
        if (!current.isGameStarted) return
        when (current.role) {
            SessionRole.HOST -> activeServer?.submitHostDrawDecision(accepted)
            SessionRole.CLIENT -> {
                val client = activeClient ?: return
                client.send(
                    NetworkMessageSerializer.createEnvelope(
                        gameUuid = current.gameUuid,
                        senderUuid = current.localPlayerUuid,
                        type = if (accepted) NetworkMessageType.DRAW_ACCEPTED else NetworkMessageType.DRAW_REJECTED,
                        sequenceNumber = clientSequence.incrementAndGet(),
                        roomSecret = current.roomSecret,
                        payload = DrawDecisionPayload(decidedBy = current.localColor, accepted = accepted)
                    )
                )
            }
            SessionRole.NONE -> Unit
        }
    }

    suspend fun resign() {
        val current = _sessionState.value
        if (!current.isGameStarted) return
        when (current.role) {
            SessionRole.HOST -> activeServer?.submitHostResign()
            SessionRole.CLIENT -> {
                val client = activeClient ?: return
                client.send(
                    NetworkMessageSerializer.createEnvelope(
                        gameUuid = current.gameUuid,
                        senderUuid = current.localPlayerUuid,
                        type = NetworkMessageType.RESIGN,
                        sequenceNumber = clientSequence.incrementAndGet(),
                        roomSecret = current.roomSecret,
                        payload = ResignPayload(resigningColor = current.localColor)
                    )
                )
            }
            SessionRole.NONE -> Unit
        }
    }

    override suspend fun disconnect() {
        sessionMutex.withLock {
            teardownLocked()
            connectionStateManager.setConnectionState(ConnectionState.Idle)
            _sessionState.value = GameSessionState()
        }
    }

    fun clearError() {
        _sessionState.update { it.copy(lastError = null, lastRejectionReason = null) }
    }

    // ---- Synchronized read-only queries against authoritative mirror engine ----

    fun legalTargetsFrom(square: BoardSquare): List<LegalTarget> = synchronized(engineLock) {
        mirrorEngine.legalTargetsFrom(square)
    }

    fun isPromotionMove(from: BoardSquare, to: BoardSquare): Boolean = synchronized(engineLock) {
        mirrorEngine.isPromotionMove(from, to)
    }

    fun positionAtPly(ply: Int): ChessPositionSnapshot = synchronized(engineLock) {
        mirrorEngine.positionAtPly(ply)
    }

    fun livePositionSnapshot(): ChessPositionSnapshot = synchronized(engineLock) {
        mirrorEngine.snapshot()
    }

    fun liveClockSnapshot(): ClockSnapshot = synchronized(engineLock) {
        mirrorClock.snapshot()
    }

    fun exportPgn(whiteName: String, blackName: String): String = synchronized(engineLock) {
        mirrorEngine.exportPgn(
            PgnMetadata(
                whitePlayer = whiteName,
                blackPlayer = blackName,
                timeControl = _sessionState.value.timeControl.label
            )
        )
    }

    // ---- Internal authoritative envelope reducer ----

    private fun handleAuthoritativeEnvelope(envelope: NetworkEnvelope) {
        when (envelope.type) {
            NetworkMessageType.ROOM_INFO -> {
                val info = NetworkMessageSerializer.decodeEnvelopePayload<RoomInfoPayload>(envelope).getOrNull()
                    ?: return
                applyRoomInfo(info)
            }
            NetworkMessageType.GAME_START -> {
                val start = NetworkMessageSerializer.decodeEnvelopePayload<GameStartPayload>(envelope).getOrNull()
                    ?: return
                applyGameStart(start)
            }
            NetworkMessageType.MOVE_ACCEPTED -> {
                val accepted = NetworkMessageSerializer.decodeEnvelopePayload<MoveAcceptedPayload>(envelope).getOrNull()
                    ?: return
                applyMoveAccepted(accepted)
            }
            NetworkMessageType.MOVE_REJECTED -> {
                val rejected = NetworkMessageSerializer.decodeEnvelopePayload<MoveRejectedPayload>(envelope).getOrNull()
                    ?: return
                _sessionState.update { it.copy(lastRejectionReason = rejected.reason) }
            }
            NetworkMessageType.GAME_STATE -> {
                val statePayload = NetworkMessageSerializer.decodeEnvelopePayload<GameStatePayload>(envelope).getOrNull()
                    ?: return
                applyAuthoritativeGameState(statePayload)
            }
            NetworkMessageType.CLOCK_UPDATE -> {
                val clockUpdate = NetworkMessageSerializer.decodeEnvelopePayload<ClockUpdatePayload>(envelope).getOrNull()
                    ?: return
                val syncedClock = synchronized(engineLock) {
                    mirrorClock.syncFromAuthoritative(
                        whiteRemainingMs = clockUpdate.whiteRemainingMs,
                        blackRemainingMs = clockUpdate.blackRemainingMs,
                        activeSide = clockUpdate.activeClockColor,
                        isRunning = clockUpdate.clockRunning
                    )
                }
                _sessionState.update { it.copy(clockSnapshot = syncedClock) }
            }
            NetworkMessageType.DRAW_OFFER -> {
                val offer = NetworkMessageSerializer.decodeEnvelopePayload<DrawOfferPayload>(envelope).getOrNull()
                    ?: return
                val current = _sessionState.value
                val nextDrawState = if (offer.offeredBy == current.localColor) {
                    DrawOfferState.SENT
                } else {
                    DrawOfferState.RECEIVED
                }
                _sessionState.update {
                    it.copy(
                        drawOfferState = nextDrawState,
                        drawOfferedBy = offer.offeredBy
                    )
                }
            }
            NetworkMessageType.DRAW_ACCEPTED -> {
                _sessionState.update {
                    it.copy(drawOfferState = DrawOfferState.ACCEPTED)
                }
            }
            NetworkMessageType.DRAW_REJECTED -> {
                _sessionState.update {
                    it.copy(
                        drawOfferState = DrawOfferState.DECLINED,
                        drawOfferedBy = null
                    )
                }
            }
            NetworkMessageType.GAME_FINISHED -> {
                val finished = NetworkMessageSerializer.decodeEnvelopePayload<GameFinishedPayload>(envelope).getOrNull()
                    ?: return
                applyGameFinished(finished)
            }
            NetworkMessageType.RECONNECT_RESPONSE -> {
                val resp = NetworkMessageSerializer.decodeEnvelopePayload<ReconnectResponsePayload>(envelope).getOrNull()
                    ?: return
                if (resp.accepted) {
                    resp.roomInfo?.let(::applyRoomInfo)
                    resp.gameState?.let(::applyAuthoritativeGameState)
                } else if (!resp.reason.isNullOrBlank()) {
                    _sessionState.update { it.copy(lastError = resp.reason) }
                }
            }
            NetworkMessageType.DISCONNECT -> {
                val disconnect = NetworkMessageSerializer.decodeEnvelopePayload<DisconnectPayload>(envelope).getOrNull()
                _sessionState.update { current ->
                    current.copy(
                        connectionStatus = ConnectionStatusUi.DISCONNECTED,
                        lastError = disconnect?.reason ?: "Соединение разорвано"
                    )
                }
                val current = _sessionState.value
                if (current.role == SessionRole.HOST && !current.isGameStarted) {
                    connectionStateManager.setConnectionState(
                        ConnectionState.Hosting(current.roomId, current.port, current.connectionType)
                    )
                }
            }
            else -> Unit
        }
    }

    private fun applyRoomInfo(info: RoomInfoPayload) {
        val current = _sessionState.value
        val control = TimeControl.fromId(info.timeControlId)
        val localColor = when (current.role) {
            SessionRole.HOST -> info.hostColor
            SessionRole.CLIENT -> info.clientColor
            SessionRole.NONE -> current.localColor
        }
        val opponent = when (current.role) {
            SessionRole.HOST -> info.clientName
            SessionRole.CLIENT -> info.hostName
            SessionRole.NONE -> null
        }
        val status = when (current.role) {
            SessionRole.HOST -> if (info.clientUuid != null) {
                ConnectionStatusUi.CONNECTED
            } else {
                ConnectionStatusUi.DISCONNECTED
            }
            SessionRole.CLIENT -> ConnectionStatusUi.CONNECTED
            SessionRole.NONE -> ConnectionStatusUi.LOCAL_PASS_AND_PLAY
        }

        _sessionState.update {
            it.copy(
                roomId = info.roomId,
                gameUuid = info.gameUuid,
                roomCode = info.roomCode,
                roomInfo = info,
                timeControl = control,
                localColor = localColor,
                opponentName = opponent,
                isGameStarted = info.gameStarted || it.isGameStarted,
                connectionStatus = status,
                isConnecting = false
            )
        }

        if (current.role == SessionRole.HOST && info.clientUuid != null && info.clientName != null) {
            connectionStateManager.setConnectionState(
                ConnectionState.Connected(
                    roomId = info.roomId,
                    peerName = info.clientName,
                    mode = info.connectionType
                )
            )
        }
    }

    private fun applyGameStart(start: GameStartPayload) {
        val control = TimeControl.fromId(start.timeControlId)
        val (snap, clockSnap) = synchronized(engineLock) {
            mirrorEngine.reset()
            mirrorClock = ChessClock(control, timeProvider)
            val synced = mirrorClock.syncFromAuthoritative(
                whiteRemainingMs = start.whiteRemainingMs,
                blackRemainingMs = start.blackRemainingMs,
                activeSide = null,
                isRunning = false
            )
            mirrorEngine.snapshot() to synced
        }

        _sessionState.update { current ->
            val resolvedLocalColor = when (current.localPlayerUuid) {
                start.whitePlayerUuid -> PieceColor.WHITE
                start.blackPlayerUuid -> PieceColor.BLACK
                else -> current.localColor
            }
            val resolvedOpponentName = if (resolvedLocalColor == PieceColor.WHITE) {
                start.blackPlayerName
            } else {
                start.whitePlayerName
            }
            current.copy(
                gameUuid = start.gameUuid,
                timeControl = control,
                localColor = resolvedLocalColor,
                opponentName = resolvedOpponentName,
                isGameStarted = true,
                positionSnapshot = snap,
                clockSnapshot = clockSnap,
                drawOfferState = DrawOfferState.NONE,
                drawOfferedBy = null,
                lastAnimatedMove = null,
                lastRejectionReason = null
            )
        }
    }

    private fun applyMoveAccepted(accepted: MoveAcceptedPayload) {
        val (snap, clockSnap, committedMove) = synchronized(engineLock) {
            val currentHistorySize = mirrorEngine.moveHistory().size
            var appliedMove: EngineMove? = null
            if (accepted.plyIndex == currentHistorySize + 1) {
                val ok = mirrorEngine.applyMove(accepted.uci)
                if (ok) {
                    appliedMove = mirrorEngine.lastMove()
                }
            }
            val syncedClock = mirrorClock.syncFromAuthoritative(
                whiteRemainingMs = accepted.whiteRemainingMs,
                blackRemainingMs = accepted.blackRemainingMs,
                activeSide = accepted.activeClockColor,
                isRunning = accepted.clockRunning
            )
            Triple(mirrorEngine.snapshot(), syncedClock, appliedMove)
        }

        _sessionState.update { current ->
            current.copy(
                positionSnapshot = snap,
                clockSnapshot = clockSnap,
                drawOfferState = DrawOfferState.NONE,
                drawOfferedBy = null,
                lastAnimatedMove = committedMove ?: current.lastAnimatedMove,
                lastAnimatedMoveId = if (committedMove != null) {
                    animatedMoveCounter.incrementAndGet()
                } else {
                    current.lastAnimatedMoveId
                },
                lastRejectionReason = null
            )
        }
    }

    private fun applyAuthoritativeGameState(statePayload: GameStatePayload) {
        val (snap, clockSnap, newlyAppliedMove) = synchronized(engineLock) {
            val currentMoves = mirrorEngine.moveHistory().map { it.uci }
            var newMove: EngineMove? = null
            if (currentMoves != statePayload.uciMoves) {
                if (statePayload.uciMoves.size == currentMoves.size + 1 &&
                    statePayload.uciMoves.subList(0, currentMoves.size) == currentMoves
                ) {
                    if (mirrorEngine.applyMove(statePayload.uciMoves.last())) {
                        newMove = mirrorEngine.lastMove()
                    }
                } else {
                    mirrorEngine.reset()
                    statePayload.uciMoves.forEach { uci -> mirrorEngine.applyMove(uci) }
                    newMove = mirrorEngine.lastMove()
                }
            }

            applyExplicitTerminationLocked(
                result = statePayload.result,
                reason = statePayload.terminationReason
            )

            val syncedClock = mirrorClock.syncFromAuthoritative(
                whiteRemainingMs = statePayload.whiteRemainingMs,
                blackRemainingMs = statePayload.blackRemainingMs,
                activeSide = statePayload.activeClockColor,
                isRunning = statePayload.clockRunning
            )
            Triple(mirrorEngine.snapshot(), syncedClock, newMove)
        }

        _sessionState.update { current ->
            val drawState = when {
                statePayload.drawOfferedBy == null -> {
                    if (current.drawOfferState == DrawOfferState.DECLINED) {
                        DrawOfferState.DECLINED
                    } else {
                        DrawOfferState.NONE
                    }
                }
                statePayload.drawOfferedBy == current.localColor -> DrawOfferState.SENT
                else -> DrawOfferState.RECEIVED
            }
            current.copy(
                isGameStarted = true,
                positionSnapshot = snap,
                clockSnapshot = clockSnap,
                drawOfferState = drawState,
                drawOfferedBy = statePayload.drawOfferedBy,
                lastAnimatedMove = newlyAppliedMove ?: current.lastAnimatedMove,
                lastAnimatedMoveId = if (newlyAppliedMove != null) {
                    animatedMoveCounter.incrementAndGet()
                } else {
                    current.lastAnimatedMoveId
                }
            )
        }
    }

    private fun applyGameFinished(finished: GameFinishedPayload) {
        val (snap, clockSnap) = synchronized(engineLock) {
            applyExplicitTerminationLocked(
                result = finished.result,
                reason = finished.reason
            )
            val syncedClock = mirrorClock.syncFromAuthoritative(
                whiteRemainingMs = finished.whiteRemainingMs,
                blackRemainingMs = finished.blackRemainingMs,
                activeSide = null,
                isRunning = false
            )
            mirrorEngine.snapshot() to syncedClock
        }

        _sessionState.update { current ->
            current.copy(
                positionSnapshot = snap,
                clockSnapshot = clockSnap,
                drawOfferState = if (finished.reason == GameTerminationReason.DRAW_BY_AGREEMENT) {
                    DrawOfferState.ACCEPTED
                } else {
                    DrawOfferState.NONE
                }
            )
        }
    }

    private fun applyExplicitTerminationLocked(
        result: GameResult?,
        reason: GameTerminationReason?
    ) {
        if (result == null || reason == null) return
        if (mirrorEngine.gameOutcome() != null) return
        when (reason) {
            GameTerminationReason.DRAW_BY_AGREEMENT -> mirrorEngine.agreeDraw()
            GameTerminationReason.RESIGNATION -> {
                val resigning = if (result == GameResult.WHITE_WIN) PieceColor.BLACK else PieceColor.WHITE
                mirrorEngine.resign(resigning)
            }
            GameTerminationReason.TIMEOUT,
            GameTerminationReason.TIMEOUT_VS_INSUFFICIENT_MATERIAL -> {
                val flagged = when (result) {
                    GameResult.WHITE_WIN -> PieceColor.BLACK
                    GameResult.BLACK_WIN -> PieceColor.WHITE
                    else -> mirrorEngine.sideToMove()
                }
                mirrorEngine.timeout(flagged)
            }
            GameTerminationReason.ABORTED -> mirrorEngine.abortGame()
            else -> Unit
        }
    }

    private suspend fun teardownLocked() {
        serverEventsJob?.cancel()
        serverEventsJob = null
        clientMessagesJob?.cancel()
        clientMessagesJob = null
        clientTransportJob?.cancel()
        clientTransportJob = null

        lanDiscoveryManager.releaseAll()

        val server = activeServer
        activeServer = null
        server?.stop()

        val client = activeClient
        activeClient = null
        client?.disconnect()
    }
}
