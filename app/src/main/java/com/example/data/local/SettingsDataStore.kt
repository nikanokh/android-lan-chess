package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.BoardTheme
import com.example.domain.model.PieceSet
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chesslan_settings"
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val PLAYER_NAME = stringPreferencesKey("player_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BOARD_THEME = stringPreferencesKey("board_theme")
        val PIECE_SET = stringPreferencesKey("piece_set")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val ANIMATION_ENABLED = booleanPreferencesKey("animation_enabled")
        val SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
        val HIGHLIGHT_LEGAL_MOVES = booleanPreferencesKey("highlight_legal_moves")
        val HIGHLIGHT_LAST_MOVE = booleanPreferencesKey("highlight_last_move")
        val CONFIRM_RESIGN = booleanPreferencesKey("confirm_resign")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val PREMOVES_ENABLED = booleanPreferencesKey("premoves_enabled")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            AppSettings(
                playerName = prefs[Keys.PLAYER_NAME]?.takeIf { it.isNotBlank() } ?: "Игрок",
                themeMode = prefs[Keys.THEME_MODE]
                    ?.let { name -> AppThemeMode.entries.firstOrNull { it.name == name } }
                    ?: AppThemeMode.DARK,
                boardTheme = prefs[Keys.BOARD_THEME]
                    ?.let { name -> BoardTheme.entries.firstOrNull { it.name == name } }
                    ?: BoardTheme.CLASSIC_GREEN,
                pieceSet = prefs[Keys.PIECE_SET]
                    ?.let { name -> PieceSet.entries.firstOrNull { it.name == name } }
                    ?: PieceSet.NEO,
                soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
                animationEnabled = prefs[Keys.ANIMATION_ENABLED] ?: true,
                showCoordinates = prefs[Keys.SHOW_COORDINATES] ?: true,
                highlightLegalMoves = prefs[Keys.HIGHLIGHT_LEGAL_MOVES] ?: true,
                highlightLastMove = prefs[Keys.HIGHLIGHT_LAST_MOVE] ?: true,
                confirmResign = prefs[Keys.CONFIRM_RESIGN] ?: true,
                vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
                premovesEnabled = prefs[Keys.PREMOVES_ENABLED] ?: false
            )
        }

    suspend fun updatePlayerName(name: String) {
        val trimmed = name.trim()
        context.settingsDataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.PLAYER_NAME)
            } else {
                prefs[Keys.PLAYER_NAME] = trimmed
            }
        }
    }

    suspend fun updateBoardTheme(theme: BoardTheme) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.BOARD_THEME] = theme.name }
    }

    suspend fun updatePieceSet(pieceSet: PieceSet) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.PIECE_SET] = pieceSet.name }
    }

    suspend fun updateSoundEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun updateAnimationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.ANIMATION_ENABLED] = enabled }
    }

    suspend fun updateShowCoordinates(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.SHOW_COORDINATES] = enabled }
    }

    suspend fun updateHighlightLegalMoves(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.HIGHLIGHT_LEGAL_MOVES] = enabled }
    }

    suspend fun updateHighlightLastMove(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.HIGHLIGHT_LAST_MOVE] = enabled }
    }

    suspend fun updateConfirmResign(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.CONFIRM_RESIGN] = enabled }
    }

    suspend fun updateVibrationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.VIBRATION_ENABLED] = enabled }
    }

    suspend fun updatePremovesEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.PREMOVES_ENABLED] = enabled }
    }
}
