package com.example.feature.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.analysis.EngineState
import com.example.core.analysis.MoveNode
import com.example.core.analysis.VariationTree
import com.example.core.analysis.styleMeta
import com.example.core.ui.components.ChessPrimaryButton
import com.example.core.ui.components.ChessSecondaryButton
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.MoveClassification
import com.example.domain.model.PieceColor
import kotlin.math.roundToInt

private data class MovePairRow(
    val moveNumber: Int,
    val white: MoveNode?,
    val black: MoveNode?,
    val variationsAfterWhite: List<MoveNode>,
    val variationsAfterBlack: List<MoveNode>
)

private fun MoveNode.containsNodeId(targetId: String): Boolean {
    if (id == targetId) return true
    return children.any { it.containsNodeId(targetId) }
}

@Composable
fun MoveListPanel(
    uiState: AnalyzerUiState,
    onSelectNode: (String) -> Unit,
    onDeleteVariation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val listState = rememberLazyListState()
    val pairs = remember(uiState.rootNode) {
        val mainline = VariationTree.getMainlineNodes(uiState.rootNode)
        val result = mutableListOf<MovePairRow>()
        for (i in 1 until mainline.size) {
            val node = mainline[i]
            val parent = mainline[i - 1]
            val siblingVariations = parent.children.drop(1)
            if (node.color == PieceColor.WHITE) {
                result.add(
                    MovePairRow(
                        moveNumber = node.moveNumber,
                        white = node,
                        black = null,
                        variationsAfterWhite = siblingVariations,
                        variationsAfterBlack = emptyList()
                    )
                )
            } else {
                val last = result.lastOrNull()
                if (last != null && last.moveNumber == node.moveNumber && last.black == null) {
                    result[result.lastIndex] = last.copy(
                        black = node,
                        variationsAfterBlack = siblingVariations
                    )
                } else {
                    result.add(
                        MovePairRow(
                            moveNumber = node.moveNumber,
                            white = null,
                            black = node,
                            variationsAfterWhite = emptyList(),
                            variationsAfterBlack = siblingVariations
                        )
                    )
                }
            }
        }
        result
    }

    LaunchedEffect(uiState.currentNodeId, pairs) {
        if (pairs.isEmpty()) return@LaunchedEffect
        val targetId = uiState.currentNodeId
        if (targetId == "root") {
            listState.animateScrollToItem(0)
            return@LaunchedEffect
        }
        val targetIdx = pairs.indexOfFirst { pair ->
            pair.white?.id == targetId ||
                pair.black?.id == targetId ||
                pair.variationsAfterWhite.any { it.containsNodeId(targetId) } ||
                pair.variationsAfterBlack.any { it.containsNodeId(targetId) }
        }
        if (targetIdx >= 0) {
            listState.animateScrollToItem(targetIdx)
        }
    }

    if (pairs.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Список ходов пуст.\nСделайте ход на доске или импортируйте партию PGN / Chess.com.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items(items = pairs, key = { it.moveNumber }) { pair ->
            val whiteNode = pair.white
            val blackNode = pair.black
            val whiteLeavesBook = whiteNode != null && whiteNode.ply == uiState.openingSummary.firstOutOfBookPly
            val blackLeavesBook = blackNode != null && blackNode.ply == uiState.openingSummary.firstOutOfBookPly

            if (whiteLeavesBook) {
                OutOfTheoryBanner("${pair.moveNumber}. ${whiteNode.san}")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${pair.moveNumber}.",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(30.dp)
                )
                MoveCell(
                    node = pair.white,
                    isSelected = pair.white?.id == uiState.currentNodeId,
                    isLastTheory = pair.white?.ply == uiState.openingSummary.lastTheoreticalPly &&
                        uiState.openingSummary.lastTheoreticalPly > 0,
                    onClick = { pair.white?.let { onSelectNode(it.id) } },
                    modifier = Modifier.weight(1f)
                )
                MoveCell(
                    node = pair.black,
                    isSelected = pair.black?.id == uiState.currentNodeId,
                    isLastTheory = pair.black?.ply == uiState.openingSummary.lastTheoreticalPly &&
                        uiState.openingSummary.lastTheoreticalPly > 0,
                    onClick = { pair.black?.let { onSelectNode(it.id) } },
                    modifier = Modifier.weight(1f)
                )
            }

            if (blackLeavesBook) {
                OutOfTheoryBanner("${pair.moveNumber}... ${blackNode.san}")
            }

            pair.variationsAfterWhite.forEach { v ->
                VariationBranchItem(
                    startNode = v,
                    currentNodeId = uiState.currentNodeId,
                    depthLevel = 0,
                    onSelectNode = onSelectNode,
                    onDeleteVariation = onDeleteVariation
                )
            }
            pair.variationsAfterBlack.forEach { v ->
                VariationBranchItem(
                    startNode = v,
                    currentNodeId = uiState.currentNodeId,
                    depthLevel = 0,
                    onSelectNode = onSelectNode,
                    onDeleteVariation = onDeleteVariation
                )
            }
        }
    }
}

