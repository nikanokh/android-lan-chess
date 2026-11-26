package com.example.feature.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.LastPage
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.analysis.ChesslibHelper
import com.example.core.analysis.EngineLine
import com.example.core.analysis.EngineState
import com.example.core.analysis.EvalBarPosition
import com.example.core.analysis.EvalScoreDisplay
import com.example.core.analysis.VariationTree
import com.example.core.analysis.styleMeta
import com.example.core.ui.board.ChessBoard
import com.example.core.ui.board.ChessBoardConfig
import com.example.core.ui.board.PawnPromotionDialog
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.AppSettings
import com.example.domain.model.MoveClassification
import com.example.domain.model.PieceColor

private val ENGINE_PRIMARY_ORANGE = Color(0xFFF39C36)

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
            onSelectArchive = { viewModel.selectChessComArchive(it) },
            onLoadGame = { viewModel.loadChessComGame(it) }
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
                        .padding(horizontal = 12.dp, vertical = 4.dp),
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

            if (uiState.isInitializing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = colors.accentGreen,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "Загрузка анализатора…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
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
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("top_bar_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Назад",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Анализ партий",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    maxLines = 1
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TopBarActionIcon(Icons.Rounded.FolderOpen, "Мои партии", colors.accentGreen, onOpenSaved)
                TopBarActionIcon(Icons.Rounded.FileUpload, "Импорт PGN / FEN", colors.accentAmber, onOpenImport)
                TopBarActionIcon(Icons.Rounded.Public, "Chess.com", colors.accentBlue, onOpenChessCom)
                TopBarActionIcon(Icons.Rounded.Settings, "Настройки анализатора", colors.textPrimary, onOpenSettings)
            }
        }

        // Compact Opening & Engine Status Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (uiState.isInitializing) "Загрузка позиции…" else "ECO ${uiState.openingSummary.eco} · ${uiState.openingSummary.name}",
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
private fun TopBarActionIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderSubtle),
        modifier = Modifier.size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
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
    val rawCp = currentNode.analysis?.evalAfterCp ?: primaryLine?.cp
    val rawMate = currentNode.analysis?.mateAfter ?: primaryLine?.mate
    val isTerminal = ChesslibHelper.terminalOutcomeFromFen(currentNode.fen) != null

    var heldCp by remember(uiState.gameId) { mutableStateOf<Int?>(null) }
    var heldMate by remember(uiState.gameId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(rawCp, rawMate, isTerminal) {
        if (isTerminal) {
            heldCp = null
            heldMate = null
        } else if (rawCp != null || rawMate != null) {
            heldCp = rawCp
            heldMate = rawMate
        }
    }

    val activeCp = if (isTerminal) null else (rawCp ?: heldCp)
    val activeMate = if (isTerminal) null else (rawMate ?: heldMate)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 720.dp

        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        BoardWithEvaluationBar(
                            uiState = uiState,
                            activeEngineLines = activeEngineLines,
                            appSettings = appSettings,
                            viewModel = viewModel,
                            activeCp = activeCp,
                            activeMate = activeMate,
                            fen = currentNode.fen,
                            maxBoardHeight = maxHeight,
                            availableWidth = maxWidth
                        )
                    }

                    PlayerStripBar(
                        isWhite = !uiState.flipped,
                        name = if (uiState.flipped) uiState.metadata.blackName else uiState.metadata.whiteName,
                        rating = if (uiState.flipped) uiState.metadata.blackRating else uiState.metadata.whiteRating,
                        title = if (uiState.flipped) uiState.metadata.blackTitle else uiState.metadata.whiteTitle
                    )
                    MoveEvaluationStrip(
                        uiState = uiState,
                        currentNode = currentNode,
                        primaryLine = primaryLine,
                        activeCp = activeCp,
                        activeMate = activeMate
                    )
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
            // Portrait phone layout: outer scroll + generous bounded panel height with internal auto-scroll
            val pageScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(pageScrollState)
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
                    BoardWithEvaluationBar(
                        uiState = uiState,
                        activeEngineLines = activeEngineLines,
                        appSettings = appSettings,
                        viewModel = viewModel,
                        activeCp = activeCp,
                        activeMate = activeMate,
                        fen = currentNode.fen,
                        maxBoardHeight = 380.dp,
                        availableWidth = maxWidth
                    )
                }

                PlayerStripBar(
                    isWhite = !uiState.flipped,
                    name = if (uiState.flipped) uiState.metadata.blackName else uiState.metadata.whiteName,
                    rating = if (uiState.flipped) uiState.metadata.blackRating else uiState.metadata.whiteRating,
                    title = if (uiState.flipped) uiState.metadata.blackTitle else uiState.metadata.whiteTitle
                )

                MoveEvaluationStrip(
                    uiState = uiState,
                    currentNode = currentNode,
                    primaryLine = primaryLine,
                    activeCp = activeCp,
                    activeMate = activeMate
                )

                MoveNavigationControls(uiState = uiState, viewModel = viewModel)

                AnalyzerRightPanel(
                    uiState = uiState,
                    engineState = engineState,
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(430.dp)
                )
            }
        }
    }
}

