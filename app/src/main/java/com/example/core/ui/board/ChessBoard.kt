package com.example.core.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ui.board.ChessBoardRenderer.drawCheckHighlight
import com.example.core.ui.board.ChessBoardRenderer.drawCoordinates
import com.example.core.ui.board.ChessBoardRenderer.drawDragTargetHighlight
import com.example.core.ui.board.ChessBoardRenderer.drawLastMoveHighlight
import com.example.core.ui.board.ChessBoardRenderer.drawLegalTargets
import com.example.core.ui.board.ChessBoardRenderer.drawPieceAtCenter
import com.example.core.ui.board.ChessBoardRenderer.drawPremoveHighlight
import com.example.core.ui.board.ChessBoardRenderer.drawSelectedSquareHighlight
import com.example.core.ui.board.ChessBoardRenderer.drawSquares
import com.example.core.ui.theme.ChessMotion
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.core.ui.theme.toSquareColors
import kotlin.math.hypot

private data class ActiveDragState(
    val originSquare: BoardSquare,
    val piece: BoardPiece,
    val pointerOffset: Offset,
    val hoveredSquare: BoardSquare?
)

private data class PendingDropState(
    val from: BoardSquare,
    val to: BoardSquare,
    val piece: BoardPiece,
    val sourcePieces: Map<BoardSquare, BoardPiece>
)

private data class SnapBackState(
    val id: Long,
    val originSquare: BoardSquare,
    val piece: BoardPiece,
    val startVisualCenter: Offset,
    val targetCenter: Offset
)

/**
 * Reusable commercial-grade 8x8 Jetpack Compose Canvas ChessBoard.
 *
 * Guarantees frame-1 glitch-free transitions:
 * - Tap/remote move slides start strictly at progress 0f on the first composed frame (never flashing at destination).
 * - Valid Drag & Drop holds an optimistic landing frame until StateFlow commits (never flashing back to origin).
 * - Invalid/off-board/same-square drops start snap-back strictly at progress 0f from finger release center.
 */
