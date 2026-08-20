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
 */
@Composable
fun FavoriteList(
    rows: List<FavoriteRow>,
    onOpenSession: (ref: String, name: String) -> Unit,
    onUnfavorite: (FavoriteRow) -> Unit,
    connectionPath: ConnectionPath? = null,
    connectionBanner: String? = null,
) {
    val items = sortSessions(rows.map { it.toSessionItem() })
    val byId = items.zip(rows).associate { it.first.id to it.second }
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
