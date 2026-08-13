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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光标判据的**决定性自检**（leader msg_bba4afdd6e28）：recap 开头的光标未下探期。
 *
 * 场景：整屏 recap 的**开头**——清屏 + 写第一行（行 0），光标还在顶部（行 0），
 * 然后**停顿**（模拟 TS 高 RTT 下的分片间隔），之后才继续整屏重写。
 *
 * 风险：此刻光标未下探（在行 0 = 与 clear 提示符同位置），若判据误判为「clear 类」→ 落定
 * → 露出刚清屏的空白屏（只有行 0 有字）→ 然后内容陆续出现 = **用户报的「从上往下刷」**。
 *
 * 断言（leader 的原话）：
 *  - **看到旧内容**（停顿期间画面停在清屏前的完整画面）→ 设计成立；
 *  - **看到空白屏**（停顿期间露出只有顶部一行的空屏）→ 我们在制造用户报的现象 → 设计不成立。
 *
 * 实现路径：光标下探判据必须是「**是否观察到光标 Y 相对清屏时上升/下探过**」，而不是
 * 「当前光标 Y 在不在顶部」——否则行 0 写入（光标仍行 0）会被误判 clear。真正要测的是：
 * **清屏后写第一行（光标行 0）→ 停顿 → 此时取帧，画面必须仍是旧内容（抑制保持）。**
 */
class TermRewriteRecapStartPauseTest {

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
                p.clock = clock
                return Harness(clock, e, p)
            }
        }
    }

    /**
     * 决定性测试：recap 开头（清屏+行0+停顿）不露空白屏。
     * 停顿期间取帧必须返回「空」（抑制保持，画面停在旧内容），不得呈现只含顶部一行的空屏。
     */
    @Test
    fun recapStart_pauseBetweenFirstRowAndRest_doesNotShowBlank() {
        val h = Harness.create()
        // 初始填满 10 行（旧内容）。
        h.emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        h.presenter.takeDamage()

        // recap 开头：清屏 + 写第一行（行 0）。
        h.emulator.feed("[2J")
        h.presenter.takeFrameRepaint() // 进入抑制
        h.emulator.feed("[1;1Hrecap-line0") // 光标停在行 0

        // 停顿（模拟 TS 分片间隔）：此刻取帧，必须仍是抑制（空呈现），不得露空白屏。
        h.clock.advance(700) // 模拟 765ms 量级的分片间隔
        val duringPause = h.presenter.takeFrameRepaint()

        // 关键断言：停顿期间画面停在旧内容（抑制保持），不是只有行 0 的空屏。
        assertTrue(
            "recap 开头停顿期间必须保持抑制（画面停旧内容，不露空屏）：实得 $duringPause",
            duringPause != null && duringPause.isEmpty(),
        )

        // 之后继续整屏重写 → 落定一次性呈现。
        for (row in 1 until 12) h.emulator.feed("[${row + 1};1Hrecap-$row")
        val settled = h.presenter.takeFrameRepaint()
        assertTrue(
            "继续重写后必须落定一次性呈现：实得 $settled",
            settled != null && settled.any { it.first <= 0 && it.last >= 11 },
        )
    }

    /** 对照：真正 clear（无后续）→ 应在合理短时内呈现清屏结果（提示符顶部）。 */
    @Test
    fun realClear_stillPresentsPromptly() {
        val h = Harness.create()
        h.emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        h.presenter.takeDamage()

        h.emulator.feed("[2J")
        h.presenter.takeFrameRepaint()
        h.emulator.feed("[1;1H\$ ")

        // clear 无后续：应呈现（不是永久抑制）。
        h.clock.advance(700) // 模拟某上界
        val presented = h.presenter.takeFrameRepaint()
        assertTrue(
            "clear 无后续应最终呈现（不冻屏）：实得 $presented",
            presented != null && presented.isNotEmpty(),
        )
    }
}
