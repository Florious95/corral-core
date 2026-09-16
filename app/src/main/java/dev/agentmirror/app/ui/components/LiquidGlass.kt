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

/** 有着色器时用带角度衰减的默认高光，否则退到纯色描边高光；[color] 指定则整圈染色（危险项红光 / 选中主色光晕）。 */
private fun glassHighlight(color: Color = Color.Unspecified): Highlight = when {
    GlassCapability.shaders && color.isSpecified -> Highlight(style = HighlightStyle.Default(color = color.copy(alpha = 0.7f)))
    GlassCapability.shaders -> Highlight.Default
    color.isSpecified -> Highlight(style = HighlightStyle.Plain(color = color.copy(alpha = 0.5f)))
    else -> Highlight.Plain
}

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
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(12.dp.toPx())
        if (GlassCapability.shaders) {
            lens(refractionHeight = 16.dp.toPx(), refractionAmount = 24.dp.toPx(), depthEffect = false)
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
    lensHeight: Dp = 12.dp,
    lensAmount: Dp = 24.dp,
    chromaticAberration: Boolean = false,
    crystalHighlight: Boolean = false,
    shadow: Shadow? = null,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(4.dp.toPx())
        if (GlassCapability.shaders) {
            lens(
                refractionHeight = lensHeight.toPx(),
                refractionAmount = lensAmount.toPx(),
                chromaticAberration = chromaticAberration,
            )
        }
    },
    highlight = {
        if (crystalHighlight && GlassCapability.shaders) {
            Highlight(style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.92f)))
        } else {
            glassHighlight(glow)
        }
    },
    shadow = shadow?.let { { it } },
    innerShadow = if (glow.isSpecified) {
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
    val pageBackdrop = LocalGlassBackdrop.current
    val trackBackdrop = rememberLayerBackdrop()
    val thumbBackdrop = rememberCombinedBackdrop(pageBackdrop, trackBackdrop)
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressProgress(interaction)

    // 轨道：半透微深灰胶囊凹槽（未开）/ 清澈科技蓝（开启）
    val trackSurface = if (checked) p.accent.copy(alpha = 0.85f) else Color(0x33000000)
    val trackBorder = if (checked) p.accent.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.16f)
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
            animationSpec = tween(Motion.glassPress * 2, easing = Motion.sheetEnter),
            label = "liquidToggleThumb",
        )
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
                        // 纯净半透微光表面，不遮挡底层透镜折射
                        surface = Color.White.copy(alpha = 0.12f),
                        tint = if (checked) p.accent else Color.Unspecified,
                        tintAlpha = if (checked) 0.15f else 0f,
                        glow = if (checked) p.accent else Color.White.copy(alpha = 0.35f),
                        pressProgress = press,
                        pressedScale = 1.08f,
                        lensHeight = 8.dp,
                        lensAmount = 16.dp,
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
