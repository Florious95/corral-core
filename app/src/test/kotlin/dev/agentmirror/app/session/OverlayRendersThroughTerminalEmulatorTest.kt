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

import dev.agentmirror.app.overlay.OverlayEmulator
import dev.agentmirror.app.overlay.dropScratchLines
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档模块自测：抓屏帧过 [OverlayEmulator] 不得含裸 CSI。主路径已不调用。
 */
class OverlayRendersThroughTerminalEmulatorTest {

    @Test
    fun archivedEmulatorStillStripsControlSequences() {
        val emu = OverlayEmulator(80, 24)
        val raw = "\u001b[?1049h\u001b[22;0;0t\u001b(B\u001b[m\u001b[H\u001b[2J" +
            "\u001b[30m\u001b[43m├─ 0:claude\u001b[K"
        emu.feed(raw)
        val shown = dropScratchLines(emu.plainText())
        assertTrue("可见文本应含树线内容，got=$shown", shown.contains("claude"))
        for (bad in listOf("[?1049", "[?1049h", "[K", "(B[m", "[30m", "[43m", "\u001b[", "[H", "[2J")) {
            assertFalse("渲染结果不得含裸控制序列 $bad，got=$shown", shown.contains(bad))
        }
    }
}
