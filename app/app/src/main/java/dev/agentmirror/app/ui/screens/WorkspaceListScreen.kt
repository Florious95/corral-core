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
import androidx.compose.foundation.layout.size
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
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/**
 * 工作区列表（一级）。
 * 行结构：❯ 方块 → 名称 / 路径 → 会话数 → ›
 * ❯ 方块从原来的 44dp 蓝色大块缩到 34dp 扁平化，保留作品牌符号但不再抢视线。
 */
@Composable
fun WorkspaceListScreen(
    workspaces: List<WorkspaceItem>,
    onWorkspaceClick: (WorkspaceItem) -> Unit,
    modifier: Modifier = Modifier,
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
    bottomBar: @Composable () -> Unit = {},
    onNewAgent: () -> Unit = {},
) {
    val p = LocalAppPalette.current
    val totalSessions = remember(workspaces) { workspaces.sumOf { it.sessionCount } }

    Column(modifier.fillMaxSize().background(p.screenBackground)) {
        ScreenHeader(
            title = "工作区",
            meta = "${workspaces.size} WORKSPACES · $totalSessions SESSIONS",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .testTag("workspace-new-agent")
                            .clickable(onClick = onNewAgent),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppText("+", p.accent, 20.sp, fontWeight = FontWeight.Light)
                    }
                    if (connectionPath != null) LanPill(connectionPath)
                }
            },
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
        Box(
            Modifier
                .size(Dims.workspaceGlyphBox)
                .clip(RoundedCornerShape(Radii.workspaceGlyphBox))
                .background(p.accentContainer),
            contentAlignment = Alignment.Center,
        ) {
            AppText("❯", p.accent, 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, lineHeightMultiplier = 1f)
        }
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
