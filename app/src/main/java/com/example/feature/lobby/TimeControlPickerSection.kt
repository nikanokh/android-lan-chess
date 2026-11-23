package com.example.feature.lobby

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ui.components.ChessSurfaceCard
import com.example.core.ui.components.SectionHeader
import com.example.core.ui.theme.ChessTheme
import com.example.core.ui.theme.MonoFontFamily
import com.example.domain.model.TimeControl
import com.example.domain.model.TimeControlCategory
import kotlin.math.roundToInt

/**
 * Organized Chess.com-style time control selector grouped into Bullet, Blitz, and Rapid/Classical
 * directions with custom icons, expanded Classical options, and a manual custom time picker.
 */
@Composable
fun TimeControlPickerSection(
    selectedTimeControlId: String,
    onSelectTimeControl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    val spacing = ChessTheme.spacing
    val activeControl = TimeControl.fromId(selectedTimeControlId)
    val isCustomSelected = TimeControl.AllPresets.none { it.id == activeControl.id }
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }

    if (showCustomDialog) {
        val initialMinutes = if (activeControl.isUnlimited) {
            10
        } else {
            (activeControl.initialSeconds / 60L).toInt().coerceIn(1, 180)
        }
        val initialIncrement = activeControl.incrementSeconds.toInt().coerceIn(0, 60)
        CustomTimeControlDialog(
            initialMinutes = initialMinutes,
            initialIncrement = initialIncrement,
            onDismiss = { showCustomDialog = false },
            onConfirm = { minutes, increment ->
                showCustomDialog = false
                onSelectTimeControl(TimeControl.custom(minutes, increment).id)
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        SectionHeader(
            title = stringResource(R.string.create_game_time_control),
            trailingText = if (activeControl.isUnlimited) {
                stringResource(R.string.time_control_unlimited)
            } else {
                activeControl.label
            }
        )

        ChessSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 1. Пуля (Bullet)
                TimeControlCategoryGroup(
                    title = stringResource(R.string.time_cat_bullet),
                    iconRes = R.drawable.ic_time_bullet,
                    presets = TimeControl.BulletPresets,
                    selectedId = activeControl.id,
                    onSelect = onSelectTimeControl
                )

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f))

                // 2. Блиц (Blitz)
                TimeControlCategoryGroup(
                    title = stringResource(R.string.time_cat_blitz),
                    iconRes = R.drawable.ic_time_blitz,
                    presets = TimeControl.BlitzPresets,
                    selectedId = activeControl.id,
                    onSelect = onSelectTimeControl
                )

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f))

                // 3. Рапид и Классика (Rapid & Classical)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeControlCategoryGroup(
                        title = stringResource(R.string.time_cat_rapid),
                        iconRes = R.drawable.ic_time_rapid,
                        presets = TimeControl.RapidPresets,
                        selectedId = activeControl.id,
                        onSelect = onSelectTimeControl
                    )

                    Text(
                        text = stringResource(R.string.time_cat_classical_and_unlimited),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )

                    // Classical presets arranged cleanly in 2 balanced rows of 3 equal-width chips
                    val classicalRows = TimeControl.ClassicalPresets.chunked(3)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        classicalRows.forEach { rowPresets ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowPresets.forEach { preset ->
                                    TimeControlChip(
                                        preset = preset,
                                        selected = preset.id == activeControl.id,
                                        onClick = { onSelectTimeControl(preset.id) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f))

                // Manual custom time control trigger
                Surface(
                    onClick = { showCustomDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("time_control_custom_button"),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isCustomSelected) colors.accentGreenContainer else colors.backgroundSecondary,
                    border = BorderStroke(
                        width = if (isCustomSelected) 1.5.dp else 1.dp,
                        color = if (isCustomSelected) colors.accentGreen else colors.borderSubtle
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = if (isCustomSelected) colors.accentGreen else colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.time_control_custom_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isCustomSelected) colors.accentGreen else colors.textPrimary
                            )
                        }
                        Text(
                            text = if (isCustomSelected) {
                                activeControl.label
                            } else {
                                stringResource(R.string.time_control_custom_pick)
                            },
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = if (isCustomSelected) MonoFontFamily else MaterialTheme.typography.labelLarge.fontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = colors.accentGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeControlCategoryGroup(
    title: String,
    @DrawableRes iconRes: Int,
    presets: List<TimeControl>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    val colors = ChessTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                TimeControlChip(
                    preset = preset,
                    selected = preset.id == selectedId,
                    onClick = { onSelect(preset.id) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TimeControlChip(
    preset: TimeControl,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChessTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .testTag("time_control_${preset.id}"),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.accentGreenContainer else colors.backgroundSecondary,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) colors.accentGreen else colors.borderSubtle
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (preset.isUnlimited) "∞" else preset.label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = if (selected) colors.accentGreen else colors.textPrimary,
                maxLines = 1
            )
            if (preset.isUnlimited) {
                Text(
                    text = stringResource(R.string.time_cat_unlimited_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CustomTimeControlDialog(
    initialMinutes: Int,
    initialIncrement: Int,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, incrementSeconds: Int) -> Unit
) {
    val colors = ChessTheme.colors
    var minutes by rememberSaveable { mutableIntStateOf(initialMinutes.coerceIn(1, 180)) }
    var increment by rememberSaveable { mutableIntStateOf(initialIncrement.coerceIn(0, 60)) }
    val preview = TimeControl.custom(minutes, increment)
    val categoryLabel = when (preview.category) {
        TimeControlCategory.BULLET -> stringResource(R.string.time_cat_bullet)
        TimeControlCategory.BLITZ -> stringResource(R.string.time_cat_blitz)
        TimeControlCategory.RAPID -> stringResource(R.string.time_cat_rapid)
        TimeControlCategory.CLASSICAL -> stringResource(R.string.time_cat_classical)
        TimeControlCategory.UNLIMITED -> stringResource(R.string.time_cat_unlimited)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("custom_time_control_dialog"),
        containerColor = colors.surfaceElevated,
        title = {
            Text(
                text = stringResource(R.string.time_control_custom_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.accentGreenContainer,
                    border = BorderStroke(1.dp, colors.accentGreen)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = preview.label,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = colors.accentGreen,
                            modifier = Modifier.testTag("custom_time_preview_label")
                        )
                        Text(
                            text = categoryLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }

                // Minutes control
                CustomStepperSlider(
                    title = stringResource(R.string.time_control_custom_minutes, minutes),
                    value = minutes,
                    valueRange = 1..180,
                    onValueChange = { minutes = it },
                    minusTag = "custom_minutes_minus",
                    plusTag = "custom_minutes_plus"
                )

                // Increment control
                CustomStepperSlider(
                    title = stringResource(R.string.time_control_custom_increment, increment),
                    value = increment,
                    valueRange = 0..60,
                    onValueChange = { increment = it },
                    minusTag = "custom_increment_minus",
                    plusTag = "custom_increment_plus"
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(minutes, increment) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                modifier = Modifier.testTag("custom_time_confirm_button")
            ) {
                Text(stringResource(R.string.time_control_custom_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.game_action_cancel))
            }
        }
    )
}

@Composable
private fun CustomStepperSlider(
    title: String,
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
    minusTag: String,
    plusTag: String
) {
    val colors = ChessTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onValueChange((value - 1).coerceIn(valueRange)) },
                    enabled = value > valueRange.first,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag(minusTag)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = null,
                        tint = colors.textPrimary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = colors.accentGreen,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(34.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onValueChange((value + 1).coerceIn(valueRange)) },
                    enabled = value < valueRange.last,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag(plusTag)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = colors.textPrimary
                    )
                }
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(valueRange)) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = colors.accentGreen,
                activeTrackColor = colors.accentGreen,
                inactiveTrackColor = colors.borderSubtle
            )
        )
    }
}
