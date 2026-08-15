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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmirror.app.conn.AgentState
import dev.agentmirror.app.ui.theme.LocalStateTones
import dev.agentmirror.app.ui.theme.MonoFontFamily
import dev.agentmirror.app.ui.theme.Spacing
import dev.agentmirror.app.ui.theme.StateTone

/** 由徽章语义取四态色调（色板在 Theme 深浅双套 token 化，018 §一.1/一.5）。 */
@Composable
private fun StateBadgeStyle.tone(): StateTone {
    val tones = LocalStateTones.current
    return when (this) {
        StateBadgeStyle.BLOCKED -> tones.blocked
        StateBadgeStyle.WORKING -> tones.working
        StateBadgeStyle.IDLE -> tones.idle
        StateBadgeStyle.UNKNOWN -> tones.unknown
    }
}

/**
 * 状态徽章：实心状态点 + 淡容器胶囊 + 文案。
 *
 * 设计决策（018 §一.5 状态可视）：旧版高饱和实底白字在列表里五个「色块」互相打架；
 * 改 tonal 语法——淡容器底 + 深内容字，视觉重量退到辅层级，扫读靠左侧高饱和圆点。
 * 色弱可辨不依赖色相：四态文案各异 + contentDescription（017 R-7），深浅双套各自配色。
 * 工作区聚合状态与单会话状态共用；unknown 灰显一等公民不阻塞渲染（008）。
 */
@Composable
fun StateBadge(state: AgentState, modifier: Modifier = Modifier) {
    val style = StateBadgeStyle.of(state)
    val tone = style.tone()
    Row(
        modifier = modifier
            .background(color = tone.container, shape = RoundedCornerShape(50))
            .padding(horizontal = Spacing.sm + 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 状态点：高饱和原色，列表扫读的第一信号（文字是第二信号，互为备份）。
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color = tone.dot, shape = CircleShape),
        )
        Text(
            text = style.label,
            style = MaterialTheme.typography.labelSmall,
            color = tone.content,
            maxLines = 1,
            // R-7（017）：颜色不作唯一信息载体——语义化供 TalkBack 朗读（加法性，不改视觉）。
            modifier = Modifier.semantics { contentDescription = "状态：${style.label}" },
        )
    }
}

/**
 * 工作区一级列表行（cwd 行）内容布局，018 §一.3 层级重做（图28 缺陷修复）：
 * - 主：目录名（末段，titleMedium 单行尾省略）——用户扫读认的是项目名；
 * - 辅：完整路径（等宽 bodySmall 单行**中段省略**，语义树仍持全路径全文，e2e 语义定位不受影响）；
 * - 次：右侧会话数 + 聚合状态徽章，与主行基线对齐（旧版数字悬空缺陷）。
 *
 * 一级=cwd 聚合（002）；session_count / aggregate_state 均为服务端权威值，只渲染（012）。
 */
@Composable
internal fun WorkspaceRow(
    cwd: String,
    sessionCount: Int,
    aggregateState: AgentState,
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
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            StateBadge(state = aggregateState)
            Text(
                text = "$sessionCount 个会话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
