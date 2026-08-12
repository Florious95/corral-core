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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM 回归门：真实多指 MotionEvent 必须贯通 View 的缩放探测器并改变终端字格。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermSurfacePinchGestureTest {

    @Test
    fun spreadingTwoPointersScalesCellsAndEndsDetectorGesture() {
        val presenter = TermViewPresenter(TerminalEmulator(cols = 80, rows = 24)) { _, _ -> }
        val view = TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            this.presenter = presenter
            layout(0, 0, 1080, 1920)
        }
        val initialWidth = presenter.cellWidth
        val initialHeight = presenter.cellHeight
        val detector = scaleDetector(view)
        val events = spreadingPinch(downTime = 1_000L, centerX = 540f, centerY = 960f)

        try {
            view.onTouchEvent(events[0]) // ACTION_DOWN
            view.onTouchEvent(events[1]) // ACTION_POINTER_DOWN
            view.onTouchEvent(events[2]) // ACTION_MOVE: crosses scale slop
            view.onTouchEvent(events[3]) // ACTION_MOVE: starts the detector gesture
            view.onTouchEvent(events[4]) // ACTION_MOVE: delivers a non-unit scale factor

            assertTrue("ScaleGestureDetector 未进入缩放态", detector.isInProgress)
            assertTrue(
                "onScale 未调用 presenter.onFontSizeChanged: " +
                    "${initialWidth}x$initialHeight -> ${presenter.cellWidth}x${presenter.cellHeight}",
                presenter.cellWidth > initialWidth && presenter.cellHeight > initialHeight,
            )

            view.onTouchEvent(events[5]) // ACTION_POINTER_UP
            view.onTouchEvent(events[6]) // ACTION_UP
            assertFalse("双指抬起后 ScaleGestureDetector 仍在缩放态", detector.isInProgress)
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    /** 不依赖私有字段名，只读取 View 唯一的缩放探测器以区分“探测器触发”与“字号改变”。 */
    private fun scaleDetector(view: TermSurfaceView): ScaleGestureDetector {
        val field = TermSurfaceView::class.java.declaredFields.single {
            ScaleGestureDetector::class.java.isAssignableFrom(it.type)
        }
        field.isAccessible = true
        return field.get(view) as ScaleGestureDetector
    }

    private fun spreadingPinch(downTime: Long, centerX: Float, centerY: Float): List<MotionEvent> = listOf(
        event(downTime, downTime, MotionEvent.ACTION_DOWN, centerX - 40f to centerY),
        event(
            downTime,
            downTime + 10,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            centerX - 40f to centerY,
            centerX + 40f to centerY,
        ),
        event(
            downTime,
            downTime + 20,
            MotionEvent.ACTION_MOVE,
            centerX - 80f to centerY,
            centerX + 80f to centerY,
        ),
        event(
            downTime,
            downTime + 30,
            MotionEvent.ACTION_MOVE,
            centerX - 140f to centerY,
            centerX + 140f to centerY,
        ),
        event(
            downTime,
            downTime + 40,
            MotionEvent.ACTION_MOVE,
            centerX - 200f to centerY,
            centerX + 200f to centerY,
        ),
        event(
            downTime,
            downTime + 50,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            centerX - 200f to centerY,
            centerX + 200f to centerY,
        ),
        event(downTime, downTime + 60, MotionEvent.ACTION_UP, centerX - 200f to centerY),
    )

    private fun event(
        downTime: Long,
        eventTime: Long,
        action: Int,
        vararg points: Pair<Float, Float>,
    ): MotionEvent {
        val properties = Array(points.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = points[index].first
                y = points[index].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            points.size,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }
}
