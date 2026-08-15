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
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.Level2Frame
import dev.agentmirror.app.conn.Level2SubscribeFrame
import dev.agentmirror.app.conn.Level2UnsubscribeFrame
import dev.agentmirror.app.conn.Session
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二级菜单流生命周期红测（060 t.app A-a-*）。
 *
 * 断言：进入二级 → 发 Level2Subscribe；退出 → 发 Level2Unsubscribe；退订后 VM dispose
 * 不再有拉取（无定时器/后台轮询）。
 *
 * 红测先行：修复前无 Level2ViewModel → 编译失败即红。
 */
class Level2StreamLifecycleTest {

    private class Harness(workspace: String = "/proj/a") {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        lateinit var vm: Level2ViewModel

        init {
            manager.start()
            transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            vm = Level2ViewModel(manager, workspace)
            manager.setListener(vm)
        }

        fun sentFrames(): List<FramePayload> =
            transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }

        fun level2Frame(sessions: List<Session>) = Level2Frame(
            workspace = "/proj/a",
            seq = 1,
            sessions = sessions,
        )
    }

    @Test
    fun enteringSubscribesLevel2() {
        val h = Harness()
        // 构造即发 Level2Subscribe（进入二级菜单自动订阅）。
        assertTrue(
            "进入二级必须发 Level2Subscribe",
            h.sentFrames().any { it is Level2SubscribeFrame },
        )
    }

    @Test
    fun leavingUnsubscribesLevel2() {
        val h = Harness()
        h.vm.dispose()
        assertTrue(
            "离开二级必须发 Level2Unsubscribe",
            h.sentFrames().any { it is Level2UnsubscribeFrame },
        )
    }

    @Test
    fun receivingLevel2FrameReplacesEntriesVerbatim() {
        val h = Harness()
        val entry = Session(ref = "ref-1", name = "claude", cwd = "/proj/a", rows = 24, cols = 80, title = "◐  live")
        h.vm.onFrame(h.level2Frame(listOf(entry)))
        assertEquals(1, h.vm.sessions.size)
        assertEquals("ref-1", h.vm.sessions[0].ref)
        assertEquals("◐  live", h.vm.sessions[0].title) // title 原样，零加工
    }
}
