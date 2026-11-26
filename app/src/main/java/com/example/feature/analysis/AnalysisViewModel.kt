package com.example.feature.analysis

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.analysis.AccuracyCalculator
import com.example.core.analysis.AnalyzerPreferences
import com.example.core.analysis.AnalyzerStorageService
import com.example.core.analysis.ChessComApiService
import com.example.core.analysis.ChessComGameItem
import com.example.core.analysis.ChessComProfile
import com.example.core.analysis.ChesslibHelper
import com.example.core.analysis.ClassificationThresholds
import com.example.core.analysis.EngineConfig
import com.example.core.analysis.EngineState
import com.example.core.analysis.GameMetadata
import com.example.core.analysis.GamePlatform
import com.example.core.analysis.GameStatistics
import com.example.core.analysis.LichessExplorerData
import com.example.core.analysis.LichessOpeningService
import com.example.core.analysis.MoveAnalyzerService
import com.example.core.analysis.MoveClassifier
import com.example.core.analysis.MoveNode
import com.example.core.analysis.OpeningAnalysisCacheRecord
import com.example.core.analysis.OpeningAnalysisSummary
import com.example.core.analysis.OpeningAnalyzer
import com.example.core.analysis.OpeningBookEntry
import com.example.core.analysis.OpeningBookService
import com.example.core.analysis.OpeningRepertoireAggregation
import com.example.core.analysis.PgnService
import com.example.core.analysis.PlayerGameSelectionService
import com.example.core.analysis.RepertoireAnalyzerService
import com.example.core.analysis.RepertoireCacheMode
import com.example.core.analysis.RepertoireFilterConfig
import com.example.core.analysis.RepertoirePeriodPreset
import com.example.core.analysis.RepertoireSourceGame
import com.example.core.analysis.RepertoireTimeClass
import com.example.core.analysis.SavedAnalyzerGameRecord
import com.example.core.analysis.SelectionPreviewSummary
import com.example.core.analysis.SquareHighlight
import com.example.core.analysis.StockfishUciClient
import com.example.core.analysis.UserArrow
import com.example.core.analysis.VariationTree
import com.example.core.ui.board.BoardMove
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.ChessBoardState
import com.example.core.ui.board.FenBoardParser
import com.example.core.ui.board.LegalTarget
import com.example.core.ui.board.PieceType
import com.example.domain.model.AppSettings
import com.example.domain.model.GameResult
import com.example.domain.model.GameSummary
import com.example.domain.model.PieceColor
import com.example.domain.repository.GameRepository
import com.example.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AnalyzerWorkspace {
    ANALYZER,
    REPERTOIRE
}

enum class AnalysisRightTab(val title: String) {
    MOVES("Ходы"),
    ENGINE("Stockfish"),
    CHART("График"),
    STATS("Точность"),
    INFO("Партия")
}

enum class RepertoireTab(val title: String) {
    OVERVIEW("Обзор"),
    WHITE("Белые"),
    BLACK("Чёрные"),
    TREE("Дерево"),
    PROBLEMATIC("Ошибки")
}

@Immutable
data class PendingAnalyzerPromotion(
    val from: BoardSquare,
    val to: BoardSquare,
    val color: PieceColor
)

