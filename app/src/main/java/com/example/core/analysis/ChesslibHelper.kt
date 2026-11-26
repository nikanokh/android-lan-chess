package com.example.core.analysis

import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.FenBoardParser
import com.example.core.ui.board.PieceType
import com.example.domain.model.PieceColor
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList

data class AppliedChessMove(
    val from: String,
    val to: String,
    val promotion: Char?,
    val uci: String,
    val san: String,
    val color: PieceColor,
    val movedPiece: PieceType,
    val capturedPiece: PieceType?,
    val fenAfter: String,
    val isEnPassant: Boolean
)

data class LegalMoveVerbose(
    val from: String,
    val to: String,
    val promotion: Char?,
    val uci: String,
    val san: String,
    val piece: PieceType,
    val captured: PieceType?,
    val isEnPassant: Boolean,
    val move: Move
)

sealed interface TerminalBoardOutcome {
    data class Checkmate(val winner: PieceColor) : TerminalBoardOutcome
    data object Draw : TerminalBoardOutcome

    val scoreLabel: String
        get() = when (this) {
            is Checkmate -> if (winner == PieceColor.WHITE) "1-0" else "0-1"
            is Draw -> "1/2-1/2"
        }
}

object ChesslibHelper {

    fun parseBoard(fen: String = FenBoardParser.INITIAL_FEN): Board {
        val board = Board()
        board.loadFromFen(fen.trim())
        return board
    }

    fun sideToMove(fen: String): PieceColor {
        val parts = fen.trim().split(Regex("\\s+"))
        return if (parts.getOrNull(1) == "b") PieceColor.BLACK else PieceColor.WHITE
    }

    fun isGameOver(board: Board): Boolean =
        board.isMated || board.isStaleMate || board.isInsufficientMaterial || board.halfMoveCounter >= 100 || board.isRepetition

