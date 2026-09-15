package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.agentmirror.app.ui.components.LanPill
import dev.agentmirror.app.ui.components.PathText
import dev.agentmirror.app.ui.components.RowDivider
import dev.agentmirror.app.ui.components.ScreenHeader
import dev.agentmirror.app.ui.model.WorkspaceItem
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.TypeSizes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/**
 * 工作区列表（一级）。
 * 行结构：工作状态麻将牌 → 名称 / 路径 → 会话数 → ›。
 * 麻将牌显示当前工作中的 Agent 数量；0 表示没有已识别的 working 节点。
 */
@Composable
fun WorkspaceListScreen(
    workspaces: List<WorkspaceItem>,
    onWorkspaceClick: (WorkspaceItem) -> Unit,
    modifier: Modifier = Modifier,
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val p = LocalAppPalette.current
    val totalSessions = remember(workspaces) { workspaces.sumOf { it.sessionCount } }

    Column(modifier.fillMaxSize().background(p.screenBackground)) {
        ScreenHeader(
            title = "工作区",
            meta = "${workspaces.size} WORKSPACES · $totalSessions SESSIONS",
            trailing = if (connectionPath != null) ({ LanPill(connectionPath) }) else null,
        )
        Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.divider))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(p.listBackground),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .testTag("workspace-list-scroll")
            ) {
                items(workspaces, key = { it.id }) { item ->
                    WorkspaceRow(item = item, onClick = { onWorkspaceClick(item) })
                    RowDivider()
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

/** 一级工作区的工作状态指示牌：固定 3:4 竖向圆角矩形，不使用系统 emoji。 */
@Composable
internal fun MahjongStatusBadge(
    workingCount: Int,
    modifier: Modifier = Modifier,
) {
    val count = workingCount.coerceAtLeast(0)
    Box(
        modifier
            .width(26.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (count > 0) MahjongWorkingColor else MahjongIdleColor)
            .testTag("mahjong-status-badge"),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = count.toString(),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            lineHeightMultiplier = 1f,
        )
    }
}

@Composable
private fun WorkspaceRow(
    item: WorkspaceItem,
    onClick: () -> Unit,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dims.rowHeightWithSubtitle)
            .background(if (pressed) p.rowPressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 14.dp, end = 12.dp),
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
