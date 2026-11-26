package com.example

import com.example.core.analysis.AccuracyCalculator
import com.example.core.analysis.AnalyzerPreferences
import com.example.core.analysis.ChessComApiService
import com.example.core.analysis.ChessComGameItem
import com.example.core.analysis.ChessComPlayerSide
import com.example.core.analysis.ChesslibHelper
import com.example.core.analysis.ClassificationThresholds
import com.example.core.analysis.EngineLine
import com.example.core.analysis.EvalBarPosition
import com.example.core.analysis.EvalScoreDisplay
import com.example.core.analysis.MoveAnalysis
import com.example.core.analysis.MoveClassifier
import com.example.core.analysis.OpeningAnalyzer
import com.example.core.analysis.OpeningBookEntry
import com.example.core.analysis.OpeningBookService
import com.example.core.analysis.OpeningCacheStatus
import com.example.core.analysis.OpeningStatisticsService
import com.example.core.analysis.PgnService
import com.example.core.analysis.PlayerGameSelectionService
import com.example.core.analysis.ProblematicIssueType
import com.example.core.analysis.RepertoireCacheMode
import com.example.core.analysis.RepertoireFilterConfig
import com.example.core.analysis.RepertoirePeriodPreset
import com.example.core.analysis.RepertoireTimeClass
import com.example.core.analysis.StockfishClientOptions
import com.example.core.analysis.StockfishUciClient
import com.example.core.analysis.UciEngineTransport
import com.example.core.analysis.VariationTree
import com.example.core.analysis.normalizeFenKey
import com.example.domain.model.MoveClassification
import com.example.domain.model.PieceColor
import com.example.feature.analysis.formatEvalScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic Fake UCI Transport for unit testing [StockfishUciClient].
 */
private class FakeUciEngineTransport : UciEngineTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _outputLines = MutableSharedFlow<String>(extraBufferCapacity = 128)
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 32)

    override val outputLines: SharedFlow<String> = _outputLines.asSharedFlow()
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    val sentCommands = mutableListOf<String>()
    var startCount = 0
    var restartedCount = 0
    var closed = false
    var throwOnStart: Throwable? = null

    var autoRespondUciOk = true
    var autoRespondReadyOk = true
    var autoFinishGo = true
    var respondToStop = true
    var simulatedScoreLine =
        "info depth 18 multipv 1 score cp 45 nodes 12000 nps 500000 time 25 pv e2e4 e7e5"
    var simulatedBestMove = "bestmove e2e4 ponder e7e5"

    override suspend fun start() {
        startCount++
        throwOnStart?.let { throw it }
        closed = false
    }

    override suspend fun sendCommand(command: String) {
        if (closed) return
        synchronized(sentCommands) {
            sentCommands.add(command)
        }

        when {
            command == "uci" -> {
                if (autoRespondUciOk) {
                    emitAsync("id name Stockfish 16\nuciok")
                }
            }
            command == "isready" -> {
                if (autoRespondReadyOk) {
                    emitAsync("readyok")
                }
            }
            command.startsWith("go ") -> {
                val match = Regex("""depth\s+(\d+)""").find(command)
                val targetDepth = match?.groupValues?.get(1)?.toIntOrNull() ?: 14
                val infoLine = simulatedScoreLine.replace(Regex("""depth\s+\d+"""), "depth $targetDepth")
                scope.launch {
                    delay(5)
                    if (!closed) {
                        _outputLines.emit(infoLine)
                        if (autoFinishGo) {
                            delay(5)
                            if (!closed) {
                                _outputLines.emit(simulatedBestMove)
                            }
                        }
                    }
                }
            }
            command == "stop" -> {
                if (respondToStop) {
                    emitAsync(simulatedBestMove)
                }
            }
        }
    }

    override suspend fun restart() {
        restartedCount++
        closed = false
    }

    override fun close() {
        closed = true
    }

    fun emitAsync(message: String) {
        scope.launch {
            delay(5)
            _outputLines.emit(message)
        }
    }

    fun triggerError(message: String) {
        scope.launch {
            delay(5)
            _errors.emit(message)
        }
    }
}

class AnalyzerCoreTest {

    // =========================================================================
    // 1. StockfishUciClient — UCI Engine Stability & Correctness
    // =========================================================================

    @Test
    fun `stockfish initializes after uciok and readyok and applies options`() = runBlocking {
        val transport = FakeUciEngineTransport()
        val client = StockfishUciClient(transport)

        client.init()
        assertTrue(client.engineState.value.isReady)
        assertTrue(transport.sentCommands.contains("uci"))
        assertTrue(transport.sentCommands.contains("setoption name Hash value 16"))
        assertTrue(transport.sentCommands.contains("setoption name Threads value 1"))
        assertTrue(transport.sentCommands.contains("setoption name MultiPV value 3"))
        assertTrue(transport.sentCommands.contains("setoption name Ponder value false"))
        assertTrue(transport.sentCommands.contains("isready"))
    }

    @Test
    fun `stockfish respects single threaded wasm option limits without deadlocking`() = runBlocking {
        val transport = FakeUciEngineTransport().apply {
            autoRespondUciOk = false
        }
        val client = StockfishUciClient(transport)

        val initJob = async { client.init() }
        delay(20)
        transport.emitAsync(
            """
            id name Stockfish 2019-08-15 Multi-Variant
            option name Threads type spin default 1 min 1 max 1
            option name Hash type spin default 16 min 16 max 16
            uciok
            """.trimIndent()
        )
        initJob.await()

        assertTrue(client.engineState.value.isReady)
        assertTrue(transport.sentCommands.contains("setoption name Hash value 16"))
        assertFalse(transport.sentCommands.any { it.startsWith("setoption name Threads") })
    }

