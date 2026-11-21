package com.example.domain.repository

import com.example.domain.model.AppSettings
import com.example.domain.model.BoardTheme
import com.example.domain.model.PieceSet
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updatePlayerName(name: String)
    suspend fun updateBoardTheme(theme: BoardTheme)
    suspend fun updatePieceSet(pieceSet: PieceSet)
    suspend fun updateSoundEnabled(enabled: Boolean)
    suspend fun updateAnimationEnabled(enabled: Boolean)
    suspend fun updateShowCoordinates(enabled: Boolean)
    suspend fun updateHighlightLegalMoves(enabled: Boolean)
    suspend fun updateHighlightLastMove(enabled: Boolean)
    suspend fun updateConfirmResign(enabled: Boolean)
    suspend fun updateVibrationEnabled(enabled: Boolean)
}
