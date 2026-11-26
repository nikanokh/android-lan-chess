package com.example.feature.game

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.ChessBoard
import com.example.core.ui.board.ChessBoardConfig
import com.example.core.ui.board.PawnPromotionDialog
import com.example.core.ui.board.PieceType
import com.example.core.ui.components.ChessTopBar
import com.example.core.ui.theme.ChessTheme
import com.example.domain.model.AppSettings
import com.example.domain.model.PieceColor

import androidx.activity.compose.BackHandler

@Composable
fun GameScreen(
    timeControlId: String,
    onNavigateBack: () -> Unit,
    playerSide: PieceColor = PieceColor.WHITE,
    playerName: String = "",
    isLanMatch: Boolean = false,
    viewModel: GameViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(timeControlId, playerSide, isLanMatch, playerName) {
        viewModel.initializeSession(timeControlId, playerSide, isLanMatch, playerName)
    }

    val handleExit: () -> Unit = {
        viewModel.requestExit(onNavigateBack)
    }

    val needsExitConfirm = !uiState.isGameOver && (uiState.isLanMatch || uiState.moveHistory.isNotEmpty())
    BackHandler(enabled = needsExitConfirm, onBack = handleExit)

    GameScreenContent(
        uiState = uiState,
        settings = settings,
        overridePlayerName = playerName,
        onNavigateBack = handleExit,
        onSquareTap = viewModel::onSquareTap,
        onMoveAttempt = viewModel::onMoveAttempt,
        onInvalidDrop = viewModel::onInvalidDrop,
        onBoardFlipped = viewModel::onBoardFlipped,
        onCancelPremove = viewModel::cancelPremove,
        onPromotionSelected = viewModel::onPromotionSelected,
        onPromotionDismissed = viewModel::onPromotionDismissed,
        onSelectPly = viewModel::viewPly,
        onJumpStart = viewModel::jumpToHistoryStart,
        onStepBack = viewModel::stepHistoryBackward,
        onStepForward = viewModel::stepHistoryForward,
        onReturnLive = viewModel::returnToLivePosition,
        onOfferDraw = viewModel::offerDraw,
        onAcceptDraw = viewModel::acceptDraw,
        onDeclineDraw = viewModel::declineDraw,
        onRequestResign = viewModel::requestResign,
        onConfirmResign = viewModel::confirmResignNow,
        onDismissResign = viewModel::dismissResignConfirm,
        onUndo = viewModel::requestTakeback,
        onNewGame = viewModel::resetGame,
        onDismissGameOver = viewModel::dismissGameOverDialog,
        onAcceptTakeback = viewModel::acceptTakeback,
        onDeclineTakeback = viewModel::declineTakeback,
        onAcceptRematch = viewModel::acceptRematch,
        onDeclineRematch = viewModel::declineRematch,
        onConfirmExit = { viewModel.confirmExitForfeit(onNavigateBack) },
        onDismissExit = viewModel::dismissExitConfirm,
        onOpenPgn = { whiteName, blackName -> viewModel.openPgnPreview(whiteName, blackName) },
        onDismissPgn = viewModel::dismissPgnPreview
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreenContent(
    uiState: GameUiState,
    settings: AppSettings,
    onNavigateBack: () -> Unit,
    onSquareTap: (BoardSquare) -> Unit,
    onMoveAttempt: (BoardSquare, BoardSquare, Boolean) -> Boolean,
    onPromotionSelected: (PieceType) -> Unit,
    onPromotionDismissed: () -> Unit,
    onSelectPly: (Int) -> Unit,
    onJumpStart: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onReturnLive: () -> Unit,
    onOfferDraw: () -> Unit,
    onAcceptDraw: () -> Unit,
    onDeclineDraw: () -> Unit,
    onRequestResign: () -> Unit,
    onConfirmResign: () -> Unit,
    onDismissResign: () -> Unit,
    onUndo: () -> Unit,
    onNewGame: () -> Unit,
    onDismissGameOver: () -> Unit,
    onOpenPgn: (whiteName: String, blackName: String) -> Unit,
    onDismissPgn: () -> Unit,
    overridePlayerName: String = "",
    onCancelPremove: () -> Unit = {},
    onInvalidDrop: (BoardSquare, BoardSquare?) -> Unit = { _, _ -> },
    onBoardFlipped: () -> Unit = {},
    onAcceptTakeback: () -> Unit = {},
    onDeclineTakeback: () -> Unit = {},
    onAcceptRematch: () -> Unit = {},
    onDeclineRematch: () -> Unit = {},
    onConfirmExit: () -> Unit = onNavigateBack,
    onDismissExit: () -> Unit = {}
) {
    var flipped by rememberSaveable(uiState.playerSide) {
        mutableStateOf(uiState.playerSide == PieceColor.BLACK)
    }
    var showMoveHistorySheet by rememberSaveable { mutableStateOf(false) }
    var showGameMenuSheet by rememberSaveable { mutableStateOf(false) }

    val localName = uiState.localPlayerName?.takeIf { it.isNotBlank() }
        ?: overridePlayerName.ifBlank {
            settings.playerName.ifBlank { stringResource(R.string.default_player_name) }
        }
    val opponentName = uiState.opponentName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.game_opponent_placeholder)
    val whiteName = if (uiState.playerSide == PieceColor.WHITE) localName else opponentName
    val blackName = if (uiState.playerSide == PieceColor.BLACK) localName else opponentName

    val topColor = if (flipped) PieceColor.WHITE else PieceColor.BLACK
    val bottomColor = if (flipped) PieceColor.BLACK else PieceColor.WHITE

    val boardConfig = ChessBoardConfig(
        boardTheme = settings.boardTheme,
        pieceSet = settings.pieceSet,
        showCoordinates = settings.showCoordinates,
        highlightLegalMoves = settings.highlightLegalMoves,
        highlightLastMove = settings.highlightLastMove,
        animationsEnabled = settings.animationEnabled,
        flipped = flipped,
        interactive = !uiState.isGameOver && !uiState.isViewingHistory
    )

    uiState.pendingPromotion?.let { pending ->
        PawnPromotionDialog(
            color = pending.color,
            onPieceSelected = onPromotionSelected,
            onDismiss = onPromotionDismissed
        )
    }

    if (uiState.showResignConfirmDialog) {
        ResignConfirmDialog(
            onConfirm = onConfirmResign,
            onDismiss = onDismissResign
        )
    }

    if (uiState.showExitConfirmDialog) {
        ExitConfirmDialog(
            onConfirm = onConfirmExit,
            onDismiss = onDismissExit
        )
    }

    if (uiState.showGameOverDialog && uiState.outcome != null) {
        GameOverDialog(
            outcome = uiState.outcome,
            plyCount = uiState.moveHistory.size,
            onNewGame = onNewGame,
            isLanMatch = uiState.isLanMatch,
            playerSide = uiState.playerSide,
            rematchOfferState = uiState.rematchOfferState,
            onAcceptRematch = onAcceptRematch,
            onDeclineRematch = onDeclineRematch,
            onExitMatch = onNavigateBack
        )
    }

    uiState.pgnPreview?.let { pgn ->
        PgnPreviewDialog(
            pgn = pgn,
            onDismiss = onDismissPgn
        )
    }

    if (showMoveHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoveHistorySheet = false },
            containerColor = ChessTheme.colors.surfaceElevated
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                FullMoveHistoryPanel(
                    movePairs = uiState.movePairs,
                    activeDisplayPly = uiState.activeDisplayPly,
                    totalPlies = uiState.moveHistory.size,
                    isViewingHistory = uiState.isViewingHistory,
                    onSelectPly = onSelectPly,
                    onJumpStart = onJumpStart,
                    onStepBack = onStepBack,
                    onStepForward = onStepForward,
                    onReturnLive = onReturnLive
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showGameMenuSheet) {
        val takebackPending = uiState.takebackOfferState == DrawOfferState.SENT ||
            uiState.takebackOfferState == DrawOfferState.RECEIVED
        val requiredPliesForTakeback = if (uiState.isLanMatch && uiState.sideToMove == uiState.playerSide) 2 else 1
        GameMenuSheet(
            canUndo = uiState.moveHistory.size >= requiredPliesForTakeback && !uiState.isGameOver && !takebackPending,
            onUndo = onUndo,
            onShowPgn = { onOpenPgn(whiteName, blackName) },
            onNewGame = onNewGame,
            onDismiss = { showGameMenuSheet = false },
            isLanMatch = uiState.isLanMatch,
            onLeaveRoom = onNavigateBack
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChessTopBar(
                title = "${stringResource(R.string.game_title)} • ${uiState.timeControl.label}",
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            val isWideLayout = maxWidth >= 700.dp

            if (isWideLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1.25f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val boardSide = minOf(maxWidth, maxHeight)
                        ChessBoard(
                            state = uiState.boardState,
                            config = boardConfig,
                            onSquareTap = onSquareTap,
                            onMoveAttempt = onMoveAttempt,
                            onInvalidDrop = onInvalidDrop,
                            canSelectOrDrag = { _, piece -> uiState.canInteractWithPiece(piece) },
                            cornerRadius = 8.dp,
                            modifier = Modifier.size(boardSide)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.75f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlayerBarForColor(
                            color = topColor,
                            whiteName = whiteName,
                            blackName = blackName,
                            uiState = uiState,
                            clockTestTag = "top_player_clock"
                        )

                        FullMoveHistoryPanel(
                            movePairs = uiState.movePairs,
                            activeDisplayPly = uiState.activeDisplayPly,
                            totalPlies = uiState.moveHistory.size,
                            isViewingHistory = uiState.isViewingHistory,
                            onSelectPly = onSelectPly,
                            onJumpStart = onJumpStart,
                            onStepBack = onStepBack,
                            onStepForward = onStepForward,
                            onReturnLive = onReturnLive,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (uiState.isViewingHistory) {
                            HistoryReviewBanner(
                                viewedPly = uiState.activeDisplayPly,
                                totalPlies = uiState.moveHistory.size,
                                onStepBack = onStepBack,
                                onStepForward = onStepForward,
                                onReturnLive = onReturnLive
                            )
                        }

                        DrawOfferBanner(
                            drawOfferState = uiState.drawOfferState,
                            offeredBy = uiState.drawOfferedBy,
                            onAcceptDraw = onAcceptDraw,
                            onDeclineDraw = onDeclineDraw,
                            isLanMatch = uiState.isLanMatch
                        )

                        TakebackRequestBanner(
                            takebackOfferState = uiState.takebackOfferState,
                            onAcceptTakeback = onAcceptTakeback,
                            onDeclineTakeback = onDeclineTakeback
                        )

                        if (!uiState.showGameOverDialog) {
                            RematchRequestBanner(
                                rematchOfferState = uiState.rematchOfferState,
                                onAcceptRematch = onAcceptRematch,
                                onDeclineRematch = onDeclineRematch
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        PlayerBarForColor(
                            color = bottomColor,
                            whiteName = whiteName,
                            blackName = blackName,
                            uiState = uiState,
                            clockTestTag = "bottom_player_clock"
                        )

                        GameControlBar(
                            flipped = flipped,
                            isGameOver = uiState.isGameOver,
                            drawOfferPending = uiState.drawOfferState == DrawOfferState.SENT ||
                                uiState.drawOfferState == DrawOfferState.RECEIVED,
                            onOfferDraw = onOfferDraw,
                            onResign = onRequestResign,
                            onFlip = {
                                flipped = !flipped
                                onBoardFlipped()
                            },
                            onOpenMoves = { showMoveHistorySheet = true },
                            onOpenMenu = { showGameMenuSheet = true }
                        )
                    }
                }
            } else {
                // Zero-jump portrait container: Column slots keep strictly constant heights at all times,
                // while transient banners (draw offers) float cleanly as an overlay without shifting the board.
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Main board section: opponent bar + 100% full-width board + player bar
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val maxBoardHeight = (maxHeight - 112.dp).coerceAtLeast(180.dp)
                            val boardSide = minOf(maxWidth, maxBoardHeight)

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PlayerBarForColor(
                                    color = topColor,
                                    whiteName = whiteName,
                                    blackName = blackName,
                                    uiState = uiState,
                                    clockTestTag = "top_player_clock",
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                )

                                ChessBoard(
                                    state = uiState.boardState,
                                    config = boardConfig,
                                    onSquareTap = onSquareTap,
                                    onMoveAttempt = onMoveAttempt,
                                    onInvalidDrop = onInvalidDrop,
                                    canSelectOrDrag = { _, piece -> uiState.canInteractWithPiece(piece) },
                                    cornerRadius = 0.dp,
                                    modifier = Modifier.size(boardSide)
                                )

                                PlayerBarForColor(
                                    color = bottomColor,
                                    whiteName = whiteName,
                                    blackName = blackName,
                                    uiState = uiState,
                                    clockTestTag = "bottom_player_clock",
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                )
                            }
                        }

                        // Fixed-height (48.dp) history strip with direct < / > / >| arrows and premove chip
                        CompactMoveHistoryStrip(
                            movePairs = uiState.movePairs,
                            activeDisplayPly = uiState.activeDisplayPly,
                            totalPlies = uiState.moveHistory.size,
                            isViewingHistory = uiState.isViewingHistory,
                            queuedPremoves = uiState.queuedPremoves,
                            onSelectPly = onSelectPly,
                            onStepBack = onStepBack,
                            onStepForward = onStepForward,
                            onReturnLive = onReturnLive,
                            onCancelPremove = onCancelPremove,
                            onOpenFullHistory = { showMoveHistorySheet = true },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )

                        GameControlBar(
                            flipped = flipped,
                            isGameOver = uiState.isGameOver,
                            drawOfferPending = uiState.drawOfferState == DrawOfferState.SENT ||
                                uiState.drawOfferState == DrawOfferState.RECEIVED,
                            onOfferDraw = onOfferDraw,
                            onResign = onRequestResign,
                            onFlip = {
                                flipped = !flipped
                                onBoardFlipped()
                            },
                            onOpenMoves = { showMoveHistorySheet = true },
                            onOpenMenu = { showGameMenuSheet = true },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }

                    // Request banners float cleanly above bottom bars so the board NEVER jumps
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 10.dp, end = 10.dp, bottom = 114.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DrawOfferBanner(
                            drawOfferState = uiState.drawOfferState,
                            offeredBy = uiState.drawOfferedBy,
                            onAcceptDraw = onAcceptDraw,
                            onDeclineDraw = onDeclineDraw,
                            isLanMatch = uiState.isLanMatch
                        )
                        TakebackRequestBanner(
                            takebackOfferState = uiState.takebackOfferState,
                            onAcceptTakeback = onAcceptTakeback,
                            onDeclineTakeback = onDeclineTakeback
                        )
                        if (!uiState.showGameOverDialog) {
                            RematchRequestBanner(
                                rematchOfferState = uiState.rematchOfferState,
                                onAcceptRematch = onAcceptRematch,
                                onDeclineRematch = onDeclineRematch
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerBarForColor(
    color: PieceColor,
    whiteName: String,
    blackName: String,
    uiState: GameUiState,
    clockTestTag: String,
    modifier: Modifier = Modifier
) {
    val isWhite = color == PieceColor.WHITE
    PlayerInfoClockBar(
        name = if (isWhite) whiteName else blackName,
        pieceColor = color,
        remainingMs = uiState.clock.remainingFor(color),
        isUnlimited = uiState.clock.isUnlimited,
        isActiveTurn = !uiState.isGameOver && uiState.sideToMove == color,
        isClockRunning = uiState.clock.isRunning && uiState.clock.activeColor == color,
        capturedPieces = if (isWhite) {
            uiState.materialBalance.capturedByWhite
        } else {
            uiState.materialBalance.capturedByBlack
        },
        materialAdvantage = if (isWhite) {
            uiState.materialBalance.whiteAdvantage
        } else {
            uiState.materialBalance.blackAdvantage
        },
        connectionStatus = uiState.connectionStatus,
        clockTestTag = clockTestTag,
        modifier = modifier
    )
}
