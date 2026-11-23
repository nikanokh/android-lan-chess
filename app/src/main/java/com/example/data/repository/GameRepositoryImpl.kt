package com.example.data.repository

import com.example.core.database.dao.GameDao
import com.example.core.database.dao.MoveDao
import com.example.core.database.entity.GameEntity
import com.example.core.database.entity.MoveEntity
import com.example.data.local.mapper.toDomain
import com.example.domain.model.CompletedGameRecord
import com.example.domain.model.GameSummary
import com.example.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameRepositoryImpl private constructor(
    private val gameDao: GameDao,
    private val moveDao: MoveDao?,
    @Suppress("UNUSED_PARAMETER") internalMarker: Unit
) : GameRepository {

    @Inject
    constructor(gameDao: GameDao, moveDao: MoveDao) : this(gameDao, moveDao, Unit)

    constructor(gameDao: GameDao) : this(gameDao, null, Unit)

    override fun observeGameHistory(): Flow<List<GameSummary>> =
        gameDao.observeAllGames().map { rows -> rows.map { it.toDomain() } }

    override fun observeLatestGame(): Flow<GameSummary?> =
        gameDao.observeLatestGame().map { it?.toDomain() }

    override suspend fun getGameById(gameId: Long): GameSummary? =
        gameDao.getGameById(gameId)?.toDomain()

    override suspend fun saveCompletedGame(record: CompletedGameRecord): Long {
        val gameId = gameDao.insertGame(
            GameEntity(
                gameUuid = record.gameUuid,
                createdAt = record.createdAt,
                finishedAt = record.finishedAt,
                whitePlayer = record.whitePlayer,
                blackPlayer = record.blackPlayer,
                result = record.result.name,
                timeControl = record.timeControlLabel,
                initialFen = record.initialFen,
                finalFen = record.finalFen,
                pgn = record.pgn,
                connectionType = record.connectionType.name
            )
        )
        if (moveDao != null && record.moves.isNotEmpty()) {
            moveDao.insertMoves(
                record.moves.map { move ->
                    MoveEntity(
                        gameId = gameId,
                        moveNumber = move.moveNumber,
                        color = move.color.name,
                        san = move.san,
                        uci = move.uci,
                        fenAfter = move.fenAfter,
                        timestamp = move.timestamp
                    )
                }
            )
        }
        return gameId
    }

    override suspend fun deleteGame(gameId: Long) {
        gameDao.deleteGameById(gameId)
    }
}
