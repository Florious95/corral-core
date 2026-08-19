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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 070 A-ov-nonempty：socket 空 / 非路径时不得发出 overlay_subscribe。
 */
class OverlaySubscribeEmptySocketNotSentTest {

    @Test
    fun listingTokenRefDoesNotSendSubscribe() {
        val listing = OverlayTestHarness()
        val vm = SessionViewModel(
            manager = listing.manager,
            uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("unused") },
            baseUrl = null,
            ref = "s1",
            initialRows = 24,
            initialCols = 80,
        )
        listing.manager.setListener(vm)
        val before = listing.transport.sentText.size
        vm.openOverlay()
        val after = listing.transport.sentText.drop(before)
        assertFalse(vm.overlayOpen)
        assertTrue(after.none { it.contains("overlay_subscribe") })
        assertTrue(vm.transientError.orEmpty().contains("socket"))
        assertEquals("", sessionSocketFromRef("s1"))
    }

    @Test
    fun literalEscapeInRefStillYieldsSocketPath() {
        val raw = "/tmp/tmux-1000/default\\u001f%3"
        assertEquals("/tmp/tmux-1000/default", sessionSocketFromRef(raw))
        val unit = "/tmp/tmux-1000/default\u001f%3"
        assertEquals("/tmp/tmux-1000/default", sessionSocketFromRef(unit))
    }

    @Test
    fun structuralRefStillSendsSubscribe() {
        val h = OverlayTestHarness()
        h.vm.openOverlay()
        assertTrue(h.vm.overlayOpen)
        assertTrue(h.sent().any { it is OverlaySubscribeFrame })
        assertEquals("/tmp/tmux-1000/default", h.sent().filterIsInstance<OverlaySubscribeFrame>().last().socket)
    }
}
