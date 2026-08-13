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

import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试用 ESC 字符常量（裸字面量易碎，统一显式转义，见 term-core 沉淀）。 */
private const val E = ""

/**
 * clear 场景测试（leader msg_cb560692a120）：用户敲 clear 后屏幕清空、提示符只写第 1 行，
 * 其余行**永远不被重写**（本就该空）。落定判据若依赖「每一行都被重写」→ 永远等不到 →
 * 直到硬上界兜底才呈现 → 用户看到旧内容残留 2 秒（我们主动写进去的日常场景缺陷）。
 *
 * 断言（测试先行，先测后改）：
 *  - clear 结果必须在合理短时（<500ms）内可见——不能等 2000ms 硬上界；
 *  - 不得靠硬上界兜底（fallbackCount 不递增）才呈现——否则 clear 每次都要兜底。
 */
class TermRewriteClearScenarioTest {

    private class Harness(
        val clock: FakeClock,
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
    ) {
        companion object {
            fun create(): Harness {
                val clock = FakeClock(1_000_000L)
                val e = TerminalEmulator(20, 12)
                val p = TermViewPresenter(e) { _, _ -> }
                p.clock = clock // 字段注入假时钟
                return Harness(clock, e, p)
            }
        }
    }

    @Test
    fun clearWithOnlyPromptRow_presentsPromptly_notViaHardBound() {
        val h = Harness.create()
        h.emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        // 排掉初始整屏脏区。
        h.presenter.takeDamage()

        // 用户敲 clear：ED2 清屏（进入抑制态，画面停在旧内容）。
        h.emulator.feed("${E}[2J")
        val clearFrame = h.presenter.takeFrameRepaint()
        assertTrue("clear 清屏帧应为空（抑制）", clearFrame != null && clearFrame.isEmpty())

        // 提示符只写第 1 行（clear 语义：其余行保持空白，永不重写）。
        h.emulator.feed("${E}[1;1H\$ ")
        h.presenter.takeFrameRepaint() // 消耗提示符脏区，仍抑制

        // 模拟帧循环：每 50ms 查一次，记录「清屏结果首次可见」的时刻。
        val fallbackBefore = h.presenter.rewriteFallbackCount
        var visibleAtMs: Long = -1
        for (step in 1..60) { // 60×50ms = 3s 上限
            h.clock.advance(50)
            val r = h.presenter.takeFrameRepaint()
            if (r != null && r.isNotEmpty()) {
                visibleAtMs = step * 50L
                break
            }
        }

        // 报告数值（用户可见的 clear 结果出现时间）。
        println("[CLEAR] visible at ${visibleAtMs}ms fallback=${h.presenter.rewriteFallbackCount}")

        assertTrue("clear 结果必须及时可见（<500ms），实得 ${visibleAtMs}ms", visibleAtMs in 0..500)
        assertFalse("clear 场景不得靠硬上界兜底才呈现", h.presenter.rewriteFallbackCount > fallbackBefore)
    }
}
