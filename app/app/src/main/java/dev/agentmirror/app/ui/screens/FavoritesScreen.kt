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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import dev.agentmirror.app.ui.components.AppText
import dev.agentmirror.app.ui.components.LanPill
import dev.agentmirror.app.ui.components.PathText
import dev.agentmirror.app.ui.components.RowDivider
import dev.agentmirror.app.ui.components.ScreenHeader
import dev.agentmirror.app.ui.components.SessionNameText
import dev.agentmirror.app.ui.components.StarButton
import dev.agentmirror.app.ui.components.StatusChip
import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus
import dev.agentmirror.app.ui.theme.Dims
import dev.agentmirror.app.ui.theme.LocalAppPalette
import dev.agentmirror.app.ui.theme.Radii
import dev.agentmirror.app.ui.theme.TypeSizes

/**
 * 收藏页。
 * 🔴 行结构按当前版本：星（行首）→ 标题 → 目录副标题 → 右侧状态标。
 * 星在行首，40dp 圆形触控区，与二级会话列表完全一致。
 */
@Composable
fun FavoritesScreen(
    favorites: List<SessionItem>,
    onSessionClick: (SessionItem) -> Unit,
    onToggleStar: (SessionItem) -> Unit,
    modifier: Modifier = Modifier,
    lanConnected: Boolean = true,
    bottomBar: @Composable () -> Unit = {},
) {
    val p = LocalAppPalette.current
    val activeCount = remember(favorites) { favorites.count { it.status == SessionStatus.Busy } }

    Column(modifier.fillMaxSize().background(p.screenBackground)) {
        ScreenHeader(
            title = "收藏",
            meta = "${favorites.size} SESSIONS · $activeCount ACTIVE",
            trailing = if (lanConnected) ({ LanPill() }) else null,
        )
        Box(Modifier.fillMaxWidth().height(Dims.hairline).background(p.divider))
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(p.listBackground)
                .testTag("favorite-list")
        ) {
            items(favorites, key = { it.id }) { item ->
                FavoriteRow(
                    item = item,
                    onClick = { if (item.isOnline) onSessionClick(item) },
                    onToggleStar = { onToggleStar(item) },
                )
                RowDivider()
            }
        }
        bottomBar()
    }
}

@Composable
private fun FavoriteRow(
    item: SessionItem,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dims.rowHeightWithSubtitle)
            .alpha(if (item.isOnline) 1f else 0.45f)
            .padding(start = Dims.listHPaddingStart, end = Dims.listHPaddingEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dims.rowGap),
    ) {
        StarButton(
            starred = item.starred,
            onToggle = onToggleStar,
            modifier = Modifier.testTag("fav-star-${item.id}"),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .height(Dims.rowHeightWithSubtitle)
                .background(if (pressed) p.rowPressed else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .testTag("fav-row-${item.id}"),
            verticalArrangement = Arrangement.Center,
        ) {
            SessionNameText(
                item.displayName,
                Modifier.testTag("fav-id-${item.id}"),
            )
            Box(Modifier.height(Dims.subtitleGap))
            PathText(item.path)
        }
        if (item.isOnline) {
            StatusChip(item.status, Modifier.testTag("fav-status-${item.id}"))
        } else {
            OfflineChip(ref = item.id)
        }
    }
}

/** 067/076：失联标「不在线」，不得改写成 Idle/空闲。 */
@Composable
private fun OfflineChip(ref: String) {
    val p = LocalAppPalette.current
    Row(
        modifier = Modifier
            .height(Dims.statusChipHeight)
            .clip(RoundedCornerShape(Radii.statusChip))
            .background(p.idleChipBg)
            .padding(horizontal = Dims.statusChipHPadding)
            .testTag("fav-offline-$ref"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = "不在线",
            color = p.idleChipText,
            fontSize = TypeSizes.statusChip,
            fontWeight = FontWeight.Medium,
            lineHeightMultiplier = 1f,
        )
    }
}
