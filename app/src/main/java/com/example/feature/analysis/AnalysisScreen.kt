package com.example.feature.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.LastPage
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.analysis.EngineState
import com.example.core.analysis.VariationTree
import com.example.core.ui.board.ChessBoard
import com.example.core.ui.board.ChessBoardConfig
import com.example.core.ui.board.PawnPromotionDialog
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.AppSettings
import com.example.domain.model.PieceColor

private val BRUSH_PALETTE = listOf(
    "#F39C36",
    "#36A9E1",
    "#779954",
    "#E05252"
)

@Composable
fun AnalysisScreen(
    onNavigateBack: () -> Unit,
    initialGameId: Long? = null,
    viewModel: AnalysisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()

    LaunchedEffect(initialGameId) {
        viewModel.loadRoomGameIfRequested(initialGameId)
    }

    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showChessComDialog by rememberSaveable { mutableStateOf(false) }
    var showSavedGamesDialog by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    if (showImportDialog) {
        ImportGameDialog(
            onDismiss = { showImportDialog = false },
            onLoadPgn = { viewModel.loadPgnGame(it) },
            onLoadFen = { viewModel.loadFenPosition(it) },
            onAddOpeningBook = { eco, name, variation, moves ->
                viewModel.addCustomOpeningEntry(eco, name, variation, moves)
            }
        )
    }

    if (showChessComDialog) {
        ChessComImportDialog(
            uiState = uiState,
            onDismiss = { showChessComDialog = false },
            onSearchUser = { viewModel.loadChessComUserGames(it) },
            onLoadGamePgn = { viewModel.loadPgnGame(it) }
        )
    }

    if (showSavedGamesDialog) {
        SavedGamesDialog(
            uiState = uiState,
            onDismiss = { showSavedGamesDialog = false },
            onOpenSavedRecord = { viewModel.loadSavedGameRecord(it) },
            onDeleteSavedRecord = { viewModel.deleteSavedGame(it) },
            onOpenRoomGame = { viewModel.loadRoomGameSummary(it) }
        )
    }

    if (showSettingsDialog) {
        AnalyzerSettingsDialog(
            preferences = uiState.preferences,
            onDismiss = { showSettingsDialog = false },
            onUpdatePreferences = viewModel::updateAnalyzerPreferences,
            onResetDefaults = viewModel::resetAnalyzerPreferences
        )
    }

    uiState.pendingPromotion?.let { pending ->
        PawnPromotionDialog(
            color = pending.color,
            onPieceSelected = viewModel::onPromotionSelected,
            onDismiss = viewModel::onPromotionDismissed
        )
    }

    val colors = ChessTheme.colors

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AnalyzerTopBar(
                uiState = uiState,
                engineState = engineState,
                onNavigateBack = onNavigateBack,
                onWorkspaceChange = viewModel::setWorkspace,
                onOpenImport = { showImportDialog = true },
                onOpenChessCom = { showChessComDialog = true },
                onOpenSaved = { showSavedGamesDialog = true },
                onOpenSettings = { showSettingsDialog = true }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val bannerText = uiState.errorMessage ?: uiState.statusNotice
            if (bannerText != null) {
                val isError = uiState.errorMessage != null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isError) colors.accentCoral else colors.accentGreen)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::clearError, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (uiState.workspace == AnalyzerWorkspace.REPERTOIRE) {
                OpeningRepertoireScreen(
                    uiState = uiState,
                    onLoadSavedGames = { viewModel.loadRepertoireFromSavedGames() },
                    onLoadChessCom = { viewModel.loadRepertoireFromChessCom(it) },
                    onLoadPgnText = { viewModel.loadRepertoireFromPgnText(it) },
                    onSelectPeriod = { preset ->
                        viewModel.updateRepertoireFilters { it.copy(periodPreset = preset) }
                    },
                    onSelectColorFilter = { colorChoice ->
                        viewModel.updateRepertoireFilters { it.copy(colorFilter = colorChoice) }
                    },
                    onToggleTimeClass = { tc ->
                        viewModel.updateRepertoireFilters { cfg ->
                            val nextSet = if (tc in cfg.timeClasses) cfg.timeClasses - tc else cfg.timeClasses + tc
                            cfg.copy(timeClasses = nextSet)
                        }
                    },
                    onUpdatePlayerName = { name ->
                        viewModel.updateRepertoireFilters { it.copy(playerUsername = name) }
                    },
                    onSelectTab = viewModel::setRepertoireTab,
                    onStartEngineAnalysis = { viewModel.startRepertoireEngineAnalysis() },
                    onStopEngineAnalysis = viewModel::stopRepertoireEngineAnalysis,
                    onOpenGameInAnalyzer = viewModel::openGameFromRepertoire,
                    onClearCache = viewModel::clearRepertoireCache
                )
            } else {
                AnalyzerWorkspaceContent(
                    uiState = uiState,
                    engineState = engineState,
                    appSettings = appSettings,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun AnalyzerTopBar(
    uiState: AnalyzerUiState,
    engineState: EngineState,
    onNavigateBack: () -> Unit,
    onWorkspaceChange: (AnalyzerWorkspace) -> Unit,
    onOpenImport: () -> Unit,
    onOpenChessCom: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = ChessTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.backgroundSecondary)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("top_bar_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = colors.textPrimary)
                }

                // Workspace Switcher: Анализатор / Репертуар
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceCard,
                    border = BorderStroke(1.dp, colors.borderSubtle)
                ) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        WorkspacePill(
                            label = "Анализатор",
                            selected = uiState.workspace == AnalyzerWorkspace.ANALYZER,
                            onClick = { onWorkspaceChange(AnalyzerWorkspace.ANALYZER) }
                        )
                        WorkspacePill(
                            label = "Репертуар",
                            selected = uiState.workspace == AnalyzerWorkspace.REPERTOIRE,
                            onClick = { onWorkspaceChange(AnalyzerWorkspace.REPERTOIRE) }
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(onClick = onOpenSaved, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = "Мои партии", tint = colors.accentGreen, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenImport, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = "Импорт PGN / FEN", tint = colors.accentAmber, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenChessCom, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Public, contentDescription = "Chess.com", tint = colors.accentBlue, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Настройки анализатора", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Compact Engine Status Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ECO ${uiState.openingSummary.eco} · ${uiState.openingSummary.name}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFA88865),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (engineState.isReady) {
                    if (engineState.isAnalyzing) "Stockfish · d${engineState.currentDepth}" else "Stockfish · Готов (d${engineState.currentDepth})"
                } else {
                    "Запуск Stockfish…"
                },
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                color = if (engineState.isReady) colors.accentGreen else colors.accentAmber
            )
        }
    }
}

