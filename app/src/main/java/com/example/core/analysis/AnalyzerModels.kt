package com.example.core.analysis

import androidx.compose.runtime.Immutable
import com.example.core.ui.board.BoardSquare
import com.example.domain.model.MoveClassification
import com.example.domain.model.PieceColor
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ClassificationThresholds(
    val bestMaxCpl: Int = 20,
    val excellentMaxCpl: Int = 50,
    val goodMaxCpl: Int = 95,
    val inaccuracyMaxCpl: Int = 175,
    val mistakeMaxCpl: Int = 300
)

@Immutable
@Serializable
data class EngineConfig(
    val depth: Int = 14,
    val maxDepth: Int = 30,
    val movetimeMs: Int = 0,
    val multiPv: Int = 3,
    val hashMb: Int = 16,
    val threads: Int = 1,
    val ponder: Boolean = false
)

@Immutable
@Serializable
data class EngineLine(
    val multipv: Int,
    val depth: Int,
    val cp: Int? = null,
    val mate: Int? = null,
    val pv: List<String> = emptyList(),
    val pvSan: List<String> = emptyList(),
    val nps: Long? = null,
    val nodes: Long? = null,
    val timeMs: Long? = null
)

@Immutable
data class EngineState(
    val isReady: Boolean = false,
    val isAnalyzing: Boolean = false,
    val currentDepth: Int = 0,
    val nps: Long = 0L,
    val bestMoveUci: String? = null,
    val ponderUci: String? = null,
    val lines: List<EngineLine> = emptyList(),
    val fen: String? = null,
    val engineName: String = "Stockfish 16",
    val lastError: String? = null
)

data class SearchResult(
    val bestMove: String,
    val lines: List<EngineLine>
)

@Immutable
@Serializable
data class MoveAnalysis(
    val nodeId: String,
    val ply: Int,
    val moveNumber: Int,
    val player: PieceColor,
    val san: String,
    val uci: String,
    val fenBefore: String,
    val fenAfter: String,
    val bestMoveUci: String,
    val bestMoveSan: String,
    val evalBeforeCp: Int = 0,
    val evalAfterCp: Int = 0,
    val mateBefore: Int? = null,
    val mateAfter: Int? = null,
    val winProbBefore: Double,
    val winProbAfter: Double,
    val cpl: Int,
    val winProbLoss: Double,
    val moveAccuracy: Double,
    val category: MoveClassification,
    val bestLineUci: List<String> = emptyList(),
    val bestLineSan: List<String> = emptyList(),
    val alternativeLines: List<EngineLine> = emptyList(),
    val depth: Int = 14,
    val isTheoretical: Boolean = false,
    val isBookTransposition: Boolean = false,
    val isLastTheoretical: Boolean = false,
    val isFirstOutOfTheory: Boolean = false
)

@Immutable
@Serializable
data class UserArrow(
    val id: String,
    val from: String,
    val to: String,
    val color: String,
    val width: Float = 20f,
    val opacity: Float = 0.85f
) {
    val fromSquare: BoardSquare? get() = BoardSquare.fromAlgebraic(from)
    val toSquare: BoardSquare? get() = BoardSquare.fromAlgebraic(to)
}

@Immutable
@Serializable
data class SquareHighlight(
    val square: String,
    val color: String
) {
    val boardSquare: BoardSquare? get() = BoardSquare.fromAlgebraic(square)
}

@Serializable
data class MoveNode(
    val id: String,
    val parentId: String?,
    val ply: Int,
    val moveNumber: Int,
    val color: PieceColor,
    val san: String,
    val uci: String,
    val from: String? = null,
    val to: String? = null,
    val fen: String,
    val children: MutableList<MoveNode> = mutableListOf(),
    var isMainline: Boolean = true,
    var comment: String? = null,
    val userArrows: MutableList<UserArrow> = mutableListOf(),
    val squareHighlights: MutableList<SquareHighlight> = mutableListOf(),
    var analysis: MoveAnalysis? = null
) {
    val fromSquare: BoardSquare? get() = from?.let { BoardSquare.fromAlgebraic(it) }
    val toSquare: BoardSquare? get() = to?.let { BoardSquare.fromAlgebraic(it) }

    fun deepCopy(): MoveNode = MoveNode(
        id = id,
        parentId = parentId,
        ply = ply,
        moveNumber = moveNumber,
        color = color,
        san = san,
        uci = uci,
        from = from,
        to = to,
        fen = fen,
        children = children.map { it.deepCopy() }.toMutableList(),
        isMainline = isMainline,
        comment = comment,
        userArrows = userArrows.toMutableList(),
        squareHighlights = squareHighlights.toMutableList(),
        analysis = analysis
    )
}

