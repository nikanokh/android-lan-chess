package com.example.core.chess

import kotlinx.coroutines.flow.Flow

data class AnalysisLine(
    val rank: Int,
    val scoreCentipawns: Int?,
    val mateInMoves: Int?,
    val bestMoveUci: String,
    val principalVariation: List<String>,
    val depth: Int
)

data class PositionEvaluation(
    val fen: String,
    val depth: Int,
    val lines: List<AnalysisLine>
)

/**
 * Architectural contract for connecting an external/local UCI analysis engine (e.g., Stockfish)
 * without coupling engine binaries or evaluation logic to Compose UI layers.
 */
interface ChessAnalysisEngine {
    suspend fun initialize(): Boolean
    fun analyzePosition(fen: String, maxDepth: Int = 18, multiPv: Int = 3): Flow<PositionEvaluation>
    suspend fun stopAnalysis()
    suspend fun shutdown()
}
