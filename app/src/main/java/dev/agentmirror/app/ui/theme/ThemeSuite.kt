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

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 界面风格套件（Theme Kit）。
 *
 * 风格只是 UI 展示偏好：不是路由 / 会话参数，与「外观」（浅 / 深 / 跟随系统）和终端 ANSI 主题槽完全独立。
 * 每个 [ThemeSuite] 把 [ThemeContext] 纯函数地解析成一份不可变的 [ResolvedThemeSuite]；
 * 组件只读解析结果里的令牌与配方，⛔ 不按 [ThemeId] 分支、⛔ 不持有焦点 / IME / 会话状态。
 * 新增风格 = 新增一个 [ThemeSuite] 实现 + 在 [ThemeRegistry] 注册一处。
 */

/** 稳定的风格标识（持久化值），不按标题或下标识别。 */
@JvmInline
value class ThemeId(val value: String) {
    companion object {
        val LiquidGlass = ThemeId("liquid_glass")
        val Modernist = ThemeId("modernist")
    }
}

/** 解析输入。当前只有明暗会改变令牌取值。 */
@Immutable
data class ThemeContext(val isDark: Boolean)

/** 设置页展示用的风格名。 */
@Immutable
data class ThemeMetadata(val id: ThemeId, val title: String, val englishTitle: String)

/** 一套界面风格。[resolve] 必须纯、同步、确定：同一 context 返回同一份令牌。 */
interface ThemeSuite {
    val metadata: ThemeMetadata
    fun resolve(context: ThemeContext): ResolvedThemeSuite
}

/** 解析后的整套令牌与组件配方，经 [LocalThemeSuite] 下发。 */
@Immutable
data class ResolvedThemeSuite(
    val id: ThemeId,
    val isDark: Boolean,
    val colors: ThemeColors,
    val geometry: ThemeGeometry,
    val typography: ThemeTypography,
    val surfaces: ThemeSurfaces,
    val recipes: ThemeRecipes,
)

@Immutable
data class ThemeColors(
    /** 语义色板；经 [LocalAppPalette] 下发给所有组件。 */
    val palette: AppPalette,
    /** [AppTheme] 层的 M3 色槽。 */
    val materialScheme: ColorScheme,
    /** 根部 [AgentMirrorTheme] 层的 M3 色槽。 */
    val rootMaterialScheme: ColorScheme,
    /** 页头下的截断线。 */
    val headerRule: Color,
    /** 行间 / 分区发丝线。 */
    val separator: Color,
    /** 页头与分组的眉题。 */
    val eyebrow: Color,
    /** 无工作节点时的计数色。 */
    val statusPending: Color,
)

@Immutable
data class ThemeGeometry(
    /** true 时所有圆角 / 胶囊 / 圆形一律落为直角。 */
    val sharpCorners: Boolean,
    val pageInset: Dp,
    val hairline: Dp,
    val headerRule: Dp,
    /** 列表行离屏边的外距与行间距。 */
    val listRowHMargin: Dp,
    val listRowGap: Dp,
    val workspaceRowHeight: Dp,
    val sessionRowHeight: Dp,
    /** 工作区行首计数列宽。 */
    val numberColumnWidth: Dp,
    /** 底部导航自身高度与离底留白（两者之和 + 系统导航栏 = 内容需让出的高度）。 */
    val tabBarHeight: Dp,
    val tabBarBottomGap: Dp,
    /** 底部弹层 / 动作面板离屏边的外距。 */
    val sheetOuterPadding: Dp,
    /** 根部 [AgentMirrorTheme] 的 M3 形状。 */
    val rootMaterialShapes: Shapes,
) {
    fun corner(base: Dp): Dp = if (sharpCorners) 0.dp else base
    fun shape(base: Shape): Shape = if (sharpCorners) RectangleShape else base
}

