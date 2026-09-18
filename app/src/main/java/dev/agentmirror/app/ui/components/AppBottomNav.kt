package dev.agentmirror.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import dev.agentmirror.app.ui.model.NavTab
import dev.agentmirror.app.ui.theme.DarkPalette
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.TypeSizes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 底部导航 —— 悬浮液态玻璃胶囊。
 * 悬在页面内容之上（内容从它下面滚过并被折射 / 模糊），离屏边 16dp、离底 12dp。
 * 选中态 = 一枚主色调的玻璃滑块在胶囊内滑动（[Motion.navRail]），按下时轻微放大；
 * 滑块采样「页面 + 胶囊本体」两层背景，是玻璃上的玻璃。
 *
 * 图标用文本字形（★ ☰ ⚙），⛔ 不引入任何图标库。
 */
@Composable
fun AppBottomNav(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val backdrop = LocalGlassBackdrop.current
    val tabs = remember { listOf(NavTab.Favorites, NavTab.Sessions, NavTab.Settings) }
    val interactions = remember { tabs.map { MutableInteractionSource() } }
    val anyPressed = interactions.map { it.collectIsPressedAsState().value }.any { it }
    val pressProgress by animateFloatAsState(
        targetValue = if (anyPressed) 1f else 0f,
        animationSpec = tween(Motion.glassPress),
        label = "navPress",
    )
    // 胶囊本体的成像导出给滑块采样（不含滑块与文字，不成环）。
    val barBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier
            .testTag("bottom-tabs")
            .navigationBarsPadding()
            .padding(start = Dims.navFloatHPadding, end = Dims.navFloatHPadding, bottom = Dims.navFloatMargin)
            .fillMaxWidth()
            .height(Dims.navBarHeight),
    ) {
        val cellWidth = (maxWidth - Dims.navIndicatorInset * 2) / tabs.size
        val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorPosition = remember { Animatable(selectedIndex.toFloat()) }
        var animationStart by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
        LaunchedEffect(selectedIndex) {
            val target = selectedIndex.toFloat()
            animationStart = indicatorPosition.value
            indicatorPosition.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = Motion.navRail, easing = Motion.emphasized),
            )
        }
        // Approximate the instantaneous travel speed with a bell curve over the
        // current leg. The lens stretches while crossing cells and settles back
        // to its natural capsule shape exactly at the destination.
        val delta = selectedIndex.toFloat() - animationStart
        val progress = if (abs(delta) < 0.001f) 1f else {
            ((indicatorPosition.value - animationStart) / delta).coerceIn(0f, 1f)
        }
        val travelSpeed = if (abs(delta) < 0.001f) 0f else sin(progress * PI).toFloat()
        val lensScaleX = 1f + travelSpeed * 0.14f
        val lensScaleY = 1f / sqrt(lensScaleX)

        // 胶囊本体：模糊由 12dp 降至 6dp，表面覆盖浓度浅色 0.30 / 深色 0.38
        Box(
            Modifier
                .matchParentSize()
                .glassPanel(
                    backdrop = backdrop,
                    shape = NavCapsule,
                    surface = p.navBackground.glassReadable(),
                    exportedBackdrop = barBackdrop,
                    blurRadius = 6.dp,
                    lensHeight = 8.dp,
                    lensAmount = 18.dp,
                ),
        )

        // 选中滑块：沿轨道移动时呈水滴拉伸，落位后回弹为自然胶囊；
        // 内部滑块：模糊降至 1.5dp，lensHeight = 7.dp，lensAmount = 22.dp，发丝高光 + 贴边微暗内阴影
        Box(
            Modifier
                .padding(Dims.navIndicatorInset)
                .offset { IntOffset((cellWidth * indicatorPosition.value).roundToPx(), 0) }
                .width(cellWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = lensScaleX
                    scaleY = lensScaleY
                }
                .glassControl(
                    backdrop = rememberCombinedBackdrop(backdrop, barBackdrop),
                    shape = NavCapsule,
                    surface = Color.Unspecified,
                    tint = p.navRail,
                    tintAlpha = if (p === DarkPalette) 0.14f else 0.16f,
                    pressProgress = { pressProgress },
                    pressedScale = 1.06f,
                    blurRadius = 1.5.dp,
                    lensHeight = 7.dp,
                    lensAmount = 22.dp,
                    crystalHighlight = true,
                ),
        )

        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(Dims.navIndicatorInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                NavCell(
                    glyph = tab.glyph(),
                    label = tab.label(),
                    active = tab == selected,
                    interaction = interactions[index],
                    onClick = { onSelect(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tab.tabTag())
                        .semantics { this.selected = tab == selected },
                )
            }
        }
    }
}

private val NavCapsule = Capsule()

@Composable
private fun NavCell(
    glyph: String,
    label: String,
    active: Boolean,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val tint = if (active) p.navActive else p.navInactive
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(Motion.glassPress),
        label = "navCellScale",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,          // 玻璃滑块就是反馈，不叠水波纹
                role = Role.Tab,
                onClick = onClick,
            )
            .scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppText(
            text = glyph,
            color = tint,
            fontSize = TypeSizes.navGlyph,
            fontFamily = FontFamily.Default,
            lineHeightMultiplier = 1f,
        )
        Box(Modifier.height(Dims.navIconLabelGap))
        AppText(
            text = label,
            color = tint,
            fontSize = TypeSizes.navLabel,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            lineHeightMultiplier = 1f,
            textAlign = TextAlign.Center,
        )
    }
}

private fun NavTab.glyph(): String = when (this) {
    NavTab.Favorites -> "★"
    NavTab.Sessions -> "☰"
    NavTab.Settings -> "⚙"
}

private fun NavTab.label(): String = when (this) {
    NavTab.Favorites -> "收藏"
    NavTab.Sessions -> "会话"
    NavTab.Settings -> "设置"
}

private fun NavTab.tabTag(): String = when (this) {
    NavTab.Favorites -> "bottom-tab-favorites"
    NavTab.Sessions -> "bottom-tab-sessions"
    NavTab.Settings -> "bottom-tab-settings"
}
