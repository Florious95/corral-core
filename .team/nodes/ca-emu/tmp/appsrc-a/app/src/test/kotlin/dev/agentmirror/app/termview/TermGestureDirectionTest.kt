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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真手势回归：手指下拖应像终端/列表惯例一样露出上方历史。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermGestureDirectionTest {

    @Test
    fun fingerDragDownScrollsIntoHistory() {
        val emulator = TerminalEmulator(cols = 10, rows = 2)
        emulator.feed("a\r\nb\r\nc\r\nd")
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            this.presenter = presenter
            layout(0, 0, 200, 80)
        }
        // 首次绘制建立行高；手势换算必须使用与画布相同的网格尺寸。
        view.draw(Canvas(Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)))

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 20f, 0)
        val move = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_MOVE, 100f, 70f, 0)
        try {
            view.onTouchEvent(down)
            view.onTouchEvent(move)
        } finally {
            down.recycle()
            move.recycle()
        }

        assertFalse("下拖后仍停在底部，滚动方向反了", presenter.isFollowingBottom)
    }
}
