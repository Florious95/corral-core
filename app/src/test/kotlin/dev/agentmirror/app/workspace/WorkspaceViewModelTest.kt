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

import dev.agentmirror.app.conn.AgentLauncherFrame
import dev.agentmirror.app.conn.AuthAckFrame
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.CreateAgentResultFrame
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkspaceViewModel 纯 JVM 单测（验收 --tests "*Workspace*"）。
 *
 * 060 uproot（2026-08-15）：二级会话列表模型与聚合状态随状态判定整体拔除，本 VM 只
 * 维护一级工作区（cwd → session_count + working_count）。二级会话增删
 * （added/changed/removed sessions）是二级实时流的数据源，不在本一级 VM 消费；一级只
 * 消费 changed_workspaces 的两个聚合计数。
 */
class WorkspaceViewModelTest {

    // ---- listing：全量列表 → 一级渲染 ----

    @Test
    fun listing_rendersOneLevel() {
        val vm = WorkspaceViewModel()
        vm.onFrame(
            ListingFrame(
                reqId = 1,
                seq = 42,
                workspaces = listOf(
                    Workspace(cwd = "/proj/a", sessionCount = 2, workingCount = 2),
                    Workspace(cwd = "/proj/b", sessionCount = 1, workingCount = 0),
                ),
            ),
        )

        val s = vm.uiState.value
        assertEquals(2, s.workspaces.size)

        // 一级：cwd 聚合条目携带会话数。
        val a = s.workspaces[0]
        assertEquals("/proj/a", a.cwd)
        assertEquals(2, a.sessionCount)
        assertEquals(2, a.workingCount)
        val b = s.workspaces[1]
        assertEquals("/proj/b", b.cwd)
        assertEquals(1, b.sessionCount)
        assertEquals(0, b.workingCount)
    }

    @Test
    fun listing_missingWorkingCount_defaultsToZero() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/legacy", count = 2)))

