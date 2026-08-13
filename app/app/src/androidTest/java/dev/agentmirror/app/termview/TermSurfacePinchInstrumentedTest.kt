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

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.agentmirror.terminal.TerminalEmulator

/**
 * 设备链路回归：UiAutomation 注入系统触摸流，事件须经窗口分发到真实 TermSurfaceView。
 *
 * 工程尚无 androidTest 依赖，故 runner 自执行并用进程退出码表示结果；运行命令：
 * `adb shell am instrument -w dev.agentmirror.app.test/$PACKAGE.PinchHarnessInstrumentation`。
 */
class PinchHarnessInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // 基类只完成注册；显式 start 才会在 instrumentation 线程调用 onStart。
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runPinchTest()
            result.putString("pinch_harness", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("pinch_harness", "FAIL")
            result.putString("stack", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun runPinchTest() {
        val activity = startActivitySync(
            Intent().setClassName(targetContext.packageName, "androidx.activity.ComponentActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val presenter = TermViewPresenter(TerminalEmulator(cols = 80, rows = 24)) { _, _ -> }
        lateinit var view: TermSurfaceView
        try {
            runOnMainSync {
                view = TermSurfaceView(activity).apply { this.presenter = presenter }
                activity.setContentView(view)
            }
            waitForIdleSync()

            val location = IntArray(2)
            runOnMainSync { view.getLocationOnScreen(location) }
            val centerX = location[0] + view.width / 2f
            val centerY = location[1] + view.height / 2f
            val initialWidth = presenter.cellWidth
            val initialHeight = presenter.cellHeight
            val downTime = SystemClock.uptimeMillis()
            val events = spreadingPinch(downTime, centerX, centerY)

            try {
                for (event in events) {
                    check(uiAutomation.injectInputEvent(event, true)) {
                        "UiAutomation 拒绝 ${MotionEvent.actionToString(event.action)}"
                    }
                }
                waitForIdleSync()
            } finally {
                events.forEach(MotionEvent::recycle)
            }

            check(presenter.cellWidth > initialWidth && presenter.cellHeight > initialHeight) {
                "系统注入已成功但 TermSurfaceView 未改变字格: " +
                    "${initialWidth}x$initialHeight -> ${presenter.cellWidth}x${presenter.cellHeight}"
            }
        } finally {
            runOnMainSync { activity.finish() }
        }
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
