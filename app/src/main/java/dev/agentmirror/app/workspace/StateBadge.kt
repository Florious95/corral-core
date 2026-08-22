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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing

/**
 * 工作区一级列表行（cwd 行）内容布局，018 §一.3 层级重做（图28 缺陷修复）：
 * - 主：目录名（末段，titleMedium 单行尾省略）——用户扫读认的是项目名；
 * - 辅：完整路径（等宽 bodySmall 单行**中段省略**，语义树仍持全路径全文，e2e 语义定位不受影响）；
 * - 次：右侧会话数，与主行基线对齐（旧版数字悬空缺陷）。
 *
 * 一级=cwd 聚合（002）；session_count 为服务端权威值，只渲染。
 *
 * 060 uproot（2026-08-15）：状态徽章与聚合状态随状态判定整体拔除，
 * 本行不再展示聚合状态，只保留目录名 / 路径 / 会话数。
 */
@Composable
internal fun WorkspaceRow(
    cwd: String,
    sessionCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 终端提示符图标位：等宽「❯」+ primaryContainer 圆角方块，产品身份语言
        // （零图标库依赖：material-icons 无终端/目录形，字形即品牌）。
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "❯",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = cwdDisplayName(cwd),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 完整路径单行中段省略（018 §一.3 长文本截断有设计；语义 text 仍是全路径，
            // uiautomator 精确匹配 cwd 的既有 e2e 选择器继续可用）。
            Text(
                text = cwd,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Text(
            text = "$sessionCount 个会话",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