@Composable
private fun OutOfTheoryBanner(moveLabel: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color(0x20A88865)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Выход из дебютной теории",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFA88865)
            )
            Text(
                text = moveLabel,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = Color(0xFFA88865),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MoveCell(
    node: MoveNode?,
    isSelected: Boolean,
    isLastTheory: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    if (node == null) {
        Box(
            modifier = modifier
                .height(30.dp)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text("…", color = colors.textMuted, fontSize = 12.sp)
        }
        return
    }

    val analysis = node.analysis
    Surface(
        onClick = onClick,
        modifier = modifier.height(30.dp),
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) colors.accentGreenContainer else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, colors.accentGreen) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (analysis != null) {
                    Image(
                        painter = painterResource(badgeDrawableFor(analysis.category)),
                        contentDescription = analysis.category.title,
                        modifier = Modifier.size(15.dp)
                    )
                } else if (isLastTheory) {
                    Icon(
                        imageVector = Icons.Rounded.AutoStories,
                        contentDescription = null,
                        tint = Color(0xFFA88865),
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = node.san,
                    fontFamily = MonoFontFamily,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (analysis != null) {
                Text(
                    text = formatEvalScore(analysis.evalAfterCp, analysis.mateAfter, 2, node.fen),
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(analysis.category.styleMeta().colorHex),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariationBranchItem(
    startNode: MoveNode,
    currentNodeId: String,
    depthLevel: Int,
    onSelectNode: (String) -> Unit,
    onDeleteVariation: (String) -> Unit
) {
    val colors = ChessTheme.colors
    val branchNodes = mutableListOf<MoveNode>()
    val subVariations = mutableListOf<MoveNode>()

    var curr: MoveNode? = startNode
    while (curr != null) {
        branchNodes.add(curr)
        if (curr.children.size > 1) {
            subVariations.addAll(curr.children.drop(1))
        }
        curr = curr.children.firstOrNull()
    }

    val indent = (14 + depthLevel * 10).coerceAtMost(36).dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent, top = 2.dp, bottom = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = colors.backgroundSecondary,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountTree,
                    contentDescription = null,
                    tint = colors.accentBlue,
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.CenterVertically)
                )
                branchNodes.forEachIndexed { idx, vn ->
                    val selected = vn.id == currentNodeId
                    val showNum = idx == 0 || vn.color == PieceColor.WHITE
                    val prefix = if (showNum) {
                        "${vn.moveNumber}${if (vn.color == PieceColor.WHITE) "." else "..."} "
                    } else ""
                    Surface(
                        onClick = { onSelectNode(vn.id) },
                        shape = RoundedCornerShape(5.dp),
                        color = if (selected) colors.accentBlue else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            vn.analysis?.let { va ->
                                Image(
                                    painter = painterResource(badgeDrawableFor(va.category)),
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                text = "$prefix${vn.san}",
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else colors.textPrimary
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = { onDeleteVariation(startNode.id) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Удалить вариант",
                    tint = colors.accentCoral,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    subVariations.forEach { sv ->
        VariationBranchItem(
            startNode = sv,
            currentNodeId = currentNodeId,
            depthLevel = depthLevel + 1,
            onSelectNode = onSelectNode,
            onDeleteVariation = onDeleteVariation
        )
    }
}

@Composable
fun EngineLinesPanel(
    uiState: AnalyzerUiState,
    engineState: EngineState,
    onMakeSanMove: (String) -> Unit,
    onDepthChanged: (Int) -> Unit,
    onMultiPvChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val currentNode = VariationTree.findNodeById(uiState.rootNode, uiState.currentNodeId) ?: uiState.rootNode
    val moveAnalysis = currentNode.analysis
    val primaryLine = engineState.lines.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (moveAnalysis != null) {
            val meta = moveAnalysis.category.styleMeta()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(meta.colorHex).copy(alpha = 0.14f),
                border = BorderStroke(1.dp, Color(meta.colorHex).copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Image(
                                painter = painterResource(badgeDrawableFor(moveAnalysis.category)),
                                contentDescription = meta.labelRu,
                                modifier = Modifier.size(26.dp)
                            )
                            Column {
                                Text(
                                    text = "${moveAnalysis.moveNumber}${if (moveAnalysis.player == PieceColor.WHITE) "." else "..."} ${moveAnalysis.san} — ${meta.labelRu}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "Точность: ${moveAnalysis.moveAccuracy}% · Потеря CPL: ${moveAnalysis.cpl}",
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatEvalScore(moveAnalysis.evalAfterCp, moveAnalysis.mateAfter, 2, moveAnalysis.fenAfter),
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "было ${formatEvalScore(moveAnalysis.evalBeforeCp, moveAnalysis.mateBefore, 2, moveAnalysis.fenBefore)}",
                                fontFamily = MonoFontFamily,
                                fontSize = 10.sp,
                                color = colors.textSecondary
                            )
                        }
                    }

                    if (moveAnalysis.category != MoveClassification.BEST &&
                        moveAnalysis.category != MoveClassification.BOOK &&
                        moveAnalysis.bestMoveSan.isNotBlank()
                    ) {
                        HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Лучший ход Stockfish:",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                            Text(
                                text = "${moveAnalysis.bestMoveSan} (${moveAnalysis.bestLineSan.take(4).joinToString(" ")})",
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accentGreen
                            )
                        }
                    }
                }
            }
        }

        // Stockfish Controls Card
        ChessSurfaceCard(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = if (engineState.isReady) colors.accentGreen else colors.accentAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = engineState.engineName,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = buildString {
                        append("d${engineState.currentDepth}/${uiState.preferences.engineConfig.depth}")
                        if (engineState.nps > 0) {
                            append(" · ${engineState.nps / 1000} kN/s")
                        }
                    },
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            engineState.lastError?.let { err ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accentCoral
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Глубина: ${uiState.preferences.engineConfig.depth}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    Slider(
                        value = uiState.preferences.engineConfig.depth.toFloat(),
                        onValueChange = { onDepthChanged(it.roundToInt()) },
                        valueRange = 8f..24f,
                        modifier = Modifier.height(26.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accentGreen,
                            activeTrackColor = colors.accentGreen
                        )
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Линии MultiPV: ${uiState.preferences.engineConfig.multiPv}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    Slider(
                        value = uiState.preferences.engineConfig.multiPv.toFloat(),
                        onValueChange = { onMultiPvChanged(it.roundToInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                        modifier = Modifier.height(26.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accentBlue,
                            activeTrackColor = colors.accentBlue
                        )
                    )
                }
            }
        }

        // MultiPV Lines
        Text(
            text = "Варианты движка (нажмите для хода)",
            style = MaterialTheme.typography.titleSmall,
            color = colors.textPrimary
        )

        if (engineState.lines.isEmpty()) {
            ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
                Text(
                    text = if (engineState.isAnalyzing) "Расчёт вариантов Stockfish…" else "Ожидание позиции или Stockfish",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                engineState.lines.forEach { line ->
                    val scoreText = formatEvalScore(line.cp, line.mate, 2, currentNode.fen)
                    val isPrimary = line.multipv == 1
                    val firstSan = line.pvSan.firstOrNull()
                    val firstMoveToken = firstSan ?: line.pv.firstOrNull()

                    Surface(
                        onClick = { firstMoveToken?.let(onMakeSanMove) },
                        shape = RoundedCornerShape(8.dp),
                        color = colors.backgroundSecondary,
                        border = BorderStroke(
                            1.dp,
                            if (isPrimary) Color(0x66F39C36) else colors.borderSubtle
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isPrimary) Color(0xFFF39C36) else colors.accentBlueContainer
                            ) {
                                Text(
                                    text = scoreText,
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = if (isPrimary) Color.White else colors.accentBlue,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                val movesText = (line.pvSan.ifEmpty { line.pv }).joinToString(" ")
                                Text(
                                    text = movesText,
                                    fontFamily = MonoFontFamily,
                                    fontSize = 12.sp,
                                    color = colors.textPrimary,
                                    textDecoration = if (isPrimary) TextDecoration.None else TextDecoration.None
                                )
                                Text(
                                    text = "Линия #${line.multipv} · d${line.depth}${if (firstSan != null) " · ход $firstSan" else ""}",
                                    fontFamily = MonoFontFamily,
                                    fontSize = 10.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Opening Theory + Lichess Masters DB
        ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Дебютная теория и статистика",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = "ECO ${uiState.openingSummary.eco}",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = Color(0xFFA88865)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${uiState.openingSummary.name} (${uiState.openingSummary.variation})",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary
            )
            if (uiState.openingSummary.mainBookContinuationSan.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Книга: ${uiState.openingSummary.mainBookContinuationSan.joinToString(" → ")}",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = Color(0xFFA88865)
                )
            }
            if (primaryLine?.pvSan?.isNotEmpty() == true) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Stockfish: ${primaryLine.pvSan.first()} (${formatEvalScore(primaryLine.cp, primaryLine.mate, 2, currentNode.fen)})",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = Color(0xFFF39C36)
                )
            }

            val lichessMoves = uiState.lichessStats?.moves?.take(4).orEmpty()
            if (lichessMoves.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Популярные ходы мастеров (Lichess Masters DB)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                lichessMoves.forEach { lm ->
                    val total = (lm.white + lm.draws + lm.black).coerceAtLeast(1L)
                    val wFrac = (lm.white.toFloat() / total).coerceIn(0f, 1f)
                    val dFrac = (lm.draws.toFloat() / total).coerceIn(0f, 1f)
                    val bFrac = (1f - wFrac - dFrac).coerceAtLeast(0f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onMakeSanMove(lm.san) }
                            .padding(vertical = 5.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = lm.san,
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = colors.textPrimary,
                            modifier = Modifier.width(46.dp)
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF262421))
                        ) {
                            if (wFrac > 0.01f) Box(Modifier.weight(wFrac).height(10.dp).background(Color(0xFFEEEED2)))
                            if (dFrac > 0.01f) Box(Modifier.weight(dFrac).height(10.dp).background(Color(0xFF888888)))
                            if (bFrac > 0.01f) Box(Modifier.weight(bFrac).height(10.dp).background(Color(0xFF333333)))
                        }
                        Text(
                            text = total.toString(),
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(48.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChartAndTurningPointsPanel(
    uiState: AnalyzerUiState,
    onSelectNode: (String) -> Unit,
    onRunFullAnalysis: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val branchNodes = remember(uiState.rootNode, uiState.currentNodeId) {
        val current = VariationTree.findNodeById(uiState.rootNode, uiState.currentNodeId)
        if (current != null && !current.isMainline) {
            VariationTree.getPathToNode(uiState.rootNode, uiState.currentNodeId)
        } else {
            VariationTree.getMainlineNodes(uiState.rootNode)
        }
    }
    val hasAnyAnalyzed = branchNodes.any { it.ply > 0 && it.analysis != null }

    val turningPoints = remember(uiState.rootNode) {
        VariationTree.getMainlineNodes(uiState.rootNode).filter { node ->
            val cat = node.analysis?.category
            node.ply > 0 && (
                cat == MoveClassification.BLUNDER ||
                    cat == MoveClassification.MISTAKE ||
                    cat == MoveClassification.MISSED_CHANCE ||
                    cat == MoveClassification.BRILLIANT ||
                    cat == MoveClassification.GREAT
                )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hasAnyAnalyzed) {
            ChessSurfaceCard(contentPadding = PaddingValues(16.dp)) {
                Text(
                    text = "Запустите «Полный анализ» или сделайте ход на доске, чтобы построить интерактивный график оценки.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                ChessPrimaryButton(
                    text = if (uiState.isBatchAnalyzing) "Идёт анализ…" else "Запустить полный анализ",
                    leadingIcon = Icons.Rounded.PlayArrow,
                    enabled = !uiState.isBatchAnalyzing,
                    onClick = onRunFullAnalysis
                )
            }
        } else {
            AccuracyChart(
                branchNodes = branchNodes,
                currentNodeId = uiState.currentNodeId,
                onSelectNode = onSelectNode,
                expanded = true
            )

            if (turningPoints.isNotEmpty()) {
                ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
                    Text(
                        text = "Ключевые переломные ходы (${turningPoints.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        turningPoints.forEach { node ->
                            val a = node.analysis!!
                            val meta = a.category.styleMeta()
                            val selected = node.id == uiState.currentNodeId
                            Surface(
                                onClick = { onSelectNode(node.id) },
                                shape = RoundedCornerShape(6.dp),
                                color = if (selected) colors.accentGreenContainer else colors.backgroundSecondary
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Image(
                                            painter = painterResource(badgeDrawableFor(a.category)),
                                            contentDescription = meta.labelRu,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "${node.moveNumber}${if (node.color == PieceColor.WHITE) "." else "..."} ${node.san}",
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = meta.labelRu,
                                            fontSize = 11.sp,
                                            color = Color(meta.colorHex)
                                        )
                                    }
                                    Text(
                                        text = formatEvalScore(a.evalAfterCp, a.mateAfter, 2, node.fen),
                                        fontFamily = MonoFontFamily,
                                        fontSize = 11.sp,
                                        color = colors.textSecondary
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
fun AccuracyStatsPanel(
    uiState: AnalyzerUiState,
    onRunFullAnalysis: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val stats = uiState.statistics

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (stats == null) {
            ChessSurfaceCard(contentPadding = PaddingValues(16.dp)) {
                Text(
                    text = "Для расчёта точности белых и чёрных (по формуле Lichess), средней потери CPL и таблицы качества ходов запустите полный анализ партии.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                ChessPrimaryButton(
                    text = if (uiState.isBatchAnalyzing) "Идёт анализ…" else "Запустить полный анализ",
                    leadingIcon = Icons.Rounded.PlayArrow,
                    enabled = !uiState.isBatchAnalyzing,
                    onClick = onRunFullAnalysis
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlayerAccuracyCard(
                    label = "${uiState.metadata.whiteName} (Б)",
                    accuracy = stats.whiteAccuracy,
                    avgCpl = stats.whiteAvgCpl,
                    modifier = Modifier.weight(1f)
                )
                PlayerAccuracyCard(
                    label = "${uiState.metadata.blackName} (Ч)",
                    accuracy = stats.blackAccuracy,
                    avgCpl = stats.blackAvgCpl,
                    modifier = Modifier.weight(1f)
                )
            }

            ChessSurfaceCard(contentPadding = PaddingValues(0.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.backgroundSecondary)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Белые", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text("Категория хода", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text("Чёрные", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }

                MoveClassification.entries.forEach { cat ->
                    val meta = cat.styleMeta()
                    val wCount = stats.whiteCounts[cat] ?: 0
                    val bCount = stats.blackCounts[cat] ?: 0
                    HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = wCount.toString(),
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (wCount > 0) Color(meta.colorHex) else colors.textMuted,
                            modifier = Modifier.width(36.dp)
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(badgeDrawableFor(cat)),
                                contentDescription = meta.labelRu,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = meta.labelRu,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textPrimary
                            )
                        }
                        Text(
                            text = bCount.toString(),
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (bCount > 0) Color(meta.colorHex) else colors.textMuted,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(36.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerAccuracyCard(
    label: String,
    accuracy: Double,
    avgCpl: Int,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    ChessSurfaceCard(modifier = modifier, contentPadding = PaddingValues(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$accuracy%",
            fontFamily = MonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = colors.accentGreen
        )
        Text(
            text = "Средняя потеря: $avgCpl сП",
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            color = colors.textSecondary
        )
    }
}

@Composable
fun GameInfoPanel(
    uiState: AnalyzerUiState,
    onRunFullAnalysis: () -> Unit,
    onCancelAnalysis: () -> Unit,
    onExportPgnNotice: (String) -> Unit,
    getExportPgn: () -> String,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Players Card
        ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
            InfoPlayerRow(
                isWhite = true,
                name = uiState.metadata.whiteName,
                title = uiState.metadata.whiteTitle,
                rating = uiState.metadata.whiteRating,
                ratingDiff = uiState.metadata.whiteRatingDiff,
                accuracy = uiState.statistics?.whiteAccuracy
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(8.dp))
            InfoPlayerRow(
                isWhite = false,
                name = uiState.metadata.blackName,
                title = uiState.metadata.blackTitle,
                rating = uiState.metadata.blackRating,
                ratingDiff = uiState.metadata.blackRatingDiff,
                accuracy = uiState.statistics?.blackAccuracy
            )
        }

        // Metadata Grid
        ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                MetaItem("Платформа", uiState.metadata.platform.label, Modifier.weight(1f))
                MetaItem("Результат", uiState.metadata.result, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MetaItem("Контроль", uiState.metadata.timeControl ?: "Без часов", Modifier.weight(1f))
                MetaItem("Дата", uiState.metadata.date, Modifier.weight(1f))
            }
        }

        // Opening Recognition Card
        ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Дебют (ECO ${uiState.openingSummary.eco})",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFA88865)
                )
                Text(
                    text = uiState.openingSummary.version,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    color = colors.textMuted
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.openingSummary.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary
            )
            Text(
                text = uiState.openingSummary.variation,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Теория: Б ${uiState.openingSummary.theoreticalMovesWhite} · Ч ${uiState.openingSummary.theoreticalMovesBlack}",
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = colors.textSecondary
            )
            uiState.openingSummary.firstOutOfBookMoveSan?.let { outMove ->
                Text(
                    text = "Выход из книги: $outMove",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = colors.accentAmber
                )
            }
        }

        // Analysis trigger & progress
        if (uiState.isBatchAnalyzing) {
            ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Анализ Stockfish…", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Text(
                        text = "${uiState.batchCompleted} / ${uiState.batchTotal}",
                        fontFamily = MonoFontFamily,
                        fontSize = 12.sp,
                        color = colors.accentGreen
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val progress = if (uiState.batchTotal > 0) {
                    uiState.batchCompleted.toFloat() / uiState.batchTotal.toFloat()
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentGreen,
                    trackColor = colors.backgroundSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                ChessSecondaryButton(
                    text = "Остановить анализ",
                    leadingIcon = Icons.Rounded.Stop,
                    onClick = onCancelAnalysis
                )
            }
        } else {
            ChessPrimaryButton(
                text = if (uiState.statistics != null) "Пересчитать полный анализ" else "Запустить полный анализ",
                leadingIcon = Icons.Rounded.PlayArrow,
                onClick = onRunFullAnalysis
            )
        }

        ChessSecondaryButton(
            text = "Копировать PGN",
            leadingIcon = Icons.Rounded.ContentCopy,
            onClick = {
                val pgn = getExportPgn()
                clipboard.setText(AnnotatedString(pgn))
                onExportPgnNotice("PGN скопирован в буфер обмена")
            }
        )
    }
}

@Composable
private fun InfoPlayerRow(
    isWhite: Boolean,
    name: String,
    title: String?,
    rating: Int?,
    ratingDiff: Int?,
    accuracy: Double?
) {
    val colors = ChessTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isWhite) Color.White else Color(0xFF262421))
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!title.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = Color(0xFFB33430)
                        ) {
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
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (rating != null) {
                        Text(
                            text = buildString {
                                append("($rating)")
                                if (ratingDiff != null) {
                                    append(" ${if (ratingDiff > 0) "+" else ""}$ratingDiff")
                                }
                            },
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Text(
                    text = if (isWhite) "Белые" else "Чёрные",
                    fontSize = 10.sp,
                    color = colors.textMuted
                )
            }
        }
        if (accuracy != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$accuracy%",
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = colors.accentGreen
                )
                Text("Точность", fontSize = 10.sp, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun MetaItem(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = ChessTheme.colors
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
