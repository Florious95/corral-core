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

import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 红测 · fix-pinch-preview-commit（raw/041 裁定：捏合时本地预览、松手时才发一次 resize）。
 *
 * 判据（leader 批准）：
 * > 一次完整捏合手势（多个 onScale + 一个手势结束），`onResizeRequest` 必须恰好被调用 1 次，
 * > 且发生在手势结束时。
 *
 * 当前实现（TermSurfaceView.onScale → presenter.onFontSizeChanged → onResizeRequest）每个
 * onScale 都 emit —— 一次捏合 = N 次（N 是手势步数）。本红测当前必红，红的实际数字 = N。
 *
 * 守卫（leader 必做）：
 * 1. 手势过程中**字号必须实时变化**（本地预览生效），不能为了少发帧连预览都不做。
 * 2. 手势结束后那**一次** resize 必须带**最终**行列数，不是中间某步的。
 * 3. 回归门 TermSurfacePinchGestureTest 保持绿（手势结束检测器复位）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermPinchPreviewCommitTest {

    private class Harness(
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
        val view: TermSurfaceView,
    )

    private fun harness(): Harness {
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val emulator = TerminalEmulator(cols = 80, rows = 24)
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            resizeCalls += rows to cols
            emulator.resize(cols, rows)
        }
        val view = TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            this.presenter = presenter
            layout(0, 0, 1080, 1920)
        }
        return Harness(emulator, presenter, resizeCalls, view)
    }

    private fun scaleDetector(view: TermSurfaceView): ScaleGestureDetector {
        val field = TermSurfaceView::class.java.declaredFields.single {
            ScaleGestureDetector::class.java.isAssignableFrom(it.type)
        }
        field.isAccessible = true
        return field.get(view) as ScaleGestureDetector
    }

    /** 一次完整捏合手势：DOWN → POINTER_DOWN → 多个 MOVE（onScale）→ POINTER_UP → UP。 */
    private fun pinch(downTime: Long): List<MotionEvent> = listOf(

        event(downTime, downTime, MotionEvent.ACTION_DOWN, 540f - 40f to 960f),
        event(
            downTime, downTime + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            540f - 40f to 960f, 540f + 40f to 960f,
        ),
        // 5 个 MOVE（放大），每个都触发 onScale。
        event(downTime, downTime + 20, MotionEvent.ACTION_MOVE, 540f - 60f to 960f, 540f + 60f to 960f),
        event(downTime, downTime + 30, MotionEvent.ACTION_MOVE, 540f - 90f to 960f, 540f + 90f to 960f),
        event(downTime, downTime + 40, MotionEvent.ACTION_MOVE, 540f - 120f to 960f, 540f + 120f to 960f),
        event(downTime, downTime + 50, MotionEvent.ACTION_MOVE, 540f - 150f to 960f, 540f + 150f to 960f),
        event(downTime, downTime + 60, MotionEvent.ACTION_MOVE, 540f - 180f to 960f, 540f + 180f to 960f),
        event(
            downTime, downTime + 70, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            540f - 180f to 960f, 540f + 180f to 960f,
        ),
        event(downTime, downTime + 80, MotionEvent.ACTION_UP, 540f - 180f to 960f),
    )

    /**
     * 核心判据：一次完整捏合 → onResizeRequest 恰好 1 次（且发生在手势结束时）。
     * 当前实现每个 MOVE emit → 预期红，红的实际数字 = resizeCalls.size（≥2）。
     */
    @Test
    fun pinchGestureEmitsResizeExactlyOnceAtEnd() {
        val h = harness()
        val detector = scaleDetector(h.view)
        h.resizeCalls.clear() // 排除 layout 触发的首帧 seed（96,108）
        val events = pinch(downTime = 1000L)

        try {
            for (e in events) h.view.onTouchEvent(e)
            println("[RED-N] 完整捏合 resize 次数 = ${h.resizeCalls.size}, 序列 = ${h.resizeCalls}")

            // 判据：恰好 1 次（松手时）。
            assertEquals(
                "一次完整捏合必须恰好发 1 次 resize（raw/041 裁定：预览不重排、松手才生效）——" +
                    "当前每次 onScale 都发，实际发了 ${h.resizeCalls.size} 次",
                1, h.resizeCalls.size,
            )
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    /** 守卫1：手势过程中字号必须实时变化（本地预览生效，不能为了少发帧连预览都不做）。 */
    @Test
    fun pinchPreviewChangesFontSizeLive() {
        val h = harness()
        val detector = scaleDetector(h.view)
        val initialW = h.presenter.cellWidth
        val initialH = h.presenter.cellHeight
        val events = pinch(downTime = 2000L)

        try {
            // 只喂前几个 MOVE（未结束手势），字号应已变化（预览生效）。
            for (e in events.take(5)) h.view.onTouchEvent(e)
            assertTrue(
                "手势过程中字号必须实时变化（本地预览生效）：$initialW x $initialH -> " +
                    "${h.presenter.cellWidth} x ${h.presenter.cellHeight}",
                h.presenter.cellWidth > initialW || h.presenter.cellHeight > initialH,
            )
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    /** 守卫2：手势结束那一次 resize 必须带最终行列数，不是中间某步的。
     *  用「最终字号推算的预期 cols」vs「中间字号推算的预期 cols」验证。 */
    @Test
    fun pinchCommitCarriesFinalDims() {
        val h = harness()
        h.resizeCalls.clear() // 排除首帧 seed
        val events = pinch(downTime = 3000L)

        try {
            // 先发前几个 MOVE（放大），记录中间字号。
            for (e in events.take(5)) h.view.onTouchEvent(e)
            val midW = h.presenter.cellWidth
            val midH = h.presenter.cellHeight
            // 继续放大到最大，结束手势。
            for (e in events.drop(5)) h.view.onTouchEvent(e)
            val finalW = h.presenter.cellWidth
            val finalH = h.presenter.cellHeight

            // 唯一一次 resize 的 cols 应对应最终字号（finalW），不是中间字号（midW）。
            assertEquals("守卫2：手势结束的 resize 必须带最终行列数", 1, h.resizeCalls.size)
            val (rows, cols) = h.resizeCalls[0]
            // 预期 cols = viewportWidth / 最终字号；若最终字号 > 中间字号，最终 cols 应 < 中间 cols。
            // viewport 宽 1080。比较「最终字号推算」vs「中间字号推算」。
            val expectedFinalCols = 1080 / finalW
            val expectedMidCols = 1080 / midW
            assertEquals("resize 的 cols 应等于最终字号推算（1080/finalW）", expectedFinalCols, cols)
            if (finalW > midW) {
                assertTrue(
                    "最终字号($finalW)>中间($midW) 时，最终 cols($cols) 应小于中间 cols($expectedMidCols)",
                    cols < expectedMidCols,
                )
            }
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    private fun event(
        downTime: Long, eventTime: Long, action: Int, vararg points: Pair<Float, Float>,
    ): MotionEvent {
        val properties = Array(points.size) { index ->
            MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(points.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = points[index].first; y = points[index].second; pressure = 1f; size = 1f
            }
        }
        return MotionEvent.obtain(downTime, eventTime, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
