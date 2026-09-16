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

package dev.agentmirror.app.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes
import kotlinx.coroutines.flow.first

/*
 * 液态玻璃材质层（Kyant0/AndroidLiquidGlass · io.github.kyant0:backdrop）。
 *
 * 三条约定：
 * 1. 被采样的内容用 `Modifier.layerBackdrop` 录进 [LocalGlassBackdrop]（ThreePaneHome 的分页内容）。
 * 2. 玻璃表面用 [glassPanel] / [glassControl] 采样并折射它。⛔ 玻璃表面绝不能落在被录制的内容里：
 *    层会采样到自己，RenderThread 直接 SIGSEGV（库文档 FAQ）。列表行因此只用不采样的 [glassCard]。
 * 3. 所以模态（新建 Agent 弹窗 / 长按操作弹层）不开独立 Window，而经 [GlassModalLayer] 抬到
 *    [GlassModalHost]——与被录内容同窗、同坐标系、却在录制边界之外，才折射得到真实页面。
 *
 * 能力分级见 [GlassCapability]：API 31+ 模糊 / 饱和，API 33+ 透镜折射与高光着色器；更低版本只画
 * 表面着色，表面因此经 [glassReadable] 提到近乎不透明以保可读。
 */

/** 玻璃表面采样的背景。ThreePaneHome 提供分页内容；面板内再覆盖为面板自身导出的层（玻璃上的玻璃）。 */
val LocalGlassBackdrop: ProvidableCompositionLocal<Backdrop> = staticCompositionLocalOf { emptyBackdrop() }

/** 悬浮导航压住的底部内容高度。滚动容器把它当作 contentPadding，末行才能滚出胶囊。 */
val LocalFloatingNavInset: ProvidableCompositionLocal<Dp> = compositionLocalOf { 0.dp }

private const val SURFACE_ALPHA_WITHOUT_BLUR = 0.94f

/**
 * 玻璃能力分级，与库内部判据同源：模糊 / 色彩走 RenderEffect（API 31+），透镜与高光走 AGSL
 * RuntimeShader（API 33+）。Robolectric 自带的 Skia 不认 AGSL 的 `shader.eval(...)` 语法
 * （编译期报 "cannot swizzle value of type 'shader'"），单测宿主因此只走非着色器路径。
 */
private object GlassCapability {
    val blur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val shaders: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        Build.FINGERPRINT != "robolectric"
}

/** 没有模糊能力时，半透明表面下是清晰的页面内容，必须近乎不透明才可读。 */
fun Color.glassReadable(): Color =
    if (GlassCapability.blur) this else copy(alpha = SURFACE_ALPHA_WITHOUT_BLUR)

/** 发丝级高光：极窄 0.65dp，45° 聚光（falloff = 2.5f），浅色 alpha 0.65，避免整圈死白 */
val HairlineHighlight: Highlight = Highlight(
    width = 0.65.dp,
    alpha = 0.65f,
    style = HighlightStyle.Default(
        color = Color.White,
        angle = 45f,
        falloff = 2.5f,
    ),
)

/** 贴紧亮边的微暗内阴影：极窄 1.5dp，offset = (0, 0.75dp)，黑色 alpha 0.12，勾勒水滴晶莹立体厚度 */
val HairlineInnerShadow: InnerShadow = InnerShadow(
    radius = 1.5.dp,
    offset = DpOffset(0.dp, 0.75.dp),
    color = Color.Black.copy(alpha = 0.12f),
    alpha = 0.12f,
)

/** 有着色器时用带角度衰减的默认高光，否则退到纯色描边高光；[color] 指定则整圈染色（危险项红光 / 选中主色光晕）。 */
private fun glassHighlight(color: Color = Color.Unspecified): Highlight = when {
    GlassCapability.shaders && color.isSpecified -> Highlight(style = HighlightStyle.Default(color = color.copy(alpha = 0.7f)))
    GlassCapability.shaders -> Highlight.Default
    color.isSpecified -> Highlight(style = HighlightStyle.Plain(color = color.copy(alpha = 0.5f)))
    else -> Highlight.Plain
}

