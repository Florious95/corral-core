/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.agentmirror.app.session

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.R

/**
 * Claude Design Nocturne variables mapped one-for-one onto the Material slots consumed by the dock.
 * The mapping is session-local so the new source does not retheme unrelated application screens.
 */
internal object SessionDockMotion {
    const val RowInMillis = 180
    const val PopInMillis = 200
    const val InputBorderMillis = 200
    const val InputHeightMillis = 250
    const val KeyboardPushMillis = 300
    const val CursorBlinkMillis = 1_100
    const val CursorDimAlpha = 51 // CSS opacity .2
    val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** CSS `blink 1.1s steps(1)` phase: full opacity for one half, .2 for the other. */
    fun cursorAlphaAt(elapsedMillis: Long): Int =
        if (Math.floorMod(elapsedMillis, CursorBlinkMillis.toLong()) < CursorBlinkMillis / 2) 255
        else CursorDimAlpha

    fun millisToNextCursorStep(elapsedMillis: Long): Long {
        val half = CursorBlinkMillis / 2L
        return half - Math.floorMod(elapsedMillis, half)
    }
}

internal val sessionDockLightScheme = lightColorScheme(
    primary = Color(0xFF6A5CC0),
    onPrimary = Color(0xFFF3F5FE),
    primaryContainer = Color(0xFFE7E5FE),
    onPrimaryContainer = Color(0xFF423A6A),
    tertiary = Color(0xFF7DD3A0),
    onTertiary = Color(0xFF1F2430),
    background = Color(0xFFF3F5FE),
    onBackground = Color(0xFF1F2430),
    surface = Color.White,
    onSurface = Color(0xFF1F2430),
    surfaceVariant = Color(0xFFE9ECF7),
    onSurfaceVariant = Color(0xFF595D6C),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE9ECF7),
    outline = Color(0xFFB2B6CA),
    outlineVariant = Color(0xFFD4D9EA),
    error = Color(0xFFC25B7C),
    scrim = Color(0x73000000),
)

internal val SessionDockSans = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal),
)

internal data class SessionDockSourceTokens(
    val surface: Color,
    val neutral200: Color,
    val neutral300: Color,
    val neutral400: Color,
    val neutral500: Color,
    val neutral600: Color,
    val neutral700: Color,
    val neutral800: Color,
    val neutral900: Color,
    val accent: Color,
    val accent200: Color,
    val accent300: Color,
    val accent600: Color,
    val accent700: Color,
    val accent900: Color,
    val cliGround: Color,
)

internal val sessionDockLightTokens = SessionDockSourceTokens(
    surface = Color.White,
    neutral200 = Color(0xFF292B31),
    neutral300 = Color(0xFF3F424D),
    neutral400 = Color(0xFF595D6C),
    neutral500 = Color(0xFF75798C),
    neutral600 = Color(0xFF9397AB),
    neutral700 = Color(0xFFB2B6CA),
    neutral800 = Color(0xFFD4D9EA),
    neutral900 = Color(0xFFE9ECF7),
    accent = Color(0xFF6A5CC0),
    accent200 = Color(0xFF423A6A),
    accent300 = Color(0xFF5D5294),
    accent600 = Color(0xFF796CBF),
    accent700 = Color(0xFF968AE0),
    accent900 = Color(0xFFE7E5FE),
    cliGround = Color(0xFFE9F2EC),
)

internal val sessionDockDarkTokens = SessionDockSourceTokens(
    surface = Color(0xFF232532),
    neutral200 = Color(0xFFE4E7F5),
    neutral300 = Color(0xFFCFD3E5),
    neutral400 = Color(0xFFB2B6CA),
    neutral500 = Color(0xFF9397AB),
    neutral600 = Color(0xFF75798C),
    neutral700 = Color(0xFF595D6C),
    neutral800 = Color(0xFF3F424D),
    neutral900 = Color(0xFF292B31),
    accent = Color(0xFF9184D9),
    accent200 = Color(0xFFE7E5FE),
    accent300 = Color(0xFFD2CEFD),
    accent600 = Color(0xFF796CBF),
    accent700 = Color(0xFF5D5294),
    accent900 = Color(0xFF2B2741),
    cliGround = Color(0xFF0F111C),
)

@Composable
internal fun sessionDockSourceTokens(): SessionDockSourceTokens =
    if (MaterialTheme.colorScheme.background == sessionDockLightScheme.background) {
        sessionDockLightTokens
    } else {
        sessionDockDarkTokens
    }

