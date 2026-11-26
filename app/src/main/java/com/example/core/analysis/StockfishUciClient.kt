package com.example.core.analysis

import com.example.core.chess.AnalysisLine
import com.example.core.chess.ChessAnalysisEngine
import com.example.core.chess.PositionEvaluation
import com.example.domain.model.PieceColor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Transport abstraction for communicating with a UCI engine (WebView Stockfish WASM/JS, native binary, or test mock).
 */
interface UciEngineTransport {
    val outputLines: SharedFlow<String>
    val errors: SharedFlow<String>
    suspend fun start()
    suspend fun sendCommand(command: String)
    suspend fun restart()
    fun close()
}

data class StockfishClientOptions(
    val initTimeoutMs: Long = 15_000L,
    val stopTimeoutMs: Long = 12_000L,
    val searchTimeoutMs: Long = 45_000L,
    val maxDepth: Int = 30
)

private data class OptionSpinLimits(
    val defaultVal: Int,
    val min: Int,
    val max: Int
)

private data class PendingSearch(
    val id: Long,
    val deferred: CompletableDeferred<SearchResult>
)

/**
 * Coroutine-safe UCI client for Stockfish (ported from StockfishClient.ts)
 * implementing both the existing [ChessAnalysisEngine] contract and the full analyzer UCI API.
 */