    @Test
    fun `stockfish rejects init when transport start fails`() = runBlocking {
        val transport = FakeUciEngineTransport().apply {
            throwOnStart = IllegalStateException("Failed to load WASM worker script")
        }
        val client = StockfishUciClient(transport)

        try {
            client.init()
            fail("Expected init() to throw")
        } catch (ex: Exception) {
            assertTrue(ex.message?.contains("Failed to load WASM worker script") == true)
        }
        assertFalse(client.engineState.value.isReady)
    }

    @Test
    fun `stockfish rejects init when transport error fires during startup`() = runBlocking {
        val transport = FakeUciEngineTransport().apply {
            autoRespondUciOk = false
        }
        val client = StockfishUciClient(transport)

        val initJob = async {
            try {
                client.init()
                null
            } catch (ex: Exception) {
                ex
            }
        }
        delay(20)
        transport.triggerError("WASM instantiation error")

        val caught = initJob.await()
        assertNotNull(caught)
        assertTrue(caught?.message?.contains("WASM instantiation error") == true)
        assertFalse(client.engineState.value.isReady)
    }

    @Test
    fun `stockfish rejects init on readyok timeout`() = runBlocking {
        val transport = FakeUciEngineTransport().apply {
            autoRespondReadyOk = false
        }
        val client = StockfishUciClient(
            transport = transport,
            options = StockfishClientOptions(initTimeoutMs = 80L)
        )

        try {
            client.init()
            fail("Expected init timeout")
        } catch (ex: Exception) {
            assertFalse(client.engineState.value.isReady)
        }
    }

    @Test
    fun `stockfish applies user config up to depth 30 and evaluates sequential requests with white POV`() = runBlocking {
        val transport = FakeUciEngineTransport()
        val client = StockfishUciClient(transport)
        client.init()
        client.updateConfig(
            hashMb = 256,
            threads = 4,
            multiPv = 4,
            depth = 24,
            ponder = true
        )

        val cfg = client.getConfig()
        assertEquals(256, cfg.hashMb)
        assertEquals(4, cfg.threads)
        assertEquals(24, cfg.depth)
        assertTrue(transport.sentCommands.contains("setoption name Hash value 256"))
        assertTrue(transport.sentCommands.contains("setoption name Threads value 4"))
        assertTrue(transport.sentCommands.contains("setoption name MultiPV value 4"))
        assertTrue(transport.sentCommands.contains("setoption name Ponder value true"))

        val fen1 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val fen2 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"

        val results = listOf(
            async { client.evaluateFenAsync(fen1, depth = 18, multiPv = 2) },
            async { client.evaluateFenAsync(fen2, depth = 24, multiPv = 2) }
        ).awaitAll()

        // First position is Black to move (+45 cp for Black -> -45 cp White POV)
        assertEquals(-45, results[0].lines.firstOrNull()?.cp)
        assertEquals(18, results[0].lines.firstOrNull()?.depth)
        // Second position is White to move (+45 cp for White -> +45 cp White POV)
        assertEquals(45, results[1].lines.firstOrNull()?.cp)
        assertEquals(24, results[1].lines.firstOrNull()?.depth)
        assertTrue(transport.sentCommands.contains("go depth 24"))
    }

    @Test
    fun `stockfish detects hung stopAndWait and restarts transport`() = runBlocking {
        val transport = FakeUciEngineTransport()
        val client = StockfishUciClient(
            transport = transport,
            options = StockfishClientOptions(stopTimeoutMs = 80L)
        )
        client.init()

        transport.autoFinishGo = false
        transport.respondToStop = false
        client.analyzePositionInteractive(VariationTree.DEFAULT_START_FEN, overrideDepth = 20)
        delay(50)
        assertTrue(client.engineState.value.isAnalyzing)

        // Prepare fresh transport responses upon restart
        val job = async {
            try {
                client.stopAndWait()
                null
            } catch (ex: Exception) {
                ex
            }
        }
        delay(120)
        transport.autoFinishGo = true
        transport.respondToStop = true

        val caught = job.await()
        assertNotNull(caught)
        assertTrue(transport.restartedCount >= 1)
    }

    @Test
    fun `stockfish normalizes mate to white POV without synthetic 10000 cp`() = runBlocking {
        val transport = FakeUciEngineTransport().apply {
            simulatedScoreLine =
                "info depth 15 multipv 1 score mate 3 nodes 8000 nps 400000 time 20 pv d8h4 g2g3 h4e4"
            simulatedBestMove = "bestmove d8h4"
        }
        val client = StockfishUciClient(transport)

        val blackToMoveFen = "rnbqkbnr/pppp1ppp/8/4p3/4P2P/5P2/PPPP2P1/RNBQKBNR b KQkq - 0 2"
        val res = client.evaluateFenAsync(blackToMoveFen, depth = 15, multiPv = 1)

        assertEquals(-3, res.lines.firstOrNull()?.mate)
        assertNull(res.lines.firstOrNull()?.cp)
    }

    // =========================================================================
    // 2. AccuracyCalculator — Win Probability & Accuracy Math
    // =========================================================================

