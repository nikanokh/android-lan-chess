package com.example.core.chess

import com.example.core.ui.board.BoardPiece
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.FenBoardParser
import com.example.core.ui.board.LegalTarget
import com.example.core.ui.board.PieceType
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import kotlin.math.abs

/**
 * Authoritative ChessEngine backed by bhlangonijr/chesslib (Apache 2.0).
 *
 * All chess rules (legal move generation, pins, castling, en passant, promotions,
 * check/checkmate/stalemate, 50-move rule, threefold repetition, insufficient material,
 * FEN/SAN/UCI/PGN) are delegated to chesslib rather than custom heuristics.
 */
class ChesslibChessEngine : ChessEngine {

    private val lock = Any()
    private var board = Board()
    private var startFen: String = FenBoardParser.INITIAL_FEN
    private var moveList = MoveList(startFen)
    private val history = mutableListOf<EngineMove>()
    private var manualOutcome: GameOutcome? = null

    override fun currentFen(): String = synchronized(lock) { board.fen }

    override fun initialFen(): String = synchronized(lock) { startFen }

    override fun exportPgn(): String = exportPgn(PgnMetadata())

    override fun exportPgn(metadata: PgnMetadata): String = synchronized(lock) {
        val outcome = computeOutcomeLocked()
        val resultTag = when (outcome?.result) {
            GameResult.WHITE_WIN -> "1-0"
            GameResult.BLACK_WIN -> "0-1"
            GameResult.DRAW -> "1/2-1/2"
            else -> "*"
        }

        buildString {
            appendLine("[Event \"${escapePgnHeader(metadata.event)}\"]")
            appendLine("[Site \"${escapePgnHeader(metadata.site)}\"]")
            appendLine("[Date \"${escapePgnHeader(metadata.date)}\"]")
            appendLine("[Round \"${escapePgnHeader(metadata.round)}\"]")
            appendLine("[White \"${escapePgnHeader(metadata.whitePlayer)}\"]")
            appendLine("[Black \"${escapePgnHeader(metadata.blackPlayer)}\"]")
            appendLine("[Result \"$resultTag\"]")
            if (metadata.timeControl.isNotBlank() && metadata.timeControl != "-") {
                appendLine("[TimeControl \"${escapePgnHeader(metadata.timeControl)}\"]")
            }
            if (startFen != FenBoardParser.INITIAL_FEN) {
                appendLine("[SetUp \"1\"]")
                appendLine("[FEN \"$startFen\"]")
            }
            appendLine()

            if (history.isEmpty()) {
                append(resultTag)
            } else {
                val tokens = mutableListOf<String>()
                for (index in history.indices) {
                    val move = history[index]
                    if (move.color == PieceColor.WHITE) {
                        tokens.add("${move.moveNumber}.")
                    } else if (index == 0) {
                        tokens.add("${move.moveNumber}...")
                    }
                    tokens.add(move.san)
                }
                tokens.add(resultTag)
                append(tokens.joinToString(" "))
            }
        }
    }

    override fun sideToMove(): PieceColor = synchronized(lock) { board.sideToMove.toPieceColor() }

    override fun legalMovesFrom(square: String): List<String> = synchronized(lock) {
        val boardSquare = BoardSquare.fromAlgebraic(square) ?: return emptyList()
        legalMovesFromLocked(boardSquare).map { it.uci }
    }

    override fun legalMoves(): List<EngineLegalMove> = synchronized(lock) {
        if (isGameOverLocked()) return emptyList()
        board.legalMoves().map { move -> move.toEngineLegalMove(board) }
    }

    override fun legalMovesFrom(square: BoardSquare): List<EngineLegalMove> = synchronized(lock) {
        legalMovesFromLocked(square)
    }