    fun terminalOutcomeFromFen(fen: String?): TerminalBoardOutcome? {
        if (fen.isNullOrBlank()) return null
        return try {
            val board = parseBoard(fen)
            when {
                board.isMated -> {
                    val winner = if (board.sideToMove == Side.WHITE) PieceColor.BLACK else PieceColor.WHITE
                    TerminalBoardOutcome.Checkmate(winner)
                }
                board.isStaleMate || board.isInsufficientMaterial || board.halfMoveCounter >= 100 || board.isRepetition ->
                    TerminalBoardOutcome.Draw
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun computeSan(board: Board, move: Move): String {
        return try {
            val list = MoveList(board.fen)
            list.add(move)
            list.toSanArray().firstOrNull()?.trim() ?: move.toString()
        } catch (_: Exception) {
            move.toString()
        }
    }

    fun legalMovesVerbose(board: Board): List<LegalMoveVerbose> {
        if (isGameOver(board)) return emptyList()
        val legal = board.legalMoves()
        if (legal.isEmpty()) return emptyList()
        return legal.map { move ->
            val fromStr = move.from.value().lowercase()
            val toStr = move.to.value().lowercase()
            val moving = board.getPiece(move.from).toPieceType() ?: PieceType.PAWN
            val targetPiece = board.getPiece(move.to).toPieceType()
            val isEp = moving == PieceType.PAWN && fromStr[0] != toStr[0] && targetPiece == null
            val captured = when {
                isEp -> PieceType.PAWN
                else -> targetPiece
            }
            val promo = move.promotion.toPieceType()?.fenSymbol
            LegalMoveVerbose(
                from = fromStr,
                to = toStr,
                promotion = promo,
                uci = move.toString().lowercase(),
                san = computeSan(board, move),
                piece = moving,
                captured = captured,
                isEnPassant = isEp,
                move = move
            )
        }
    }

    fun applyUci(board: Board, uci: String): AppliedChessMove? {
        val normalized = uci.trim().lowercase()
        if (normalized.length !in 4..5) return null
        val match = board.legalMoves().firstOrNull { it.toString().lowercase() == normalized }
            ?: return null
        return executeLegalMove(board, match)
    }

    fun applyMove(
        board: Board,
        from: BoardSquare,
        to: BoardSquare,
        promotion: PieceType? = null
    ): AppliedChessMove? {
        val fromSq = Square.fromValue(from.algebraic.uppercase())
        val toSq = Square.fromValue(to.algebraic.uppercase())
        val candidates = board.legalMoves().filter { it.from == fromSq && it.to == toSq }
        if (candidates.isEmpty()) return null

        val hasPromo = candidates.any { it.promotion != Piece.NONE }
        val chosen = if (hasPromo) {
            val targetType = promotion ?: PieceType.QUEEN
            candidates.firstOrNull { it.promotion.toPieceType() == targetType } ?: candidates.first()
        } else {
            candidates.first()
        }
        return executeLegalMove(board, chosen)
    }

    fun applySan(board: Board, rawSan: String): AppliedChessMove? {
        val trimmed = rawSan.trim()
        if (trimmed.isEmpty()) return null

        // Normalize castling 0-0 / 0-0-0 -> O-O / O-O-O
        val normalizedSan = trimmed
            .replace("0-0-0", "O-O-O")
            .replace("0-0", "O-O")

        // Fast path: chesslib MoveList SAN parser
        try {
            val probe = MoveList(board.fen)
            probe.loadFromSan(normalizedSan)
            val parsed = probe.firstOrNull()
            if (parsed != null) {
                val legal = board.legalMoves().firstOrNull {
                    it.from == parsed.from && it.to == parsed.to && it.promotion == parsed.promotion
                }
                if (legal != null) {
                    return executeLegalMove(board, legal)
                }
            }
        } catch (_: Exception) {
            // Fall through to tolerant legal-move SAN matching
        }

        // Fallback: compare stripped SAN (without check/mate/annotation suffixes) against legal moves
        val strippedInput = normalizedSan.replace(Regex("[+#!?]+$"), "")
        for (legal in board.legalMoves()) {
            val san = computeSan(board, legal)
            if (san == normalizedSan || san.replace(Regex("[+#]+$"), "") == strippedInput) {
                return executeLegalMove(board, legal)
            }
        }
        return null
    }

    private fun executeLegalMove(board: Board, move: Move): AppliedChessMove {
        val movingSide = board.sideToMove.toPieceColor()
        val movingPiece = board.getPiece(move.from).toPieceType() ?: PieceType.PAWN
        val targetPiece = board.getPiece(move.to).toPieceType()
        val fromStr = move.from.value().lowercase()
        val toStr = move.to.value().lowercase()
        val isEp = movingPiece == PieceType.PAWN && fromStr[0] != toStr[0] && targetPiece == null
        val captured = if (isEp) PieceType.PAWN else targetPiece
        val promo = move.promotion.toPieceType()?.fenSymbol
        val san = computeSan(board, move)

        board.doMove(move, false)

        return AppliedChessMove(
            from = fromStr,
            to = toStr,
            promotion = promo,
            uci = move.toString().lowercase(),
            san = san,
            color = movingSide,
            movedPiece = movingPiece,
            capturedPiece = captured,
            fenAfter = board.fen,
            isEnPassant = isEp
        )
    }

    fun uciLineToSan(fen: String, uciMoves: List<String>, maxPlies: Int = 8): List<String> {
        if (uciMoves.isEmpty() || maxPlies <= 0) return emptyList()
        val sanMoves = mutableListOf<String>()
        try {
            val board = parseBoard(fen)
            val limit = minOf(uciMoves.size, maxPlies)
            for (i in 0 until limit) {
                val uci = uciMoves[i]
                val applied = applyUci(board, uci) ?: break
                sanMoves.add(applied.san)
            }
        } catch (_: Exception) {
            // Stop on invalid FEN or move
        }
        return sanMoves
    }

    fun Side.toPieceColor(): PieceColor =
        if (this == Side.WHITE) PieceColor.WHITE else PieceColor.BLACK

    fun Piece.toPieceType(): PieceType? = when (this) {
        Piece.WHITE_PAWN, Piece.BLACK_PAWN -> PieceType.PAWN
        Piece.WHITE_KNIGHT, Piece.BLACK_KNIGHT -> PieceType.KNIGHT
        Piece.WHITE_BISHOP, Piece.BLACK_BISHOP -> PieceType.BISHOP
        Piece.WHITE_ROOK, Piece.BLACK_ROOK -> PieceType.ROOK
        Piece.WHITE_QUEEN, Piece.BLACK_QUEEN -> PieceType.QUEEN
        Piece.WHITE_KING, Piece.BLACK_KING -> PieceType.KING
        Piece.NONE -> null
    }

    fun Piece.toPieceColor(): PieceColor? = when (this) {
        Piece.WHITE_PAWN, Piece.WHITE_KNIGHT, Piece.WHITE_BISHOP,
        Piece.WHITE_ROOK, Piece.WHITE_QUEEN, Piece.WHITE_KING -> PieceColor.WHITE
        Piece.BLACK_PAWN, Piece.BLACK_KNIGHT, Piece.BLACK_BISHOP,
        Piece.BLACK_ROOK, Piece.BLACK_QUEEN, Piece.BLACK_KING -> PieceColor.BLACK
        Piece.NONE -> null
    }
}
