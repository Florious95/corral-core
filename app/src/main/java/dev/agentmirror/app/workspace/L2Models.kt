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
 * 二级菜单一行。身份来自结构字段 / ref，状态来自 [Session.status]。
 * [title] 不参与身份/过滤/判活/显示名；列表与顶栏只消费服务端 [Session.name]。
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
        get() = sessionDisplayName(name)

    /** 结构导航标签；空时不回填显示 [name]。 */
    val navigationName: String
        get() = windowName.ifEmpty { sessionName }
}

/** 服务端未给出可用 Session.name 时的统一空值占位。 */
internal const val UNNAMED_SESSION = "未命名会话"

/**
 * 列表 / 收藏 / 查看菜单 / 顶栏显示名。
 *
 * 只转交服务端已解析的 [Session.name] 与统一空值占位。
 * 禁止按 Provider、window_name、pane_title 或原生 session_name 再猜一遍。
 */
internal fun sessionDisplayName(name: String): String =
    name.trim().ifEmpty { UNNAMED_SESSION }

/**
 * 顶栏跟当前 ref 的 live [Session.name]。overlay 尚未含该行时才用导航带来的 fallback。
 */
internal fun liveSessionDisplayName(
    sessionRef: String,
    overlaySessions: List<L2Entry>,
    fallback: String,
): String {
    val live = overlaySessions.firstOrNull { it.ref == sessionRef }?.name
    return sessionDisplayName(live ?: fallback)
}

data class L2UiState(
    val sessions: List<L2Entry> = emptyList(),
    val seq: Long = 0L,
    val banner: String? = null,
)

/**
 * 「查看」浮层读哪一份二级列表（076 §1）。
 *
 * 当前会话工作区键 [currentWorkspace] 与浮层实际采用的 [overlayWorkspace]
 * 必须相同；[lastPublishedWorkspace] 是被最后一次 [WorkspaceViewModel.enterLevel2]
 * / 收藏覆盖的单例，只作对照，不得当浮层输入。
 *
 * 先收藏 A 再收藏 B 之后：[lastPublishedWorkspace] 必是 B。
 * overlay==B 且 current==A ⇒ 读错了源；overlay==A 且 lastPublished 仍是 A ⇒ 没刷新。
 */
data class ViewMenuSource(
    val currentSessionRef: String,
    val currentWorkspace: String,
    val currentSocket: String,
    val overlayWorkspace: String,
    val overlaySocket: String,
    val lastPublishedWorkspace: String,
    val sessions: List<L2Entry>,
)

/** ref = socket + U+001F + pane_id；无结构分隔或非路径则空。 */
internal fun socketPrefixFromRef(ref: String): String {
    val unitSep = ref.indexOf('\u001f')
    val literalSep = ref.indexOf("\\u001f")
    val sep = when {
        unitSep > 0 -> unitSep
        literalSep > 0 -> literalSep
        else -> -1
    }
    val socket = if (sep > 0) ref.substring(0, sep) else ref
    return if (socket.contains('/')) socket else ""
}

internal fun Session.toL2Entry(): L2Entry {
    // name 是服务端显示投影；结构字段原样保留，禁止用显示名回填 window/session。
    return L2Entry(
        ref = ref,
        name = name,
        title = title,
        rows = rows,
        cols = cols,
        status = L2Status.fromWire(status),
        cwd = cwd,
        sessionName = sessionName,
        windowIndex = windowIndex,
        windowName = windowName,
    )
}
