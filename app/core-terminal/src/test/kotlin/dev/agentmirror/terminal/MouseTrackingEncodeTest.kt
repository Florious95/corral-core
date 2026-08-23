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

package dev.agentmirror.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入透传第 3 步：鼠标 DEC 模式跟踪 + SGR 1006 编码。
 */
class MouseTrackingEncodeTest {

    private fun term() = TerminalEmulator(80, 24)

    // R1: 输出流里的 1002h / 1006h 必须被记下。
    @Test
    fun testMouseMode1002And1006Tracked() {
        val t = term()
        t.feed("${E}[?1002h${E}[?1006h")
        assertEquals(1002, t.mouseTrackingMode)
        assertEquals(1006, t.mouseEncodingMode)
        assertTrue(t.mouseModeOrder.any { it.code == 1002 && it.on })
        assertTrue(t.mouseModeOrder.any { it.code == 1006 && it.on })
    }

    // R2: 1002+1006 已开时，左键按下于第 3 行第 5 列 = CSI < 0 ; 5 ; 3 M（SGR1006，1-based）。
    @Test
    fun testSgr1006LeftPressAtRow3Col5() {
        val t = term()
        t.feed("${E}[?1002h${E}[?1006h")
        val got = t.encodeMouse(
            button = 0,
            column = 5,
            row = 3,
            press = true,
        )
        assertNotNull(got)
        // xterm ctlseqs: CSI < Pb ; Px ; Py M；Pb=0 左键，Px=列，Py=行，均为 1-based。
        val want = "${E}[<0;5;3M".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(want, got)
    }

    // R3: 对面请求 1003 时实际启用 1002，留痕同时有 1003 与 1002。
    @Test
    fun testMouse1003DowngradesTo1002WithTrace() {
        val t = term()
        t.feed("${E}[?1003h")
        assertEquals(1002, t.mouseTrackingMode)
        assertFalse(t.mouseTrackingMode == 1003)
        val trace = t.lastMouseDowngradeTrace
        assertNotNull(trace)
        assertTrue("trace must name requested 1003: $trace", trace!!.contains("1003"))
        assertTrue("trace must name enabled 1002: $trace", trace.contains("1002"))
    }

    // R4: 没开任何鼠标模式时编码入口返回不发（null）。
    @Test
    fun testEncodeMouseNoneWhenTrackingOff() {
        val t = term()
        val got = t.encodeMouse(button = 0, column = 5, row = 3, press = true)
        assertNull("没开模式时必须不发", got)
    }

    @Test
    fun testSgrCoordinatesAre1BasedNot0Based() {
        val t = term()
        t.feed("${E}[?1002h${E}[?1006h")
        val origin = t.encodeMouse(button = 0, column = 1, row = 1, press = true)!!
        assertArrayEquals("${E}[<0;1;1M".toByteArray(Charsets.US_ASCII), origin)
        val release = t.encodeMouse(button = 0, column = 5, row = 3, press = false)!!
        assertArrayEquals("${E}[<0;5;3m".toByteArray(Charsets.US_ASCII), release)
    }
}