    @Test
    fun `win probability calculation across cp extreme and mate values`() {
        assertTrue(abs(AccuracyCalculator.cpToWhiteWinProbability(0) - 50.0) < 0.0001)
        assertTrue(AccuracyCalculator.cpToWhiteWinProbability(100) > 58.0)
        assertTrue(AccuracyCalculator.cpToWhiteWinProbability(-100) < 42.0)
        assertTrue(
            abs(
                AccuracyCalculator.cpToWhiteWinProbability(100) +
                    AccuracyCalculator.cpToWhiteWinProbability(-100) - 100.0
            ) < 0.0001
        )

        assertTrue(AccuracyCalculator.cpToWhiteWinProbability(5000) > 99.0)
        assertTrue(AccuracyCalculator.cpToWhiteWinProbability(-5000) < 1.0)
        assertEquals(100.0, AccuracyCalculator.cpToWhiteWinProbability(Double.POSITIVE_INFINITY), 0.001)
        assertEquals(0.0, AccuracyCalculator.cpToWhiteWinProbability(Double.NEGATIVE_INFINITY), 0.001)
        assertEquals(50.0, AccuracyCalculator.cpToWhiteWinProbability(Double.NaN), 0.001)

        assertEquals(100.0, AccuracyCalculator.cpToWhiteWinProbability(cp = null as Int?, mate = 1), 0.001)
        assertEquals(0.0, AccuracyCalculator.cpToWhiteWinProbability(cp = null as Int?, mate = -1), 0.001)
        assertTrue(AccuracyCalculator.cpToWhiteWinProbability(cp = null as Int?, mate = 5) > 98.0)
        assertTrue(AccuracyCalculator.cpToWhiteWinProbability(cp = null as Int?, mate = -5) < 2.0)

        assertEquals(70.0, AccuracyCalculator.toPlayerWinProbability(70.0, PieceColor.WHITE), 0.001)
        assertEquals(30.0, AccuracyCalculator.toPlayerWinProbability(70.0, PieceColor.BLACK), 0.001)
    }

    @Test
    fun `single move accuracy bounded and handles mate blunder transition`() {
        assertEquals(100.0, AccuracyCalculator.calculateSingleMoveAccuracy(0.0), 0.001)
        assertEquals(100.0, AccuracyCalculator.calculateSingleMoveAccuracy(-5.0), 0.001)
        assertEquals(0.0, AccuracyCalculator.calculateSingleMoveAccuracy(100.0), 0.001)
        assertEquals(0.0, AccuracyCalculator.calculateSingleMoveAccuracy(Double.NaN), 0.001)

        val smallLoss = AccuracyCalculator.calculateSingleMoveAccuracy(2.0)
        val mediumLoss = AccuracyCalculator.calculateSingleMoveAccuracy(15.0)
        val hugeLoss = AccuracyCalculator.calculateSingleMoveAccuracy(45.0)
        assertTrue(smallLoss > 90.0)
        assertTrue(mediumLoss > 45.0 && mediumLoss < 60.0)
        assertTrue(hugeLoss < 15.0)

        val mateBlunder = AccuracyCalculator.computeMoveWinProbLoss(
            player = PieceColor.BLACK,
            mateBefore = 6,
            mateAfter = 1,
            isBestMove = false
        )
        assertTrue(mateBlunder.winProbLoss >= 40.0)
    }

    @Test
    fun `game statistics excludes Book and Forced inflation`() {
        val emptyStats = AccuracyCalculator.calculateGameStatistics(emptyList())
        assertEquals(0.0, emptyStats.whiteAccuracy, 0.001)
        assertEquals(0.0, emptyStats.blackAccuracy, 0.001)

        val analyses = listOf(
            MoveAnalysis(
                nodeId = "1", ply = 1, moveNumber = 1, player = PieceColor.WHITE,
                san = "e4", uci = "e2e4", fenBefore = "", fenAfter = "",
                bestMoveUci = "e2e4", bestMoveSan = "e4", evalBeforeCp = 20, evalAfterCp = 20,
                winProbBefore = 52.0, winProbAfter = 52.0, cpl = 0, winProbLoss = 0.0,
                moveAccuracy = 100.0, category = MoveClassification.BOOK, depth = 14, isTheoretical = true
            ),
            MoveAnalysis(
                nodeId = "2", ply = 2, moveNumber = 1, player = PieceColor.BLACK,
                san = "e5", uci = "e7e5", fenBefore = "", fenAfter = "",
                bestMoveUci = "e7e5", bestMoveSan = "e5", evalBeforeCp = 20, evalAfterCp = 20,
                winProbBefore = 52.0, winProbAfter = 52.0, cpl = 0, winProbLoss = 0.0,
                moveAccuracy = 100.0, category = MoveClassification.FORCED, depth = 14
            ),
            MoveAnalysis(
                nodeId = "3", ply = 3, moveNumber = 2, player = PieceColor.WHITE,
                san = "Nf3", uci = "g1f3", fenBefore = "", fenAfter = "",
                bestMoveUci = "d2d4", bestMoveSan = "d4", evalBeforeCp = 50, evalAfterCp = -50,
                winProbBefore = 55.0, winProbAfter = 45.0, cpl = 100, winProbLoss = 10.0,
                moveAccuracy = 60.0, category = MoveClassification.INACCURACY, depth = 14
            ),
            MoveAnalysis(
                nodeId = "4", ply = 4, moveNumber = 2, player = PieceColor.BLACK,
                san = "Nc6", uci = "b8c6", fenBefore = "", fenAfter = "",
                bestMoveUci = "b8c6", bestMoveSan = "Nc6", evalBeforeCp = -50, evalAfterCp = -50,
                winProbBefore = 45.0, winProbAfter = 45.0, cpl = 0, winProbLoss = 0.0,
                moveAccuracy = 80.0, category = MoveClassification.GOOD, depth = 14
            )
        )

        val stats = AccuracyCalculator.calculateGameStatistics(analyses)
        assertEquals(60.0, stats.whiteAccuracy, 0.1)
        assertEquals(80.0, stats.blackAccuracy, 0.1)
    }

