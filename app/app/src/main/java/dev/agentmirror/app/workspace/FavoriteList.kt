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

package dev.agentmirror.app.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.ui.screens.FavoritesScreen
import dev.agentmirror.app.ui.theme.AppTheme

/**
 * 收藏列表：按加入时间倒序。失联行置灰标「不在线」，不可点进，可取消收藏。
 * 076 §3：星在行首、标题、目录副标题、右侧状态标（与会话列表同构）。
 * 094：本页不套 [sortSessions]（「运行中靠前」只属于会话页 088）；点击按 ref 身份绑定。
 *
 * @contract
 * @pre rows 已是收藏稳定次序（[FavoriteBook.rows] 按 addedAt 倒序）
 * @post 展示序与 rows 相同；onOpenSession(ref) == 被点行 ref
 * @inv 运行状态变化不改序；禁止 zip(sortedDisplay, unsortedRows)
 */
@Composable
fun FavoriteList(
    rows: List<FavoriteRow>,
    onOpenSession: (ref: String, name: String) -> Unit,
    onUnfavorite: (FavoriteRow) -> Unit,
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
) {
    val items = rows.map { it.toSessionItem() }
    val byId = rows.associateBy { it.ref }
    AppTheme {
        FavoritesScreen(
            favorites = items,
            onSessionClick = { item ->
                val row = byId[item.id] ?: return@FavoritesScreen
                if (row.isOnline && row.ref.isNotEmpty()) {
                    onOpenSession(row.ref, row.identityLabel)
                }
            },
            onToggleStar = { item ->
                byId[item.id]?.let(onUnfavorite)
            },
            connectionPath = connectionPath,
            connectionBanner = connectionBanner,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        )
    }
}