@Immutable
@Serializable
data class OpeningAnalysisSummary(
    val eco: String,
    val name: String,
    val variation: String,
    val source: String,
    val version: String,
    val theoreticalMovesWhite: Int,
    val theoreticalMovesBlack: Int,
    val lastTheoreticalPly: Int,
    val lastTheoreticalMoveSan: String?,
    val firstOutOfBookPly: Int?,
    val firstOutOfBookMoveSan: String?,
    val transposedBackToBookPly: Int? = null,
    val transposedOpeningName: String? = null,
    val mainBookContinuationSan: List<String> = emptyList()
)

@Immutable
@Serializable
data class LichessExplorerMove(
    val uci: String,
    val san: String,
    val white: Long,
    val draws: Long,
    val black: Long,
    val averageRating: Int = 0
)

@Immutable
@Serializable
data class LichessOpeningTag(
    val eco: String,
    val name: String
)

@Immutable
@Serializable
data class LichessExplorerData(
    val white: Long = 0L,
    val draws: Long = 0L,
    val black: Long = 0L,
    val moves: List<LichessExplorerMove> = emptyList(),
    val opening: LichessOpeningTag? = null
)

@Serializable
enum class GamePlatform(val label: String) {
    CHESS_COM("Chess.com"),
    LICHESS("Lichess"),
    PGN("PGN"),
    LOCAL("ChessLAN"),
    ANALYSIS("Analysis")
}

@Immutable
@Serializable
data class GameMetadata(
    val id: String,
    val whiteName: String,
    val blackName: String,
    val whiteTitle: String? = null,
    val blackTitle: String? = null,
    val whiteRating: Int? = null,
    val blackRating: Int? = null,
    val whiteRatingDiff: Int? = null,
    val blackRatingDiff: Int? = null,
    val timeControl: String? = null,
    val timeClass: String? = null,
    val platform: GamePlatform = GamePlatform.PGN,
    val date: String,
    val result: String,
    val event: String? = null,
    val site: String? = null,
    val eco: String? = null,
    val openingName: String? = null
)

@Immutable
@Serializable
data class GameStatistics(
    val whiteAccuracy: Double,
    val blackAccuracy: Double,
    val whiteAvgCpl: Int,
    val blackAvgCpl: Int,
    val whiteCounts: Map<MoveClassification, Int>,
    val blackCounts: Map<MoveClassification, Int>
)

