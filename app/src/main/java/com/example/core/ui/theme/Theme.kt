package com.example.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val DarkChessColorScheme = darkColorScheme(
    primary = ChessPalette.AccentGreen,
    onPrimary = ChessPalette.TextOnAccent,
    primaryContainer = ChessPalette.AccentGreenContainer,
    onPrimaryContainer = ChessPalette.AccentGreenBright,
    secondary = ChessPalette.AccentBlue,
    onSecondary = ChessPalette.BackgroundPrimary,
    secondaryContainer = ChessPalette.AccentBlueContainer,
    onSecondaryContainer = ChessPalette.TextPrimary,
    tertiary = ChessPalette.AccentAmber,
    onTertiary = ChessPalette.BackgroundPrimary,
    tertiaryContainer = ChessPalette.AccentAmberContainer,
    onTertiaryContainer = ChessPalette.TextPrimary,
    background = ChessPalette.BackgroundPrimary,
    onBackground = ChessPalette.TextPrimary,
    surface = ChessPalette.BackgroundSecondary,
    onSurface = ChessPalette.TextPrimary,
    surfaceVariant = ChessPalette.SurfaceCard,
    onSurfaceVariant = ChessPalette.TextSecondary,
    surfaceContainerLowest = ChessPalette.BackgroundPrimary,
    surfaceContainerLow = ChessPalette.BackgroundSecondary,
    surfaceContainer = ChessPalette.SurfaceCard,
    surfaceContainerHigh = ChessPalette.SurfaceElevated,
    outline = ChessPalette.BorderSubtle,
    outlineVariant = ChessPalette.Divider,
    error = ChessPalette.AccentCoral,
    onError = ChessPalette.TextPrimary,
    errorContainer = ChessPalette.AccentCoralContainer,
    onErrorContainer = ChessPalette.TextPrimary
)

private val LightChessColorScheme = lightColorScheme(
    primary = ChessPalette.AccentGreenDark,
    onPrimary = ChessPalette.LightSurfaceCard,
    primaryContainer = ChessPalette.SquareLightClassic,
    onPrimaryContainer = ChessPalette.LightTextPrimary,
    secondary = ChessPalette.AccentBlue,
    onSecondary = ChessPalette.LightSurfaceCard,
    background = ChessPalette.LightBackgroundPrimary,
    onBackground = ChessPalette.LightTextPrimary,
    surface = ChessPalette.LightBackgroundSecondary,
    onSurface = ChessPalette.LightTextPrimary,
    surfaceVariant = ChessPalette.LightSurfaceCard,
    onSurfaceVariant = ChessPalette.LightTextSecondary,
    outline = ChessPalette.LightBorderSubtle,
    error = ChessPalette.AccentCoral,
    onError = ChessPalette.LightSurfaceCard
)

private val DarkExtendedColors = ChessExtendedColors(
    backgroundSecondary = ChessPalette.BackgroundSecondary,
    surfaceCard = ChessPalette.SurfaceCard,
    surfaceElevated = ChessPalette.SurfaceElevated,
    borderSubtle = ChessPalette.BorderSubtle,
    accentGreen = ChessPalette.AccentGreen,
    accentGreenBevel = ChessPalette.AccentGreenDark,
    accentGreenContainer = ChessPalette.AccentGreenContainer,
    accentBlue = ChessPalette.AccentBlue,
    accentBlueContainer = ChessPalette.AccentBlueContainer,
    accentAmber = ChessPalette.AccentAmber,
    accentAmberContainer = ChessPalette.AccentAmberContainer,
    accentCoral = ChessPalette.AccentCoral,
    accentCoralContainer = ChessPalette.AccentCoralContainer,
    textPrimary = ChessPalette.TextPrimary,
    textSecondary = ChessPalette.TextSecondary,
    textMuted = ChessPalette.TextMuted,
    squareLight = ChessPalette.SquareLightClassic,
    squareDark = ChessPalette.SquareDarkClassic
)

private val LightExtendedColors = ChessExtendedColors(
    backgroundSecondary = ChessPalette.LightBackgroundSecondary,
    surfaceCard = ChessPalette.LightSurfaceCard,
    surfaceElevated = ChessPalette.LightSurfaceCard,
    borderSubtle = ChessPalette.LightBorderSubtle,
    accentGreen = ChessPalette.AccentGreenDark,
    accentGreenBevel = ChessPalette.SquareDarkClassic,
    accentGreenContainer = ChessPalette.SquareLightClassic,
    accentBlue = ChessPalette.AccentBlue,
    accentBlueContainer = ChessPalette.LightBackgroundSecondary,
    accentAmber = ChessPalette.AccentAmber,
    accentAmberContainer = ChessPalette.LightBackgroundSecondary,
    accentCoral = ChessPalette.AccentCoral,
    accentCoralContainer = ChessPalette.LightBackgroundSecondary,
    textPrimary = ChessPalette.LightTextPrimary,
    textSecondary = ChessPalette.LightTextSecondary,
    textMuted = ChessPalette.LightTextSecondary,
    squareLight = ChessPalette.SquareLightClassic,
    squareDark = ChessPalette.SquareDarkClassic
)

val LocalChessColors = staticCompositionLocalOf { DarkExtendedColors }
val LocalChessSpacing = staticCompositionLocalOf { ChessSpacing() }
val LocalChessRadii = staticCompositionLocalOf { ChessRadii() }

object ChessTheme {
    val colors: ChessExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalChessColors.current

    val spacing: ChessSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalChessSpacing.current

    val radii: ChessRadii
        @Composable
        @ReadOnlyComposable
        get() = LocalChessRadii.current
}

@Composable
fun ChessLanTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkChessColorScheme else LightChessColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalChessColors provides extendedColors,
        LocalChessSpacing provides ChessSpacing(),
        LocalChessRadii provides ChessRadii()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ChessTypography,
            shapes = ChessShapes,
            content = content
        )
    }
}
