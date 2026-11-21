package com.example.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.core.database.entity.AppSettingsEntity
import com.example.core.database.entity.GameEntity
import com.example.core.database.entity.MoveEntity
import com.example.core.database.entity.PlayerEntity
import kotlinx.coroutines.flow.Flow

data class GameWithMoveCount(
    @Embedded val game: GameEntity,
    @ColumnInfo(name = "move_count") val moveCount: Int
)

@Dao
interface GameDao {
    @Query(
        """
        SELECT games.*, COUNT(moves.id) AS move_count
        FROM games
        LEFT JOIN moves ON moves.game_id = games.id
        GROUP BY games.id
        ORDER BY games.created_at DESC
        """
    )
    fun observeAllGames(): Flow<List<GameWithMoveCount>>

    @Query(
        """
        SELECT games.*, COUNT(moves.id) AS move_count
        FROM games
        LEFT JOIN moves ON moves.game_id = games.id
        GROUP BY games.id
        ORDER BY games.created_at DESC
        LIMIT 1
        """
    )
    fun observeLatestGame(): Flow<GameWithMoveCount?>

    @Query(
        """
        SELECT games.*, COUNT(moves.id) AS move_count
        FROM games
        LEFT JOIN moves ON moves.game_id = games.id
        WHERE games.id = :gameId
        GROUP BY games.id
        """
    )
    suspend fun getGameById(gameId: Long): GameWithMoveCount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGame(game: GameEntity): Long

    @Update
    suspend fun updateGame(game: GameEntity)

    @Query("DELETE FROM games WHERE id = :gameId")
    suspend fun deleteGameById(gameId: Long)
}

@Dao
interface MoveDao {
    @Query("SELECT * FROM moves WHERE game_id = :gameId ORDER BY move_number ASC, id ASC")
    fun observeMovesForGame(gameId: Long): Flow<List<MoveEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMove(move: MoveEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMoves(moves: List<MoveEntity>)
}

@Dao
interface PlayerDao {
    @Query("SELECT * FROM players ORDER BY id ASC")
    fun observePlayers(): Flow<List<PlayerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayer(player: PlayerEntity): Long
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettingsEntity)
}
