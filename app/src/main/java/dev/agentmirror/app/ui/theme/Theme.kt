/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 全 App 视觉 token 单一事实源（018 §一.1：M3 色板/字阶/间距/圆角 token 化，深浅双套）。
 *
 * 设计基调「终端深夜」：产品本体是手机镜像主机 tmux 终端，深色是主人格——近黑深蓝底
 * （不是纯黑：终端画布纯黑在其上仍有层次差），去饱和浅蓝为主色（深底上高饱和蓝刺眼且
 * 对比度不足）；浅色套用可读性优先的品牌蓝（白底上浅蓝主色对比度不够，必须压深）。
 * 所有页面/组件禁止再出现字面量颜色——只允许引用 MaterialTheme.colorScheme 与本文件 token。
 */

/**
 * 历史品牌深蓝（`0xFF1B2A4A`）。
 *
 * ui-redesign（018）后 M3 深浅两套 primary 均已改用独立取值（深 `0xFF9DBDFF`、
 * 浅 `0xFF2F5DA8`），本 token 自彼时起无任何消费点——保留仅为存档（原 colors.xml 的
 * `brand_primary` 资源随 stage3 #19 删除，本 token 不再有资源对照）。
 */
val brandPrimary: Color = Color(0xFF1B2A4A)

/**
 * 历史品牌背景深蓝（`0xFF0D1626`；colors.xml 的 `brand_background` 仍被自适应启动图标引用）。
 *
 * ui-redesign（018）后深色 scheme 的 `background` 已改用 `0xFF0B111D`（近黑深蓝，
 * 与纯黑终端画布分层次）；终端画布默认底色现由 [TermPalette.Dark.defaultBg] 承载
 * （078 §2 B：深浅两套集中在 TermPalette，不再散落 TermSurfaceView）。本 token 无消费点，保留仅为存档。
 */
val brandBackground: Color = Color(0xFF0D1626)

// ---- 深色套（主人格）：近黑深蓝底 + 去饱和浅蓝主色，表面用蓝灰阶拉开容器层次 ----
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9DBDFF), // 去饱和浅蓝：深底上可读且不刺眼
    onPrimary = Color(0xFF002F67),
    primaryContainer = Color(0xFF1E4487),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBCC7DC),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFF82D8C4), // 终端青绿：成功/活跃点缀
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF005045),
    onTertiaryContainer = Color(0xFF9EF2DD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B111D), // 近黑深蓝：与纯黑终端画布仍分得开
    onBackground = Color(0xFFE2E6EF),
    surface = Color(0xFF0B111D),
    onSurface = Color(0xFFE2E6EF),
    surfaceVariant = Color(0xFF414A5C),
    onSurfaceVariant = Color(0xFFC1C9DB),
    surfaceContainerLowest = Color(0xFF060B14),
    surfaceContainerLow = Color(0xFF10182A),
    surfaceContainer = Color(0xFF141D31),
    surfaceContainerHigh = Color(0xFF1B2540),
    surfaceContainerHighest = Color(0xFF202A42),
    outline = Color(0xFF8B93A5),
    outlineVariant = Color(0xFF313B4E),
    inverseSurface = Color(0xFFE2E6EF),
    inverseOnSurface = Color(0xFF191C22),
    inversePrimary = Color(0xFF2F5DA8),
    scrim = Color(0xFF000000),
)

// ---- 浅色套：白底 + 压深品牌蓝（可读性优先），容器用极浅蓝灰阶 ----
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2F5DA8), // 品牌蓝压深：白底上达 AA 对比
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF565F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF006A5F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9EF2E2),
    onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FD),
    onBackground = Color(0xFF191C22),
    surface = Color(0xFFF8F9FD),
    onSurface = Color(0xFF191C22),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4FA),
    surfaceContainer = Color(0xFFECEEF6),
    surfaceContainerHigh = Color(0xFFE6E9F1),
    surfaceContainerHighest = Color(0xFFE0E3EC),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFF9DBDFF),
    scrim = Color(0xFF000000),
)

// ---- 字阶 token（018 §一.3 信息层级：字号/字重分级的唯一来源） ----
// 相对 M3 默认整体收紧标题字重（SemiBold）：本产品页面信息密度高（路径/会话名/徽章），
// 主层级靠字重而非超大字号，避免手机窄屏上大标题挤压内容区。
private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.3.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
)

/** 等宽字体：路径 / 会话名 / 终端相关文本统一用它（终端产品的身份语言）。 */
val MonoFontFamily: FontFamily = FontFamily.Monospace

// ---- 圆角 token（018 §一.1）：卡片 14、输入/行内组件 10、小组件 6 ----
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 间距 token（018 §一.4 密度与呼吸的唯一来源）。
 *
 * 4dp 基（Material 网格）；页面水平留白统一 [pageH]，列表行垂直内边距 [rowV]，
 * 触控目标最低 48dp 由组件层保证（M3 minimumInteractiveComponentSize）。
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp

    /** 页面统一水平留白。 */
    val pageH = 16.dp

    /** 列表行统一垂直内边距（行高 ≈ 64dp 双行信息密度）。 */
    val rowV = 12.dp
}

/**
 * 全局 Material3 主题入口：色板/字阶/圆角一站注入，深浅随系统。
 *
 * 状态栏图标颜色适配（018 §一.2）由 MainActivity enableEdgeToEdge 的 auto 样式承担
 * （浅色套深图标/深色套浅图标），主题层不重复管理窗口。
 *
 * 060 uproot（2026-08-15）：状态徽章色板随状态判定整体拔除，
 * 主题不再注入徽章色板。
 *
 * @contract
 * @pre none（`darkTheme` / `content` 无前置约束）
 * @post 以 MaterialTheme 渲染对应 colorScheme / typography / shapes
 * @err none
 */
@Composable
fun AgentMirrorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
