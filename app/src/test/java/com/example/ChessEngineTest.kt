package com.example

import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.EngineMoveResult
import com.example.core.chess.GameTerminationReason
import com.example.core.chess.PgnMetadata
import com.example.core.ui.board.BoardPiece
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.FenBoardParser
import com.example.core.ui.board.PieceType
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessEngineTest {

    private fun sq(algebraic: String): BoardSquare = BoardSquare.fromAlgebraic(algebraic)!!

    @Test
    fun initialPosition_hasTwentyLegalMovesAndStandardFen() {
        val engine = ChesslibChessEngine()
        assertEquals(FenBoardParser.INITIAL_FEN, engine.currentFen())
        assertEquals(PieceColor.WHITE, engine.sideToMove())
        assertEquals(20, engine.legalMoves().size)
        assertFalse(engine.isCheck())
        assertNull(engine.gameResult())
        assertNull(engine.checkedKingSquare())
        assertEquals(listOf("e2e3", "e2e4"), engine.legalMovesFrom("e2").sorted())
    }

    @Test
    fun standardMovesAndIllegalMoves_enforcedStrictlyIncludingPins() {
        val engine = ChesslibChessEngine()

        // Valid opening moves
        assertTrue(engine.applyMove("e2e4"))
        assertEquals(PieceColor.BLACK, engine.sideToMove())
        assertEquals("e4", engine.lastMove()?.san)

        // Illegal move: moving white piece on black's turn
        assertFalse(engine.isMoveLegal("d2d4"))
        assertFalse(engine.applyMove("d2d4"))

        // Illegal move: malformed UCI
        assertFalse(engine.isMoveLegal("z9z8"))
        assertFalse(engine.applyMove(""))

        // Pin test: white king on e1, white knight on e2, black rook on e8 -> knight cannot move
        engine.loadFromFen("4r1k1/8/8/8/8/8/4N3/4K3 w - - 0 1")
        assertTrue(engine.legalMovesFrom("e2").isEmpty())
        assertFalse(engine.applyMove("e2c3"))
        // King can move off the e-file
        assertTrue(engine.isMoveLegal("e1f1"))
        assertTrue(engine.applyMove("e1f1"))
    }

    @Test
    fun checkAndCheckmate_foolsMateDetectedAccurately() {
        val engine = ChesslibChessEngine()

        // Fool's Mate: 1. f3 e5 2. g4 Qh4#
        assertTrue(engine.applyMove("f2f3"))
        assertTrue(engine.applyMove("e7e5"))
        assertTrue(engine.applyMove("g2g4"))
        val mateResult = engine.attemptMove(sq("d8"), sq("h4"))
        assertTrue(mateResult is EngineMoveResult.Success)

        val move = (mateResult as EngineMoveResult.Success).move
        assertTrue(move.isCheck)
        assertTrue(move.isCheckmate)
        assertTrue(move.san.startsWith("Qh4"))

        val snapshot = mateResult.snapshot
        assertTrue(snapshot.isCheck)
        assertTrue(snapshot.isCheckmate)
        assertTrue(snapshot.isGameOver)
        assertEquals(sq("e1"), snapshot.checkedKingSquare)
        assertEquals(GameResult.BLACK_WIN, engine.gameResult())
        assertEquals(GameTerminationReason.CHECKMATE, engine.gameOutcome()?.reason)

        // No legal moves allowed after checkmate
        assertTrue(engine.legalMoves().isEmpty())
        assertTrue(engine.attemptMove(sq("e2"), sq("e4")) is EngineMoveResult.Illegal)
    }

    @Test
    fun scholarsMate_resultsInWhiteWin() {
        val engine = ChesslibChessEngine()
        listOf("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#").forEach { san ->
            assertTrue("Expected SAN $san to succeed", engine.applySanMove(san) is EngineMoveResult.Success)
        }
        assertEquals(GameResult.WHITE_WIN, engine.gameResult())
        assertEquals(GameTerminationReason.CHECKMATE, engine.gameOutcome()?.reason)
        assertEquals(sq("e8"), engine.checkedKingSquare())
    }

    @Test
    fun stalemate_detectedAsDraw() {
        val engine = ChesslibChessEngine()
        // White queen moves to f7 leaving Black king on h8 with no legal moves and not in check
        engine.loadFromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        val snap = engine.snapshot()
        assertFalse(snap.isCheck)
        assertFalse(snap.isCheckmate)
        assertTrue(snap.isStalemate)
        assertTrue(snap.isDraw)
        assertTrue(snap.isGameOver)
        assertEquals(GameResult.DRAW, engine.gameResult())
        assertEquals(GameTerminationReason.STALEMATE, engine.gameOutcome()?.reason)
    }

    @Test
    fun castling_kingsideQueensideAndIllegalThroughCheck() {
        val engine = ChesslibChessEngine()

        // White kingside castling (O-O)
        engine.loadFromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertTrue(engine.isMoveLegal("e1g1"))
        assertTrue(engine.isMoveLegal("e1c1"))
        val whiteCastle = engine.attemptMove(sq("e1"), sq("g1")) as EngineMoveResult.Success
        assertTrue(whiteCastle.move.isCastling)
        assertEquals("O-O", whiteCastle.move.san)
        assertEquals(BoardPiece(PieceType.KING, PieceColor.WHITE), whiteCastle.snapshot.pieces[sq("g1")])
        assertEquals(BoardPiece(PieceType.ROOK, PieceColor.WHITE), whiteCastle.snapshot.pieces[sq("f1")])
        assertNull(whiteCastle.snapshot.pieces[sq("h1")])

        // Black queenside castling (O-O-O)
        val blackCastle = engine.attemptMove(sq("e8"), sq("c8")) as EngineMoveResult.Success
        assertTrue(blackCastle.move.isCastling)
        assertEquals("O-O-O", blackCastle.move.san)
        assertEquals(BoardPiece(PieceType.KING, PieceColor.BLACK), blackCastle.snapshot.pieces[sq("c8")])
        assertEquals(BoardPiece(PieceType.ROOK, PieceColor.BLACK), blackCastle.snapshot.pieces[sq("d8")])
        assertNull(blackCastle.snapshot.pieces[sq("a8")])

        // Illegal castling through attacked square (black rook on f8 attacks f1)
        engine.loadFromFen("5r1k/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        assertFalse(engine.isMoveLegal("e1g1"))
        assertTrue(engine.isMoveLegal("e1c1"))

        // Illegal castling while in check (black rook on e8 attacks e1)
        engine.loadFromFen("4r2k/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        assertTrue(engine.isCheck())
        assertFalse(engine.isMoveLegal("e1g1"))
        assertFalse(engine.isMoveLegal("e1c1"))
    }

    @Test
    fun enPassant_executesCaptureAndExpiresAfterOneTurn() {
        val engine = ChesslibChessEngine()

        // 1. e4 a6 2. e5 d5 3. exd6 (en passant!)
        assertTrue(engine.applyMove("e2e4"))
        assertTrue(engine.applyMove("a7a6"))
        assertTrue(engine.applyMove("e4e5"))
        assertTrue(engine.applyMove("d7d5"))

        val targets = engine.legalTargetsFrom(sq("e5"))
        assertTrue(targets.any { it.square == sq("d6") && it.isCapture })

        val epResult = engine.attemptMove(sq("e5"), sq("d6")) as EngineMoveResult.Success
        assertTrue(epResult.move.isEnPassant)
        assertEquals(BoardPiece(PieceType.PAWN, PieceColor.BLACK), epResult.move.capturedPiece)
        assertEquals(sq("d5"), epResult.move.capturedSquare)
        assertNull(epResult.snapshot.pieces[sq("d5")])
        assertEquals(BoardPiece(PieceType.PAWN, PieceColor.WHITE), epResult.snapshot.pieces[sq("d6")])

        // En passant expires if not taken immediately
        engine.reset()
        assertTrue(engine.applyMove("e2e4"))
        assertTrue(engine.applyMove("a7a6"))
        assertTrue(engine.applyMove("e4e5"))
        assertTrue(engine.applyMove("d7d5"))
        assertTrue(engine.applyMove("a2a3"))
        assertTrue(engine.applyMove("h7h6"))
        assertFalse(engine.isMoveLegal("e5d6"))
    }

    @Test
    fun pawnPromotion_requiresChoiceAndSupportsAllFourPieces() {
        val engine = ChesslibChessEngine()
        engine.loadFromFen("7k/P7/8/8/8/8/8/4K3 w - - 0 1")

        assertTrue(engine.isPromotionMove(sq("a7"), sq("a8")))
        val targets = engine.legalTargetsFrom(sq("a7"))
        assertTrue(targets.any { it.square == sq("a8") && it.requiresPromotion })

        // Attempting without promotion piece asks UI for choice
        val prompt = engine.attemptMove(sq("a7"), sq("a8"), promotion = null)
        assertTrue(prompt is EngineMoveResult.RequiresPromotion)
        assertEquals(PieceColor.WHITE, (prompt as EngineMoveResult.RequiresPromotion).color)

        // Promote to Knight (underpromotion)
        val knightPromo = engine.attemptMove(sq("a7"), sq("a8"), promotion = PieceType.KNIGHT)
        assertTrue(knightPromo is EngineMoveResult.Success)
        val move = (knightPromo as EngineMoveResult.Success).move
        assertEquals(PieceType.KNIGHT, move.promotion)
        assertEquals("a7a8n", move.uci)
        assertEquals(BoardPiece(PieceType.KNIGHT, PieceColor.WHITE), knightPromo.snapshot.pieces[sq("a8")])

        // Undo and promote to Queen
        assertNotNull(engine.undoLastMove())
        val queenPromo = engine.attemptMove(sq("a7"), sq("a8"), promotion = PieceType.QUEEN) as EngineMoveResult.Success
        assertEquals(PieceType.QUEEN, queenPromo.move.promotion)
        assertEquals("a7a8q", queenPromo.move.uci)
        assertTrue(queenPromo.snapshot.isCheck)
    }

    @Test
    fun insufficientMaterial_fiftyMoveRule_andThreefoldRepetition() {
        val engine = ChesslibChessEngine()

        // Insufficient material: K vs K
        engine.loadFromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals(GameResult.DRAW, engine.gameResult())
        assertEquals(GameTerminationReason.INSUFFICIENT_MATERIAL, engine.gameOutcome()?.reason)

        // Insufficient material: K+N vs K
        engine.loadFromFen("4k3/8/8/8/8/8/4N3/4K3 w - - 0 1")
        assertEquals(GameResult.DRAW, engine.gameResult())
        assertEquals(GameTerminationReason.INSUFFICIENT_MATERIAL, engine.gameOutcome()?.reason)

        // Fifty-move rule: halfmove clock reaches 100
        engine.loadFromFen("4k3/8/8/8/8/8/4R3/4K3 w - - 99 60")
        assertNull(engine.gameResult())
        assertTrue(engine.applyMove("e2e3"))
        assertEquals(GameResult.DRAW, engine.gameResult())
        assertEquals(GameTerminationReason.FIFTY_MOVE_RULE, engine.gameOutcome()?.reason)

        // Threefold repetition
        engine.reset()
        val repeatCycle = listOf("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8")
        repeatCycle.forEach { uci ->
            assertTrue("Move $uci should be legal", engine.applyMove(uci))
        }
        assertTrue(engine.snapshot().isThreefoldRepetition)
        assertEquals(GameResult.DRAW, engine.gameResult())
        assertEquals(GameTerminationReason.THREEFOLD_REPETITION, engine.gameOutcome()?.reason)
    }

    @Test
    fun resignDrawAgreementAndTimeoutMaterialRules_workAccurately() {
        val engine = ChesslibChessEngine()

        // Resignation
        val resignOutcome = engine.resign(PieceColor.WHITE)
        assertEquals(GameResult.BLACK_WIN, resignOutcome.result)
        assertEquals(GameTerminationReason.RESIGNATION, resignOutcome.reason)
        assertFalse(engine.applyMove("e2e4"))

        // Draw by agreement
        engine.reset()
        val drawOutcome = engine.agreeDraw()
        assertEquals(GameResult.DRAW, drawOutcome.result)
        assertEquals(GameTerminationReason.DRAW_BY_AGREEMENT, drawOutcome.reason)

        // Timeout with sufficient material -> opponent wins
        engine.reset()
        assertTrue(engine.hasSufficientMatingMaterial(PieceColor.WHITE))
        val timeoutWin = engine.timeout(PieceColor.BLACK)
        assertEquals(GameResult.WHITE_WIN, timeoutWin.result)
        assertEquals(GameTerminationReason.TIMEOUT, timeoutWin.reason)

        // Timeout vs insufficient material (Black only has K+B, White flags) -> DRAW
        engine.loadFromFen("4k3/4b3/8/8/8/8/4Q3/4K3 w - - 0 1")
        assertFalse(engine.hasSufficientMatingMaterial(PieceColor.BLACK))
        assertTrue(engine.hasSufficientMatingMaterial(PieceColor.WHITE))
        val timeoutDraw = engine.timeout(PieceColor.WHITE)
        assertEquals(GameResult.DRAW, timeoutDraw.result)
        assertEquals(GameTerminationReason.TIMEOUT_VS_INSUFFICIENT_MATERIAL, timeoutDraw.reason)
    }

    @Test
    fun pgnExportAndLoad_undoAndHistoryReplay_preserveExactState() {
        val engine = ChesslibChessEngine()
        val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6")
        moves.forEach { assertTrue(engine.applyMove(it)) }

        assertEquals(6, engine.moveHistory().size)
        val fenAfterSix = engine.currentFen()

        // Non-mutating ply inspection
        val plyZero = engine.positionAtPly(0)
        assertEquals(FenBoardParser.INITIAL_FEN, plyZero.fen)
        val plyTwo = engine.positionAtPly(2)
        assertEquals(2, plyTwo.moveHistory.size)
        assertEquals("e5", plyTwo.lastMove?.san)
        // Real engine state unchanged by positionAtPly
        assertEquals(fenAfterSix, engine.currentFen())
        assertEquals(6, engine.moveHistory().size)

        // Export PGN and reload into a fresh engine
        val pgn = engine.exportPgn(
            PgnMetadata(
                whitePlayer = "Alice",
                blackPlayer = "Bob",
                timeControl = "300+3"
            )
        )
        assertTrue(pgn.contains("[White \"Alice\"]"))
        assertTrue(pgn.contains("[Black \"Bob\"]"))
        assertTrue(pgn.contains("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6"))

        val restored = ChesslibChessEngine()
        assertTrue(restored.loadFromPgn(pgn))
        assertEquals(fenAfterSix, restored.currentFen())
        assertEquals(6, restored.moveHistory().size)

        // Undo last move reverts cleanly
        val undone = restored.undoLastMove()
        assertEquals("a6", undone?.san)
        assertEquals(5, restored.moveHistory().size)
        assertEquals(PieceColor.BLACK, restored.sideToMove())
    }
}
