package com.example.feature.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.components.ChessTopBar
import com.example.core.ui.components.StagePillBadge
import com.example.core.ui.theme.ChessDimensions
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.GameResult
import com.example.domain.usecase.GetGameHistoryUseCase
import com.example.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class ProfileUiState(
    val playerName: String = "Игрок",
    val totalGames: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    observeSettingsUseCase: ObserveSettingsUseCase,
    getGameHistoryUseCase: GetGameHistoryUseCase
) : ViewModel() {
    val uiState: StateFlow<ProfileUiState> = combine(
        observeSettingsUseCase(),
        getGameHistoryUseCase()
    ) { settings, games ->
        val wins = games.count { game ->
            (game.whitePlayer == settings.playerName && game.result == GameResult.WHITE_WIN) ||
                (game.blackPlayer == settings.playerName && game.result == GameResult.BLACK_WIN)
        }
        val draws = games.count { it.result == GameResult.DRAW }
        ProfileUiState(
            playerName = settings.playerName,
            totalGames = games.size,
            wins = wins,
            draws = draws
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState()
    )
}

@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChessTopBar(
                title = stringResource(R.string.profile_title),
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = ChessDimensions.MaxContentWidth)
                    .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                ChessSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.accentGreenContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.piece_wk),
                                contentDescription = null,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = uiState.playerName,
                                style = MaterialTheme.typography.headlineSmall,
                                color = colors.textPrimary
                            )
                            StagePillBadge(text = stringResource(R.string.profile_local_badge))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    StatTile(
                        label = stringResource(R.string.profile_games_played),
                        value = uiState.totalGames.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.profile_wins),
                        value = uiState.wins.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.profile_draws),
                        value = uiState.draws.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    ChessSurfaceCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = colors.accentGreen
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}