class StockfishUciClient(
    private val transport: UciEngineTransport,
    private val options: StockfishClientOptions = StockfishClientOptions()
) : ChessAnalysisEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()
    private val stateLock = Any()

    private val _engineState = MutableStateFlow(EngineState())
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _livePositionUpdates = MutableSharedFlow<EngineState>(extraBufferCapacity = 64)
    val livePositionUpdates: SharedFlow<EngineState> = _livePositionUpdates.asSharedFlow()

    private var config = EngineConfig(maxDepth = options.maxDepth.coerceAtLeast(MIN_ALLOWED_DEPTH))

    @Volatile
    private var isReady = false

    @Volatile
    private var isAnalyzing = false

    @Volatile
    private var isStoppingCurrentSearch = false

    @Volatile
    private var initDeferred: CompletableDeferred<Unit>? = null

    private var uciOkReceived = false
    private var currentFen: String = VariationTree.DEFAULT_START_FEN
    private var activeTurn: PieceColor = PieceColor.WHITE
    private var currentDepth = 0
    private var nps = 0L
    private var bestMoveUci: String? = null
    private var ponderUci: String? = null
    private var engineName: String = "Stockfish 16"
    private val linesMap = ConcurrentHashMap<Int, EngineLine>()

    private var appliedMultiPv = 0
    private var appliedHashMb = 0
    private var appliedThreads = 0
    private var appliedPonder: Boolean? = null
    private var engineThreadLimits: OptionSpinLimits? = null
    private var engineHashLimits: OptionSpinLimits? = null

    private val stopWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private val readyWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private var activeSearch: PendingSearch? = null
    private val searchIdGen = AtomicLong(0L)
    private val interactiveReqGen = AtomicInteger(0)

    init {
        scope.launch {
            transport.outputLines.collect { raw ->
                raw.split(Regex("\\r?\\n")).forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isNotEmpty()) {
                        handleTransportLine(line)
                    }
                }
            }
        }
        scope.launch {
            transport.errors.collect { errMsg ->
                cleanupState(IllegalStateException(errMsg.ifBlank { "Stockfish engine error" }))
            }
        }
    }

    fun getConfig(): EngineConfig = synchronized(stateLock) { config }

    fun normalizeDepth(requestedDepth: Int? = null): Int {
        val cfg = getConfig()
        val maxAllowed = maxOf(MIN_ALLOWED_DEPTH, cfg.maxDepth)
        val raw = requestedDepth ?: cfg.depth
        return raw.coerceIn(MIN_ALLOWED_DEPTH, maxAllowed)
    }

    override suspend fun initialize(): Boolean {
        return try {
            init()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun init() {
        if (isReady) return
        val existing = synchronized(stateLock) { initDeferred }
        if (existing != null) {
            existing.await()
            return
        }

        val deferred = CompletableDeferred<Unit>()
        synchronized(stateLock) {
            if (isReady) {
                deferred.complete(Unit)
                return@synchronized
            }
            uciOkReceived = false
            initDeferred = deferred
        }

        if (deferred.isCompleted) return

        try {
            transport.start()
            transport.sendCommand("uci")
            withTimeout(options.initTimeoutMs) {
                deferred.await()
            }
        } catch (ex: Exception) {
            val error = if (ex is IllegalStateException) ex else IllegalStateException(
                ex.message ?: "Stockfish initialization timed out",
                ex
            )
            cleanupState(error)
            throw error
        }
    }

    private suspend fun handleTransportLine(line: String) {
        if (!isReady) {
            when {
                line.startsWith("id name ") -> {
                    engineName = line.removePrefix("id name ").trim()
                }
                line.startsWith("option name ") -> {
                    parseEngineOptionLimits(line)
                }
                line == "uciok" && !uciOkReceived -> {
                    uciOkReceived = true
                    transport.sendCommand("setoption name UCI_AnalyseMode value true")
                    transport.sendCommand("setoption name Contempt value 0")
                    transport.sendCommand("setoption name Analysis Contempt value Off")
                    applyEngineOptionsRaw(getConfig())
                    transport.sendCommand("isready")
                }
                line == "readyok" && uciOkReceived -> {
                    isReady = true
                    val waiter = synchronized(stateLock) {
                        val d = initDeferred
                        initDeferred = null
                        d
                    }
                    emitState()
                    waiter?.complete(Unit)
                }
            }
        } else {
            handleUciMessage(line)
        }
    }

    private fun parseEngineOptionLimits(line: String) {
        THREADS_LIMIT_REGEX.find(line)?.let { match ->
            val def = match.groupValues[1].toIntOrNull() ?: 1
            val minVal = match.groupValues[2].toIntOrNull() ?: 1
            val maxVal = match.groupValues[3].toIntOrNull() ?: 1
            engineThreadLimits = OptionSpinLimits(def, minVal, maxVal)
            if (maxVal <= 1) {
                appliedThreads = def
            }
            return
        }

        HASH_LIMIT_REGEX.find(line)?.let { match ->
            val def = match.groupValues[1].toIntOrNull() ?: 16
            val minVal = match.groupValues[2].toIntOrNull() ?: 1
            val maxVal = match.groupValues[3].toIntOrNull() ?: 512
            engineHashLimits = OptionSpinLimits(def, minVal, maxVal)
        }
    }

    private fun isSingleThreadedBuild(): Boolean {
        val limits = engineThreadLimits
        return limits != null && limits.max <= 1
    }

    private suspend fun applyEngineOptionsRaw(cfg: EngineConfig) {
        var hashMb = clampHashMb(cfg.hashMb)
        engineHashLimits?.let { limits ->
            hashMb = hashMb.coerceIn(limits.min, limits.max)
        }

        var threads = clampThreads(cfg.threads)
        engineThreadLimits?.let { limits ->
            threads = threads.coerceIn(limits.min, limits.max)
        }

        val multiPv = clampMultiPv(cfg.multiPv)
        val ponder = cfg.ponder

        if (appliedHashMb != hashMb) {
            transport.sendCommand("setoption name Hash value $hashMb")
            appliedHashMb = hashMb
        }
        if (!isSingleThreadedBuild() && appliedThreads != threads) {
            transport.sendCommand("setoption name Threads value $threads")
            appliedThreads = threads
        }
        if (appliedMultiPv != multiPv) {
            transport.sendCommand("setoption name MultiPV value $multiPv")
            appliedMultiPv = multiPv
        }
        if (appliedPonder != ponder) {
            transport.sendCommand("setoption name Ponder value ${if (ponder) "true" else "false"}")
            appliedPonder = ponder
        }
    }

    private suspend fun waitReady() {
        if (!isReady) throw IllegalStateException("Stockfish engine is not ready")
        val deferred = CompletableDeferred<Unit>()
        synchronized(stateLock) {
            readyWaiters.add(deferred)
        }
        try {
            transport.sendCommand("isready")
            withTimeout(options.stopTimeoutMs) {
                deferred.await()
            }
        } catch (ex: Exception) {
            synchronized(stateLock) {
                readyWaiters.remove(deferred)
            }
            throw ex
        }
    }

    private fun cleanupState(reason: Throwable) {
        isReady = false
        isAnalyzing = false
        isStoppingCurrentSearch = false
        uciOkReceived = false
        appliedMultiPv = 0
        appliedHashMb = 0
        appliedThreads = 0
        appliedPonder = null
        engineThreadLimits = null
        engineHashLimits = null

        val pendingInit: CompletableDeferred<Unit>?
        val pendingSearch: PendingSearch?
        val sWaiters: List<CompletableDeferred<Unit>>
        val rWaiters: List<CompletableDeferred<Unit>>

        synchronized(stateLock) {
            pendingInit = initDeferred
            initDeferred = null
            pendingSearch = activeSearch
            activeSearch = null
            sWaiters = stopWaiters.toList()
            stopWaiters.clear()
            rWaiters = readyWaiters.toList()
            readyWaiters.clear()
        }

        pendingInit?.completeExceptionally(reason)
        pendingSearch?.deferred?.completeExceptionally(reason)
        sWaiters.forEach { it.completeExceptionally(reason) }
        rWaiters.forEach { it.completeExceptionally(reason) }

        _engineState.value = _engineState.value.copy(
            isReady = false,
            isAnalyzing = false,
            lastError = reason.message
        )
    }

    suspend fun restartWorker() {
        cleanupState(IllegalStateException("Stockfish Worker restarted"))
        transport.restart()
        init()
    }

    override suspend fun stopAnalysis() {
        commandMutex.withLock {
            try {
                stopAndWait()
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun shutdown() {
        cleanupState(IllegalStateException("StockfishUciClient shutdown"))
        transport.close()
    }

    fun stop() {
        if (isReady && isAnalyzing && !isStoppingCurrentSearch) {
            isStoppingCurrentSearch = true
            scope.launch {
                try {
                    transport.sendCommand("stop")
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun stopAndWait() {
        if (!isReady || !isAnalyzing) return

        val effectiveTimeout = if (isSingleThreadedBuild()) {
            minOf(options.stopTimeoutMs, 2500L)
        } else {
            options.stopTimeoutMs
        }

        val deferred = CompletableDeferred<Unit>()
        val shouldSendStop = synchronized(stateLock) {
            stopWaiters.add(deferred)
            if (!isStoppingCurrentSearch) {
                isStoppingCurrentSearch = true
                true
            } else {
                false
            }
        }

        try {
            if (shouldSendStop) {
                transport.sendCommand("stop")
            }
            withTimeout(effectiveTimeout) {
                deferred.await()
            }
            waitReady()
        } catch (ex: Exception) {
            synchronized(stateLock) {
                stopWaiters.remove(deferred)
            }
            restartWorker()
            throw ex
        }
    }

    private fun handleUciMessage(line: String) {
        if (line == "readyok") {
            val waiters = synchronized(stateLock) {
                val list = readyWaiters.toList()
                readyWaiters.clear()
                list
            }
            waiters.forEach { it.complete(Unit) }
            return
        }

        if (line.startsWith("info ") && line.contains(" pv ")) {
            if (!isAnalyzing || isStoppingCurrentSearch) return
            val prevDepth = currentDepth
            parseInfoLine(line)
            if (activeSearch == null || currentDepth != prevDepth) {
                emitState()
            }
            return
        }

        if (line.startsWith("bestmove ")) {
            val parts = line.split(Regex("\\s+"))
            val bestMove = parts.getOrNull(1)?.takeIf { it != "(none)" }
            val ponderIdx = parts.indexOf("ponder")
            val ponder = if (ponderIdx != -1) parts.getOrNull(ponderIdx + 1) else null

            val pendingSearch: PendingSearch?
            val sWaiters: List<CompletableDeferred<Unit>>

            synchronized(stateLock) {
                bestMoveUci = bestMove
                ponderUci = ponder
                isAnalyzing = false
                isStoppingCurrentSearch = false
                pendingSearch = activeSearch
                activeSearch = null
                sWaiters = stopWaiters.toList()
                stopWaiters.clear()
            }

            emitState()

            pendingSearch?.deferred?.complete(
                SearchResult(
                    bestMove = bestMove.orEmpty(),
                    lines = getSortedLines()
                )
            )
            sWaiters.forEach { it.complete(Unit) }
        }
    }

    private fun parseInfoLine(line: String) {
        if (BOUND_REGEX.containsMatchIn(line) && linesMap.isNotEmpty()) {
            return
        }

        val depth = DEPTH_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val multipv = MULTIPV_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        NPS_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { nps = it }
        val nodes = NODES_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
        val timeMs = TIME_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()

        val perspectiveSign = if (activeTurn == PieceColor.WHITE) 1 else -1

        var cp: Int? = null
        var mate: Int? = null

        val cpMatch = SCORE_CP_REGEX.find(line)
        val mateMatch = SCORE_MATE_REGEX.find(line)

        if (cpMatch != null) {
            val rawCp = cpMatch.groupValues[1].toIntOrNull() ?: 0
            cp = if (rawCp == 0) 0 else rawCp * perspectiveSign
        } else if (mateMatch != null) {
            val rawMate = mateMatch.groupValues[1].toIntOrNull() ?: 0
            mate = if (rawMate == 0) 0 else rawMate * perspectiveSign
            cp = null
        }

        val pvIndex = line.indexOf(" pv ")
        val pvString = if (pvIndex != -1) line.substring(pvIndex + 4).trim() else ""
        val pv = if (pvString.isNotEmpty()) pvString.split(Regex("\\s+")) else emptyList()
        val pvSan = ChesslibHelper.uciLineToSan(currentFen, pv, 8)

        if (multipv == 1) {
            currentDepth = maxOf(currentDepth, depth)
            if (pv.isNotEmpty()) {
                bestMoveUci = pv[0]
            }
        }

        val maxAllowedMultiPv = maxOf(appliedMultiPv, getConfig().multiPv, 1)
        if (multipv <= maxAllowedMultiPv) {
            val existing = linesMap[multipv]
            if (existing == null || depth >= existing.depth) {
                linesMap[multipv] = EngineLine(
                    multipv = multipv,
                    depth = depth,
                    cp = cp,
                    mate = mate,
                    pv = pv,
                    pvSan = pvSan,
                    nps = nps,
                    nodes = nodes,
                    timeMs = timeMs
                )
            }
        }
    }

    suspend fun updateConfig(
        depth: Int? = null,
        maxDepth: Int? = null,
        movetimeMs: Int? = null,
        multiPv: Int? = null,
        hashMb: Int? = null,
        threads: Int? = null,
        ponder: Boolean? = null
    ) {
        val nextCfg = synchronized(stateLock) {
            val resolvedMax = maxDepth?.coerceAtLeast(MIN_ALLOWED_DEPTH) ?: config.maxDepth
            val resolvedDepth = (depth ?: config.depth).coerceIn(MIN_ALLOWED_DEPTH, resolvedMax)
            config.copy(
                depth = resolvedDepth,
                maxDepth = resolvedMax,
                movetimeMs = movetimeMs?.coerceAtLeast(0) ?: config.movetimeMs,
                multiPv = multiPv?.let(::clampMultiPv) ?: config.multiPv,
                hashMb = hashMb?.let(::clampHashMb) ?: config.hashMb,
                threads = threads?.let(::clampThreads) ?: config.threads,
                ponder = ponder ?: config.ponder
            ).also { config = it }
        }

        if (!isReady) return

        commandMutex.withLock {
            if (!isReady) return@withLock
            try {
                stopAndWait()
            } catch (_: Exception) {
            }
            applyEngineOptionsRaw(nextCfg)
            waitReady()
        }
    }

    suspend fun newGame() {
        commandMutex.withLock {
            init()
            try {
                stopAndWait()
            } catch (_: Exception) {
            }
            transport.sendCommand("ucinewgame")
            waitReady()
        }
    }

    private fun checkTerminalPosition(fen: String): List<EngineLine>? {
        val outcome = ChesslibHelper.terminalOutcomeFromFen(fen) ?: return null
        return when (outcome) {
            is TerminalBoardOutcome.Checkmate -> listOf(
                EngineLine(
                    multipv = 1,
                    depth = 0,
                    cp = null,
                    mate = if (outcome.winner == PieceColor.BLACK) -1 else 1,
                    pv = emptyList(),
                    pvSan = emptyList()
                )
            )
            TerminalBoardOutcome.Draw -> listOf(
                EngineLine(
                    multipv = 1,
                    depth = 0,
                    cp = 0,
                    mate = null,
                    pv = emptyList(),
                    pvSan = emptyList()
                )
            )
        }
    }

    fun analyzePositionInteractive(
        fen: String,
        overrideDepth: Int? = null,
        overrideMovetimeMs: Int? = null
    ) {
        val requestId = interactiveReqGen.incrementAndGet()
        scope.launch {
            try {
                commandMutex.withLock {
                    init()
                    if (requestId != interactiveReqGen.get()) return@withLock

                    try {
                        stopAndWait()
                    } catch (_: Exception) {
                    }
                    if (requestId != interactiveReqGen.get()) return@withLock

                    currentFen = fen
                    activeTurn = ChesslibHelper.sideToMove(fen)
                    currentDepth = 0
                    bestMoveUci = null
                    ponderUci = null
                    linesMap.clear()

                    val terminal = checkTerminalPosition(fen)
                    if (terminal != null) {
                        isAnalyzing = false
                        linesMap[1] = terminal[0]
                        emitState()
                        return@withLock
                    }

                    val cfg = getConfig()
                    val depth = normalizeDepth(overrideDepth ?: cfg.depth)
                    val movetime = overrideMovetimeMs ?: cfg.movetimeMs

                    applyEngineOptionsRaw(cfg)
                    waitReady()
                    if (requestId != interactiveReqGen.get()) return@withLock

                    searchIdGen.incrementAndGet()
                    isAnalyzing = true
                    isStoppingCurrentSearch = false
                    emitState()

                    transport.sendCommand("position fen $fen")
                    if (movetime > 0) {
                        transport.sendCommand("go movetime $movetime")
                    } else if (isSingleThreadedBuild()) {
                        val budgetMs = minOf(3500, maxOf(800, depth * 120))
                        transport.sendCommand("go depth $depth movetime $budgetMs")
                    } else {
                        transport.sendCommand("go depth $depth")
                    }
                }
            } catch (ex: Exception) {
                _engineState.value = _engineState.value.copy(
                    isAnalyzing = false,
                    lastError = ex.message
                )
            }
        }
    }

    override fun analyzePosition(fen: String, maxDepth: Int, multiPv: Int): Flow<PositionEvaluation> = callbackFlow {
        val job = scope.launch {
            updateConfig(depth = maxDepth, multiPv = multiPv)
            analyzePositionInteractive(fen, overrideDepth = maxDepth)
            livePositionUpdates.collect { state ->
                if (state.fen == fen) {
                    trySend(
                        PositionEvaluation(
                            fen = fen,
                            depth = state.currentDepth,
                            lines = state.lines.map { line ->
                                AnalysisLine(
                                    rank = line.multipv,
                                    scoreCentipawns = line.cp,
                                    mateInMoves = line.mate,
                                    bestMoveUci = line.pv.firstOrNull().orEmpty(),
                                    principalVariation = line.pv,
                                    depth = line.depth
                                )
                            }
                        )
                    )
                }
            }
        }
        awaitClose {
            job.cancel()
            stop()
        }
    }

    suspend fun evaluateFenAsync(
        fen: String,
        depth: Int? = null,
        multiPv: Int = 2
    ): SearchResult {
        interactiveReqGen.incrementAndGet()
        return commandMutex.withLock {
            init()
            try {
                stopAndWait()
            } catch (_: Exception) {
            }

            checkTerminalPosition(fen)?.let { terminal ->
                return@withLock SearchResult(bestMove = "", lines = terminal)
            }

            val cfg = getConfig()
            val targetDepth = normalizeDepth(depth ?: cfg.depth)
            val targetMultiPv = clampMultiPv(multiPv)

            if (appliedMultiPv != targetMultiPv) {
                transport.sendCommand("setoption name MultiPV value $targetMultiPv")
                appliedMultiPv = targetMultiPv
            }
            waitReady()

            val searchId = searchIdGen.incrementAndGet()
            val deferred = CompletableDeferred<SearchResult>()

            synchronized(stateLock) {
                currentFen = fen
                activeTurn = ChesslibHelper.sideToMove(fen)
                currentDepth = 0
                bestMoveUci = null
                ponderUci = null
                linesMap.clear()
                isAnalyzing = true
                isStoppingCurrentSearch = false
                activeSearch = PendingSearch(searchId, deferred)
            }
            emitState()

            try {
                transport.sendCommand("position fen $fen")
                if (isSingleThreadedBuild()) {
                    val batchBudgetMs = if (cfg.movetimeMs > 0) {
                        cfg.movetimeMs
                    } else {
                        minOf(1000, maxOf(250, targetDepth * 35))
                    }
                    transport.sendCommand("go depth $targetDepth movetime $batchBudgetMs")
                } else {
                    transport.sendCommand("go depth $targetDepth")
                }

                withTimeout(options.searchTimeoutMs) {
                    deferred.await()
                }
            } catch (ex: Exception) {
                val shouldRestart = synchronized(stateLock) {
                    val active = activeSearch
                    if (active != null && active.id == searchId) {
                        activeSearch = null
                        isAnalyzing = false
                        true
                    } else {
                        false
                    }
                }
                if (shouldRestart) {
                    try {
                        restartWorker()
                    } catch (_: Exception) {
                    }
                }
                throw ex
            }
        }
    }

    fun getSortedLines(): List<EngineLine> =
        linesMap.values.sortedBy { it.multipv }

    private fun emitState() {
        val state = EngineState(
            isReady = isReady,
            isAnalyzing = isAnalyzing,
            currentDepth = currentDepth,
            nps = nps,
            bestMoveUci = bestMoveUci,
            ponderUci = ponderUci,
            lines = getSortedLines(),
            fen = currentFen,
            engineName = engineName,
            lastError = null
        )
        _engineState.value = state
        _livePositionUpdates.tryEmit(state)
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 30
        const val MIN_ALLOWED_DEPTH = 1
        const val MIN_HASH_MB = 1
        const val MAX_HASH_MB = 2048
        const val MIN_THREADS = 1
        const val MAX_THREADS = 32
        const val MIN_MULTIPV = 1
        const val MAX_MULTIPV = 10

        fun clampHashMb(hashMb: Int): Int = hashMb.coerceIn(MIN_HASH_MB, MAX_HASH_MB)
        fun clampThreads(threads: Int): Int = threads.coerceIn(MIN_THREADS, MAX_THREADS)
        fun clampMultiPv(multiPv: Int): Int = multiPv.coerceIn(MIN_MULTIPV, MAX_MULTIPV)

        private val THREADS_LIMIT_REGEX = Regex(
            "^option\\s+name\\s+Threads\\s+type\\s+spin\\s+default\\s+(\\d+)\\s+min\\s+(\\d+)\\s+max\\s+(\\d+)",
            RegexOption.IGNORE_CASE
        )
        private val HASH_LIMIT_REGEX = Regex(
            "^option\\s+name\\s+Hash\\s+type\\s+spin\\s+default\\s+(\\d+)\\s+min\\s+(\\d+)\\s+max\\s+(\\d+)",
            RegexOption.IGNORE_CASE
        )
        private val BOUND_REGEX = Regex("\\b(upperbound|lowerbound)\\b")
        private val DEPTH_REGEX = Regex("\\bdepth\\s+(\\d+)")
        private val MULTIPV_REGEX = Regex("\\bmultipv\\s+(\\d+)")
        private val NPS_REGEX = Regex("\\bnps\\s+(\\d+)")
        private val NODES_REGEX = Regex("\\bnodes\\s+(\\d+)")
        private val TIME_REGEX = Regex("\\btime\\s+(\\d+)")
        private val SCORE_CP_REGEX = Regex("\\bscore\\s+cp\\s+(-?\\d+)")
        private val SCORE_MATE_REGEX = Regex("\\bscore\\s+mate\\s+(-?\\d+)")
    }
}
