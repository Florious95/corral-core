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

package dev.agentmirror.app.termview

import android.content.Context
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.ResizeFrame
import dev.agentmirror.app.conn.SubscribeFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.session.AttachmentUploader
import dev.agentmirror.app.session.SessionViewModel
import dev.agentmirror.app.session.UploadOutcome
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Guards the termview -> session resize edge when a session presenter is rebound to a View. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermSurfaceSessionBindingRegressionTest {

    @Test
    fun rebindingLaidOutViewHandsOffViewportAndSubscribesNewSessionOnce() {
        val context = RuntimeEnvironment.getApplication()
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("unused") }
        val first = SessionViewModel(manager, uploader, null, "a", 24, 80)
        val second = SessionViewModel(manager, uploader, null, "b", 24, 80)
        first.presenter.seedCellMetrics(cellW = 10, cellH = 20)
        second.presenter.seedCellMetrics(cellW = 10, cellH = 20)

        val surface = TermSurfaceView(context)
        surface.presenter = first.presenter
        surface.layout(0, 0, 800, 480)
        surface.presenter = second.presenter
        surface.presenter = second.presenter // identity rebind must stay deduplicated.

        val frames = transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
        val secondSubscribes = frames.filterIsInstance<SubscribeFrame>().filter { it.ref == "b" }
        assertEquals("换绑后的 presenter 必须获得一次首订", 1, secondSubscribes.size)
        assertTrue(secondSubscribes.single().rows >= 1)
        assertTrue(secondSubscribes.single().cols >= 1)
        assertTrue(
            "换绑首订不得再发送 Resize",
            frames.filterIsInstance<ResizeFrame>().none { it.ref == "b" },
        )
    }

    @Test
    fun bindingPresenterDoesNotResizeSessionFromItsStaleViewport() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("cell_size", Context.MODE_PRIVATE)
            .edit()
            .putInt("cell_width", 14)
            .putInt("cell_height", 28)
            .commit()

        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(TerminalEmulator(cols = 80, rows = 24)) { rows, cols ->
            resizeCalls += rows to cols
        }
        // 防静默失效守卫要求先 seed（feat-font-size-setting-drop-pinch）。
        presenter.seedCellMetrics(10, 20)
        // A retained SessionViewModel presenter still carries the previous View's viewport.
        presenter.onViewportSizeChanged(widthPx = 800, heightPx = 480)
        resizeCalls.clear()

        TermSurfaceView(context).presenter = presenter

        // Binding happens before the replacement View has a viewport, so it must not push a
        // resize into the session using the retained presenter's stale 800x480 geometry.
        assertTrue("binding emitted session resize(s): $resizeCalls", resizeCalls.isEmpty())
    }
}
