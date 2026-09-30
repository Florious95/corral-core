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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档模块自测：连续整帧刷新必须替换。主路径已不调用。
 */
class OverlayScreenReplacesNotAppendsTest {

    @Test
    fun archivedEmulatorRepeatedFullRedrawsStayBounded() {
        val emu = OverlayEmulator(80, 24)
        val frame = "\u001b[?1049h\u001b[H\u001b[2J" +
            "(0) - 1 windows\n" +
            "├─ 0:claude\n" +
            "│  ✳ idle\n"
        repeat(12) {
            emu.resize(80, 24)
            emu.feed(frame)
        }
        val shown = dropScratchLines(emu.plainText())
        val lines = shown.lines()
        assertTrue("行数必须有界 ≤ 终端行数，got=${lines.size} text=$shown", lines.size <= 24)
        val treeMarks = Regex("├─ 0:claude").findAll(shown).count()
        assertEquals("同一棵树不得重复堆叠，got=$shown", 1, treeMarks)
        assertEquals(24, emu.rows)
    }
}