/** 发丝级晶莹高光：有着色器走 45° 聚光的 [HairlineHighlight]，否则退到同宽同透明度的纯色细环。 */
private fun crystalHighlight(): Highlight =
    if (GlassCapability.shaders) {
        HairlineHighlight
    } else {
        Highlight(
            width = 0.65.dp,
            alpha = 0.65f,
            style = HighlightStyle.Plain(color = Color.White.copy(alpha = 0.65f)),
        )
    }

// ─────────────────────────────────────────────────────────────
// FlatGlass（平整薄片）：不采样、纯 background / border，录制树内任何位置都安全
// ─────────────────────────────────────────────────────────────

/** FlatGlass 材质常量：半透平面底、更薄的软底、0.5dp 发丝边、顶亮 / 底暗明暗对、按压变暗蒙层。 */
data class FlatGlassTokens(
    val fill: Color,
    val fillSoft: Color,
    val hairline: Color,
    val topGlint: Color,
    val bottomShade: Color,
    val pressDim: Color,
)

val LightFlatGlass = FlatGlassTokens(
    fill = Color(0xD9FFFFFF),
    fillSoft = Color(0x66FFFFFF),
    hairline = Color(0x1F000000),
    topGlint = Color(0xB3FFFFFF),
    bottomShade = Color(0x14000000),
    pressDim = Color(0x14000000),
)

val DarkFlatGlass = FlatGlassTokens(
    fill = Color(0xD9252525),
    fillSoft = Color(0x66252525),
    hairline = Color(0x26FFFFFF),
    topGlint = Color(0x2EFFFFFF),
    bottomShade = Color(0x4D000000),
    pressDim = Color(0x33000000),
)

/** 跟随 [LocalAppPalette] 的 FlatGlass 常量。 */
@Composable
fun flatGlassTokens(): FlatGlassTokens =
    if (LocalAppPalette.current === DarkPalette) DarkFlatGlass else LightFlatGlass

/** FlatGlass 发丝边宽度（比 [Dims.hairline] 的 1dp 更细）。 */
val FlatHairline: Dp = 0.5.dp

/** 光学明暗对的内描边宽度：比发丝边宽，发丝边压在其上，边内侧留出顶亮 / 底暗的一线。 */
private val FlatEdgeWidth: Dp = 1.5.dp

/**
 * 纯平微水润薄片：clip → 半透底 → 按压蒙层 → 顶亮/底暗内边 → 发丝边。
 * ⛔ 不采样任何 backdrop，因此可用于 ThreePane 录制树内部（列表 / 设置 / 顶栏）与会话底栏。
 */
fun Modifier.flatGlass(
    shape: Shape,
    fill: Color,
    hairline: Color,
    topGlint: Color,
    bottomShade: Color,
    overlay: Color = Color.Transparent,
    hairlineWidth: Dp = FlatHairline,
): Modifier = this
    .clip(shape)
    .background(fill)
    .background(overlay)
    .border(
        width = FlatEdgeWidth,
        brush = Brush.verticalGradient(0f to topGlint, 0.45f to Color.Transparent, 1f to bottomShade),
        shape = shape,
    )
    .border(width = hairlineWidth, color = hairline, shape = shape)

/**
 * 模态面板 / 导航胶囊：饱和提升 + 重模糊 + 深折射（depthEffect）+ 高光 + 投影。
 * [exportedBackdrop] 把面板自身的成像导出，供面板内控件继续采样（不含内容，故不成环）。
 */
fun Modifier.glassPanel(
    backdrop: Backdrop,
    shape: RoundedRectangularShape,
    surface: Color,
    exportedBackdrop: LayerBackdrop? = null,
    shadow: Shadow? = PanelShadow,
    innerShadow: InnerShadow? = null,
    blurRadius: Dp = 12.dp,
    lensHeight: Dp = 16.dp,
    lensAmount: Dp = 24.dp,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        if (GlassCapability.shaders) {
            lens(
                refractionHeight = lensHeight.toPx(),
                refractionAmount = lensAmount.toPx(),
                depthEffect = false,
            )
        }
    },
    highlight = { glassHighlight() },
    shadow = shadow?.let { { it } },
    innerShadow = innerShadow?.let { { it } },
    exportedBackdrop = exportedBackdrop,
    onDrawSurface = { drawRect(surface) },
)

