package com.example.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.components.ChessTopBar
import com.example.core.ui.components.SectionHeader
import com.example.core.ui.theme.ChessDimensions
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.toSquareColors
import com.example.domain.model.AppSettings
import com.example.domain.model.BoardTheme
import com.example.domain.usecase.ObserveSettingsUseCase
import com.example.domain.usecase.UpdateSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeSettingsUseCase: ObserveSettingsUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase
) : ViewModel() {

    val uiState: StateFlow<AppSettings> = observeSettingsUseCase().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    val persistedPlayerName: StateFlow<String?> = observeSettingsUseCase()
        .map { it.playerName }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun onPlayerNameChanged(name: String) {
        viewModelScope.launch { updateSettingsUseCase.setPlayerName(name) }
    }

    fun onBoardThemeSelected(theme: BoardTheme) {
        viewModelScope.launch { updateSettingsUseCase.setBoardTheme(theme) }
    }

    fun onPieceSetSelected(pieceSet: com.example.domain.model.PieceSet) {
        viewModelScope.launch { updateSettingsUseCase.setPieceSet(pieceSet) }
    }

    fun onSoundToggle(enabled: Boolean) {
        viewModelScope.launch { updateSettingsUseCase.setSoundEnabled(enabled) }
    }

    fun onAnimationToggle(enabled: Boolean) {
        viewModelScope.launch { updateSettingsUseCase.setAnimationEnabled(enabled) }
    }

    fun onCoordinatesToggle(enabled: Boolean) {
        viewModelScope.launch { updateSettingsUseCase.setShowCoordinates(enabled) }
    }

    fun onLegalMovesToggle(enabled: Boolean) {
        viewModelScope.launch { updateSettingsUseCase.setHighlightLegalMoves(enabled) }
    }

    fun onLastMoveToggle(enabled: Boolean) {
        viewModelScope.launch { updateSettingsUseCase.setHighlightLastMove(enabled) }
    }

    fun onConfirmResignToggle(enabled: Boolean) {
        viewModelScope.launch { updateSettingsUseCase.setConfirmResign(enabled) }
    }

    fun onVibrationToggle(enabled: Boolean) {
        viewModelScope.launch { updateSettingsUseCase.setVibrationEnabled(enabled) }
    }

    fun onPremovesToggle(enabled: Boolean) {
        viewModelScope.launch { updateSettingsUseCase.setPremovesEnabled(enabled) }
    }
}

