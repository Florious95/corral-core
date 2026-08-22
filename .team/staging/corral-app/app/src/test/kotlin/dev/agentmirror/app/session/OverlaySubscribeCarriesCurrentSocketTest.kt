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

package dev.agentmirror.app.session

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.OverlaySubscribeFrame
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 065：订阅必须带当前会话所属 socket（ref 的结构前缀）。
 */
class OverlaySubscribeCarriesCurrentSocketTest {

    @Test
    fun subscribePayloadUsesSocketFromSessionRef() {
        val socket = "/tmp/tmux-1000/default"
        val ref = "$socket\u001f%3"
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val vm = SessionViewModel(
            manager = manager,
            uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("unused") },
            baseUrl = null,
            ref = ref,
            initialRows = 24,
            initialCols = 80,
        )
        manager.setListener(vm)
        vm.openOverlay()

        val sent = transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
        val sub = sent.filterIsInstance<OverlaySubscribeFrame>()
        assertTrue("查看改为二级列表后不得发出 overlay_subscribe", sub.isEmpty())
        assertTrue(vm.overlayOpen)
        assertEquals(socket, sessionSocketFromRef(ref))
    }
}
