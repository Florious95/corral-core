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

package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白屏根因红测：ConnectionManager 单槽被列表页占着，订阅快照投错接收者。
 * 方法名即判据，⛔ 不许改名。
 */
class ConnListenerRoutingTest {

    private class Harness {
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory {
                FakeWebSocketTransport().also { transports.add(it) }
            },
            clock = clock,
        )

        fun transport(): FakeWebSocketTransport = transports.last()

        fun startReady() {
            manager.start()
            transport().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        }

        fun deliverSnapshot(ref: String, body: String = "screen") {
            transport().deliverBinary(
                BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, ref, body.toByteArray())),
            )
        }
    }

    @Test
    fun 帧只投给订阅该ref的接收者() {
        val h = Harness()
        h.startReady()
        val recvA = RecordingConnListener()
        val recvB = RecordingConnListener()
        h.manager.addBinaryListener("s1", recvA)
        h.manager.addBinaryListener("s2", recvB)
        h.manager.subscribe("s1", 24, 80)
        h.deliverSnapshot("s1")
        assertEquals("s1 的快照必须到 s1 接收者，实际=${recvA.binaries.size}", 1, recvA.binaries.size)
        assertEquals("s1", recvA.binaries[0].ref)
        assertTrue("s2 接收者不得拿到 s1 的帧，实际=${recvB.binaries}", recvB.binaries.isEmpty())
    }

    @Test
    fun 列表页占用槽位不影响会话页收帧() {
        val h = Harness()
        val listPage = RecordingConnListener()
        val sessionPage = RecordingConnListener()
        h.manager.setListener(listPage)
        h.startReady()
        h.manager.addBinaryListener("s1", sessionPage)
        h.manager.subscribe("s1", 24, 80)
        h.deliverSnapshot("s1")
        assertEquals(
            "会话页必须收到本 ref 快照（列表占槽不得吃掉），实际 session=${sessionPage.binaries.size} list=${listPage.binaries.size}",
            1,
            sessionPage.binaries.size,
        )
        assertTrue(
            "列表页不得吃会话镜像帧，实际=${listPage.binaries}",
            listPage.binaries.isEmpty(),
        )
    }
}
