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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing

/**
 * 二级菜单列表（061）：每行会话标识 + 右侧状态标。点行用结构 ref + 结构名，title 不参与。
 */
@Composable
internal fun L2SessionList(
    sessions: List<L2Entry>,
    onOpenSession: (ref: String, name: String) -> Unit,
    banner: String? = null,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
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
            Surface(
                onClick = { onOpenSession(entry.ref, entry.navigationName) },
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.testTag("l2-row-${entry.ref}"),
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
                    L2StatusBadge(entry)
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

@Composable
private fun L2StatusBadge(entry: L2Entry) {
    val container = when (entry.status) {
        L2Status.WORKING -> MaterialTheme.colorScheme.primaryContainer
        L2Status.IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh
        L2Status.UNKNOWN -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (entry.status) {
        L2Status.WORKING -> MaterialTheme.colorScheme.onPrimaryContainer
        L2Status.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        L2Status.UNKNOWN -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.testTag("l2-status-${entry.ref}"),
    ) {
        Text(
            text = entry.status.label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
