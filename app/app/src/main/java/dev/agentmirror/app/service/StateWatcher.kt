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

package dev.agentmirror.app.service

import dev.agentmirror.app.conn.AgentState
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListingFrame

/**
 * 状态守望者（纯 JVM，服务核心逻辑，验收单测全部打在这里）。
 *
 * 消费 conn 层 listing / list_delta 帧流，检测会话状态**沿变化**，向调用方输出两类事件：
 * - [onNotify]：会话**首次进入** blocked/done（需要人注意）时触发一次；同状态重复推送抑制；
 *   unknown 永不通知；blocked→done 仍属沿变化，刷新通知内容。
 * - [onClear]：会话离开 blocked/done（状态恢复）或会话消失（removed_refs / listing 缺席）时触发。
 *
 * 语义（对齐 003 标准四「需要时被唤醒」）：
 * - **初始 listing 是基线**：只记录各会话当前状态，不触发任何通知——沿变化才通知，
 *   非全量罗列（否则每次服务冷启动都会把已 blocked/done 的舰队会话全量轰炸一遍）。
 * - **断连期间的状态变化在重连 listing 时补齐触发**：本对象持有最近已知状态（跨断连保留，
 *   服务进程存活期间持续），重连全量列表与旧状态逐会话比对：变化一次触发、未变化抑制——
 *   不因断连丢唤醒，也不因重连重复轰炸。
 * - conn 层保证 list_delta 不先于 listing（seq 不连续 / delta 先到即自动重新 list，
 *   见 ConnectionManager.onFrame），本对象按"有基线在前"的顺序消费即可。
 */
class StateWatcher(
    private val onNotify: (ref: String, name: String, state: AgentState) -> Unit,
    private val onClear: (ref: String) -> Unit,
) {
    /** 最近已知状态（ref → state）；跨断连保留，供重连全量比对。 */
    private val lastState = LinkedHashMap<String, AgentState>()

    /** 当前处于通知中的状态（ref → 通知时的状态）；用于同状态抑制与离开清除。 */
    private val notified = LinkedHashMap<String, AgentState>()

    /** 是否已建立初始基线（首个 listing 到达即置位）。 */
    private var baseline = false

    /** 帧入口：只消费 listing / list_delta，其余帧忽略（本层只关心会话状态）。 */
    fun onFrame(frame: FramePayload) {
        when (frame) {
            is ListingFrame -> applyListing(frame)
            is ListDeltaFrame -> applyDelta(frame)
            else -> Unit
        }
    }

    /**
     * 全量列表：权威整体替换。
     *
     * 首个 listing 只建基线（记录状态、不通知）；后续 listing（重连全量）逐会话比对旧状态，
     * 变化沿触发、未变化抑制；缺席 = 会话消失 → 清除其通知。
     */
    private fun applyListing(frame: ListingFrame) {
        val isBaseline = !baseline
        baseline = true
        val seen = HashSet<String>()
        for (w in frame.workspaces) {
            for (s in w.sessions) {
                seen.add(s.ref)
                val prev = lastState.put(s.ref, s.state)
                if (!isBaseline) {
                    handle(ref = s.ref, name = s.name, prev = prev, curr = s.state)
                }
            }
        }
        // 非基线 listing 是权威全量：缺席 = 会话消失 → 清除其通知。
        if (!isBaseline) {
            for (ref in lastState.keys.toList()) {
                if (ref !in seen) remove(ref)
            }
        }
    }

    /** 增量：added/changed 按 ref upsert，removed 按 ref 清除（协议 §5.3 两两不相交）。 */
    private fun applyDelta(frame: ListDeltaFrame) {
        for (s in frame.addedSessions) {
            val prev = lastState.put(s.ref, s.state)
            handle(ref = s.ref, name = s.name, prev = prev, curr = s.state)
        }
        for (s in frame.changedSessions) {
            val prev = lastState.put(s.ref, s.state)
            handle(ref = s.ref, name = s.name, prev = prev, curr = s.state)
        }
        for (ref in frame.removedRefs) remove(ref)
    }

    /**
     * 沿变化判定（一次且仅一次的抑制逻辑核心）：
     * - 状态**变化**进入 blocked/done，且当前通知内容不是该状态 → [onNotify]；
     *   - 首次进入（未通知）→ 通知；
     *   - blocked→done 等"仍需要注意但状态变了"→ 刷新通知内容；
     *   - 状态未变（prev==curr，同状态重复推送）→ 抑制；
     *   - 已通知同状态且未变 → 抑制。
     * - 离开 blocked/done（状态恢复）→ [onClear]，无需人再注意。
     */
    private fun handle(ref: String, name: String, prev: AgentState?, curr: AgentState) {
        val changed = prev != curr
        val needsAttention = curr == AgentState.BLOCKED || curr == AgentState.DONE
        if (needsAttention) {
            if (changed && notified[ref] != curr) {
                notified[ref] = curr
                onNotify(ref, name, curr)
            }
            // 未变化（同状态）或已通知同状态 → 抑制，无动作。
        } else if (notified.remove(ref) != null) {
            onClear(ref)
        }
    }

    /** 会话消失：移出已知状态；若正通知中则清除。 */
    private fun remove(ref: String) {
        lastState.remove(ref)
        if (notified.remove(ref) != null) onClear(ref)
    }
}
