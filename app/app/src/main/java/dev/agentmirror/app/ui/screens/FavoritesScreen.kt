package dev.agentmirror.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.LanPill
import dev.agentmirror.app.ui.components.RowDivider
import dev.agentmirror.app.ui.components.ScreenHeader
import dev.agentmirror.app.ui.components.SessionRow
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 收藏页。与普通会话列表同一行结构：CLI 工作灯 / 不在线、标题+路径、右侧官方 Provider 图标。
 * 整行唯一手势：在线短按打开，离线短按不导航；长按唯一「取消收藏」。
 *
 * @contract
 * @pre favorites are the same SessionItem rows as the ordinary list
 * @post row layout matches SessionListScreen; long-press is unfavorite only
 * @err none
 * @inv offline short-press does not navigate
 */
@Composable
fun FavoritesScreen(
    favorites: List<SessionItem>,
    onSessionClick: (SessionItem) -> Unit,
    onToggleStar: (SessionItem) -> Unit,
    modifier: Modifier = Modifier,
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val p = LocalAppPalette.current
    val activeCount = remember(favorites) { favorites.count { it.status == SessionStatus.Busy } }

    Column(modifier.fillMaxSize().background(p.screenBackground)) {
        ScreenHeader(
            title = "收藏",
            meta = "${favorites.size} SESSIONS · $activeCount ACTIVE",
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
                    .testTag("favorite-list")
            ) {
                items(favorites, key = { it.id }) { item ->
                    SessionRow(
                        item = item,
                        tagPrefix = "fav",
                        onClick = { onSessionClick(item) },
                        onToggleFavorite = { onToggleStar(item) },
                        unfavoriteOnly = true,
                    )
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
