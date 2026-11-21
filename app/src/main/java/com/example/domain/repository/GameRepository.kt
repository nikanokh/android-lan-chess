package com.example.domain.repository

import com.example.domain.model.GameSummary
import kotlinx.coroutines.flow.Flow

interface GameRepository {
    fun observeGameHistory(): Flow<List<GameSummary>>
    fun observeLatestGame(): Flow<GameSummary?>
    suspend fun getGameById(gameId: Long): GameSummary?
    suspend fun deleteGame(gameId: Long)
}
