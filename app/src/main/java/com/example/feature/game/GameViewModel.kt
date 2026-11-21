package com.example.feature.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.chess.ChessClock
import com.example.core.chess.ChessEngine
import com.example.core.chess.ChessPositionSnapshot
import com.example.core.chess.ClockSnapshot
import com.example.core.chess.EngineMove
import com.example.core.chess.EngineMoveResult
import com.example.core.chess.MonotonicTimeProvider
import com.example.core.chess.PgnMetadata
import com.example.core.chess.PremoveCalculator
import com.example.core.network.GameSessionManager
import com.example.core.network.GameSessionState
import com.example.core.ui.board.BoardMoveAnimation
import com.example.core.ui.board.BoardPremove
import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.ChessBoardState
import com.example.core.ui.board.PieceType
import com.example.domain.model.AppSettings
import com.example.domain.model.PieceColor
import com.example.domain.model.TimeControl
import com.example.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GameViewModel private constructor(
    private val chessEngine: ChessEngine,
    private val timeProvider: MonotonicTimeProvider,
    observeSettingsUseCase: ObserveSettingsUseCase,
    private val gameSessionManager: GameSessionManager?,
    @Suppress("UNUSED_PARAMETER") internalMarker: Unit
) : ViewModel() {

    @Inject
    constructor(
        chessEngine: ChessEngine,
        timeProvider: MonotonicTimeProvider,
        observeSettingsUseCase: ObserveSettingsUseCase,
        gameSessionManager: GameSessionManager
    ) : this(chessEngine, timeProvider, observeSettingsUseCase, gameSessionManager, Unit)

    constructor(
        chessEngine: ChessEngine,
        timeProvider: MonotonicTimeProvider,
        observeSettingsUseCase: ObserveSettingsUseCase
    ) : this(chessEngine, timeProvider, observeSettingsUseCase, null, Unit)

    private var chessClock = ChessClock(TimeControl.RAPID_10_0, timeProvider)
    private var initializedSessionKey: String? = null
    private var clockTickerJob: Job? = null
    private var lanObserverJob: Job? = null
    private var isLanMode: Boolean = false
    private var lastAppliedLanMoveId: Long = 0L
    private var pendingDraggedLanMove: Pair<BoardSquare, BoardSquare>? = null
    private var animationIdCounter = 0L
    private var versionCounter = 0L
    private var lastActionElapsedMs = 0L

    val settings: StateFlow<AppSettings> = observeSettingsUseCase().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    private val _uiState = MutableStateFlow(
        buildLiveUiState(
            snapshot = chessEngine.snapshot(),
            clockSnapshot = chessClock.snapshot(),
            timeControl = TimeControl.RAPID_10_0,
            playerSide = PieceColor.WHITE
        )
    )
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    fun initializeSession(
        timeControlId: String,
        playerSide: PieceColor = PieceColor.WHITE,
        isLanMatch: Boolean = false
    ) {
        val key = "$timeControlId:${playerSide.name}:$isLanMatch"
        if (initializedSessionKey == key) return
        initializedSessionKey = key

        val manager = gameSessionManager
        if (isLanMatch && manager != null && manager.sessionState.value.isActiveLanSession) {
            isLanMode = true
            startLanObservation(manager)
        } else {
            isLanMode = false
            lanObserverJob?.cancel()
            lanObserverJob = null
            val control = TimeControl.fromId(timeControlId)
            startNewGameInternal(control, playerSide)
        }
    }

    fun leaveLanMatch() {
        val manager = gameSessionManager ?: return
        if (!isLanMode) return
        stopClockTicker()
        lanObserverJob?.cancel()
        lanObserverJob = null
        viewModelScope.launch {
            manager.disconnect()
        }
    }

    fun onSquareTap(square: BoardSquare) {
        val current = _uiState.value
        if (current.isGameOver || current.isViewingHistory) return

        val board = current.boardState
        if (board.selectedSquare == square) {
            val shouldCancelPremove = board.isPremoveSelection &&
                current.queuedPremove?.from == square
            val nextPremove = if (shouldCancelPremove) null else current.queuedPremove
            _uiState.value = current.copy(
                queuedPremove = nextPremove,
                boardState = board.copy(
                    selectedSquare = null,
                    legalTargets = emptyList(),
                    isPremoveSelection = false,
                    queuedPremove = nextPremove
                )
            )
            return
        }

        val piece = board.pieces[square]
        if (piece != null && current.canInteractWithPiece(piece)) {
            if (piece.color == current.sideToMove) {
                val targets = if (isLanMode) {
                    gameSessionManager?.legalTargetsFrom(square).orEmpty()
                } else {
                    chessEngine.legalTargetsFrom(square)
                }
                _uiState.value = current.copy(
                    boardState = board.copy(
                        selectedSquare = square,
                        legalTargets = targets,
                        isPremoveSelection = false
                    )
                )
            } else {
                // Waiting-side piece tapped -> enter premove target selection
                val premoveTargets = PremoveCalculator.premoveTargetsFor(square, piece)
                _uiState.value = current.copy(
                    boardState = board.copy(
                        selectedSquare = square,
                        legalTargets = premoveTargets,
                        isPremoveSelection = true
                    )
                )
            }
        } else {
            // Tapping an empty / non-selectable square clears selection and cancels any queued premove
            _uiState.value = current.copy(
                queuedPremove = null,
                boardState = board.copy(
                    selectedSquare = null,
                    legalTargets = emptyList(),
                    isPremoveSelection = false,
                    queuedPremove = null
                )
            )
        }
    }

    fun onMoveAttempt(from: BoardSquare, to: BoardSquare, fromDrag: Boolean): Boolean {
        val current = _uiState.value
        if (current.isGameOver || current.isViewingHistory) return false

        val piece = if (isLanMode) {
            gameSessionManager?.livePositionSnapshot()?.pieces?.get(from)
        } else {
            chessEngine.snapshot().pieces[from]
        } ?: return false
        if (!current.canInteractWithPiece(piece)) return false

        // Waiting side piece -> queue Chess.com-style premove
        if (piece.color != current.sideToMove) {
            return attemptQueuePremove(from = from, to = to, piece = piece, fromDrag = fromDrag)
        }

        if (isLanMode) {
            val manager = gameSessionManager ?: return false
            val legalTargets = manager.legalTargetsFrom(from)
            if (legalTargets.none { it.square == to }) return false

            if (manager.isPromotionMove(from, to)) {
                _uiState.value = current.copy(
                    pendingPromotion = PendingPromotion(
                        from = from,
                        to = to,
                        color = piece.color,
                        fromDrag = fromDrag,
                        isPremove = false
                    ),
                    boardState = current.boardState.copy(
                        selectedSquare = null,
                        legalTargets = emptyList(),
                        isPremoveSelection = false
                    )
                )
                return true
            }

            pendingDraggedLanMove = if (fromDrag) (from to to) else null
            _uiState.value = current.copy(
                boardState = current.boardState.copy(
                    selectedSquare = null,
                    legalTargets = emptyList(),
                    isPremoveSelection = false
                )
            )
            viewModelScope.launch {
                manager.submitMove(from, to, promotion = null)
            }
            return true
        }

        // Verify live clock before committing live move so flagged players cannot move after 0:00
        if (checkAndApplyTimeoutIfFlagged()) return false

        return when (val result = chessEngine.attemptMove(from, to, promotion = null)) {
            is EngineMoveResult.Success -> {
                handleMoveCommitted(result.move, result.snapshot, fromDrag = fromDrag)
                true
            }
            is EngineMoveResult.RequiresPromotion -> {
                _uiState.value = current.copy(
                    pendingPromotion = PendingPromotion(
                        from = result.from,
                        to = result.to,
                        color = result.color,
                        fromDrag = fromDrag,
                        isPremove = false
                    ),
                    boardState = current.boardState.copy(
                        selectedSquare = null,
                        legalTargets = emptyList(),
                        isPremoveSelection = false
                    )
                )
                true
            }
            is EngineMoveResult.Illegal -> false
        }
    }

    private fun attemptQueuePremove(
        from: BoardSquare,
        to: BoardSquare,
        piece: com.example.core.ui.board.BoardPiece,
        fromDrag: Boolean
    ): Boolean {
        val current = _uiState.value
        if (!PremoveCalculator.isPremoveTargetValid(from, to, piece)) {
            val nextPremove = current.queuedPremove?.takeIf { it.piece.color != piece.color }
            _uiState.value = current.copy(
                queuedPremove = nextPremove,
                boardState = current.boardState.copy(
                    selectedSquare = null,
                    legalTargets = emptyList(),
                    isPremoveSelection = false,
                    queuedPremove = nextPremove
                )
            )
            return false
        }

        if (PremoveCalculator.isPremovePromotion(from, to, piece)) {
            _uiState.value = current.copy(
                pendingPromotion = PendingPromotion(
                    from = from,
                    to = to,
                    color = piece.color,
                    fromDrag = fromDrag,
                    isPremove = true
                ),
                boardState = current.boardState.copy(
                    selectedSquare = null,
                    legalTargets = emptyList(),
                    isPremoveSelection = false
                )
            )
            return true
        }

        val premove = BoardPremove(from = from, to = to, piece = piece, promotion = null)
        _uiState.value = current.copy(
            queuedPremove = premove,
            boardState = current.boardState.copy(
                selectedSquare = null,
                legalTargets = emptyList(),
                isPremoveSelection = false,
                queuedPremove = premove
            )
        )
        return true
    }

    fun cancelPremove() {
        _uiState.update { current ->
            if (current.queuedPremove == null && !current.boardState.isPremoveSelection) return@update current
            current.copy(
                queuedPremove = null,
                boardState = current.boardState.copy(
                    selectedSquare = if (current.boardState.isPremoveSelection) null else current.boardState.selectedSquare,
                    legalTargets = if (current.boardState.isPremoveSelection) emptyList() else current.boardState.legalTargets,
                    isPremoveSelection = false,
                    queuedPremove = null
                )
            )
        }
    }

    fun onPromotionSelected(pieceType: PieceType) {
        val pending = _uiState.value.pendingPromotion ?: return
        if (pending.isPremove) {
            val piece = if (isLanMode) {
                gameSessionManager?.livePositionSnapshot()?.pieces?.get(pending.from)
            } else {
                chessEngine.snapshot().pieces[pending.from]
            }
            if (piece == null || piece.color == _uiState.value.sideToMove) {
                _uiState.update { it.copy(pendingPromotion = null) }
                return
            }
            val premove = BoardPremove(
                from = pending.from,
                to = pending.to,
                piece = piece,
                promotion = pieceType
            )
            _uiState.update { current ->
                current.copy(
                    pendingPromotion = null,
                    queuedPremove = premove,
                    boardState = current.boardState.copy(
                        selectedSquare = null,
                        legalTargets = emptyList(),
                        isPremoveSelection = false,
                        queuedPremove = premove
                    )
                )
            }
            return
        }

        if (isLanMode) {
            val manager = gameSessionManager ?: return
            pendingDraggedLanMove = if (pending.fromDrag) (pending.from to pending.to) else null
            _uiState.update { it.copy(pendingPromotion = null) }
            viewModelScope.launch {
                manager.submitMove(pending.from, pending.to, promotion = pieceType)
            }
            return
        }

        if (checkAndApplyTimeoutIfFlagged()) {
            _uiState.update { it.copy(pendingPromotion = null) }
            return
        }
        when (val result = chessEngine.attemptMove(pending.from, pending.to, promotion = pieceType)) {
            is EngineMoveResult.Success -> {
                handleMoveCommitted(result.move, result.snapshot, fromDrag = pending.fromDrag)
            }
            else -> {
                _uiState.update { it.copy(pendingPromotion = null) }
            }
        }
    }

    fun onPromotionDismissed() {
        _uiState.update { it.copy(pendingPromotion = null) }
    }

    // ---- Move history non-mutating inspection ----

    fun viewPly(ply: Int) {
        val historySize = _uiState.value.moveHistory.size
        val clamped = ply.coerceIn(0, historySize)
        if (clamped == historySize) {
            returnToLivePosition()
            return
        }
        val historicalSnapshot = if (isLanMode) {
            gameSessionManager?.positionAtPly(clamped) ?: return
        } else {
            chessEngine.positionAtPly(clamped)
        }
        _uiState.update { current ->
            current.copy(
                viewedPlyIndex = clamped,
                boardState = ChessBoardState(
                    pieces = historicalSnapshot.pieces,
                    selectedSquare = null,
                    legalTargets = emptyList(),
                    lastMove = historicalSnapshot.lastMove?.toBoardMove(),
                    checkedKingSquare = historicalSnapshot.checkedKingSquare,
                    lastMoveAnimation = null,
                    queuedPremove = null,
                    isPremoveSelection = false,
                    positionVersion = ++versionCounter
                ),
                materialBalance = GameHistoryFormatter.computeMaterialBalance(historicalSnapshot.pieces)
            )
        }
    }

    fun stepHistoryBackward() {
        val current = _uiState.value
        if (current.moveHistory.isEmpty()) return
        val target = (current.activeDisplayPly - 1).coerceAtLeast(0)
        viewPly(target)
    }

    fun stepHistoryForward() {
        val current = _uiState.value
        if (!current.isViewingHistory) return
        val target = (current.activeDisplayPly + 1).coerceAtMost(current.moveHistory.size)
        viewPly(target)
    }

    fun jumpToHistoryStart() {
        if (_uiState.value.moveHistory.isEmpty()) return
        viewPly(0)
    }

    fun returnToLivePosition() {
        val liveSnapshot = if (isLanMode) {
            gameSessionManager?.livePositionSnapshot() ?: return
        } else {
            chessEngine.snapshot()
        }
        _uiState.update { current ->
            current.copy(
                viewedPlyIndex = null,
                boardState = ChessBoardState(
                    pieces = liveSnapshot.pieces,
                    selectedSquare = null,
                    legalTargets = emptyList(),
                    lastMove = liveSnapshot.lastMove?.toBoardMove(),
                    checkedKingSquare = liveSnapshot.checkedKingSquare,
                    lastMoveAnimation = null,
                    queuedPremove = current.queuedPremove,
                    isPremoveSelection = false,
                    positionVersion = ++versionCounter
                ),
                materialBalance = GameHistoryFormatter.computeMaterialBalance(liveSnapshot.pieces)
            )
        }
    }

    // ---- Draw offer state machine (NONE -> SENT / RECEIVED -> ACCEPTED / DECLINED) ----

    fun offerDraw() {
        val current = _uiState.value
        if (current.isGameOver) return
        if (current.drawOfferState == DrawOfferState.SENT || current.drawOfferState == DrawOfferState.RECEIVED) {
            return
        }
        if (!acquireActionGuard()) return

        if (isLanMode) {
            val manager = gameSessionManager ?: return
            viewModelScope.launch { manager.offerDraw() }
            return
        }

        _uiState.update {
            it.copy(
                drawOfferState = DrawOfferState.SENT,
                drawOfferedBy = it.sideToMove
            )
        }
    }

    /**
     * Used by future network client/host (Stage 2.4+) or tests when receiving a remote draw offer.
     */
    fun onRemoteDrawOfferReceived(fromColor: PieceColor) {
        val current = _uiState.value
        if (current.isGameOver) return
        _uiState.update {
            it.copy(
                drawOfferState = DrawOfferState.RECEIVED,
                drawOfferedBy = fromColor
            )
        }
    }

    fun acceptDraw() {
        val current = _uiState.value
        if (current.isGameOver) return
        if (current.drawOfferState != DrawOfferState.SENT &&
            current.drawOfferState != DrawOfferState.RECEIVED
        ) return
        if (!acquireActionGuard()) return

        if (isLanMode) {
            if (current.drawOfferState != DrawOfferState.RECEIVED) return
            val manager = gameSessionManager ?: return
            viewModelScope.launch { manager.respondToDrawOffer(accepted = true) }
            return
        }

        chessEngine.agreeDraw()
        val stoppedClock = chessClock.stop()
        stopClockTicker()
        val live = chessEngine.snapshot()
        _uiState.value = buildLiveUiState(
            snapshot = live,
            clockSnapshot = stoppedClock,
            timeControl = current.timeControl,
            playerSide = current.playerSide,
            drawOfferState = DrawOfferState.ACCEPTED,
            drawOfferedBy = current.drawOfferedBy,
            showGameOverDialog = true
        )
    }

    fun declineDraw() {
        val current = _uiState.value
        if (current.isGameOver) return
        if (current.drawOfferState != DrawOfferState.SENT &&
            current.drawOfferState != DrawOfferState.RECEIVED
        ) return
        if (!acquireActionGuard()) return

        if (isLanMode) {
            if (current.drawOfferState != DrawOfferState.RECEIVED) return
            val manager = gameSessionManager ?: return
            viewModelScope.launch { manager.respondToDrawOffer(accepted = false) }
            return
        }

        _uiState.update {
            it.copy(
                drawOfferState = DrawOfferState.DECLINED,
                drawOfferedBy = null
            )
        }
    }

    // ---- Resignation with confirmResign setting ----

    fun requestResign() {
        val current = _uiState.value
        if (current.isGameOver) return
        if (!acquireActionGuard()) return

        if (settings.value.confirmResign) {
            _uiState.update { it.copy(showResignConfirmDialog = true) }
        } else {
            confirmResignNow()
        }
    }

    fun dismissResignConfirm() {
        _uiState.update { it.copy(showResignConfirmDialog = false) }
    }

    fun confirmResignNow() {
        val current = _uiState.value
        if (current.isGameOver) return
        if (isLanMode) {
            val manager = gameSessionManager ?: return
            _uiState.update { it.copy(showResignConfirmDialog = false) }
            viewModelScope.launch { manager.resign() }
            return
        }
        chessEngine.resign(current.sideToMove)
        val stoppedClock = chessClock.stop()
        stopClockTicker()
        val live = chessEngine.snapshot()
        _uiState.value = buildLiveUiState(
            snapshot = live,
            clockSnapshot = stoppedClock,
            timeControl = current.timeControl,
            playerSide = current.playerSide,
            showGameOverDialog = true
        )
    }

    // ---- Utility actions ----

    fun undoMove() {
        if (isLanMode) return
        val current = _uiState.value
        if (current.moveHistory.isEmpty() || current.isGameOver) return
        if (chessEngine.undoLastMove() != null) {
            val live = chessEngine.snapshot()
            val clockSnap = if (live.moveHistory.isEmpty()) {
                stopClockTicker()
                chessClock.reset(current.timeControl)
            } else {
                // Keep clock strictly synchronized with the side whose turn it is after undo
                val switched = chessClock.switchActiveSide(live.sideToMove)
                if (switched.flaggedColor != null) {
                    chessEngine.timeout(switched.flaggedColor)
                    stopClockTicker()
                } else {
                    ensureClockTickerRunning()
                }
                switched
            }
            val finalLive = chessEngine.snapshot()
            _uiState.value = buildLiveUiState(
                snapshot = finalLive,
                clockSnapshot = clockSnap,
                timeControl = current.timeControl,
                playerSide = current.playerSide,
                showGameOverDialog = finalLive.isGameOver
            )
        }
    }

    fun resetGame() {
        if (isLanMode) return
        val current = _uiState.value
        startNewGameInternal(current.timeControl, current.playerSide)
    }

    fun dismissGameOverDialog() {
        _uiState.update { it.copy(showGameOverDialog = false) }
    }

    fun openPgnPreview(whiteName: String, blackName: String) {
        val pgn = if (isLanMode) {
            gameSessionManager?.exportPgn(whiteName, blackName).orEmpty()
        } else {
            chessEngine.exportPgn(
                PgnMetadata(
                    whitePlayer = whiteName,
                    blackPlayer = blackName,
                    timeControl = _uiState.value.timeControl.label
                )
            )
        }
        _uiState.update { it.copy(pgnPreview = pgn) }
    }

    fun dismissPgnPreview() {
        _uiState.update { it.copy(pgnPreview = null) }
    }

    /**
     * Manually ticks/evaluates the monotonic clock (invoked by ticker and tests).
     * Enforces the invariant that a running clock must always belong to [GameUiState.sideToMove].
     */
    internal fun refreshClockNow() {
        val current = _uiState.value
        if (current.isGameOver || current.timeControl.isUnlimited) return
        if (isLanMode) {
            val manager = gameSessionManager ?: return
            val liveClock = manager.liveClockSnapshot()
            _uiState.update { it.copy(clock = liveClock) }
            return
        }
        if (checkAndApplyTimeoutIfFlagged()) return
        var snap = chessClock.snapshot()
        if (snap.isRunning && snap.activeColor != current.sideToMove) {
            snap = chessClock.switchActiveSide(current.sideToMove)
        }
        _uiState.update { it.copy(clock = snap) }
    }

    private fun startLanObservation(manager: GameSessionManager) {
        lanObserverJob?.cancel()
        lastAppliedLanMoveId = 0L
        lanObserverJob = viewModelScope.launch {
            manager.sessionState.collect { session ->
                applyLanSessionState(manager, session)
            }
        }
    }

    private fun applyLanSessionState(
        manager: GameSessionManager,
        session: GameSessionState
    ) {
        val snapshot = session.positionSnapshot ?: manager.livePositionSnapshot()
        val clockSnap = session.clockSnapshot ?: manager.liveClockSnapshot()
        val previous = _uiState.value

        val isNewMove = session.lastAnimatedMoveId != 0L &&
            session.lastAnimatedMoveId != lastAppliedLanMoveId
        val newAnimatedMove = if (isNewMove) session.lastAnimatedMove else null
        if (isNewMove) {
            lastAppliedLanMoveId = session.lastAnimatedMoveId
        }

        val skipSlide = if (newAnimatedMove != null && pendingDraggedLanMove != null) {
            val dragged = pendingDraggedLanMove
            pendingDraggedLanMove = null
            dragged?.first == newAnimatedMove.from && dragged.second == newAnimatedMove.to
        } else {
            false
        }

        // Execute queued premove over LAN as soon as opponent's authoritative move hands us the turn
        var nextPremove = previous.queuedPremove
        if (isNewMove &&
            !snapshot.isGameOver &&
            nextPremove != null &&
            nextPremove.piece.color == session.localColor &&
            snapshot.sideToMove == session.localColor
        ) {
            val premoveToFire = nextPremove
            nextPremove = null
            viewModelScope.launch {
                val promo = if (manager.isPromotionMove(premoveToFire.from, premoveToFire.to)) {
                    premoveToFire.promotion ?: PieceType.QUEEN
                } else {
                    premoveToFire.promotion
                }
                manager.submitMove(premoveToFire.from, premoveToFire.to, promo)
            }
        } else if (snapshot.isGameOver) {
            nextPremove = null
        }

        if (snapshot.isGameOver || session.timeControl.isUnlimited) {
            stopClockTicker()
        } else if (clockSnap.isRunning) {
            ensureClockTickerRunning()
        }

        val wasGameOver = previous.isGameOver
        val showGameOverNow = if (snapshot.isGameOver && !wasGameOver) {
            true
        } else {
            previous.showGameOverDialog && snapshot.isGameOver
        }

        if (previous.isViewingHistory && previous.viewedPlyIndex != null && !snapshot.isGameOver) {
            // Preserve user's history inspection without jumping the board while still keeping clock/moves fresh
            _uiState.update {
                it.copy(
                    timeControl = session.timeControl,
                    playerSide = session.localColor,
                    restrictToLocalPlayer = true,
                    isLanMatch = true,
                    localPlayerName = session.localPlayerName,
                    opponentName = session.opponentName,
                    sideToMove = snapshot.sideToMove,
                    isCheck = snapshot.isCheck,
                    outcome = snapshot.outcome,
                    moveHistory = snapshot.moveHistory,
                    movePairs = GameHistoryFormatter.toMovePairs(snapshot.moveHistory),
                    queuedPremove = nextPremove,
                    clock = clockSnap,
                    drawOfferState = session.drawOfferState,
                    drawOfferedBy = session.drawOfferedBy,
                    connectionStatus = session.connectionStatus,
                    showGameOverDialog = showGameOverNow
                )
            }
            return
        }

        val moveAnimation = newAnimatedMove?.let { move ->
            BoardMoveAnimation(
                id = ++animationIdCounter,
                piece = move.movedPiece,
                from = move.from,
                to = move.to,
                capturedPiece = move.capturedPiece,
                capturedSquare = move.capturedSquare ?: move.to,
                skipSlideAnimation = skipSlide
            )
        }

        val keepSelection = !isNewMove &&
            previous.boardState.selectedSquare != null &&
            !snapshot.isGameOver

        _uiState.value = GameUiState(
            timeControl = session.timeControl,
            playerSide = session.localColor,
            restrictToLocalPlayer = true,
            isLanMatch = true,
            localPlayerName = session.localPlayerName,
            opponentName = session.opponentName,
            boardState = ChessBoardState(
                pieces = snapshot.pieces,
                selectedSquare = if (keepSelection) previous.boardState.selectedSquare else null,
                legalTargets = if (keepSelection) previous.boardState.legalTargets else emptyList(),
                lastMove = snapshot.lastMove?.toBoardMove(),
                checkedKingSquare = snapshot.checkedKingSquare,
                lastMoveAnimation = moveAnimation,
                queuedPremove = nextPremove,
                isPremoveSelection = if (keepSelection) previous.boardState.isPremoveSelection else false,
                positionVersion = if (isNewMove || previous.moveHistory.size != snapshot.moveHistory.size) {
                    ++versionCounter
                } else {
                    previous.boardState.positionVersion
                }
            ),
            sideToMove = snapshot.sideToMove,
            isCheck = snapshot.isCheck,
            outcome = snapshot.outcome,
            moveHistory = snapshot.moveHistory,
            movePairs = GameHistoryFormatter.toMovePairs(snapshot.moveHistory),
            viewedPlyIndex = null,
            queuedPremove = nextPremove,
            clock = clockSnap,
            drawOfferState = session.drawOfferState,
            drawOfferedBy = session.drawOfferedBy,
            connectionStatus = session.connectionStatus,
            materialBalance = GameHistoryFormatter.computeMaterialBalance(snapshot.pieces),
            pendingPromotion = previous.pendingPromotion?.takeIf { !isNewMove && !snapshot.isGameOver },
            showResignConfirmDialog = previous.showResignConfirmDialog && !snapshot.isGameOver,
            showGameOverDialog = showGameOverNow,
            pgnPreview = previous.pgnPreview
        )
    }

    private fun startNewGameInternal(control: TimeControl, playerSide: PieceColor) {
        stopClockTicker()
        chessEngine.reset()
        chessClock = ChessClock(control, timeProvider)
        _uiState.value = buildLiveUiState(
            snapshot = chessEngine.snapshot(),
            clockSnapshot = chessClock.snapshot(),
            timeControl = control,
            playerSide = playerSide
        )
    }

    private fun handleMoveCommitted(
        move: EngineMove,
        snapshot: ChessPositionSnapshot,
        fromDrag: Boolean
    ) {
        val previousState = _uiState.value
        val currentControl = previousState.timeControl
        val pendingPremove = previousState.queuedPremove

        var updatedClock = advanceClockAfterMove(move.color, snapshot.isGameOver)
        var finalSnapshot = chessEngine.snapshot()
        var displayedMove = move
        var skipSlide = fromDrag

        // Attempt automatic execution of queued premove if it belongs to the new sideToMove
        if (!finalSnapshot.isGameOver &&
            pendingPremove != null &&
            pendingPremove.piece.color == finalSnapshot.sideToMove
        ) {
            val premoveResult = executeQueuedPremove(pendingPremove)
            if (premoveResult is EngineMoveResult.Success) {
                updatedClock = advanceClockAfterMove(
                    movedColor = premoveResult.move.color,
                    isGameOver = premoveResult.snapshot.isGameOver
                )
                finalSnapshot = chessEngine.snapshot()
                displayedMove = premoveResult.move
                // Always animate auto-executed premove smoothly across the board
                skipSlide = false
            }
        }

        _uiState.value = buildLiveUiState(
            snapshot = finalSnapshot,
            clockSnapshot = updatedClock,
            timeControl = currentControl,
            playerSide = previousState.playerSide,
            animatedMove = displayedMove,
            skipSlideAnimation = skipSlide,
            queuedPremove = null,
            drawOfferState = DrawOfferState.NONE,
            drawOfferedBy = null,
            showGameOverDialog = finalSnapshot.isGameOver
        )
    }

    private fun executeQueuedPremove(premove: BoardPremove): EngineMoveResult {
        val attempt = chessEngine.attemptMove(
            from = premove.from,
            to = premove.to,
            promotion = premove.promotion
        )
        return if (attempt is EngineMoveResult.RequiresPromotion) {
            chessEngine.attemptMove(
                from = premove.from,
                to = premove.to,
                promotion = premove.promotion ?: PieceType.QUEEN
            )
        } else {
            attempt
        }
    }

    private fun advanceClockAfterMove(movedColor: PieceColor, isGameOver: Boolean): ClockSnapshot {
        return if (isGameOver) {
            stopClockTicker()
            chessClock.stop()
        } else {
            val nextClock = chessClock.onMoveCompleted(movedColor)
            if (nextClock.flaggedColor != null) {
                chessEngine.timeout(nextClock.flaggedColor)
                stopClockTicker()
                nextClock
            } else {
                ensureClockTickerRunning()
                nextClock
            }
        }
    }

    private fun checkAndApplyTimeoutIfFlagged(): Boolean {
        val snap = chessClock.snapshot()
        val flagged = snap.flaggedColor ?: return false
        chessEngine.timeout(flagged)
        stopClockTicker()
        val live = chessEngine.snapshot()
        _uiState.update { current ->
            buildLiveUiState(
                snapshot = live,
                clockSnapshot = snap,
                timeControl = current.timeControl,
                playerSide = current.playerSide,
                showGameOverDialog = true
            )
        }
        return true
    }

    private fun ensureClockTickerRunning() {
        if (_uiState.value.timeControl.isUnlimited) return
        if (clockTickerJob?.isActive == true) return
        clockTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(CLOCK_POLL_INTERVAL_MS)
                refreshClockNow()
            }
        }
    }

    private fun stopClockTicker() {
        clockTickerJob?.cancel()
        clockTickerJob = null
    }

    private fun acquireActionGuard(): Boolean {
        val now = timeProvider.elapsedRealtimeMs()
        if (lastActionElapsedMs > 0L && now - lastActionElapsedMs < ACTION_DEBOUNCE_MS) {
            return false
        }
        lastActionElapsedMs = now
        return true
    }

    private fun buildLiveUiState(
        snapshot: ChessPositionSnapshot,
        clockSnapshot: ClockSnapshot,
        timeControl: TimeControl,
        playerSide: PieceColor = PieceColor.WHITE,
        animatedMove: EngineMove? = null,
        skipSlideAnimation: Boolean = false,
        queuedPremove: BoardPremove? = null,
        drawOfferState: DrawOfferState = DrawOfferState.NONE,
        drawOfferedBy: PieceColor? = null,
        showGameOverDialog: Boolean = false
    ): GameUiState {
        val moveAnimation = animatedMove?.let { move ->
            BoardMoveAnimation(
                id = ++animationIdCounter,
                piece = move.movedPiece,
                from = move.from,
                to = move.to,
                capturedPiece = move.capturedPiece,
                capturedSquare = move.capturedSquare ?: move.to,
                skipSlideAnimation = skipSlideAnimation
            )
        }
        return GameUiState(
            timeControl = timeControl,
            playerSide = playerSide,
            boardState = ChessBoardState(
                pieces = snapshot.pieces,
                selectedSquare = null,
                legalTargets = emptyList(),
                lastMove = snapshot.lastMove?.toBoardMove(),
                checkedKingSquare = snapshot.checkedKingSquare,
                lastMoveAnimation = moveAnimation,
                queuedPremove = queuedPremove,
                isPremoveSelection = false,
                positionVersion = ++versionCounter
            ),
            sideToMove = snapshot.sideToMove,
            isCheck = snapshot.isCheck,
            outcome = snapshot.outcome,
            moveHistory = snapshot.moveHistory,
            movePairs = GameHistoryFormatter.toMovePairs(snapshot.moveHistory),
            viewedPlyIndex = null,
            queuedPremove = queuedPremove,
            clock = clockSnapshot,
            drawOfferState = drawOfferState,
            drawOfferedBy = drawOfferedBy,
            connectionStatus = ConnectionStatusUi.LOCAL_PASS_AND_PLAY,
            materialBalance = GameHistoryFormatter.computeMaterialBalance(snapshot.pieces),
            pendingPromotion = null,
            showResignConfirmDialog = false,
            showGameOverDialog = showGameOverDialog,
            pgnPreview = null
        )
    }

    override fun onCleared() {
        stopClockTicker()
        lanObserverJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val CLOCK_POLL_INTERVAL_MS = 100L
        private const val ACTION_DEBOUNCE_MS = 350L
    }
}
