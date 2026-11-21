package com.example.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    indices = [
        Index(value = ["game_uuid"], unique = true),
        Index(value = ["created_at"])
    ]
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "game_uuid")
    val gameUuid: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo(name = "white_player")
    val whitePlayer: String,
    @ColumnInfo(name = "black_player")
    val blackPlayer: String,
    val result: String,
    @ColumnInfo(name = "time_control")
    val timeControl: String,
    @ColumnInfo(name = "initial_fen")
    val initialFen: String,
    @ColumnInfo(name = "final_fen")
    val finalFen: String,
    val pgn: String,
    @ColumnInfo(name = "connection_type")
    val connectionType: String
)
