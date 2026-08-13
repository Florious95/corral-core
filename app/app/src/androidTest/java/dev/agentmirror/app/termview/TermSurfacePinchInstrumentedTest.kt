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
import android.view.ViewGroup
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
                // TermSurfaceView 无 onMeasure 覆写：View 默认测量对 WRAP_CONTENT
                // 落到 suggestedMinimumSize（常为 0）。setContentView(view) 若不显式给
                // MATCH_PARENT×MATCH_PARENT，视图会以 0x0 完成布局——手势span/坐标计算
                // 在退化尺寸上毫无意义，这是此前"注入回true但字格纹丝不动"的根因猜想，
                // 本次显式撑满修正。
                activity.setContentView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
            waitForIdleSync()

            val location = IntArray(2)
            runOnMainSync { view.getLocationOnScreen(location) }
            check(view.width > 0 && view.height > 0) {
                "TermSurfaceView 布局后尺寸退化: ${view.width}x${view.height}（MATCH_PARENT 未生效）"
            }
            val centerX = location[0] + view.width / 2f
            val centerY = location[1] + view.height / 2f
            var dispatchedTouchCount = 0
            runOnMainSync {
                view.setOnTouchListener { _, _ -> dispatchedTouchCount++; false }
            }
            val initialWidth = presenter.cellWidth
            val initialHeight = presenter.cellHeight

            // 每个事件用注入时刻的真实 SystemClock 打时间戳（而不是预先算好的固定偏移），
            // 且相邻事件间真实 sleep 一小段——诊断假说：预先算好一串仅相差 10ms 的
            // eventTime、却在紧邻的 for 循环里瞬间（<1ms）连续注入，事件到达时已经是
            // "未来"时间戳，可能被判定陈旧/被系统丢弃或合并，ScaleGestureDetector 因此
            // 从未见到跨越多帧的 span 变化。
            val downTime = SystemClock.uptimeMillis()
            var lastEventTime = downTime
            fun inject(action: Int, vararg points: Pair<Float, Float>) {
                lastEventTime = SystemClock.uptimeMillis()
                val event = event(downTime, lastEventTime, action, *points)
                try {
                    check(uiAutomation.injectInputEvent(event, true)) {
                        "UiAutomation 拒绝 ${MotionEvent.actionToString(action)}"
                    }
                } finally {
                    event.recycle()
                }
                SystemClock.sleep(16) // 一帧的量级，让手势看起来像真实产生的
            }

            val p0 = centerX - 40f to centerY
            val p1 = centerX + 40f to centerY
            inject(MotionEvent.ACTION_DOWN, p0)
            inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), p0, p1)
            inject(MotionEvent.ACTION_MOVE, centerX - 80f to centerY, centerX + 80f to centerY)
            inject(MotionEvent.ACTION_MOVE, centerX - 140f to centerY, centerX + 140f to centerY)
            inject(MotionEvent.ACTION_MOVE, centerX - 200f to centerY, centerX + 200f to centerY)
            inject(
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                centerX - 200f to centerY,
                centerX + 200f to centerY,
            )
            inject(MotionEvent.ACTION_UP, centerX - 200f to centerY)
            waitForIdleSync()

            check(dispatchedTouchCount > 0) {
                "TermSurfaceView.setOnTouchListener 从未被调用——事件根本没有分发到这个 View " +
                    "（View 可能未获得窗口焦点/未在命中测试路径上），不是手势判定问题"
            }
            check(presenter.cellWidth > initialWidth && presenter.cellHeight > initialHeight) {
                "系统注入已成功且事件确实到达 View（收到 $dispatchedTouchCount 次 onTouch），" +
                    "但 TermSurfaceView 未改变字格: " +
                    "${initialWidth}x$initialHeight -> ${presenter.cellWidth}x${presenter.cellHeight}"
            }
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

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
