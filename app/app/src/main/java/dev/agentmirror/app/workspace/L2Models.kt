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
 * 二级菜单一行。身份来自结构字段，状态来自 [Session.effectiveActivity]。
 * [name] 是 server 显示投影，不能回填结构字段；[title] 不参与身份/过滤/判活，
 * 076 §3a 只允许 claude_code 用它当**显示名**。
 */
data class L2Entry(
    val ref: String,
    val name: String,
    val title: String,
    val rows: Int,
    val cols: Int,
    val status: L2Status,
    val provider: String = "unknown",
    val activity: L2Status = status,
    val health: String = "unknown",
    val cwd: String = "",
    val sessionName: String = "",
    val windowIndex: String = "",
    val windowName: String = "",
) {
    val identityLabel: String
        get() = sessionDisplayName(
            windowName = windowName,
            sessionName = sessionName,
            name = name,
            title = title,
            provider = provider,
        )

    /** Navigation structure only; [name] is a display projection and never a fallback. */
    val navigationName: String
        get() = windowName.ifEmpty { sessionName }
}

/**
 * 076 §3a 显示名。Codex 直接取 server name（完整 PaneTitle）；Pi 只取真实
 * session_name；claude_code 取 pane_title 并剥 062 前导状态符号；其余 CLI 取
 * window_name，空时 fallback tmux session。缺失/冲突统一显示「名称未知」。
 * 只用于显示，不参与身份。
 *
 * 符号表与 server/internal/api/l2detect_claudecode.go Match 同一套，禁止另写一份。
 */
internal const val UNKNOWN_SESSION_DISPLAY_NAME = "名称未知"

internal fun sessionDisplayName(
    windowName: String,
    sessionName: String = "",
    name: String = "",
    title: String = "",
    provider: String = "",
): String = when (provider) {
    "codex" -> name.ifEmpty { UNKNOWN_SESSION_DISPLAY_NAME }
    "pi" -> sessionName.ifEmpty { UNKNOWN_SESSION_DISPLAY_NAME }
    else -> {
        if (isClaudeCodeWindow(windowName, name)) {
            val fromTitle = stripClaudeCodeStatusPrefix(title)
            if (fromTitle.isNotEmpty()) fromTitle
            else windowName.ifEmpty { sessionName }.ifEmpty { UNKNOWN_SESSION_DISPLAY_NAME }
        } else {
            windowName.ifEmpty { sessionName }.ifEmpty { UNKNOWN_SESSION_DISPLAY_NAME }
        }
    }
}

internal fun isClaudeCodeWindow(windowName: String, name: String): Boolean =
    windowName == "claude_code" || name == "claude_code"

/** 与 l2detect_claudecode.go 的 ◐◓◑◒ / ✳ 同一套，剥掉后再丢掉紧随的空白。 */
internal fun stripClaudeCodeStatusPrefix(title: String): String {
    val n = title.length
    var i = 0
    while (i < n && title[i].isWhitespace()) i++
    if (i >= n) return ""
    if (title[i] in CLAUDE_CODE_STATUS_PREFIXES) {
        i++
        while (i < n && title[i].isWhitespace()) i++
    }
    return title.substring(i)
}

internal val CLAUDE_CODE_STATUS_PREFIXES = setOf(
    '\u25D0', // ◐
    '\u25D3', // ◓
    '\u25D1', // ◑
    '\u25D2', // ◒
    '\u2733', // ✳
)

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
    // name is a server display projection; structure fields stay independent.
    // Favorite keys must use ref and never recover structure from display text.
    val effective = L2Status.fromWire(effectiveActivity)
    return L2Entry(
        ref = ref,
        name = name,
        title = title,
        rows = rows,
        cols = cols,
        status = effective,
        provider = provider.ifEmpty { "unknown" },
        activity = effective,
        health = health.takeIf { it == "normal" || it == "abnormal" || it == "unknown" } ?: "unknown",
        cwd = cwd,
        sessionName = sessionName.orEmpty(),
        windowIndex = windowIndex,
        windowName = windowName,
    )
}