private val PanelShadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.16f))

/**
 * 面板内控件（按钮 / 图标卡 / 操作行 / 导航滑块）：浅模糊 + 轻折射。
 * [tint] 用 Hue 混合让折射到的背景随主色变调，再叠 [tintAlpha] 的实色；[glow] 给危险项一圈内发红光。
 * [pressProgress] 0→1 时整块缩放到 [pressedScale]，配合 [rememberPressProgress] 得到玻璃按压回弹。
 */
fun Modifier.glassControl(
    backdrop: Backdrop,
    shape: RoundedRectangularShape,
    surface: Color,
    tint: Color = Color.Unspecified,
    tintAlpha: Float = 0.72f,
    glow: Color = Color.Unspecified,
    pressProgress: () -> Float = NoPress,
    pressedScale: Float = 0.96f,
    blurRadius: Dp = 4.dp,
    lensHeight: Dp = 12.dp,
    lensAmount: Dp = 24.dp,
    chromaticAberration: Boolean = false,
    crystalHighlight: Boolean = false,
    highlight: Highlight? = null,
    shadow: Shadow? = null,
    innerShadow: InnerShadow? = null,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        if (GlassCapability.shaders) {
            lens(
                refractionHeight = lensHeight.toPx(),
                refractionAmount = lensAmount.toPx(),
                chromaticAberration = chromaticAberration,
            )
        }
    },
    highlight = {
        if (highlight != null) {
            highlight
        } else if (crystalHighlight) {
            crystalHighlight()
        } else {
            glassHighlight(glow)
        }
    },
    shadow = shadow?.let { { it } },
    innerShadow = innerShadow?.let { { it } } ?: if (crystalHighlight) {
        { HairlineInnerShadow }
    } else if (glow.isSpecified) {
        { InnerShadow(radius = 14.dp, offset = DpOffset.Zero, color = glow.copy(alpha = 0.32f)) }
    } else {
        null
    },
    layerBlock = if (pressProgress === NoPress) {
        null
    } else {
        {
            val scale = lerp(1f, pressedScale, pressProgress())
            scaleX = scale
            scaleY = scale
        }
    },
    onDrawSurface = {
        if (tint.isSpecified) {
            drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(tint.copy(alpha = tintAlpha))
        }
        if (surface.isSpecified) drawRect(surface)
    },
)

private val NoPress: () -> Float = { 0f }

/**
 * 列表行纯平微玻璃卡（Modern Flat Sheet Glass）：
 * 彻底废除拟物凸面弧度高光与渐变反光，回归现代纯平通透玻璃板：
 * 纯净半透平面底色（flat translucent surface） + 极细发丝级全局均匀微描边（hairline border）。
 */
fun Modifier.glassCard(
    shape: Shape,
    fill: Color,
    stroke: Color,
    specular: Color = Color.Unspecified,
): Modifier = this
    .clip(shape)
    .background(fill)
    .border(Dims.hairline, stroke, shape)

/** 按压进度 0→1（[Motion.glassPress] 回弹），供 [glassControl] 的 pressProgress 使用。 */
@Composable
fun rememberPressProgress(interaction: MutableInteractionSource): () -> Float {
    val pressed by interaction.collectIsPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(Motion.glassPress),
        label = "glassPress",
    )
    return { progress }
}

