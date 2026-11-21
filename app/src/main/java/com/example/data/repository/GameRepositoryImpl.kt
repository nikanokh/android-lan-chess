package com.example.data.repository

import com.example.core.database.dao.GameDao
import com.example.data.local.mapper.toDomain
import com.example.domain.model.GameSummary
import com.example.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepositoryImpl @Inject constructor(
    private val gameDao: GameDao
) : GameRepository {

    override fun observeGameHistory(): Flow<List<GameSummary>> =
        gameDao.observeAllGames().map { rows -> rows.map { it.toDomain() } }

    override fun observeLatestGame(): Flow<GameSummary?> =
        gameDao.observeLatestGame().map { it?.toDomain() }

    override suspend fun getGameById(gameId: Long): GameSummary? =
        gameDao.getGameById(gameId)?.toDomain()

    override suspend fun deleteGame(gameId: Long) {
        gameDao.deleteGameById(gameId)
    }
}
