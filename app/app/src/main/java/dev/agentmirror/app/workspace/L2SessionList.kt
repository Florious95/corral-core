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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing

/** 072 §3：星星点击指示必须无界/圆形，禁止 Material 默认方形 bounded ripple。 */
internal const val L2_STAR_RIPPLE_BOUNDED = false

/**
 * 二级菜单列表（061/067/072）：每行星标在会话名之前，右侧状态标。
 * 点行用结构 ref + 结构名，title 不参与。点星只切换收藏。
 */
@Composable
internal fun L2SessionList(
    sessions: List<L2Entry>,
    onOpenSession: (ref: String, name: String) -> Unit,
    banner: String? = null,
    favorited: Set<FavoriteKey> = emptySet(),
    onToggleFavorite: (L2Entry) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .testTag("l2-session-list-scroll"),
    ) {
        if (banner != null) {
            item(key = "l2-stale-banner") {
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
        }
        items(sessions, key = { it.ref }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = Spacing.pageH, vertical = Spacing.rowV),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val starred = favorited.contains(entry.favoriteKey())
                L2FavoriteStar(
                    starred = starred,
                    onClick = { onToggleFavorite(entry) },
                    tag = "l2-star-${entry.ref}",
                )
                Surface(
                    onClick = { onOpenSession(entry.ref, entry.navigationName) },
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("l2-row-${entry.ref}"),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = entry.identityLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("l2-id-${entry.ref}"),
                        )
                        val secondary = buildString {
                            if (entry.windowIndex.isNotEmpty()) {
                                append('#')
                                append(entry.windowIndex)
                            }
                            if (entry.cwd.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append(entry.cwd)
                            }
                        }
                        if (secondary.isNotEmpty()) {
                            Text(
                                text = secondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                }
                L2StatusBadge(status = entry.status, ref = entry.ref)
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = Spacing.pageH),
            )
        }
    }
}

@Composable
internal fun L2StatusBadge(
    status: L2Status,
    ref: String,
    tagPrefix: String = "l2-status",
) {
    val container = when (status) {
        L2Status.WORKING -> MaterialTheme.colorScheme.primaryContainer
        L2Status.IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh
        L2Status.UNKNOWN -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (status) {
        L2Status.WORKING -> MaterialTheme.colorScheme.onPrimaryContainer
        L2Status.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        L2Status.UNKNOWN -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier
            .testTag("$tagPrefix-$ref")
            .width(80.dp)
            .height(24.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                textAlign = TextAlign.Center,
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