    // =========================================================================
    // 3. MoveClassifier — Classification & SEE Material Exchange
    // =========================================================================

    @Test
    fun `distinguishes Best from Excellent and preserves exact CPL without dampener`() {
        val startFen = VariationTree.DEFAULT_START_FEN
        val bestRes = MoveClassifier.classifyMoveFromEvaluations(
            player = PieceColor.WHITE,
            uci = "e2e4",
            fenBefore = startFen,
            fenAfter = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            bestMoveUci = "e2e4",
            evalBeforeCp = 30,
            evalAfterCp = 28,
            isTheoretical = false,
            thresholds = ClassificationThresholds()
        )
        assertEquals(MoveClassification.BEST, bestRes.category)
        assertEquals(0, bestRes.cpl)

        val nearBestRes = MoveClassifier.classifyMoveFromEvaluations(
            player = PieceColor.WHITE,
            uci = "d2d3",
            fenBefore = startFen,
            fenAfter = "rnbqkbnr/pppppppp/8/8/8/3P4/PPP1PPPP/RNBQKBNR b KQkq - 0 1",
            bestMoveUci = "e2e4",
            evalBeforeCp = 30,
            evalAfterCp = 15,
            isTheoretical = false,
            thresholds = ClassificationThresholds()
        )
        assertEquals(MoveClassification.EXCELLENT, nearBestRes.category)
        assertEquals(15, nearBestRes.cpl)

        val highEvalRes = MoveClassifier.classifyMoveFromEvaluations(
            player = PieceColor.WHITE,
            uci = "a2a3",
            fenBefore = startFen,
            fenAfter = startFen,
            bestMoveUci = "e2e4",
            evalBeforeCp = 700,
            evalAfterCp = 500,
            isTheoretical = false,
            thresholds = ClassificationThresholds()
        )
        assertEquals(200, highEvalRes.cpl)
    }

    @Test
    fun `classifies Great and Brilliant moves properly`() {
        val startFen = VariationTree.DEFAULT_START_FEN
        val greatRes = MoveClassifier.classifyMoveFromEvaluations(
            player = PieceColor.WHITE,
            uci = "e2e4",
            fenBefore = startFen,
            fenAfter = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            bestMoveUci = "e2e4",
            evalBeforeCp = 180,
            evalAfterCp = 180,
            secondBestCp = -20,
            isTheoretical = false,
            thresholds = ClassificationThresholds()
        )
        assertEquals(MoveClassification.GREAT, greatRes.category)

        val fenBeforeSac = "r1bqkbnr/pp1ppppp/2p5/8/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 3"
        val board = ChesslibHelper.parseBoard(fenBeforeSac)
        val sacMove = ChesslibHelper.applySan(board, "Nd5")!!
        val brilliantRes = MoveClassifier.classifyMoveFromEvaluations(
            player = PieceColor.WHITE,
            uci = sacMove.uci,
            fenBefore = fenBeforeSac,
            fenAfter = sacMove.fenAfter,
            bestMoveUci = sacMove.uci,
            evalBeforeCp = 220,
            evalAfterCp = 220,
            secondBestCp = 40,
            isTheoretical = false,
            thresholds = ClassificationThresholds()
        )
        assertEquals(MoveClassification.BRILLIANT, brilliantRes.category)
    }

    @Test
    fun `SEE doesMoveLosePiece handles captures trades and hanging queen`() {
        // Scenario A: White pawn on d4 captures Black rook on e5 (Black knight on c6 recaptures)
        val fenBeforePawnTakesRook = "4k3/8/2n5/4r3/3P4/8/8/4K3 w - - 0 1"
        val b1 = ChesslibHelper.parseBoard(fenBeforePawnTakesRook)
        val m1 = ChesslibHelper.applySan(b1, "dxe5")!!
        assertFalse(
            MoveClassifier.doesMoveLosePiece(
                fenBeforePawnTakesRook,
                m1.fenAfter,
                PieceColor.WHITE,
                listOf("c6e5")
            )
        )

        // Scenario B: Equal knight trade
        val fenBeforeTrade = "r1bqkbnr/pp2pppp/2p5/3n4/8/2N5/PPPPPPPP/R1BQKBNR w KQkq - 0 1"
        val b2 = ChesslibHelper.parseBoard(fenBeforeTrade)
        val m2 = ChesslibHelper.applySan(b2, "Nxd5")!!
        assertFalse(
            MoveClassifier.doesMoveLosePiece(
                fenBeforeTrade,
                m2.fenAfter,
                PieceColor.WHITE,
                listOf("c6d5")
            )
        )

        // Scenario C: Hanging Queen on h5 taken by Black knight on f6
        val fenBeforeBlunder = "rnbqkb1r/pppppppp/5n2/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 1 2"
        val b3 = ChesslibHelper.parseBoard(fenBeforeBlunder)
        val m3 = ChesslibHelper.applySan(b3, "Qh5")!!
        assertTrue(
            MoveClassifier.doesMoveLosePiece(
                fenBeforeBlunder,
                m3.fenAfter,
                PieceColor.WHITE,
                listOf("f6h5")
            )
        )
    }

