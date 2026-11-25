package com.example.domain.model

import androidx.compose.runtime.Immutable
import com.example.core.network.ConnectionType
import kotlinx.serialization.Serializable

@Serializable
enum class PieceColor {
    WHITE,
    BLACK
}

@Serializable
enum class SideSelection {
    WHITE,
    RANDOM,
    BLACK
}

@Serializable
enum class GameResult {
    WHITE_WIN,
    BLACK_WIN,
    DRAW,
    IN_PROGRESS,
    ABORTED
}

enum class TimeControlCategory {
    UNLIMITED,
    BULLET,
    BLITZ,
    RAPID,
    CLASSICAL
}

@Immutable
@Serializable
data class TimeControl(
    val id: String,
    val label: String,
    val initialSeconds: Long,
    val incrementSeconds: Long,
    val category: TimeControlCategory
) {
    val isUnlimited: Boolean
        get() = category == TimeControlCategory.UNLIMITED

    companion object {
        val UNLIMITED = TimeControl("unlimited", "∞", 0L, 0L, TimeControlCategory.UNLIMITED)
        val BULLET_1_0 = TimeControl("1+0", "1+0", 60L, 0L, TimeControlCategory.BULLET)
        val BULLET_1_1 = TimeControl("1+1", "1+1", 60L, 1L, TimeControlCategory.BULLET)
        val BULLET_2_1 = TimeControl("2+1", "2+1", 120L, 1L, TimeControlCategory.BULLET)
        val BLITZ_3_0 = TimeControl("3+0", "3+0", 180L, 0L, TimeControlCategory.BLITZ)
        val BLITZ_3_2 = TimeControl("3+2", "3+2", 180L, 2L, TimeControlCategory.BLITZ)
        val BLITZ_5_0 = TimeControl("5+0", "5+0", 300L, 0L, TimeControlCategory.BLITZ)
        val BLITZ_5_3 = TimeControl("5+3", "5+3", 300L, 3L, TimeControlCategory.BLITZ)
        val RAPID_10_0 = TimeControl("10+0", "10+0", 600L, 0L, TimeControlCategory.RAPID)
        val RAPID_10_5 = TimeControl("10+5", "10+5", 600L, 5L, TimeControlCategory.RAPID)
        val RAPID_15_10 = TimeControl("15+10", "15+10", 900L, 10L, TimeControlCategory.RAPID)
        val CLASSICAL_30_0 = TimeControl("30+0", "30+0", 1800L, 0L, TimeControlCategory.CLASSICAL)
        val CLASSICAL_30_20 = TimeControl("30+20", "30+20", 1800L, 20L, TimeControlCategory.CLASSICAL)
        val CLASSICAL_45_15 = TimeControl("45+15", "45+15", 2700L, 15L, TimeControlCategory.CLASSICAL)
        val CLASSICAL_60_0 = TimeControl("60+0", "60+0", 3600L, 0L, TimeControlCategory.CLASSICAL)
        val CLASSICAL_60_30 = TimeControl("60+30", "60+30", 3600L, 30L, TimeControlCategory.CLASSICAL)

        val BulletPresets: List<TimeControl> = listOf(BULLET_1_0, BULLET_1_1, BULLET_2_1)
        val BlitzPresets: List<TimeControl> = listOf(BLITZ_3_0, BLITZ_3_2, BLITZ_5_0, BLITZ_5_3)
        val RapidPresets: List<TimeControl> = listOf(RAPID_10_0, RAPID_10_5, RAPID_15_10)
        val ClassicalPresets: List<TimeControl> = listOf(
            CLASSICAL_30_0,
            CLASSICAL_30_20,
            CLASSICAL_45_15,
            CLASSICAL_60_0,
            CLASSICAL_60_30,
            UNLIMITED
        )

        val AllPresets: List<TimeControl> = BulletPresets + BlitzPresets + RapidPresets + ClassicalPresets

        fun custom(minutes: Int, incrementSeconds: Int): TimeControl {
            val safeMinutes = minutes.coerceIn(1, 180)
            val safeInc = incrementSeconds.coerceIn(0, 60)
            val id = "$safeMinutes+$safeInc"
            AllPresets.firstOrNull { it.id == id }?.let { return it }
            val initialSec = safeMinutes * 60L
            val estimatedMinutes = safeMinutes + (40.0 * safeInc / 60.0)
            val category = when {
                estimatedMinutes < 3.0 -> TimeControlCategory.BULLET
                estimatedMinutes < 10.0 -> TimeControlCategory.BLITZ
                estimatedMinutes < 30.0 -> TimeControlCategory.RAPID
                else -> TimeControlCategory.CLASSICAL
            }
            return TimeControl(
                id = id,
                label = id,
                initialSeconds = initialSec,
                incrementSeconds = safeInc.toLong(),
                category = category
            )
        }

        fun fromId(id: String): TimeControl {
            val trimmed = id.trim()
            if (trimmed.equals("unlimited", ignoreCase = true) || trimmed == "∞") {
                return UNLIMITED
            }
            AllPresets.firstOrNull { it.id == trimmed }?.let { return it }
            val parts = trimmed.split("+")
            if (parts.size == 2) {
                val minutes = parts[0].trim().toIntOrNull()
                val increment = parts[1].trim().toIntOrNull()
                if (minutes != null && increment != null && minutes in 1..180 && increment in 0..60) {
                    return custom(minutes, increment)
                }
            }
            return RAPID_10_0
        }
    }
}

