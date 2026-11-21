package com.example.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.core.database.dao.AppSettingsDao
import com.example.core.database.dao.GameDao
import com.example.core.database.dao.MoveDao
import com.example.core.database.dao.PlayerDao
import com.example.core.database.entity.AppSettingsEntity
import com.example.core.database.entity.GameEntity
import com.example.core.database.entity.MoveEntity
import com.example.core.database.entity.PlayerEntity

@Database(
    entities = [
        GameEntity::class,
        MoveEntity::class,
        PlayerEntity::class,
        AppSettingsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ChessDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun moveDao(): MoveDao
    abstract fun playerDao(): PlayerDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        const val DATABASE_NAME = "chesslan_local.db"
    }
}
