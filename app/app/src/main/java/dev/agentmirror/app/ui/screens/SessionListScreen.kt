package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.BackAffordance
import dev.agentmirror.app.ui.components.LanPill
import dev.agentmirror.app.ui.components.PathText
import dev.agentmirror.app.ui.components.RowDivider
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 会话列表（二级，某个工作区内）。
 * 顶部：‹ 工作区 + LAN，下面是工作区名 + 完整路径。
 * 行结构：CLI 工作灯 → 会话显示名 + cwd 路径（66dp）→ 右侧官方 Provider 图标。
 * 整行是唯一手势 owner：短按打开（在线），长按弹出收藏/取消收藏。
 */
@Composable
fun SessionListScreen(
    workspaceName: String,
    workspacePath: String,
    sessions: List<SessionItem>,
    onBack: () -> Unit,
    onSessionClick: (SessionItem) -> Unit,
    onToggleStar: (SessionItem) -> Unit,
    modifier: Modifier = Modifier,
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val p = LocalAppPalette.current
    Column(modifier.fillMaxSize().background(p.screenBackground)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dims.topBarHeight)
                .padding(start = 2.dp, end = Dims.screenHPadding)
                .testTag("session-list-topbar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackAffordance(label = "工作区", onBack = onBack)
            Box(Modifier.weight(1f))
            if (connectionPath != null) LanPill(connectionPath)
        }
        Column(Modifier.padding(start = Dims.screenHPadding, end = Dims.screenHPadding, top = 2.dp, bottom = 13.dp)) {
            AppText(
                text = workspaceName,
                color = p.titleText,
                fontSize = TypeSizes.screenTitleSecondary,
                fontWeight = FontWeight.Bold,
                lineHeightMultiplier = 1.2f,
                letterSpacing = (-0.4).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.height(5.dp))
            PathText(workspacePath)
        }
        Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.divider))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(p.listBackground),
        ) {
            SessionListRows(
                sessions = sessions,
                onSessionClick = onSessionClick,
                onToggleStar = onToggleStar,
                modifier = Modifier.fillMaxSize(),
            )
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

/**
 * 会话行列表（无页头）。二级全屏走 [SessionListScreen]；悬浮窗 / 单测走本函数。
 */
@Composable
fun SessionListRows(
    sessions: List<SessionItem>,
    onSessionClick: (SessionItem) -> Unit,
    onToggleStar: (SessionItem) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "l2",
    listTestTag: String = "l2-session-list-scroll",
) {
    LazyColumn(modifier.testTag(listTestTag)) {
        items(sessions, key = { it.id }) { item ->
            SessionRow(
                item = item,
                tagPrefix = tagPrefix,
                onClick = { onSessionClick(item) },
                onToggleFavorite = { onToggleStar(item) },
                unfavoriteOnly = false,
            )
            RowDivider()
        }
    }
}
