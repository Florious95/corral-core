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
import dev.agentmirror.app.conn.AuthAckFrame
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkspaceViewModel 纯 JVM 单测（验收 --tests "*Workspace*"）。
 *
 * 消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）
 * 全部来自服务端权威值，客户端只渲染不重算（012 裁定）。红测先行：先列行为，后落实现。
 */
class WorkspaceViewModelTest {

    // ---- listing：全量列表 → 两级渲染 ----

    @Test
    fun listing_rendersTwoLevels() {
        val vm = WorkspaceViewModel()
        vm.onFrame(
            ListingFrame(
                reqId = 1,
                seq = 42,
                workspaces = listOf(
                    Workspace(
                        cwd = "/proj/a",
                        sessionCount = 2,
                        aggregateState = AgentState.BLOCKED,
                        sessions = listOf(
                            session("s1", "claude", "/proj/a", AgentState.WORKING),
                            session("s2", "codex", "/proj/a", AgentState.BLOCKED),
                        ),
                    ),
                    Workspace(
                        cwd = "/proj/b",
                        sessionCount = 1,
                        aggregateState = AgentState.UNKNOWN,
                        sessions = listOf(
                            session("s3", "claude", "/proj/b", AgentState.UNKNOWN),
                        ),
                    ),
                ),
            ),
        )

        val s = vm.uiState.value
        assertEquals(2, s.workspaces.size)

        // 一级：cwd 聚合条目携带会话数徽章 + 聚合状态徽章。
        val a = s.workspaces[0]
        assertEquals("/proj/a", a.cwd)
        assertEquals(2, a.sessionCount)
        assertEquals(AgentState.BLOCKED, a.aggregateState)

        // 二级：该 cwd 下会话列表；ref 寻址、name 展示。
        assertEquals(2, a.sessions.size)
        assertEquals("s1", a.sessions[0].ref)
        assertEquals("claude", a.sessions[0].name)
        assertEquals(AgentState.WORKING, a.sessions[0].state)

        // 全 unknown 工作区：聚合 unknown、会话 unknown，渲染侧灰显但绝不阻塞（008）。
        val b = s.workspaces[1]
        assertEquals(AgentState.UNKNOWN, b.aggregateState)
        assertEquals(1, b.sessions.size)
        assertEquals(AgentState.UNKNOWN, b.sessions[0].state)
    }

