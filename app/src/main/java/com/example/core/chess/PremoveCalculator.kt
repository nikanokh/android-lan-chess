package com.example.core.chess

import com.example.core.ui.board.BoardPiece
import com.example.core.ui.board.BoardPremove
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.LegalTarget
import com.example.core.ui.board.PieceType
import com.example.domain.model.PieceColor
import kotlin.math.abs

/**
 * Computes Chess.com-style pseudo-legal premove candidate targets for UI selection and queueing.
 *
 * Important: This calculator NEVER decides final chess legality. When the opponent's move arrives,
 * any queued premove is executed exclusively through [ChessEngine.attemptMove], which enforces all
 * FIDE rules (pins, checks, captures, castling rights, en passant, etc.) and cancels illegal premoves.
 */
object PremoveCalculator {

    const val MAX_QUEUED_PREMOVES: Int = 8

    fun applyVirtualPremoves(
        basePieces: Map<BoardSquare, BoardPiece>,
        premoves: List<BoardPremove>
    ): Map<BoardSquare, BoardPiece> {
        if (premoves.isEmpty()) return basePieces
        val projected = LinkedHashMap(basePieces)
        for (premove in premoves) {
            val movingPiece = projected.remove(premove.from) ?: premove.piece
            val placedPiece = premove.promotion?.let { promo ->
                BoardPiece(type = promo, color = movingPiece.color)
            } ?: movingPiece
            projected[premove.to] = placedPiece

            // Relocate rook virtually when king premoves castling so the visual preview matches reality
            if (movingPiece.type == PieceType.KING && abs(premove.to.file - premove.from.file) == 2) {
                val rank = premove.from.rank
                if (premove.to.file == 6) {
                    val rookFrom = BoardSquare(7, rank)
                    val rook = projected[rookFrom]
                    if (rook != null && rook.type == PieceType.ROOK && rook.color == movingPiece.color) {
                        projected.remove(rookFrom)
                        projected[BoardSquare(5, rank)] = rook
                    }
                } else if (premove.to.file == 2) {
                    val rookFrom = BoardSquare(0, rank)
                    val rook = projected[rookFrom]
                    if (rook != null && rook.type == PieceType.ROOK && rook.color == movingPiece.color) {
                        projected.remove(rookFrom)
                        projected[BoardSquare(3, rank)] = rook
                    }
                }
            }
        }
        return projected
    }

    fun premoveTargetsFor(square: BoardSquare, piece: BoardPiece): List<LegalTarget> {
        val targets = mutableListOf<LegalTarget>()
        when (piece.type) {
            PieceType.PAWN -> addPawnTargets(square, piece.color, targets)
            PieceType.KNIGHT -> addKnightTargets(square, targets)
            PieceType.BISHOP -> addRayTargets(square, DIAGONAL_DIRS, targets)
            PieceType.ROOK -> addRayTargets(square, ORTHOGONAL_DIRS, targets)
            PieceType.QUEEN -> {
                addRayTargets(square, ORTHOGONAL_DIRS, targets)
                addRayTargets(square, DIAGONAL_DIRS, targets)
            }
            PieceType.KING -> addKingTargets(square, piece.color, targets)
        }
        return targets
    }

    fun isPremoveTargetValid(from: BoardSquare, to: BoardSquare, piece: BoardPiece): Boolean {
        if (from == to) return false
        return premoveTargetsFor(from, piece).any { it.square == to }
    }

    fun isPremovePromotion(from: BoardSquare, to: BoardSquare, piece: BoardPiece): Boolean {
        if (piece.type != PieceType.PAWN) return false
        val promoRank = if (piece.color == PieceColor.WHITE) 7 else 0
        return to.rank == promoRank && isPremoveTargetValid(from, to, piece)
    }

    private fun addPawnTargets(
        square: BoardSquare,
        color: PieceColor,
        out: MutableList<LegalTarget>
    ) {
        val dir = if (color == PieceColor.WHITE) 1 else -1
        val startRank = if (color == PieceColor.WHITE) 1 else 6
        val promoRank = if (color == PieceColor.WHITE) 7 else 0
        val nextRank = square.rank + dir
        if (nextRank !in 0..7) return

        // Single step forward
        out.add(
            LegalTarget(
                square = BoardSquare(file = square.file, rank = nextRank),
                isCapture = false,
                requiresPromotion = nextRank == promoRank
            )
        )

        // Double step from starting rank
        if (square.rank == startRank) {
            val doubleRank = square.rank + dir * 2
            if (doubleRank in 0..7) {
                out.add(
                    LegalTarget(
                        square = BoardSquare(file = square.file, rank = doubleRank),
                        isCapture = false,
                        requiresPromotion = false
                    )
                )
            }
        }

        // Diagonal capture squares (always allowed for premove in case opponent moves there)
        for (df in intArrayOf(-1, 1)) {
            val nf = square.file + df
            if (nf in 0..7) {
                out.add(
                    LegalTarget(
                        square = BoardSquare(file = nf, rank = nextRank),
                        isCapture = true,
                        requiresPromotion = nextRank == promoRank
                    )
                )
            }
        }
    }

    private fun addKnightTargets(square: BoardSquare, out: MutableList<LegalTarget>) {
        for ((df, dr) in KNIGHT_JUMPS) {
            val nf = square.file + df
            val nr = square.rank + dr
            if (nf in 0..7 && nr in 0..7) {
                out.add(LegalTarget(square = BoardSquare(nf, nr), isCapture = false))
            }
        }
    }

    private fun addRayTargets(
        square: BoardSquare,
        directions: Array<Pair<Int, Int>>,
        out: MutableList<LegalTarget>
    ) {
        for ((df, dr) in directions) {
            var nf = square.file + df
            var nr = square.rank + dr
            while (nf in 0..7 && nr in 0..7) {
                out.add(LegalTarget(square = BoardSquare(nf, nr), isCapture = false))
                nf += df
                nr += dr
            }
        }
    }

    private fun addKingTargets(
        square: BoardSquare,
        color: PieceColor,
        out: MutableList<LegalTarget>
    ) {
        for (df in -1..1) {
            for (dr in -1..1) {
                if (df == 0 && dr == 0) continue
                val nf = square.file + df
                val nr = square.rank + dr
                if (nf in 0..7 && nr in 0..7) {
                    out.add(LegalTarget(square = BoardSquare(nf, nr), isCapture = false))
                }
            }
        }
        // Potential castling squares when king is on its home square
        val homeRank = if (color == PieceColor.WHITE) 0 else 7
        if (square.file == 4 && square.rank == homeRank) {
            out.add(LegalTarget(square = BoardSquare(6, homeRank), isCapture = false))
            out.add(LegalTarget(square = BoardSquare(2, homeRank), isCapture = false))
        }
    }

    private val ORTHOGONAL_DIRS = arrayOf(
        1 to 0, -1 to 0, 0 to 1, 0 to -1
    )

    private val DIAGONAL_DIRS = arrayOf(
        1 to 1, 1 to -1, -1 to 1, -1 to -1
    )

    private val KNIGHT_JUMPS = arrayOf(
        1 to 2, 2 to 1, 2 to -1, 1 to -2,
        -1 to -2, -2 to -1, -2 to 1, -1 to 2
    )
}
