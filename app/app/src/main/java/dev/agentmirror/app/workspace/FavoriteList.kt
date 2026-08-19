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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing

/**
 * 收藏列表：按加入时间倒序。失联行置灰标「不在线」，不可点进，可取消收藏。
 */
@Composable
fun FavoriteList(
    rows: List<FavoriteRow>,
    onOpenSession: (ref: String, name: String) -> Unit,
    onUnfavorite: (FavoriteRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .testTag("favorite-list"),
    ) {
        items(
            rows,
            key = { row ->
                row.ref.ifEmpty {
                    "legacy-${row.addedAt}-${row.sessionName}-${row.windowIndex}-${row.windowName}"
                }
            },
        ) { row ->
            val dim = if (row.isOnline) 1f else 0.45f
            Surface(
                onClick = {
                    if (row.isOnline && row.ref.isNotEmpty()) {
                        onOpenSession(row.ref, row.identityLabel)
                    }
                },
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .alpha(dim)
                    .testTag("fav-row-${row.ref}"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .padding(horizontal = Spacing.pageH, vertical = Spacing.rowV),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = row.identityLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val secondary = buildString {
                            if (row.windowIndex.isNotEmpty()) {
                                append('#')
                                append(row.windowIndex)
                            }
                            if (row.cwd.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append(row.cwd)
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
                    Text(
                        text = "★",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .testTag("fav-star-${row.ref}")
                            .clickable { onUnfavorite(row) }
                            .padding(4.dp),
                    )
                    if (!row.isOnline) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = "不在线",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("fav-offline-${row.ref}"),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = Spacing.pageH),
            )
        }
    }
}
