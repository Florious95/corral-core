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

package dev.agentmirror.app.conn

/**
 * 会话生命周期状态（五值闭集，docs/protocol.md §7.1）。
 *
 * 状态只出现在控制帧（listing / list_delta），永不进入二进制镜像通道；
 * 状态层与镜像层严格解耦（008 隔离铁律）：状态判不出不影响镜像与输入。
 * 任何无法解析的状态值一律降级为 [UNKNOWN]——它是**一等公民值**，不是错误。
 */
enum class AgentState(val wire: String) {
    /** 正在产出输出。 */
    WORKING("working"),

    /** 在场但当前无动作。 */
    IDLE("idle"),

    /** 等待输入（如提示符），需要人。 */
    BLOCKED("blocked"),

    /** 任务完成。 */
    DONE("done"),

    /** 解析失败/无法判定时的兜底值；不参与聚合、不阻断镜像与输入。 */
    UNKNOWN("unknown");

    companion object {
        /** 按线上字符串解析；未识别值返回 [UNKNOWN]（协议规定的一等公民兜底）。 */
        fun fromWire(value: String): AgentState =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}
