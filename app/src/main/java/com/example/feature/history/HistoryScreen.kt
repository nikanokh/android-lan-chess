package com.example.feature.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.components.ChessTopBar
import com.example.core.ui.theme.ChessDimensions
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.GameResult
import com.example.domain.model.GameSummary
import com.example.domain.usecase.DeleteGameUseCase
import com.example.domain.usecase.GetGameHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    getGameHistoryUseCase: GetGameHistoryUseCase,
    private val deleteGameUseCase: DeleteGameUseCase
) : ViewModel() {
    val games: StateFlow<List<GameSummary>> = getGameHistoryUseCase().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun deleteGame(gameId: Long) {
        viewModelScope.launch {
            deleteGameUseCase(gameId)
        }
    }
}

@Composable
fun HistoryScreen(
    onNavigateBack: (() -> Unit)?,
    onAnalyzeGame: (Long) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val games by viewModel.games.collectAsStateWithLifecycle()
    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing
    var pendingDeleteGame by remember { mutableStateOf<GameSummary?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChessTopBar(
                title = stringResource(R.string.history_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (games.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = ChessDimensions.MaxContentWidth)
                        .padding(horizontal = spacing.xl, vertical = 48.dp)
                        .testTag("history_empty_state"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surfaceCard),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.piece_wr),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.history_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.history_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = ChessDimensions.MaxContentWidth)
                        .testTag("history_games_list"),
                    contentPadding = PaddingValues(
                        horizontal = spacing.screenHorizontal,
                        vertical = spacing.md
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    items(items = games, key = { it.id }) { game ->
                        HistoryGameCard(
                            game = game,
                            onAnalyzeClick = { onAnalyzeGame(game.id) },
                            onDeleteClick = { pendingDeleteGame = game }
                        )
                    }
                }
            }
        }
    }

    pendingDeleteGame?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGame = null },
            containerColor = colors.surfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.history_delete_title),
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.history_delete_body,
                        target.whitePlayer,
                        target.blackPlayer,
                        resolveHistoryResultLabel(target.result)
                    ),
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteGame(target.id)
                        pendingDeleteGame = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentCoral,
                        contentColor = colors.textPrimary
                    ),
                    modifier = Modifier.testTag("confirm_delete_game_button")
                ) {
                    Text(stringResource(R.string.game_action_delete))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingDeleteGame = null },
                    modifier = Modifier.testTag("cancel_delete_game_button")
                ) {
                    Text(stringResource(R.string.game_action_cancel))
                }
            }
        )
    }
}

private fun resolveHistoryResultLabel(result: GameResult): String = when (result) {
    GameResult.WHITE_WIN -> "1–0"
    GameResult.BLACK_WIN -> "0–1"
    GameResult.DRAW -> "½–½"
    GameResult.IN_PROGRESS -> "…"
    GameResult.ABORTED -> "×"
}

@Composable
private fun HistoryGameCard(
    game: GameSummary,
    onAnalyzeClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val colors = ChessTheme.colors
    val resultLabel = resolveHistoryResultLabel(game.result)
    ChessSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAnalyzeClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${game.whitePlayer} vs ${game.blackPlayer}",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${game.timeControlLabel} • ${stringResource(R.string.history_moves_count, game.moveCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.accentGreenContainer
                ) {
                    Text(
                        text = resultLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                        color = colors.accentGreen,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("delete_history_game_${game.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.cd_delete_game),
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
