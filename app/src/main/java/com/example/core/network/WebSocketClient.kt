package com.example.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.withTimeout

enum class ClientTransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Ktor CIO WebSocket client connecting to a Host's [LocalGameServer] over Wi-Fi LAN, Hotspot, or Wi-Fi Direct.
 */
class WebSocketClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    private var httpClient: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null

    private val _transportState = MutableStateFlow(ClientTransportState.DISCONNECTED)
    val transportState: StateFlow<ClientTransportState> = _transportState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<NetworkEnvelope>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<NetworkEnvelope> = _incomingMessages.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun probeRoomHealth(host: String, port: Int): Result<RoomInfoPayload> = runCatching {
        val client = HttpClient(CIO) {
            engine {
                requestTimeout = CONNECT_TIMEOUT_MS
            }
        }
        try {
            val response = withTimeout(CONNECT_TIMEOUT_MS) {
                client.get("http://$host:$port/health")
            }
            check(response.status.isSuccess()) { "Сервер вернул статус ${response.status.value}" }
            NetworkMessageSerializer.decodePayload(RoomInfoPayload.serializer(), response.bodyAsText()).getOrThrow()
        } finally {
            client.close()
        }
    }

    suspend fun connect(host: String, port: Int): Result<Unit> {
        disconnect()
        _transportState.value = ClientTransportState.CONNECTING
        _lastError.value = null

        return runCatching {
            val client = HttpClient(CIO) {
                install(WebSockets)
                engine {
                    requestTimeout = 0 // Keep long-lived WebSocket open
                }
            }
            val wsSession = withTimeout(CONNECT_TIMEOUT_MS) {
                client.webSocketSession(
                    method = HttpMethod.Get,
                    host = host,
                    port = port,
                    path = "/chess"
                )
            }
            httpClient = client
            session = wsSession
            _transportState.value = ClientTransportState.CONNECTED

            readerJob = scope.launch {
                try {
                    for (frame in wsSession.incoming) {
                        if (frame is Frame.Text) {
                            val envelope = NetworkMessageSerializer.decodeEnvelope(frame.readText()).getOrNull()
                            if (envelope != null && NetworkMessageSerializer.isProtocolCompatible(envelope)) {
                                _incomingMessages.emit(envelope)
                            }
                        }
                    }
                } catch (ex: Exception) {
                    if (isActive) {
                        _lastError.value = ex.message ?: "Соединение потеряно"
                        _transportState.value = ClientTransportState.ERROR
                    }
                } finally {
                    if (_transportState.value == ClientTransportState.CONNECTED) {
                        _transportState.value = ClientTransportState.DISCONNECTED
                    }
                }
            }
        }.onFailure { error ->
            _lastError.value = error.message ?: "Не удалось подключиться к $host:$port"
            _transportState.value = ClientTransportState.ERROR
            cleanupClientOnly()
        }
    }

    suspend fun send(envelope: NetworkEnvelope): Boolean = sendMutex.withLock {
        val activeSession = session ?: return false
        return runCatching {
            activeSession.send(Frame.Text(NetworkMessageSerializer.encodeEnvelope(envelope)))
            true
        }.getOrElse { error ->
            _lastError.value = error.message ?: "Ошибка отправки сообщения"
            false
        }
    }

    suspend fun disconnect(reason: String = "Client disconnected") {
        readerJob?.cancel()
        readerJob = null
        val activeSession = session
        session = null
        if (activeSession != null) {
            runCatching {
                activeSession.close(CloseReason(CloseReason.Codes.NORMAL, reason))
            }
        }
        cleanupClientOnly()
        _transportState.value = ClientTransportState.DISCONNECTED
    }

    private fun cleanupClientOnly() {
        runCatching { httpClient?.close() }
        httpClient = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 6_000L
    }
}
