package com.example.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized Design Tokens for spacing, corner radii, strokes, touch targets, and motion.
 * Strictly aligned to an 8.dp spatial grid.
 */
@Immutable
data class ChessSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val screenHorizontal: Dp = 16.dp,
    val sectionGap: Dp = 20.dp
)

@Immutable
data class ChessRadii(
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val pill: Dp = 999.dp
)

@Immutable
data class ChessExtendedColors(
    val backgroundSecondary: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val accentGreen: Color,
    val accentGreenBevel: Color,
    val accentGreenContainer: Color,
    val accentBlue: Color,
    val accentBlueContainer: Color,
    val accentAmber: Color,
    val accentAmberContainer: Color,
    val accentCoral: Color,
    val accentCoralContainer: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val squareLight: Color,
    val squareDark: Color
)

object ChessMotion {
    const val FAST_MS: Int = 150
    const val STANDARD_MS: Int = 240
    const val EMPHASIZED_MS: Int = 320
    const val MOVE_ANIMATION_MS: Int = 165
    const val SNAP_BACK_ANIMATION_MS: Int = 140
    const val FLIP_ANIMATION_MS: Int = 180
}

object ChessDimensions {
    val MinTouchTarget: Dp = 48.dp
    val PrimaryButtonHeight: Dp = 56.dp
    val SecondaryButtonHeight: Dp = 50.dp
    val MaxContentWidth: Dp = 640.dp
    val CardBorderWidth: Dp = 1.dp
}
