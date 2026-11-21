package com.example.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.UUID

/**
 * Deterministic kotlinx.serialization codec for [NetworkEnvelope] and typed protocol payloads.
 */
object NetworkMessageSerializer {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodeEnvelope(envelope: NetworkEnvelope): String =
        json.encodeToString(NetworkEnvelope.serializer(), envelope)

    fun decodeEnvelope(raw: String): Result<NetworkEnvelope> = runCatching {
        json.decodeFromString(NetworkEnvelope.serializer(), raw)
    }

    fun <T> encodePayload(serializer: KSerializer<T>, payload: T): String =
        json.encodeToString(serializer, payload)

    fun <T> decodePayload(serializer: KSerializer<T>, raw: String): Result<T> = runCatching {
        json.decodeFromString(serializer, raw)
    }

    inline fun <reified T> createEnvelope(
        gameUuid: String,
        senderUuid: String,
        type: NetworkMessageType,
        sequenceNumber: Long,
        payload: T,
        roomSecret: String = "",
        messageUuid: String = UUID.randomUUID().toString()
    ): NetworkEnvelope {
        val payloadJson = json.encodeToString(serializer<T>(), payload)
        return NetworkEnvelope(
            protocolVersion = CHESS_LAN_PROTOCOL_VERSION,
            gameUuid = gameUuid,
            senderUuid = senderUuid,
            messageUuid = messageUuid,
            type = type,
            sequenceNumber = sequenceNumber,
            roomSecret = roomSecret,
            payload = payloadJson
        )
    }

    inline fun <reified T> decodeEnvelopePayload(envelope: NetworkEnvelope): Result<T> = runCatching {
        json.decodeFromString(serializer<T>(), envelope.payload)
    }

    fun isProtocolCompatible(envelope: NetworkEnvelope): Boolean =
        envelope.protocolVersion == CHESS_LAN_PROTOCOL_VERSION
}