    override fun legalTargetsFrom(square: BoardSquare): List<LegalTarget> = synchronized(lock) {
        val moves = legalMovesFromLocked(square)
        if (moves.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<BoardSquare, MutableList<EngineLegalMove>>()
        for (move in moves) {
            grouped.getOrPut(move.to) { mutableListOf() }.add(move)
        }
        grouped.map { (targetSquare, targetMoves) ->
            LegalTarget(
                square = targetSquare,
                isCapture = targetMoves.any { it.isCapture },
                requiresPromotion = targetMoves.any { it.promotion != null }
            )
        }
    }

    override fun isPromotionMove(from: BoardSquare, to: BoardSquare): Boolean = synchronized(lock) {
        if (isGameOverLocked()) return false
        val fromSq = from.toChesslibSquare()
        val toSq = to.toChesslibSquare()
        board.legalMoves().any { it.from == fromSq && it.to == toSq && it.promotion != Piece.NONE }
    }

    override fun isMoveLegal(uci: String): Boolean = synchronized(lock) {
        if (isGameOverLocked()) return false
        findLegalMoveByUciLocked(uci) != null
    }

    override fun applyMove(uci: String): Boolean = synchronized(lock) {
        if (isGameOverLocked()) return false
        val legalMove = findLegalMoveByUciLocked(uci) ?: return false
        executeLegalMoveLocked(legalMove)
        true
    }

    override fun attemptMove(
        from: BoardSquare,
        to: BoardSquare,
        promotion: PieceType?
    ): EngineMoveResult = synchronized(lock) {
        if (isGameOverLocked()) {
            return EngineMoveResult.Illegal("Game is already finished")
        }
        val fromSq = from.toChesslibSquare()
        val toSq = to.toChesslibSquare()
        val matching = board.legalMoves().filter { it.from == fromSq && it.to == toSq }
        if (matching.isEmpty()) {
            return EngineMoveResult.Illegal("Illegal move ${from.algebraic}${to.algebraic}")
        }

        val hasPromotion = matching.any { it.promotion != Piece.NONE }
        if (hasPromotion && promotion == null) {
            return EngineMoveResult.RequiresPromotion(
                from = from,
                to = to,
                color = board.sideToMove.toPieceColor()
            )
        }

        val chosenMove = if (hasPromotion && promotion != null) {
            val expectedPiece = promotion.toChesslibPiece(board.sideToMove)
            matching.firstOrNull { it.promotion == expectedPiece }
                ?: return EngineMoveResult.Illegal("Invalid promotion piece $promotion")
        } else {
            matching.first()
        }

        val engineMove = executeLegalMoveLocked(chosenMove)
        EngineMoveResult.Success(move = engineMove, snapshot = buildSnapshotLocked())
    }

    override fun applySanMove(san: String): EngineMoveResult = synchronized(lock) {
        if (isGameOverLocked()) {
            return EngineMoveResult.Illegal("Game is already finished")
        }
        val trimmed = san.trim()
        if (trimmed.isEmpty()) {
            return EngineMoveResult.Illegal("Empty SAN move")
        }
        return try {
            val probeList = MoveList(board.fen)
            probeList.loadFromSan(trimmed)
            val parsed = probeList.firstOrNull()
                ?: return EngineMoveResult.Illegal("Could not parse SAN '$trimmed'")
            val legal = board.legalMoves().firstOrNull {
                it.from == parsed.from && it.to == parsed.to && it.promotion == parsed.promotion
            } ?: return EngineMoveResult.Illegal("Illegal SAN move '$trimmed'")
            val engineMove = executeLegalMoveLocked(legal)
            EngineMoveResult.Success(move = engineMove, snapshot = buildSnapshotLocked())
        } catch (ex: Exception) {
            EngineMoveResult.Illegal("Invalid SAN '$trimmed': ${ex.message}")
        }
    }

    override fun undoLastMove(): EngineMove? = synchronized(lock) {
        if (history.isEmpty()) return null
        val removed = history.removeAt(history.lastIndex)
        board.undoMove()
        if (moveList.isNotEmpty()) {
            moveList.removeLast()
        }
        manualOutcome = null
        removed
    }

    override fun lastMove(): EngineMove? = synchronized(lock) { history.lastOrNull() }

    override fun moveHistory(): List<EngineMove> = synchronized(lock) { history.toList() }

    override fun positionAtPly(ply: Int): ChessPositionSnapshot = synchronized(lock) {
        val targetPly = ply.coerceIn(0, history.size)
        if (targetPly == history.size) {
            return buildSnapshotLocked()
        }
        val replay = Board()
        replay.loadFromFen(startFen)
        val subHistory = history.subList(0, targetPly).toList()
        for (engineMove in subHistory) {
            val uciMove = Move(engineMove.uci, replay.sideToMove)
            replay.doMove(uciMove, false)
        }
        buildSnapshotForBoard(
            targetBoard = replay,
            targetHistory = subHistory,
            explicitOutcome = null
        )
    }

    override fun isCheck(): Boolean = synchronized(lock) { board.isKingAttacked }

    override fun checkedKingSquare(): BoardSquare? = synchronized(lock) {
        if (!board.isKingAttacked) return null
        board.getKingSquare(board.sideToMove).toBoardSquare()
    }

    override fun gameResult(): GameResult? = synchronized(lock) {
        computeOutcomeLocked()?.result
    }

    override fun gameOutcome(): GameOutcome? = synchronized(lock) {
        computeOutcomeLocked()
    }

    override fun snapshot(): ChessPositionSnapshot = synchronized(lock) {
        buildSnapshotLocked()
    }

    override fun resign(resigningColor: PieceColor): GameOutcome = synchronized(lock) {
        computeOutcomeLocked()?.let { return it }
        val result = if (resigningColor == PieceColor.WHITE) {
            GameResult.BLACK_WIN
        } else {
            GameResult.WHITE_WIN
        }
        GameOutcome(result = result, reason = GameTerminationReason.RESIGNATION).also {
            manualOutcome = it
        }
    }

    override fun agreeDraw(): GameOutcome = synchronized(lock) {
        computeOutcomeLocked()?.let { return it }
        GameOutcome(result = GameResult.DRAW, reason = GameTerminationReason.DRAW_BY_AGREEMENT).also {
            manualOutcome = it
        }
    }

    override fun abortGame(): GameOutcome = synchronized(lock) {
        computeOutcomeLocked()?.let { return it }
        GameOutcome(result = GameResult.ABORTED, reason = GameTerminationReason.ABORTED).also {
            manualOutcome = it
        }
    }

    override fun timeout(flaggedColor: PieceColor): GameOutcome = synchronized(lock) {
        computeOutcomeLocked()?.let { return it }
        val opponent = flaggedColor.opposite()
        val outcome = if (hasSufficientMatingMaterialLocked(opponent)) {
            val winnerResult = if (opponent == PieceColor.WHITE) {
                GameResult.WHITE_WIN
            } else {
                GameResult.BLACK_WIN
            }
            GameOutcome(result = winnerResult, reason = GameTerminationReason.TIMEOUT)
        } else {
            GameOutcome(
                result = GameResult.DRAW,
                reason = GameTerminationReason.TIMEOUT_VS_INSUFFICIENT_MATERIAL
            )
        }
        manualOutcome = outcome
        outcome
    }

    override fun hasSufficientMatingMaterial(color: PieceColor): Boolean = synchronized(lock) {
        hasSufficientMatingMaterialLocked(color)
    }

    override fun loadFromFen(fen: String) = synchronized(lock) {
        val candidate = Board()
        candidate.loadFromFen(fen.trim())
        // Verify the FEN actually contains both kings and is valid
        require(candidate.getKingSquare(Side.WHITE) != Square.NONE) {
            "Invalid FEN: missing White king"
        }
        require(candidate.getKingSquare(Side.BLACK) != Square.NONE) {
            "Invalid FEN: missing Black king"
        }
        val normalizedFen = candidate.fen
        board = candidate
        startFen = normalizedFen
        moveList = MoveList(normalizedFen)
        history.clear()
        manualOutcome = null
    }

    override fun loadFromPgn(pgn: String): Boolean = synchronized(lock) {
        val trimmed = pgn.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val holder = PgnHolder(null)
            holder.loadPgn(trimmed)
            val game = holder.games.firstOrNull() ?: return false
            game.loadMoveText()

            val fenHeader = game.fen?.takeIf { it.isNotBlank() } ?: FenBoardParser.INITIAL_FEN
            val freshBoard = Board()
            freshBoard.loadFromFen(fenHeader)
            require(freshBoard.getKingSquare(Side.WHITE) != Square.NONE)
            require(freshBoard.getKingSquare(Side.BLACK) != Square.NONE)

            val normalizedStart = freshBoard.fen
            val previousBoard = board
            val previousStartFen = startFen
            val previousMoveList = moveList
            val previousHistory = history.toList()
            val previousManualOutcome = manualOutcome

            try {
                board = freshBoard
                startFen = normalizedStart
                moveList = MoveList(normalizedStart)
                history.clear()
                manualOutcome = null

                for (move in game.halfMoves) {
                    val legal = board.legalMoves().firstOrNull {
                        it.from == move.from && it.to == move.to && it.promotion == move.promotion
                    } ?: throw IllegalArgumentException("Illegal PGN move $move")
                    executeLegalMoveLocked(legal)
                }
                true
            } catch (_: Exception) {
                board = previousBoard
                startFen = previousStartFen
                moveList = previousMoveList
                history.clear()
                history.addAll(previousHistory)
                manualOutcome = previousManualOutcome
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun reset() = synchronized(lock) {
        board = Board()
        startFen = FenBoardParser.INITIAL_FEN
        moveList = MoveList(startFen)
        history.clear()
        manualOutcome = null
    }

    private fun legalMovesFromLocked(square: BoardSquare): List<EngineLegalMove> {
        if (isGameOverLocked()) return emptyList()
        val origin = square.toChesslibSquare()
        return board.legalMoves()
            .filter { it.from == origin }
            .map { it.toEngineLegalMove(board) }
    }

    private fun findLegalMoveByUciLocked(uci: String): Move? {
        val normalized = uci.trim().lowercase()
        if (normalized.length !in 4..5) return null
        return board.legalMoves().firstOrNull { it.toString().lowercase() == normalized }
    }

    private fun executeLegalMoveLocked(move: Move): EngineMove {
        val movingColor = board.sideToMove.toPieceColor()
        val moveNumberBefore = board.moveCounter
        val fromBoardSquare = move.from.toBoardSquare()!!
        val toBoardSquare = move.to.toBoardSquare()!!
        val movingPieceBefore = board.getPiece(move.from).toBoardPiece()!!
        val promotionType = move.promotion.toBoardPiece()?.type

        val targetOccupant = board.getPiece(move.to).toBoardPiece()
        val isEnPassant = movingPieceBefore.type == PieceType.PAWN &&
            fromBoardSquare.file != toBoardSquare.file &&
            targetOccupant == null
        val capturedSquare = when {
            isEnPassant -> BoardSquare(file = toBoardSquare.file, rank = fromBoardSquare.rank)
            targetOccupant != null -> toBoardSquare
            else -> null
        }
        val capturedPiece = when {
            isEnPassant -> BoardPiece(type = PieceType.PAWN, color = movingColor.opposite())
            else -> targetOccupant
        }
        val isCastling = movingPieceBefore.type == PieceType.KING &&
            abs(fromBoardSquare.file - toBoardSquare.file) == 2

        val sanBeforeMove = computeSanForMoveLocked(move)

        board.doMove(move, false)
        moveList.add(move)

        val isCheckAfter = board.isKingAttacked
        val isCheckmateAfter = board.isMated
        val finalPiece = if (promotionType != null) {
            BoardPiece(type = promotionType, color = movingColor)
        } else {
            movingPieceBefore
        }

        val engineMove = EngineMove(
            plyIndex = history.size + 1,
            moveNumber = moveNumberBefore,
            color = movingColor,
            from = fromBoardSquare,
            to = toBoardSquare,
            promotion = promotionType,
            uci = move.toString().lowercase(),
            san = sanBeforeMove,
            fenAfter = board.fen,
            movedPiece = finalPiece,
            capturedPiece = capturedPiece,
            capturedSquare = capturedSquare,
            isEnPassant = isEnPassant,
            isCastling = isCastling,
            isCheck = isCheckAfter,
            isCheckmate = isCheckmateAfter
        )
        history.add(engineMove)
        return engineMove
    }

    private fun computeSanForMoveLocked(move: Move): String {
        val singleMoveList = MoveList(board.fen)
        singleMoveList.add(move)
        return singleMoveList.toSanArray().firstOrNull() ?: move.toString()
    }

    private fun isGameOverLocked(): Boolean = computeOutcomeLocked() != null

    private fun computeOutcomeLocked(): GameOutcome? =
        computeOutcomeForBoard(board, manualOutcome)

    private fun buildSnapshotLocked(): ChessPositionSnapshot =
        buildSnapshotForBoard(
            targetBoard = board,
            targetHistory = history.toList(),
            explicitOutcome = manualOutcome
        )

    private fun buildSnapshotForBoard(
        targetBoard: Board,
        targetHistory: List<EngineMove>,
        explicitOutcome: GameOutcome?
    ): ChessPositionSnapshot {
        val isCheck = targetBoard.isKingAttacked
        val isMated = targetBoard.isMated
        val isStalemate = targetBoard.isStaleMate
        val isInsufficient = targetBoard.isInsufficientMaterial
        val isFiftyMove = targetBoard.halfMoveCounter >= 100
        val isRepetition = targetBoard.isRepetition
        val outcome = computeOutcomeForBoard(targetBoard, explicitOutcome)
        val isDraw = outcome?.result == GameResult.DRAW

        return ChessPositionSnapshot(
            fen = targetBoard.fen,
            pieces = extractPiecesMap(targetBoard),
            sideToMove = targetBoard.sideToMove.toPieceColor(),
            fullMoveNumber = targetBoard.moveCounter,
            halfMoveClock = targetBoard.halfMoveCounter,
            isCheck = isCheck,
            checkedKingSquare = if (isCheck) {
                targetBoard.getKingSquare(targetBoard.sideToMove).toBoardSquare()
            } else {
                null
            },
            isCheckmate = isMated,
            isStalemate = isStalemate,
            isInsufficientMaterial = isInsufficient,
            isFiftyMoveRule = isFiftyMove,
            isThreefoldRepetition = isRepetition,
            isDraw = isDraw,
            isGameOver = outcome != null,
            outcome = outcome,
            lastMove = targetHistory.lastOrNull(),
            moveHistory = targetHistory
        )
    }

    private fun computeOutcomeForBoard(
        targetBoard: Board,
        explicitOutcome: GameOutcome?
    ): GameOutcome? {
        if (explicitOutcome != null) return explicitOutcome
        return when {
            targetBoard.isMated -> {
                val winner = if (targetBoard.sideToMove == Side.WHITE) {
                    GameResult.BLACK_WIN
                } else {
                    GameResult.WHITE_WIN
                }
                GameOutcome(result = winner, reason = GameTerminationReason.CHECKMATE)
            }
            targetBoard.isStaleMate -> GameOutcome(
                result = GameResult.DRAW,
                reason = GameTerminationReason.STALEMATE
            )
            targetBoard.isInsufficientMaterial -> GameOutcome(
                result = GameResult.DRAW,
                reason = GameTerminationReason.INSUFFICIENT_MATERIAL
            )
            targetBoard.halfMoveCounter >= 100 -> GameOutcome(
                result = GameResult.DRAW,
                reason = GameTerminationReason.FIFTY_MOVE_RULE
            )
            targetBoard.isRepetition -> GameOutcome(
                result = GameResult.DRAW,
                reason = GameTerminationReason.THREEFOLD_REPETITION
            )
            else -> null
        }
    }

    /**
     * Checks whether [color] possesses enough mating material to win on timeout.
     * Any pawn, rook, queen, two knights/bishops (except same-colored bishops only),
     * or bishop + knight is sufficient. Lone king, king + single minor piece, or king +
     * same-color bishops cannot force checkmate -> timeout results in a draw.
     */
    private fun hasSufficientMatingMaterialLocked(color: PieceColor): Boolean {
        val pieces = extractPiecesMap(board)
        var knights = 0
        var lightBishops = 0
        var darkBishops = 0

        for ((square, piece) in pieces) {
            if (piece.color != color) continue
            when (piece.type) {
                PieceType.PAWN, PieceType.ROOK, PieceType.QUEEN -> return true
                PieceType.KNIGHT -> knights++
                PieceType.BISHOP -> {
                    if (square.isLightSquare) lightBishops++ else darkBishops++
                }
                PieceType.KING -> Unit
            }
        }

        val totalBishops = lightBishops + darkBishops
        if (lightBishops > 0 && darkBishops > 0) return true
        if (totalBishops > 0 && knights > 0) return true
        if (knights >= 2) return true
        return false
    }

    private fun extractPiecesMap(targetBoard: Board): Map<BoardSquare, BoardPiece> {
        val map = LinkedHashMap<BoardSquare, BoardPiece>(32)
        for (rank in 0..7) {
            for (file in 0..7) {
                val square = BoardSquare(file = file, rank = rank)
                val piece = targetBoard.getPiece(square.toChesslibSquare()).toBoardPiece()
                if (piece != null) {
                    map[square] = piece
                }
            }
        }
        return map
    }

    private fun Move.toEngineLegalMove(currentBoard: Board): EngineLegalMove {
        val fromSq = from.toBoardSquare()!!
        val toSq = to.toBoardSquare()!!
        val movingPiece = currentBoard.getPiece(from).toBoardPiece()
        val targetPiece = currentBoard.getPiece(to).toBoardPiece()
        val isEnPassant = movingPiece?.type == PieceType.PAWN &&
            fromSq.file != toSq.file &&
            targetPiece == null
        val isCastling = movingPiece?.type == PieceType.KING &&
            abs(fromSq.file - toSq.file) == 2

        return EngineLegalMove(
            from = fromSq,
            to = toSq,
            promotion = promotion.toBoardPiece()?.type,
            uci = toString().lowercase(),
            isCapture = targetPiece != null || isEnPassant,
            isEnPassant = isEnPassant,
            isCastling = isCastling
        )
    }

    private fun escapePgnHeader(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun Side.toPieceColor(): PieceColor =
        if (this == Side.WHITE) PieceColor.WHITE else PieceColor.BLACK

    private fun BoardSquare.toChesslibSquare(): Square =
        Square.fromValue(algebraic.uppercase())

    private fun Square.toBoardSquare(): BoardSquare? {
        if (this == Square.NONE) return null
        return BoardSquare.fromAlgebraic(value().lowercase())
    }

    private fun Piece.toBoardPiece(): BoardPiece? = when (this) {
        Piece.WHITE_PAWN -> BoardPiece(PieceType.PAWN, PieceColor.WHITE)
        Piece.WHITE_KNIGHT -> BoardPiece(PieceType.KNIGHT, PieceColor.WHITE)
        Piece.WHITE_BISHOP -> BoardPiece(PieceType.BISHOP, PieceColor.WHITE)
        Piece.WHITE_ROOK -> BoardPiece(PieceType.ROOK, PieceColor.WHITE)
        Piece.WHITE_QUEEN -> BoardPiece(PieceType.QUEEN, PieceColor.WHITE)
        Piece.WHITE_KING -> BoardPiece(PieceType.KING, PieceColor.WHITE)
        Piece.BLACK_PAWN -> BoardPiece(PieceType.PAWN, PieceColor.BLACK)
        Piece.BLACK_KNIGHT -> BoardPiece(PieceType.KNIGHT, PieceColor.BLACK)
        Piece.BLACK_BISHOP -> BoardPiece(PieceType.BISHOP, PieceColor.BLACK)
        Piece.BLACK_ROOK -> BoardPiece(PieceType.ROOK, PieceColor.BLACK)
        Piece.BLACK_QUEEN -> BoardPiece(PieceType.QUEEN, PieceColor.BLACK)
        Piece.BLACK_KING -> BoardPiece(PieceType.KING, PieceColor.BLACK)
        Piece.NONE -> null
    }

    private fun PieceType.toChesslibPiece(side: Side): Piece = when (side) {
        Side.WHITE -> when (this) {
            PieceType.QUEEN -> Piece.WHITE_QUEEN
            PieceType.ROOK -> Piece.WHITE_ROOK
            PieceType.BISHOP -> Piece.WHITE_BISHOP
            PieceType.KNIGHT -> Piece.WHITE_KNIGHT
            PieceType.PAWN -> Piece.WHITE_PAWN
            PieceType.KING -> Piece.WHITE_KING
        }
        Side.BLACK -> when (this) {
            PieceType.QUEEN -> Piece.BLACK_QUEEN
            PieceType.ROOK -> Piece.BLACK_ROOK
            PieceType.BISHOP -> Piece.BLACK_BISHOP
            PieceType.KNIGHT -> Piece.BLACK_KNIGHT
            PieceType.PAWN -> Piece.BLACK_PAWN
            PieceType.KING -> Piece.BLACK_KING
        }
    }
}
