package com.example.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "avatar_path")
    val avatarPath: String? = null
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val theme: String,
    @ColumnInfo(name = "board_theme")
    val boardTheme: String,
    @ColumnInfo(name = "piece_set")
    val pieceSet: String,
    @ColumnInfo(name = "sound_enabled")
    val soundEnabled: Boolean,
    @ColumnInfo(name = "animation_enabled")
    val animationEnabled: Boolean,
    @ColumnInfo(name = "player_name")
    val playerName: String
)