@Composable
private fun WorkspacePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) colors.accentGreen else Color.Transparent
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) Color.White else colors.textSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun AnalyzerWorkspaceContent(
    uiState: AnalyzerUiState,
    engineState: EngineState,
    appSettings: AppSettings,
    viewModel: AnalysisViewModel
) {
    val currentNode = VariationTree.findNodeById(uiState.rootNode, uiState.currentNodeId) ?: uiState.rootNode
    val activeEngineLines = if (!uiState.isBatchAnalyzing && (engineState.fen == null || engineState.fen == currentNode.fen)) {
        engineState.lines
    } else emptyList()
    val primaryLine = activeEngineLines.firstOrNull()
    val activeCp = currentNode.analysis?.evalAfterCp ?: primaryLine?.cp
    val activeMate = currentNode.analysis?.mateAfter ?: primaryLine?.mate

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 720.dp

        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PlayerStripBar(
                        isWhite = uiState.flipped,
                        name = if (uiState.flipped) uiState.metadata.whiteName else uiState.metadata.blackName,
                        rating = if (uiState.flipped) uiState.metadata.whiteRating else uiState.metadata.blackRating,
                        title = if (uiState.flipped) uiState.metadata.whiteTitle else uiState.metadata.blackTitle
                    )

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val evalW = if (uiState.preferences.showEvalBar) 32.dp else 0.dp
                        val boardSide = minOf(maxWidth - evalW, maxHeight)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (uiState.preferences.showEvalBar) {
                                EvaluationBar(
                                    cp = activeCp,
                                    mate = activeMate,
                                    fen = currentNode.fen,
                                    flipped = uiState.flipped,
                                    modifier = Modifier.height(boardSide)
                                )
                            }
                            AnalyzerBoardBox(
                                uiState = uiState,
                                activeEngineLines = activeEngineLines,
                                appSettings = appSettings,
                                viewModel = viewModel,
                                modifier = Modifier.size(boardSide)
                            )
                        }
                    }

                    PlayerStripBar(
                        isWhite = !uiState.flipped,
                        name = if (uiState.flipped) uiState.metadata.blackName else uiState.metadata.whiteName,
                        rating = if (uiState.flipped) uiState.metadata.blackRating else uiState.metadata.whiteRating,
                        title = if (uiState.flipped) uiState.metadata.blackTitle else uiState.metadata.whiteTitle
                    )
                    AnnotationToolbar(uiState = uiState, viewModel = viewModel)
                    MoveNavigationControls(uiState = uiState, viewModel = viewModel)
                }

                AnalyzerRightPanel(
                    uiState = uiState,
                    engineState = engineState,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                )
            }
        } else {
            // Portrait phone layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PlayerStripBar(
                    isWhite = uiState.flipped,
                    name = if (uiState.flipped) uiState.metadata.whiteName else uiState.metadata.blackName,
                    rating = if (uiState.flipped) uiState.metadata.whiteRating else uiState.metadata.blackRating,
                    title = if (uiState.flipped) uiState.metadata.whiteTitle else uiState.metadata.blackTitle
                )

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val evalW = if (uiState.preferences.showEvalBar) 30.dp else 0.dp
                    val boardSide = minOf(maxWidth - evalW, 380.dp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.preferences.showEvalBar) {
                            EvaluationBar(
                                cp = activeCp,
                                mate = activeMate,
                                fen = currentNode.fen,
                                flipped = uiState.flipped,
                                modifier = Modifier.height(boardSide)
                            )
                        }
                        AnalyzerBoardBox(
                            uiState = uiState,
                            activeEngineLines = activeEngineLines,
                            appSettings = appSettings,
                            viewModel = viewModel,
                            modifier = Modifier.size(boardSide)
                        )
                    }
                }

                PlayerStripBar(
                    isWhite = !uiState.flipped,
                    name = if (uiState.flipped) uiState.metadata.blackName else uiState.metadata.whiteName,
                    rating = if (uiState.flipped) uiState.metadata.blackRating else uiState.metadata.whiteRating,
                    title = if (uiState.flipped) uiState.metadata.blackTitle else uiState.metadata.whiteTitle
                )

                AnnotationToolbar(uiState = uiState, viewModel = viewModel)
                MoveNavigationControls(uiState = uiState, viewModel = viewModel)

                AnalyzerRightPanel(
                    uiState = uiState,
                    engineState = engineState,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AnalyzerBoardBox(
    uiState: AnalyzerUiState,
    activeEngineLines: List<com.example.core.analysis.EngineLine>,
    appSettings: AppSettings,
    viewModel: AnalysisViewModel,
    modifier: Modifier = Modifier
) {
    val currentNode = VariationTree.findNodeById(uiState.rootNode, uiState.currentNodeId) ?: uiState.rootNode
    val boardConfig = ChessBoardConfig(
        boardTheme = appSettings.boardTheme,
        pieceSet = appSettings.pieceSet,
        showCoordinates = appSettings.showCoordinates,
        highlightLegalMoves = appSettings.highlightLegalMoves,
        highlightLastMove = appSettings.highlightLastMove,
        animationsEnabled = appSettings.animationEnabled,
        flipped = uiState.flipped,
        interactive = uiState.drawMode == BoardDrawMode.MOVE && !uiState.isBatchAnalyzing
    )

    Box(modifier = modifier) {
        ChessBoard(
            state = uiState.boardState,
            config = boardConfig,
            onSquareTap = viewModel::onSquareTap,
            onMoveAttempt = viewModel::onMoveAttempt,
            cornerRadius = 6.dp,
            canSelectOrDrag = { _, piece ->
                uiState.drawMode == BoardDrawMode.MOVE && piece.color == uiState.sideToMove
            },
            modifier = Modifier.fillMaxSize()
        )
        BoardAnnotationOverlay(
            flipped = uiState.flipped,
            drawMode = uiState.drawMode,
            arrowStartSquare = uiState.arrowStartSquare,
            activeBrushHex = uiState.activeBrushHex,
            userArrows = currentNode.userArrows,
            squareHighlights = currentNode.squareHighlights,
            engineLines = activeEngineLines,
            showUserArrows = uiState.preferences.showUserArrows,
            showEngineArrows = uiState.preferences.showEngineArrows,
            enginePrimaryHex = uiState.preferences.engineArrowColor,
            engineSecondaryHex = uiState.preferences.secondaryEngineArrowColor,
            moveBadgeSquare = currentNode.toSquare,
            moveBadgeCategory = currentNode.analysis?.category,
            onAnnotationSquareTap = viewModel::onAnnotationSquareTap,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PlayerStripBar(
    isWhite: Boolean,
    name: String,
    rating: Int?,
    title: String?
) {
    val colors = ChessTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (isWhite) Color.White else Color(0xFF262421))
        )
        if (!title.isNullOrBlank()) {
            Surface(shape = RoundedCornerShape(3.dp), color = Color(0xFFB33430)) {
                Text(
                    text = title,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (rating != null) {
            Text(
                text = rating.toString(),
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun AnnotationToolbar(
    uiState: AnalyzerUiState,
    viewModel: AnalysisViewModel
) {
    val colors = ChessTheme.colors
    val currentNode = VariationTree.findNodeById(uiState.rootNode, uiState.currentNodeId) ?: uiState.rootNode

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DrawModeChip("Ходы", Icons.Rounded.TouchApp, uiState.drawMode == BoardDrawMode.MOVE) {
                    viewModel.setDrawMode(BoardDrawMode.MOVE)
                }
                DrawModeChip("Стрелки", Icons.Rounded.Create, uiState.drawMode == BoardDrawMode.ARROW) {
                    viewModel.setDrawMode(BoardDrawMode.ARROW)
                }
                DrawModeChip("Клетки", Icons.Rounded.GridOn, uiState.drawMode == BoardDrawMode.HIGHLIGHT) {
                    viewModel.setDrawMode(BoardDrawMode.HIGHLIGHT)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                BRUSH_PALETTE.forEach { hex ->
                    val selected = uiState.activeBrushHex.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(if (selected) 20.dp else 16.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(hex))
                            .clickable { viewModel.setActiveBrushColor(hex) }
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
                IconButton(
                    onClick = {
                        viewModel.updateAnalyzerPreferences { it.copy(showEngineArrows = !it.showEngineArrows) }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.preferences.showEngineArrows) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = "Линии Stockfish",
                        tint = if (uiState.preferences.showEngineArrows) colors.accentBlue else colors.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (currentNode.userArrows.isNotEmpty() || currentNode.squareHighlights.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearNodeAnnotations, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Очистить", tint = colors.accentCoral, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) colors.accentGreen else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else colors.textSecondary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else colors.textSecondary
            )
        }
    }
}

@Composable
private fun MoveNavigationControls(
    uiState: AnalyzerUiState,
    viewModel: AnalysisViewModel
) {
    val colors = ChessTheme.colors
    val speedLabel = when (uiState.autoplaySpeedMs) {
        1600L -> "0.5×"
        600L -> "1.5×"
        350L -> "2×"
        else -> "1×"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::goToStart, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.FirstPage, contentDescription = "В начало", tint = colors.textPrimary)
            }
            IconButton(onClick = viewModel::goToPrev, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Назад", tint = colors.textPrimary)
            }
            IconButton(onClick = viewModel::toggleAutoplay, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (uiState.isAutoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Автопроигрывание",
                    tint = colors.accentGreen
                )
            }
            Surface(
                onClick = viewModel::cycleAutoplaySpeed,
                shape = RoundedCornerShape(5.dp),
                color = colors.backgroundSecondary
            ) {
                Text(
                    text = speedLabel,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            IconButton(onClick = viewModel::goToNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Вперёд", tint = colors.textPrimary)
            }
            IconButton(onClick = viewModel::goToEnd, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Rounded.LastPage, contentDescription = "В конец", tint = colors.textPrimary)
            }
            IconButton(onClick = viewModel::flipBoard, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.SwapVert, contentDescription = "Перевернуть доску", tint = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun AnalyzerRightPanel(
    uiState: AnalyzerUiState,
    engineState: EngineState,
    viewModel: AnalysisViewModel,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val currentNode = VariationTree.findNodeById(uiState.rootNode, uiState.currentNodeId) ?: uiState.rootNode

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = uiState.rightTab.ordinal,
                containerColor = colors.backgroundSecondary,
                contentColor = colors.accentGreen
            ) {
                AnalysisRightTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.rightTab == tab,
                        onClick = { viewModel.setRightTab(tab) },
                        text = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            if (!currentNode.isMainline) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentBlueContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Альтернативная ветка",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accentBlue
                        )
                        Surface(
                            onClick = viewModel::returnToMainline,
                            shape = RoundedCornerShape(6.dp),
                            color = colors.accentBlue
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Text("К основной", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (uiState.rightTab) {
                    AnalysisRightTab.MOVES -> MoveListPanel(
                        uiState = uiState,
                        onSelectNode = viewModel::selectNode,
                        onDeleteVariation = viewModel::deleteVariation
                    )
                    AnalysisRightTab.ENGINE -> EngineLinesPanel(
                        uiState = uiState,
                        engineState = engineState,
                        onMakeSanMove = { viewModel.makeSanMove(it) },
                        onDepthChanged = { d ->
                            viewModel.updateAnalyzerPreferences { it.copy(engineConfig = it.engineConfig.copy(depth = d)) }
                        },
                        onMultiPvChanged = { mpv ->
                            viewModel.updateAnalyzerPreferences { it.copy(engineConfig = it.engineConfig.copy(multiPv = mpv)) }
                        }
                    )
                    AnalysisRightTab.CHART -> ChartAndTurningPointsPanel(
                        uiState = uiState,
                        onSelectNode = viewModel::selectNode,
                        onRunFullAnalysis = viewModel::runFullGameAnalysis
                    )
                    AnalysisRightTab.STATS -> AccuracyStatsPanel(
                        uiState = uiState,
                        onRunFullAnalysis = viewModel::runFullGameAnalysis
                    )
                    AnalysisRightTab.INFO -> GameInfoPanel(
                        uiState = uiState,
                        onRunFullAnalysis = viewModel::runFullGameAnalysis,
                        onCancelAnalysis = viewModel::cancelFullGameAnalysis,
                        onExportPgnNotice = { msg ->
                            viewModel.showNotice(msg)
                        },
                        getExportPgn = viewModel::getExportPgn
                    )
                }
            }
        }
    }
}
