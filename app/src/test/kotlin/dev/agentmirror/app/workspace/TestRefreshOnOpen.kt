/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.workspace

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Level2SubscribeFrame
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 069：进一级发 list、进二级重订；缓存优先；滚动/旋转不是进入。
 *
 * 断言世界变了（发出的帧计数 + 画面序列），不是「方法还在」。
 */
class TestRefreshOnOpen {

    @Test
    fun enterLevel1_sendsOneList_andKeepsCachedWorkspaces() {
        var lists = 0
        val vm = WorkspaceViewModel(requestList = { lists++ })
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(listing(seq = 1, "/proj/a" to 2))
        assertEquals(listOf("/proj/a"), vm.uiState.value.workspaces.map { it.cwd })
        assertEquals(2, vm.uiState.value.workspaces.single().sessionCount)

        vm.enterLevel1()
        assertEquals("进入一级必须发一次 list", 1, lists)
        assertEquals(
            "发 list 不得先清空已画列表",
            listOf("/proj/a"),
            vm.uiState.value.workspaces.map { it.cwd },
        )
        assertEquals(2, vm.uiState.value.workspaces.single().sessionCount)

        vm.onFrame(listing(seq = 2, "/proj/a" to 3, "/proj/b" to 1))
        assertEquals(listOf("/proj/a", "/proj/b"), vm.uiState.value.workspaces.map { it.cwd })
        assertEquals(3, vm.uiState.value.workspaces[0].sessionCount)
        assertEquals(1, lists)
    }

    @Test
    fun enterLevel2_paintsCacheThenSubscribes_andResubscribesOnReenter() {
        var lists = 0
        var subs = 0
        val sizes = mutableListOf<Int>()
        val vm = WorkspaceViewModel(
            requestList = { lists++ },
            subscribeLevel2 = { subs++ },
            unsubscribeLevel2 = {},
        )
        val job = Job()
        CoroutineScope(Dispatchers.Unconfined + job).launch {
            vm.level2.collect { sizes.add(it.sessions.size) }
        }

        vm.enterLevel2("/proj/a")
        vm.onFrame(level2("/proj/a", 1, "old-a", status = "idle"))
        assertEquals(1, subs)
        assertEquals(0, lists)
        assertEquals("old-a", vm.level2.value.sessions.single().ref)
        assertEquals(L2Status.IDLE, vm.level2.value.sessions.single().status)

        vm.leaveLevel2()
        val fromReenter = sizes.size
        vm.enterLevel2("/proj/a")

        assertEquals("再进必须再订（服务端 wake）", 2, subs)
        assertEquals(0, lists)
        assertTrue(
            "有缓存再进必须立刻有内容",
            vm.level2.value.sessions.any { it.ref == "old-a" },
        )
        assertFalse(
            "有缓存再进不得出现空列表帧 序列=${sizes.drop(fromReenter)}",
            sizes.drop(fromReenter).contains(0),
        )

        vm.onFrame(level2("/proj/a", 2, "old-a", status = "working"))
        assertEquals(L2Status.WORKING, vm.level2.value.sessions.single().status)
        assertFalse(
            "新帧原地替换不得经过空列表",
            sizes.drop(fromReenter).contains(0),
        )
        job.cancel()
    }

    @Test
    fun enterLevel2_sameConnectionWithoutLeave_stillResubscribes() {
        var subs = 0
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = { subs++ },
            unsubscribeLevel2 = {},
        )
        vm.enterLevel2("/proj/a")
        vm.onFrame(level2("/proj/a", 1, "keep", status = "idle"))
        vm.enterLevel2("/proj/a")
        assertEquals("未退订再进也必须再发订阅", 2, subs)
        assertEquals("keep", vm.level2.value.sessions.single().ref)
    }

    @Test
    fun rotationAndScroll_areNotEnter_andSendNothing() {
        var lists = 0
        var subs = 0
        val vm = WorkspaceViewModel(
            requestList = { lists++ },
            subscribeLevel2 = { subs++ },
            unsubscribeLevel2 = {},
        )
        vm.onConnectionStateChanged(ConnectionState.READY)
        vm.onFrame(listing(seq = 1, "/proj/a" to 1))
        vm.enterLevel2("/proj/a")
        vm.onFrame(level2("/proj/a", 1, "keep", status = "idle"))
        val listsAfterEnter = lists
        val subsAfterEnter = subs

        vm.onListScroll()
        vm.onConfigurationChange()
        assertEquals("滚动不得发 list", listsAfterEnter, lists)
        assertEquals("滚动不得重订", subsAfterEnter, subs)

        vm.suppressNextEnterRefresh()
        vm.enterLevel1()
        vm.enterLevel2("/proj/a")
        assertEquals("旋转抑制后 enterLevel1 不得发 list", listsAfterEnter, lists)
        assertEquals("旋转抑制后 enterLevel2 不得重订", subsAfterEnter, subs)
        assertEquals("keep", vm.level2.value.sessions.single().ref)
        assertEquals("/proj/a", vm.uiState.value.workspaces.single().cwd)
    }

    @Test
    fun listWithoutNewListing_doesNotBlankLevel1() {
        var lists = 0
        val vm = WorkspaceViewModel(requestList = { lists++ })
        vm.onFrame(listing(seq = 1, "/proj/a" to 4))
        vm.enterLevel1()
        assertEquals(1, lists)
        assertEquals(4, vm.uiState.value.workspaces.single().sessionCount)
        assertFalse(vm.uiState.value.workspaces.isEmpty())
    }

    @Test
    fun connectionManager_resubscribeSendsAnotherLevel2Subscribe() {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val afterReady = transport.sentText.size

        manager.subscribeLevel2("/proj/a")
        manager.subscribeLevel2("/proj/a")

        val after = transport.sentText.drop(afterReady).mapNotNull {
            runCatching { FrameCodec.decode(it) }.getOrNull()
        }
        assertEquals(
            "同一连接两次 subscribeLevel2 必须发出两帧（服务端每次 wake）",
            2,
            after.count { it is Level2SubscribeFrame },
        )
    }

    private fun listing(seq: Long, vararg counts: Pair<String, Int>) = ListingFrame(
        reqId = seq,
        seq = seq,
        workspaces = counts.map { Workspace(cwd = it.first, sessionCount = it.second) },
    )

    private fun level2(ws: String, seq: Long, ref: String, status: String) = Level2Frame(
        workspace = ws,
        seq = seq,
        sessions = listOf(
            Session(ref = ref, name = ref, cwd = ws, rows = 24, cols = 80, status = status),
        ),
    )
}