    @Test
    fun `classifies Good Inaccuracy Mistake MissedChance Blunder Forced and bad Book moves`() {
        val startFen = VariationTree.DEFAULT_START_FEN
        val th = ClassificationThresholds()

        assertEquals(
            MoveClassification.GOOD,
            MoveClassifier.classifyMoveFromEvaluations(
                player = PieceColor.WHITE, uci = "h2h3", fenBefore = startFen, fenAfter = startFen,
                bestMoveUci = "e2e4", evalBeforeCp = 40, evalAfterCp = -30, isTheoretical = false, thresholds = th
            ).category
        )
        assertEquals(
            MoveClassification.INACCURACY,
            MoveClassifier.classifyMoveFromEvaluations(
                player = PieceColor.WHITE, uci = "g2g4", fenBefore = startFen, fenAfter = startFen,
                bestMoveUci = "e2e4", evalBeforeCp = 40, evalAfterCp = -100, isTheoretical = false, thresholds = th
            ).category
        )
        assertEquals(
            MoveClassification.MISTAKE,
            MoveClassifier.classifyMoveFromEvaluations(
                player = PieceColor.WHITE, uci = "f2f4", fenBefore = startFen, fenAfter = startFen,
                bestMoveUci = "e2e4", evalBeforeCp = 40, evalAfterCp = -200, isTheoretical = false, thresholds = th
            ).category
        )
        assertEquals(
            MoveClassification.MISSED_CHANCE,
            MoveClassifier.classifyMoveFromEvaluations(
                player = PieceColor.WHITE, uci = "a2a3", fenBefore = startFen, fenAfter = startFen,
                bestMoveUci = "e2e4", evalBeforeCp = 320, evalAfterCp = 20, isTheoretical = false, thresholds = th
            ).category
        )
        assertEquals(
            MoveClassification.BLUNDER,
            MoveClassifier.classifyMoveFromEvaluations(
                player = PieceColor.WHITE, uci = "g2g4", fenBefore = startFen, fenAfter = startFen,
                bestMoveUci = "e2e4", evalBeforeCp = 30, evalAfterCp = -400, isTheoretical = false, thresholds = th
            ).category
        )

        val singleLegalMoveFen = "6k1/8/8/8/8/8/6q1/7K w - - 0 1"
        assertEquals(
            MoveClassification.FORCED,
            MoveClassifier.classifyMoveFromEvaluations(
                player = PieceColor.WHITE,
                uci = "h1g2",
                fenBefore = singleLegalMoveFen,
                fenAfter = "6k1/8/8/8/8/8/6K1/8 b - - 0 1",
                bestMoveUci = "h1g2",
                evalBeforeCp = 500,
                evalAfterCp = 500,
                isTheoretical = false,
                thresholds = th
            ).category
        )

        // Book move that loses 250 cp must NOT be masked as Book
        assertEquals(
            MoveClassification.MISTAKE,
            MoveClassifier.classifyMoveFromEvaluations(
                player = PieceColor.WHITE,
                uci = "e2e4",
                fenBefore = startFen,
                fenAfter = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                bestMoveUci = "d2d4",
                evalBeforeCp = 30,
                evalAfterCp = -220,
                isTheoretical = true,
                thresholds = th
            ).category
        )
    }

    // =========================================================================
    // 4. OpeningBookService — En Passant, Transpositions & Custom PGN
    // =========================================================================

    @Test
    fun `opening book separates EP target square handles transpositions and custom PGN`() {
        val fenWithEp = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"
        val fenWithoutEp = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq - 0 5"
        assertNotEquals(normalizeFenKey(fenWithEp), normalizeFenKey(fenWithoutEp))
        assertTrue(normalizeFenKey(fenWithEp).endsWith(" d6"))
        assertTrue(normalizeFenKey(fenWithoutEp).endsWith(" -"))

        val book = OpeningBookService()

        // 1. Transposition into C50 Italian Game via 1.Nf3 Nc6 2.e4 e5 3.Bc4
        var root1 = VariationTree.createRootNode()
        var curId = root1.id
        for (mv in listOf("Nf3", "Nc6", "e4", "e5", "Bc4")) {
            val res = VariationTree.addSanOrUciMoveToTree(root1, curId, mv)
            root1 = res.updatedRoot
            curId = res.newNode!!.id
        }
        val opening = book.analyzeMainlineOpening(VariationTree.getMainlineNodes(root1))
        assertEquals("C50", opening.eco)
        assertNull(opening.firstOutOfBookPly)
        assertEquals(5, opening.lastTheoreticalPly)

        // 2. Game leaves theory on ply 3 (2. a3) then plays book moves: firstOutOfBookPly stays 3
        var root2 = VariationTree.createRootNode()
        var curId2 = root2.id
        for (mv in listOf("e4", "e5", "a3", "Nc6", "Nf3", "Nf6")) {
            val res = VariationTree.addSanOrUciMoveToTree(root2, curId2, mv)
            root2 = res.updatedRoot
            curId2 = res.newNode!!.id
        }
        val outSummary = book.analyzeMainlineOpening(VariationTree.getMainlineNodes(root2))
        assertEquals(2, outSummary.lastTheoreticalPly)
        assertEquals(3, outSummary.firstOutOfBookPly)
        assertEquals("2. a3", outSummary.firstOutOfBookMoveSan)

        // 3. Custom PGN opening import
        val customBook = OpeningBookService()
        val imported = customBook.importPgnOpening(
            "[ECO \"B00\"]\n[Opening \"Nimzowitsch Defense\"]\n[Variation \"Kennedy Variation\"]\n\n1. e4 Nc6 2. d4 e5 *"
        )
        assertNotNull(imported)
        assertEquals("B00", imported?.eco)
        val b = ChesslibHelper.parseBoard()
        for (m in listOf("e4", "Nc6", "d4", "e5")) {
            ChesslibHelper.applySan(b, m)
        }
        assertEquals("Nimzowitsch Defense", customBook.isPositionInTheory(b.fen)?.name)
    }

