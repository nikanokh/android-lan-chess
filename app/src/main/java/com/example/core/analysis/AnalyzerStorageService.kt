package com.example.core.analysis

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class EvalBarPosition {
    LEFT,
    TOP
}

@Serializable
enum class EvalScoreDisplay {
    INSIDE_BAR,
    SEPARATE,
    HIDDEN
}

@Serializable
data class AnalyzerPreferences(
    val showEvalBar: Boolean = true,
    val evalBarPosition: EvalBarPosition = EvalBarPosition.LEFT,
    val evalScoreDisplay: EvalScoreDisplay = EvalScoreDisplay.INSIDE_BAR,
    val showEngineArrows: Boolean = true,
    val showUserArrows: Boolean = true,
    val engineConfig: EngineConfig = EngineConfig(),
    val thresholds: ClassificationThresholds = ClassificationThresholds(),
    val userArrowColor: String = "#F39C36",
    val engineArrowColor: String = "#F39C36",
    val secondaryEngineArrowColor: String = "#36A9E1",
    val lastChessComUsername: String = "",
    val recentChessComUsernames: List<String> = emptyList()
)

@Singleton
class AnalyzerStorageService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val storageDir: File by lazy {
        File(context.filesDir, "analyzer").apply { mkdirs() }
    }

    private val gamesFile: File get() = File(storageDir, "saved_games.json")
    private val prefsFile: File get() = File(storageDir, "analyzer_prefs.json")
    private val repertoireCacheFile: File get() = File(storageDir, "repertoire_cache.json")

    suspend fun loadPreferences(): AnalyzerPreferences = withContext(Dispatchers.IO) {
        try {
            if (!prefsFile.exists()) return@withContext AnalyzerPreferences()
            val decoded = json.decodeFromString(AnalyzerPreferences.serializer(), prefsFile.readText())
            val migratedColor = if (decoded.engineArrowColor.equals("#779954", ignoreCase = true) ||
                decoded.engineArrowColor.isBlank()
            ) {
                "#F39C36"
            } else {
                decoded.engineArrowColor
            }
            decoded.copy(engineArrowColor = migratedColor)
        } catch (_: Exception) {
            AnalyzerPreferences()
        }
    }

    suspend fun savePreferences(prefs: AnalyzerPreferences) = withContext(Dispatchers.IO) {
        try {
            prefsFile.writeText(json.encodeToString(AnalyzerPreferences.serializer(), prefs))
        } catch (_: Exception) {
        }
    }

    suspend fun loadAllSavedGames(): List<SavedAnalyzerGameRecord> = withContext(Dispatchers.IO) {
        try {
            if (!gamesFile.exists()) return@withContext emptyList()
            val list = json.decodeFromString<List<SavedAnalyzerGameRecord>>(gamesFile.readText())
            deduplicateGames(list).sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveGame(record: SavedAnalyzerGameRecord): List<SavedAnalyzerGameRecord> = withContext(Dispatchers.IO) {
        val current = loadAllSavedGames().toMutableList()
        val now = System.currentTimeMillis()
        val targetSig = dedupSignature(record)

        var earliestCreatedAt = record.createdAt
        var preservedStats = record.statistics

        val filtered = current.filter { item ->
            if (item.id == record.id || dedupSignature(item) == targetSig) {
                if (item.createdAt < earliestCreatedAt) earliestCreatedAt = item.createdAt
                if (preservedStats == null && item.statistics != null) preservedStats = item.statistics
                false
            } else {
                true
            }
        }.toMutableList()

        val normalized = record.copy(
            statistics = preservedStats,
            createdAt = earliestCreatedAt,
            updatedAt = now
        )
        filtered.add(0, normalized)
        val sorted = filtered.sortedByDescending { it.updatedAt }
        try {
            gamesFile.writeText(json.encodeToString(sorted))
        } catch (_: Exception) {
        }
        sorted
    }

    suspend fun deleteGame(id: String): List<SavedAnalyzerGameRecord> = withContext(Dispatchers.IO) {
        val remaining = loadAllSavedGames().filterNot { it.id == id }
        try {
            gamesFile.writeText(json.encodeToString(remaining))
        } catch (_: Exception) {
        }
        remaining
    }

    suspend fun loadOpeningCache(): MutableMap<String, OpeningAnalysisCacheRecord> = withContext(Dispatchers.IO) {
        try {
            if (!repertoireCacheFile.exists()) return@withContext LinkedHashMap()
            val list = json.decodeFromString<List<OpeningAnalysisCacheRecord>>(repertoireCacheFile.readText())
            list.associateBy { it.id }.toMutableMap()
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    suspend fun saveOpeningCache(cache: Map<String, OpeningAnalysisCacheRecord>) = withContext(Dispatchers.IO) {
        try {
            repertoireCacheFile.writeText(json.encodeToString(cache.values.toList()))
        } catch (_: Exception) {
        }
    }

    suspend fun clearOpeningCache() = withContext(Dispatchers.IO) {
        try {
            if (repertoireCacheFile.exists()) repertoireCacheFile.delete()
        } catch (_: Exception) {
        }
    }

    private fun dedupSignature(record: SavedAnalyzerGameRecord): String {
        val site = (record.metadata.site ?: "").trim().lowercase()
        if ((site.startsWith("http://") || site.startsWith("https://")) &&
            Regex("/\\d{5,}|lichess\\.org/[a-z0-9]{8}", RegexOption.IGNORE_CASE).containsMatchIn(site)
        ) {
            return "site:$site"
        }
        val movetext = record.pgn
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (movetext.isEmpty() || movetext == "*") {
            return "id:${record.id}"
        }
        return listOf(
            record.metadata.whiteName.trim().lowercase(),
            record.metadata.blackName.trim().lowercase(),
            record.metadata.date.trim(),
            record.metadata.platform.name,
            movetext
        ).joinToString("|")
    }

    private fun deduplicateGames(raw: List<SavedAnalyzerGameRecord>): List<SavedAnalyzerGameRecord> {
        val map = LinkedHashMap<String, SavedAnalyzerGameRecord>()
        for (item in raw.sortedByDescending { it.updatedAt }) {
            val sig = dedupSignature(item)
            val existing = map[sig]
            if (existing == null) {
                map[sig] = item
            } else if (existing.statistics == null && item.statistics != null) {
                map[sig] = existing.copy(statistics = item.statistics)
            }
        }
        return map.values.toList()
    }
}
