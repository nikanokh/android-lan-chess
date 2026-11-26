package com.example.core.analysis

import com.example.domain.model.PieceColor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Serializable
data class ChessComProfile(
    val username: String,
    val name: String? = null,
    val avatar: String? = null,
    val title: String? = null,
    val country: String? = null,
    val followers: Int? = null,
    val url: String? = null
)

@Serializable
data class ChessComArchivesResponse(
    val archives: List<String> = emptyList()
)

@Serializable
data class ChessComPlayerSide(
    val rating: Int? = null,
    val result: String,
    val username: String
)

@Serializable
data class ChessComGameItem(
    val uuid: String? = null,
    val url: String,
    val pgn: String? = null,
    @SerialName("time_control") val timeControl: String = "",
    @SerialName("end_time") val endTime: Long = 0L,
    val rated: Boolean? = null,
    @SerialName("time_class") val timeClass: String? = null,
    val rules: String? = null,
    val white: ChessComPlayerSide,
    val black: ChessComPlayerSide
)

@Serializable
private data class ChessComGamesResponse(
    val games: List<ChessComGameItem> = emptyList()
)

data class MappedChessComResult(
    val userColor: PieceColor,
    val outcome: RepertoireOutcome,
    val resultString: String
)

@Singleton
class ChessComApiService @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 20_000
        }
    }

    private data class CacheEntry(
        val body: String,
        val timestamp: Long
    )

    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun fetchProfile(username: String, forceRefresh: Boolean = false): ChessComProfile {
        val clean = username.trim().lowercase()
        if (clean.isEmpty()) throw IllegalArgumentException("Введите имя пользователя Chess.com")
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val body = fetchWithRetry(
            url = "https://api.chess.com/pub/player/$encoded",
            ttlMs = PROFILE_TTL_MS,
            forceRefresh = forceRefresh
        )
        return json.decodeFromString(ChessComProfile.serializer(), body)
    }

    suspend fun fetchArchives(username: String, forceRefresh: Boolean = false): List<String> {
        val clean = username.trim().lowercase()
        if (clean.isEmpty()) throw IllegalArgumentException("Введите имя пользователя Chess.com")
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val body = fetchWithRetry(
            url = "https://api.chess.com/pub/player/$encoded/games/archives",
            ttlMs = ARCHIVES_LIST_TTL_MS,
            forceRefresh = forceRefresh
        )
        val parsed = json.decodeFromString(ChessComArchivesResponse.serializer(), body)
        return parsed.archives.asReversed().distinct()
    }

    suspend fun fetchGamesByArchiveUrl(
        archiveUrl: String,
        forceRefresh: Boolean = false,
        isLatestArchive: Boolean = false
    ): List<ChessComGameItem> {
        val isLive = isLatestArchive || isCurrentMonthArchiveUrl(archiveUrl)
        val body = fetchWithRetry(
            url = archiveUrl,
            ttlMs = if (isLive) LATEST_ARCHIVE_TTL_MS else HISTORICAL_ARCHIVE_TTL_MS,
            forceRefresh = forceRefresh || isLive
        )
        val parsed = json.decodeFromString(ChessComGamesResponse.serializer(), body)
        val valid = parsed.games.filter { g ->
            !g.pgn.isNullOrBlank() && (g.rules == null || g.rules == "chess" || g.rules == "chess960")
        }
        return mergeAndDeduplicateGames(emptyList(), valid)
    }

    fun clearCache(username: String? = null) {
        if (username.isNullOrBlank()) {
            memoryCache.clear()
            return
        }
        val clean = URLEncoder.encode(username.trim().lowercase(), "UTF-8").lowercase()
        memoryCache.keys.removeIf { it.lowercase().contains("/player/$clean") }
    }

    private suspend fun fetchWithRetry(
        url: String,
        ttlMs: Long,
        forceRefresh: Boolean,
        retries: Int = 3
    ): String {
        if (!forceRefresh) {
            memoryCache[url]?.let { cached ->
                if (System.currentTimeMillis() - cached.timestamp < ttlMs) {
                    return cached.body
                }
            }
        }

        var lastError: Exception? = null
        for (attempt in 0..retries) {
            try {
                val response = client.get(url) {
                    header("Accept", "application/json")
                    header("User-Agent", "ChessLAN-Android/1.0")
                }
                val code = response.status.value
                if (code == 429 || code in 500..599) {
                    if (attempt < retries) {
                        delay((600.0 * 2.0.pow(attempt)).toLong())
                        continue
                    }
                    throw IllegalStateException(
                        if (code == 429) {
                            "Превышен лимит запросов к Chess.com API. Попробуйте через несколько секунд."
                        } else {
                            "Временная ошибка сервера Chess.com ($code)"
                        }
                    )
                }
                if (response.status == HttpStatusCode.NotFound) {
                    throw IllegalStateException("Пользователь или архив на Chess.com не найден")
                }
                if (code !in 200..299) {
                    throw IllegalStateException("Ошибка Chess.com API ($code)")
                }

                val text = response.bodyAsText()
                memoryCache[url] = CacheEntry(text, System.currentTimeMillis())
                return text
            } catch (ex: Exception) {
                lastError = ex
                if (ex.message?.contains("не найден") == true) throw ex
                if (attempt < retries) {
                    delay((500.0 * 2.0.pow(attempt)).toLong())
                }
            }
        }
        throw lastError ?: IllegalStateException("Ошибка соединения с Chess.com API")
    }

    companion object {
        private const val PROFILE_TTL_MS = 5 * 60 * 1000L
        private const val ARCHIVES_LIST_TTL_MS = 8 * 1000L
        private const val LATEST_ARCHIVE_TTL_MS = 5 * 1000L
        private const val HISTORICAL_ARCHIVE_TTL_MS = 30 * 60 * 1000L

        private val DRAW_RESULTS = setOf(
            "agreed",
            "repetition",
            "stalemate",
            "insufficient",
            "50move",
            "timevsinsufficient"
        )

        fun isCurrentMonthArchiveUrl(archiveUrl: String): Boolean {
            val parts = archiveUrl.split("/")
            val month = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: return true
            val year = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return true
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val curYear = cal.get(Calendar.YEAR)
            val curMonth = cal.get(Calendar.MONTH) + 1
            return year == curYear && month == curMonth
        }

        fun gameKey(game: ChessComGameItem): String = when {
            !game.uuid.isNullOrBlank() -> "uuid:${game.uuid}"
            game.url.isNotBlank() -> "url:${game.url}"
            else -> "fallback:${game.white.username.lowercase()}_${game.black.username.lowercase()}_${game.endTime}"
        }

        fun mergeAndDeduplicateGames(
            existing: List<ChessComGameItem>,
            incoming: List<ChessComGameItem>
        ): List<ChessComGameItem> {
            val map = LinkedHashMap<String, ChessComGameItem>(existing.size + incoming.size)
            for (g in existing) map[gameKey(g)] = g
            for (g in incoming) map[gameKey(g)] = g
            return map.values.sortedByDescending { it.endTime }
        }

        fun mapResult(game: ChessComGameItem, targetUsername: String): MappedChessComResult {
            val isWhite = game.white.username.equals(targetUsername.trim(), ignoreCase = true)
            val side = if (isWhite) game.white else game.black
            val opp = if (isWhite) game.black else game.white

            val (outcome, resultString) = when {
                side.result == "win" -> RepertoireOutcome.WIN to (if (isWhite) "1-0" else "0-1")
                side.result in DRAW_RESULTS || opp.result in DRAW_RESULTS ->
                    RepertoireOutcome.DRAW to "1/2-1/2"
                else -> RepertoireOutcome.LOSS to (if (isWhite) "0-1" else "1-0")
            }

            return MappedChessComResult(
                userColor = if (isWhite) PieceColor.WHITE else PieceColor.BLACK,
                outcome = outcome,
                resultString = resultString
            )
        }
    }
}