    // =========================================================================
    // 5. VariationTree — Deletion Promotion & PGN Round-Trip
    // =========================================================================

    @Test
    fun `variation tree promotes variation on mainline delete and round-trips PGN`() {
        var root = VariationTree.createRootNode()
        val m1 = VariationTree.addSanOrUciMoveToTree(root, "root", "e4")
        root = m1.updatedRoot
        val e4 = m1.newNode!!

        val m2 = VariationTree.addSanOrUciMoveToTree(root, e4.id, "e5")
        root = m2.updatedRoot
        val e5 = m2.newNode!!

        root = VariationTree.addSanOrUciMoveToTree(root, e5.id, "Nf3").updatedRoot

        val m3 = VariationTree.addSanOrUciMoveToTree(root, e4.id, "c5")
        root = m3.updatedRoot
        val c5 = m3.newNode!!

        root = VariationTree.addSanOrUciMoveToTree(root, c5.id, "Nc3").updatedRoot

        val afterDelete = VariationTree.deleteVariationNode(root, e5.id)
        val e4After = VariationTree.findNodeById(afterDelete, e4.id)!!
        assertEquals(1, e4After.children.size)
        assertEquals("c5", e4After.children[0].san)
        assertTrue(e4After.children[0].isMainline)
        assertEquals("Nc3", e4After.children[0].children[0].san)
        assertTrue(e4After.children[0].children[0].isMainline)

        // Export + Re-import round-trip with comments and variations
        var r2 = VariationTree.createRootNode()
        val r2m1 = VariationTree.addSanOrUciMoveToTree(r2, "root", "e4")
        r2 = r2m1.updatedRoot
        val r2e4 = r2m1.newNode!!

        val r2m2 = VariationTree.addSanOrUciMoveToTree(r2, r2e4.id, "e5")
        r2 = r2m2.updatedRoot
        val r2e5 = VariationTree.findNodeById(r2, r2m2.newNode!!.id)!!
        r2e5.comment = "Open game"

        val r2m3 = VariationTree.addSanOrUciMoveToTree(r2, r2e4.id, "c5")
        r2 = r2m3.updatedRoot
        val r2c5 = r2m3.newNode!!
        r2 = VariationTree.addSanOrUciMoveToTree(r2, r2c5.id, "Nf3").updatedRoot

        val pgn = VariationTree.exportTreeToPgn(
            r2,
            mapOf("White" to "Player A", "Black" to "Player B", "Result" to "*")
        )
        assertTrue(pgn.contains("1. e4 e5 { Open game } (1... c5 2. Nf3) *"))

        val reimported = VariationTree.parsePgnMovetextToTree(pgn)
        val reE4 = reimported.children[0]
        assertEquals("e4", reE4.san)
        assertEquals(2, reE4.children.size)
        assertEquals("e5", reE4.children[0].san)
        assertTrue(reE4.children[0].isMainline)
        assertEquals("Open game", reE4.children[0].comment)
        assertEquals("c5", reE4.children[1].san)
        assertFalse(reE4.children[1].isMainline)
        assertEquals("Nf3", reE4.children[1].children[0].san)
    }

    // =========================================================================
    // 6. Archive Merging, Deduplication & Deterministic PGN Metadata ID
    // =========================================================================

