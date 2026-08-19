package dev.agentmirror.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/** 设置页「外观」项的三个取值。System = 跟随系统。 */
enum class Appearance { Light, Dark, System }

/** 当前外观。嵌套 [AppTheme] 不传参时继承，避免把强制深/浅冲回「跟随系统」。 */
val LocalAppearance = staticCompositionLocalOf { Appearance.System }

private val LightScheme = lightColorScheme(
    primary = LightPalette.accent,
    onPrimary = LightPalette.onAccent,
    primaryContainer = LightPalette.accentContainer,
    onPrimaryContainer = LightPalette.accent,
    secondary = LightPalette.busyDot,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = LightPalette.screenBackground,
    onBackground = LightPalette.titleText,
    surface = LightPalette.listBackground,
    onSurface = LightPalette.rowTitleText,
    surfaceVariant = LightPalette.consoleBackground,
    onSurfaceVariant = LightPalette.metaText,
    outline = LightPalette.cardBorder,
    outlineVariant = LightPalette.divider,
    scrim = LightPalette.scrim,
)

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.accent,
    onPrimary = DarkPalette.onAccent,
    primaryContainer = DarkPalette.accentContainer,
    onPrimaryContainer = DarkPalette.navActive,
    secondary = DarkPalette.busyDot,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF04201D),
    background = DarkPalette.screenBackground,
    onBackground = DarkPalette.titleText,
    surface = DarkPalette.listBackground,
    onSurface = DarkPalette.rowTitleText,
    surfaceVariant = DarkPalette.consoleBackground,
    onSurfaceVariant = DarkPalette.metaText,
    outline = DarkPalette.cardBorder,
    outlineVariant = DarkPalette.divider,
    scrim = DarkPalette.scrim,
)

/**
 * 全站字型。
 * 🔴 中文一律 FontFamily.Default（系统字体，思源/鸿蒙/Noto 都能正常出字）。
 * 等宽只用在 ASCII 标识符、路径、数字、按键标签上 —— FontFamily.Monospace 走系统等宽，
 * 中文落到 Monospace 上时系统会回退到默认中文字体，宽度不再是整格，所以中文文本别用它。
 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = TypeSizes.screenTitle,
        lineHeight = TypeSizes.screenTitle * TypeSizes.titleLineHeight,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = TypeSizes.screenTitleSecondary,
        lineHeight = TypeSizes.screenTitleSecondary * 1.2f,
        letterSpacing = (-0.4).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = TypeSizes.topBarTitle,
        lineHeight = TypeSizes.topBarTitle * 1.2f,
        letterSpacing = (-0.2).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = TypeSizes.rowTitle,
        lineHeight = TypeSizes.rowTitle * TypeSizes.rowLineHeight,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = TypeSizes.cardBody,
        lineHeight = TypeSizes.cardBody * TypeSizes.bodyLineHeight,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = TypeSizes.rowSubtitle,
        lineHeight = TypeSizes.rowSubtitle * 1.3f,
    ),
)

/**
 * 用法：
 *   AppTheme(appearance = uiState.appearance) { ... }
 * appearance 由你那边持有（DataStore / SharedPreferences），这里只读不写。
 */
@Composable
fun AppTheme(
    appearance: Appearance = LocalAppearance.current,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        Appearance.Light -> false
        Appearance.Dark -> true
        Appearance.System -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(
        LocalAppPalette provides palette,
        LocalAppearance provides appearance,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/** 终端自绘层要用的色板 —— 与 AppTheme 同源，供 SurfaceView 侧读取。 */
@Composable
fun currentTerminalPalette(): TerminalPalette {
    val dark = LocalAppPalette.current === DarkPalette
    return if (dark) TerminalPaletteDark else TerminalPaletteLight
}
