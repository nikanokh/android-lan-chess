package com.example

import androidx.compose.ui.geometry.Offset
import com.example.core.ui.board.BoardCoordinates
import com.example.core.ui.board.BoardPiece
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.ChessPieceResources
import com.example.core.ui.board.FenBoardParser
import com.example.core.ui.board.PieceType
import com.example.domain.model.PieceColor
import com.example.core.chess.ChesslibChessEngine
import com.example.core.chess.EngineMoveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessBoardModelsTest {

    @Test
    fun squareColors_matchStandardChessGeometry() {
        // a1 is dark, h1 is light, a8 is light, h8 is dark
        assertFalse(BoardSquare.fromAlgebraic("a1")!!.isLightSquare)
        assertTrue(BoardSquare.fromAlgebraic("h1")!!.isLightSquare)
        assertTrue(BoardSquare.fromAlgebraic("a8")!!.isLightSquare)
        assertFalse(BoardSquare.fromAlgebraic("h8")!!.isLightSquare)

        // Adjacent squares always alternate light/dark
        for (file in 0 until 8) {
            for (rank in 0 until 8) {
                val sq = BoardSquare(file, rank)
                if (file < 7) {
                    assertTrue(sq.isLightSquare != BoardSquare(file + 1, rank).isLightSquare)
                }
                if (rank < 7) {
                    assertTrue(sq.isLightSquare != BoardSquare(file, rank + 1).isLightSquare)
                }
            }
        }
    }

    @Test
    fun coordinatesProjection_roundTripsCorrectlyInBothOrientations() {
        val boardPx = 800f
        val squarePx = boardPx / 8f

        for (flipped in listOf(false, true)) {
            for (file in 0 until 8) {
                for (rank in 0 until 8) {
                    val sq = BoardSquare(file, rank)
                    val center = BoardCoordinates.squareCenter(sq, squarePx, flipped)
                    val resolved = BoardCoordinates.squareAtOffset(center, boardPx, flipped)
                    assertEquals("Failed round-trip for ${sq.algebraic} flipped=$flipped", sq, resolved)
                }
            }
        }
    }

    @Test
    fun coordinatesLabels_invertProperlyWhenFlipped() {
        assertEquals("a", BoardCoordinates.fileLabelForDisplayCol(0, flipped = false))
        assertEquals("h", BoardCoordinates.fileLabelForDisplayCol(7, flipped = false))
        assertEquals("8", BoardCoordinates.rankLabelForDisplayRow(0, flipped = false))
        assertEquals("1", BoardCoordinates.rankLabelForDisplayRow(7, flipped = false))

        assertEquals("h", BoardCoordinates.fileLabelForDisplayCol(0, flipped = true))
        assertEquals("a", BoardCoordinates.fileLabelForDisplayCol(7, flipped = true))
        assertEquals("1", BoardCoordinates.rankLabelForDisplayRow(0, flipped = true))
        assertEquals("8", BoardCoordinates.rankLabelForDisplayRow(7, flipped = true))
    }

    @Test
    fun squareAtOffset_returnsNullOutsideBoardBounds() {
        val boardPx = 800f
        assertNull(BoardCoordinates.squareAtOffset(Offset(-1f, 50f), boardPx, false))
        assertNull(BoardCoordinates.squareAtOffset(Offset(50f, -5f), boardPx, false))
        assertNull(BoardCoordinates.squareAtOffset(Offset(800f, 50f), boardPx, false))
        assertNull(BoardCoordinates.squareAtOffset(Offset(50f, 801f), boardPx, true))
    }

    @Test
    fun fenBoardParser_parsesInitialPositionAndRoundTrips() {
        val pieces = FenBoardParser.parsePlacement(FenBoardParser.INITIAL_FEN)
        assertEquals(32, pieces.size)
        assertEquals(BoardPiece(PieceType.KING, PieceColor.WHITE), pieces[BoardSquare.fromAlgebraic("e1")])
        assertEquals(BoardPiece(PieceType.QUEEN, PieceColor.WHITE), pieces[BoardSquare.fromAlgebraic("d1")])
        assertEquals(BoardPiece(PieceType.KING, PieceColor.BLACK), pieces[BoardSquare.fromAlgebraic("e8")])
        assertEquals(BoardPiece(PieceType.ROOK, PieceColor.BLACK), pieces[BoardSquare.fromAlgebraic("a8")])
        assertEquals(BoardPiece(PieceType.PAWN, PieceColor.WHITE), pieces[BoardSquare.fromAlgebraic("e2")])
        assertNull(pieces[BoardSquare.fromAlgebraic("e4")])

        val expectedPlacement = FenBoardParser.INITIAL_FEN.substringBefore(' ')
        assertEquals(expectedPlacement, FenBoardParser.toPlacement(pieces))
    }

    @Test
    fun pieceResources_mapsAllTwelveDistinctSprites() {
        val drawables = mutableSetOf<Int>()
        for (color in PieceColor.entries) {
            for (type in PieceType.entries) {
                val res = ChessPieceResources.drawableFor(color, type)
                assertTrue(res != 0)
                drawables.add(res)
            }
        }
        assertEquals(12, drawables.size)
    }

    @Test
    fun engineLegalTargetsAndMoves_feedChessBoardAccurately() {
        val engine = ChesslibChessEngine()
        val e2 = BoardSquare.fromAlgebraic("e2")!!
        val e4 = BoardSquare.fromAlgebraic("e4")!!
        val e6 = BoardSquare.fromAlgebraic("e6")!!

        val targets = engine.legalTargetsFrom(e2)
        assertEquals(2, targets.size)
        assertTrue(targets.any { it.square == e4 && !it.isCapture })

        // Reject illegal move e2 -> e6
        assertTrue(engine.attemptMove(e2, e6) is EngineMoveResult.Illegal)

        // Accept legal move e2 -> e4
        val result = engine.attemptMove(e2, e4)
        assertTrue(result is EngineMoveResult.Success)
        val snapshot = (result as EngineMoveResult.Success).snapshot
        assertNull(snapshot.pieces[e2])
        assertEquals(BoardPiece(PieceType.PAWN, PieceColor.WHITE), snapshot.pieces[e4])
        assertEquals(e2, snapshot.lastMove?.from)
        assertEquals(e4, snapshot.lastMove?.to)
        assertEquals(PieceColor.BLACK, snapshot.sideToMove)
        assertNull(snapshot.checkedKingSquare)
        assertNotNull(snapshot.lastMove)
    }
}