/**
 * 玻璃按钮：中性（取消）或主色着色（创建）。文字唯一子节点，语义树上按钮文本即按钮名。
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    textColor: Color = LocalAppPalette.current.rowTitleText,
    minWidth: Dp = 64.dp,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .glassControl(
                backdrop = LocalGlassBackdrop.current,
                shape = ControlShape,
                surface = if (tint.isSpecified) Color.Unspecified else p.glassSurface.copy(alpha = 0.35f),
                tint = tint,
                pressProgress = press,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .defaultMinSize(minWidth = minWidth)
            .height(44.dp)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            color = textColor,
            fontSize = TypeSizes.actionButton,
            fontWeight = FontWeight.SemiBold,
            lineHeightMultiplier = 1f,
        )
    }
}

private val ControlShape = RoundedRectangle(Radii.glassControl)
private val ToggleTrackShape = Capsule()
private val ToggleThumbShape = Capsule()

/**
 * Kyant0 风格液态开关：通透胶囊轨道 + 带折射/高光/内阴影的水滴圆钮。
 * 轨道导出自己的成像层，圆钮采样「页面 + 轨道」两层背景，避免把玻璃控件录回自身。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalAppPalette.current
    val isDark = p === DarkPalette
    val pageBackdrop = LocalGlassBackdrop.current
    val trackBackdrop = rememberLayerBackdrop()
    val thumbBackdrop = rememberCombinedBackdrop(pageBackdrop, trackBackdrop)
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction)

    // 轨道：半透微深灰胶囊凹槽（未开）/ 清澈科技蓝（开启）
    // 轨道颜色过渡（200ms）与滑块平移同步启动
    val targetTrackSurface = if (checked) p.accent.copy(alpha = 0.85f) else Color(0x33000000)
    val trackSurface by animateColorAsState(
        targetValue = targetTrackSurface,
        animationSpec = tween(200),
        label = "liquidToggleTrackSurface",
    )
    val targetTrackBorder = if (checked) p.accent.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.16f)
    val trackBorder by animateColorAsState(
        targetValue = targetTrackBorder,
        animationSpec = tween(200),
        label = "liquidToggleTrackBorder",
    )
    val trackInnerShadow = InnerShadow(
        radius = 3.dp,
        offset = DpOffset(0.dp, 1.dp),
        color = if (checked) Color.White.copy(alpha = 0.28f) else Color(0x2E000000),
    )

    BoxWithConstraints(
        modifier = modifier
            .width(64.dp)
            .height(36.dp)
            .clip(ToggleTrackShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .semantics {
                toggleableState = ToggleableState(checked)
            }
            .alpha(if (enabled) 1f else 0.45f)
            .glassPanel(
                backdrop = pageBackdrop,
                shape = ToggleTrackShape,
                surface = trackSurface.glassReadable(),
                exportedBackdrop = trackBackdrop,
                shadow = null,
                innerShadow = trackInnerShadow,
                blurRadius = 3.dp,
                lensHeight = 4.dp,
                lensAmount = 6.dp,
            )
            .clip(ToggleTrackShape)
            .border(
                width = Dims.hairline,
                color = trackBorder,
                shape = ToggleTrackShape,
            ),
    ) {
        val thumbSize = 30.dp
        val inset = 3.dp
        val travel = (maxWidth - thumbSize - inset * 2).coerceAtLeast(0.dp)
        val thumbOffset by animateDpAsState(
            targetValue = if (checked) travel else 0.dp,
            animationSpec = tween(200, easing = Motion.sheetEnter),
            label = "liquidToggleThumb",
        )
        val thumbSurfaceAlpha = if (isDark) 0.14f else 0.10f
        Box(
            Modifier
                .fillMaxSize()
                .clip(ToggleTrackShape)
                .padding(inset),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .glassControl(
                        backdrop = thumbBackdrop,
                        shape = ToggleThumbShape,
                        // 纯净半透微光表面，不遮挡底层透镜折射：浅色 0.10 / 深色 0.14
                        surface = Color.White.copy(alpha = thumbSurfaceAlpha),
                        tint = if (checked) p.accent else Color.Unspecified,
                        tintAlpha = if (checked) 0.08f else 0f,
                        glow = if (checked) p.accent else Color.Unspecified,
                        pressProgress = press,
                        pressedScale = 1.03f,
                        blurRadius = 1.dp,
                        lensHeight = 5.dp,
                        lensAmount = 12.dp,
                        chromaticAberration = true,
                        crystalHighlight = true,
                        shadow = Shadow(
                            radius = 3.dp,
                            offset = DpOffset(0.dp, 1.dp),
                            color = Color(0x40000000),
                        ),
                    ),
            )
        }
    }
}

/**
 * 自包含液态开关（录制树内安全）。
 * ⛔ 不读 [LocalGlassBackdrop]、⛔ 不采样任何图层：轨道与水滴圆钮都以 [emptyBackdrop] 走 [drawBackdrop]，
 * 只靠表面着色 + 发丝高光 + 贴边内阴影 + 微投影 + 左上一点柔光来模拟水滴厚度，
 * 因此可以放在 ThreePane 的 `layerBackdrop` 录制树里（设置页），不会触发 RenderNode 自采样递归。
 * 语义与 [LiquidToggle] 一致（Role.Switch + toggleable 状态）。
 */
