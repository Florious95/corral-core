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

package dev.agentmirror.app.ui.theme

import dev.agentmirror.terminal.TerminalColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A-pf-equiv：优化后 colorFor 必须与优化前金样逐格相同。
 * 金样在改 TermPalette 之前由 RemapProfileDumpTest 倾倒。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemapEquivalenceTest {

    @After
    fun tearDown() {
        TermPalette.resetBindingForTest()
    }

    @Test
    fun indexedAndTruecolorMatchGoldCapturedBeforeOptimization() {
        TermPalette.resetBindingForTest()
        val text = javaClass.getResourceAsStream("/remap-gold-before.txt")
            ?.bufferedReader()
            ?.readText()
        assertNotNull("缺金样 remap-gold-before.txt", text)
        val lines = text!!.lines()
        val idx = lines.filter { it.matches(Regex("[01][01] [0-9A-Fa-f]{2048}")) }
        assertEquals("金样应有 2 dark × 2 bg 四行索引", 4, idx.size)
        val mismatches = mutableListOf<String>()
        for (line in idx) {
            val dark = line[0] == '1'
            val bg = line[1] == '1'
            val hex = line.substring(3)
            for (i in 0..255) {
                val expect = hex.substring(i * 8, i * 8 + 8).toLong(16).toInt()
                val got = TermPalette.colorFor(TerminalColor.Indexed(i), bg, dark)
                if (got != expect) {
                    mismatches += "idx=$i bg=$bg dark=$dark expect=${hex8(expect)} got=${hex8(got)}"
                    if (mismatches.size >= 12) break
                }
            }
            if (mismatches.size >= 12) break
        }
        val rgbLines = lines.filter { it.contains("=") && it[0].isDigit() }
        assertEquals("真彩金样 30×2×2", 120, rgbLines.size)
        for (line in rgbLines) {
            val (lhs, rhs) = line.split("=")
            val p = lhs.split(",")
            val r = p[0].toInt()
            val g = p[1].toInt()
            val b = p[2].toInt()
            val bg = p[3] == "1"
            val dark = p[4] == "1"
            val expect = rhs.toLong(16).toInt()
            val got = TermPalette.colorFor(TerminalColor.Rgb(r, g, b), bg, dark)
            if (got != expect) {
                mismatches += "rgb=$r,$g,$b bg=$bg dark=$dark expect=${hex8(expect)} got=${hex8(got)}"
                if (mismatches.size >= 12) break
            }
        }
        assertEquals(mismatches.joinToString("\n"), 0, mismatches.size)
    }

    private fun hex8(v: Int): String = "%08X".format(v)
}
