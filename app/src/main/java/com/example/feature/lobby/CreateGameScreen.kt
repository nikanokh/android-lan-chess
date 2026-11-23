package com.example.feature.lobby

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.ui.components.ChessPrimaryButton
import com.example.core.ui.components.ChessTopBar
import com.example.core.ui.components.SectionHeader
import com.example.core.ui.theme.ChessDimensions
import com.example.core.ui.theme.ChessTheme
import com.example.core.network.ConnectionType
import com.example.domain.model.SideSelection
import com.example.domain.model.TimeControl
import com.example.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CreateGameViewModel @Inject constructor(
    observeSettingsUseCase: ObserveSettingsUseCase
) : ViewModel() {
    val defaultPlayerName: StateFlow<String?> = observeSettingsUseCase()
        .map { it.playerName }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )
}

@Composable
fun CreateGameScreen(
    onNavigateBack: (() -> Unit)?,
    onCreateRoom: (timeControlId: String, side: SideSelection, playerName: String, connectionType: ConnectionType) -> Unit,
    viewModel: CreateGameViewModel = hiltViewModel()
) {
    val defaultName by viewModel.defaultPlayerName.collectAsStateWithLifecycle()
    var selectedTimeControlId by rememberSaveable { mutableStateOf(TimeControl.RAPID_10_0.id) }
    var selectedSide by rememberSaveable { mutableStateOf(SideSelection.RANDOM) }
    var selectedConnectionType by rememberSaveable { mutableStateOf(ConnectionType.WIFI_LAN) }
    var playerName by rememberSaveable { mutableStateOf("") }
    var hasSyncedDefaultName by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(defaultName) {
        if (defaultName != null && !hasSyncedDefaultName) {
            playerName = defaultName.orEmpty()
            hasSyncedDefaultName = true
        }
    }

    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChessTopBar(
                title = stringResource(R.string.create_game_title),
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
                item(key = "player_name") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.create_game_player_name))
                        OutlinedTextField(
                            value = playerName,
                            onValueChange = {
                                hasSyncedDefaultName = true
                                playerName = it
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create_game_player_name_input"),
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

                item(key = "side_picker") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.create_game_side_title))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            SideOptionCard(
                                label = stringResource(R.string.side_white),
                                selected = selectedSide == SideSelection.WHITE,
                                onClick = { selectedSide = SideSelection.WHITE },
                                testTag = "side_option_white",
                                modifier = Modifier.weight(1f)
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.piece_wk),
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            SideOptionCard(
                                label = stringResource(R.string.side_random),
                                selected = selectedSide == SideSelection.RANDOM,
                                onClick = { selectedSide = SideSelection.RANDOM },
                                testTag = "side_option_random",
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = null,
                                    tint = colors.accentGreen,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            SideOptionCard(
                                label = stringResource(R.string.side_black),
                                selected = selectedSide == SideSelection.BLACK,
                                onClick = { selectedSide = SideSelection.BLACK },
                                testTag = "side_option_black",
                                modifier = Modifier.weight(1f)
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.piece_bk),
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }

                item(key = "time_controls") {
                    TimeControlPickerSection(
                        selectedTimeControlId = selectedTimeControlId,
                        onSelectTimeControl = { selectedTimeControlId = it }
                    )
                }

                item(key = "connection_mode") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SectionHeader(title = stringResource(R.string.create_game_connection_mode))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ModeToggleChip(
                                label = stringResource(R.string.connection_wifi_lan),
                                icon = Icons.Rounded.Wifi,
                                selected = selectedConnectionType == ConnectionType.WIFI_LAN,
                                onClick = { selectedConnectionType = ConnectionType.WIFI_LAN },
                                testTag = "create_mode_wifi_lan",
                                modifier = Modifier.weight(1f)
                            )
                            ModeToggleChip(
                                label = stringResource(R.string.connection_hotspot),
                                icon = Icons.Rounded.WifiTethering,
                                selected = selectedConnectionType == ConnectionType.HOTSPOT,
                                onClick = { selectedConnectionType = ConnectionType.HOTSPOT },
                                testTag = "create_mode_hotspot",
                                modifier = Modifier.weight(1f)
                            )
                            ModeToggleChip(
                                label = stringResource(R.string.connection_wifi_direct),
                                icon = Icons.Rounded.Devices,
                                selected = selectedConnectionType == ConnectionType.WIFI_DIRECT,
                                onClick = { selectedConnectionType = ConnectionType.WIFI_DIRECT },
                                testTag = "create_mode_wifi_direct",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                item(key = "submit") {
                    Spacer(modifier = Modifier.height(4.dp))
                    ChessPrimaryButton(
                        text = stringResource(R.string.create_game_open_lobby),
                        leadingIcon = Icons.Rounded.PlayArrow,
                        testTag = "create_game_open_lobby_button",
                        onClick = {
                            onCreateRoom(
                                selectedTimeControlId,
                                selectedSide,
                                playerName.trim().ifEmpty { defaultName ?: "Игрок" },
                                selectedConnectionType
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SideOptionCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    iconContent: @Composable () -> Unit
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(84.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(ChessTheme.radii.sm),
        color = if (selected) colors.accentGreenContainer else colors.surfaceCard,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) colors.accentGreen else colors.borderSubtle
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            iconContent()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.accentGreen else colors.textPrimary
            )
        }
    }
}
