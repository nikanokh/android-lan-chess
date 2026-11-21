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
        val BULLET_2_1 = TimeControl("2+1", "2+1", 120L, 1L, TimeControlCategory.BULLET)
        val BLITZ_3_0 = TimeControl("3+0", "3+0", 180L, 0L, TimeControlCategory.BLITZ)
        val BLITZ_3_2 = TimeControl("3+2", "3+2", 180L, 2L, TimeControlCategory.BLITZ)
        val BLITZ_5_0 = TimeControl("5+0", "5+0", 300L, 0L, TimeControlCategory.BLITZ)
        val BLITZ_5_3 = TimeControl("5+3", "5+3", 300L, 3L, TimeControlCategory.BLITZ)
        val RAPID_10_0 = TimeControl("10+0", "10+0", 600L, 0L, TimeControlCategory.RAPID)
        val RAPID_10_5 = TimeControl("10+5", "10+5", 600L, 5L, TimeControlCategory.RAPID)
        val RAPID_15_10 = TimeControl("15+10", "15+10", 900L, 10L, TimeControlCategory.RAPID)
        val CLASSICAL_30_0 = TimeControl("30+0", "30+0", 1800L, 0L, TimeControlCategory.CLASSICAL)

        val AllPresets: List<TimeControl> = listOf(
            UNLIMITED,
            BULLET_1_0,
            BULLET_2_1,
            BLITZ_3_0,
            BLITZ_3_2,
            BLITZ_5_0,
            BLITZ_5_3,
            RAPID_10_0,
            RAPID_10_5,
            RAPID_15_10,
            CLASSICAL_30_0
        )

        fun fromId(id: String): TimeControl =
            AllPresets.firstOrNull { it.id == id } ?: RAPID_10_0
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
    val vibrationEnabled: Boolean = true
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