@Composable
fun ChessBoard(
    state: ChessBoardState,
    config: ChessBoardConfig,
    onSquareTap: (BoardSquare) -> Unit,
    onMoveAttempt: (from: BoardSquare, to: BoardSquare, fromDrag: Boolean) -> Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    onInvalidDrop: (from: BoardSquare, droppedOn: BoardSquare?) -> Unit = { _, _ -> },
    canSelectOrDrag: (BoardSquare, BoardPiece) -> Boolean = { _, _ -> true }
) {
    val pieceCache = rememberPieceBitmapCache(config.pieceSet)
    val squareColors = remember(config.boardTheme) { config.boardTheme.toSquareColors() }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val boardDescription = stringResource(R.string.cd_board_preview)

    var activeDrag by remember { mutableStateOf<ActiveDragState?>(null) }
    var pendingDrop by remember { mutableStateOf<PendingDropState?>(null) }
    var snapBack by remember { mutableStateOf<SnapBackState?>(null) }
    var snapBackCounter by remember { mutableStateOf(0L) }

    // Synchronous frame-1-safe move slide animation tracking
    val moveAnimProgress = remember { Animatable(1f) }
    var startedMoveAnimId by remember { mutableStateOf<Long?>(null) }
    var completedMoveAnimId by remember { mutableStateOf<Long?>(null) }

    val activeMoveAnim = state.lastMoveAnimation?.takeIf { anim ->
        config.animationsEnabled &&
            !anim.skipSlideAnimation &&
            anim.id != completedMoveAnimId
    }

    LaunchedEffect(activeMoveAnim?.id, config.animationsEnabled, config.moveAnimationDurationMs) {
        val anim = activeMoveAnim
        if (anim == null || !config.animationsEnabled) {
            moveAnimProgress.snapTo(1f)
            return@LaunchedEffect
        }
        moveAnimProgress.snapTo(0f)
        startedMoveAnimId = anim.id
        moveAnimProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = config.moveAnimationDurationMs.coerceIn(60, 400),
                easing = FastOutSlowInEasing
            )
        )
        completedMoveAnimId = anim.id
    }

    // Synchronous frame-1-safe invalid-drop snap-back animation
    val snapBackProgress = remember { Animatable(1f) }
    var startedSnapBackId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(snapBack?.id, config.animationsEnabled) {
        val currentSnap = snapBack ?: return@LaunchedEffect
        if (!config.animationsEnabled) {
            snapBack = null
            snapBackProgress.snapTo(1f)
            return@LaunchedEffect
        }
        snapBackProgress.snapTo(0f)
        startedSnapBackId = currentSnap.id
        snapBackProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = ChessMotion.SNAP_BACK_ANIMATION_MS,
                easing = FastOutSlowInEasing
            )
        )
        if (snapBack?.id == currentSnap.id) {
            snapBack = null
        }
    }

    // Clear optimistic drop lock once incoming state catches up
    LaunchedEffect(state) {
        if (pendingDrop != null && state.pieces !== pendingDrop?.sourcePieces) {
            pendingDrop = null
        } else if (pendingDrop != null && state.selectedSquare == null) {
            pendingDrop = null
        }
    }

    // Reset transient gesture state on external position update or flip
    LaunchedEffect(state.positionVersion, config.flipped) {
        activeDrag = null
        snapBack = null
        pendingDrop = null
    }

    // Subtle crisp settle animation when board orientation flips
    val flipScale = remember { Animatable(1f) }
    var flipInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(config.flipped, config.animationsEnabled) {
        if (!flipInitialized) {
            flipInitialized = true
            return@LaunchedEffect
        }
        if (config.animationsEnabled) {
            flipScale.snapTo(0.97f)
            flipScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ChessMotion.FLIP_ANIMATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
        } else {
            flipScale.snapTo(1f)
        }
    }

    // Smooth appearance/disappearance of selection highlights and subtle piece lift
    val selectionHighlightAlpha by animateFloatAsState(
        targetValue = if (state.selectedSquare != null) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (config.animationsEnabled) ChessMotion.FAST_MS else 0
        ),
        label = "selectionHighlightAlpha"
    )
    val selectedPieceScale by animateFloatAsState(
        targetValue = if (state.selectedSquare != null && config.animationsEnabled) 1.05f else 1f,
        animationSpec = tween(durationMillis = ChessMotion.FAST_MS),
        label = "selectedPieceScale"
    )

    val latestState by rememberUpdatedState(state)
    val latestConfig by rememberUpdatedState(config)
    val latestOnSquareTap by rememberUpdatedState(onSquareTap)
    val latestOnMoveAttempt by rememberUpdatedState(onMoveAttempt)
    val latestOnInvalidDrop by rememberUpdatedState(onInvalidDrop)
    val latestCanSelectOrDrag by rememberUpdatedState(canSelectOrDrag)

    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = flipScale.value
                scaleY = flipScale.value
            }
            .semantics { contentDescription = boardDescription }
            .testTag("chess_board"),
        shape = RoundedCornerShape(cornerRadius),
        border = if (cornerRadius > 0.dp) BorderStroke(1.dp, ChessTheme.colors.borderSubtle) else null,
        shadowElevation = if (cornerRadius > 0.dp) 4.dp else 0.dp
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val currentBoardPx = with(density) { minOf(maxWidth, maxHeight).toPx() }

            val coordinateFontSp = with(density) {
                (currentBoardPx / 8f * 0.21f).coerceIn(8f, 15f).toSp()
            }
            val coordinateTextStyle = remember(coordinateFontSp) {
                TextStyle(
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = coordinateFontSp
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentBoardPx, config.flipped, config.interactive) {
                        if (!config.interactive || currentBoardPx <= 0f) return@pointerInput

                        val squareSize = currentBoardPx / 8f
                        val dragSlopPx = (viewConfiguration.touchSlop * 0.45f).coerceAtLeast(8f)
                        val dragLiftY = squareSize * 0.32f

                        fun triggerSnapBack(
                            origin: BoardSquare,
                            piece: BoardPiece,
                            releasePos: Offset,
                            flipped: Boolean
                        ) {
                            snapBackCounter += 1L
                            snapBack = SnapBackState(
                                id = snapBackCounter,
                                originSquare = origin,
                                piece = piece,
                                startVisualCenter = Offset(releasePos.x, releasePos.y - dragLiftY),
                                targetCenter = BoardCoordinates.squareCenter(origin, squareSize, flipped)
                            )
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPos = down.position
                            val flipped = latestConfig.flipped
                            val downSquare = BoardCoordinates.squareAtOffset(downPos, currentBoardPx, flipped)
                                ?: return@awaitEachGesture

                            val snapshot = latestState
                            val isTapMoveTarget = snapshot.selectedSquare != null &&
                                snapshot.legalTargets.any { it.square == downSquare }
                            val pieceAtDown = snapshot.pieces[downSquare]
                            val canDrag = !isTapMoveTarget &&
                                pieceAtDown != null &&
                                latestCanSelectOrDrag(downSquare, pieceAtDown)
                            val wasAlreadySelected = snapshot.selectedSquare == downSquare

                            if (canDrag && !wasAlreadySelected) {
                                latestOnSquareTap(downSquare)
                            }

                            var isDragging = false
                            var lastPointerPos = downPos

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break

                                if (event.type == PointerEventType.Exit || !change.pressed) {
                                    lastPointerPos = change.position
                                    if (isDragging) change.consume()
                                    break
                                }

                                lastPointerPos = change.position
                                if (canDrag && pieceAtDown != null) {
                                    if (!isDragging) {
                                        val distance = hypot(
                                            (lastPointerPos.x - downPos.x).toDouble(),
                                            (lastPointerPos.y - downPos.y).toDouble()
                                        ).toFloat()
                                        if (distance >= dragSlopPx) {
                                            isDragging = true
                                            snapBack = null
                                            pendingDrop = null
                                        }
                                    }
                                    if (isDragging) {
                                        change.consume()
                                        activeDrag = ActiveDragState(
                                            originSquare = downSquare,
                                            piece = pieceAtDown,
                                            pointerOffset = lastPointerPos,
                                            hoveredSquare = BoardCoordinates.squareAtOffset(
                                                lastPointerPos,
                                                currentBoardPx,
                                                flipped
                                            )
                                        )
                                    }
                                }
                            }

                            if (isDragging && pieceAtDown != null) {
                                val dropSquare = BoardCoordinates.squareAtOffset(
                                    lastPointerPos,
                                    currentBoardPx,
                                    flipped
                                )

                                if (dropSquare != null && dropSquare != downSquare) {
                                    val preDropPieces = latestState.pieces
                                    val isPremoveDrag = latestState.isPremoveSelection
                                    val accepted = latestOnMoveAttempt(downSquare, dropSquare, true)
                                    if (accepted) {
                                        if (!isPremoveDrag) {
                                            pendingDrop = PendingDropState(
                                                from = downSquare,
                                                to = dropSquare,
                                                piece = pieceAtDown,
                                                sourcePieces = preDropPieces
                                            )
                                        }
                                        activeDrag = null
                                    } else {
                                        activeDrag = null
                                        latestOnInvalidDrop(downSquare, dropSquare)
                                        triggerSnapBack(downSquare, pieceAtDown, lastPointerPos, flipped)
                                    }
                                } else {
                                    activeDrag = null
                                    if (dropSquare == null) {
                                        latestOnInvalidDrop(downSquare, null)
                                    }
                                    triggerSnapBack(downSquare, pieceAtDown, lastPointerPos, flipped)
                                }
                            } else {
                                val releaseSquare = BoardCoordinates.squareAtOffset(
                                    lastPointerPos,
                                    currentBoardPx,
                                    flipped
                                )
                                if (releaseSquare == downSquare) {
                                    if (isTapMoveTarget) {
                                        val from = latestState.selectedSquare
                                        if (from != null) {
                                            latestOnMoveAttempt(from, downSquare, false)
                                        }
                                    } else if (!canDrag || wasAlreadySelected) {
                                        latestOnSquareTap(downSquare)
                                    }
                                }
                            }
                        }
                    }
            ) {
                val squareSize = size.width / 8f
                val flipped = config.flipped
                val drag = activeDrag
                val returning = snapBack
                val dropLock = pendingDrop?.takeIf { state.pieces === it.sourcePieces }
                val moveAnim = activeMoveAnim
                val animProgress = when {
                    moveAnim == null -> 1f
                    startedMoveAnimId != moveAnim.id -> 0f
                    else -> moveAnimProgress.value.coerceIn(0f, 1f)
                }

                // 1. Base 8x8 squares
                drawSquares(squareSize = squareSize, colors = squareColors, flipped = flipped)

                // 2. Last move highlight
                if (config.highlightLastMove) {
                    drawLastMoveHighlight(
                        lastMove = state.lastMove,
                        squareSize = squareSize,
                        colors = squareColors,
                        flipped = flipped
                    )
                }

                // 2.5 Queued premove highlight (Chess.com coral-red from/to squares)
                drawPremoveHighlight(
                    premove = state.queuedPremove,
                    squareSize = squareSize,
                    colors = squareColors,
                    flipped = flipped
                )

                // 3. Selected square highlight
                drawSelectedSquareHighlight(
                    selectedSquare = state.selectedSquare,
                    squareSize = squareSize,
                    colors = squareColors,
                    flipped = flipped,
                    alpha = selectionHighlightAlpha
                )

                // 4. King in check radial crimson halo
                drawCheckHighlight(
                    checkedKingSquare = state.checkedKingSquare,
                    squareSize = squareSize,
                    colors = squareColors,
                    flipped = flipped
                )

                // 5. Drag target hover border
                if (drag != null) {
                    drawDragTargetHighlight(
                        hoveredSquare = drag.hoveredSquare,
                        originSquare = drag.originSquare,
                        squareSize = squareSize,
                        colors = squareColors,
                        flipped = flipped
                    )
                }

                // 6. Coordinates A–H and 1–8
                if (config.showCoordinates) {
                    drawCoordinates(
                        squareSize = squareSize,
                        colors = squareColors,
                        flipped = flipped,
                        textMeasurer = textMeasurer,
                        baseTextStyle = coordinateTextStyle
                    )
                }

                // 7. Legal move / premove target indicators (dots & capture rings)
                if (config.highlightLegalMoves) {
                    drawLegalTargets(
                        legalTargets = state.legalTargets,
                        squareSize = squareSize,
                        colors = squareColors,
                        flipped = flipped,
                        alpha = selectionHighlightAlpha.coerceAtLeast(
                            if (state.legalTargets.isNotEmpty()) 1f else 0f
                        ),
                        isPremove = state.isPremoveSelection
                    )
                }

                // 7.5 Translucent premove destination preview on empty target square
                state.queuedPremove?.let { premove ->
                    if (!state.pieces.containsKey(premove.to)) {
                        val previewPiece = premove.promotion?.let { promo ->
                            BoardPiece(type = promo, color = premove.piece.color)
                        } ?: premove.piece
                        pieceCache[previewPiece]?.let { ghostBitmap ->
                            drawPieceAtCenter(
                                bitmap = ghostBitmap,
                                center = BoardCoordinates.squareCenter(premove.to, squareSize, flipped),
                                squareSize = squareSize,
                                alpha = 0.36f,
                                colorFilter = pieceCache.colorFilter
                            )
                        }
                    }
                }

                // 8. Stationary pieces (strictly single-instance: exclude dragged, pending drop, snap-back, sliding)
                val slidingTarget = if (moveAnim != null && animProgress < 1f) moveAnim.to else null
                for ((square, piece) in state.pieces) {
                    if (square == drag?.originSquare) continue
                    if (square == dropLock?.from || square == dropLock?.to) continue
                    if (square == returning?.originSquare) continue
                    if (square == slidingTarget) continue

                    val bitmap = pieceCache[piece] ?: continue
                    val center = BoardCoordinates.squareCenter(square, squareSize, flipped)
                    val pieceScale = if (square == state.selectedSquare) selectedPieceScale else 1f
                    drawPieceAtCenter(
                        bitmap = bitmap,
                        center = center,
                        squareSize = squareSize,
                        scale = pieceScale,
                        colorFilter = pieceCache.colorFilter
                    )
                }

                // 9. Optimistic drag landing bridge (prevents 1-frame snap-back before StateFlow commits)
                if (dropLock != null) {
                    val bitmap = pieceCache[dropLock.piece]
                    if (bitmap != null) {
                        drawPieceAtCenter(
                            bitmap = bitmap,
                            center = BoardCoordinates.squareCenter(dropLock.to, squareSize, flipped),
                            squareSize = squareSize,
                            colorFilter = pieceCache.colorFilter
                        )
                    }
                }

                // 10. Active move slide animation + smooth capture fade-out
                if (moveAnim != null && animProgress < 1f) {
                    val captured = moveAnim.capturedPiece
                    if (captured != null) {
                        pieceCache[captured]?.let { capturedBitmap ->
                            drawPieceAtCenter(
                                bitmap = capturedBitmap,
                                center = BoardCoordinates.squareCenter(moveAnim.capturedSquare, squareSize, flipped),
                                squareSize = squareSize,
                                alpha = (1f - animProgress).coerceIn(0f, 1f),
                                colorFilter = pieceCache.colorFilter
                            )
                        }
                    }

                    pieceCache[moveAnim.piece]?.let { movingBitmap ->
                        val startCenter = BoardCoordinates.squareCenter(moveAnim.from, squareSize, flipped)
                        val endCenter = BoardCoordinates.squareCenter(moveAnim.to, squareSize, flipped)
                        drawPieceAtCenter(
                            bitmap = movingBitmap,
                            center = lerp(startCenter, endCenter, animProgress),
                            squareSize = squareSize,
                            scale = 1.04f,
                            drawShadow = true,
                            colorFilter = pieceCache.colorFilter
                        )
                    }
                }

                // 11. Snap-back animation (starts strictly at t=0f on frame 1)
                if (returning != null) {
                    pieceCache[returning.piece]?.let { bitmap ->
                        val t = if (startedSnapBackId != returning.id) {
                            0f
                        } else {
                            snapBackProgress.value.coerceIn(0f, 1f)
                        }
                        drawPieceAtCenter(
                            bitmap = bitmap,
                            center = lerp(returning.startVisualCenter, returning.targetCenter, t),
                            squareSize = squareSize,
                            scale = 1.22f - 0.22f * t,
                            drawShadow = t < 0.85f,
                            colorFilter = pieceCache.colorFilter
                        )
                    }
                }

                // 12. Active dragged piece (topmost, lifted above finger)
                if (drag != null) {
                    pieceCache[drag.piece]?.let { bitmap ->
                        drawPieceAtCenter(
                            bitmap = bitmap,
                            center = Offset(
                                x = drag.pointerOffset.x,
                                y = drag.pointerOffset.y - squareSize * 0.32f
                            ),
                            squareSize = squareSize,
                            scale = 1.22f,
                            drawShadow = true,
                            colorFilter = pieceCache.colorFilter
                        )
                    }
                }
            }
        }
    }
}

