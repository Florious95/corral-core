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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-36 鸡生蛋红测：本地 buffer 为空（打开会话初始态）时，向上滑动必须进入**可补页的锁定态**，
 * 而不是恒跟随底部 —— 否则「滚到顶才拉历史」的触发条件永远走不到。
 *
 * 现状（修前红）：`replaySnapshot` 用 capture-pane 可见屏填满网格、无行滚出 ⇒ `scrollback.size == 0`
 * ⇒ `logicalCount == rows` ⇒ `maxTop == 0` ⇒ `onScrollBy` 的 `next` 被 `coerceIn(0,0)` 钳到 0 且
 * `next >= maxTop` 恒真 ⇒ `topLine` 恒 `null`（跟随态）⇒ 上滑完全无效，补页永不触发。
 *
 * 修复（绿）：`deltaLines > 0`（上滑）且 `maxTop == 0`（空 buffer 无可滚空间）时，
 * 显式置 `topLine = 0`（锁定到逻辑行 0 = 可补页锚点），使 `window.first == 0`，上层
 * `syncFromPresenter` 的补页条件即可命中。
 */
class TermViewScrollbackBoundaryTest {

    @Test
    fun scrollUpWithEmptyScrollbackLocksToPagingAnchor() {
        // 打开会话初始态：snapshot 填满网格，scrollback 恒空。
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        emulator.replaySnapshot("abc\ndef\nghi", cols = 10, rows = 3)
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        assertTrue("初始应跟随底部", presenter.isFollowingBottom)
        assertEquals("初始无历史可滚", 0, emulator.scrollback.size)

        presenter.onScrollBy(deltaLines = 1) // 上滑一屏

        // 空 buffer 上滑必须能锁定（进入可补页态），而非恒跟随 → 修前红。
        assertFalse("空 scrollback 上滑后仍跟随底部（鸡生蛋：锁不住）", presenter.isFollowingBottom)
        assertTrue("空 scrollback 上滑后应出现「回到底部」可补页锚点", presenter.showBackToBottom)
        assertEquals("锁定后窗口顶应为逻辑行 0（补页锚点）", 0, presenter.window.first)
    }

    @Test
    fun scrollDownOnEmptyScrollbackStaysFollowing() {
        // 空 buffer 下拖（deltaLines 负）语义不变：仍跟随底部，不进锁定态。
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        emulator.replaySnapshot("abc\ndef\nghi", cols = 10, rows = 3)
        val presenter = TermViewPresenter(emulator) { _, _ -> }

        presenter.onScrollBy(deltaLines = -1)

        assertTrue("空 buffer 下拖不应锁定", presenter.isFollowingBottom)
        assertFalse(presenter.showBackToBottom)
    }
}
