package com.example.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "moves",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["game_id"])]
)
data class MoveEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "game_id")
    val gameId: Long,
    @ColumnInfo(name = "move_number")
    val moveNumber: Int,
    val color: String,
    val san: String,
    val uci: String,
    @ColumnInfo(name = "fen_after")
    val fenAfter: String,
    val timestamp: Long
)
