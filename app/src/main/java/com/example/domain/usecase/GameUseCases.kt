package com.example.domain.usecase

import com.example.domain.model.AppSettings
import com.example.domain.model.BoardTheme
import com.example.domain.model.GameSummary
import com.example.domain.model.PieceSet
import com.example.domain.repository.GameRepository
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetLatestGameUseCase @Inject constructor(
    private val gameRepository: GameRepository
) {
    operator fun invoke(): Flow<GameSummary?> = gameRepository.observeLatestGame()
}

class GetGameHistoryUseCase @Inject constructor(
    private val gameRepository: GameRepository
) {
    operator fun invoke(): Flow<List<GameSummary>> = gameRepository.observeGameHistory()
}

class ObserveSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<AppSettings> = settingsRepository.observeSettings()
}

class UpdateSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend fun setPlayerName(name: String) = settingsRepository.updatePlayerName(name)
    suspend fun setBoardTheme(theme: BoardTheme) = settingsRepository.updateBoardTheme(theme)
    suspend fun setPieceSet(pieceSet: PieceSet) = settingsRepository.updatePieceSet(pieceSet)
    suspend fun setSoundEnabled(enabled: Boolean) = settingsRepository.updateSoundEnabled(enabled)
    suspend fun setAnimationEnabled(enabled: Boolean) = settingsRepository.updateAnimationEnabled(enabled)
    suspend fun setShowCoordinates(enabled: Boolean) = settingsRepository.updateShowCoordinates(enabled)
    suspend fun setHighlightLegalMoves(enabled: Boolean) = settingsRepository.updateHighlightLegalMoves(enabled)
    suspend fun setHighlightLastMove(enabled: Boolean) = settingsRepository.updateHighlightLastMove(enabled)
    suspend fun setConfirmResign(enabled: Boolean) = settingsRepository.updateConfirmResign(enabled)
    suspend fun setVibrationEnabled(enabled: Boolean) = settingsRepository.updateVibrationEnabled(enabled)
}
