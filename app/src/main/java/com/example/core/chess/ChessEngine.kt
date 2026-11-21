package com.example.core.chess

import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.LegalTarget
import com.example.core.ui.board.PieceType
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor

/**
 * Authoritative chess rules engine contract.
 * Preserves Stage 1 methods for full backwards compatibility while providing typed move,
 * history, FEN/PGN, SAN/UCI, and termination operations for GameScreen, clocks, LAN, and archive.
 */
interface ChessEngine {
    // Stage 1 core contract
    fun currentFen(): String
    fun exportPgn(): String
    fun sideToMove(): PieceColor
    fun legalMovesFrom(square: String): List<String>
    fun isMoveLegal(uci: String): Boolean
    fun applyMove(uci: String): Boolean
    fun isCheck(): Boolean
    fun gameResult(): GameResult?
    fun loadFromFen(fen: String)
    fun reset()

    // Extended Stage 2.2 authoritative contract
    fun initialFen(): String
    fun snapshot(): ChessPositionSnapshot
    fun legalMoves(): List<EngineLegalMove>
    fun legalMovesFrom(square: BoardSquare): List<EngineLegalMove>
    fun legalTargetsFrom(square: BoardSquare): List<LegalTarget>
    fun isPromotionMove(from: BoardSquare, to: BoardSquare): Boolean
    fun attemptMove(from: BoardSquare, to: BoardSquare, promotion: PieceType? = null): EngineMoveResult
    fun applySanMove(san: String): EngineMoveResult
    fun undoLastMove(): EngineMove?
    fun lastMove(): EngineMove?
    fun moveHistory(): List<EngineMove>
    fun positionAtPly(ply: Int): ChessPositionSnapshot
    fun checkedKingSquare(): BoardSquare?
    fun gameOutcome(): GameOutcome?
    fun resign(resigningColor: PieceColor): GameOutcome
    fun agreeDraw(): GameOutcome
    fun abortGame(): GameOutcome
    fun timeout(flaggedColor: PieceColor): GameOutcome
    fun hasSufficientMatingMaterial(color: PieceColor): Boolean
    fun exportPgn(metadata: PgnMetadata): String
    fun loadFromPgn(pgn: String): Boolean
}
