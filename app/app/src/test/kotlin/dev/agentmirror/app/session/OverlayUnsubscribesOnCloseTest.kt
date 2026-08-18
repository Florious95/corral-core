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

import dev.agentmirror.app.conn.OverlayFrame
import dev.agentmirror.app.conn.OverlaySubscribeFrame
import dev.agentmirror.app.conn.OverlayUnsubscribeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 064：关闭悬浮窗必须退订，不得后台继续收流。
 */
class OverlayUnsubscribesOnCloseTest {

    @Test
    fun closeSendsUnsubscribeAndStopsApplyingFrames() {
        val h = OverlayTestHarness()
        h.vm.openOverlay()
        assertTrue(h.sent().any { it is OverlaySubscribeFrame })

        h.vm.onFrame(OverlayFrame(text = "tree-a", seq = 1))
        assertEquals("tree-a", h.vm.overlayText)

        h.vm.closeOverlay()
        assertTrue(
            "关闭必须发 overlay_unsubscribe",
            h.sent().any { it is OverlayUnsubscribeFrame },
        )
        assertFalse(h.vm.overlayOpen)
        assertEquals("", h.vm.overlayText)

        h.vm.onFrame(OverlayFrame(text = "tree-after-close", seq = 2))
        assertEquals("关后不得再收流进画面", "", h.vm.overlayText)
    }

    @Test
    fun disposeAlsoUnsubscribes() {
        val h = OverlayTestHarness()
        h.vm.openOverlay()
        h.vm.dispose()
        assertTrue(h.sent().any { it is OverlayUnsubscribeFrame })
        assertFalse(h.vm.overlayOpen)
    }
}
