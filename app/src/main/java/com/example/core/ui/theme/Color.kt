package com.example.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.example.domain.model.BoardTheme

/**
 * Centralized color palette for ChessLAN.
 * Matches Section 4 specification with semantic tokens for both dark (default) and future light themes.
 */
object ChessPalette {
    // Core Dark Surfaces
    val BackgroundPrimary = Color(0xFF171A1F)
    val BackgroundSecondary = Color(0xFF222831)
    val SurfaceCard = Color(0xFF292F38)
    val SurfaceElevated = Color(0xFF313844)
    val BorderSubtle = Color(0xFF38404E)
    val Divider = Color(0xFF2E3540)

    // Primary Accent (Chess.com-inspired green)
    val AccentGreen = Color(0xFF81B64C)
    val AccentGreenBright = Color(0xFF95C95F)
    val AccentGreenDark = Color(0xFF5D8A31)
    val AccentGreenContainer = Color(0xFF25351E)

    // Secondary & Semantic Accents
    val AccentBlue = Color(0xFF4F9CF9)
    val AccentBlueContainer = Color(0xFF1D2F45)
    val AccentAmber = Color(0xFFE5A93B)
    val AccentAmberContainer = Color(0xFF3A2F1B)
    val AccentCoral = Color(0xFFE05A47)
    val AccentCoralContainer = Color(0xFF3D2221)
    val AccentTeal = Color(0xFF26B59C)

    // Typography
    val TextPrimary = Color(0xFFF0F0E8)
    val TextSecondary = Color(0xFFA0A6AF)
    val TextMuted = Color(0xFF6F7682)
    val TextOnAccent = Color(0xFF11180A)

    // Classic Board Squares
    val SquareLightClassic = Color(0xFFEEEED2)
    val SquareDarkClassic = Color(0xFF769656)

    // Alternate Board Themes
    val SquareLightWalnut = Color(0xFFF0D9B5)
    val SquareDarkWalnut = Color(0xFFB58863)

    val SquareLightSlate = Color(0xFFDDE3EA)
    val SquareDarkSlate = Color(0xFF6B7C93)

    val SquareLightOcean = Color(0xFFDEE3E6)
    val SquareDarkOcean = Color(0xFF4B7399)

    // Light Theme Surfaces (Prepared for future light theme support)
    val LightBackgroundPrimary = Color(0xFFF4F5F2)
    val LightBackgroundSecondary = Color(0xFFEAECE7)
    val LightSurfaceCard = Color(0xFFFFFFFF)
    val LightBorderSubtle = Color(0xFFD6DBD2)
    val LightTextPrimary = Color(0xFF191D23)
    val LightTextSecondary = Color(0xFF58606B)
}

@Immutable
data class BoardSquareColors(
    val light: Color,
    val dark: Color,
    val highlight: Color,
    val check: Color,
    val selectedLight: Color = Color(0xFFF6F681),
    val selectedDark: Color = Color(0xFFBACA44),
    val lastMoveLight: Color = Color(0xCCF5F682),
    val lastMoveDark: Color = Color(0xCCB9CA43),
    val premoveLight: Color = Color(0xCCEB6A5B),
    val premoveDark: Color = Color(0xCCCD4E40),
    val premoveTargetIndicator: Color = Color(0x66E05A47),
    val legalMoveIndicator: Color = Color(0x45171A1F),
    val dragHoverOverlay: Color = Color(0x44FFFFFF),
    val dragHoverBorder: Color = Color(0xCCFFFFFF),
    val checkEdge: Color = Color(0x00E05A47)
)

fun BoardTheme.toSquareColors(): BoardSquareColors = when (this) {
    BoardTheme.CLASSIC_GREEN -> BoardSquareColors(
        light = ChessPalette.SquareLightClassic,
        dark = ChessPalette.SquareDarkClassic,
        highlight = Color(0x88F6F669),
        check = Color(0xDDE04438),
        selectedLight = Color(0xFFF5F682),
        selectedDark = Color(0xFFBACA44),
        lastMoveLight = Color(0xC8F5F682),
        lastMoveDark = Color(0xC8B9CA43)
    )
    BoardTheme.WALNUT_WOOD -> BoardSquareColors(
        light = ChessPalette.SquareLightWalnut,
        dark = ChessPalette.SquareDarkWalnut,
        highlight = Color(0x88F5D76E),
        check = Color(0xDDE04438),
        selectedLight = Color(0xFFF7E28B),
        selectedDark = Color(0xFFD5B153),
        lastMoveLight = Color(0xC8F7E28B),
        lastMoveDark = Color(0xC8CCA447)
    )
    BoardTheme.SLATE_TOURNAMENT -> BoardSquareColors(
        light = ChessPalette.SquareLightSlate,
        dark = ChessPalette.SquareDarkSlate,
        highlight = Color(0x8881B64C),
        check = Color(0xDDE04438),
        selectedLight = Color(0xFFB7D691),
        selectedDark = Color(0xFF7CA45B),
        lastMoveLight = Color(0xB89ED16A),
        lastMoveDark = Color(0xB86D9952)
    )
    BoardTheme.OCEAN_BLUE -> BoardSquareColors(
        light = ChessPalette.SquareLightOcean,
        dark = ChessPalette.SquareDarkOcean,
        highlight = Color(0x88F6F669),
        check = Color(0xDDE04438),
        selectedLight = Color(0xFF9ACAEF),
        selectedDark = Color(0xFF5D9BD1),
        lastMoveLight = Color(0xC8F3EC8C),
        lastMoveDark = Color(0xC88BB57A)
    )
}