        assertEquals(0, vm.uiState.value.workspaces.single().workingCount)
    }

    @Test
    fun listing_isFullReplace_clearsDeltaState() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 1)))
        vm.onFrame(ListDeltaFrame(seq = 43, changedWorkspaces = listOf(Workspace(cwd = "/a", sessionCount = 2))))
        assertEquals(2, vm.uiState.value.workspaces.single().sessionCount)

        // 新 listing 是权威全量，覆盖此前 delta 拼出的状态。
        vm.onFrame(listing(workspaceOf("/b", count = 1)))
        val s = vm.uiState.value
        assertEquals(1, s.workspaces.size)
        assertEquals("/b", s.workspaces.single().cwd)
        assertEquals(1, s.workspaces.single().sessionCount)
    }

    // ---- delta：只消费 changed_workspaces 的 aggregate counts ----

    @Test
    fun delta_changedWorkspaces_updatesSessionAndWorkingCount() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(Workspace(cwd = "/a", sessionCount = 2, workingCount = 2)))

        // changed_workspaces 整体替换两个服务端权威计数。
        vm.onFrame(
            ListDeltaFrame(
                seq = 43,
                changedWorkspaces = listOf(Workspace(cwd = "/a", sessionCount = 1, workingCount = 0)),
            ),
        )

        val w = vm.uiState.value.workspaces.single()
        assertEquals(1, w.sessionCount)
        assertEquals(0, w.workingCount)
    }

    @Test
    fun delta_addedSessions_ignoredByLevelOne() {
        val vm = WorkspaceViewModel()
        vm.onFrame(listing(workspaceOf("/a", count = 1)))

        // 二级会话增删不进一级 VM（二级实时流的数据源）。
        vm.onFrame(ListDeltaFrame(seq = 43, addedSessions = emptyList()))

        assertEquals(1, vm.uiState.value.workspaces.size)
        assertEquals(1, vm.uiState.value.workspaces.single().sessionCount)
    }

    // ---- 无关帧：忽略不崩溃 ----

    @Test
    fun authAck_exposesAgentLaunchers() {
        val vm = WorkspaceViewModel()
        vm.onFrame(
            AuthAckFrame(
                ok = true,
                agentLaunchers = listOf(
                    AgentLauncherFrame("pi", "Pi Coding Agent", supportsBypass = true, naming = "cli"),
                ),
            ),
        )
        assertEquals("pi", vm.uiState.value.agentLaunchers.single().provider)
        assertTrue(vm.uiState.value.agentLaunchers.single().supportsBypass)
    }

    @Test
    fun createAgent_correlatesResultAndRefreshesVisibleLevel2() {
        val subscribed = mutableListOf<String>()
        var request: List<String>? = null
        val vm = WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            subscribeLevel2 = { subscribed += it },
            createAgentRequest = { workspace, anchor, provider, name, bypass ->
                request = listOf(workspace, anchor, provider, name, bypass.toString())
                77L
            },
        )
        vm.enterLevel2("/repo")
        assertTrue(vm.createAgent("/tmp/tmux.sock\u001f%0", "pi", "child", true))
        assertEquals(listOf("/repo", "/tmp/tmux.sock\u001f%0", "pi", "child", "true"), request)
        assertTrue(vm.uiState.value.createAgent.inFlight)
        vm.onFrame(CreateAgentResultFrame(reqId = 77L, ok = true, ref = "/tmp/tmux.sock\u001f%1", name = "child", naming = "cli"))
        assertTrue(!vm.uiState.value.createAgent.inFlight)
        assertEquals(null, vm.uiState.value.createAgent.error)
        assertTrue(subscribed.size >= 2)
    }

    @Test
    fun createAgent_disconnectClearsPendingWithoutReplay() {
        var requests = 0
        val vm = WorkspaceViewModel(
            initialConnection = ConnectionUi.READY,
            subscribeLevel2 = {},
            createAgentRequest = { _, _, _, _, _ -> requests++; 5L },
        )
        vm.enterLevel2("/repo")
        assertTrue(vm.createAgent("anchor", "pi", "child", false))
        vm.onConnectionStateChanged(ConnectionState.RECONNECTING)
        assertFalse(vm.uiState.value.createAgent.inFlight)
        assertEquals("创建结果未确认，请刷新", vm.uiState.value.createAgent.error)
        vm.onFrame(CreateAgentResultFrame(reqId = 5L, ok = true, ref = "ref", name = "child", naming = "cli"))
        assertEquals(1, requests)
    }

    @Test
    fun onFrame_ignoresUnrelatedFrames() {
        val vm = WorkspaceViewModel()
        vm.onFrame(AuthAckFrame(ok = true))
        vm.onFrame(listing(workspaceOf("/a", count = 1)))

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
    fun connectingWithNoWorkspaces_isLoadingInsteadOfEmpty() {
        val s = WorkspaceViewModel().uiState.value

        // listing 尚未到达时必须给中间反馈，不能提前宣告主机没有工作区。
        assertTrue(s.isLoading)
        assertFalse(s.isEmpty)
    }

    @Test
    fun reconnectKeepsLastKnownList_butFlagsDisconnected() {
        val vm = WorkspaceViewModel()
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(listing(workspaceOf("/a", count = 1)))

        vm.onConnectionStateChanged(ConnectionState.RECONNECTING)

        val s = vm.uiState.value
        assertTrue(s.isDisconnected)
        // 重连中保留最后一次已知列表（顶栏提示反映断连），READY 后新 listing 权威覆盖。
        assertEquals(1, s.workspaces.size)
    }

    // ---- 夹具 ----

    private fun workspaceOf(cwd: String, count: Int) =
        Workspace(cwd = cwd, sessionCount = count)

    private fun listing(vararg ws: Workspace) = ListingFrame(reqId = 1, seq = 42, workspaces = ws.toList())
}
