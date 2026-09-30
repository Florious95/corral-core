package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.HeaderRule
import dev.agentmirror.app.ui.components.LanPill
import dev.agentmirror.app.ui.components.LocalFloatingNavInset
import dev.agentmirror.app.ui.components.PathText
import dev.agentmirror.app.ui.components.RuleEdge
import dev.agentmirror.app.ui.components.ScreenHeader
import dev.agentmirror.app.ui.components.TokenText
import dev.agentmirror.app.ui.components.edgeRule
import dev.agentmirror.app.ui.components.glassCard
import dev.agentmirror.app.ui.model.WorkspaceItem
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.ListRowStyle
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.LocalThemeSuite
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/** Stable server order within pinned and unpinned groups. */
internal fun sortWorkspacesPinnedFirst(
    workspaces: List<WorkspaceItem>,
    pinnedPaths: Set<String>,
): List<WorkspaceItem> = workspaces.sortedByDescending { it.path in pinnedPaths }

/**
 * 工作区列表（一级）。
 * 行结构：工作状态麻将牌 → 名称 / 路径 → 会话数 → ›。
 * 麻将牌显示当前工作中的 Agent 数量；0 表示没有已识别的 working 节点。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun WorkspaceListScreen(
    workspaces: List<WorkspaceItem>,
    onWorkspaceClick: (WorkspaceItem) -> Unit,
    onWorkspaceLongClick: (WorkspaceItem) -> Unit = {},
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val p = LocalAppPalette.current
    val rowGap = LocalThemeSuite.current.geometry.listRowGap
    val totalSessions = remember(workspaces) { workspaces.sumOf { it.sessionCount } }

    Column(modifier.fillMaxSize().background(p.screenBackground)) {
        ScreenHeader(
            title = "工作区",
            meta = "${workspaces.size} WORKSPACES · $totalSessions SESSIONS",
            trailing = if (connectionPath != null) ({ LanPill(connectionPath) }) else null,
        )
        HeaderRule()
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("workspace-list-scroll"),
                contentPadding = PaddingValues(top = rowGap, bottom = rowGap + LocalFloatingNavInset.current),
                verticalArrangement = Arrangement.spacedBy(rowGap),
            ) {
                items(workspaces, key = { it.id }) { item ->
                    WorkspaceRow(
                        item = item,
                        onClick = { onWorkspaceClick(item) },
                        onLongClick = { onWorkspaceLongClick(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (connectionBanner != null) {
                AppText(
                    text = connectionBanner,
                    color = p.metaText,
                    fontSize = TypeSizes.statusChip,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(p.consoleBackground)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("connection-banner"),
                )
            }
        }
        bottomBar()
    }
}

internal val MahjongWorkingColor = Color(0xFF047857)
internal val MahjongIdleColor = Color(0xFF6B7280)

/**
 * 一级工作区的工作状态指示牌：纯粹现代的 3:4 竖向圆角状态块（26×34，5dp圆角）。
 * 工作节点>0 为深翠绿 #047857，无工作为灰色 #6B7280，白字等宽数字。
 * 纯平现代设计，彻底废止任何拟物麻将釉光与弧面反光。
 */
@Composable
internal fun MahjongStatusBadge(
    workingCount: Int,
    modifier: Modifier = Modifier,
) {
    val count = workingCount.coerceAtLeast(0)
    val fontSize = when {
        count >= 100 -> 10.sp
        count >= 10 -> 12.sp
        else -> 14.sp
    }
    Box(
        modifier
            .width(26.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (count > 0) MahjongWorkingColor else MahjongIdleColor)
            .border(Dims.hairline, Color.White.copy(alpha = 0.22f), RoundedCornerShape(5.dp))
            .testTag("mahjong-status-badge"),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = count.toString(),
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1f,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceRow(
    item: WorkspaceItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalThemeSuite.current.recipes.listRow == ListRowStyle.RuledStrip) {
        RuledWorkspaceRow(item, onClick, onLongClick, modifier)
        return
    }
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .padding(horizontal = Dims.cardHMargin)
            .fillMaxWidth()
            .height(Dims.rowHeightWithSubtitle)
            .glassCard(
                shape = RoundedCornerShape(Radii.card),
                fill = if (pressed) p.glassCardFillPressed else p.glassCardFill,
                stroke = p.glassStroke,
                specular = p.glassSpecular,
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 14.dp, end = 12.dp)
            .testTag("workspace-row-${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MahjongStatusBadge(workingCount = item.workingCount)
        Column(Modifier.weight(1f)) {
            AppText(
                text = item.name,
                color = p.rowTitleText,
                fontSize = TypeSizes.rowTitle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.height(Dims.subtitleGap))
            PathText(item.path)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AppText(
                text = item.sessionCount.toString(),
                color = p.metaText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                lineHeightMultiplier = 1f,
            )
            AppText("会话", p.pathText, 10.5f.sp, lineHeightMultiplier = 1f)
        }
        AppText("›", p.starOff, 18.sp, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1f)
    }
}

/**
 * 通栏直角工作区行：56dp 等宽计数列（工作中 > 0 为猩红，否则待机灰）+ 竖直发丝线 + 名称 / 路径 + 会话数 + ›，
 * 底部 1dp 发丝线。手势与 [WorkspaceRow] 相同（短按进入、长按操作面板）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuledWorkspaceRow(
    item: WorkspaceItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val p = LocalAppPalette.current
    val kit = LocalThemeSuite.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val count = item.workingCount.coerceAtLeast(0)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(kit.geometry.workspaceRowHeight)
            .background(if (pressed) p.glassCardFillPressed else p.glassCardFill)
            .edgeRule(RuleEdge.Bottom, kit.colors.separator, kit.geometry.hairline)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .testTag("workspace-row-${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(kit.geometry.numberColumnWidth)
                .fillMaxHeight()
                .edgeRule(RuleEdge.End, kit.colors.separator, kit.geometry.hairline)
                .testTag("mahjong-status-badge"),
            contentAlignment = Alignment.Center,
        ) {
            TokenText(
                text = count.toString(),
                color = if (count > 0) p.accent else kit.colors.statusPending,
                token = kit.typography.numericBadge,
                maxLines = 1,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 12.dp),
        ) {
            AppText(
                text = item.name,
                color = p.rowTitleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.height(Dims.subtitleGap))
            PathText(item.path)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AppText(
                text = item.sessionCount.toString(),
                color = p.metaText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                lineHeightMultiplier = 1f,
            )
            AppText("会话", p.pathText, 10.5f.sp, lineHeightMultiplier = 1f)
        }
        AppText(
            text = "›",
            color = p.rowTitleText,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1f,
            modifier = Modifier.padding(start = 12.dp, end = kit.geometry.pageInset),
        )
    }
}
