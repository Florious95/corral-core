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
import dev.agentmirror.app.ui.model.SessionItem

/**
 * Non-empty production-protocol session list for debug/MCP fixture and
 * instrumented SessionRoute. Same [Session] → [L2Entry] mapping as
 * [WorkspaceViewModel] after a level2 frame.
 */
object ProductionOverlayFixture {
    const val WORKSPACE = "/audit/workspace-A"
    const val CURRENT_REF = "/tmp/tmux-1000/audit\u001f%1"
    const val PEER_REF = "/tmp/tmux-1000/audit\u001f%2"
    const val THIRD_REF = "/tmp/tmux-1000/audit\u001f%3"

    fun protocolSessions(): List<Session> = listOf(
        Session(
            ref = CURRENT_REF,
            name = "A 当前会话",
            cwd = WORKSPACE,
            rows = 24,
            cols = 80,
            title = "A 当前会话",
            status = "working",
            sessionName = "audit",
            windowIndex = "1",
            windowName = "A 当前会话",
        ),
        Session(
            ref = PEER_REF,
            name = "A 同目录会话",
            cwd = WORKSPACE,
            rows = 24,
            cols = 80,
            title = "A 同目录会话",
            status = "idle",
            sessionName = "audit",
            windowIndex = "2",
            windowName = "A 同目录会话",
        ),
        Session(
            ref = THIRD_REF,
            name = "A 第三会话",
            cwd = WORKSPACE,
            rows = 24,
            cols = 80,
            title = "A 第三会话",
            status = "working",
            sessionName = "audit",
            windowIndex = "3",
            windowName = "A 第三会话",
        ),
    )

    fun overlayEntries(): List<L2Entry> = protocolSessions().map { it.toL2Entry() }

    fun overlayItems(): List<SessionItem> =
        overlayEntries().map { it.toSessionItem(starred = false) }

    fun workspaceLabel(): String = cwdDisplayName(WORKSPACE)
}
