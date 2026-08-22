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

import androidx.compose.ui.test.junit4.createComposeRule
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Level2HeartbeatFrame
import dev.agentmirror.app.conn.ListDeltaFrame
import dev.agentmirror.app.conn.ListFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.Workspace
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 074 A-rf-noloop：进二级后静置不得自激 list；首帧到达后转圈必须消失。
 * 069 进菜单即时刷新保留——本测试仍要求进入时发出 list。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceScreenNoRefreshLoopTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun enterLevel2_tenSeconds_listDoesNotGrow_andSpinnerClearsOnFirstFrame() {
        var lists = 0
        val vm = WorkspaceViewModel(
            requestList = { lists++ },
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(
            ListingFrame(
                reqId = 1,
                seq = 1,
                workspaces = listOf(Workspace(cwd = "/proj/a", sessionCount = 1)),
            ),
        )

        compose.setContent {
            AgentMirrorTheme {
                WorkspaceScreen(
                    viewModel = vm,
                    selectedWorkspaceCwd = "/proj/a",
                    onSelectWorkspace = {},
                    onBackToList = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()

        val listsOnEnter = lists
        assertTrue("069：进二级必须发 list，got=$listsOnEnter", listsOnEnter in 1..2)
        assertTrue("进菜单即时刷新应置转圈", vm.refreshing.value)

        // 首帧 = level2_frame（不是 listing）。
        vm.onFrame(l2Frame(seq = 1))
        compose.waitForIdle()
        assertFalse("A-rf-noloop：首帧到达后转圈必须消失", vm.refreshing.value)
        val listsAfterFirstFrame = lists

        // 静置 10s：继续推 level2_frame / heartbeat，list 不得线性增长。
        repeat(6) { i ->
            vm.onFrame(l2Frame(seq = (i + 2).toLong()))
            vm.onFrame(Level2HeartbeatFrame(workspace = "/proj/a", seq = (i + 20).toLong()))
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(4_000L)
        compose.waitForIdle()

        assertEquals(
            "静置 10s 内推送不得再触发 list：enter=$listsOnEnter after_frame=$listsAfterFirstFrame after_10s=$lists",
            listsAfterFirstFrame,
            lists,
        )
        assertTrue("10s 内 list=$lists 必须 ≤ 2", lists <= 2)
        assertFalse("静置后转圈仍须关闭", vm.refreshing.value)
    }

    private fun l2Frame(seq: Long) = Level2Frame(
        workspace = "/proj/a",
        seq = seq,
        sessions = listOf(
            Session(ref = "r1", name = "n", cwd = "/proj/a", rows = 24, cols = 80, status = "working"),
        ),
    )
}

class ConnNoRefreshLoopTest {

    @Test
    fun level2Frame_doesNotTriggerList() {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val readyLists = countLists(transport)

        transport.deliverText(
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[]}}""",
        )
        manager.subscribeLevel2("/proj/a")
        transport.deliverText(
            """{"v":1,"type":"level2_frame","payload":{"workspace":"/proj/a","seq":2,"sessions":[]}}""",
        )
        transport.deliverText(
            """{"v":1,"type":"level2_frame","payload":{"workspace":"/proj/a","seq":3,"sessions":[]}}""",
        )
        transport.deliverText(
            """{"v":1,"type":"level2_heartbeat","payload":{"workspace":"/proj/a","seq":4}}""",
        )

        assertEquals(
            "level2_frame / heartbeat 不得触发 list",
            readyLists,
            countLists(transport),
        )
    }

    @Test
    fun sameSeqDeltaAfterListing_doesNotRelist() {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        transport.deliverText(
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":5,"workspaces":[]}}""",
        )
        val before = countLists(transport)
        // handleList 重扫变更时 fanout 的 delta 与 listing 同 seq。
        transport.deliverText(
            """{"v":1,"type":"list_delta","payload":{"seq":5,"added_sessions":[]}}""",
        )
        assertEquals("同 seq delta 不得再 list（自激）", before, countLists(transport))
    }

    @Test
    fun realSeqGap_stillRelists() {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        transport.deliverText(
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":5,"workspaces":[]}}""",
        )
        val before = countLists(transport)
        transport.deliverText(
            """{"v":1,"type":"list_delta","payload":{"seq":7,"added_sessions":[]}}""",
        )
        assertTrue("真空洞仍须 list", countLists(transport) > before)
    }

    private fun countLists(t: FakeWebSocketTransport): Int =
        t.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
            .count { it is ListFrame }
}

class ViewModelNoRefreshLoopTest {

    @Test
    fun firstLevel2Frame_clearsRefreshing_withoutExtraList() {
        var lists = 0
        val vm = WorkspaceViewModel(
            requestList = { lists++ },
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        vm.enterLevel2("/proj/a")
        vm.refresh()
        assertEquals(1, lists)
        assertTrue(vm.refreshing.value)

        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = listOf(
                    Session(ref = "r1", name = "n", cwd = "/proj/a", rows = 24, cols = 80, status = "idle"),
                ),
            ),
        )
        assertFalse("首帧后转圈必须消失", vm.refreshing.value)
        assertEquals("推送不得再 list", 1, lists)

        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 2,
                sessions = listOf(
                    Session(ref = "r1", name = "n", cwd = "/proj/a", rows = 24, cols = 80, status = "working"),
                ),
            ),
        )
        vm.onFrame(Level2HeartbeatFrame(workspace = "/proj/a", seq = 3))
        assertEquals(1, lists)
        assertFalse(vm.refreshing.value)
    }

    @Test
    fun refreshOnEnter_isPreserved() {
        var lists = 0
        val vm = WorkspaceViewModel(requestList = { lists++ })
        vm.refresh()
        assertEquals("069 即时刷新不得关掉", 1, lists)
    }
}
