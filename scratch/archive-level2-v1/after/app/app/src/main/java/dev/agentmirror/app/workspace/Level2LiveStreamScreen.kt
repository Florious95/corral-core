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
 * 二级菜单实时流渲染（060 t.app）：服务端推来的会话行，**标题原样画**（一个字符都不解析）。
 *
 * - 每行主文本 = `title`（pane_title 原样，含 ◐/✳ 前缀与尾空格），**零字符串处理**；
 * - 每行次行 = `name`（结构字段展示名，window_name fallback session_name），可做次级标签；
 * - 点行 → [onOpenSession](ref, name)，ref 是结构字段身份（socket+paneid），**title 不参与
 *   寻址**——三级终端唯一入口。
 *
 * 不复活：无状态徽章、无下拉刷新、无二级 diff 模型（060 重建纪律）。
 *
 * @contract
 * @pre sessions 为服务端 Level2Frame 的全量快照（title 原样）
 * @post 每行 title 原样渲染；点行回调 [onOpenSession] 携带结构 ref
 * @err none
 * @inv 对 title 不做任何 startsWith/contains/trim/replace/removePrefix/substring
 */
@Composable
internal fun Level2LiveStreamScreen(
    sessions: List<Level2Entry>,
    onOpenSession: (ref: String, name: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        items(sessions, key = { it.ref }) { entry ->
            // Surface onClick：ripple 点击态 + 48dp 最小触控目标（018 §一.4）。
            Surface(
                onClick = { onOpenSession(entry.ref, entry.name) },
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
                    // 标题原样画：pane_title 逐字节，不 trim/不剥前缀/不匹配/不映射。
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 结构字段展示名（window_name fallback session_name），次级标签。
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
