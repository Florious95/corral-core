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

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Level2HeartbeatFrame
import dev.agentmirror.app.conn.Level2SubscribeFrame
import dev.agentmirror.app.conn.Level2UnsubscribeFrame
import dev.agentmirror.app.conn.ListFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 061：App 只接收服务端推送，自己不轮询二级状态。
 */
class L2PushOnlyNoPollTest {

    @Test
    fun viewModelSubscribesOnceAndNeverListsOnPush() {
        var lists = 0
        var subs = 0
        var unsubs = 0
        val vm = WorkspaceViewModel(
            requestList = { lists++ },
            subscribeLevel2 = { subs++ },
            unsubscribeLevel2 = { unsubs++ },
        )

        vm.enterLevel2("/proj/a")
        assertEquals("进入二级订一次", 1, subs)
        assertEquals("二级入口不得发 list（那是轮询）", 0, lists)

        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = listOf(
                    Session(ref = "r1", name = "n", cwd = "/proj/a", rows = 24, cols = 80, status = "working"),
                ),
            ),
        )
        vm.onFrame(Level2HeartbeatFrame(workspace = "/proj/a", seq = 2))
        vm.onFrame(Level2HeartbeatFrame(workspace = "/proj/a", seq = 3))
        vm.enterLevel2("/proj/a")

        // 069：同连接再进必须再发订阅（服务端 wake），不是周期轮询。
        assertEquals("再进二级再订一次", 2, subs)
        assertEquals(0, lists)
        assertEquals(1, vm.level2.value.sessions.size)
        assertEquals(3L, vm.level2.value.seq)

        vm.leaveLevel2()
        assertEquals(1, unsubs)
        assertEquals(0, lists)
    }

    @Test
    fun connectionManagerDoesNotPollAfterLevel2Subscribe() {
        val transport = FakeWebSocketTransport()
        val clock = FakeClock()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = clock,
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")

        val afterReady = transport.sentText.size
        assertTrue("READY 会发一次 list，那是一级重建不是二级轮询", afterReady >= 1)

        manager.subscribeLevel2("/proj/a")
        clock.advance(30_000)
        manager.pump(clock.nowMs())
        manager.resolveExpiredInputs(clock.nowMs())

        val after = transport.sentText.drop(afterReady).mapNotNull {
            runCatching { FrameCodec.decode(it) }.getOrNull()
        }
        assertEquals(1, after.count { it is Level2SubscribeFrame })
        assertEquals("订完二级后推进时钟不得再发 list", 0, after.count { it is ListFrame })
        assertEquals(0, after.count { it is Level2UnsubscribeFrame })
    }

    @Test
    fun heartbeatDoesNotClearList() {
        val vm = WorkspaceViewModel(
            requestList = {},
            subscribeLevel2 = {},
            unsubscribeLevel2 = {},
        )
        vm.enterLevel2("/proj/a")
        vm.onFrame(
            Level2Frame(
                workspace = "/proj/a",
                seq = 1,
                sessions = listOf(
                    Session(ref = "r1", name = "keep-me", cwd = "/proj/a", rows = 24, cols = 80, status = "idle"),
                ),
            ),
        )
        vm.onFrame(Level2HeartbeatFrame(workspace = "/proj/a", seq = 4))
        assertEquals(1, vm.level2.value.sessions.size)
        assertEquals("keep-me", vm.level2.value.sessions.single().identityLabel)
        assertEquals(4L, vm.level2.value.seq)
        assertEquals(L2Status.IDLE, vm.level2.value.sessions.single().status)
    }
}