    @Test
    fun `merges over 200 chesscom games without cap and produces deterministic metadata ids`() {
        val month1 = List(166) { i ->
            ChessComGameItem(
                url = "https://www.chess.com/game/live/${100000 + i}",
                pgn = "[Event \"Live Chess\"]\n[White \"PlayerA\"]\n[Black \"PlayerB\"]\n\n1. e4 e5 *",
                timeControl = "180",
                endTime = 1700000000L + i * 60L,
                rated = true,
                timeClass = "blitz",
                rules = "chess",
                white = ChessComPlayerSide(rating = 1500, result = "win", username = "PlayerA"),
                black = ChessComPlayerSide(rating = 1490, result = "checkmated", username = "PlayerB")
            )
        }
        val month2 = buildList {
            addAll(month1.take(10))
            for (i in 0 until 120) {
                add(
                    ChessComGameItem(
                        url = "https://www.chess.com/game/live/${200000 + i}",
                        pgn = "[Event \"Live Chess\"]\n[White \"PlayerA\"]\n[Black \"PlayerC\"]\n\n1. d4 d5 *",
                        timeControl = "600",
                        endTime = 1690000000L + i * 60L,
                        rated = true,
                        timeClass = "rapid",
                        rules = "chess",
                        white = ChessComPlayerSide(rating = 1510, result = "win", username = "PlayerA"),
                        black = ChessComPlayerSide(rating = 1505, result = "resigned", username = "PlayerC")
                    )
                )
            }
            add(
                ChessComGameItem(
                    url = "https://www.chess.com/game/live/999999",
                    pgn = "[Event \"Live Chess\"]\n[White \"PlayerA\"]\n[Black \"NewOpponent\"]\n\n1. e4 c5 1-0",
                    timeControl = "180",
                    endTime = 1710000000L,
                    rated = true,
                    timeClass = "blitz",
                    rules = "chess",
                    white = ChessComPlayerSide(rating = 1520, result = "win", username = "PlayerA"),
                    black = ChessComPlayerSide(rating = 1515, result = "checkmated", username = "NewOpponent")
                )
            )
        }

        val merged = ChessComApiService.mergeAndDeduplicateGames(month1, month2)
        assertEquals(287, merged.size)
        assertEquals("https://www.chess.com/game/live/999999", merged[0].url)

        val p1 = PgnService.parsePgnToTree(PgnService.SAMPLE_MASTER_GAMES[0].pgn)
        val p2 = PgnService.parsePgnToTree(PgnService.SAMPLE_MASTER_GAMES[0].pgn)
        assertEquals(p1.metadata.id, p2.metadata.id)
    }

    // =========================================================================
    // 7. Repertoire Analysis — Identification, Selection, Stats & Continuations
    // =========================================================================

