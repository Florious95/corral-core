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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SGR 解析测试：16 基色/亮色、256 色、真彩、样式开关与复位、BCE 背景擦除。
 */
class SgrParsingTest {

    private fun term() = TerminalEmulator(20, 4)

    @Test
    fun basicForegroundAndBackground() {
        val t = term()
        t.feed("${E}[31;44mX")
        val cell = t.cellAt(0, 0)
        assertEquals(TerminalColor.Indexed(1), cell.style.fg)
        assertEquals(TerminalColor.Indexed(4), cell.style.bg)
    }

    @Test
    fun brightColorsMapToUpperPalette() {
        val t = term()
        t.feed("${E}[90;107mX")
        val cell = t.cellAt(0, 0)
        assertEquals(TerminalColor.Indexed(8), cell.style.fg)
        assertEquals(TerminalColor.Indexed(15), cell.style.bg)
    }

    @Test
    fun palette256Color() {
        val t = term()
        t.feed("${E}[38;5;196m${E}[48;5;21mX")
        val cell = t.cellAt(0, 0)
        assertEquals(TerminalColor.Indexed(196), cell.style.fg)
        assertEquals(TerminalColor.Indexed(21), cell.style.bg)
    }

    @Test
    fun trueColor() {
        val t = term()
        t.feed("${E}[38;2;10;20;30mX")
        assertEquals(TerminalColor.Rgb(10, 20, 30), t.cellAt(0, 0).style.fg)
    }

    @Test
    fun trueColorWithColonSubparams() {
        val t = term()
        t.feed("${E}[38:2:10:20:30mX")
        assertEquals(TerminalColor.Rgb(10, 20, 30), t.cellAt(0, 0).style.fg)
    }

    @Test
    fun attributeTogglesOnAndOff() {
        val t = term()
        t.feed("${E}[1;3;4;7;9mA")
        val on = t.cellAt(0, 0).style
        assertTrue(on.bold)
        assertTrue(on.italic)
        assertTrue(on.underline)
        assertTrue(on.inverse)
        assertTrue(on.strikethrough)
        t.feed("${E}[22;23;24;27;29mB")
        val off = t.cellAt(1, 0).style
        assertFalse(off.bold)
        assertFalse(off.italic)
        assertFalse(off.underline)
        assertFalse(off.inverse)
        assertFalse(off.strikethrough)
    }

    @Test
    fun bareResetRestoresDefault() {
        val t = term()
        t.feed("${E}[1;31mA${E}[mB")
        assertEquals(TextStyle.DEFAULT, t.cellAt(1, 0).style)
    }

    @Test
    fun defaultColorParams39And49() {
        val t = term()
        t.feed("${E}[31;44mA${E}[39;49mB")
        val cell = t.cellAt(1, 0).style
        assertEquals(TerminalColor.Default, cell.fg)
        assertEquals(TerminalColor.Default, cell.bg)
        assertEquals(true, t.cellAt(0, 0).style.fg is TerminalColor.Indexed)
    }

    @Test
    fun eraseFillsWithCurrentBackground() {
        val t = term()
        t.feed("${E}[41m${E}[2J")
        // BCE：清屏后的空白格带当前红色背景。
        assertEquals(TerminalColor.Indexed(1), t.cellAt(5, 2).style.bg)
    }

    @Test
    fun unknownParamsAreIgnored() {
        val t = term()
        t.feed("${E}[95;999mX")
        assertEquals(TerminalColor.Indexed(13), t.cellAt(0, 0).style.fg)
    }
}
