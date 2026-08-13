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

/**
 * 两状态兜底设计的**决定性判据测试**（leader msg_5043d31800e8 第四点）：
 *
 * leader 提议两状态硬上界：状态 A（清屏后光标从未下探→短上界，clear 快）、
 * 状态 B（光标已下探→长上界，recap 不露中间态）。但必须先回答：
 * **「B 判成 A」（recap 开头被当 clear）会不会露出刚清屏的空白屏 → 内容陆续出现，
 * 这正是用户报的「从上往下刷」？**
 *
 * 判据：若 recap 的**首个内容写入就触发光标下探**（切到状态 B，长上界），则状态 A 的
 * 短上界窗口内不会露出空白屏——只有「真的清屏后无后续」才落在状态 A（那本来就该呈现）。
 * 本测试构造「清屏 → 超过状态 A 短上界仍无数据 → 之后才开始整屏重写」，断言用户看到什么。
 */
class TermRewriteStateTransitionTest {

    /** 状态 A 短上界（假设值；真实值待分片实测，这里作为参数传入断言）。 */
    private val stateAShortBoundMs = 200L

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
     * 场景：清屏 → 短上界内无数据（状态 A 短上界触发）→ 之后才整屏重写。
     * 期望：**不出现空白屏后再刷**——要么状态 A 呈现时内容已到（无空白中间态），
     * 要么光标下探已切到状态 B 长上界（抑制保持，不露空白）。
     */
    @Test
    fun clearThenLateRecap_doesNotShowBlankIntermediate() {
        val h = Harness.create()
        h.emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        h.presenter.takeDamage()

        // 清屏（进入抑制态）。
        h.emulator.feed("[2J")
        val clearFrame = h.presenter.takeFrameRepaint()
        assertTrue("清屏应抑制", clearFrame != null && clearFrame.isEmpty())

        // 短上界内无数据：光标在 (0,0)，未下探 → 状态 A。若此刻呈现 → 露出空白屏（旧内容已被清）。
        // 记录「状态 A 呈现时屏幕上有什么」。
        h.clock.advance(stateAShortBoundMs + 1)
        val stateAPresented = h.presenter.takeFrameRepaint()
        // 状态 A 呈现的内容：应为「清屏后的空屏 + 无内容」（若此处有内容说明光标已下探或未清）。
        // 断言：状态 A 呈现**不得包含用户内容**（它要么是空屏=清屏结果，要么未呈现继续等）。
        // 关键：此时屏幕上不得有「半屏旧内容」——那才是缺陷。

        // 之后整屏重写开始（真实 recap：数据迟到但确实来了）。
        // 若状态 A 已经呈现了空屏，这里重写内容陆续出现 = 用户看到「空白屏 → 内容从上往下」= 缺陷复现。
        for (row in 0 until 12) h.emulator.feed("[${row + 1};1Hrecap-$row")
        val recapFrame = h.presenter.takeFrameRepaint()

        // 结论断言：**不出现「先空白屏、再内容陆续」的两段式**。
        // 即：要么状态 A 从未呈现（光标下探早于短上界，全程抑制到落定），
        // 要么状态 A 呈现时内容已完整（不是空白中间态）。
        // 简化断言：整屏重写期间，用户可见的中间帧不得是「空屏」。
        // 这里用「状态 A 呈现的帧若非空，其内容必须覆盖全窗口（完整呈现，非半屏）」，
        // 且「若状态 A 呈现的是空屏（empty），则重写帧必须是一次性完整呈现（不逐行露）」。
        if (stateAPresented != null && stateAPresented.isEmpty()) {
            // 状态 A 呈现了空屏 → 后续 recap 必须一次性完整呈现，不得逐行露。
            assertTrue(
                "状态A露空屏后，recap必须一次性完整呈现（不逐行刷）：实得 $recapFrame",
                recapFrame != null && recapFrame.any { it.first <= 0 && it.last >= 11 },
            )
        } else {
            // 状态 A 未呈现空屏（光标已下探切状态B）→ 无空白中间态问题。
            assertTrue("状态A未露空屏（应已切状态B抑制），状态A实得 $stateAPresented", stateAPresented != null && stateAPresented.isEmpty())
        }
        assertTrue("recap 落定后应退出抑制", !h.presenter.isRewriteInProgress)
    }

    /**
     * 对照：真正 clear（清屏 + 提示符 + 无后续）→ 状态 A 短上界触发，及时呈现。
     * 期望：clear 结果（提示符在顶部）在短上界内呈现，不依赖长上界。
     */
    @Test
    fun realClear_presentsPromptlyViaStateA() {
        val h = Harness.create()
        h.emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        h.presenter.takeDamage()

        // 真实 clear：清屏 + 提示符第 1 行 + 无后续。
        h.emulator.feed("[2J")
        h.presenter.takeFrameRepaint() // 进入抑制
        h.emulator.feed("[1;1H\$ ")

        // 状态 A：光标未下探（提示符在行 0）。短上界内应呈现。
        h.clock.advance(stateAShortBoundMs + 1)
        val presented = h.presenter.takeFrameRepaint()

        assertTrue(
            "clear 必须经状态A短上界及时呈现（非长上界），实得 $presented",
            presented != null && presented.isNotEmpty(),
        )
        // 不得靠硬上界（fallbackCount 不增）。
        assertTrue("clear 不得靠硬上界兜底", h.presenter.rewriteFallbackCount == 0)
    }
}