    @Test
    fun `repertoire analyzer detects transpositions filters games computes WDL stats and distinguishes sidelines`() {
        val book = OpeningBookService()
        val openingAnalyzer = OpeningAnalyzer(book)

        val multiPgn = listOf(
            "[Event \"Test 1\"]\n[White \"HeroPlayer\"]\n[Black \"Opponent1\"]\n[Result \"1-0\"]\n[ECO \"A00\"]\n[Opening \"Wrong Header\"]\n\n1. Nf3 Nc6 2. e4 e5 3. Bc4 Bc5 4. c3 Nf6 1-0",
            "[Event \"Test 2\"]\n[White \"HeroPlayer\"]\n[Black \"Opponent2\"]\n[Result \"0-1\"]\n\n1. h4 a5 2. Rh3 Ra6 3. Rg3 0-1"
        ).joinToString("\n\n")

        val (games, _) = PlayerGameSelectionService.convertPgnTextToSourceGames(multiPgn, "HeroPlayer")
        assertEquals(2, games.size)

        val rec1 = openingAnalyzer.analyzeGameOpeningForRepertoire(games[0])
        assertEquals("C54", rec1.eco)
        assertFalse(rec1.isUndefinedOpening)
        assertTrue(rec1.theoryDepthPly >= 6)

        val rec2 = openingAnalyzer.analyzeGameOpeningForRepertoire(games[1])
        assertTrue(rec2.isUndefinedOpening)
        assertEquals("Неопределённый дебют", rec2.openingName)

        // Filtering preview test
        val filterPgn = listOf(
            "[Event \"Blitz White\"]\n[White \"HeroPlayer\"]\n[Black \"OppA\"]\n[Result \"1-0\"]\n[TimeControl \"180\"]\n[Date \"2025.02.10\"]\n\n1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 1-0",
            "[Event \"Rapid Black\"]\n[White \"OppB\"]\n[Black \"HeroPlayer\"]\n[Result \"0-1\"]\n[TimeControl \"600\"]\n[Date \"2025.02.12\"]\n\n1. d4 Nf6 2. c4 g6 3. Nc3 Bg7 0-1",
            "[Event \"Bullet White\"]\n[White \"HeroPlayer\"]\n[Black \"OppC\"]\n[Result \"1/2-1/2\"]\n[TimeControl \"60\"]\n[Date \"2025.02.14\"]\n\n1. e4 e5 2. Nf3 Nc6 1/2-1/2"
        ).joinToString("\n\n")
        val (filterGames, _) = PlayerGameSelectionService.convertPgnTextToSourceGames(filterPgn, "HeroPlayer")
        val (selectedGames, preview) = PlayerGameSelectionService.filterAndPreviewGames(
            pool = filterGames,
            filters = RepertoireFilterConfig(
                playerUsername = "HeroPlayer",
                periodPreset = RepertoirePeriodPreset.ALL,
                colorFilter = PieceColor.WHITE,
                timeClasses = setOf(RepertoireTimeClass.BLITZ),
                excludeIncompletePgn = true,
                cacheMode = RepertoireCacheMode.USE_CACHE
            ),
            cacheMap = emptyMap()
        )
        assertEquals(3, preview.totalLoadedPool)
        assertEquals(1, preview.totalMatching)
        assertEquals(1, preview.whiteCount)
        assertEquals(0, preview.blackCount)
        assertEquals(RepertoireTimeClass.BLITZ, selectedGames[0].timeClass)

        // W/D/L, tree, and sideline vs objective error detection
        val statPgn = listOf(
            "[Event \"G1\"]\n[White \"HeroPlayer\"]\n[Black \"Opp1\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. a3 Nf6 1-0",
            "[Event \"G2\"]\n[White \"Opp2\"]\n[Black \"HeroPlayer\"]\n[Result \"0-1\"]\n\n1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 0-1"
        ).joinToString("\n\n")
        val (statGames, _) = PlayerGameSelectionService.convertPgnTextToSourceGames(statPgn, "HeroPlayer")
        val g1Source = statGames.first { it.playerColor == PieceColor.WHITE }
        val g2Source = statGames.first { it.playerColor == PieceColor.BLACK }
        val recWhite = openingAnalyzer.analyzeGameOpeningForRepertoire(g1Source).copy(
            status = OpeningCacheStatus.ENGINE_ANALYZED,
            firstOutOfBookPly = 7
        )
        val recBlack = openingAnalyzer.analyzeGameOpeningForRepertoire(g2Source).copy(
            status = OpeningCacheStatus.ENGINE_ANALYZED
        )

        val whiteAnalysed = recWhite.copy(
            moveAnalyses = listOf(
                MoveAnalysis(
                    nodeId = "n7", ply = 7, moveNumber = 4, player = PieceColor.WHITE,
                    san = "a3", uci = "a2a3",
                    fenBefore = recWhite.openingMoves[5].fenAfter,
                    fenAfter = recWhite.openingMoves[6].fenAfter,
                    bestMoveUci = "c2c3", bestMoveSan = "c3",
                    evalBeforeCp = 25, evalAfterCp = 7,
                    winProbBefore = 52.0, winProbAfter = 50.5,
                    cpl = 18, winProbLoss = 1.5, moveAccuracy = 94.0,
                    category = MoveClassification.GOOD,
                    bestLineUci = listOf("c2c3"), bestLineSan = listOf("c3"),
                    depth = 14, isTheoretical = false
                )
            )
        )
        val blackAnalysed = recBlack.copy(
            moveAnalyses = listOf(
                MoveAnalysis(
                    nodeId = "b4", ply = 4, moveNumber = 2, player = PieceColor.BLACK,
                    san = "d6", uci = "d7d6",
                    fenBefore = recBlack.openingMoves[2].fenAfter,
                    fenAfter = recBlack.openingMoves[3].fenAfter,
                    bestMoveUci = "b8c6", bestMoveSan = "Nc6",
                    evalBeforeCp = 20, evalAfterCp = 195,
                    winProbBefore = 48.0, winProbAfter = 32.0,
                    cpl = 175, winProbLoss = 16.0, moveAccuracy = 51.0,
                    category = MoveClassification.MISTAKE,
                    bestLineUci = listOf("b8c6"), bestLineSan = listOf("Nc6"),
                    depth = 14, isTheoretical = false
                )
            )
        )

        val records = listOf(whiteAnalysed, blackAnalysed)
        val stats = OpeningStatisticsService.buildOpeningStatsList(records)
        assertEquals(2, stats.size)
        assertEquals(100.0, stats[0].winRate, 0.1)
        assertEquals(100.0, stats[1].winRate, 0.1)

        val whiteTree = OpeningStatisticsService.buildRepertoireMoveTree(records, PieceColor.WHITE, 10)
        assertEquals(1, whiteTree.children.size)
        assertEquals("e4", whiteTree.children[0].san)
        assertEquals(100.0, whiteTree.children[0].winRate, 0.1)

        val findings = OpeningStatisticsService.detectProblematicAndNotableContinuations(records)
        val sidelineFinding = findings.firstOrNull { it.playedSan == "a3" }
        val mistakeFinding = findings.firstOrNull { it.playedSan == "d6" }

        assertNotNull(sidelineFinding)
        assertEquals(ProblematicIssueType.THEORETICAL_SIDELINE, sidelineFinding?.issueType)
        assertEquals(false, sidelineFinding?.isObjectiveError)

        assertNotNull(mistakeFinding)
        assertEquals(true, mistakeFinding?.isObjectiveError)
        assertEquals("Nc6", mistakeFinding?.recommendedSan)
    }

    @Test
    fun `evalScore formatting covers hundredths large scores mates and terminal outcomes`() {
        assertEquals("+1.08", formatEvalScore(cp = 108, mate = null, decimals = 2))
        assertEquals("-11.5", formatEvalScore(cp = -1152, mate = null, decimals = 2))
        assertEquals("M2", formatEvalScore(cp = null, mate = 2, decimals = 2))
        assertEquals("-M2", formatEvalScore(cp = null, mate = -2, decimals = 2))
        // White checkmates Black (0-1? wait: Fool's mate is 0-1, Scholar's mate is 1-0)
        val whiteCheckmatedFen = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"
        val blackCheckmatedFen = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
        val stalemateFen = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"
        assertEquals("0-1", formatEvalScore(cp = null, mate = null, decimals = 2, fen = whiteCheckmatedFen))
        assertEquals("1-0", formatEvalScore(cp = null, mate = null, decimals = 2, fen = blackCheckmatedFen))
        assertEquals("1/2-1/2", formatEvalScore(cp = null, mate = null, decimals = 2, fen = stalemateFen))

        val prefs = AnalyzerPreferences(
            showEvalBar = true,
            evalBarPosition = EvalBarPosition.TOP,
            evalScoreDisplay = EvalScoreDisplay.SEPARATE
        )
        assertEquals(EvalBarPosition.TOP, prefs.evalBarPosition)
        assertEquals(EvalScoreDisplay.SEPARATE, prefs.evalScoreDisplay)
    }
}
