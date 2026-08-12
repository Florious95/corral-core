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
 * recap 抑制的**兜底**红测（leader 硬约束：抑制必须有可观测的硬上界兜底，禁止冻屏）。
 *
 * 失败模式比原缺陷更严重：如果「整屏重写」开始后永不落定（重写中断 / 数据流卡住 /
 * 落定判据设计有漏洞——CLI 可能重写完可视区就停、末行留空、或被用户输入打断），
 * 无上界的抑制会让**画面冻住不更新**。闪一下难看，卡住不能用。
 *
 * 兜底契约：
 *  - 落定判据**结构性**（整屏每行都被重写过 = 覆盖全窗口），不是计时；
 *  - 有**硬上界**：距上一次收到数据超过 X 仍未落定 → 立刻呈现当前状态（宁可一次中间态，
 *    不可冻屏）；
 *  - **上界触发可观测**（计数器递增），禁止静默兜底（与右缘护栏同纪律）；
 *  - 兜底先于抑制实现（leader msg_3e2306931158：先把兜底写了再写抑制）。
 *
 * 本测试构造「重写开始后永不落定」的输入（清屏 + 只重写上半屏，永不写到下半屏），
 * 断言：
 *  1. 抑制开启（isRewriteInProgress）但画面不能冻死——数据静默超上界后必须仍出帧；
 *  2. 兜底计数器递增（可观测，非静默）；
 *  3. 兜底呈现的是当前状态（非空重绘范围），不是空白。
 */
class TermRewriteFallbackTest {

    private val ROWS = 12

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
                p.clock = clock // 字段注入假时钟（不进构造：见 presenter clock KDoc）
                return Harness(clock, e, p)
            }
        }
    }

    @Test
    fun rewriteThatNeverSettles_stillPresentsAfterBoundedFallback_notFrozen() {
        val h = Harness.create()
        h.emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        // 排掉初始整屏脏区。
        h.presenter.takeDamage()

        // 整屏重写开始：ED2 清屏（进入抑制态）。
        h.emulator.feed("[2J")
        val entering = h.presenter.takeFrameRepaint()
        assertTrue("清屏应进入抑制态（空呈现）", entering != null && entering.isEmpty())
        assertTrue("清屏后应处于抑制态", h.presenter.isRewriteInProgress)

        // 重写只覆盖上半屏（0..5 行），**永不写到下半屏** → 结构性落定判据（覆盖全窗口）永等不到。
        for (row in 0..5) h.emulator.feed("[${row + 1};1Hpartial-$row")

        // 此刻仍在抑制态（未落定），但若没有兜底 → 画面将冻住。
        assertTrue("未覆盖全窗口不应落定", h.presenter.isRewriteInProgress)

        // 数据静默超过上界（模拟帧回调不再被唤醒 → 兜底触发）。
        // 先排空已到脏区（抑制态期间每次查询都是空呈现，画面停在上帧）；此后数据静默，
        // 下一次帧回调查询 hit 兜底。
        h.presenter.takeFrameRepaint() // 抑制态：空呈现，画面停住
        assertTrue("抑制态期间呈现为空、仍在抑制", h.presenter.isRewriteInProgress)

        val fallbackBefore = h.presenter.rewriteFallbackCount
        // 推进假时钟到「距上次数据超过阈值」：lastDataMs 停在最后一次 feed 的时刻（1_000_000），
        // 此刻把时钟推进 >2s（rewriteSilenceTimeoutMs=2000），让 takeFrameRepaint 的空脏区查询触发兜底。
        h.clock.advance(3_000L) // 距上次数据 3s > 2s 阈值
        val presented = h.presenter.takeFrameRepaint()

        // 1. 兜底必须出帧（非空），画面不能冻死。
        assertTrue(
            "数据静默超上界后必须仍呈现当前状态（不冻屏），实得 $presented",
            presented != null && presented.isNotEmpty(),
        )
        // 2. 兜底必须可观测：计数器递增（禁止静默兜底）。
        assertTrue(
            "兜底必须递增计数器（可观测，非静默）：before=$fallbackBefore after=${h.presenter.rewriteFallbackCount}",
            h.presenter.rewriteFallbackCount > fallbackBefore,
        )
        // 3. 兜底呈现的是当前状态（上半屏重写内容），不是空白。
        assertTrue(
            "兜底呈现必须覆盖当前重写的上半屏（0..5），实得 $presented",
            presented != null && presented.any { it.first <= 0 && it.last >= 5 },
        )
    }
}
