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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.screens.SessionListRows
import dev.agentmirror.app.ui.theme.AppTheme
import dev.agentmirror.app.ui.theme.Spacing

/** 072 §3：星星点击指示必须无界/圆形，禁止 Material 默认方形 bounded ripple。 */
internal const val L2_STAR_RIPPLE_BOUNDED = false

/**
 * 二级菜单列表（061/067/072）：每行星标在会话名之前，右侧状态标。
 * 点行用结构 ref + 结构名，title 不参与。点星只切换收藏。
 * 094：展示可按 [sortSessions] 动态重排，点击必须按 ref 身份查源记录，禁止按下标 zip。
 *
 * @contract
 * @pre sessions 身份键为 ref
 * @post onOpenSession 的 ref == 被点展示项 id
 * @inv 重排后点击行 N 打开的仍是行 N 展示的会话
 */
@Composable
internal fun L2SessionList(
    sessions: List<L2Entry>,
    onOpenSession: (ref: String, name: String) -> Unit,
    banner: String? = null,
    favorited: Set<FavoriteKey> = emptySet(),
    onToggleFavorite: (L2Entry) -> Unit = {},
) {
    val items = sortSessions(
        sessions.map { it.toSessionItem(starred = favorited.contains(it.favoriteKey())) },
    )
    val byId = sessions.associateBy { it.ref }
    AppTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            if (banner != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.pageH, vertical = Spacing.xs)
                        .testTag("l2-stale-banner"),
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    )
                }
            }
            SessionListRows(
                sessions = items,
                onSessionClick = { item ->
                    val entry = byId[item.id] ?: return@SessionListRows
                    onOpenSession(entry.ref, entry.identityLabel)
                },
                onToggleStar = { item ->
                    byId[item.id]?.let(onToggleFavorite)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                showPath = true,
            )
        }
    }
}

@Composable
internal fun L2FavoriteStar(
    starred: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .testTag(tag)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = L2_STAR_RIPPLE_BOUNDED, radius = 24.dp),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (starred) "★" else "☆",
            style = MaterialTheme.typography.titleMedium,
            color = if (starred) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
