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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.OverlayFrame
import dev.agentmirror.app.conn.OverlaySubscribeFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.ui.theme.AgentMirrorTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 064：会话内右上角「查看」打开覆盖在终端之上的悬浮窗。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayOpensFromSessionTopRightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun topRightButtonOpensOverlayAndSubscribes() {
        val h = OverlayTestHarness()
        compose.setContent {
            AgentMirrorTheme {
                SessionScreen(viewModel = h.vm, name = "sess", onBack = {})
            }
        }
        compose.onNodeWithTag("session-overlay").assertDoesNotExist()
        compose.onNodeWithTag("session-overlay-open").assertIsDisplayed()
        compose.onNodeWithTag("session-overlay-open").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("session-overlay").assertIsDisplayed()
        assertTrue(h.vm.overlayOpen)
        assertTrue(
            "打开必须订 overlay_subscribe",
            h.sent().any { it is OverlaySubscribeFrame },
        )

        val tree = "├─ 0:claude\n│  ◐ working"
        h.vm.onFrame(OverlayFrame(text = tree, seq = 1))
        compose.waitForIdle()
        compose.onNodeWithText("claude", substring = true).assertIsDisplayed()
    }
}

internal class OverlayTestHarness {
    val transport = FakeWebSocketTransport()
    val manager = ConnectionManager(
        config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
        transportFactory = TransportFactory { transport },
        clock = FakeClock(),
    )
    val vm: SessionViewModel

    init {
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        vm = SessionViewModel(
            manager = manager,
            uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("unused") },
            baseUrl = null,
            ref = "s1",
            initialRows = 24,
            initialCols = 80,
        )
        manager.setListener(vm)
    }

    fun sent() = transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
}
