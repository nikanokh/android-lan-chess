package com.example.feature.analysis

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.analysis.AnalyzerPreferences
import com.example.core.analysis.ChessComApiService
import com.example.core.analysis.ChessComGameItem
import com.example.core.analysis.EvalBarPosition
import com.example.core.analysis.EvalScoreDisplay
import com.example.core.analysis.PgnService
import com.example.core.analysis.VariationTree
import com.example.core.ui.components.ChessPrimaryButton
import com.example.core.ui.components.ChessSecondaryButton
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import kotlin.math.roundToInt

@Composable
private fun CompactModalTabBar(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val colors = ChessTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.backgroundSecondary,
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            titles.forEachIndexed { idx, title ->
                val selected = idx == selectedIndex
                Surface(
                    onClick = { onSelect(idx) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) colors.accentGreenContainer else Color.Transparent,
                    border = if (selected) BorderStroke(1.dp, colors.accentGreen.copy(alpha = 0.5f)) else null,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) colors.accentGreen else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberFixedModalHeight(): Dp {
    val screenH = LocalConfiguration.current.screenHeightDp
    return minOf(620f, screenH * 0.84f).coerceAtLeast(420f).dp
}

@Composable
fun ImportGameDialog(
    onDismiss: () -> Unit,
    onLoadPgn: (String) -> Unit,
    onLoadFen: (String) -> Unit,
    onAddOpeningBook: (eco: String, name: String, variation: String, moves: String) -> Boolean
) {
    val colors = ChessTheme.colors
    val modalHeight = rememberFixedModalHeight()
    var selectedTab by remember { mutableIntStateOf(0) }
    var pgnText by remember { mutableStateOf("") }
    var fenText by remember { mutableStateOf(VariationTree.DEFAULT_START_FEN) }
    var ecoCode by remember { mutableStateOf("C50") }
    var openingName by remember { mutableStateOf("") }
    var openingVariation by remember { mutableStateOf("") }
    var openingMoves by remember { mutableStateOf("e4 e5 Nf3 Nc6 Bc4") }
    var notice by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(modalHeight),
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.borderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Импорт PGN / FEN / Дебюты", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = colors.textSecondary)
                    }
                }

                CompactModalTabBar(
                    titles = listOf("PGN", "FEN", "Классика", "Книга ECO"),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it; notice = null }
                )

                Spacer(modifier = Modifier.height(10.dp))

                notice?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accentGreen,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        0 -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = pgnText,
                                    onValueChange = { pgnText = it },
                                    label = { Text("Вставьте текст PGN") },
                                    minLines = 6,
                                    maxLines = 12,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                ChessPrimaryButton(
                                    text = "Загрузить PGN",
                                    enabled = pgnText.isNotBlank(),
                                    onClick = {
                                        onLoadPgn(pgnText)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        1 -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = fenText,
                                    onValueChange = { fenText = it },
                                    label = { Text("Строка FEN") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(onClick = { fenText = VariationTree.DEFAULT_START_FEN }) {
                                        Text("Сбросить на начальную")
                                    }
                                }
                                ChessPrimaryButton(
                                    text = "Установить FEN",
                                    enabled = fenText.isNotBlank(),
                                    onClick = {
                                        onLoadFen(fenText)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        2 -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(PgnService.SAMPLE_MASTER_GAMES) { sample ->
                                    ChessSurfaceCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            onLoadPgn(sample.pgn)
                                            onDismiss()
                                        },
                                        contentPadding = PaddingValues(12.dp)
                                    ) {
                                        Text(
                                            text = sample.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = colors.textPrimary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = sample.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                        3 -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = ecoCode,
                                        onValueChange = { ecoCode = it },
                                        label = { Text("ECO") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.35f)
                                    )
                                    OutlinedTextField(
                                        value = openingName,
                                        onValueChange = { openingName = it },
                                        label = { Text("Название дебюта") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.65f)
                                    )
                                }
                                OutlinedTextField(
                                    value = openingVariation,
                                    onValueChange = { openingVariation = it },
                                    label = { Text("Вариант") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = openingMoves,
                                    onValueChange = { openingMoves = it },
                                    label = { Text("Ходы (SAN)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                ChessPrimaryButton(
                                    text = "Добавить в базу теории",
                                    leadingIcon = Icons.Rounded.Add,
                                    enabled = openingName.isNotBlank() && openingMoves.isNotBlank(),
                                    onClick = {
                                        if (onAddOpeningBook(ecoCode, openingName, openingVariation, openingMoves)) {
                                            notice = "Добавлено в локальную базу ECO!"
                                            openingName = ""
                                            openingVariation = ""
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatArchiveMonthLabel(archiveUrl: String): String {
    val parts = archiveUrl.trimEnd('/').split("/")
    val month = parts.getOrNull(parts.size - 1) ?: return archiveUrl
    val year = parts.getOrNull(parts.size - 2) ?: return archiveUrl
    return "$year/$month"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChessComImportDialog(
    uiState: AnalyzerUiState,
    onDismiss: () -> Unit,
    onSearchUser: (String) -> Unit,
    onSelectArchive: (String) -> Unit,
    onLoadGame: (ChessComGameItem) -> Unit
) {
    val colors = ChessTheme.colors
    val modalHeight = rememberFixedModalHeight()
    var username by remember { mutableStateOf(uiState.preferences.lastChessComUsername) }
    val recentUsernames = uiState.preferences.recentChessComUsernames.take(6)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .height(modalHeight),
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.borderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Партии с Chess.com", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = colors.textSecondary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = { Text("Никнейм Chess.com", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    )
                    Surface(
                        onClick = { onSearchUser(username) },
                        shape = RoundedCornerShape(10.dp),
                        color = colors.accentGreen,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Найти",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                if (recentUsernames.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        recentUsernames.forEach { qp ->
                            Surface(
                                onClick = {
                                    username = qp
                                    onSearchUser(qp)
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = colors.backgroundSecondary,
                                border = BorderStroke(1.dp, colors.borderSubtle)
                            ) {
                                Text(
                                    text = qp,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                if (uiState.chessComArchives.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(items = uiState.chessComArchives, key = { it }) { archiveUrl ->
                            val selected = archiveUrl == uiState.selectedChessComArchiveUrl
                            Surface(
                                onClick = { onSelectArchive(archiveUrl) },
                                shape = RoundedCornerShape(7.dp),
                                color = if (selected) colors.accentGreenContainer else colors.backgroundSecondary,
                                border = BorderStroke(1.dp, if (selected) colors.accentGreen else colors.borderSubtle)
                            ) {
                                Text(
                                    text = formatArchiveMonthLabel(archiveUrl),
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) colors.accentGreen else colors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                uiState.chessComError?.let { err ->
                    Text(text = err, style = MaterialTheme.typography.bodySmall, color = colors.accentCoral)
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (uiState.isChessComLoading) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = colors.accentGreen)
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("Загрузка архива Chess.com…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                    } else if (uiState.chessComGames.isEmpty()) {
                        Text(
                            text = if (uiState.chessComArchives.isEmpty()) {
                                "Введите никнейм Chess.com и нажмите поиск."
                            } else {
                                "За выбранный месяц партий не найдено."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(items = uiState.chessComGames, key = { ChessComApiService.gameKey(it) }) { game ->
                                val mapped = ChessComApiService.mapResult(game, username)
                                ChessSurfaceCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        if (game.pgn != null) {
                                            onLoadGame(game)
                                            onDismiss()
                                        }
                                    },
                                    contentPadding = PaddingValues(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${game.white.username} (${game.white.rating ?: "?"}) vs ${game.black.username} (${game.black.rating ?: "?"})",
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${game.timeClass ?: game.timeControl} · ${mapped.resultString}",
                                                fontFamily = MonoFontFamily,
                                                fontSize = 11.sp,
                                                color = colors.textSecondary
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = when (mapped.outcome) {
                                                com.example.core.analysis.RepertoireOutcome.WIN -> colors.accentGreenContainer
                                                com.example.core.analysis.RepertoireOutcome.DRAW -> colors.backgroundSecondary
                                                com.example.core.analysis.RepertoireOutcome.LOSS -> colors.accentCoralContainer
                                            }
                                        ) {
                                            Text(
                                                text = when (mapped.outcome) {
                                                    com.example.core.analysis.RepertoireOutcome.WIN -> "Победа"
                                                    com.example.core.analysis.RepertoireOutcome.DRAW -> "Ничья"
                                                    com.example.core.analysis.RepertoireOutcome.LOSS -> "Поражение"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when (mapped.outcome) {
                                                    com.example.core.analysis.RepertoireOutcome.WIN -> colors.accentGreen
                                                    com.example.core.analysis.RepertoireOutcome.DRAW -> colors.textSecondary
                                                    com.example.core.analysis.RepertoireOutcome.LOSS -> colors.accentCoral
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
    }
}

@Composable
fun SavedGamesDialog(
    uiState: AnalyzerUiState,
    onDismiss: () -> Unit,
    onOpenSavedRecord: (com.example.core.analysis.SavedAnalyzerGameRecord) -> Unit,
    onDeleteSavedRecord: (String) -> Unit,
    onOpenRoomGame: (com.example.domain.model.GameSummary) -> Unit
) {
    val colors = ChessTheme.colors
    val modalHeight = rememberFixedModalHeight()
    var selectedTab by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(modalHeight),
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.borderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Сохранённые партии", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = colors.textSecondary)
                    }
                }

                CompactModalTabBar(
                    titles = listOf(
                        "Анализатор (${uiState.savedGames.size})",
                        "Партии ChessLAN (${uiState.roomGames.size})"
                    ),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (selectedTab == 0) {
                        if (uiState.savedGames.isEmpty()) {
                            Text(
                                text = "Нет сохранённых разборов.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(items = uiState.savedGames, key = { it.id }) { rec ->
                                    ChessSurfaceCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            onOpenSavedRecord(rec)
                                            onDismiss()
                                        },
                                        contentPadding = PaddingValues(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${rec.metadata.whiteName} vs ${rec.metadata.blackName}",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = colors.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = buildString {
                                                        append("${rec.metadata.result} · ${rec.metadata.date}")
                                                        rec.statistics?.let {
                                                            append(" · Точность ${it.whiteAccuracy}% / ${it.blackAccuracy}%")
                                                        }
                                                    },
                                                    fontFamily = MonoFontFamily,
                                                    fontSize = 11.sp,
                                                    color = colors.textSecondary
                                                )
                                            }
                                            IconButton(
                                                onClick = { onDeleteSavedRecord(rec.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить", tint = colors.accentCoral)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (uiState.roomGames.isEmpty()) {
                            Text(
                                text = "Сыгранные партии ChessLAN появятся здесь автоматически.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(items = uiState.roomGames, key = { it.id }) { g ->
                                    ChessSurfaceCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            onOpenRoomGame(g)
                                            onDismiss()
                                        },
                                        contentPadding = PaddingValues(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${g.whitePlayer} vs ${g.blackPlayer}",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = colors.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${g.timeControlLabel} · ${g.moveCount} ходов",
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
        }
    }
}

@Composable
fun AnalyzerSettingsDialog(
    preferences: AnalyzerPreferences,
    onDismiss: () -> Unit,
    onUpdatePreferences: ((AnalyzerPreferences) -> AnalyzerPreferences) -> Unit,
    onResetDefaults: () -> Unit
) {
    val colors = ChessTheme.colors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceCard,
            border = BorderStroke(1.dp, colors.borderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Настройки анализатора и Stockfish", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = colors.textSecondary)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Отображение", style = MaterialTheme.typography.titleSmall, color = colors.accentGreen)
                    Text("Шкала оценки (Eval Bar)", style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val barChoices = listOf(
                            Triple("Слева", true to EvalBarPosition.LEFT, "eval_bar_pos_left"),
                            Triple("Сверху", true to EvalBarPosition.TOP, "eval_bar_pos_top"),
                            Triple("Отключена", false to preferences.evalBarPosition, "eval_bar_pos_off")
                        )
                        barChoices.forEach { (label, targetPair, tag) ->
                            val (enabledFlag, targetPos) = targetPair
                            val selected = if (!enabledFlag) {
                                !preferences.showEvalBar
                            } else {
                                preferences.showEvalBar && preferences.evalBarPosition == targetPos
                            }
                            SegmentedSettingChip(
                                label = label,
                                selected = selected,
                                onClick = {
                                    onUpdatePreferences {
                                        it.copy(showEvalBar = enabledFlag, evalBarPosition = targetPos)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(tag)
                            )
                        }
                    }

                    Text(
                        text = "Оценка позиции на шкале",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (preferences.showEvalBar) colors.textPrimary else colors.textMuted
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val scoreChoices = listOf(
                            Triple("Внутри", EvalScoreDisplay.INSIDE_BAR, "eval_score_inside"),
                            Triple("Отдельно", EvalScoreDisplay.SEPARATE, "eval_score_separate"),
                            Triple("Скрыть", EvalScoreDisplay.HIDDEN, "eval_score_hidden")
                        )
                        scoreChoices.forEach { (label, mode, tag) ->
                            SegmentedSettingChip(
                                label = label,
                                selected = preferences.evalScoreDisplay == mode,
                                enabled = preferences.showEvalBar,
                                onClick = {
                                    onUpdatePreferences { it.copy(evalScoreDisplay = mode) }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(tag)
                            )
                        }
                    }
                    if (preferences.showEvalBar) {
                        val hintText = when (preferences.evalScoreDisplay) {
                            EvalScoreDisplay.INSIDE_BAR -> "Оценка отображается прямо внутри полосы преимущества"
                            EvalScoreDisplay.SEPARATE -> if (preferences.evalBarPosition == EvalBarPosition.TOP) {
                                "Оценка выводится отдельным блоком справа от верхней шкалы"
                            } else {
                                "Оценка выводится отдельным блоком снизу под шкалой"
                            }
                            EvalScoreDisplay.HIDDEN -> "Показывается только полоса преимущества без цифр"
                        }
                        Text(
                            text = hintText,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                    SwitchSettingRow(
                        title = "Стрелки вариантов Stockfish",
                        checked = preferences.showEngineArrows,
                        onCheckedChange = { v -> onUpdatePreferences { it.copy(showEngineArrows = v) } }
                    )
                    SwitchSettingRow(
                        title = "Пользовательские стрелки и подсветка",
                        checked = preferences.showUserArrows,
                        onCheckedChange = { v -> onUpdatePreferences { it.copy(showUserArrows = v) } }
                    )

                    HorizontalDivider(color = colors.borderSubtle)
                    Text("Параметры движка Stockfish (UCI)", style = MaterialTheme.typography.titleSmall, color = colors.accentGreen)

                    Text("Глубина (Depth): ${preferences.engineConfig.depth}", style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                    Slider(
                        value = preferences.engineConfig.depth.toFloat(),
                        onValueChange = { v ->
                            onUpdatePreferences { it.copy(engineConfig = it.engineConfig.copy(depth = v.roundToInt())) }
                        },
                        valueRange = 6f..26f
                    )

                    Text("Линии MultiPV: ${preferences.engineConfig.multiPv}", style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                    Slider(
                        value = preferences.engineConfig.multiPv.toFloat(),
                        onValueChange = { v ->
                            onUpdatePreferences { it.copy(engineConfig = it.engineConfig.copy(multiPv = v.roundToInt())) }
                        },
                        valueRange = 1f..5f,
                        steps = 3
                    )

                    Text("Хеш-таблица (Hash MB): ${preferences.engineConfig.hashMb}", style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(16, 32, 64, 128).forEach { mb ->
                            val selected = preferences.engineConfig.hashMb == mb
                                Surface(
                                onClick = {
                                    onUpdatePreferences { it.copy(engineConfig = it.engineConfig.copy(hashMb = mb)) }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) colors.accentGreenContainer else colors.backgroundSecondary,
                                border = BorderStroke(1.dp, if (selected) colors.accentGreen else colors.borderSubtle),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = "${mb}МБ",
                                        fontFamily = MonoFontFamily,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        color = if (selected) colors.accentGreen else colors.textPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = colors.borderSubtle)
                    Text("Пороги классификации ходов (CPL)", style = MaterialTheme.typography.titleSmall, color = colors.accentGreen)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThresholdField("Прекрасный ≤", preferences.thresholds.excellentMaxCpl, Modifier.weight(1f)) { valInt ->
                            onUpdatePreferences { it.copy(thresholds = it.thresholds.copy(excellentMaxCpl = valInt)) }
                        }
                        ThresholdField("Хороший ≤", preferences.thresholds.goodMaxCpl, Modifier.weight(1f)) { valInt ->
                            onUpdatePreferences { it.copy(thresholds = it.thresholds.copy(goodMaxCpl = valInt)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThresholdField("Неточность ≤", preferences.thresholds.inaccuracyMaxCpl, Modifier.weight(1f)) { valInt ->
                            onUpdatePreferences { it.copy(thresholds = it.thresholds.copy(inaccuracyMaxCpl = valInt)) }
                        }
                        ThresholdField("Ошибка ≤", preferences.thresholds.mistakeMaxCpl, Modifier.weight(1f)) { valInt ->
                            onUpdatePreferences { it.copy(thresholds = it.thresholds.copy(mistakeMaxCpl = valInt)) }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ChessSecondaryButton(
                        text = "Сбросить",
                        onClick = onResetDefaults,
                        modifier = Modifier.weight(1f)
                    )
                    ChessPrimaryButton(
                        text = "Готово",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = ChessTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SegmentedSettingChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = when {
            !enabled -> colors.backgroundSecondary.copy(alpha = 0.5f)
            selected -> colors.accentGreenContainer
            else -> colors.backgroundSecondary
        },
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> colors.borderSubtle.copy(alpha = 0.5f)
                selected -> colors.accentGreen
                else -> colors.borderSubtle
            }
        ),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = when {
                    !enabled -> colors.textMuted
                    selected -> colors.accentGreen
                    else -> colors.textPrimary
                },
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun ThresholdField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onValueChanged: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.toIntOrNull()?.coerceIn(0, 600)?.let(onValueChanged)
        },
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