@Composable
fun StandaloneLiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalAppPalette.current
    val isDark = p === DarkPalette
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction)

    // 轨道：未开为中性凹槽（浅色微暗 / 深色微亮），开启为主色 —— 颜色与滑块平移同步 200ms 过渡
    val trackSurface by animateColorAsState(
        targetValue = when {
            checked -> p.accent.copy(alpha = 0.88f)
            isDark -> Color(0x2EFFFFFF)
            else -> Color(0x24000000)
        },
        animationSpec = tween(Motion.toggle),
        label = "standaloneToggleTrack",
    )
    val trackStroke by animateColorAsState(
        targetValue = when {
            checked -> p.accent.copy(alpha = 0.55f)
            isDark -> Color(0x33FFFFFF)
            else -> Color(0x1F000000)
        },
        animationSpec = tween(Motion.toggle),
        label = "standaloneToggleStroke",
    )
    val trackInnerShadow = InnerShadow(
        radius = 3.dp,
        offset = DpOffset(0.dp, 1.dp),
        color = Color.Black.copy(alpha = if (checked) 0.18f else if (isDark) 0.35f else 0.14f),
    )
    val thumbSurface = Color.White.copy(alpha = if (isDark) 0.92f else 0.96f)

    BoxWithConstraints(
        modifier = modifier
            .width(StandaloneToggleWidth)
            .height(StandaloneToggleHeight)
            .clip(ToggleTrackShape)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else 0.45f)
            .drawBackdrop(
                backdrop = emptyBackdrop(),
                shape = { ToggleTrackShape },
                effects = {},
                highlight = { crystalHighlight() },
                innerShadow = { trackInnerShadow },
                onDrawSurface = { drawRect(trackSurface) },
            )
            .border(FlatHairline, trackStroke, ToggleTrackShape),
    ) {
        val travel = (maxWidth - StandaloneThumbSize - StandaloneThumbInset * 2).coerceAtLeast(0.dp)
        val thumbOffset by animateDpAsState(
            targetValue = if (checked) travel else 0.dp,
            animationSpec = tween(Motion.toggle, easing = Motion.sheetEnter),
            label = "standaloneToggleThumb",
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(StandaloneThumbInset),
        ) {
            // 水滴圆钮：近乎白的半透表面 + 发丝高光 + 底部贴边内阴影 + 微投影；按压微胀 4%
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbOffset)
                    .size(StandaloneThumbSize)
                    .drawBackdrop(
                        backdrop = emptyBackdrop(),
                        shape = { ToggleThumbShape },
                        effects = {},
                        highlight = { crystalHighlight() },
                        shadow = {
                            Shadow(
                                radius = 4.dp,
                                offset = DpOffset(0.dp, 1.5.dp),
                                color = Color.Black.copy(alpha = 0.22f),
                            )
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 2.5.dp,
                                offset = DpOffset(0.dp, 1.dp),
                                color = Color.Black.copy(alpha = 0.16f),
                            )
                        },
                        layerBlock = {
                            val scale = lerp(1f, 1.04f, press())
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = {
                            drawRect(thumbSurface)
                            // 左上一点柔光，只占圆钮约 1/3，⛔ 不是整片釉面弧光
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                                    center = Offset(size.width * 0.38f, size.height * 0.32f),
                                    radius = size.minDimension * 0.42f,
                                ),
                            )
                        },
                    ),
            )
        }
    }
}