@Immutable
data class AnalyzerUiState(
    val isInitializing: Boolean = true,
    val workspace: AnalyzerWorkspace = AnalyzerWorkspace.ANALYZER,
    val rightTab: AnalysisRightTab = AnalysisRightTab.MOVES,
    val gameId: String = "",
    val metadata: GameMetadata = GameMetadata(
        id = "",
        whiteName = "Белые",
        blackName = "Чёрные",
        platform = GamePlatform.PGN,
        date = "",
        result = "*"
    ),
    val initialFen: String = VariationTree.DEFAULT_START_FEN,
    val rootNode: MoveNode = VariationTree.createRootNode(),
    val currentNodeId: String = "root",
    val boardState: ChessBoardState = ChessBoardState(
        pieces = FenBoardParser.parsePlacement(VariationTree.DEFAULT_START_FEN)
    ),
    val sideToMove: PieceColor = PieceColor.WHITE,
    val flipped: Boolean = false,
    val openingSummary: OpeningAnalysisSummary = OpeningAnalysisSummary(
        eco = "—",
        name = "Дебют ещё не определён",
        variation = "Начальная позиция",
        source = "",
        version = "",
        theoreticalMovesWhite = 0,
        theoreticalMovesBlack = 0,
        lastTheoreticalPly = 0,
        lastTheoreticalMoveSan = null,
        firstOutOfBookPly = null,
        firstOutOfBookMoveSan = null
    ),
    val statistics: GameStatistics? = null,
    val preferences: AnalyzerPreferences = AnalyzerPreferences(),
    val drawMode: BoardDrawMode = BoardDrawMode.MOVE,
    val arrowStartSquare: BoardSquare? = null,
    val activeBrushHex: String = "#F39C36",
    val pendingPromotion: PendingAnalyzerPromotion? = null,
    val isBatchAnalyzing: Boolean = false,
    val batchCompleted: Int = 0,
    val batchTotal: Int = 0,
    val isAutoPlaying: Boolean = false,
    val autoplaySpeedMs: Long = 950L,
    val lichessStats: LichessExplorerData? = null,
    val savedGames: List<SavedAnalyzerGameRecord> = emptyList(),
    val roomGames: List<GameSummary> = emptyList(),
    val chessComProfile: ChessComProfile? = null,
    val chessComArchives: List<String> = emptyList(),
    val selectedChessComArchiveUrl: String? = null,
    val chessComGames: List<ChessComGameItem> = emptyList(),
    val isChessComLoading: Boolean = false,
    val chessComError: String? = null,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
    // Repertoire module state
    val repertoireTab: RepertoireTab = RepertoireTab.OVERVIEW,
    val repertoirePool: List<RepertoireSourceGame> = emptyList(),
    val repertoireFilters: RepertoireFilterConfig = RepertoireFilterConfig(),
    val repertoirePreview: SelectionPreviewSummary = SelectionPreviewSummary(),
    val repertoireAggregation: OpeningRepertoireAggregation? = null,
    val repertoireSourceLabel: String = "Локальные и сохранённые партии",
    val isRepertoireLoading: Boolean = false,
    val isRepertoireEngineRunning: Boolean = false,
    val repertoireEngineCompleted: Int = 0,
    val repertoireEngineTotal: Int = 0,
    val repertoireError: String? = null
)

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val stockfishClient: StockfishUciClient,
    private val moveAnalyzer: MoveAnalyzerService,
    private val openingBook: OpeningBookService,
    private val openingAnalyzer: OpeningAnalyzer,
    private val repertoireAnalyzer: RepertoireAnalyzerService,
    private val chessComApi: ChessComApiService,
    private val lichessApi: LichessOpeningService,
    private val storage: AnalyzerStorageService,
    private val gameRepository: GameRepository,
    observeSettingsUseCase: ObserveSettingsUseCase
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = observeSettingsUseCase().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    val engineState: StateFlow<EngineState> = stockfishClient.engineState

    private val _uiState = MutableStateFlow(AnalyzerUiState())
    val uiState: StateFlow<AnalyzerUiState> = _uiState.asStateFlow()

    private var batchJob: Job? = null
    private var autoplayJob: Job? = null
    private var repertoireJob: Job? = null
    private var selectedSquare: BoardSquare? = null
    private var boardVersion = 1L
    private var loadedInitialRoomGameId: Long? = null
    private val openingCacheMap = LinkedHashMap<String, OpeningAnalysisCacheRecord>()

    init {
        val routeGameId = savedStateHandle.get<Long>("gameId")
        if (routeGameId != null) {
            loadedInitialRoomGameId = routeGameId
        }

        viewModelScope.launch {
            observeSettingsUseCase().first()
            val prefs = storage.loadPreferences()
            val saved = storage.loadAllSavedGames()
            openingCacheMap.putAll(storage.loadOpeningCache())
            _uiState.update {
                it.copy(
                    preferences = prefs,
                    activeBrushHex = prefs.userArrowColor,
                    savedGames = saved
                )
            }
            stockfishClient.updateConfig(
                depth = prefs.engineConfig.depth,
                maxDepth = prefs.engineConfig.maxDepth,
                movetimeMs = prefs.engineConfig.movetimeMs,
                multiPv = prefs.engineConfig.multiPv,
                hashMb = prefs.engineConfig.hashMb,
                threads = prefs.engineConfig.threads,
                ponder = prefs.engineConfig.ponder
            )

            val roomGame = routeGameId?.let { gameRepository.getGameById(it) }
            if (roomGame != null) {
                loadRoomGameSummary(roomGame)
            } else {
                val initialParsed = PgnService.parsePgnToTree(PgnService.SAMPLE_MASTER_GAMES[0].pgn, GamePlatform.PGN)
                val existingSaved = saved.firstOrNull { it.id == initialParsed.metadata.id }
                applyLoadedGame(
                    gameId = initialParsed.metadata.id,
                    metadata = existingSaved?.metadata ?: initialParsed.metadata,
                    initialFen = existingSaved?.initialFen ?: initialParsed.initialFen,
                    rootNode = existingSaved?.rootNode?.deepCopy() ?: initialParsed.root,
                    statistics = existingSaved?.statistics
                )
            }

            _uiState.update { it.copy(isInitializing = false) }
        }

        viewModelScope.launch {
            gameRepository.observeGameHistory().collect { roomList ->
                _uiState.update { it.copy(roomGames = roomList) }
            }
        }
    }

    fun loadRoomGameIfRequested(gameId: Long?) {
        if (gameId == null || loadedInitialRoomGameId == gameId) return
        loadedInitialRoomGameId = gameId
        viewModelScope.launch {
            _uiState.update { it.copy(isInitializing = true) }
            val summary = gameRepository.getGameById(gameId)
            if (summary != null) {
                loadRoomGameSummary(summary)
            }
            _uiState.update { it.copy(isInitializing = false) }
        }
    }

    fun loadRoomGameSummary(summary: GameSummary) {
        val pgnToParse = if (summary.pgn.isNotBlank()) {
            summary.pgn
        } else {
            "[White \"${summary.whitePlayer}\"]\n[Black \"${summary.blackPlayer}\"]\n[Result \"${resultStringFor(summary.result)}\"]\n\n*"
        }
        loadPgnGame(pgnToParse, GamePlatform.LOCAL)
        setWorkspace(AnalyzerWorkspace.ANALYZER)
    }

    private fun resultStringFor(result: GameResult): String = when (result) {
        GameResult.WHITE_WIN -> "1-0"
        GameResult.BLACK_WIN -> "0-1"
        GameResult.DRAW -> "1/2-1/2"
        else -> "*"
    }

    fun setWorkspace(workspace: AnalyzerWorkspace) {
        _uiState.update { it.copy(workspace = workspace) }
        if (workspace == AnalyzerWorkspace.REPERTOIRE && _uiState.value.repertoirePool.isEmpty()) {
            loadRepertoireFromSavedGames()
        }
    }

    fun setRightTab(tab: AnalysisRightTab) {
        _uiState.update { it.copy(rightTab = tab) }
    }

    fun setRepertoireTab(tab: RepertoireTab) {
        _uiState.update { it.copy(repertoireTab = tab) }
    }

    fun flipBoard() {
        _uiState.update { it.copy(flipped = !it.flipped) }
    }

    fun setDrawMode(mode: BoardDrawMode) {
        selectedSquare = null
        _uiState.update {
            it.copy(
                drawMode = mode,
                arrowStartSquare = null,
                boardState = rebuildBoardState(
                    currentNode = currentNode(),
                    selected = null
                )
            )
        }
    }

    fun setActiveBrushColor(hex: String) {
        _uiState.update { it.copy(activeBrushHex = hex) }
    }

    fun onAnnotationSquareTap(square: BoardSquare) {
        val state = _uiState.value
        when (state.drawMode) {
            BoardDrawMode.HIGHLIGHT -> toggleSquareHighlight(square, state.activeBrushHex)
            BoardDrawMode.ARROW -> {
                val start = state.arrowStartSquare
                if (start == null) {
                    _uiState.update { it.copy(arrowStartSquare = square) }
                } else {
                    if (start != square) {
                        toggleUserArrow(start.algebraic, square.algebraic, state.activeBrushHex)
                    }
                    _uiState.update { it.copy(arrowStartSquare = null) }
                }
            }
            BoardDrawMode.MOVE -> Unit
        }
    }

    private fun toggleUserArrow(from: String, to: String, colorHex: String) {
        val cloned = _uiState.value.rootNode.deepCopy()
        val curr = VariationTree.findNodeById(cloned, _uiState.value.currentNodeId) ?: return
        val existingIdx = curr.userArrows.indexOfFirst { it.from == from && it.to == to && it.color == colorHex }
        if (existingIdx != -1) {
            curr.userArrows.removeAt(existingIdx)
        } else {
            curr.userArrows.removeAll { it.from == from && it.to == to }
            curr.userArrows.add(
                UserArrow(
                    id = "arrow_${System.currentTimeMillis()}_${curr.userArrows.size}",
                    from = from,
                    to = to,
                    color = colorHex
                )
            )
        }
        _uiState.update { it.copy(rootNode = cloned) }
        persistCurrentGameAsync()
    }

    private fun toggleSquareHighlight(square: BoardSquare, colorHex: String) {
        val cloned = _uiState.value.rootNode.deepCopy()
        val curr = VariationTree.findNodeById(cloned, _uiState.value.currentNodeId) ?: return
        val alg = square.algebraic
        val idx = curr.squareHighlights.indexOfFirst { it.square == alg }
        if (idx != -1) {
            if (curr.squareHighlights[idx].color == colorHex) {
                curr.squareHighlights.removeAt(idx)
            } else {
                curr.squareHighlights[idx] = SquareHighlight(alg, colorHex)
            }
        } else {
            curr.squareHighlights.add(SquareHighlight(alg, colorHex))
        }
        _uiState.update { it.copy(rootNode = cloned) }
        persistCurrentGameAsync()
    }

    fun clearNodeAnnotations() {
        val cloned = _uiState.value.rootNode.deepCopy()
        val curr = VariationTree.findNodeById(cloned, _uiState.value.currentNodeId) ?: return
        curr.userArrows.clear()
        curr.squareHighlights.clear()
        _uiState.update { it.copy(rootNode = cloned, arrowStartSquare = null) }
        persistCurrentGameAsync()
    }

    fun startNewEmptyGame() {
        cancelFullGameAnalysis()
        val parsed = PgnService.createNewEmptyGame()
        applyLoadedGame(parsed.metadata.id, parsed.metadata, parsed.initialFen, parsed.root, null)
        persistCurrentGameAsync()
    }

    fun loadPgnGame(
        pgnText: String,
        platform: GamePlatform = GamePlatform.PGN,
        extraTitles: Pair<String?, String?>? = null
    ) {
        try {
            cancelFullGameAnalysis()
            val parsed = PgnService.parsePgnToTree(pgnText, platform, extraTitles)
            val existingSaved = _uiState.value.savedGames.firstOrNull { it.id == parsed.metadata.id }
            val openingSummary = openingBook.analyzeMainlineOpening(VariationTree.getMainlineNodes(parsed.root))
            val enrichedMeta = parsed.metadata.copy(
                eco = openingSummary.eco,
                openingName = "${openingSummary.name}: ${openingSummary.variation}"
            )
            applyLoadedGame(
                gameId = parsed.metadata.id,
                metadata = enrichedMeta,
                initialFen = parsed.initialFen,
                rootNode = existingSaved?.rootNode?.deepCopy() ?: parsed.root,
                statistics = existingSaved?.statistics
            )
            persistCurrentGameAsync()
        } catch (ex: Exception) {
            _uiState.update { it.copy(errorMessage = ex.message ?: "Не удалось загрузить PGN") }
        }
    }

    fun loadFenPosition(fen: String) {
        try {
            cancelFullGameAnalysis()
            val parsed = PgnService.createTreeFromFen(fen)
            applyLoadedGame(parsed.metadata.id, parsed.metadata, parsed.initialFen, parsed.root, null)
            persistCurrentGameAsync()
        } catch (ex: Exception) {
            _uiState.update { it.copy(errorMessage = ex.message ?: "Некорректный FEN") }
        }
    }

    fun loadSavedGameRecord(record: SavedAnalyzerGameRecord) {
        cancelFullGameAnalysis()
        applyLoadedGame(
            gameId = record.id,
            metadata = record.metadata,
            initialFen = record.initialFen,
            rootNode = record.rootNode.deepCopy(),
            statistics = record.statistics
        )
        setWorkspace(AnalyzerWorkspace.ANALYZER)
    }

    fun deleteSavedGame(id: String) {
        viewModelScope.launch {
            val remaining = storage.deleteGame(id)
            _uiState.update { it.copy(savedGames = remaining) }
        }
    }

    fun addCustomOpeningEntry(eco: String, name: String, variation: String, movesText: String): Boolean {
        val sanList = movesText.trim()
            .replace(Regex("\\d+\\.(\\.\\.)?"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (name.isBlank() || sanList.isEmpty()) return false
        openingBook.addCustomEntries(
            listOf(
                OpeningBookEntry(
                    eco = eco.ifBlank { "A00" },
                    name = name.trim(),
                    variation = variation.ifBlank { "Пользовательский вариант" },
                    pgnMoves = sanList
                )
            )
        )
        val summary = computeOpeningForContext(_uiState.value.rootNode, _uiState.value.currentNodeId)
        _uiState.update {
            it.copy(
                openingSummary = summary,
                statusNotice = "Дебют «${name.trim()}» добавлен в базу ECO"
            )
        }
        return true
    }

    fun selectNode(nodeId: String) {
        val root = _uiState.value.rootNode
        val target = VariationTree.findNodeById(root, nodeId) ?: return
        selectedSquare = null
        val summary = computeOpeningForContext(root, target.id)
        _uiState.update {
            it.copy(
                currentNodeId = target.id,
                openingSummary = summary,
                arrowStartSquare = null,
                boardState = rebuildBoardState(target, null),
                sideToMove = ChesslibHelper.sideToMove(target.fen)
            )
        }
        onActivePositionChanged(target)
    }

    fun goToStart() = selectNode(_uiState.value.rootNode.id)

    fun goToPrev() {
        val curr = currentNode()
        curr.parentId?.let { selectNode(it) }
    }

    fun goToNext() {
        val curr = currentNode()
        val next = curr.children.firstOrNull()
        if (next != null) {
            selectNode(next.id)
        } else {
            stopAutoplay()
        }
    }

    fun goToEnd() {
        var curr = currentNode()
        while (curr.children.isNotEmpty()) {
            curr = curr.children[0]
        }
        stopAutoplay()
        selectNode(curr.id)
    }

    fun returnToMainline() {
        val root = _uiState.value.rootNode
        var curr: MoveNode? = currentNode()
        while (curr != null && !curr.isMainline && curr.parentId != null) {
            curr = VariationTree.findNodeById(root, curr.parentId)
        }
        curr?.let { selectNode(it.id) }
    }

    fun deleteVariation(nodeId: String) {
        val root = _uiState.value.rootNode
        val target = VariationTree.findNodeById(root, nodeId) ?: return
        if (target.id == "root") return
        val parentId = target.parentId ?: "root"
        val updated = VariationTree.deleteVariationNode(root, nodeId)
        val nextId = if (VariationTree.findNodeById(updated, _uiState.value.currentNodeId) != null) {
            _uiState.value.currentNodeId
        } else {
            parentId
        }
        val activeNode = VariationTree.findNodeById(updated, nextId) ?: updated
        selectedSquare = null
        _uiState.update {
            it.copy(
                rootNode = updated,
                currentNodeId = activeNode.id,
                openingSummary = computeOpeningForContext(updated, activeNode.id),
                boardState = rebuildBoardState(activeNode, null),
                sideToMove = ChesslibHelper.sideToMove(activeNode.fen)
            )
        }
        onActivePositionChanged(activeNode)
        persistCurrentGameAsync()
    }

    fun toggleAutoplay() {
        if (_uiState.value.isAutoPlaying) {
            stopAutoplay()
        } else {
            _uiState.update { it.copy(isAutoPlaying = true) }
            autoplayJob?.cancel()
            autoplayJob = viewModelScope.launch {
                while (isActive && _uiState.value.isAutoPlaying) {
                    delay(_uiState.value.autoplaySpeedMs)
                    val curr = currentNode()
                    val next = curr.children.firstOrNull()
                    if (next != null) {
                        selectNode(next.id)
                    } else {
                        stopAutoplay()
                    }
                }
            }
        }
    }

    private fun stopAutoplay() {
        autoplayJob?.cancel()
        autoplayJob = null
        _uiState.update { it.copy(isAutoPlaying = false) }
    }

    fun cycleAutoplaySpeed() {
        val speeds = listOf(1600L, 950L, 600L, 350L)
        val idx = speeds.indexOf(_uiState.value.autoplaySpeedMs)
        val next = speeds[(idx + 1) % speeds.size]
        _uiState.update { it.copy(autoplaySpeedMs = next) }
    }

    fun onSquareTap(square: BoardSquare) {
        if (_uiState.value.drawMode != BoardDrawMode.MOVE) {
            onAnnotationSquareTap(square)
            return
        }

        val curr = currentNode()
        val board = try {
            ChesslibHelper.parseBoard(curr.fen)
        } catch (_: Exception) {
            return
        }
        val pieces = FenBoardParser.parsePlacement(curr.fen)
        val turn = ChesslibHelper.sideToMove(curr.fen)

        val prevSelected = selectedSquare
        if (prevSelected != null) {
            if (prevSelected == square) {
                selectedSquare = null
                _uiState.update { it.copy(boardState = rebuildBoardState(curr, null)) }
                return
            }
            if (onMoveAttempt(prevSelected, square, false)) {
                return
            }
        }

        val pieceAtTap = pieces[square]
        if (pieceAtTap != null && pieceAtTap.color == turn && !ChesslibHelper.isGameOver(board)) {
            selectedSquare = square
            _uiState.update { it.copy(boardState = rebuildBoardState(curr, square)) }
        } else {
            selectedSquare = null
            _uiState.update { it.copy(boardState = rebuildBoardState(curr, null)) }
        }
    }

    fun onMoveAttempt(from: BoardSquare, to: BoardSquare, fromDrag: Boolean): Boolean {
        if (_uiState.value.drawMode != BoardDrawMode.MOVE) return false
        val curr = currentNode()
        val board = try {
            ChesslibHelper.parseBoard(curr.fen)
        } catch (_: Exception) {
            return false
        }
        val legal = ChesslibHelper.legalMovesVerbose(board).filter {
            it.from == from.algebraic && it.to == to.algebraic
        }
        if (legal.isEmpty()) return false

        if (legal.any { it.promotion != null }) {
            selectedSquare = null
            _uiState.update {
                it.copy(
                    pendingPromotion = PendingAnalyzerPromotion(from, to, ChesslibHelper.sideToMove(curr.fen)),
                    boardState = rebuildBoardState(curr, null)
                )
            }
            return true
        }

        return executeBoardMove(from, to, null)
    }

    fun onPromotionSelected(pieceType: PieceType) {
        val pending = _uiState.value.pendingPromotion ?: return
        _uiState.update { it.copy(pendingPromotion = null) }
        executeBoardMove(pending.from, pending.to, pieceType)
    }

    fun onPromotionDismissed() {
        _uiState.update { it.copy(pendingPromotion = null) }
    }

    fun makeSanMove(sanOrUci: String): Boolean {
        val curr = currentNode()
        val res = VariationTree.addSanOrUciMoveToTree(_uiState.value.rootNode, curr.id, sanOrUci)
        val newNode = res.newNode ?: return false
        commitNewMoveNode(res.updatedRoot, newNode)
        return true
    }

    private fun executeBoardMove(from: BoardSquare, to: BoardSquare, promotion: PieceType?): Boolean {
        val curr = currentNode()
        val res = VariationTree.addMoveToTree(_uiState.value.rootNode, curr.id, from, to, promotion)
        val newNode = res.newNode ?: return false
        commitNewMoveNode(res.updatedRoot, newNode)
        return true
    }

    private fun commitNewMoveNode(updatedRoot: MoveNode, newNode: MoveNode) {
        selectedSquare = null
        val summary = computeOpeningForContext(updatedRoot, newNode.id)
        var updatedMeta = _uiState.value.metadata.copy(
            eco = summary.eco,
            openingName = "${summary.name}: ${summary.variation}"
        )

        if (newNode.isMainline) {
            when (val term = ChesslibHelper.terminalOutcomeFromFen(newNode.fen)) {
                is com.example.core.analysis.TerminalBoardOutcome.Checkmate -> {
                    updatedMeta = updatedMeta.copy(
                        result = if (newNode.color == PieceColor.WHITE) "1-0" else "0-1"
                    )
                }
                is com.example.core.analysis.TerminalBoardOutcome.Draw -> {
                    updatedMeta = updatedMeta.copy(result = "1/2-1/2")
                }
                null -> Unit
            }
        }

        _uiState.update {
            it.copy(
                rootNode = updatedRoot,
                currentNodeId = newNode.id,
                openingSummary = summary,
                metadata = updatedMeta,
                boardState = rebuildBoardState(newNode, null),
                sideToMove = ChesslibHelper.sideToMove(newNode.fen),
                errorMessage = null
            )
        }

        onActivePositionChanged(newNode)
        persistCurrentGameAsync()

        // Background single-move evaluation if not already analyzed
        if (newNode.analysis == null && !_uiState.value.isBatchAnalyzing && newNode.parentId != null) {
            val parent = VariationTree.findNodeById(updatedRoot, newNode.parentId) ?: return
            val prefs = _uiState.value.preferences
            val targetId = newNode.id
            viewModelScope.launch {
                try {
                    val analysis = moveAnalyzer.evaluateSingleMoveNode(
                        prevNode = parent,
                        currNode = newNode,
                        depth = prefs.engineConfig.depth,
                        thresholds = prefs.thresholds
                    )
                    if (_uiState.value.isBatchAnalyzing) return@launch
                    val cloned = _uiState.value.rootNode.deepCopy()
                    val target = VariationTree.findNodeById(cloned, targetId) ?: return@launch
                    target.analysis = analysis
                    val mainlineAnalyses = VariationTree.getMainlineNodes(cloned).mapNotNull { it.analysis }
                    val stats = if (mainlineAnalyses.isNotEmpty()) {
                        AccuracyCalculator.calculateGameStatistics(mainlineAnalyses)
                    } else {
                        _uiState.value.statistics
                    }
                    _uiState.update { it.copy(rootNode = cloned, statistics = stats) }
                    triggerInteractiveEngineForCurrentNode()
                    persistCurrentGameAsync()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun runFullGameAnalysis() {
        val currentRoot = _uiState.value.rootNode.deepCopy()
        val allMoveNodes = VariationTree.getAllTreeNodes(currentRoot).filter { it.ply > 0 }
        if (allMoveNodes.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Нет ходов для анализа. Сделайте ходы или загрузите партию.") }
            return
        }

        batchJob?.cancel()
        stopAutoplay()
        val prefs = _uiState.value.preferences

        _uiState.update {
            it.copy(
                isBatchAnalyzing = true,
                currentNodeId = currentRoot.id,
                batchCompleted = 0,
                batchTotal = allMoveNodes.size,
                boardState = rebuildBoardState(currentRoot, null),
                sideToMove = ChesslibHelper.sideToMove(currentRoot.fen),
                errorMessage = null
            )
        }

        batchJob = viewModelScope.launch {
            try {
                val analyses = moveAnalyzer.analyzeGameNodes(
                    rootNode = currentRoot,
                    depth = prefs.engineConfig.depth,
                    thresholds = prefs.thresholds,
                    onProgress = { completed, total, analyzedNodeId ->
                        val mainline = VariationTree.getMainlineNodes(currentRoot)
                        val partialAnalyses = mainline.mapNotNull { it.analysis }
                        val partialStats = if (partialAnalyses.isNotEmpty()) {
                            AccuracyCalculator.calculateGameStatistics(partialAnalyses)
                        } else null
                        val summary = computeOpeningForContext(currentRoot, analyzedNodeId)
                        val activeNode = VariationTree.findNodeById(currentRoot, analyzedNodeId) ?: currentRoot
                        _uiState.update {
                            it.copy(
                                batchCompleted = completed,
                                batchTotal = total,
                                rootNode = currentRoot.deepCopy(),
                                currentNodeId = analyzedNodeId,
                                openingSummary = summary,
                                statistics = partialStats,
                                boardState = rebuildBoardState(activeNode, null),
                                sideToMove = ChesslibHelper.sideToMove(activeNode.fen)
                            )
                        }
                    }
                )

                val activeId = _uiState.value.currentNodeId
                val finalStats = AccuracyCalculator.calculateGameStatistics(analyses)
                val finalSummary = computeOpeningForContext(currentRoot, activeId)
                _uiState.update {
                    it.copy(
                        rootNode = currentRoot,
                        openingSummary = finalSummary,
                        statistics = finalStats,
                        isBatchAnalyzing = false
                    )
                }
                triggerInteractiveEngineForCurrentNode()
                persistCurrentGameAsync()
            } catch (_: CancellationException) {
                _uiState.update { it.copy(isBatchAnalyzing = false) }
            } catch (ex: Exception) {
                _uiState.update {
                    it.copy(
                        isBatchAnalyzing = false,
                        errorMessage = ex.message ?: "Ошибка при анализе партии"
                    )
                }
            }
        }
    }

    fun cancelFullGameAnalysis() {
        batchJob?.cancel()
        batchJob = null
        moveAnalyzer.cancelBatchAnalysis()
        _uiState.update { it.copy(isBatchAnalyzing = false) }
    }

    fun updateAnalyzerPreferences(transform: (AnalyzerPreferences) -> AnalyzerPreferences) {
        val next = transform(_uiState.value.preferences)
        val oldThresholds = _uiState.value.preferences.thresholds
        val oldConfig = _uiState.value.preferences.engineConfig

        _uiState.update { it.copy(preferences = next) }
        viewModelScope.launch {
            storage.savePreferences(next)
            if (next.engineConfig != oldConfig) {
                stockfishClient.updateConfig(
                    depth = next.engineConfig.depth,
                    maxDepth = next.engineConfig.maxDepth,
                    movetimeMs = next.engineConfig.movetimeMs,
                    multiPv = next.engineConfig.multiPv,
                    hashMb = next.engineConfig.hashMb,
                    threads = next.engineConfig.threads,
                    ponder = next.engineConfig.ponder
                )
                triggerInteractiveEngineForCurrentNode()
            }
        }

        if (next.thresholds != oldThresholds) {
            recalculateWithThresholds(next.thresholds)
        }
    }

    fun resetAnalyzerPreferences() {
        updateAnalyzerPreferences { AnalyzerPreferences() }
    }

    private fun recalculateWithThresholds(thresholds: ClassificationThresholds) {
        val cloned = _uiState.value.rootNode.deepCopy()
        val allNodes = VariationTree.getAllTreeNodes(cloned)
        if (allNodes.none { it.analysis != null }) return

        MoveClassifier.reclassifyMoveAnalyses(allNodes, thresholds, openingBook)
        val mainline = VariationTree.getMainlineNodes(cloned)
        val analyses = mainline.mapNotNull { it.analysis }
        val stats = if (analyses.isNotEmpty()) AccuracyCalculator.calculateGameStatistics(analyses) else null
        _uiState.update {
            it.copy(
                rootNode = cloned,
                openingSummary = computeOpeningForContext(cloned, it.currentNodeId),
                statistics = stats
            )
        }
        persistCurrentGameAsync()
    }

    fun getExportPgn(): String {
        val st = _uiState.value
        val headers = linkedMapOf(
            "Event" to (st.metadata.event ?: "ChessLAN Analysis"),
            "Site" to (st.metadata.site ?: "ChessLAN"),
            "Date" to st.metadata.date,
            "White" to st.metadata.whiteName,
            "Black" to st.metadata.blackName,
            "Result" to st.metadata.result,
            "ECO" to st.openingSummary.eco,
            "Opening" to "${st.openingSummary.name}: ${st.openingSummary.variation}"
        )
        st.metadata.whiteRating?.let { headers["WhiteElo"] = it.toString() }
        st.metadata.blackRating?.let { headers["BlackElo"] = it.toString() }
        return VariationTree.exportTreeToPgn(st.rootNode, headers)
    }

    fun showNotice(message: String) = _uiState.update { it.copy(statusNotice = message, errorMessage = null) }

    fun clearError() = _uiState.update { it.copy(errorMessage = null, statusNotice = null) }

    // =========================================================================
    // Chess.com Import
    // =========================================================================

    fun loadChessComUserGames(username: String, archiveUrl: String? = null) {
        val clean = username.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val prevRecent = _uiState.value.preferences.recentChessComUsernames
            val updatedRecent = (listOf(clean) + prevRecent.filterNot { it.equals(clean, ignoreCase = true) }).take(6)
            val updatedPrefs = _uiState.value.preferences.copy(
                lastChessComUsername = clean,
                recentChessComUsernames = updatedRecent
            )
            _uiState.update {
                it.copy(
                    isChessComLoading = true,
                    chessComError = null,
                    preferences = updatedPrefs
                )
            }
            storage.savePreferences(updatedPrefs)
            try {
                val profile = chessComApi.fetchProfile(clean)
                val archives = chessComApi.fetchArchives(clean)
                val targetArchive = archiveUrl?.takeIf { it in archives } ?: archives.firstOrNull()
                val games = if (targetArchive != null) {
                    chessComApi.fetchGamesByArchiveUrl(
                        archiveUrl = targetArchive,
                        isLatestArchive = (targetArchive == archives.firstOrNull())
                    )
                } else {
                    emptyList()
                }
                _uiState.update {
                    it.copy(
                        chessComProfile = profile,
                        chessComArchives = archives,
                        selectedChessComArchiveUrl = targetArchive,
                        chessComGames = games,
                        isChessComLoading = false
                    )
                }
            } catch (ex: Exception) {
                _uiState.update {
                    it.copy(
                        isChessComLoading = false,
                        chessComError = ex.message ?: "Ошибка загрузки Chess.com"
                    )
                }
            }
        }
    }

    fun selectChessComArchive(archiveUrl: String) {
        val st = _uiState.value
        if (archiveUrl.isBlank() || archiveUrl == st.selectedChessComArchiveUrl) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedChessComArchiveUrl = archiveUrl,
                    isChessComLoading = true,
                    chessComError = null
                )
            }
            try {
                val games = chessComApi.fetchGamesByArchiveUrl(
                    archiveUrl = archiveUrl,
                    isLatestArchive = (archiveUrl == st.chessComArchives.firstOrNull())
                )
                _uiState.update {
                    it.copy(
                        chessComGames = games,
                        isChessComLoading = false
                    )
                }
            } catch (ex: Exception) {
                _uiState.update {
                    it.copy(
                        isChessComLoading = false,
                        chessComError = ex.message ?: "Ошибка загрузки архива за месяц"
                    )
                }
            }
        }
    }

    fun loadChessComGame(game: ChessComGameItem) {
        val pgn = game.pgn ?: return
        val profile = _uiState.value.chessComProfile
        val searchedUser = profile?.username
        val whiteTitle = if (searchedUser != null && game.white.username.equals(searchedUser, ignoreCase = true)) {
            profile.title
        } else null
        val blackTitle = if (searchedUser != null && game.black.username.equals(searchedUser, ignoreCase = true)) {
            profile.title
        } else null
        loadPgnGame(
            pgnText = pgn,
            platform = GamePlatform.CHESS_COM,
            extraTitles = whiteTitle to blackTitle
        )
    }

    // =========================================================================
    // Opening Repertoire Module
    // =========================================================================

    fun loadRepertoireFromSavedGames(preferredPlayer: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRepertoireLoading = true, repertoireError = null) }
            try {
                val (pool, inferredUser) = PlayerGameSelectionService.convertSavedRecordsToSourceGames(
                    analyzerRecords = _uiState.value.savedGames,
                    roomGames = _uiState.value.roomGames,
                    preferredUsername = preferredPlayer ?: _uiState.value.repertoireFilters.playerUsername.takeIf { it.isNotBlank() },
                    openingAnalyzer = openingAnalyzer
                )
                val nextFilters = _uiState.value.repertoireFilters.copy(playerUsername = inferredUser)
                rebuildRepertoireAggregation(
                    pool = pool,
                    filters = nextFilters,
                    sourceLabel = "Сохранённые партии и архив ChessLAN (${pool.size})"
                )
            } catch (ex: Exception) {
                _uiState.update { it.copy(isRepertoireLoading = false, repertoireError = ex.message) }
            }
        }
    }

    fun loadRepertoireFromChessCom(username: String, monthsLimit: Int = 3) {
        val clean = username.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRepertoireLoading = true, repertoireError = null) }
            try {
                val archives = chessComApi.fetchArchives(clean)
                val collected = mutableListOf<ChessComGameItem>()
                for (url in archives.take(monthsLimit)) {
                    collected.addAll(chessComApi.fetchGamesByArchiveUrl(url))
                }
                val merged = ChessComApiService.mergeAndDeduplicateGames(emptyList(), collected)
                val pool = PlayerGameSelectionService.convertChessComGamesToSourceGames(merged, clean, openingAnalyzer)
                val nextFilters = _uiState.value.repertoireFilters.copy(playerUsername = clean)
                rebuildRepertoireAggregation(
                    pool = pool,
                    filters = nextFilters,
                    sourceLabel = "Chess.com · $clean (${pool.size})"
                )
            } catch (ex: Exception) {
                _uiState.update { it.copy(isRepertoireLoading = false, repertoireError = ex.message) }
            }
        }
    }

    fun loadRepertoireFromPgnText(pgnText: String, preferredPlayer: String? = null) {
        if (pgnText.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRepertoireLoading = true, repertoireError = null) }
            try {
                val (pool, inferredUser) = PlayerGameSelectionService.convertPgnTextToSourceGames(
                    rawPgnText = pgnText,
                    preferredUsername = preferredPlayer
                )
                val nextFilters = _uiState.value.repertoireFilters.copy(playerUsername = inferredUser)
                rebuildRepertoireAggregation(
                    pool = pool,
                    filters = nextFilters,
                    sourceLabel = "Импорт PGN (${pool.size})"
                )
            } catch (ex: Exception) {
                _uiState.update { it.copy(isRepertoireLoading = false, repertoireError = ex.message) }
            }
        }
    }

    fun updateRepertoireFilters(transform: (RepertoireFilterConfig) -> RepertoireFilterConfig) {
        val prev = _uiState.value.repertoireFilters
        val next = transform(prev)
        val pool = if (next.playerUsername.trim() != prev.playerUsername.trim() && next.playerUsername.isNotBlank()) {
            PlayerGameSelectionService.rebindPlayerPerspective(_uiState.value.repertoirePool, next.playerUsername)
        } else {
            _uiState.value.repertoirePool
        }
        viewModelScope.launch {
            rebuildRepertoireAggregation(pool, next, _uiState.value.repertoireSourceLabel)
        }
    }

    private suspend fun rebuildRepertoireAggregation(
        pool: List<RepertoireSourceGame>,
        filters: RepertoireFilterConfig,
        sourceLabel: String
    ) {
        val (selectedGames, preview) = PlayerGameSelectionService.filterAndPreviewGames(
            pool = pool,
            filters = filters,
            cacheMap = openingCacheMap
        )
        val (_, aggregated) = repertoireAnalyzer.buildInstantRepertoireFromSelection(
            selectedGames = selectedGames,
            filters = filters,
            cacheMap = openingCacheMap
        )
        storage.saveOpeningCache(openingCacheMap)
        _uiState.update {
            it.copy(
                repertoirePool = pool,
                repertoireFilters = filters,
                repertoirePreview = preview,
                repertoireAggregation = aggregated,
                repertoireSourceLabel = sourceLabel,
                isRepertoireLoading = false
            )
        }
    }

    fun startRepertoireEngineAnalysis(depth: Int = 12, maxOpeningPly: Int = 16) {
        val st = _uiState.value
        val (selected, _) = PlayerGameSelectionService.filterAndPreviewGames(
            pool = st.repertoirePool,
            filters = st.repertoireFilters,
            cacheMap = openingCacheMap
        )
        val (_, agg) = repertoireAnalyzer.buildInstantRepertoireFromSelection(selected, st.repertoireFilters, openingCacheMap)
        val recordsToAnalyze = selected.mapNotNull { g ->
            val key = "${g.id}_${g.playerColor.name}"
            val rec = openingCacheMap[key] ?: return@mapNotNull null
            if (st.repertoireFilters.cacheMode == RepertoireCacheMode.FORCE_REANALYZE ||
                rec.status != com.example.core.analysis.OpeningCacheStatus.ENGINE_ANALYZED
            ) rec else null
        }

        if (recordsToAnalyze.isEmpty()) {
            _uiState.update { it.copy(repertoireAggregation = agg, statusNotice = "Все выбранные партии уже проанализированы") }
            return
        }

        repertoireJob?.cancel()
        _uiState.update {
            it.copy(
                isRepertoireEngineRunning = true,
                repertoireEngineCompleted = 0,
                repertoireEngineTotal = recordsToAnalyze.size,
                repertoireError = null
            )
        }

        repertoireJob = viewModelScope.launch {
            val posCache = HashMap<String, com.example.core.analysis.CachedFenEval>()
            try {
                for (i in recordsToAnalyze.indices) {
                    val rec = recordsToAnalyze[i]
                    val updated = repertoireAnalyzer.evaluateSingleGameOpeningWithStockfish(
                        record = rec,
                        depth = depth,
                        maxOpeningPly = maxOpeningPly,
                        thresholds = _uiState.value.preferences.thresholds,
                        positionEvalCache = posCache
                    )
                    openingCacheMap[updated.id] = updated
                    val currentPoolRecords = selected.mapNotNull { openingCacheMap["${it.id}_${it.playerColor.name}"] }
                    val updatedAgg = repertoireAnalyzer.aggregateRepertoireRecords(currentPoolRecords, _uiState.value.repertoireFilters)
                    val (_, updatedPreview) = PlayerGameSelectionService.filterAndPreviewGames(
                        _uiState.value.repertoirePool,
                        _uiState.value.repertoireFilters,
                        openingCacheMap
                    )
                    _uiState.update {
                        it.copy(
                            repertoireEngineCompleted = i + 1,
                            repertoireAggregation = updatedAgg,
                            repertoirePreview = updatedPreview
                        )
                    }
                }
                storage.saveOpeningCache(openingCacheMap)
                _uiState.update { it.copy(isRepertoireEngineRunning = false) }
            } catch (_: CancellationException) {
                storage.saveOpeningCache(openingCacheMap)
                _uiState.update { it.copy(isRepertoireEngineRunning = false) }
            } catch (ex: Exception) {
                storage.saveOpeningCache(openingCacheMap)
                _uiState.update {
                    it.copy(
                        isRepertoireEngineRunning = false,
                        repertoireError = ex.message ?: "Ошибка анализа репертуара"
                    )
                }
            }
        }
    }

    fun stopRepertoireEngineAnalysis() {
        repertoireJob?.cancel()
        repertoireJob = null
        repertoireAnalyzer.stopRepertoireAnalysis()
        _uiState.update { it.copy(isRepertoireEngineRunning = false) }
    }

    fun openGameFromRepertoire(gameId: String) {
        val rec = _uiState.value.repertoirePool.firstOrNull { it.id == gameId } ?: return
        loadPgnGame(
            rec.pgn,
            when (rec.source) {
                com.example.core.analysis.RepertoireSourceType.CHESS_COM -> GamePlatform.CHESS_COM
                com.example.core.analysis.RepertoireSourceType.SAVED -> GamePlatform.LOCAL
                com.example.core.analysis.RepertoireSourceType.PGN -> GamePlatform.PGN
            }
        )
        setWorkspace(AnalyzerWorkspace.ANALYZER)
    }

    fun clearRepertoireCache() {
        viewModelScope.launch {
            openingCacheMap.clear()
            storage.clearOpeningCache()
            if (_uiState.value.repertoirePool.isNotEmpty()) {
                rebuildRepertoireAggregation(
                    _uiState.value.repertoirePool,
                    _uiState.value.repertoireFilters,
                    _uiState.value.repertoireSourceLabel
                )
            }
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private fun currentNode(): MoveNode =
        VariationTree.findNodeById(_uiState.value.rootNode, _uiState.value.currentNodeId)
            ?: _uiState.value.rootNode

    private fun applyLoadedGame(
        gameId: String,
        metadata: GameMetadata,
        initialFen: String,
        rootNode: MoveNode,
        statistics: GameStatistics?
    ) {
        selectedSquare = null
        val summary = openingBook.analyzeMainlineOpening(VariationTree.getMainlineNodes(rootNode))
        _uiState.update {
            it.copy(
                gameId = gameId,
                metadata = metadata,
                initialFen = initialFen,
                rootNode = rootNode,
                currentNodeId = rootNode.id,
                openingSummary = summary,
                statistics = statistics,
                boardState = rebuildBoardState(rootNode, null),
                sideToMove = ChesslibHelper.sideToMove(rootNode.fen),
                isBatchAnalyzing = false,
                isAutoPlaying = false,
                arrowStartSquare = null,
                pendingPromotion = null,
                errorMessage = null
            )
        }
        onActivePositionChanged(rootNode)
    }

    private fun onActivePositionChanged(node: MoveNode) {
        triggerInteractiveEngineForCurrentNode()
        if (node.ply <= 24) {
            val targetFen = node.fen
            viewModelScope.launch {
                val stats = lichessApi.fetchLichessOpeningStats(targetFen)
                if (currentNode().fen == targetFen) {
                    _uiState.update { it.copy(lichessStats = stats) }
                }
            }
        } else {
            _uiState.update { it.copy(lichessStats = null) }
        }
    }

    private fun triggerInteractiveEngineForCurrentNode() {
        if (_uiState.value.isBatchAnalyzing) return
        val node = currentNode()
        val cfg = _uiState.value.preferences.engineConfig
        stockfishClient.analyzePositionInteractive(
            fen = node.fen,
            overrideDepth = cfg.depth,
            overrideMovetimeMs = cfg.movetimeMs
        )
    }

    private fun computeOpeningForContext(root: MoveNode, nodeId: String): OpeningAnalysisSummary {
        if (nodeId != "root") {
            val path = VariationTree.getPathToNode(root, nodeId)
            if (path.size > 1) return openingBook.analyzeMainlineOpening(path)
        }
        return openingBook.analyzeMainlineOpening(VariationTree.getMainlineNodes(root))
    }

    private fun rebuildBoardState(currentNode: MoveNode, selected: BoardSquare?): ChessBoardState {
        val pieces = try {
            FenBoardParser.parsePlacement(currentNode.fen)
        } catch (_: Exception) {
            emptyMap()
        }
        val board = try {
            ChesslibHelper.parseBoard(currentNode.fen)
        } catch (_: Exception) {
            null
        }
        val checkedKing = if (board != null && board.isKingAttacked) {
            val kingSq = board.getKingSquare(board.sideToMove)
            BoardSquare.fromAlgebraic(kingSq.value().lowercase())
        } else null

        val legalTargets = if (selected != null && board != null) {
            val grouped = LinkedHashMap<BoardSquare, MutableList<com.example.core.analysis.LegalMoveVerbose>>()
            for (mv in ChesslibHelper.legalMovesVerbose(board)) {
                if (mv.from == selected.algebraic) {
                    val toSq = BoardSquare.fromAlgebraic(mv.to) ?: continue
                    grouped.getOrPut(toSq) { mutableListOf() }.add(mv)
                }
            }
            grouped.map { (sq, mvs) ->
                LegalTarget(
                    square = sq,
                    isCapture = mvs.any { it.captured != null },
                    requiresPromotion = mvs.any { it.promotion != null }
                )
            }
        } else emptyList()

        val lastMove = run {
            val from = currentNode.fromSquare
            val to = currentNode.toSquare
            if (from != null && to != null) BoardMove(from, to) else null
        }

        return ChessBoardState(
            pieces = pieces,
            selectedSquare = selected,
            legalTargets = legalTargets,
            lastMove = lastMove,
            checkedKingSquare = checkedKing,
            positionVersion = boardVersion++
        )
    }

    private fun persistCurrentGameAsync() {
        val st = _uiState.value
        if (st.gameId.isBlank()) return
        val record = SavedAnalyzerGameRecord(
            id = st.gameId,
            metadata = st.metadata,
            pgn = getExportPgn(),
            initialFen = st.initialFen,
            rootNode = st.rootNode.deepCopy(),
            statistics = st.statistics,
            openingSummary = st.openingSummary,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            val list = storage.saveGame(record)
            _uiState.update { it.copy(savedGames = list) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelFullGameAnalysis()
        stopAutoplay()
        stopRepertoireEngineAnalysis()
    }
}
