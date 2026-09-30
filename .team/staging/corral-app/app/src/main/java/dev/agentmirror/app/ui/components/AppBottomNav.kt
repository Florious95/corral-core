package dev.agentmirror.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.model.NavTab
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 底部导航栏 —— 3b「顶部指示轨」。
 * 选中态 = 顶边 44×2dp 主色轨 + 主色图标文字（SemiBold）；未选中 = 中性色。
 * 无色块、无胶囊，是全站唯一一处横向滑动的指示元素。
 *
 * 图标用文本字形（★ ☰ ⚙），⛔ 不引入任何图标库。
 * 想换成 Material Symbols 需要外部字体资源，见文末说明，请先告诉我。
 */
@Composable
fun AppBottomNav(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val tabs = remember { listOf(NavTab.Favorites, NavTab.Sessions, NavTab.Settings) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom-tabs")
            .navigationBarsPadding()
            .height(Dims.navBarHeight)
            .background(p.navBackground)
    ) {
        val cellWidth = maxWidth / tabs.size
        val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
        val railTarget = cellWidth * selectedIndex + (cellWidth - Dims.navRailWidth) / 2
        val railOffset by animateDpAsState(
            targetValue = railTarget,
            animationSpec = tween(durationMillis = Motion.navRail, easing = Motion.emphasized),
            label = "navRail",
        )

        // 顶部分割线（略重于列表发丝线，把导航栏和内容分层）
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dims.hairline)
                .align(Alignment.TopStart)
                .background(p.dividerStrong)
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavCell(
                    glyph = tab.glyph(),
                    label = tab.label(),
                    active = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tab.tabTag())
                        .semantics { this.selected = tab == selected },
                )
            }
        }

        // 指示轨压在分割线之上
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = railOffset)
                .width(Dims.navRailWidth)
                .height(Dims.navRailThickness)
                .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                .background(p.navRail)
        )
    }
}

@Composable
private fun NavCell(
    glyph: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    val tint = if (active) p.navActive else p.navInactive
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interaction,
                indication = null,          // 3b 不用水波纹，整栏保持安静
                onClick = onClick,
            ),
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
