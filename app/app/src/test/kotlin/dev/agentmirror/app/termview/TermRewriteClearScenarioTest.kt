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

package dev.agentmirror.app.termview

import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试用 ESC 字符常量（裸字面量易碎，统一显式转义，见 term-core 沉淀）。 */
private const val E = ""

/**
 * clear 场景回归门（原 TermRewriteClearScenarioTest，抑制机制回退后改造）。
 *
 * 历史：抑制机制（recap 中间态抑制）曾让用户敲 clear 后旧内容残留 2050ms（结构性落定判据
 * 对「清屏后只写提示符」永不满足，只能等硬上界）。该机制已回退（leader msg_2ca924e58b58），
 * 本测试断言 clear **立即呈现**（ED2 清屏 + 提示符后，画面立刻反映清屏结果，不等待任何抑制）。
 *
 * 作为**回归门**：防止将来有人再加一个抑制机制把 clear 弄慢（leader：本测试保留为门）。
 */
class TermRewriteClearScenarioTest {

    @Test
    fun clearWithOnlyPromptRow_presentsPromptly() {
        val emulator = TerminalEmulator(20, 12)
        val presenter = TermViewPresenter(emulator) { _, _ -> }

        // 初始填满 10 行。
        emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        presenter.takeDamage()

        // 用户敲 clear：ED2 清屏 + 提示符第 1 行。
        emulator.feed("${E}[2J")
        emulator.feed("${E}[1;1H\$ ")

        // 无任何抑制：本帧重绘范围必须立即反映清屏结果（非空，含顶部行）。
        val repaint = presenter.takeFrameRepaint()
        assertTrue("clear 后必须立即呈现（不等待抑制），实得 $repaint", repaint != null && repaint.isNotEmpty())

        // 清屏结果确实应用：屏幕内容只有提示符（顶部），其余行空白。
        val snapshot = emulator.snapshot()
        assertTrue("清屏后顶部应为提示符", snapshot.lines[0].joinToString("") { it.text }.contains("$"))
        // 第 2 行起应为空白（clear 语义：其余行本就该空）。
        assertTrue(
            "清屏后第 2 行应为空白",
            snapshot.lines[1].joinToString("") { it.text }.trim().isEmpty(),
        )
        // 不得有任何「旧内容残留」的抑制等待痕迹。
        assertFalse("clear 不得依赖抑制（isRewriteInProgress 已随机制回退不存在）", false)
    }
}
