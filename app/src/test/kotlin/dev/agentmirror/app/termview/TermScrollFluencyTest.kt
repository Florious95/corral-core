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

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import dev.agentmirror.terminal.TerminalEmulator
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 滚动跟手（指哪到哪）：抬手惯性甩动、按下即停不误点、远端节流尾沿补发、挤压视口不污染
 * warm 几何缓存。挂到真实窗口上跑——postOnAnimation/postDelayed 只在 attach 后才执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class TermScrollFluencyTest {

    /** 固定行高（Robolectric 字形度量是桩），远大于 touch slop。 */
    private val lineHeightPx = 40

    private lateinit var controller: ActivityController<Activity>
    private lateinit var parent: FrameLayout
    private lateinit var view: TermSurfaceView
    private val scrolls = mutableListOf<Int>()
    private val clicks = mutableListOf<Boolean>()
    private var flushes = 0

    @Before
    fun attach() {
        controller = Robolectric.buildActivity(Activity::class.java).create()
        parent = FrameLayout(controller.get())
        controller.get().setContentView(parent)
        val emulator = TerminalEmulator(cols = 20, rows = 50)
        emulator.feed((0 until 50).joinToString("\r\n") { "line$it" })
        view = TermSurfaceView(controller.get())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        parent.addView(view, FrameLayout.LayoutParams(300, 400))
        controller.start().resume().visible()
        idle(64)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        TermSurfaceView::class.java.getDeclaredField("lineHeightPx").apply {
            isAccessible = true
            setInt(view, lineHeightPx)
        }
        view.onRemoteScrollBy = { scrolls += it }
        view.onRemoteScrollFlush = { flushes++ }
        view.onTermMouse = { _, _, press, _, _, _, _ ->
            clicks += press
            true
        }
    }

    @After
    fun cleanup() {
        parent.removeAllViews()
        controller.pause().stop().destroy()
        context().getSharedPreferences("term_viewport_geom", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun touch(action: Int, tMs: Long, y: Float) {
        val e = MotionEvent.obtain(0L, tMs, action, 100f, y, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    /** 快速下甩：3 段 60px/10ms（≈6000px/s）后抬手。 */
    private fun flickDown() {
        touch(MotionEvent.ACTION_DOWN, 0L, 0f)
        touch(MotionEvent.ACTION_MOVE, 10L, 60f)
        touch(MotionEvent.ACTION_MOVE, 20L, 120f)
        touch(MotionEvent.ACTION_MOVE, 30L, 180f)
        touch(MotionEvent.ACTION_UP, 30L, 180f)
    }

    @Test
    fun flingAfterLiftKeepsScrollingSameDirectionThenStops() {
        flickDown()
        val dragged = scrolls.sum()
        assertTrue("拖动本身必须先跟手投送", dragged > 0)

        idle(5_000)

        assertTrue("抬手后惯性继续同向滚动：drag=$dragged total=${scrolls.sum()}", scrolls.sum() > dragged)
        assertTrue("惯性不得反向", scrolls.all { it > 0 })
        val settled = scrolls.size
        idle(1_000)
        assertEquals("惯性必须自然停下", settled, scrolls.size)
        assertTrue("甩动过程不产生点按", clicks.isEmpty())
    }

    @Test
    fun touchDuringFlingStopsItWithoutTerminalClick() {
        flickDown() // 抬手即起甩（首个动画帧尚未执行）
        val caught = scrolls.size
        assertTrue(scrolls.sum() > 0)

        touch(MotionEvent.ACTION_DOWN, 1_000L, 200f)
        touch(MotionEvent.ACTION_UP, 1_040L, 200f)
        idle(3_000)

        assertEquals("按下即停：不再有惯性投送", caught, scrolls.size)
        assertTrue("接住甩动的按下不得当点按上报", clicks.isEmpty())

        touch(MotionEvent.ACTION_DOWN, 5_000L, 200f)
        touch(MotionEvent.ACTION_UP, 5_040L, 200f)
        assertEquals("静止时点按照常上报 press+release", listOf(true, false), clicks)
    }

    @Test
    fun remoteScrollTailFlushesOneThrottleWindowAfterLastDispatch() {
        touch(MotionEvent.ACTION_DOWN, 0L, 0f)
        touch(MotionEvent.ACTION_MOVE, 100L, 45f) // 1 行
        assertEquals(listOf(1), scrolls)
        idle(30)
        touch(MotionEvent.ACTION_MOVE, 200L, 85f) // 再 1 行：尾沿重排
        idle(49)
        assertEquals("窗口未满不提前补发", 0, flushes)
        idle(2)
        assertEquals("停手一个节流窗口后补发一次尾量", 1, flushes)
        idle(500)
        assertEquals("无新位移不重复补发", 1, flushes)
    }

    @Test
    fun squeezedViewportNeverOverwritesWarmGeometry() {
        val emulator = TerminalEmulator(cols = 10, rows = 5)
        val presenter = TermViewPresenter(emulator) { rows, cols -> emulator.resize(cols, rows) }
        val v = TermSurfaceView(context())
        v.presenter = presenter
        v.layout(0, 0, 640, 480) // 首次真实视口：内核按排布重算并落盘
        val store = SharedPreferencesViewportGeomStore(context())
        val rest = store.load()!!
        assertEquals(emulator.rows, rest.rows)
        assertEquals(emulator.cols, rest.cols)
        val config = context().resources.configuration
        assertEquals("窗口 dp 必须随几何落盘（旋转/分屏命中判据）", config.screenWidthDp, rest.windowWidthDp)
        assertEquals(config.screenHeightDp, rest.windowHeightDp)
        assertEquals(termTextSizePx(v.fontSizeSp, context().resources), rest.textSizePx)

        v.layout(0, 0, 640, 240) // IME/dock 挤压：同宽、行数变少
        persist(v) // 运动静默后的 settle 落盘
        assertTrue(presenter.viewportSqueezed)
        assertEquals("挤压值不得覆盖真实排布", rest, store.load())

        v.layout(0, 0, 640, 480)
        persist(v)
        assertEquals(rest, store.load())
    }

    private fun persist(v: TermSurfaceView) {
        TermSurfaceView::class.java.getDeclaredMethod("persistViewportGeom").apply {
            isAccessible = true
            invoke(v)
        }
    }
}
