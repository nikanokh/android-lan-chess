package com.example.core.analysis

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LichessOpeningService @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 10_000
        }
    }

    private val cache = ConcurrentHashMap<String, LichessExplorerData>()

    suspend fun fetchLichessOpeningStats(fen: String): LichessExplorerData? {
        cache[fen]?.let { return it }
        return try {
            val encodedFen = URLEncoder.encode(fen, "UTF-8")
            val url = "https://explorer.lichess.ovh/masters?fen=$encodedFen&moves=6&topGames=0"
            val response = client.get(url) {
                header("Accept", "application/json")
            }
            if (response.status.value !in 200..299) return null
            val parsed = json.decodeFromString(LichessExplorerData.serializer(), response.bodyAsText())
            cache[fen] = parsed
            parsed
        } catch (_: Exception) {
            null
        }
    }
}