private val StandaloneToggleWidth: Dp = 52.dp
private val StandaloneToggleHeight: Dp = 32.dp
private val StandaloneThumbSize: Dp = 26.dp
private val StandaloneThumbInset: Dp = 3.dp

// ─────────────────────────────────────────────────────────────
// 页内模态：宿主 + 图层
// ─────────────────────────────────────────────────────────────

/** 模态图层作用域：可对齐（Box）、可做进出场（AnimatedVisibility）、可请求带动效收起。 */
@Stable
interface GlassModalScope : BoxScope, AnimatedVisibilityScope {
    /** 播放退场动效后再回调 onDismiss；动作项应先触发业务回调，再调用它。 */
    fun dismiss()
}

private class GlassModalScopeImpl(
    box: BoxScope,
    visibility: AnimatedVisibilityScope,
    private val onDismissRequest: () -> Unit,
) : GlassModalScope, BoxScope by box, AnimatedVisibilityScope by visibility {
    override fun dismiss() = onDismissRequest()
}

private class GlassModalEntry {
    var content by mutableStateOf<@Composable () -> Unit>({})
}

@Stable
private class GlassModalHostState {
    val entries = mutableStateListOf<GlassModalEntry>()
}

private val LocalGlassModalHost = staticCompositionLocalOf<GlassModalHostState?> { null }

/**
 * 模态宿主：[content] 之上依次叠放所有经 [GlassModalLayer] 抬上来的模态。
 * 放在录制边界（`layerBackdrop`）之外、与被录内容同一个 Box 里。
 */
@Composable
fun GlassModalHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val host = remember { GlassModalHostState() }
    Box(modifier) {
        CompositionLocalProvider(LocalGlassModalHost provides host) { content() }
        host.entries.forEach { entry -> key(entry) { entry.content() } }
    }
}

/**
 * 一层页内模态：遮罩 + 进出场 + 返回键 + 退场后回调 [onDismiss]。
 * 有宿主时抬到宿主顶层（并带上此处的 CompositionLocal 上下文）；没有宿主（单测 / 独立预览）就地渲染。
 * [dismissible] 为 false 时点遮罩与返回键都不收起（如提交进行中）。
 */
@Composable
fun GlassModalLayer(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable GlassModalScope.() -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val dismiss = remember(visibleState) { { visibleState.targetState = false } }
    LaunchedEffect(visibleState) {
        snapshotFlow { visibleState.isIdle && !visibleState.targetState }.first { it }
        latestOnDismiss()
    }
    BackHandler(enabled = dismissible && visibleState.targetState, onBack = dismiss)

    val locals = currentCompositionLocalContext
    val layer: @Composable () -> Unit = {
        CompositionLocalProvider(locals) {
            GlassModalSurface(visibleState, dismissible, dismiss, content)
        }
    }
    val host = LocalGlassModalHost.current
    if (host == null) {
        layer()
        return
    }
    val entry = remember { GlassModalEntry() }
    SideEffect { entry.content = layer }
    DisposableEffect(host, entry) {
        host.entries += entry
        onDispose { host.entries -= entry }
    }
}

@Composable
private fun GlassModalSurface(
    visibleState: MutableTransitionState<Boolean>,
    dismissible: Boolean,
    dismiss: () -> Unit,
    content: @Composable GlassModalScope.() -> Unit,
) {
    val p = LocalAppPalette.current
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(Motion.scrimFade)),
        exit = fadeOut(tween(Motion.sheetSlideOut)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(p.scrim)
                    .then(
                        if (dismissible) {
                            Modifier.clickable(interactionSource = null, indication = null, onClick = dismiss)
                        } else {
                            Modifier.pointerInput(Unit) {}
                        },
                    )
                    .testTag("glass-modal-scrim"),
            )
            val scope = remember(this@Box, this@AnimatedVisibility, dismiss) {
                GlassModalScopeImpl(this@Box, this@AnimatedVisibility, dismiss)
            }
            scope.content()
        }
    }
}