/** 一种文字样式的令牌（字号 / 字重 / 字距 / 行高倍数 / 字族）。 */
@Immutable
data class TextToken(
    val size: TextUnit,
    val weight: FontWeight,
    val tracking: TextUnit = 0.sp,
    val lineHeight: Float = TypeSizes.rowLineHeight,
    val family: FontFamily = FontFamily.Default,
    val uppercase: Boolean = false,
)

@Immutable
data class ThemeTypography(
    /** 一级页大标题。 */
    val pageTitle: TextToken,
    /** 二级页（工作区 / 设置子页）标题。 */
    val sectionTitle: TextToken,
    /** 页头 meta / 分组小标题。 */
    val eyebrow: TextToken,
    /** 工作区计数。 */
    val numericBadge: TextToken,
    /** [AppTheme] 层的 M3 字阶。 */
    val material: Typography,
    /** 根部 [AgentMirrorTheme] 层的 M3 字阶。 */
    val rootMaterial: Typography,
)

/** 表面材质。 */
enum class SurfaceMaterial {
    /** 液态玻璃：backdrop 模糊 / 透镜 / 高光 / 按压回弹。 */
    LiquidGlass,

    /** 直角固色：不透明填充 + 发丝描边，无模糊、无投影。 */
    Ruled,
}

@Immutable
data class ThemeSurfaces(
    val material: SurfaceMaterial,
    /** 录制背景里漂移的环境光斑。 */
    val ambientLight: Boolean,
    /** 外投影（终端卡浮起、设置卡浮起）。 */
    val shadows: Boolean,
) {
    val isGlass: Boolean get() = material == SurfaceMaterial.LiquidGlass
}

/** 页头：紧凑（标题下接等宽 meta）/ 展示（眉题在上 + 大标题 + 重线）。 */
enum class HeaderStyle { Compact, Display }

/** 列表行：浮动微玻璃卡 / 通栏直角条（带计数列与底线）。 */
enum class ListRowStyle { FloatingCard, RuledStrip }

/** 底部导航：悬浮玻璃胶囊 / 通栏直角标签栏。 */
enum class TabBarStyle { FloatingCapsule, RuledBar }

@Immutable
data class ThemeRecipes(
    val header: HeaderStyle,
    val listRow: ListRowStyle,
    val tabBar: TabBarStyle,
)

/**
 * 唯一的风格注册表：可选项、默认值与未知 ID 回退都只在这里。
 * 重复 ID 或默认值未注册属于注册错误；读到未知 ID 安全回退默认（不改写存储的原值）。
 */
object ThemeRegistry {
    val suites: List<ThemeSuite> = listOf(LiquidGlassThemeSuite, ModernistThemeSuite)
    val default: ThemeSuite = checkRegistry(suites, ThemeId.LiquidGlass)

    operator fun get(id: ThemeId): ThemeSuite = suites.firstOrNull { it.metadata.id == id } ?: default

    fun resolve(id: ThemeId, context: ThemeContext): ResolvedThemeSuite = get(id).resolve(context)

    /** 持久化原值 → 已注册 ID；空值 / 未知值回退默认。 */
    fun idOrDefault(raw: String?): ThemeId = get(ThemeId(raw.orEmpty())).metadata.id
}

/** 校验注册表并返回默认风格。 */
internal fun checkRegistry(suites: List<ThemeSuite>, defaultId: ThemeId): ThemeSuite {
    val ids = suites.map { it.metadata.id }
    require(ids.toSet().size == ids.size) { "duplicate theme id in $ids" }
    return requireNotNull(suites.firstOrNull { it.metadata.id == defaultId }) {
        "default theme ${defaultId.value} is not registered"
    }
}

/** 当前解析后的风格。根部 [AppTheme] 提供；没有提供者（单测 / 预览）时为液态玻璃浅色。 */
val LocalThemeSuite = compositionLocalOf { LiquidGlassThemeSuite.resolve(ThemeContext(isDark = false)) }

/** 色板是否深色槽。液态玻璃下与旧判据 `=== DarkPalette` 逐一等价。 */
val AppPalette.isDark: Boolean
    get() = this === DarkPalette || this === ModernistDarkPalette
