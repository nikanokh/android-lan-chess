package com.example.data.repository

import com.example.data.local.SettingsDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.BoardTheme
import com.example.domain.model.PieceSet
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : SettingsRepository {

    override fun observeSettings(): Flow<AppSettings> = settingsDataStore.settingsFlow

    override suspend fun updatePlayerName(name: String) =
        settingsDataStore.updatePlayerName(name)

    override suspend fun updateBoardTheme(theme: BoardTheme) =
        settingsDataStore.updateBoardTheme(theme)

    override suspend fun updatePieceSet(pieceSet: PieceSet) =
        settingsDataStore.updatePieceSet(pieceSet)

    override suspend fun updateSoundEnabled(enabled: Boolean) =
        settingsDataStore.updateSoundEnabled(enabled)

    override suspend fun updateAnimationEnabled(enabled: Boolean) =
        settingsDataStore.updateAnimationEnabled(enabled)

    override suspend fun updateShowCoordinates(enabled: Boolean) =
        settingsDataStore.updateShowCoordinates(enabled)

    override suspend fun updateHighlightLegalMoves(enabled: Boolean) =
        settingsDataStore.updateHighlightLegalMoves(enabled)

    override suspend fun updateHighlightLastMove(enabled: Boolean) =
        settingsDataStore.updateHighlightLastMove(enabled)

    override suspend fun updateConfirmResign(enabled: Boolean) =
        settingsDataStore.updateConfirmResign(enabled)

    override suspend fun updateVibrationEnabled(enabled: Boolean) =
        settingsDataStore.updateVibrationEnabled(enabled)
}
