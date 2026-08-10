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

import dev.agentmirror.terminal.Cell
import dev.agentmirror.terminal.DamageListener
import dev.agentmirror.terminal.ScreenSnapshot
import dev.agentmirror.terminal.TerminalEmulator

/**
 * 终端视口状态机：跟随/锁定历史、可见行窗口、捏合行列数换算、脏区合并（渲染逻辑与 Android View 分离的可测核心）。
 *
 * 本地滚动（006）：滚动只改视口顶行（本地 scrollback 行号，零网络）。跟随底部时 [topLine] 为 null，
 * 新输出到达窗口自动贴底；用户上滚即锁定历史，[topLine] 冻结为具体逻辑行号，锁定态新输出到达不动视口；
 * 拖回底部或点"回到底部"恢复跟随。捏合字号（005）→ 像素尺寸换算 rows/cols → 经 [onResizeRequest]
 * 上抛（协议 resize 帧由上层接线，conn/session 归属其他任务）。
 */
class TermViewPresenter(
    private val emulator: TerminalEmulator,
    private val onResizeRequest: (rows: Int, cols: Int) -> Unit,
) {

    /** 视口顶行（逻辑行，0=最老历史）：null=跟随底部；非 null=锁定历史，冻结不变。 */
    private var topLine: Int? = null

    /**
     * 帧请求回调（fix-term-render-debt 缺陷①）：任何"画面需要重画"的状态变化
     * （新脏区到达/视口滚动/回到底部/字号变化）时触发，由 View 层接到 postFrame。
     *
     * 数据驱动唤醒是渲染循环的唯一入口：增量流在 WS 收件线程 feed 后经内核
     * damageListener 到达这里，没有本回调就没人唤醒 Choreographer（真机实证
     * 画面冻结、重 attach 才刷新）。禁止用定时器轮询替代（静默经济红线：
     * 空闲必须零帧循环）。可能在任意线程被调（WS 收件线程/主线程），接收方自行跳线程。
     */
    var onFrameRequested: (() -> Unit)? = null

    /** 当前等宽字格像素尺寸（View 层测量/捏合后设置）。 */
    var cellWidth: Int = DEFAULT_CELL_WIDTH
        private set
    var cellHeight: Int = DEFAULT_CELL_HEIGHT
        private set

    /** 视图像素尺寸，捏合行列数换算的基准。 */
    private var viewportWidthPx: Int = 0
    private var viewportHeightPx: Int = 0

    /** 内核脏区换算来的逻辑行区间缓冲（"画面已变化"信号载体，非局部重绘清单——渲染层
     *  整帧全窗口重绘，View 帧回调取走即弃）。写侧在 WS 收件线程（feed→damageListener）、
     *  取侧在主线程帧回调，经 [damageLock] 互斥。 */
    private var pendingDamage: MutableList<IntRange>? = null

    /** [pendingDamage] 的跨线程互斥锁（增量流唤醒后写/取真正并发，缺陷①修复连带）。 */
    private val damageLock = Any()

    /** 本帧内核快照缓存：beginFrame 抓一次，避免 lineCells 对屏幕行逐行深拷贝。 */
    private var frameSnapshot: ScreenSnapshot? = null

    init {
        // 接管内核脏区回调：把屏幕脏行换算为逻辑行区间后缓存，作"画面已变化"的数据驱动
        // 唤醒信号（缺陷①：增量流到达的唯一唤醒点）。渲染层不据此局部重绘——View 帧回调
        // 取走即弃（仅排空缓冲防无界增长），实际整帧全窗口重绘（见 TermSurfaceView）。
        // 首帧必全绘由内核首次 feed/replay 的整屏脏区承载（内核构造后初始脏区=整屏，
        // 首次 flushDamage 整屏上抛），Present 不再重复标全屏。
        emulator.damageListener = DamageListener { markScreenRowsDirty(it) }
    }

    // ---- 视口状态机 ----

    /** 是否跟随底部（视口钉在最新输出）。 */
    val isFollowingBottom: Boolean get() = topLine == null

    /** 是否锁定在历史中（"回到底部"按钮可见性）。 */
    val showBackToBottom: Boolean get() = topLine != null

    /** 总逻辑行数 = 本地 scrollback + 当前屏幕（渲染窗口的坐标空间上界）。 */
    private val logicalCount: Int get() = emulator.scrollback.size + emulator.rows

    /**
     * 当前可见逻辑行区间（含端点，长度恒为屏幕行数，钳制在逻辑行空间内）。
     *
     * @contract
     * @pre none
     * @post 返回 [top, bottom] 且长度恒为 emulator.rows（顶部钳制在 [0, maxTop]，底部钳制到末逻辑行）
     * @err none
     * @inv 跟随态（topLine == null）窗口贴底；锁定态窗口顶 == 冻结的 topLine
     */
    val window: IntRange
        get() {
            val height = emulator.rows
            val maxTop = (logicalCount - height).coerceAtLeast(0)
            val top = (topLine ?: maxTop).coerceIn(0, maxTop)
            val bottom = (top + height - 1).coerceAtMost((logicalCount - 1).coerceAtLeast(0))
            return top..bottom
        }

    /**
     * 手指拖动改视口（正 [deltaLines] = 向上滚看更早历史，负 = 向下滚）。
     *
     * 跟随态先锁定到当前底部再滚；拖回窗口顶 == 屏幕顶即触底，自动恢复跟随（006）。
     *
     * @contract
     * @pre 无（deltaLines 为任意整数；正 = 向更早历史滚，负 = 向最新滚）
     * @post 视口顶行按 deltaLines 平移并钳制在 [0, maxTop]；触底（next >= maxTop）恢复跟随；
     *       随后必触发一次 [onFrameRequested]
     * @err none
     * @inv topLine 恒为 null（跟随）或在 [0, maxTop] 内的冻结行号
     */
    fun onScrollBy(deltaLines: Int) {
        val height = emulator.rows
        val maxTop = (logicalCount - height).coerceAtLeast(0)
        val current = topLine ?: maxTop
        val next = (current - deltaLines).coerceIn(0, maxTop)
        topLine = if (next >= maxTop) null else next
        // 视口移动即需重画（真机实证 swipe 无效与缺陷①同根：无人请求帧）。
        onFrameRequested?.invoke()
    }

    /**
     * 回到底部：恢复跟随，视口钉回最新输出。
     *
     * @contract
     * @pre none
     * @post topLine 置 null（恢复跟随态），随后必触发一次 [onFrameRequested]
     * @err none
     * @inv none
     */
    fun onScrollToBottom() {
        topLine = null
        onFrameRequested?.invoke()
    }

    // ---- 捏合 → 行列数换算（005：让 CLI 自己重画）----

    /**
     * 视图像素尺寸变化（旋转/窗口调整）：重算行列数，变化则上抛 resize 请求。
     *
     * @contract
     * @pre none
     * @post viewportWidthPx/HeightPx 更新为入参；行列数与内核不一致则经 [onResizeRequest] 上抛
     * @err none
     * @inv 像素/字格任一非正时不做换算（recomputeGeometry 提前返回）
     */
    fun onViewportSizeChanged(widthPx: Int, heightPx: Int) {
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        recomputeGeometry()
    }

    /**
     * 捏合改字号：重算行列数，与内核当前尺寸不一致则上抛 resize 请求。
     *
     * @contract
     * @pre newCellWidth / newCellHeight 为正整数
     * @post cellWidth/cellHeight 更新为入参；行列数变化则经 [onResizeRequest] 上抛；
     *       随后必触发一次 [onFrameRequested]（栅格几何已变，即使行列数没变）
     * @err none
     * @inv none
     */
    fun onFontSizeChanged(newCellWidth: Int, newCellHeight: Int) {
        cellWidth = newCellWidth
        cellHeight = newCellHeight
        recomputeGeometry()
        // 栅格几何变了（即使行列数没变，格子像素尺寸也变了），必须重画。
        onFrameRequested?.invoke()
    }

    /** 按视口像素与字格像素重算 rows/cols；内核尺寸已一致则跳过（避免重复 resize）。 */
    private fun recomputeGeometry() {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || cellWidth <= 0 || cellHeight <= 0) return
        val rows = viewportHeightPx / cellHeight
        val cols = viewportWidthPx / cellWidth
        if (rows != emulator.rows || cols != emulator.cols) {
            onResizeRequest(rows, cols)
        }
    }

    // ---- 帧消费：脏区合并 + 行数据 ----

    /**
     * 取当前缓存的逻辑行区间（内核脏区换算而来，已合并、裁剪到窗口内）；取走即清空。
     * 渲染层整帧全窗口重绘，本返回值仅作"画面已变化"的排空信号，不用于局部重绘。
     *
     * @contract
     * @pre none
     * @post 返回全部缓存区间且清空 pendingDamage（本帧一次性消费）；
     *       返回区间已裁剪到 [window] 内并合并成最小覆盖集
     * @err none
     * @inv 锁内只做取走置空（最小临界区），合并裁剪在锁外进行
     */
    fun takeDamage(): List<IntRange> {
        // 锁内只做取走置空（最小临界区），合并裁剪在锁外进行。
        val raw = synchronized(damageLock) {
            val taken = pendingDamage ?: return emptyList()
            pendingDamage = null
            taken
        }
        val win = window
        val clipped = raw.mapNotNull { r ->
            val lo = maxOf(r.first, win.first)
            val hi = minOf(r.last, win.last)
            if (lo <= hi) lo..hi else null
        }
        return mergeRanges(clipped)
    }

    /** 帧开始：抓一次内核快照缓存，供本帧 [lineCells] 复用（屏幕行零重复拷贝）。 */
    fun beginFrame() {
        frameSnapshot = emulator.snapshot()
    }

    /** 取第 [row] 个逻辑行的单元格（scrollback 行零拷贝，屏幕行用帧缓存）。 */
    fun lineCells(row: Int): List<Cell> {
        val sb = emulator.scrollback.size
        if (row < sb) return emulator.scrollback.line(row)
        val snap = frameSnapshot ?: emulator.snapshot()
        val index = row - sb
        return if (index in snap.lines.indices) snap.lines[index] else emptyList()
    }

    // ---- 内部实现 ----

    /** 内核屏幕脏行 [range] → 逻辑行区间缓存；锁定态窗口外损伤由 [takeDamage] 裁剪吸收。
     *  缓存后触发帧请求（缺陷①：增量流到达的唯一唤醒点，回调在锁外调避免持锁跳线程）。 */
    private fun markScreenRowsDirty(range: IntRange) {
        val sb = emulator.scrollback.size
        val logical = (sb + range.first)..(sb + range.last)
        synchronized(damageLock) {
            (pendingDamage ?: mutableListOf<IntRange>().also { pendingDamage = it }).add(logical)
        }
        onFrameRequested?.invoke()
    }

    /** 把区间集合并成最小覆盖集（重叠或相邻 [a,b] 与 [b+1,c] 都合并，减少每帧 draw 调用数）。 */
    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>(sorted.size)
        var cur = sorted[0]
        for (r in sorted.drop(1)) {
            if (r.first <= cur.last + 1) {
                cur = cur.first..maxOf(cur.last, r.last)
            } else {
                out.add(cur)
                cur = r
            }
        }
        out.add(cur)
        return out
    }

    private companion object {
        /** 初始等宽字格像素（View 测量前的占位，典型 6x13 密集字形）。 */
        const val DEFAULT_CELL_WIDTH = 10
        const val DEFAULT_CELL_HEIGHT = 20
    }
}
