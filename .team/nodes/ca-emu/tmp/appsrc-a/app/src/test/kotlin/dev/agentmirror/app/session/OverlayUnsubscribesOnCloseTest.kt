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

import dev.agentmirror.app.conn.OverlaySubscribeFrame
import dev.agentmirror.app.conn.OverlayUnsubscribeFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 072：主路径不再订/退订 overlay 抓屏流。
 */
class OverlayUnsubscribesOnCloseTest {

    @Test
    fun closeDoesNotSendOverlayFrames() {
        val h = OverlayTestHarness()
        h.vm.openOverlay()
        assertTrue(h.sent().none { it is OverlaySubscribeFrame })

        h.vm.closeOverlay()
        assertTrue(h.sent().none { it is OverlayUnsubscribeFrame })
        assertFalse(h.vm.overlayOpen)
    }

    @Test
    fun disposeDoesNotSendUnsubscribe() {
        val h = OverlayTestHarness()
        h.vm.openOverlay()
        h.vm.dispose()
        assertTrue(h.sent().none { it is OverlayUnsubscribeFrame })
        assertFalse(h.vm.overlayOpen)
    }
}