/**
 * 会话底栏 FlatGlass（平整薄片）材质：键帽 / 输入胶囊 / 圆钮共用。
 * 不采样背景（无 backdrop、无模糊、无折射），只有半透平面底色 + 0.5dp 发丝边 +
 * 「顶亮 / 底暗」的光学明暗对；按压只叠一层变暗蒙层，⛔ 不画大面积弧光。
 */
internal data class SessionDockGlassTokens(
    val fill: Color,          // 键帽 / 输入胶囊半透底
    val fillSoft: Color,      // 方向键簇轨道 / 加号圆钮 / 空发送钮的更薄底
    val hairline: Color,      // 0.5dp 发丝边
    val topGlint: Color,      // 顶部内微高光
    val bottomShade: Color,   // 底部贴边微暗
    val pressDim: Color,      // 按压变暗蒙层
)

internal val sessionDockLightGlass = SessionDockGlassTokens(
    fill = Color(0xD9FFFFFF),
    fillSoft = Color(0x66FFFFFF),
    hairline = Color(0x1F000000),
    topGlint = Color(0xB3FFFFFF),
    bottomShade = Color(0x14000000),
    pressDim = Color(0x14000000),
)

internal val sessionDockDarkGlass = SessionDockGlassTokens(
    fill = Color(0xD9252525),
    fillSoft = Color(0x66252525),
    hairline = Color(0x26FFFFFF),
    topGlint = Color(0x2EFFFFFF),
    bottomShade = Color(0x4D000000),
    pressDim = Color(0x33000000),
)

@Composable
internal fun sessionDockGlassTokens(): SessionDockGlassTokens =
    if (MaterialTheme.colorScheme.background == sessionDockLightScheme.background) {
        sessionDockLightGlass
    } else {
        sessionDockDarkGlass
    }

/** 底栏发丝边宽度（比 [dev.agentmirror.app.ui.theme.Dims.hairline] 的 1dp 更细）。 */
internal val SessionDockHairline: Dp = 0.5.dp

/** 光学明暗对的内描边宽度：比发丝边宽，发丝边压在其上，边内侧留出顶亮 / 底暗的一线。 */
private val SessionDockEdgeWidth: Dp = 1.5.dp

/**
 * 纯平微水润薄片：clip → 半透底 → 按压蒙层 → 顶亮/底暗内边 → 发丝边。
 * 纯 [background] / [border] 组合，可安全用在任何录制树内部。
 */
internal fun Modifier.dockFlatGlass(
    shape: Shape,
    fill: Color,
    hairline: Color,
    topGlint: Color,
    bottomShade: Color,
    overlay: Color = Color.Transparent,
    hairlineWidth: Dp = SessionDockHairline,
): Modifier = this
    .clip(shape)
    .background(fill)
    .background(overlay)
    .border(
        width = SessionDockEdgeWidth,
        brush = Brush.verticalGradient(0f to topGlint, 0.45f to Color.Transparent, 1f to bottomShade),
        shape = shape,
    )
    .border(width = hairlineWidth, color = hairline, shape = shape)

internal val sessionDockDarkScheme = darkColorScheme(
    primary = Color(0xFF9184D9),
    onPrimary = Color(0xFF161826),
    primaryContainer = Color(0xFF2B2741),
    onPrimaryContainer = Color(0xFFD2CEFD),
    tertiary = Color(0xFF7DD3A0),
    onTertiary = Color(0xFF161826),
    background = Color(0xFF161826),
    onBackground = Color(0xFFE9E9ED),
    surface = Color(0xFF232532),
    onSurface = Color(0xFFE9E9ED),
    surfaceVariant = Color(0xFF292B31),
    onSurfaceVariant = Color(0xFFB2B6CA),
    surfaceContainer = Color(0xFF232532),
    surfaceContainerHigh = Color(0xFF292B31),
    outline = Color(0xFF595D6C),
    outlineVariant = Color(0xFF3F424D),
    error = Color(0xFFD98AA6),
    scrim = Color(0x73000000),
)

@Composable
internal fun SessionDockTheme(dark: Boolean, content: @Composable () -> Unit) {
    // The source uses real 40/36/32px controls rather than Material's injected 48dp target.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        MaterialTheme(
            colorScheme = if (dark) sessionDockDarkScheme else sessionDockLightScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
