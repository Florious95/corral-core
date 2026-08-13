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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 取证内省接口的无副作用守门（leader 硬约束：forensicsSnapshot 常驻只读，零副作用）。
 *
 * 断言：调用 forensicsSnapshot() 前后，resize 次数、脏区（takeDamage）、帧请求计数都**不变**——
 * 证明它是纯读取，不会成为耦合点（w-dev-cols 巡检层可安全调用）。
 */
class TermForensicsSnapshotSideEffectTest {

    @Test
    fun forensicsSnapshotHasNoSideEffects() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { r, c -> resizeCalls.add(r to c) }
        var frameRequests = 0
        presenter.onFrameRequested = { frameRequests++ }

        // 建立一些状态（历史 + 脏区）。
        emulator.replaySnapshot("abc\ndef\nghi", cols = 10, rows = 3)
        emulator.feed("jkl\nmno\npqr\n")
        presenter.onScrollBy(deltaLines = 1) // 锁定历史
        // 消费现有脏区与帧请求计数基线。
        presenter.takeDamage()
        val frameBaseline = frameRequests
        val resizeBaseline = resizeCalls.size

        // 调用取证快照（多次）。
        val s1 = presenter.forensicsSnapshot()
        val s2 = presenter.forensicsSnapshot()

        // 快照自身内容正确（相对断言：反映当前状态，不硬编码行数）。
        val sb = emulator.scrollback.size
        assertEquals("scrollbackSize 应反映当前历史", sb, s1.scrollbackSize)
        assertEquals("logicalCount = scrollback + rows", sb + 3, s1.logicalCount)
        assertEquals("visibleRows 应 = 内核行数", 3, s1.visibleRows)
        assertEquals("maxTop = logicalCount - visibleRows", sb, s1.maxTop)
        assertEquals("上滑后应锁定历史（非跟随）", false, s1.isFollowingBottom)
        assertEquals("topLine 应非 null（锁定态）", true, s1.topLine != null)
        assertEquals("两次快照必须一致（无副作用、无状态漂移）", s1, s2)

        // 无副作用：resize 数、帧请求数、脏区均不变。
        assertEquals("forensicsSnapshot 不得触发 resize", resizeBaseline, resizeCalls.size)
        assertEquals("forensicsSnapshot 不得触发帧请求", frameBaseline, frameRequests)
        assertEquals("forensicsSnapshot 不得产生脏区", emptyList<IntRange>(), presenter.takeDamage())
    }
}
