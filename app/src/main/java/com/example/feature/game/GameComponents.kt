package com.example.feature.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.chess.ChessClock
import com.example.core.chess.GameOutcome
import com.example.core.chess.GameTerminationReason
import com.example.core.ui.board.BoardPremove
import com.example.core.ui.board.PieceType
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.GameResult
import com.example.domain.model.PieceColor

@Composable
fun PlayerInfoClockBar(
    name: String,
    pieceColor: PieceColor,
    remainingMs: Long,
    isUnlimited: Boolean,
    isActiveTurn: Boolean,
    isClockRunning: Boolean,
    capturedPieces: List<PieceType>,
    materialAdvantage: Int,
    connectionStatus: ConnectionStatusUi,
    clockTestTag: String,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val isLowTime = !isUnlimited && remainingMs < ChessClock.LOW_TIME_THRESHOLD_MS && remainingMs > 0L
    val isFlagged = !isUnlimited && remainingMs <= 0L
    val formattedTime = ChessClock.formatClockTime(remainingMs, isUnlimited)

    val clockContainerColor = when {
        isFlagged -> colors.accentCoral
        isActiveTurn && isLowTime -> colors.accentCoralContainer
        isActiveTurn -> colors.accentGreenContainer
        else -> colors.surfaceCard
    }
    val clockBorderColor = when {
        isFlagged || (isActiveTurn && isLowTime) -> colors.accentCoral
        isActiveTurn -> colors.accentGreen
        else -> colors.borderSubtle
    }
    val clockTextColor = when {
        isFlagged -> colors.textPrimary
        isActiveTurn && isLowTime -> colors.accentCoral
        isActiveTurn -> colors.accentGreen
        else -> colors.textSecondary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.backgroundSecondary)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Avatar with piece color badge
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (pieceColor == PieceColor.WHITE) colors.squareLight else colors.surfaceElevated
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.trim().take(1).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (pieceColor == PieceColor.WHITE) colors.surfaceElevated else colors.textPrimary
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Active turn dot
                    if (isActiveTurn) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(colors.accentGreen)
                        )
                    }
                    // Subtle connection indicator when non-local or degraded
                    if (connectionStatus == ConnectionStatusUi.RECONNECTING ||
                        connectionStatus == ConnectionStatusUi.DISCONNECTED
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WifiOff,
                            contentDescription = stringResource(R.string.game_connection_disconnected),
                            tint = colors.accentCoral,
                            modifier = Modifier.size(14.dp)
                        )
                    } else if (connectionStatus == ConnectionStatusUi.CONNECTED) {
                        Icon(
                            imageVector = Icons.Rounded.Wifi,
                            contentDescription = stringResource(R.string.game_connection_connected),
                            tint = colors.accentGreen,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Piece side label + material advantage
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (pieceColor == PieceColor.WHITE) {
                            stringResource(R.string.side_white)
                        } else {
                            stringResource(R.string.side_black)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    if (capturedPieces.isNotEmpty()) {
                        Text(
                            text = capturedPieces.take(8).joinToString("") { pieceGlyph(it) },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (materialAdvantage > 0) {
                        Text(
                            text = "+$materialAdvantage",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = colors.accentGreen
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Digital Monospace Chess Clock
        Surface(
            modifier = Modifier.testTag(clockTestTag),
            shape = RoundedCornerShape(8.dp),
            color = clockContainerColor,
            border = BorderStroke(if (isActiveTurn && isClockRunning) 1.5.dp else 1.dp, clockBorderColor)
        ) {
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = clockTextColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun CompactMoveHistoryStrip(
    movePairs: List<MoveHistoryPair>,
    activeDisplayPly: Int,
    totalPlies: Int,
    isViewingHistory: Boolean,
    queuedPremove: BoardPremove?,
    onSelectPly: (Int) -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onReturnLive: () -> Unit,
    onCancelPremove: () -> Unit,
    onOpenFullHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val listState = rememberLazyListState()

    // Keep the currently displayed ply scrolled into view without shifting any layout geometry
    LaunchedEffect(activeDisplayPly, movePairs.size) {
        if (movePairs.isEmpty()) return@LaunchedEffect
        val targetIndex = if (activeDisplayPly <= 0) {
            0
        } else {
            ((activeDisplayPly - 1) / 2).coerceIn(0, movePairs.lastIndex)
        }
        listState.animateScrollToItem(targetIndex)
    }

    val containerColor = when {
        isViewingHistory -> colors.accentBlueContainer.copy(alpha = 0.45f)
        queuedPremove != null -> colors.accentCoralContainer.copy(alpha = 0.45f)
        else -> colors.backgroundSecondary
    }
    val borderColor = when {
        isViewingHistory -> colors.accentBlue.copy(alpha = 0.7f)
        queuedPremove != null -> colors.accentCoral.copy(alpha = 0.7f)
        else -> colors.borderSubtle
    }

    // Strictly fixed height (48.dp) so entering/exiting history or queueing a premove NEVER shifts the board
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("compact_move_history_strip"),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Inline fixed-height status pill (history ply badge or cancelable premove chip)
            if (isViewingHistory) {
                Surface(
                    onClick = onReturnLive,
                    shape = RoundedCornerShape(7.dp),
                    color = colors.accentBlueContainer,
                    border = BorderStroke(1.dp, colors.accentBlue.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .padding(start = 4.dp, end = 4.dp)
                        .testTag("history_review_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = colors.accentBlue,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "$activeDisplayPly/$totalPlies",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = colors.accentBlue
                        )
                    }
                }
            } else if (queuedPremove != null) {
                Surface(
                    onClick = onCancelPremove,
                    shape = RoundedCornerShape(7.dp),
                    color = colors.accentCoralContainer,
                    border = BorderStroke(1.dp, colors.accentCoral.copy(alpha = 0.75f)),
                    modifier = Modifier
                        .padding(start = 4.dp, end = 4.dp)
                        .testTag("cancel_premove_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.game_premove_badge,
                                queuedPremove.from.algebraic,
                                queuedPremove.to.algebraic
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = colors.accentCoral
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_cancel_premove),
                            tint = colors.accentCoral,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            if (movePairs.isEmpty()) {
                Text(
                    text = stringResource(R.string.game_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
            } else {
                LazyRow(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(movePairs, key = { it.moveNumber }) { pair ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${pair.moveNumber}.",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                color = colors.textMuted,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            pair.whiteMove?.let { white ->
                                MoveChip(
                                    san = white.san,
                                    selected = activeDisplayPly == white.plyIndex,
                                    testTag = "history_ply_${white.plyIndex}",
                                    onClick = { onSelectPly(white.plyIndex) }
                                )
                            }
                            pair.blackMove?.let { black ->
                                MoveChip(
                                    san = black.san,
                                    selected = activeDisplayPly == black.plyIndex,
                                    testTag = "history_ply_${black.plyIndex}",
                                    onClick = { onSelectPly(black.plyIndex) }
                                )
                            }
                        }
                    }
                }
            }

            // Direct 1-tap Previous / Next move navigation without opening any menu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val canStepBack = activeDisplayPly > 0
                val canStepForward = activeDisplayPly < totalPlies

                IconButton(
                    onClick = onStepBack,
                    enabled = canStepBack,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("compact_history_prev")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.cd_history_prev),
                        tint = if (canStepBack) colors.textPrimary else colors.textMuted.copy(alpha = 0.32f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = onStepForward,
                    enabled = canStepForward,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("compact_history_next")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.cd_history_next),
                        tint = if (canStepForward) colors.textPrimary else colors.textMuted.copy(alpha = 0.32f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (isViewingHistory) {
                    IconButton(
                        onClick = onReturnLive,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("return_to_live_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LastPage,
                            contentDescription = stringResource(R.string.game_history_return_live),
                            tint = colors.accentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onOpenFullHistory,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("open_full_history_icon")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.FormatListBulleted,
                            contentDescription = stringResource(R.string.game_history_title),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveChip(
    san: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val colors = ChessTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.accentGreenContainer else colors.surfaceCard.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(testTag)
    ) {
        Text(
            text = san,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = MonoFontFamily,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (selected) colors.accentGreen else colors.textPrimary
        )
    }
}

@Composable
fun FullMoveHistoryPanel(
    movePairs: List<MoveHistoryPair>,
    activeDisplayPly: Int,
    totalPlies: Int,
    isViewingHistory: Boolean,
    onSelectPly: (Int) -> Unit,
    onJumpStart: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onReturnLive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val listState = rememberLazyListState()

    LaunchedEffect(movePairs.size, isViewingHistory) {
        if (!isViewingHistory && movePairs.isNotEmpty()) {
            listState.animateScrollToItem(movePairs.lastIndex)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.backgroundSecondary,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.game_history_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HistoryNavButton(
                        icon = Icons.Rounded.FirstPage,
                        description = stringResource(R.string.cd_history_first),
                        enabled = activeDisplayPly > 0,
                        testTag = "history_nav_first",
                        onClick = onJumpStart
                    )
                    HistoryNavButton(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        description = stringResource(R.string.cd_history_prev),
                        enabled = activeDisplayPly > 0,
                        testTag = "history_nav_prev",
                        onClick = onStepBack
                    )
                    HistoryNavButton(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        description = stringResource(R.string.cd_history_next),
                        enabled = activeDisplayPly < totalPlies,
                        testTag = "history_nav_next",
                        onClick = onStepForward
                    )
                    HistoryNavButton(
                        icon = Icons.Rounded.LastPage,
                        description = stringResource(R.string.cd_history_last),
                        enabled = activeDisplayPly < totalPlies,
                        testTag = "history_nav_last",
                        onClick = onReturnLive
                    )
                }
            }

            HorizontalDivider(color = colors.borderSubtle)

            if (movePairs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.game_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 280.dp)
                ) {
                    items(movePairs, key = { it.moveNumber }) { pair ->
                        val rowBg = if (pair.moveNumber % 2 == 0) {
                            colors.surfaceCard.copy(alpha = 0.45f)
                        } else {
                            colors.backgroundSecondary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${pair.moveNumber}.",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                color = colors.textMuted,
                                modifier = Modifier.width(34.dp)
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                pair.whiteMove?.let { white ->
                                    MoveChip(
                                        san = white.san,
                                        selected = activeDisplayPly == white.plyIndex,
                                        testTag = "sheet_ply_${white.plyIndex}",
                                        onClick = { onSelectPly(white.plyIndex) }
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                pair.blackMove?.let { black ->
                                    MoveChip(
                                        san = black.san,
                                        selected = activeDisplayPly == black.plyIndex,
                                        testTag = "sheet_ply_${black.plyIndex}",
                                        onClick = { onSelectPly(black.plyIndex) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryNavButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val colors = ChessTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(34.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) colors.textPrimary else colors.textMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun HistoryReviewBanner(
    viewedPly: Int,
    totalPlies: Int,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onReturnLive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("history_review_banner"),
        shape = RoundedCornerShape(10.dp),
        color = colors.accentBlueContainer,
        border = BorderStroke(1.dp, colors.accentBlue.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = colors.accentBlue,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.game_history_viewing_ply, viewedPly, totalPlies),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HistoryNavButton(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    description = stringResource(R.string.cd_history_prev),
                    enabled = viewedPly > 0,
                    testTag = "review_banner_prev",
                    onClick = onStepBack
                )
                HistoryNavButton(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    description = stringResource(R.string.cd_history_next),
                    enabled = viewedPly < totalPlies,
                    testTag = "review_banner_next",
                    onClick = onStepForward
                )
                TextButton(
                    onClick = onReturnLive,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.testTag("return_to_live_button")
                ) {
                    Text(
                        text = stringResource(R.string.game_history_return_live),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentBlue
                    )
                }
            }
        }
    }
}

@Composable
fun DrawOfferBanner(
    drawOfferState: DrawOfferState,
    offeredBy: PieceColor?,
    onAcceptDraw: () -> Unit,
    onDeclineDraw: () -> Unit,
    modifier: Modifier = Modifier,
    isLanMatch: Boolean = false
) {
    if (drawOfferState == DrawOfferState.NONE || drawOfferState == DrawOfferState.ACCEPTED) return
    val colors = ChessTheme.colors

    val message = when (drawOfferState) {
        DrawOfferState.SENT -> if (isLanMatch) {
            stringResource(R.string.game_draw_offer_sent_waiting)
        } else if (offeredBy == PieceColor.BLACK) {
            stringResource(R.string.game_draw_offer_sent_black)
        } else {
            stringResource(R.string.game_draw_offer_sent_white)
        }
        DrawOfferState.RECEIVED -> stringResource(R.string.game_draw_offer_received)
        DrawOfferState.DECLINED -> stringResource(R.string.game_draw_offer_declined)
        else -> ""
    }

    val isActionable = if (isLanMatch) {
        drawOfferState == DrawOfferState.RECEIVED
    } else {
        drawOfferState == DrawOfferState.SENT || drawOfferState == DrawOfferState.RECEIVED
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("draw_offer_banner"),
        shape = RoundedCornerShape(10.dp),
        color = if (isActionable || drawOfferState == DrawOfferState.SENT) colors.accentAmberContainer else colors.surfaceCard,
        border = BorderStroke(
            1.dp,
            if (isActionable || drawOfferState == DrawOfferState.SENT) colors.accentAmber.copy(alpha = 0.6f) else colors.borderSubtle
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            if (isActionable) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = onDeclineDraw,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("decline_draw_button")
                    ) {
                        Text(
                            text = stringResource(R.string.game_action_decline),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accentCoral
                        )
                    }
                    Button(
                        onClick = onAcceptDraw,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("accept_draw_button")
                    ) {
                        Text(
                            text = stringResource(R.string.game_action_accept),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GameControlBar(
    flipped: Boolean,
    isGameOver: Boolean,
    drawOfferPending: Boolean,
    onOfferDraw: () -> Unit,
    onResign: () -> Unit,
    onFlip: () -> Unit,
    onOpenMoves: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ControlActionButton(
            label = stringResource(R.string.game_action_draw),
            icon = Icons.Rounded.Handshake,
            highlighted = drawOfferPending,
            enabled = !isGameOver && !drawOfferPending,
            testTag = "game_draw_button",
            onClick = onOfferDraw,
            modifier = Modifier.weight(1f)
        )
        ControlActionButton(
            label = stringResource(R.string.game_action_resign),
            icon = Icons.Rounded.Flag,
            highlighted = false,
            enabled = !isGameOver,
            testTag = "game_resign_button",
            onClick = onResign,
            modifier = Modifier.weight(1f)
        )
        ControlActionButton(
            label = stringResource(R.string.game_action_flip),
            icon = Icons.Rounded.SwapVert,
            highlighted = flipped,
            enabled = true,
            testTag = "game_flip_button",
            onClick = onFlip,
            modifier = Modifier.weight(1f)
        )
        ControlActionButton(
            label = stringResource(R.string.game_action_moves),
            icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
            highlighted = false,
            enabled = true,
            testTag = "game_moves_button",
            onClick = onOpenMoves,
            modifier = Modifier.weight(1f)
        )
        ControlActionButton(
            label = stringResource(R.string.game_action_menu),
            icon = Icons.Rounded.MoreHoriz,
            highlighted = false,
            enabled = true,
            testTag = "game_menu_button",
            onClick = onOpenMenu,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ControlActionButton(
    label: String,
    icon: ImageVector,
    highlighted: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val tint = when {
        !enabled -> colors.textSecondary.copy(alpha = 0.38f)
        highlighted -> colors.accentGreen
        else -> colors.textPrimary
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.testTag(testTag),
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) colors.accentGreenContainer else colors.surfaceCard,
        border = BorderStroke(1.dp, if (highlighted) colors.accentGreen else colors.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ResignConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = ChessTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("resign_confirm_dialog"),
        containerColor = colors.surfaceElevated,
        title = {
            Text(
                text = stringResource(R.string.game_resign_confirm_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
        },
        text = {
            Text(
                text = stringResource(R.string.game_resign_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentCoral),
                modifier = Modifier.testTag("confirm_resign_button")
            ) {
                Text(stringResource(R.string.game_action_resign))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_resign_button")
            ) {
                Text(stringResource(R.string.game_action_cancel))
            }
        }
    )
}

@Composable
fun GameOverDialog(
    outcome: GameOutcome,
    plyCount: Int,
    onNewGame: () -> Unit,
    onViewBoard: () -> Unit,
    isLanMatch: Boolean = false
) {
    val colors = ChessTheme.colors
    val scoreText = when (outcome.result) {
        GameResult.WHITE_WIN -> "1 — 0"
        GameResult.BLACK_WIN -> "0 — 1"
        GameResult.DRAW -> "½ — ½"
        else -> "—"
    }

    AlertDialog(
        onDismissRequest = onViewBoard,
        modifier = Modifier.testTag("game_over_dialog"),
        containerColor = colors.surfaceElevated,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = scoreText,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = colors.accentGreen
                )
                Text(
                    text = stringResource(R.string.game_over_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = resolveGameOutcomeLabel(outcome),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = stringResource(R.string.game_over_moves_summary, plyCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onNewGame,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                modifier = Modifier.testTag("game_over_new_game_button")
            ) {
                Text(
                    if (isLanMatch) {
                        stringResource(R.string.game_action_leave_room)
                    } else {
                        stringResource(R.string.game_action_new_game)
                    }
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onViewBoard,
                modifier = Modifier.testTag("game_over_view_board_button")
            ) {
                Text(stringResource(R.string.game_action_view_board))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameMenuSheet(
    canUndo: Boolean,
    onUndo: () -> Unit,
    onShowPgn: () -> Unit,
    onNewGame: () -> Unit,
    onDismiss: () -> Unit,
    isLanMatch: Boolean = false
) {
    val colors = ChessTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.game_menu_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (!isLanMatch) {
                MenuSheetItem(
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    label = stringResource(R.string.game_action_undo),
                    enabled = canUndo,
                    testTag = "menu_undo_item",
                    onClick = {
                        onUndo()
                        onDismiss()
                    }
                )
            }
            MenuSheetItem(
                icon = Icons.Rounded.Description,
                label = stringResource(R.string.game_action_export_pgn),
                enabled = true,
                testTag = "menu_pgn_item",
                onClick = {
                    onDismiss()
                    onShowPgn()
                }
            )
            MenuSheetItem(
                icon = if (isLanMatch) Icons.Rounded.Close else Icons.Rounded.Refresh,
                label = if (isLanMatch) {
                    stringResource(R.string.game_action_leave_room)
                } else {
                    stringResource(R.string.game_action_new_game)
                },
                enabled = true,
                testTag = "menu_new_game_item",
                onClick = {
                    onNewGame()
                    onDismiss()
                }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MenuSheetItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceCard
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) colors.textPrimary else colors.textMuted
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.textPrimary else colors.textMuted
            )
        }
    }
}

@Composable
fun PgnPreviewDialog(
    pgn: String,
    onDismiss: () -> Unit
) {
    val colors = ChessTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("pgn_preview_dialog"),
        containerColor = colors.surfaceElevated,
        title = {
            Text(
                text = stringResource(R.string.game_pgn_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
        },
        text = {
            Text(
                text = pgn,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                color = colors.textSecondary,
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.game_action_close))
            }
        }
    )
}

@Composable
fun resolveGameOutcomeLabel(outcome: GameOutcome): String = when (outcome.reason) {
    GameTerminationReason.CHECKMATE -> if (outcome.result == GameResult.WHITE_WIN) {
        stringResource(R.string.game_status_checkmate_white)
    } else {
        stringResource(R.string.game_status_checkmate_black)
    }
    GameTerminationReason.STALEMATE -> stringResource(R.string.game_status_stalemate)
    GameTerminationReason.INSUFFICIENT_MATERIAL -> stringResource(R.string.game_status_insufficient_material)
    GameTerminationReason.FIFTY_MOVE_RULE -> stringResource(R.string.game_status_fifty_move)
    GameTerminationReason.THREEFOLD_REPETITION -> stringResource(R.string.game_status_repetition)
    GameTerminationReason.DRAW_BY_AGREEMENT -> stringResource(R.string.game_status_draw_agreement)
    GameTerminationReason.RESIGNATION -> if (outcome.result == GameResult.WHITE_WIN) {
        stringResource(R.string.game_status_resignation_white)
    } else {
        stringResource(R.string.game_status_resignation_black)
    }
    GameTerminationReason.TIMEOUT -> if (outcome.result == GameResult.WHITE_WIN) {
        stringResource(R.string.game_status_timeout_white)
    } else {
        stringResource(R.string.game_status_timeout_black)
    }
    GameTerminationReason.TIMEOUT_VS_INSUFFICIENT_MATERIAL ->
        stringResource(R.string.game_status_timeout_draw)
    GameTerminationReason.ABORTED -> stringResource(R.string.game_status_aborted)
}

private fun pieceGlyph(type: PieceType): String = when (type) {
    PieceType.QUEEN -> "♛"
    PieceType.ROOK -> "♜"
    PieceType.BISHOP -> "♝"
    PieceType.KNIGHT -> "♞"
    PieceType.PAWN -> "♟"
    PieceType.KING -> "♚"
}
