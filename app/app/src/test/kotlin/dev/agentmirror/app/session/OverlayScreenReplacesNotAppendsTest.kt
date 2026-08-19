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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 065 缺陷 3：连续整帧刷新必须替换，行数有界，不得把同一棵树堆很多份。
 */
class OverlayScreenReplacesNotAppendsTest {

    @Test
    fun repeatedFullRedrawsStayBoundedAndDoNotStackTrees() {
        val h = OverlayTestHarness()
        h.vm.openOverlay()
        val frame = "\u001b[?1049h\u001b[H\u001b[2J" +
            "(0) - 1 windows\n" +
            "├─ 0:claude\n" +
            "│  ✳ idle\n"
        repeat(12) { i ->
            h.vm.onFrame(OverlayFrame(text = frame, seq = (i + 1).toLong(), rows = 24, cols = 80))
        }

        val shown = h.vm.overlayText
        val lines = shown.lines()
        assertTrue("行数必须有界 ≤ 终端行数，got=${lines.size} text=$shown", lines.size <= 24)
        val treeMarks = Regex("├─ 0:claude").findAll(shown).count()
        assertEquals("同一棵树不得重复堆叠，got=$shown", 1, treeMarks)
        assertEquals(24, h.vm.overlayEmulator.rows)
    }
}