enum class AppThemeMode {
    DARK,
    LIGHT
}

enum class BoardTheme(val displayName: String) {
    CLASSIC_GREEN("Классическая зелёная"),
    WALNUT_WOOD("Ореховое дерево"),
    SLATE_TOURNAMENT("Турнирный сланец"),
    OCEAN_BLUE("Глубокий синий")
}

enum class PieceSet(val displayName: String) {
    NEO("Neo (Классика)"),
    ALPHA("Alpha"),
    TOURNAMENT("Турнирный")
}

enum class MoveClassification(val title: String) {
    BRILLIANT("Блестящий"),
    GREAT("Отличный"),
    BEST("Лучший"),
    EXCELLENT("Прекрасный"),
    GOOD("Хороший"),
    BOOK("Книжный"),
    FORCED("Вынужденный"),
    INACCURACY("Неточность"),
    MISTAKE("Ошибка"),
    MISSED_CHANCE("Упущенный шанс"),
    BLUNDER("Зевок")
}

@Immutable
data class AppSettings(
    val playerName: String = "Игрок",
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val boardTheme: BoardTheme = BoardTheme.CLASSIC_GREEN,
    val pieceSet: PieceSet = PieceSet.NEO,
    val soundEnabled: Boolean = true,
    val animationEnabled: Boolean = true,
    val showCoordinates: Boolean = true,
    val highlightLegalMoves: Boolean = true,
    val highlightLastMove: Boolean = true,
    val confirmResign: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val premovesEnabled: Boolean = false
)

@Immutable
data class GameSummary(
    val id: Long,
    val gameUuid: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val whitePlayer: String,
    val blackPlayer: String,
    val result: GameResult,
    val timeControlLabel: String,
    val moveCount: Int,
    val connectionType: ConnectionType,
    val pgn: String
)

data class CompletedMoveRecord(
    val moveNumber: Int,
    val color: PieceColor,
    val san: String,
    val uci: String,
    val fenAfter: String,
    val timestamp: Long
)

data class CompletedGameRecord(
    val gameUuid: String,
    val createdAt: Long,
    val finishedAt: Long,
    val whitePlayer: String,
    val blackPlayer: String,
    val result: GameResult,
    val timeControlLabel: String,
    val initialFen: String,
    val finalFen: String,
    val pgn: String,
    val connectionType: ConnectionType,
    val moves: List<CompletedMoveRecord>
)