@Serializable
data class SavedAnalyzerGameRecord(
    val id: String,
    val metadata: GameMetadata,
    val pgn: String,
    val initialFen: String,
    val rootNode: MoveNode,
    val statistics: GameStatistics? = null,
    val openingSummary: OpeningAnalysisSummary? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
enum class RepertoireTimeClass(val label: String) {
    BULLET("Bullet"),
    BLITZ("Blitz"),
    RAPID("Rapid"),
    CLASSICAL("Classical")
}

@Serializable
enum class RepertoirePeriodPreset(val label: String) {
    ALL("Все партии"),
    DAYS_7("7 дней"),
    DAYS_30("30 дней"),
    DAYS_90("90 дней"),
    MONTH("По месяцу"),
    CUSTOM("Диапазон дат")
}

@Serializable
enum class RepertoireCacheMode {
    USE_CACHE,
    EXCLUDE_ANALYZED,
    FORCE_REANALYZE
}

@Serializable
enum class RepertoireSourceType {
    CHESS_COM,
    PGN,
    SAVED
}

@Serializable
enum class RepertoireOutcome {
    WIN,
    DRAW,
    LOSS
}

@Immutable
@Serializable
data class RepertoireSourceGame(
    val id: String,
    val source: RepertoireSourceType,
    val pgn: String,
    val pgnHash: String,
    val whiteName: String,
    val blackName: String,
    val whiteRating: Int? = null,
    val blackRating: Int? = null,
    val playerUsername: String,
    val playerColor: PieceColor,
    val playerRating: Int? = null,
    val opponentName: String,
    val opponentRating: Int? = null,
    val result: String,
    val outcome: RepertoireOutcome,
    val timeClass: RepertoireTimeClass,
    val timeControl: String,
    val timestamp: Long,
    val dateStr: String,
    val hasValidPgn: Boolean,
    val siteUrl: String? = null,
    val precomputedAnalyses: List<MoveAnalysis>? = null
)

@Immutable
@Serializable
data class RepertoireFilterConfig(
    val playerUsername: String = "",
    val periodPreset: RepertoirePeriodPreset = RepertoirePeriodPreset.ALL,
    val selectedMonth: String = "all",
    val dateFrom: String = "",
    val dateTo: String = "",
    val colorFilter: PieceColor? = null, // null = all
    val timeClasses: Set<RepertoireTimeClass> = RepertoireTimeClass.entries.toSet(),
    val excludeIncompletePgn: Boolean = true,
    val cacheMode: RepertoireCacheMode = RepertoireCacheMode.USE_CACHE,
    val manualSelectedGameIds: Set<String>? = null
)

@Immutable
data class SelectionPreviewSummary(
    val totalLoadedPool: Int = 0,
    val totalMatching: Int = 0,
    val whiteCount: Int = 0,
    val blackCount: Int = 0,
    val alreadyAnalyzedCount: Int = 0,
    val basicCachedCount: Int = 0,
    val needsEngineAnalysisCount: Int = 0,
    val incompleteOrMissingCount: Int = 0
)

@Immutable
@Serializable
data class OpeningMoveStep(
    val ply: Int,
    val moveNumber: Int,
    val color: PieceColor,
    val san: String,
    val uci: String,
    val fenBefore: String,
    val fenAfter: String,
    val positionKey: String,
    val isTheoretical: Boolean,
    val ecoAtStep: String? = null,
    val openingNameAtStep: String? = null,
    val variationAtStep: String? = null
)

@Serializable
enum class OpeningCacheStatus {
    BASIC,
    ENGINE_ANALYZED,
    ERROR
}

@Immutable
@Serializable
data class OpeningAnalysisCacheRecord(
    val id: String,
    val gameId: String,
    val source: RepertoireSourceType,
    val pgnHash: String,
    val normalizedMovesKey: String,
    val playerUsername: String,
    val playerColor: PieceColor,
    val opponentName: String,
    val playerRating: Int? = null,
    val opponentRating: Int? = null,
    val whiteName: String,
    val blackName: String,
    val outcome: RepertoireOutcome,
    val result: String,
    val timeClass: RepertoireTimeClass,
    val timeControl: String,
    val timestamp: Long,
    val dateStr: String,
    val eco: String,
    val openingName: String,
    val variationName: String,
    val isUndefinedOpening: Boolean,
    val theoryDepthPly: Int,
    val firstOutOfBookPly: Int?,
    val firstOutOfBookMoveSan: String?,
    val transposed: Boolean,
    val totalPlies: Int,
    val totalMoves: Int,
    val openingMoves: List<OpeningMoveStep>,
    val moveAnalyses: List<MoveAnalysis>? = null,
    val playerAccuracy: Double? = null,
    val playerAvgCpl: Int? = null,
    val algorithmVersion: String,
    val analyzedAt: Long,
    val status: OpeningCacheStatus,
    val pgn: String
)

@Immutable
@Serializable
data class VariationStatsSummary(
    val variationName: String,
    val eco: String,
    val gamesCount: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val winRate: Double,
    val drawRate: Double,
    val lossRate: Double,
    val avgAccuracy: Double? = null,
    val avgCpl: Int? = null,
    val sampleMovesSan: List<String> = emptyList(),
    val gameIds: List<String> = emptyList()
)

@Immutable
@Serializable
data class ColorSplitStats(
    val gamesCount: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val winRate: Double = 0.0,
    val drawRate: Double = 0.0,
    val lossRate: Double = 0.0,
    val avgAccuracy: Double? = null,
    val avgCpl: Int? = null,
    val avgOpponentRating: Int? = null,
    val avgMovesLength: Int = 0
)

@Immutable
@Serializable
data class ContinuationPerformanceSummary(
    val lineKey: String,
    val movesSan: List<String>,
    val ply: Int,
    val color: PieceColor,
    val gamesCount: Int,
    val winRate: Double,
    val avgAccuracy: Double? = null,
    val avgCpl: Int? = null
)

@Immutable
@Serializable
data class OpeningStatsSummary(
    val key: String,
    val eco: String,
    val openingName: String,
    val isUndefinedOpening: Boolean,
    val gamesCount: Int,
    val frequencyPercent: Double,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val winRate: Double,
    val drawRate: Double,
    val lossRate: Double,
    val avgAccuracy: Double? = null,
    val avgCpl: Int? = null,
    val avgOpponentRating: Int? = null,
    val avgMovesLength: Int = 0,
    val analyzedGamesCount: Int = 0,
    val lowSampleConfidence: Boolean = false,
    val whiteStats: ColorSplitStats,
    val blackStats: ColorSplitStats,
    val variations: List<VariationStatsSummary> = emptyList(),
    val bestContinuations: List<ContinuationPerformanceSummary> = emptyList(),
    val problematicContinuations: List<ContinuationPerformanceSummary> = emptyList(),
    val gameIds: List<String> = emptyList()
)

@Immutable
@Serializable
data class RepertoireTreeNode(
    val id: String,
    val ply: Int,
    val moveNumber: Int,
    val color: PieceColor,
    val san: String,
    val uci: String,
    val fen: String,
    val positionKey: String,
    val eco: String? = null,
    val openingName: String? = null,
    val variationName: String? = null,
    val isTheoretical: Boolean = false,
    val gamesCount: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val winRate: Double = 0.0,
    val drawRate: Double = 0.0,
    val lossRate: Double = 0.0,
    val avgAccuracy: Double? = null,
    val avgCpl: Int? = null,
    val analyzedCount: Int = 0,
    val gameIds: List<String> = emptyList(),
    val children: List<RepertoireTreeNode> = emptyList()
)

@Immutable
@Serializable
data class ProblematicGameExample(
    val gameId: String,
    val whiteName: String,
    val blackName: String,
    val playerColor: PieceColor,
    val opponentName: String,
    val dateStr: String,
    val result: String,
    val outcome: RepertoireOutcome,
    val ply: Int,
    val moveNumber: Int,
    val playedSan: String,
    val bestMoveSan: String,
    val cpl: Int,
    val winProbLoss: Double,
    val evalBeforeCp: Int,
    val evalAfterCp: Int,
    val category: MoveClassification
)

@Serializable
enum class ProblematicIssueType {
    RECURRING_MISTAKE,
    SYSTEMATIC_EVAL_DROP,
    LOST_ADVANTAGE,
    THEORETICAL_SIDELINE,
    STRONG_CONTINUATION
}

@Immutable
@Serializable
data class ProblematicContinuationItem(
    val id: String,
    val issueType: ProblematicIssueType,
    val playerColor: PieceColor,
    val eco: String,
    val openingName: String,
    val variationName: String,
    val ply: Int,
    val moveNumber: Int,
    val playedSan: String,
    val recommendedSan: String,
    val fenBefore: String,
    val fenAfter: String,
    val positionKey: String,
    val occurrences: Int,
    val errorCount: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val winRate: Double,
    val avgCpl: Int,
    val avgWinProbLoss: Double,
    val avgEvalAfterPlayerCp: Int,
    val isOutOfBook: Boolean,
    val isObjectiveError: Boolean,
    val lowSampleWarning: Boolean,
    val summaryTitle: String,
    val summaryDescription: String,
    val examples: List<ProblematicGameExample> = emptyList()
)

@Immutable
@Serializable
data class OpeningRepertoireAggregation(
    val id: String,
    val playerIdentifier: String,
    val filterSnapshot: RepertoireFilterConfig,
    val totalGames: Int,
    val whiteGames: Int,
    val blackGames: Int,
    val analyzedGames: Int,
    val openings: List<OpeningStatsSummary>,
    val whiteOpenings: List<OpeningStatsSummary>,
    val blackOpenings: List<OpeningStatsSummary>,
    val whiteTree: RepertoireTreeNode,
    val blackTree: RepertoireTreeNode,
    val problematicItems: List<ProblematicContinuationItem>,
    val createdAt: Long,
    val algorithmVersion: String
)

@Immutable
data class MoveCategoryStyle(
    val labelRu: String,
    val symbol: String,
    val colorHex: Long
)

fun MoveClassification.styleMeta(): MoveCategoryStyle = when (this) {
    MoveClassification.BRILLIANT -> MoveCategoryStyle("Бриллиантовый", "!!", 0xFF1BACA6)
    MoveClassification.GREAT -> MoveCategoryStyle("Отличный ход", "!", 0xFF5C8BB0)
    MoveClassification.BEST -> MoveCategoryStyle("Лучший ход", "★", 0xFF779954)
    MoveClassification.EXCELLENT -> MoveCategoryStyle("Прекрасный", "✓", 0xFF81B64C)
    MoveClassification.GOOD -> MoveCategoryStyle("Хороший", "✓", 0xFF95B776)
    MoveClassification.BOOK -> MoveCategoryStyle("Теория", "📖", 0xFFA88865)
    MoveClassification.FORCED -> MoveCategoryStyle("Форсированный", "→", 0xFF779954)
    MoveClassification.INACCURACY -> MoveCategoryStyle("Неточность", "?!", 0xFFF0C15C)
    MoveClassification.MISTAKE -> MoveCategoryStyle("Ошибка", "?", 0xFFE6912C)
    MoveClassification.MISSED_CHANCE -> MoveCategoryStyle("Упущенный шанс", "✖", 0xFFEE6B5E)
    MoveClassification.BLUNDER -> MoveCategoryStyle("Зевок", "??", 0xFFCA3431)
}
