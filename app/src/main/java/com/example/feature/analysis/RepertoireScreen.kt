package com.example.feature.analysis

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.analysis.OpeningStatsSummary
import com.example.core.analysis.ProblematicContinuationItem
import com.example.core.analysis.RepertoirePeriodPreset
import com.example.core.analysis.RepertoireTimeClass
import com.example.core.analysis.RepertoireTreeNode
import com.example.core.ui.components.ChessPrimaryButton
import com.example.core.ui.components.ChessSecondaryButton
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.PieceColor
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpeningRepertoireScreen(
    uiState: AnalyzerUiState,
    onLoadSavedGames: () -> Unit,
    onLoadChessCom: (String) -> Unit,
    onLoadPgnText: (String) -> Unit,
    onSelectPeriod: (RepertoirePeriodPreset) -> Unit,
    onSelectColorFilter: (PieceColor?) -> Unit,
    onToggleTimeClass: (RepertoireTimeClass) -> Unit,
    onUpdatePlayerName: (String) -> Unit,
    onSelectTab: (RepertoireTab) -> Unit,
    onStartEngineAnalysis: () -> Unit,
    onStopEngineAnalysis: () -> Unit,
    onOpenGameInAnalyzer: (String) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    var chessComInput by remember { mutableStateOf(uiState.preferences.lastChessComUsername) }
    var pgnInput by remember { mutableStateOf("") }
    var showPgnBox by remember { mutableStateOf(false) }
    var treeColor by remember { mutableStateOf(PieceColor.WHITE) }
    var expandedTreeIds by remember { mutableStateOf(setOf("tree_root_WHITE", "tree_root_BLACK")) }
    var searchQuery by remember { mutableStateOf("") }

    val aggregation = uiState.repertoireAggregation

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Source & Controls Card
        item(key = "sources") {
            ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.AutoStories, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(18.dp))
                        Text("Источники партий", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    }
                    IconButton(onClick = onClearCache, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Очистить кеш", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    text = uiState.repertoireSourceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChessSecondaryButton(
                        text = "Мои партии",
                        leadingIcon = Icons.Rounded.Refresh,
                        onClick = onLoadSavedGames,
                        modifier = Modifier.weight(1f)
                    )
                    ChessSecondaryButton(
                        text = if (showPgnBox) "Скрыть PGN" else "Вставить PGN",
                        onClick = { showPgnBox = !showPgnBox },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = chessComInput,
                        onValueChange = { chessComInput = it },
                        label = { Text("Chess.com никнейм", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        onClick = { onLoadChessCom(chessComInput) },
                        shape = RoundedCornerShape(10.dp),
                        color = colors.accentGreen
                    ) {
                        Text(
                            text = "Загрузить",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 15.dp)
                        )
                    }
                }

                if (showPgnBox) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pgnInput,
                        onValueChange = { pgnInput = it },
                        label = { Text("Многопартийный PGN") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ChessPrimaryButton(
                        text = "Разобрать PGN в репертуар",
                        enabled = pgnInput.isNotBlank(),
                        onClick = {
                            onLoadPgnText(pgnInput)
                            showPgnBox = false
                        }
                    )
                }

                uiState.repertoireError?.let { err ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = err, style = MaterialTheme.typography.bodySmall, color = colors.accentCoral)
                }
            }
        }

        // Filters & Engine Analysis Card
        item(key = "filters") {
            ChessSurfaceCard(contentPadding = PaddingValues(12.dp)) {
                Text("Фильтры и анализ", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = uiState.repertoireFilters.playerUsername,
                    onValueChange = onUpdatePlayerName,
                    label = { Text("Перспектива игрока", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        null to "Все цвета",
                        PieceColor.WHITE to "Белые",
                        PieceColor.BLACK to "Чёрные"
                    ).forEach { (colorChoice, label) ->
                        val selected = uiState.repertoireFilters.colorFilter == colorChoice
                        FilterChipPill(label = label, selected = selected) {
                            onSelectColorFilter(colorChoice)
                        }
                    }

                    listOf(
                        RepertoirePeriodPreset.ALL,
                        RepertoirePeriodPreset.DAYS_7,
                        RepertoirePeriodPreset.DAYS_30,
                        RepertoirePeriodPreset.DAYS_90
                    ).forEach { preset ->
                        FilterChipPill(
                            label = preset.label,
                            selected = uiState.repertoireFilters.periodPreset == preset
                        ) {
                            onSelectPeriod(preset)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RepertoireTimeClass.entries.forEach { tc ->
                        FilterChipPill(
                            label = tc.label,
                            selected = tc in uiState.repertoireFilters.timeClasses
                        ) {
                            onToggleTimeClass(tc)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                val preview = uiState.repertoirePreview
                Text(
                    text = "Выбрано: ${preview.totalMatching} (Б: ${preview.whiteCount} · Ч: ${preview.blackCount}) · С анализом: ${preview.alreadyAnalyzedCount} · Ждут Stockfish: ${preview.needsEngineAnalysisCount}",
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.isRepertoireLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = colors.accentGreen)
                        Text("Классификация дебютов…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                } else if (uiState.isRepertoireEngineRunning) {
                    val progress = if (uiState.repertoireEngineTotal > 0) {
                        uiState.repertoireEngineCompleted.toFloat() / uiState.repertoireEngineTotal.toFloat()
                    } else 0f
                    Text(
                        text = "Анализ дебютов Stockfish: ${uiState.repertoireEngineCompleted} / ${uiState.repertoireEngineTotal}",
                        fontFamily = MonoFontFamily,
                        fontSize = 11.sp,
                        color = colors.accentGreen
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = colors.accentGreen)
                    Spacer(modifier = Modifier.height(8.dp))
                    ChessSecondaryButton(
                        text = "Остановить анализ",
                        leadingIcon = Icons.Rounded.Stop,
                        onClick = onStopEngineAnalysis
                    )
                } else {
                    ChessPrimaryButton(
                        text = "Анализировать репертуар (Stockfish)",
                        leadingIcon = Icons.Rounded.PlayArrow,
                        enabled = preview.needsEngineAnalysisCount > 0,
                        onClick = onStartEngineAnalysis
                    )
                }
            }
        }

        // Tabs
        item(key = "tabs") {
            ScrollableTabRow(
                selectedTabIndex = uiState.repertoireTab.ordinal,
                containerColor = colors.backgroundSecondary,
                contentColor = colors.accentGreen,
                edgePadding = 4.dp
            ) {
                RepertoireTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.repertoireTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(tab.title, fontSize = 12.sp) }
                    )
                }
            }
        }

        if (aggregation == null || aggregation.totalGames == 0) {
            item(key = "empty") {
                ChessSurfaceCard(contentPadding = PaddingValues(18.dp)) {
                    Text(
                        text = "Нет партий под текущие фильтры. Загрузите партии с Chess.com, из PGN или сыграйте партию в ChessLAN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
        } else {
            when (uiState.repertoireTab) {
                RepertoireTab.OVERVIEW, RepertoireTab.WHITE, RepertoireTab.BLACK -> {
                    val list = when (uiState.repertoireTab) {
                        RepertoireTab.WHITE -> aggregation.whiteOpenings
                        RepertoireTab.BLACK -> aggregation.blackOpenings
                        else -> aggregation.openings
                    }.filter {
                        searchQuery.isBlank() ||
                            it.openingName.contains(searchQuery, ignoreCase = true) ||
                            it.eco.contains(searchQuery, ignoreCase = true)
                    }

                    item(key = "search_openings") {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Поиск по дебюту или ECO", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    items(items = list, key = { "${uiState.repertoireTab.name}_${it.key}" }) { item ->
                        OpeningSummaryCard(
                            summary = item,
                            onOpenSampleGame = { gameId -> onOpenGameInAnalyzer(gameId) }
                        )
                    }
                }

                RepertoireTab.TREE -> {
                    item(key = "tree_switcher") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChipPill("За белых (${aggregation.whiteGames})", treeColor == PieceColor.WHITE) {
                                treeColor = PieceColor.WHITE
                            }
                            FilterChipPill("За чёрных (${aggregation.blackGames})", treeColor == PieceColor.BLACK) {
                                treeColor = PieceColor.BLACK
                            }
                        }
                    }

                    val rootTree = if (treeColor == PieceColor.WHITE) aggregation.whiteTree else aggregation.blackTree
                    val flattened = flattenVisibleTree(rootTree, expandedTreeIds)
                    items(items = flattened, key = { it.first.id }) { (node, depth) ->
                        RepertoireTreeNodeRow(
                            node = node,
                            depth = depth,
                            expanded = node.id in expandedTreeIds,
                            onToggleExpand = {
                                expandedTreeIds = if (node.id in expandedTreeIds) {
                                    expandedTreeIds - node.id
                                } else {
                                    expandedTreeIds + node.id
                                }
                            },
                            onOpenGame = { node.gameIds.firstOrNull()?.let(onOpenGameInAnalyzer) }
                        )
                    }
                }

                RepertoireTab.PROBLEMATIC -> {
                    val itemsList = aggregation.problematicItems
                    if (itemsList.isEmpty()) {
                        item(key = "no_problems") {
                            ChessSurfaceCard(contentPadding = PaddingValues(16.dp)) {
                                Text(
                                    text = if (aggregation.analyzedGames == 0) {
                                        "Запустите анализ репертуара движком Stockfish, чтобы выявить регулярные дебютные ошибки и сильные продолжения."
                                    } else {
                                        "Повторяющихся критических ошибок в дебюте не обнаружено."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    } else {
                        items(items = itemsList, key = { it.id }) { item ->
                            ProblematicItemCard(
                                item = item,
                                onOpenExampleGame = onOpenGameInAnalyzer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) colors.accentGreenContainer else colors.backgroundSecondary,
        border = BorderStroke(1.dp, if (selected) colors.accentGreen else colors.borderSubtle)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.accentGreen else colors.textPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun OpeningSummaryCard(
    summary: OpeningStatsSummary,
    onOpenSampleGame: (String) -> Unit
) {
    val colors = ChessTheme.colors
    var expanded by remember { mutableStateOf(false) }

    ChessSurfaceCard(
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(12.dp)
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
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = colors.backgroundSecondary
                ) {
                    Text(
                        text = summary.eco,
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFFA88865),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = summary.openingName,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${summary.gamesCount} (${summary.frequencyPercent}%)",
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = colors.textSecondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        WdlBar(
            winRate = summary.winRate,
            drawRate = summary.drawRate,
            lossRate = summary.lossRate,
            wins = summary.wins,
            draws = summary.draws,
            losses = summary.losses
        )

        if (summary.avgAccuracy != null || summary.avgCpl != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    summary.avgAccuracy?.let { append("Точность: $it% ") }
                    summary.avgCpl?.let { append("· Ср. CPL: $it") }
                },
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = colors.accentGreen
            )
        }

        if (expanded && summary.variations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            summary.variations.take(5).forEach { v ->
                Surface(
                    onClick = { v.gameIds.firstOrNull()?.let(onOpenSampleGame) },
                    shape = RoundedCornerShape(6.dp),
                    color = colors.backgroundSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = v.variationName,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (v.sampleMovesSan.isNotEmpty()) {
                                Text(
                                    text = v.sampleMovesSan.take(6).joinToString(" "),
                                    fontFamily = MonoFontFamily,
                                    fontSize = 10.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        Text(
                            text = "${v.gamesCount} · ${v.winRate}%",
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            color = colors.accentGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WdlBar(
    winRate: Double,
    drawRate: Double,
    lossRate: Double,
    wins: Int,
    draws: Int,
    losses: Int
) {
    val colors = ChessTheme.colors
    val w = winRate.toFloat().coerceAtLeast(0f)
    val d = drawRate.toFloat().coerceAtLeast(0f)
    val l = lossRate.toFloat().coerceAtLeast(0f)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.backgroundSecondary)
        ) {
            if (w > 0f) Box(Modifier.weight(w).height(8.dp).background(colors.accentGreen))
            if (d > 0f) Box(Modifier.weight(d).height(8.dp).background(Color(0xFF8B8987)))
            if (l > 0f) Box(Modifier.weight(l).height(8.dp).background(colors.accentCoral))
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("В: ${winRate.roundToInt()}% ($wins)", fontFamily = MonoFontFamily, fontSize = 10.sp, color = colors.accentGreen)
            Text("Н: ${drawRate.roundToInt()}% ($draws)", fontFamily = MonoFontFamily, fontSize = 10.sp, color = colors.textSecondary)
            Text("П: ${lossRate.roundToInt()}% ($losses)", fontFamily = MonoFontFamily, fontSize = 10.sp, color = colors.accentCoral)
        }
    }
}

private fun flattenVisibleTree(
    root: RepertoireTreeNode,
    expandedIds: Set<String>
): List<Pair<RepertoireTreeNode, Int>> {
    val result = mutableListOf<Pair<RepertoireTreeNode, Int>>()
    fun dfs(node: RepertoireTreeNode, depth: Int) {
        result.add(node to depth)
        if (node.id in expandedIds) {
            for (child in node.children) {
                dfs(child, depth + 1)
            }
        }
    }
    dfs(root, 0)
    return result
}

@Composable
private fun RepertoireTreeNodeRow(
    node: RepertoireTreeNode,
    depth: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenGame: () -> Unit
) {
    val colors = ChessTheme.colors
    val indent = (depth * 14).coerceAtMost(70).dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent),
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenGame)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (node.children.isNotEmpty()) {
                    IconButton(onClick = onToggleExpand, modifier = Modifier.size(26.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(26.dp))
                }
                val titleText = if (node.ply == 0) {
                    node.san
                } else {
                    "${node.moveNumber}${if (node.color == PieceColor.WHITE) "." else "..."} ${node.san}"
                }
                Text(
                    text = titleText,
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = colors.textPrimary
                )
            }

            Text(
                text = "${node.gamesCount} п · ${node.winRate.roundToInt()}% В",
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = colors.accentGreen
            )
        }
    }
}

@Composable
private fun ProblematicItemCard(
    item: ProblematicContinuationItem,
    onOpenExampleGame: (String) -> Unit
) {
    val colors = ChessTheme.colors
    val accentColor = if (item.isObjectiveError) colors.accentCoral else colors.accentGreen

    ChessSurfaceCard(
        borderColor = accentColor.copy(alpha = 0.45f),
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.summaryTitle,
                style = MaterialTheme.typography.titleSmall,
                color = accentColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${item.eco} · ${item.occurrences}x",
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = colors.textSecondary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${item.openingName} (${item.variationName})",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.summaryDescription,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary
        )
        if (item.examples.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            item.examples.take(2).forEach { ex ->
                Surface(
                    onClick = { onOpenExampleGame(ex.gameId) },
                    shape = RoundedCornerShape(6.dp),
                    color = colors.backgroundSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "vs ${ex.opponentName} (${ex.dateStr})",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Открыть →",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accentBlue
                        )
                    }
                }
            }
        }
    }
}