@Composable
private fun BoardWithEvaluationBar(
    uiState: AnalyzerUiState,
    activeEngineLines: List<EngineLine>,
    appSettings: AppSettings,
    viewModel: AnalysisViewModel,
    activeCp: Int?,
    activeMate: Int?,
    fen: String,
    maxBoardHeight: androidx.compose.ui.unit.Dp,
    availableWidth: androidx.compose.ui.unit.Dp
) {
    val prefs = uiState.preferences
    val showBar = prefs.showEvalBar
    val isTopBar = showBar && prefs.evalBarPosition == EvalBarPosition.TOP
    val isLeftBar = showBar && prefs.evalBarPosition == EvalBarPosition.LEFT

    val evalW = when {
        !isLeftBar -> 0.dp
        prefs.evalScoreDisplay == EvalScoreDisplay.SEPARATE -> 44.dp
        prefs.evalScoreDisplay == EvalScoreDisplay.HIDDEN -> 24.dp
        else -> 32.dp
    }
    val evalTopH = when {
        !isTopBar -> 0.dp
        prefs.evalScoreDisplay == EvalScoreDisplay.HIDDEN -> 18.dp
        else -> 26.dp
    }
    val boardSide = minOf(availableWidth - evalW, maxBoardHeight - evalTopH).coerceAtLeast(120.dp)

    if (isTopBar) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EvaluationBar(
                cp = activeCp,
                mate = activeMate,
                fen = fen,
                flipped = uiState.flipped,
                position = EvalBarPosition.TOP,
                scoreDisplay = prefs.evalScoreDisplay,
                modifier = Modifier.width(boardSide)
            )
            AnalyzerBoardBox(
                uiState = uiState,
                activeEngineLines = activeEngineLines,
                appSettings = appSettings,
                viewModel = viewModel,
                modifier = Modifier.size(boardSide)
            )
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLeftBar) {
                EvaluationBar(
                    cp = activeCp,
                    mate = activeMate,
                    fen = fen,
                    flipped = uiState.flipped,
                    position = EvalBarPosition.LEFT,
                    scoreDisplay = prefs.evalScoreDisplay,
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
}

@Composable
private fun AnalyzerBoardBox(
    uiState: AnalyzerUiState,
    activeEngineLines: List<EngineLine>,
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
        interactive = !uiState.isBatchAnalyzing
    )

    Box(modifier = modifier) {
        ChessBoard(
            state = uiState.boardState,
            config = boardConfig,
            onSquareTap = viewModel::onSquareTap,
            onMoveAttempt = viewModel::onMoveAttempt,
            cornerRadius = 6.dp,
            canSelectOrDrag = { _, piece ->
                piece.color == uiState.sideToMove
            },
            modifier = Modifier.fillMaxSize()
        )
        BoardAnnotationOverlay(
            flipped = uiState.flipped,
            drawMode = BoardDrawMode.MOVE,
            arrowStartSquare = null,
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
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (isWhite) Color.White else Color(0xFF262421))
        )
        if (!title.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = Color(0xFFB33430)
            ) {
                Text(
                    text = title,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            ),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (rating != null) {
            Text(
                text = "($rating)",
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                color = colors.textSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun MoveEvaluationStrip(
    uiState: AnalyzerUiState,
    currentNode: com.example.core.analysis.MoveNode,
    primaryLine: EngineLine?,
    activeCp: Int?,
    activeMate: Int?
) {
    val colors = ChessTheme.colors
    val moveAnalysis = currentNode.analysis
    val evalText = formatEvalScore(activeCp, activeMate, 2, currentNode.fen)

    val containerColor = when {
        moveAnalysis != null -> Color(moveAnalysis.category.styleMeta().colorHex).copy(alpha = 0.14f)
        else -> colors.surfaceCard
    }
    val borderColor = when {
        moveAnalysis != null -> Color(moveAnalysis.category.styleMeta().colorHex).copy(alpha = 0.45f)
        else -> colors.borderSubtle
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (moveAnalysis != null) {
                val meta = moveAnalysis.category.styleMeta()
                val movePrefix = "${moveAnalysis.moveNumber}${if (moveAnalysis.player == PieceColor.WHITE) "." else "..."}"
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Image(
                        painter = painterResource(badgeDrawableFor(moveAnalysis.category)),
                        contentDescription = meta.labelRu,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = buildString {
                            append("$movePrefix ${moveAnalysis.san} — ${meta.labelRu}")
                            if (moveAnalysis.category != MoveClassification.BEST &&
                                moveAnalysis.category != MoveClassification.BOOK &&
                                moveAnalysis.bestMoveSan.isNotBlank()
                            ) {
                                append(" · лучший ${moveAnalysis.bestMoveSan}")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(meta.colorHex),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (uiState.isBatchAnalyzing) {
                Text(
                    text = "Анализ партии: ${uiState.batchCompleted}/${uiState.batchTotal}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accentGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else if (primaryLine != null) {
                val bestSan = primaryLine.pvSan.firstOrNull() ?: primaryLine.pv.firstOrNull().orEmpty()
                Text(
                    text = if (bestSan.isNotEmpty()) {
                        "Stockfish d${primaryLine.depth} · лучший $bestSan"
                    } else {
                        "Stockfish d${primaryLine.depth}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = "Оценка позиции",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = colors.backgroundSecondary
            ) {
                Text(
                    text = evalText,
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    maxLines = 1,
                    softWrap = false
                )
            }
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
    val arrowsVisible = uiState.preferences.showEngineArrows

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavControlIconTile(
                icon = Icons.Rounded.FirstPage,
                description = "В начало",
                onClick = viewModel::goToStart,
                modifier = Modifier.weight(1f)
            )
            NavControlIconTile(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                description = "Назад",
                onClick = viewModel::goToPrev,
                modifier = Modifier.weight(1f)
            )
            Surface(
                onClick = viewModel::toggleAutoplay,
                shape = RoundedCornerShape(8.dp),
                color = if (uiState.isAutoPlaying) ENGINE_PRIMARY_ORANGE else colors.accentGreen,
                modifier = Modifier
                    .weight(1.1f)
                    .height(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (uiState.isAutoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Автопроигрывание",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Surface(
                onClick = viewModel::cycleAutoplaySpeed,
                shape = RoundedCornerShape(8.dp),
                color = colors.backgroundSecondary,
                border = BorderStroke(1.dp, colors.borderSubtle),
                modifier = Modifier
                    .width(50.dp)
                    .height(34.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = speedLabel,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.2.sp
                        ),
                        color = colors.textPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            NavControlIconTile(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                description = "Вперёд",
                onClick = viewModel::goToNext,
                modifier = Modifier.weight(1f)
            )
            NavControlIconTile(
                icon = Icons.AutoMirrored.Rounded.LastPage,
                description = "В конец",
                onClick = viewModel::goToEnd,
                modifier = Modifier.weight(1f)
            )
            NavControlIconTile(
                icon = Icons.Rounded.SwapVert,
                description = "Перевернуть доску",
                onClick = viewModel::flipBoard,
                modifier = Modifier.weight(1f)
            )
            Surface(
                onClick = {
                    viewModel.updateAnalyzerPreferences { it.copy(showEngineArrows = !it.showEngineArrows) }
                },
                shape = RoundedCornerShape(8.dp),
                color = if (arrowsVisible) ENGINE_PRIMARY_ORANGE.copy(alpha = 0.16f) else colors.backgroundSecondary,
                border = BorderStroke(
                    1.dp,
                    if (arrowsVisible) ENGINE_PRIMARY_ORANGE.copy(alpha = 0.65f) else colors.borderSubtle
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (arrowsVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = "Стрелки движка",
                        tint = if (arrowsVisible) ENGINE_PRIMARY_ORANGE else colors.textMuted,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NavControlIconTile(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = colors.backgroundSecondary,
        border = BorderStroke(1.dp, colors.borderSubtle),
        modifier = modifier.height(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = colors.textPrimary,
                modifier = Modifier.size(19.dp)
            )
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
            // Compact Tab Bar with readable, non-cramped typography
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.backgroundSecondary)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AnalysisRightTab.entries.forEach { tab ->
                    val selected = uiState.rightTab == tab
                    Surface(
                        onClick = { viewModel.setRightTab(tab) },
                        shape = RoundedCornerShape(7.dp),
                        color = if (selected) colors.accentGreenContainer else Color.Transparent,
                        border = if (selected) BorderStroke(1.dp, colors.accentGreen.copy(alpha = 0.5f)) else null,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 11.dp)
                        ) {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    letterSpacing = 0.1.sp
                                ),
                                color = if (selected) colors.accentGreen else colors.textSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.borderSubtle)

            // Quick Full-Game Analysis Action / Live Batch Progress Strip
            if (uiState.isBatchAnalyzing) {
                val progress = if (uiState.batchTotal > 0) {
                    uiState.batchCompleted.toFloat() / uiState.batchTotal.toFloat()
                } else 0f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.backgroundSecondary.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Полный анализ Stockfish: ${uiState.batchCompleted}/${uiState.batchTotal}",
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            color = colors.accentGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                        Surface(
                            onClick = viewModel::cancelFullGameAnalysis,
                            shape = RoundedCornerShape(5.dp),
                            color = colors.accentCoralContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null, tint = colors.accentCoral, modifier = Modifier.size(13.dp))
                                Text("Стоп", fontSize = 10.sp, color = colors.accentCoral, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = colors.accentGreen,
                        trackColor = colors.surfaceCard
                    )
                }
                HorizontalDivider(color = colors.borderSubtle)
            } else if (uiState.statistics == null && uiState.rootNode.children.isNotEmpty() && uiState.rightTab == AnalysisRightTab.MOVES) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.backgroundSecondary.copy(alpha = 0.55f))
                        .clickable(onClick = viewModel::runFullGameAnalysis)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Запустить полный анализ партии",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.accentGreen
                        )
                    }
                    Text(
                        text = "d${uiState.preferences.engineConfig.depth}",
                        fontFamily = MonoFontFamily,
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                }
                HorizontalDivider(color = colors.borderSubtle)
            }

            if (!currentNode.isMainline) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentBlueContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
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
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
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
