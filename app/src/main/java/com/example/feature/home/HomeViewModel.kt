package com.example.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.usecase.GetGameHistoryUseCase
import com.example.domain.usecase.GetLatestGameUseCase
import com.example.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeSettingsUseCase: ObserveSettingsUseCase,
    getLatestGameUseCase: GetLatestGameUseCase,
    getGameHistoryUseCase: GetGameHistoryUseCase
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        observeSettingsUseCase(),
        getLatestGameUseCase(),
        getGameHistoryUseCase()
    ) { settings, latestGame, history ->
        HomeUiState(
            playerName = settings.playerName,
            boardTheme = settings.boardTheme,
            latestGame = latestGame,
            totalGamesCount = history.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )
}
