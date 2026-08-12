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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-36 手势级红测：真实 MotionEvent 手势序列（下拖 = 终端惯例「露出上方内容/看历史」）
 * 驱动空 scrollback 的视口进入可补页锁定态 —— 与 [TermGestureDirectionTest] 同模式，
 * 但锁定的是「空 buffer 也能锁定 → 触发补页」的鸡生蛋打破。
 *
 * 用户现象「向上滑完全失效、只有一屏」的根因之一：本地 buffer 为空时 `maxTop==0`，
 * 手势滚动的 `next` 被钳到 0 且恒触底 → topLine 恒 null（跟随）→ 补页条件永不走。
 * 修复后：空 buffer 下拖（deltaLines 正）显式锁定到逻辑行 0，视口可补页。
 *
 * 修前红：gesture 后 isFollowingBottom 仍为 true（锁不住）→ 本测试断言失败。
 * 修后绿：gesture 后进入锁定态、showBackToBottom 出现、窗口顶为逻辑行 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermGesturePagingRedTest {

    @Test
    fun fingerDragOnEmptyScrollbackLocksForPaging() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        // 打开会话初始态：snapshot 填满网格，scrollback 恒空（用户「只有一屏」）。
        emulator.replaySnapshot("abc\ndef\nghi", cols = 10, rows = 3)
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            this.presenter = presenter
            layout(0, 0, 200, 120)
        }
        view.draw(Canvas(Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)))

        // 手势：手指下拖（y 增大）= 露出上方内容/看历史（与 TermGestureDirectionTest 同向）。
        // lineHeightPx = presenter.cellHeight = 20 ⇒ 下拖 100px = 5 行。
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 10f, 0)
        val move = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_MOVE, 100f, 110f, 0)
        try {
            view.onTouchEvent(down)
            view.onTouchEvent(move)
        } finally {
            down.recycle()
            move.recycle()
        }

        // 修复前红：空 buffer 下拖锁不住，恒跟随底部。
        assertFalse(
            "空 scrollback 下拖手势后仍跟随底部（鸡生蛋：锁不住就永远不补页）",
            presenter.isFollowingBottom,
        )
        // 锁定到可补页锚点（逻辑行 0）。
        assertTrue("空 scrollback 下拖后应进入可补页锁定态", presenter.showBackToBottom)
        assertTrue("锁定后窗口顶应为逻辑行 0", presenter.window.first == 0)
    }
}
