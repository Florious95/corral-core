package dev.agentmirror.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Motion
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 「查看」二级菜单浮层 —— 会话切换抽屉。
 *
 * 改自原来那个占满 80% 屏幕、下半全空的白卡：现在高度贴合内容，
 * 从底部滑起（320ms，末端轻微减速），遮罩 180ms 淡入，五行依次上浮（40ms 起，34ms 间隔）。
 * 当前会话有左侧 3dp 轨 + 轻着色底 + 「当前」标记 —— 切换器里必须能看出自己在哪一个。
 * 每行重复的同一条路径已去掉，只留显示名和状态。
 *
 * 用法：套在会话页最外层的 Box 里，与 SessionShellScreen 同级。
 * ⛔ 没有内部业务状态：visible 由你控制，onDismiss 只是回调。
 */
@Composable
fun SessionSwitchSheet(
    visible: Boolean,
    workspaceName: String,
    sessions: List<SessionItem>,
    currentSessionId: String?,
    onDismiss: () -> Unit,
    onSelect: (SessionItem) -> Unit,
    onToggleStar: (SessionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalAppPalette.current
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.scrimFade, easing = Motion.linear)),
            exit = fadeOut(tween(Motion.scrimFade, easing = Motion.linear)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(p.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                animationSpec = tween(Motion.sheetSlideIn, easing = Motion.sheetEnter),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = tween(Motion.sheetSlideOut, easing = Motion.emphasized),
                targetOffsetY = { it },
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(topStart = Radii.sheetTop, topEnd = Radii.sheetTop))
                    .background(p.sheetBackground)
                    .padding(bottom = 12.dp)
            ) {
                // 抓手条 —— 关闭的唯一视觉提示，⛔ 不要再加一行「点空白处关闭」的文案
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Dims.sheetGrabberRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(Dims.sheetGrabberWidth)
                            .height(Dims.sheetGrabberHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(p.sheetGrabber)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Dims.screenHPadding, end = Dims.screenHPadding, top = 2.dp, bottom = Dims.sheetHeaderBottomPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        text = "切换会话",
                        color = p.titleText,
                        fontSize = TypeSizes.sheetTitle,
                        fontWeight = FontWeight.Bold,
                        lineHeightMultiplier = 1.25f,
                        letterSpacing = (-0.3).sp,
                    )
                    Box(Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(p.segmentedTrack)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        AppText(
                            text = "$workspaceName · ${sessions.size}",
                            color = p.metaText,
                            fontSize = TypeSizes.footnote,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            lineHeightMultiplier = 1f,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(p.sheetSurface)
                ) {
                    Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.divider))
                    sessions.forEachIndexed { index, item ->
                        StaggeredRow(index = index, replayKey = visible) {
                            SheetRow(
                                item = item,
                                isCurrent = item.id == currentSessionId,
                                onClick = { onSelect(item) },
                                onToggleStar = { onToggleStar(item) },
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.divider))
                    }
                }
            }
        }
    }
}

/**
 * 逐行上浮。
 * 这里的 remember 只是动画状态，不是业务状态 —— replayKey 变化时重新播放。
 */
@Composable
private fun StaggeredRow(
    index: Int,
    replayKey: Any,
    content: @Composable () -> Unit,
) {
    var shown by remember(replayKey) { mutableStateOf(false) }
    LaunchedEffect(replayKey) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = Motion.sheetRow,
            delayMillis = Motion.sheetRowDelayBase + Motion.sheetRowStagger * index,
            easing = Motion.emphasized,
        ),
        label = "sheetRow$index",
    )
    val rise = with(LocalDensity.current) { Motion.sheetRowRiseDp.dp.toPx() }
    Box(
        Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * rise
        }
    ) { content() }
}

@Composable
private fun SheetRow(
    item: SessionItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        pressed -> p.sheetRowPressed
        isCurrent -> p.sheetCurrentRowBg
        else -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dims.rowHeightSheet)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        if (isCurrent) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(Dims.sheetCurrentRailWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(p.sheetCurrentRail)
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = Dims.listHPaddingStart, end = Dims.listHPaddingEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dims.rowGap),
        ) {
            StarButton(starred = item.starred, onToggle = onToggleStar)
            SessionNameText(item.displayName, Modifier.weight(1f))
            if (isCurrent) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .border(Dims.hairline, p.currentBadgeBorder, RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 4.dp)
                ) {
                    AppText(
                        text = "当前",
                        color = p.currentBadgeText,
                        fontSize = TypeSizes.currentBadge,
                        fontWeight = FontWeight.SemiBold,
                        lineHeightMultiplier = 1f,
                        letterSpacing = 0.9.sp,
                    )
                }
            }
            StatusChip(item.status)
        }
    }
}
