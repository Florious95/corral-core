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

import dev.agentmirror.app.conn.Session

/**
 * 二级状态标（061）。缺省 / 乱值 / 空串一律 [UNKNOWN]，禁止回落成 [IDLE]。
 */
enum class L2Status(val wire: String, val label: String) {
    WORKING("working", "进行中"),
    IDLE("idle", "空闲"),
    UNKNOWN("unknown", "未知"),
    ;

    companion object {
        fun fromWire(raw: String): L2Status = when (raw) {
            WORKING.wire -> WORKING
            IDLE.wire -> IDLE
            else -> UNKNOWN
        }
    }
}

/**
 * 二级菜单一行。身份来自结构字段，状态来自 [Session.status]，[title] 不参与二者。
 */
data class L2Entry(
    val ref: String,
    val name: String,
    val title: String,
    val rows: Int,
    val cols: Int,
    val status: L2Status,
    val cwd: String = "",
    val sessionName: String = "",
    val windowIndex: String = "",
    val windowName: String = "",
) {
    val identityLabel: String
        get() = windowName.ifEmpty { sessionName }.ifEmpty { name }

    val navigationName: String
        get() = identityLabel
}

data class L2UiState(
    val sessions: List<L2Entry> = emptyList(),
    val seq: Long = 0L,
    val banner: String? = null,
)

internal fun Session.toL2Entry(): L2Entry {
    // 线上 Session 只有 name（window_name fallback session_name），三元组常缺省。
    // 收藏键必须落结构字段：空三元组回填 name，永不回填 title。
    val resolvedWindow = windowName.ifEmpty { name }
    val resolvedSession = sessionName.ifEmpty { name }
    return L2Entry(
        ref = ref,
        name = resolvedWindow.ifEmpty { resolvedSession },
        title = title,
        rows = rows,
        cols = cols,
        status = L2Status.fromWire(status),
        cwd = cwd,
        sessionName = resolvedSession,
        windowIndex = windowIndex,
        windowName = resolvedWindow,
    )
}
