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

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 红测（feat-remote-scroll-mouse-wheel，app 侧丢帧修复）。
 *
 * 用户实测上滑无效，归档定位：GestureDetector 每次 onScroll 的 dy 是"距上次回调的增量"，
 * 触屏高频采样下单次增量常常不足一整行；旧实现 `(-dy/lineHeightPx).roundToInt()` 判 0
 * 就直接丢、不留余数——多次不足一行的位移永远凑不成一行，慢速/阅读式拖动的位移绝大部分
 * 在 [TermSurfaceView] 这一层就消失了（不会到达 SessionViewModel 的节流累加器：那层管
 * 发送节奏，累加的是已取整的行数，管不到这里丢失的亚行像素）。
 *
 * 判据是守恒式的，不是存在式的："连续 N 次亚行位移，累计像素达到 M 行时，发出的行数
 * 总和必须 = M"——这是本文件唯一断言的核心不变量，天然有判别力：旧实现（无累加器）下
 * 每次 onScroll 的单步 dy 都小于一行，deltaLines 每次都是 0、直接 return，
 * remoteScroll 全程不会被调用一次，总和恒为 0 ≠ M，断言必然红；只有把亚行像素累加起来
 * 才能让总和收敛到 M。
 *
 * **环境限制记录（过程发现，已用反射规避）**：Robolectric legacy graphics 的
 * Paint.fontMetrics 恒返回 0（同 TermFontSizeSettingDropPinchTest 类文档记录的现象），
 * View 的 [TermSurfaceView] 私有字段 `lineHeightPx` 因此被 `max(1, …)` 钉死在 1px——
 * 这个人为的极小值本身也会撞上 `android.view.GestureDetector` 的真实机制：DOWN 后第一次
 * MOVE 若未超出 touch slop（~几 px，ViewConfiguration 默认值），detector 把它算进"点按
 * 容差区"、完全不触发 onScroll（这是真机也有的行为，不是本文件的假象）。本文件反射直接
 * 把 lineHeightPx 钉到一个测试可控的常量，脱离字体测量环境限制，同时选取远大于 slop 的
 * 步长，避免 slop 吞掉首次位移导致误判"累加器没生效"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermScrollSubLineAccumulatorTest {

    /** 测试固定行高（px）——远大于 touch slop，避免 slop 吞掉首次位移干扰断言。 */
    private val lineHeightPx = 40

    private fun buildView(): TermSurfaceView {
        val emulator = TerminalEmulator(cols = 10, rows = 50)
        emulator.feed((0 until 50).joinToString("\r\n") { "line$it" })
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            this.presenter = presenter
            layout(0, 0, 200, 400)
        }
        // 反射钉死 lineHeightPx：见类文档"环境限制记录"——不依赖 Robolectric 的字体测量 stub。
        val f = TermSurfaceView::class.java.getDeclaredField("lineHeightPx")
        f.isAccessible = true
        f.setInt(view, lineHeightPx)
        return view
    }

    // 不在两次 onTouchEvent 之间 recycle()：GestureDetector 内部持有前一个 MotionEvent
    // 的引用（用于算 distanceY/速度），过早 recycle 会让对象被复用池回收、内部字段失真——
    // 与 TermGestureDirectionTest 一致，整段手势结束后统一在调用方一次性 recycle。
    private fun sendDown(view: TermSurfaceView, y: Float): MotionEvent {
        val e = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, y, 0)
        view.onTouchEvent(e)
        return e
    }

    private fun sendMove(view: TermSurfaceView, tMs: Long, y: Float): MotionEvent {
        val e = MotionEvent.obtain(0L, tMs, MotionEvent.ACTION_MOVE, 100f, y, 0)
        view.onTouchEvent(e)
        return e
    }

    @Test
    fun manySubLineDrags_accumulateToExactLineCount() {
        val view = buildView()
        val received = mutableListOf<Int>()
        view.onRemoteScrollBy = { deltaLines -> received += deltaLines }

        // 步长必须**不能整除行高**（leader 复核指出的检测力缺口）：原来用半行 20px（行高
        // 40）时，每次凑够一行发出后余数恰好是 0——这时"发出后扣减已发出部分"和"发出后
        // 整个清零"两种实现完全等价，变异（清零替代扣减）测不出来，守恒断言在这个步长下
        // 没有分辨力。改用 30px（不能整除 40）：扣减实现下余数会在 0/20/10 之间滚动累积，
        // 清零实现会在每次发出后把这部分非零余数也吃掉，两者的总和会分道扬镳。
        val stepPx = 30f
        val steps = 8
        var y = 0f
        val events = mutableListOf(sendDown(view, y))
        for (i in 1..steps) {
            y += stepPx
            events += sendMove(view, i * 16L, y)
        }
        events.forEach { it.recycle() }

        val totalLines = received.sum()
        val expectedLines = (steps * stepPx / lineHeightPx.toFloat()).toInt() // 8×30/40 = 6

        assertEquals(
            "[守恒] $steps 次亚行位移（每次 30px < 一行 40px，且步长不能整除行高——分辨力" +
                "关键，见上方注释）累计后发出的行数总和必须等于按总像素折算的整行数——" +
                "收到的调用序列=$received，说明亚行像素在 View 层被丢弃而非累加",
            expectedLines, totalLines,
        )
        assertTrue("[不倒退] 亚行位移必须真正触发过 remoteScroll（不是一次都没调）", received.isNotEmpty())
    }

    @Test
    fun newGestureAfterPriorGesture_doesNotCarryOverStaleRemainder() {
        val view = buildView()
        val received = mutableListOf<Int>()
        view.onRemoteScrollBy = { deltaLines -> received += deltaLines }

        // 第一次手势：单步位移 38px（lineHeightPx=40 的 95%，足够清 slop——GestureDetector
        // 的"点按容差区"判据用的是距 DOWN 点的累计距离，实测阈值约为 2×scaledTouchSlop，
        // 单步必须足够大才能在一次 move 内清掉，见类文档"环境限制记录"）。38px 不足一行，
        // deltaLines=0，onScroll 内部 return 前不调用 remoteScroll——这一步不发任何调用，
        // 但把 38px 的余数留在了 TermSurfaceView.pendingScrollPx 里。
        val e1 = sendDown(view, 0f)
        val e2 = sendMove(view, 16L, 38f)

        // 第二次手势（新的 onDown）：单步位移 35px（同样清 slop）。
        //   - 若余数被错误带入（bug 复现）：35 + 上一手势残留的 38 = 73px，73/40=1 整行，
        //     会触发一次 remoteScroll(1)。
        //   - 若 onDown 正确清零残留（当前实现）：这一步单独只有 35px，仍不足一行，
        //     deltaLines=0，不触发任何调用。
        // 两种结果都有实际观测量（不是"存在式断言"），本测试真正具有判别力。
        val e3 = sendDown(view, 100f)
        val e4 = sendMove(view, 200L, 100f + 35f)
        listOf(e1, e2, e3, e4).forEach { it.recycle() }

        assertTrue(
            "[边界] 新手势的 onDown 必须清空上一次手势残留的亚行余数，否则第二次手势的 35px" +
                "会和上一次残留的 38px 拼成一整行提前发出：received=$received（预期为空）",
            received.isEmpty(),
        )
    }

    /**
     * 不倒退：单次超过一行的位移行为不变（一次到位，不依赖多帧累加）。
     */
    @Test
    fun singleLargeDragStillReportsCorrectLineCount() {
        val view = buildView()
        val received = mutableListOf<Int>()
        view.onRemoteScrollBy = { deltaLines -> received += deltaLines }

        val e1 = sendDown(view, 20f)
        val e2 = sendMove(view, 100L, 20f + lineHeightPx * 5f) // 一步位移 5 行
        e1.recycle()
        e2.recycle()

        assertEquals("[不倒退] 单次超过一行的位移应一次性发出对应行数", listOf(5), received)
    }
}
