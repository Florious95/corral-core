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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.agentmirror.terminal.Cell
import dev.agentmirror.terminal.TerminalColor
import dev.agentmirror.terminal.TerminalEmulator
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 终端画布视图：Canvas 逐格绘制 + 拖动/捏合手势 + Choreographer 帧调度（薄层，业务状态全在 [TermViewPresenter]）。
 *
 * 每帧只重绘 presenter 给出的脏逻辑行区间（60fps 工作量 = 脏行数而非全屏，006）；
 * 拖动滚动只改本地视口零网络，捏合字号经 presenter 换算 rows/cols 后由上层发协议 resize 帧（005）。
 * 绘制全部逻辑收敛在本类（等宽字体测量/同色 run 合并/宽字符/BCE 背景），供 Presenter 单测隔离。
 */
class TermSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 视口状态机；由上层注入（与内核同构，渲染/手势全部委托给它）。 */
    var presenter: TermViewPresenter? = null
        set(value) {
            field = value
            if (value != null) postFrame()
        }

    /** 像素高度对应一逻辑行的行高（视口向下滚动超过一行时对齐整格）。 */
    private var lineHeightPx: Int = 0

    private var backToBottomLabel: String? = null

    // ---- 绘制工具 ----
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 44f
        color = Color.WHITE
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var cellW: Int = 0
    private var cellH: Int = 0

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            presenter?.let {
                // 向上滚 = dy<0：content 更向下，历史在更上，deltaLines>0。
                val deltaLines = ((dy) / lineHeightPx.toFloat()).roundToInt()
                if (deltaLines != 0) it.onScrollBy(deltaLines)
            }
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            presenter?.let {
                val factor = detector.scaleFactor
                val newW = max(MIN_CELL_PX, (it.cellWidth * factor).roundToInt())
                val newH = max(MIN_CELL_PX, (it.cellHeight * factor).roundToInt())
                it.onFontSizeChanged(newW, newH)
            }
            return true
        }
    })

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePending = false
            val p = presenter ?: return
            val allDamage = buildList {
                while (true) {
                    val more = p.takeDamage()
                    if (more.isEmpty()) break
                    addAll(more)
                }
            }
            p.beginFrame()
            invalidate()
            if (allDamage.isNotEmpty() || p.showBackToBottom) postFrame()
        }
    }

    /** 帧是否已排入 Choreographer（防重复排队；doFrame 时复位）。 */
    private var framePending = false

    /** 请求一帧：脏数据或状态变化驱动（Choreographer 垂直同步对齐；重复请求被合并为一帧）。 */
    private fun postFrame() {
        if (framePending) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        presenter?.onViewportSizeChanged(w, h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        presenter ?: return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (!handled) super.onTouchEvent(event)
        return true
    }

    /** 每帧：清屏、铺脏行背景、按同色 run 合并画前景。 */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = presenter ?: return
        // 清屏为终端默认背景（BCE：空白格也带背景色，必须整帧铺底色）。
        bgPaint.color = themeBgArgb()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        measureCells()
        canvas.translate(0f, -lineHeightPx.toFloat())
        val win = p.window
        for (logical in win) {
            val rowY = (logical - win.first) * cellH
            drawLine(canvas, p.lineCells(logical), rowY)
        }

        // 回到底部悬浮按钮（锁定历史时显示）。
        backToBottomLabel?.let { label ->
            if (p.showBackToBottom) {
                labelBgPaint.color = Color.argb(200, 30, 30, 30)
                val tw = labelPaint.measureText(label)
                val margin = dp(12f)
                val pad = dp(10f)
                val x = width - margin - tw - pad * 2
                val y = height - margin - dp(40f)
                canvas.translate(0f, lineHeightPx.toFloat())
                canvas.drawRoundRect(x, y, x + tw + pad * 2, y + dp(40f), dp(8f), dp(8f), labelBgPaint)
                canvas.drawText(label, x + pad, y + dp(28f), labelPaint)
            }
        }
    }

    // ---- 逐行绘制 ----

    /**
     * 画一行逻辑行到 [rowY]：第一遍按格铺背景（BCE：空白格也带背景色），
     * 第二遍把连续同前景色格合并成一次 drawText（性能关键：draw 调用数 = run 数而非格数）。
     */
    private fun drawLine(canvas: Canvas, cells: List<Cell>, rowY: Int) {
        var x = 0
        for (cell in cells) {
            if (cell.width == 0) {
                x += cellW
                continue
            }
            bgPaint.color = colorFor(cell.style.bg, background = true)
            canvas.drawRect(x.toFloat(), rowY.toFloat(), (x + cellW).toFloat(), (rowY + cellH).toFloat(), bgPaint)
            x += if (cell.width == 2) cellW * 2 else cellW
        }
        drawTextRuns(canvas, cells, rowY)
    }

    /** 扫描 [cells] 的同色连续段（run）：段内一次 drawText，draw x 由格宽累计。 */
    private fun drawTextRuns(canvas: Canvas, cells: List<Cell>, rowY: Int) {
        var runStartPx = 0
        var x = 0
        var runStyle: TerminalColor? = null
        val sb = StringBuilder()
        for (cell in cells) {
            if (cell.width == 0) {
                x += cellW
                continue
            }
            // 同色段延续：append；颜色切换：flush 上一段再开新段。
            if (runStyle != cell.style.fg && sb.isNotEmpty()) {
                fgPaint.color = colorFor(runStyle ?: TerminalColor.Default, background = false)
                canvas.drawText(sb.toString(), runStartPx.toFloat(), rowY.toFloat(), fgPaint)
                sb.clear()
                runStartPx = x
            } else if (sb.isEmpty()) {
                runStartPx = x
            }
            runStyle = cell.style.fg
            sb.append(cell.text)
            x += if (cell.width == 2) cellW * 2 else cellW
        }
        if (sb.isNotEmpty()) {
            fgPaint.color = colorFor(runStyle ?: TerminalColor.Default, background = false)
            canvas.drawText(sb.toString(), runStartPx.toFloat(), rowY.toFloat(), fgPaint)
        }
    }

    // ---- 测量与配色 ----

    private fun measureCells() {
        val p = presenter ?: return
        fgPaint.textSize = p.cellHeight * 0.85f
        val metrics = fgPaint.fontMetrics
        cellH = (metrics.descent - metrics.ascent).roundToInt()
        val textW = fgPaint.measureText("W")
        cellW = max(1, textW.roundToInt())
        lineHeightPx = p.cellHeight
    }

    /** 终端色（Indexed/真彩/默认）→ Android ARGB 色值。 */
    private fun colorFor(color: TerminalColor, background: Boolean): Int = when (color) {
        TerminalColor.Default -> if (background) themeBgArgb() else themeFgArgb()
        is TerminalColor.Rgb -> Color.rgb(color.r, color.g, color.b)
        is TerminalColor.Indexed -> ANSI_COLORS[color.index.coerceIn(0, 15)] ?: fallbackIndexed(color.index)
    }

    private fun fallbackIndexed(index: Int): Int = when (index / 8) {
        0 -> ANSI_COLORS[(index % 8) + 8] ?: Color.GRAY
        else -> Color.GRAY
    }

    private fun themeBgArgb(): Int = DEFAULT_BG
    private fun themeFgArgb(): Int = DEFAULT_FG

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MIN_CELL_PX = 4
        /** 终端默认前景/背景色（主题定制任务替换处）。 */
        const val DEFAULT_FG = 0xFFE8E8E8.toInt()
        const val DEFAULT_BG = 0xFF0D1626.toInt()
        /** 终端基础 16 色调色板（XTerm 近似，主题定制任务可替换）。 */
        val ANSI_COLORS: Map<Int, Int> = mapOf(
            0 to Color.rgb(0, 0, 0),
            1 to Color.rgb(205, 49, 49),
            2 to Color.rgb(13, 188, 121),
            3 to Color.rgb(229, 229, 16),
            4 to Color.rgb(36, 114, 200),
            5 to Color.rgb(188, 63, 188),
            6 to Color.rgb(17, 168, 205),
            7 to Color.rgb(229, 229, 229),
            8 to Color.rgb(102, 102, 102),
            9 to Color.rgb(241, 76, 76),
            10 to Color.rgb(35, 209, 139),
            11 to Color.rgb(245, 245, 67),
            12 to Color.rgb(59, 142, 234),
            13 to Color.rgb(214, 112, 214),
            14 to Color.rgb(41, 184, 219),
            15 to Color.rgb(229, 229, 229),
        )
    }
}