@Composable
fun SettingsScreen(
    onNavigateBack: (() -> Unit)?,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.uiState.collectAsStateWithLifecycle()
    val persistedName by viewModel.persistedPlayerName.collectAsStateWithLifecycle()
    var nameInput by rememberSaveable { mutableStateOf("") }
    var hasSyncedInitialName by rememberSaveable { mutableStateOf(false) }
    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing

    LaunchedEffect(persistedName) {
        if (persistedName != null && !hasSyncedInitialName) {
            nameInput = persistedName.orEmpty()
            hasSyncedInitialName = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChessTopBar(
                title = stringResource(R.string.settings_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = ChessDimensions.MaxContentWidth),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                item(key = "player") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.settings_player_section))
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { updated ->
                                hasSyncedInitialName = true
                                nameInput = updated
                                viewModel.onPlayerNameChanged(updated)
                            },
                            label = { Text(stringResource(R.string.settings_player_name_label)) },
                            trailingIcon = {
                                if (hasSyncedInitialName && nameInput.trim().isNotEmpty() && nameInput.trim() == persistedName) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = colors.accentGreen
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_player_name_input"),
                            shape = RoundedCornerShape(ChessTheme.radii.sm),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentGreen,
                                unfocusedBorderColor = colors.borderSubtle,
                                focusedContainerColor = colors.surfaceCard,
                                unfocusedContainerColor = colors.surfaceCard,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            )
                        )
                    }
                }

                item(key = "board_theme") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.settings_board_theme))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BoardTheme.entries.forEach { theme ->
                                val selected = settings.boardTheme == theme
                                val squareColors = theme.toSquareColors()
                                Surface(
                                    onClick = { viewModel.onBoardThemeSelected(theme) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("settings_board_theme_${theme.name.lowercase()}"),
                                    shape = RoundedCornerShape(ChessTheme.radii.sm),
                                    color = if (selected) colors.accentGreenContainer else colors.surfaceCard,
                                    border = BorderStroke(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) colors.accentGreen else colors.borderSubtle
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Canvas(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        ) {
                                            val half = size.width / 2f
                                            drawRect(squareColors.light, Offset(0f, 0f), Size(half, half))
                                            drawRect(squareColors.dark, Offset(half, 0f), Size(half, half))
                                            drawRect(squareColors.dark, Offset(0f, half), Size(half, half))
                                            drawRect(squareColors.light, Offset(half, half), Size(half, half))
                                        }
                                        Text(
                                            text = theme.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (selected) colors.accentGreen else colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "piece_set") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = "Набор фигур")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.example.domain.model.PieceSet.entries.forEach { pieceSet ->
                                val selected = settings.pieceSet == pieceSet
                                Surface(
                                    onClick = { viewModel.onPieceSetSelected(pieceSet) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_piece_set_${pieceSet.name.lowercase()}"),
                                    shape = RoundedCornerShape(ChessTheme.radii.sm),
                                    color = if (selected) colors.accentGreenContainer else colors.surfaceCard,
                                    border = BorderStroke(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) colors.accentGreen else colors.borderSubtle
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        androidx.compose.foundation.Image(
                                            painter = androidx.compose.ui.res.painterResource(R.drawable.piece_wn),
                                            contentDescription = null,
                                            colorFilter = com.example.core.ui.board.ChessPieceResources.colorFilterFor(pieceSet),
                                            modifier = Modifier.size(30.dp)
                                        )
                                        Text(
                                            text = pieceSet.displayName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) colors.accentGreen else colors.textPrimary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "gameplay") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.settings_gameplay_section))
                        ChessSurfaceCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_sound),
                                checked = settings.soundEnabled,
                                onCheckedChange = viewModel::onSoundToggle,
                                testTag = "settings_toggle_sound"
                            )
                            SettingsDivider()
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_animations),
                                checked = settings.animationEnabled,
                                onCheckedChange = viewModel::onAnimationToggle,
                                testTag = "settings_toggle_animations"
                            )
                            SettingsDivider()
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_coordinates),
                                checked = settings.showCoordinates,
                                onCheckedChange = viewModel::onCoordinatesToggle,
                                testTag = "settings_toggle_coordinates"
                            )
                            SettingsDivider()
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_legal_moves),
                                checked = settings.highlightLegalMoves,
                                onCheckedChange = viewModel::onLegalMovesToggle,
                                testTag = "settings_toggle_legal_moves"
                            )
                            SettingsDivider()
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_last_move),
                                checked = settings.highlightLastMove,
                                onCheckedChange = viewModel::onLastMoveToggle,
                                testTag = "settings_toggle_last_move"
                            )
                            SettingsDivider()
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_confirm_resign),
                                checked = settings.confirmResign,
                                onCheckedChange = viewModel::onConfirmResignToggle,
                                testTag = "settings_toggle_confirm_resign"
                            )
                            SettingsDivider()
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_vibration),
                                checked = settings.vibrationEnabled,
                                onCheckedChange = viewModel::onVibrationToggle,
                                testTag = "settings_toggle_vibration"
                            )
                            SettingsDivider()
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_premoves),
                                checked = settings.premovesEnabled,
                                onCheckedChange = viewModel::onPremovesToggle,
                                testTag = "settings_toggle_premoves"
                            )
                        }
                    }
                }

                item(key = "footer") {
                    Text(
                        text = stringResource(R.string.settings_saved_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    val colors = ChessTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.textPrimary,
                checkedTrackColor = colors.accentGreen,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.backgroundSecondary,
                uncheckedBorderColor = colors.borderSubtle
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = ChessTheme.colors.borderSubtle.copy(alpha = 0.5f))
}
