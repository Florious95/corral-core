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
import android.view.View
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.terminal.Cell
import dev.agentmirror.terminal.CharWidth
import dev.agentmirror.terminal.TerminalColor
import dev.agentmirror.terminal.TerminalEmulator
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 终端画布视图：Canvas 逐格绘制 + 拖动手势 + Choreographer 帧调度（薄层，业务状态全在 [TermViewPresenter]）。
 *
 * 帧循环纯数据驱动：presenter 的脏区只作"画面已变化"的触发信号（[TermViewPresenter.takeDamage]
 * 排空即弃，防缓冲无界增长），之后整帧重绘可见窗口全部行（每帧工作量 = 窗口行数，非脏行数，006）；
 * 拖动滚动只改本地视口零网络。字号（[fontSizeSp]，Settings 持久化，取代原 005 捏合）经
 * [applyFontMetrics] 实测换算 cellWidth/cellHeight 后写回 presenter，视口建立时presenter
 * 据此算 rows/cols 并由上层发协议 resize 帧。绘制全部逻辑收敛在本类（等宽字体测量/同色 run
 * 合并/宽字符/BCE 背景），供 Presenter 单测隔离。
 */
class TermSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 视口状态机；由上层注入（与内核同构，渲染/手势全部委托给它）。
     *  注入即接管其帧请求回调（缺陷①：增量流/滚动变化经 presenter 唤醒本 View），并立即
     *  用当前 [fontSizeSp] 实测一次字格尺寸（[applyFontMetrics]，供本 View 自己绘制用）。
     *  已 seed 过的 presenter（如测试直接注入实测值）不会被覆盖——见 [applyFontMetrics]
     *  内部对 [TermViewPresenter.cellMetricsSeeded] 的判断：显式 seed 优先于 View 默认字号。 */
    var presenter: TermViewPresenter? = null
        set(value) {
            field?.onFrameRequested = null // 换 presenter 时摘旧钩，避免旧实例继续唤醒
            field = value
            if (value != null) {
                value.onFrameRequested = { requestFrameFromAnyThread() }
                applyFontMetrics()
                postFrame()
            }
        }

    /**
     * 用户设置字号（sp，Settings 持久化——问题③：捏合后大小未延续；见
     * [dev.agentmirror.app.termview.SharedPreferencesFontSizeStore]）。唯一决定单元格像素
     * 尺寸的独立输入（契约①④），设置即触发 [applyFontMetrics] 重新实测。
     */
    var fontSizeSp: Float = SharedPreferencesFontSizeStore.DEFAULT_FONT_SIZE_SP.toFloat()
        set(value) {
            field = value
            applyFontMetrics()
        }

    /**
     * 远端滚动回调（缺陷④）：由会话层（SessionViewModel）注入，将手势档位数投送到远端 pane。
     * 注入后 onScroll 优先走此路径；null 则退回 presenter.onScrollBy（本地缓冲降级）。
     *
     * 注意：SessionScreen 在 AndroidView update lambda 里永远设此回调（只要会话页存活），
     * 因此 null 分支在正常会话中不会触达——降级逻辑（READY 判断）实际由 SessionViewModel
     * 内部负责，View 层不感知连接状态。null 分支保留是为了在测试/预览中允许不注入 VM。
     */
    var onRemoteScrollBy: ((deltaLines: Int) -> Unit)? = null

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

    /** 字形回退提供者：三槽位 Paint + 槽位判定 + 分段器（单例，首帧懒建）。 */
    private var glyphProvider: GlyphFontProvider? = null

    private fun glyphs(): GlyphFontProvider =
        glyphProvider ?: GlyphFontProvider(context).also { glyphProvider = it }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var cellW: Int = 0
    private var cellH: Int = 0

    /**
     * 护栏金丝雀（X3，fix-cols-grid-convergence）：网格内容超出画布右缘时，背景矩形与
     * 字形被收边钳进画布的**次数**。
     *
     * ⚠️ 金丝雀语义（leader 2026-08-14 裁定）：**X3 一旦在正常路径上 engage，就意味着实测度量失效**。
     * 计数恒为 0 = [applyFontMetrics] 的实测写回（[TermViewPresenter.seedCellMetrics]）在干活，
     * X3 是纯保险；
     * 计数开始涨 = **有路径绕过了回写**，那是要查的 bug，不是护栏起作用了，很好。
     * 护栏经常救场恰恰说明主修复漏了——不许读成「护栏很有用」。
     * 测试可经 [clipGuardEngageCount] 断言正常路径恒 0。
     */
    private var clipGuardEngageCount: Int = 0

    /** 护栏 engage 计数（金丝雀可观测出口；测试断言正常路径恒 0）。 */
    fun clipGuardEngageCount(): Int = clipGuardEngageCount

    /** 行内文本基线相对行顶的偏移（= -ascent）。drawText 的 y 是基线、字形画在基线
     *  上方，直接用行顶 y 画会把整行字形抬出行带（首行即被裁出画布顶，fix-term-residuals）。 */
    private var baselinePx: Float = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // GestureDetector 的 distanceY 是”上一点 - 当前点”：手指下拖为负；
            // Presenter 的正值才是看更早历史，因此必须反号以保持内容跟手移动。
            val deltaLines = (-dy / lineHeightPx.toFloat()).roundToInt()
            if (deltaLines == 0) return true
            val remoteScroll = onRemoteScrollBy
            if (remoteScroll != null) {
                remoteScroll(deltaLines) // 远端路径；降级判断由 SessionViewModel 负责
            } else {
                presenter?.onScrollBy(deltaLines) // 本地缓冲路径（测试/预览，无 VM 注入时）
            }
            return true
        }
    })

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePending = false
            val p = presenter ?: return
            // 排空脏区缓冲（防无界增长）后整帧重绘。不再自续下一帧：帧循环是纯数据
            // 驱动的（presenter.onFrameRequested 唤醒），空闲即零帧（静默经济红线；
            // 旧版 showBackToBottom 自续 = 锁定历史时 60fps 空转，本案顺带拆除）。
            while (p.takeDamage().isNotEmpty()) Unit
            p.beginFrame()
            invalidate()
        }
    }

    /** 帧是否已排入 Choreographer（防重复排队；doFrame 时复位；仅主线程触碰）。 */
    private var framePending = false

    /** 请求一帧：脏数据或状态变化驱动（Choreographer 垂直同步对齐；重复请求被合并为一帧）。 */
    private fun postFrame() {
        if (framePending) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // ---- 跨线程帧唤醒（缺陷①：WS 收件线程 feed 后必须能唤醒主线程帧循环）----

    /** 主线程 Handler：非主线程的帧请求经此跳线程（Choreographer 是 thread-local 的）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 已有跨线程唤醒在途（合并突发增量的重复唤醒，稳态零新增分配）。 */
    private val wakeQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 缓存的唤醒任务（避免每次增量到达都分配 Runnable——热路径纪律）。 */
    private val wakeRunnable = Runnable {
        wakeQueued.set(false)
        postFrame()
    }

    /**
     * 任意线程请求帧：主线程直达 [postFrame]；其他线程（WS 收件线程）经 [mainHandler]
     * 跳到主线程，[wakeQueued] 保证同一时刻至多一个在途唤醒（背靠背增量合并为一帧）。
     */
    private fun requestFrameFromAnyThread() {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            postFrame()
            return
        }
        if (wakeQueued.compareAndSet(false, true)) {
            mainHandler.post(wakeRunnable)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        presenter?.onViewportSizeChanged(w, h)
    }

    /**
     * D-38（回炉）：窗口从后台回前台时尺寸可能与离开前相同，系统不会因此再回调 [onSizeChanged]
     * ——而后台期间几何可能已过时（IME 收起后 emulator.rows 未更新）。VISIBLE 时主动重放当前
     * viewport（真实视口事件，见 [TermViewPresenter.onRealViewportChanged]），清掉旧的挤压小值。
     *
     * 不带任何 IME/insets 状态推断（v3 黑屏闪死因：在「IME 弹出」路径上加了状态分支；这里只
     * 响应 VISIBLE 事件、把「是否重算」的判断交给 presenter 的 [TermViewPresenter.viewportOutgrewEmulator]——
     * 挤压值永远不会 shrink 终端，IME 在屏回前台时保持挤压显示，不发 resize）。可见性事件上
     * 没有尺寸变化时不出意外调用 onSizeChanged，故本 override 独立于它。
     *
     * @contract
     * @pre visibility 是 Android 窗口可见性值；可在 presenter 未注入 / 尺寸未就绪时调用
     * @post 非 VISIBLE 时撤销待执行帧并复位 framePending；VISIBLE 且尺寸为正时向 presenter
     *       重放当前 viewport（真实视口事件）并请求整帧
     * @err none
     * @inv 不可见期间 framePending=false；恢复只复用当前 width/height，不猜测历史尺寸
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // 仪表（leader 2026-08-14 补充裁定）：一进来就记，不等后续分支——「有没有被调用」
        // 是排查 Activity ON_STOP/ON_START 与本回调是否对得上的第一手证据（窗口级可见性
        // 与 Activity 生命周期不是一回事，长后台被系统回收 Surface 时尤其可能对不上）。
        DiagLog.record(
            "viewport",
            "source=windowVisibility visibility=$visibility width=$width height=$height",
        )
        if (visibility != VISIBLE) {
            if (framePending) {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                framePending = false
            }
            return
        }
        if (width <= 0 || height <= 0) return
        presenter?.onRealViewportChanged(width, height)
        postFrame()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        presenter ?: return super.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (!handled) super.onTouchEvent(event)
        return true
    }

    /** 每帧：清屏、铺可见窗口全部行背景、按同色 run 合并画前景。 */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = presenter ?: return
        // 清屏为终端默认背景（BCE：空白格也带背景色，必须整帧铺底色）。
        bgPaint.color = themeBgArgb()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val win = p.window
        for (logical in win) {
            val rowY = (logical - win.first) * cellH
            drawLine(canvas, p.lineCells(logical), rowY)
        }

        // 视图内"回到底部"悬浮钮为历史遗留死代码：backToBottomLabel 全仓库无赋值点，本块永不走；
        // 实际回到底部入口是 session 层 Compose 悬浮钮（读 `SessionViewModel.showBackToBottom`）。
        backToBottomLabel?.let { label ->
            if (p.showBackToBottom) {
                labelBgPaint.color = Color.argb(200, 30, 30, 30)
                val tw = labelPaint.measureText(label)
                val margin = dp(12f)
                val pad = dp(10f)
                val x = width - margin - tw - pad * 2
                val y = height - margin - dp(40f)
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
        // 网格是「每列一条目」：宽字符 = width=2 主格 + width=0 续格两个条目，x 恒按
        // 一列推进；主格矩形铺满 width 列（含续格列），续格只占位不重画。旧实现把主格
        // 当 2 列推进、续格又推 1 列且只铺 1 列宽矩形——背景色块内每个 CJK 留 2 列
        // 默认深底黑洞、后续格整体右漂（用户真机实拍黑块马赛克根因，fix-term-bg-cjk）。
        var x = 0
        // 右缘护栏（X3）：网格超宽时背景矩形右缘会越过画布被 Canvas 裁。钳进画布宽 + 金丝雀计数。
        // width > 0 guard：TermBgCjkAlignTest 走 view.draw 无 layout（width=0）时护栏必须失效
        // （否则空画布下每个矩形都越界，计数误报、测试错乱）。只有真实视口（width>0）才兜底。
        val guardActive = width > 0
        for (cell in cells) {
            if (cell.width == 0) {
                x += cellW
                continue
            }
            bgPaint.color = colorFor(cell.style.bg, background = true)
            val right = x + cellW * cell.width
            val clipped = if (guardActive && right > width) {
                clipGuardEngageCount++
                width.toFloat()
            } else {
                right.toFloat()
            }
            canvas.drawRect(x.toFloat(), rowY.toFloat(), clipped, (rowY + cellH).toFloat(), bgPaint)
            x += cellW
        }
        drawTextRuns(canvas, cells, rowY)
    }

    /**
     * 扫描 [cells] 的同色连续段（run）：段内按字形槽位再切成子段绘制。
     *
     * 颜色段拼接成文本（格列按 cell.width 推进，宽字符占两格、续格占一位），
     * 交给 [GlyphRunBuilder] 按槽位分段：
     * - MONO 段 batch 一次 drawText（ASCII 原生等宽，栅格不破坏，draw 数 = 段数）；
     * - SYSTEM_FALLBACK / POWERLINE 段逐码点按格居中画（fallback advance ≠ 格宽，
     *   实证 ⠋=22px vs 格 19px，整段连画会漂移，见记忆 term-glyph-fallback-empirics）。
     */
    private fun drawTextRuns(canvas: Canvas, cells: List<Cell>, rowY: Int) {
        var runStartCol = 0
        var col = 0
        var runStyle: TerminalColor? = null
        val sb = StringBuilder()
        for (cell in cells) {
            if (cell.width == 0) {
                // 宽字符续格：主格已按 width=2 计列，这里不再推进——否则每个 CJK 多漂
                // 1 列，换色 run 的起始列随之右漂（背景色块内文字错位重叠根因）。
                continue
            }
            // 同色段延续：append；颜色切换：flush 上一段再开新段。
            if (runStyle != cell.style.fg && sb.isNotEmpty()) {
                drawGlyphRuns(canvas, sb.toString(), runStartCol, rowY, runStyle ?: TerminalColor.Default)
                sb.clear()
                runStartCol = col
            } else if (sb.isEmpty()) {
                runStartCol = col
            }
            runStyle = cell.style.fg
            sb.append(cell.text)
            col += cell.width
        }
        if (sb.isNotEmpty()) {
            drawGlyphRuns(canvas, sb.toString(), runStartCol, rowY, runStyle ?: TerminalColor.Default)
        }
    }

    /** 画一个颜色段：按字形槽位切成子段后分槽绘制。 */
    private fun drawGlyphRuns(canvas: Canvas, text: String, startCol: Int, rowY: Int, fg: TerminalColor) {
        val g = glyphs()
        val color = colorFor(fg, background = false)
        for (seg in g.runBuilder.build(text, startCol)) {
            when (seg.slot) {
                GlyphSlot.MONO -> {
                    // 等宽原生段：batch 一次 drawText（基线 = 行顶 + baselinePx，字形恰落行带内）。
                    // 字形右缘护栏（X3）：段首列越界（X2 失效/异常回归，正常路径恒 0）时钳进画布，
                    // 否则末列字形被 Canvas 裁半（用户「『它』的一半」正是字形被裁）。width>0 guard 同背景。
                    // 段占列宽按码点 CharWidth 累计（宽字符主格 2 列但 text 仅 1 字符，length 会低估）。
                    fgPaint.color = color
                    if (width > 0 && seg.startCol * cellW >= width) {
                        clipGuardEngageCount++
                        canvas.drawText(seg.text, (width - cellW).coerceAtLeast(0).toFloat(), rowY + baselinePx, fgPaint)
                    } else {
                        canvas.drawText(seg.text, seg.startCol * cellW.toFloat(), rowY + baselinePx, fgPaint)
                    }
                }
                GlyphSlot.SYSTEM_FALLBACK -> {
                    g.systemPaint.color = color
                    drawCentered(canvas, g.systemPaint, seg.text, seg.startCol, rowY)
                }
                GlyphSlot.POWERLINE -> {
                    g.powerlinePaint.color = color
                    drawCentered(canvas, g.powerlinePaint, seg.text, seg.startCol, rowY)
                }
                // GlyphRunBuilder 必须在输出段前把内部信号改写成 MONO+'?'；到达这里即违约。
                GlyphSlot.VISIBLE_FALLBACK -> error("unresolved visible glyph fallback")
            }
        }
    }

    /**
     * 逐格把 [text] 按格居中画（fallback 字形 advance ≠ 格宽，必须逐格定位才能保持
     * 等宽栅格）。宽字符（CJK/emoji）按 [CharWidth] 占两格居中；紧随主字符的组合/零宽
     * 码点并入同格一起画（组合字形必须整体渲染，如"你"+尖音符）。基线沿用 rowY（与
     * batch ASCII 同基线，纵向对齐）。
     */
    private fun drawCentered(canvas: Canvas, paint: Paint, text: String, startCol: Int, rowY: Int) {
        var x = startCol * cellW
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            val width = CharWidth.of(cp)
            val chars = Character.charCount(cp)
            if (width == 0) {
                // 段首孤立零宽（构建器已把组合码点并入主字符段，理论边界）：跳过不画。
                i += chars
                continue
            }
            // 本格主字符 + 紧随的组合/零宽码点（同段内），一次性画（组合字形整体）。
            // 用 (text, start, end) 区间重载测量/绘制，热路径零分配（不切子串）。
            var j = i + chars
            while (j < n) {
                val nc = text.codePointAt(j)
                if (CharWidth.of(nc) != 0) break
                j += Character.charCount(nc)
            }
            val cellPx = cellW * width
            val actual = paint.measureText(text, i, j)
            // 格内水平居中：字形实际宽度小于格宽时居中，大于则轻微左出（不破栅格）。
            // 纵向与 batch ASCII 同基线（行顶 + baselinePx）。
            canvas.drawText(text, i, j, x + (cellPx - actual) / 2f, rowY + baselinePx, paint)
            x += cellPx
            i = j
        }
    }

    // ---- 测量与配色 ----

    /**
     * 字号 → 单元格像素尺寸的唯一计算点（feat-font-size-setting-drop-pinch 契约①②④）：
     * [fontSizeSp] 直接换算 textSize（不再由 cellHeight 反推——旧 cellH→textSize→cellH
     * 反馈环随捏合一起拆除），再用 measureText/fontMetrics 实测 cellW/cellH。在 presenter
     * 注入或字号变化时调用，早于首次 [onSizeChanged]——不再是每帧重复测量+回写收敛
     * （旧 measureCells 每帧执行的「先播种后回写」模式已消失）。
     *
     * 本 View 的绘制字段（[cellW]/[cellH]/[baselinePx]/[lineHeightPx]）无条件更新——绘制
     * 只认自己实测的字号。但只在 presenter **尚未** seed 过时才写回它
     * （[TermViewPresenter.seedCellMetrics]）：presenter 已被显式 seed 过（如测试直接注入
     * 实测值、或本 View 换绑一个已建立几何的会话 presenter）时不得覆盖——显式 seed 优先。
     */
    private fun applyFontMetrics() {
        val p = presenter ?: return
        val sizePx = fontSizeSp * resources.displayMetrics.scaledDensity
        // 主字体 textSize 决定格宽（等宽栅格基准）；回退槽字体同尺寸，逐格居中使用同指标。
        fgPaint.textSize = sizePx
        glyphs().setTextSize(sizePx)
        val metrics = fgPaint.fontMetrics
        // 下界 1px（同 cellW 的 max(1, …)）：字格不可能是 0px 高——JVM 测试环境（Robolectric
        // legacy graphics）的 fontMetrics 在部分路径下是 stub，可能诚实地测出 descent==ascent==0，
        // 若不设下界会让 recomputeGeometry 的既有 guard 永久跳过、真机上则本就不会发生。
        cellH = max(1, (metrics.descent - metrics.ascent).roundToInt())
        // ascent 为负（基线上方高度）：基线偏移 = -ascent，保证首行字形完整落在 y∈[0,cellH)。
        baselinePx = -metrics.ascent
        // 实测字形推进宽 = 等宽栅格的唯一来源：上报 cols 与绘制列推进必须同源。
        val textW = fgPaint.measureText("W")
        cellW = max(1, floor(textW).toInt())
        lineHeightPx = cellH
        if (!p.cellMetricsSeeded) {
            p.seedCellMetrics(cellW, cellH)
        }
    }

    /** 终端色（Indexed/真彩/默认）→ Android ARGB 色值。
     *  索引 >15 走 xterm 256 扩展查表：旧实现 coerceIn(0,15) 把 256 色区整体塌缩到
     *  基础 16 色——fg 16（黑）与 bg 254（浅灰）同折到 15 号浅灰，Claude Code recap
     *  背景块内文字与底色同色整块隐形（fix-term-bg-cjk 模拟器实拍第二缺陷）。 */
    private fun colorFor(color: TerminalColor, background: Boolean): Int = when (color) {
        TerminalColor.Default -> if (background) themeBgArgb() else themeFgArgb()
        is TerminalColor.Rgb -> Color.rgb(color.r, color.g, color.b)
        is TerminalColor.Indexed ->
            if (color.index in 0..15) ANSI_COLORS[color.index] ?: Color.GRAY
            else XTERM_256.getOrElse(color.index) { Color.GRAY }
    }

    private fun themeBgArgb(): Int = DEFAULT_BG
    private fun themeFgArgb(): Int = DEFAULT_FG

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
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

        /** xterm 256 色扩展区一次性预计算查表（绘制热路径查表零分配）：
         *  16-231 为 6×6×6 色立方（分量 0 或 55+40×v，xterm 标准），232-255 为
         *  24 级灰阶梯 8+10×n。0-15 槽位仅占位（colorFor 先走 ANSI_COLORS）。 */
        val XTERM_256: IntArray = IntArray(256) { i ->
            fun cube(v: Int): Int = if (v == 0) 0 else 55 + 40 * v
            when {
                i < 16 -> ANSI_COLORS[i] ?: Color.GRAY
                i < 232 -> {
                    val c = i - 16
                    Color.rgb(cube(c / 36), cube(c / 6 % 6), cube(c % 6))
                }
                else -> {
                    val v = 8 + 10 * (i - 232)
                    Color.rgb(v, v, v)
                }
            }
        }
    }
}
