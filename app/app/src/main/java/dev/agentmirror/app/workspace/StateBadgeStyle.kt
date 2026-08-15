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

/**
 * 会话状态徽章语义（requirement 008 裁定，docs/protocol.md §5.2 优先级表同源）。
 *
 * 四值闭集（[AgentState]，done 已删）各自对应一种视觉与文案；[UNKNOWN] 是一等公民、灰显，
 * 绝不阻塞列表渲染（008 状态/镜像解耦）。文案与色板是渲染侧的单一事实来源。
 */
enum class StateBadgeStyle(
    /** 徽章文案（中文 UI 文案，展示层用）。 */
    val label: String,
) {
    /** 等待输入、需要人：醒目（红）。 */
    BLOCKED("需人"),

    /** 正在产出：活跃色（蓝）。 */
    WORKING("工作中"),

    /** 在场无动作：中性（灰蓝）。 */
    IDLE("空闲"),

    /** 判不出：灰显、一等公民、不报错。 */
    UNKNOWN("未知");

    companion object {
        /** 由线上状态映射为徽章语义（[AgentState] → [StateBadgeStyle] 双射）。 */
        fun of(state: AgentState): StateBadgeStyle = when (state) {
            AgentState.BLOCKED -> BLOCKED
            AgentState.WORKING -> WORKING
            AgentState.IDLE -> IDLE
            AgentState.UNKNOWN -> UNKNOWN
        }
    }
}
