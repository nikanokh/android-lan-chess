package com.example.feature.home

import androidx.compose.runtime.Immutable
import com.example.domain.model.BoardTheme
import com.example.domain.model.GameSummary

@Immutable
data class HomeUiState(
    val playerName: String = "Игрок",
    val boardTheme: BoardTheme = BoardTheme.CLASSIC_GREEN,
    val latestGame: GameSummary? = null,
    val totalGamesCount: Int = 0
)
