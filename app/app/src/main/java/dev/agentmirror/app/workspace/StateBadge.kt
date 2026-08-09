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

import dev.agentmirror.app.conn.AgentState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 状态徽章各状态的背景色（008：blocked 醒目、done 完成色、working 活跃色、idle 中性、unknown 灰显）。 */
private val badgeColors: Map<StateBadgeStyle, Color> = mapOf(
    StateBadgeStyle.BLOCKED to Color(0xFFB3261E), // 醒目红：需要人
    StateBadgeStyle.DONE to Color(0xFF2E7D32), // 完成绿
    StateBadgeStyle.WORKING to Color(0xFF1565C0), // 活跃蓝
    StateBadgeStyle.IDLE to Color(0xFF546E7A), // 中性蓝灰
    StateBadgeStyle.UNKNOWN to Color(0xFF9E9E9E), // 灰显：一等公民兜底，不报错
)

/**
 * 状态徽章（小圆角胶囊 + 文案）。
 *
 * 工作区聚合状态与单会话状态共用同一组件；unknown 灰显不阻塞列表渲染（008）。
 */
@Composable
fun StateBadge(state: AgentState, modifier: Modifier = Modifier) {
    val style = StateBadgeStyle.of(state)
    val color = badgeColors.getValue(style)
    Box(
        modifier = modifier
            .background(color = color, shape = RoundedCornerShape(50))
            .defaultMinSize(minWidth = 44.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = style.label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                // R-7（017 当期裁定）：颜色不作唯一信息载体——徽章语义化，供 TalkBack 朗读
                // （text 与 contentDescription 并存，取后者播报；加法性，不改变视觉渲染）。
                .semantics { contentDescription = "状态：${style.label}" },
        )
    }
}

/**
 * 工作区一级列表条目布局（cwd 行）：cwd + 会话数徽章 + 聚合状态徽章。
 *
 * 一级=cwd 聚合（002）；session_count / aggregate_state 均为服务端权威值，只渲染（012）。
 */
@Composable
internal fun WorkspaceRow(
    cwd: String,
    sessionCount: Int,
    aggregateState: dev.agentmirror.app.conn.AgentState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = cwd,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$sessionCount",
            style = MaterialTheme.typography.labelMedium,
        )
        StateBadge(state = aggregateState)
    }
}
