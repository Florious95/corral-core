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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 065 缺陷 2：抓屏帧必须过 [TerminalEmulator]，渲染结果不得含裸 CSI。
 */
class OverlayRendersThroughTerminalEmulatorTest {

    @Test
    fun renderedTextHasNoBareControlSequences() {
        val h = OverlayTestHarness()
        h.vm.openOverlay()
        val raw = "\u001b[?1049h\u001b[22;0;0t\u001b(B\u001b[m\u001b[H\u001b[2J" +
            "\u001b[30m\u001b[43m├─ 0:claude\u001b[K"
        h.vm.onFrame(OverlayFrame(text = raw, seq = 1, rows = 24, cols = 80))

        val shown = h.vm.overlayText
        assertTrue("可见文本应含树线内容，got=$shown", shown.contains("claude"))
        for (bad in listOf("[?1049", "[?1049h", "[K", "(B[m", "[30m", "[43m", "\u001b[", "[H", "[2J")) {
            assertFalse("渲染结果不得含裸控制序列 $bad，got=$shown", shown.contains(bad))
        }
    }
}