    @Test
    fun listing_isFullReplace_clearsDeltaState() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 1, aggregate = AgentState.IDLE, sessions = listOf(session("s1", "n1", "/a", AgentState.IDLE)))))
        vm.onFrame(delta(added = listOf(session("s2", "n2", "/a", AgentState.WORKING))))
        assertEquals(2, vm.uiState.value.workspaces.single().sessions.size)

        // 新 listing 是权威全量，覆盖此前 delta 拼出的状态。
        vm.onFrame(listing(workspaceOf("/b", count = 1, aggregate = AgentState.DONE, sessions = listOf(session("s9", "n9", "/b", AgentState.DONE)))))
        val s = vm.uiState.value
        assertEquals(1, s.workspaces.size)
        assertEquals("/b", s.workspaces.single().cwd)
        assertEquals(1, s.workspaces.single().sessions.size)
    }

    // ---- delta：增删改会话 ----

    @Test
    fun delta_addsSessionToExistingWorkspace() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 1, aggregate = AgentState.WORKING, sessions = listOf(session("s1", "n1", "/a", AgentState.WORKING)))))

        vm.onFrame(delta(added = listOf(session("s2", "n2", "/a", AgentState.IDLE))))

        val w = vm.uiState.value.workspaces.single()
        assertEquals(2, w.sessions.size)
        assertEquals("s2", w.sessions[1].ref)
        assertEquals(AgentState.IDLE, w.sessions[1].state)
        // session_count 是服务端权威值，客户端在收到 changed_workspaces 前不擅自改。
        assertEquals(1, w.sessionCount)
    }

    @Test
    fun delta_addedSession_createsNewWorkspace() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 1, aggregate = AgentState.IDLE, sessions = listOf(session("s1", "n1", "/a", AgentState.IDLE)))))

        // 新 cwd 通过 added_sessions 出现（无对应 changed_workspaces 的兜底路径）。
        vm.onFrame(delta(added = listOf(session("s3", "n3", "/b", AgentState.BLOCKED))))

        val ws = vm.uiState.value.workspaces
        assertEquals(2, ws.size)
        assertEquals("/b", ws[1].cwd)
        assertEquals(1, ws[1].sessions.size)
        assertEquals("s3", ws[1].sessions.single().ref)
        // 兜底占位：未知权威聚合前，用该会话自身状态做渲染占位（012：不自行推导聚合）。
        assertEquals(AgentState.BLOCKED, ws[1].aggregateState)
    }

    @Test
    fun delta_addedSession_thenChangedWorkspaces_correctsMetadata() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 1, aggregate = AgentState.IDLE, sessions = listOf(session("s1", "n1", "/a", AgentState.IDLE)))))

        // 正常链路：同一 delta 内 added_sessions + changed_workspaces（两组互不相交，允许同现）。
        vm.onFrame(
            ListDeltaFrame(
                seq = 3,
                addedSessions = listOf(session("s2", "n2", "/a", AgentState.WORKING)),
                changedWorkspaces = listOf(Workspace(cwd = "/a", sessionCount = 2, aggregateState = AgentState.WORKING)),
            ),
        )

        val w = vm.uiState.value.workspaces.single()
        assertEquals(2, w.sessionCount)
        assertEquals(AgentState.WORKING, w.aggregateState)
        assertEquals(2, w.sessions.size)
    }

    @Test
    fun delta_removesSession_keepsWorkspaceWhenNonEmpty() {
        val vm = WorkspaceViewModel()
        vm.onFrame(
            listing(
                workspaceOf(
                    "/a",
                    count = 2,
                    aggregate = AgentState.BLOCKED,
                    sessions = listOf(session("s1", "n1", "/a", AgentState.BLOCKED), session("s2", "n2", "/a", AgentState.IDLE)),
                ),
            ),
        )

        vm.onFrame(delta(removed = listOf("s1")))

        val w = vm.uiState.value.workspaces.single()
        assertEquals(1, w.sessions.size)
        assertEquals("s2", w.sessions.single().ref)
    }

    @Test
    fun delta_removingLastSession_dropsEmptyWorkspace() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 1, aggregate = AgentState.IDLE, sessions = listOf(session("s1", "n1", "/a", AgentState.IDLE)))))

        // 协议 delta 无 removed_workspaces 通道：会话全走后，空工作区从列表消失（渲染必需）。
        vm.onFrame(delta(removed = listOf("s1")))

        assertTrue(vm.uiState.value.workspaces.isEmpty())
    }

    @Test
    fun delta_changedSession_replacesByRef() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 1, aggregate = AgentState.WORKING, sessions = listOf(session("s1", "n1", "/a", AgentState.WORKING)))))

        vm.onFrame(delta(changed = listOf(session("s1", "n1-renamed", "/a", AgentState.BLOCKED))))

        val s1 = vm.uiState.value.workspaces.single().sessions.single()
        assertEquals("n1-renamed", s1.name)
        assertEquals(AgentState.BLOCKED, s1.state)
    }

    @Test
    fun delta_changedWorkspaces_updatesMetadata_keepsSessionsWhenOmitted() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 2, aggregate = AgentState.WORKING, sessions = listOf(session("s1", "n1", "/a", AgentState.WORKING), session("s2", "n2", "/a", AgentState.IDLE)))))

        // changed_workspaces 中 sessions 可省略，只携带 cwd/count/aggregate 语义（协议 §5.3）。
        vm.onFrame(delta(changedWorkspaces = listOf(Workspace(cwd = "/a", sessionCount = 1, aggregateState = AgentState.BLOCKED))))

        val w = vm.uiState.value.workspaces.single()
        assertEquals(1, w.sessionCount)
        assertEquals(AgentState.BLOCKED, w.aggregateState)
        assertEquals(2, w.sessions.size)
    }

    @Test
    fun delta_movesSessionAcrossWorkspaces_whenCwdChanges() {
        val vm = WorkspaceViewModel()
        vm.onFrame(
            listing(
                workspaceOf("/a", count = 1, aggregate = AgentState.IDLE, sessions = listOf(session("s1", "n1", "/a", AgentState.IDLE))),
                workspaceOf("/b", count = 1, aggregate = AgentState.DONE, sessions = listOf(session("s2", "n2", "/b", AgentState.DONE))),
            ),
        )

        // changed_sessions 携不同 cwd：会话迁居到新工作区；旧工作区变空后被剪除（协议无
        // removed_workspaces 通道，渲染必需）。
        vm.onFrame(delta(changed = listOf(session("s1", "n1", "/b", AgentState.WORKING))))

        val ws = vm.uiState.value.workspaces
        assertEquals(1, ws.size)
        assertEquals("/b", ws.single().cwd)
        assertEquals(2, ws.single().sessions.size)
        assertEquals("s1", ws.single().sessions.first { it.ref == "s1" }.ref)
    }

    // ---- 无关帧：忽略不崩溃 ----

    @Test
    fun onFrame_ignoresUnrelatedFrames() {
        val vm = WorkspaceViewModel()
        vm.onFrame(AuthAckFrame(ok = true))
        vm.onFrame(listing(workspaceOf("/a", count = 1, aggregate = AgentState.IDLE, sessions = listOf(session("s1", "n1", "/a", AgentState.IDLE)))))

        // 无关帧不破坏状态，listing 仍正常渲染。
        vm.onFrame(AuthAckFrame(ok = false, reason = "rejected"))
        assertEquals(1, vm.uiState.value.workspaces.size)
    }

    // ---- 断连态 / 空态 ----

    @Test
    fun connectionState_mapsToUiStates() {
        val vm = WorkspaceViewModel()
        vm.onConnectionStateChanged(ConnectionState.CONNECTING)
        assertEquals(ConnectionUi.CONNECTING, vm.uiState.value.connection)
        vm.onConnectionStateChanged(ConnectionState.AUTHENTICATING)
        assertEquals(ConnectionUi.CONNECTING, vm.uiState.value.connection)
        vm.onConnectionStateChanged(ConnectionState.READY)
        assertEquals(ConnectionUi.READY, vm.uiState.value.connection)
        // 断连：UI 只反映重连中条（conn 层自动重连）。
        vm.onConnectionStateChanged(ConnectionState.RECONNECTING)
        assertEquals(ConnectionUi.RECONNECTING, vm.uiState.value.connection)
        vm.onConnectionStateChanged(ConnectionState.STOPPED)
        assertEquals(ConnectionUi.STOPPED, vm.uiState.value.connection)
    }

    @Test
    fun readyWithNoWorkspaces_isEmptyForGuideText() {
        val vm = WorkspaceViewModel()
        vm.onConnectionStateChanged(ConnectionState.READY)
        val s = vm.uiState.value
        assertTrue(s.isEmpty)
        assertFalse(s.isDisconnected)
    }

    @Test
    fun reconnectKeepsLastKnownList_butFlagsDisconnected() {
        val vm = WorkspaceViewModel()
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(listing(workspaceOf("/a", count = 1, aggregate = AgentState.WORKING, sessions = listOf(session("s1", "n1", "/a", AgentState.WORKING)))))

        vm.onConnectionStateChanged(ConnectionState.RECONNECTING)

        val s = vm.uiState.value
        assertTrue(s.isDisconnected)
        // 重连中保留最后一次已知列表（顶栏提示反映断连），READY 后新 listing 权威覆盖。
        assertEquals(1, s.workspaces.size)
    }

    // ---- 夹具 ----

    private fun session(ref: String, name: String, cwd: String, state: AgentState) =
        Session(ref = ref, name = name, cwd = cwd, state = state, rows = 24, cols = 80)

    private fun workspaceOf(cwd: String, count: Int, aggregate: AgentState, sessions: List<Session>) =
        Workspace(cwd = cwd, sessionCount = count, aggregateState = aggregate, sessions = sessions)

    private fun listing(vararg ws: Workspace) = ListingFrame(reqId = 1, seq = 42, workspaces = ws.toList())

    private fun delta(
        added: List<Session> = emptyList(),
        removed: List<String> = emptyList(),
        changed: List<Session> = emptyList(),
        changedWorkspaces: List<Workspace> = emptyList(),
    ) = ListDeltaFrame(
        seq = 43,
        addedSessions = added,
        removedRefs = removed,
        changedSessions = changed,
        changedWorkspaces = changedWorkspaces,
    )
}
