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

import dev.agentmirror.app.diag.DiagLog
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
 *
 * resize 抑制（raw/019 裁定②，fix-ime-no-resize）：[onViewportSizeChanged] 只在**首次真实视口**
 * 建立时换算一次 rows/cols 并上抛（「仅首次进入 CLI 时 resize 一次」）；此后 IME 弹起 / 输入框
 * 变高引起的视口收缩（及复原）只更新 [visibleRows]（可见行数）——渲染窗口随之下移/上推露出底行
 * （视口上推，内容区平移，最后一行始终可见，D-20），**不再**改 rows/cols、不再上抛 resize。
 * 捏合改字号（[onFontSizeChanged]）仍按像素换算并上抛（005 契约，D-29 同族另行立案，不得误伤）。
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

    /** 上次栅格快照的关键量（recordGridSnapshot 的变更守卫，防每帧重复记录刷缓冲）。 */
    private var lastGridVw: Int = -1
    private var lastGridNominal: Int = -1
    private var lastGridMeasured: Int = -1

    /**
     * 首次真实视口是否已建立（raw/019：唯一合法的一次 resize 已上抛）。
     *
     * 置位后 [onViewportSizeChanged] 只更新像素基准与可见行数，不再重算 rows/cols、
     * 不再上抛 resize——IME/输入框挤压不得再扰动服务端。首帧前的 0x0 预布局 / 非正尺寸
     * 不算真实视口，不置位（旋转重建 VM 后重新走本门，保证旋转仍是合法 resize）。
     */
    private var viewportSeeded = false

    /**
     * 当前可见行数（≤ 内核行数）；null = 未挤压，取 [TerminalEmulator.rows]。
     *
     * 视口上推的载体（raw/019）：IME/输入框把 View 高度挤小时，本值收缩为
     * `viewportHeightPx / cellHeight`，[window] 随之只覆盖内容**底部**这几行——
     * 跟随态贴底露出末行（D-20 最后一行仍可见），而非把末行裁出画布。像素挤压是布局
     * 必然，真正被消灭的是 rows/cols 变化引发的服务端重排（resize 帧）。
     */
    private var visibleRowsOverride: Int? = null

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
     * 当前可见逻辑行区间（含端点，长度恒为可见行数，钳制在逻辑行空间内）。
     *
     * 可见行数 = [visibleRowsOverride]（IME/输入框挤压时收缩）或内核行数。收缩时跟随态
     * 窗口仍贴底——露出内容末行（D-20 最后一行可见），这就是「视口上推，内容区平移」。
     *
     * @contract
     * @pre none
     * @post 返回 [top, bottom] 且长度恒为可见行数（顶部钳制在 [0, maxTop]，底部钳制到末逻辑行）
     * @err none
     * @inv 跟随态（topLine == null）窗口贴底；锁定态窗口顶 == 冻结的 topLine
     */
    val window: IntRange
        get() {
            val height = visibleRows
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
        val height = visibleRows
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
     * 视图像素尺寸变化（IME/输入框挤压、复原、旋转重建后的首帧）。
     *
     * raw/019 裁定②核心：**只在首次真实视口建立一次 rows/cols 并上抛**（「仅首次进入
     * CLI 时 resize 一次」）；此后本方法把尺寸变化一律当作「布局挤压」——只更新像素基准
     * 与可见行数（[updateVisibleRows]，视口上推露出底行），不再重算 rows/cols、不再上抛
     * resize，服务端不被扰动（消灭 resize 协议帧本体，而非靠服务端 no-op 兜底）。
     *
     * @contract
     * @pre none
     * @post viewportWidthPx/HeightPx 更新为入参；首次真实视口（正尺寸）行列数与内核不一致
     *       则经 [onResizeRequest] 上抛一次并置位 [viewportSeeded]；此后仅更新可见行数
     * @err none
     * @inv 像素/字格任一非正时不做换算（recomputeGeometry 提前返回）
     */
    fun onViewportSizeChanged(widthPx: Int, heightPx: Int) {
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        // 0x0 预布局 / 非正尺寸不是真实视口：不落 seed（否则旋转重建后首帧被吞成非 resize）。
        if (!viewportSeeded && widthPx > 0 && heightPx > 0) {
            viewportSeeded = true
            recomputeGeometry()
        }
        // 首帧之后：挤压/复原只改可见行数（视口上推），不再 emit resize。
        val rowsBefore = visibleRows
        updateVisibleRows()
        // D-38（回炉）：首帧后视口若真实增长超出内核 rows/cols（IME 收起、分屏/窗口变大、
        // 回前台），必须重算几何——否则 emulator.rows 停在旧的挤压小值，visibleRows 被
        // coerceIn(1, rows) 上限夹住，窗口画不满 View（用户截图：56 行空黑）。
        // 判据见 [viewportOutgrewEmulator]：挤压恒 <= 内核，等于/小于都不触发；只有
        // 「内核行数过时偏低」才重算并 emit——这就是 v1/v2/v3 找不到的区分判据。
        if (viewportOutgrewEmulator()) {
            recomputeGeometry()
        }
        // 可见行数变化（挤压/复原/增长恢复）即需重画——视口上推露出底行，本回调是唯一信号
        // （旧链路经 emulator.resize→flushDamage 间接唤醒，现在 resize 不再走，须直呼）。
        if (visibleRows != rowsBefore) {
            onFrameRequested?.invoke()
        }
    }

    /**
     * 真实视口变化入口（D-38 回炉修复）：回前台 / 分屏 / 窗口尺寸变更等「真实」视口事件。
     *
     * 与 [onViewportSizeChanged] 正交——后者首帧后一律按「挤压」处理（只更新可见行数），
     * 处理不了「回前台时 View bounds 未变、onSizeChanged 不再触发」的情况：后台期间几何
     * 若已过时（如 IME 收起后 emulator.rows 未更新），回前台若不主动重算就永远卡在旧的
     * 挤压小值。调用方：TermSurfaceView.onWindowVisibilityChanged 的 VISIBLE 分支（v5 曾
     * 用此法补同一缺口且 QA PASS，但 v5 的文件含闪烁回归元凶，见 TermSurfaceView 注释）。
     *
     * 判据与 [onViewportSizeChanged] 的增长分支一致（[viewportOutgrewEmulator]）：只有视口
     * 行/列数**超出**内核时重算并 emit。挤压（<）与相等一律不动——绝不把 IME 在屏的挤压
     * 值当真实视口 shrink 终端（v1/v2/v3 死因；回前台 IME 仍在屏时应保持挤压显示，不发 resize）。
     *
     * @contract
     * @pre none
     * @post viewportWidthPx/HeightPx 更新为入参；首帧未 seed 且尺寸为正则按首次视口 seed；
     *       视口超出内核行列数则重算并 emit；可见行数变化即请求帧
     * @err none
     * @inv 挤压（视口 < 内核）不产生任何重算/emit
     */
    fun onRealViewportChanged(widthPx: Int, heightPx: Int) {
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        // 首帧尚未建立（onSizeChanged 未到，先来的是窗口可见事件）：按首次真实视口 seed，
        // 之后 onViewportSizeChanged 因 viewportSeeded 已置位不再 emit——两种事件顺序只 seed 一次。
        if (!viewportSeeded && widthPx > 0 && heightPx > 0) {
            viewportSeeded = true
            recomputeGeometry()
            updateVisibleRows()
            onFrameRequested?.invoke()
            return
        }
        val rowsBefore = visibleRows
        if (viewportOutgrewEmulator()) {
            recomputeGeometry()
        }
        updateVisibleRows()
        if (visibleRows != rowsBefore) {
            onFrameRequested?.invoke()
        }
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
        // 捏合改字格后可见行数随之变化（同一视口高 ÷ 新字格高），重排跟随新栅格。
        updateVisibleRows()
        // 栅格几何变了（即使行列数没变，格子像素尺寸也变了），必须重画。
        onFrameRequested?.invoke()
        // 缺陷②观测点：捏合事件后落一条栅格快照（前后对比即「捏合前后各值如何变化」）。
        recordGridSnapshot(newCellWidth)
    }

    /**
     * 测量回写（fix-cols-grid-convergence X2 根治）：View 层 [TermSurfaceView.measureCells]
     * 每帧测得实测字形推进宽后调用，使 recomputeGeometry 的 cols 与绘制同一栅格来源。
     *
     * 幂等契约（反馈环收敛）：同值 no-op（不重算、不 emit、不请求帧）——每帧 measureCells
     * 都回写，若不同值就每帧重算，收敛性完全靠"值稳定即停止"兜住：cellHeight 不变 →
     * 测量 cellW 不变 → 同值 no-op → 至多一次 emit。cellHeight 永不被回写改动
     * （否则 cellH→textSize→cellH 反馈环）。
     *
     * 权衡①裁定（FIELD.md，leader 已批）：测量值胜于捏合——测量 cellW 是 cellHeight 的
     * 函数，与捏合 newW 无关，下次绘制必然用测量值覆盖捏合设的宽度。捏合缩放仍生效
     * （newH 变 → cellW 变），但宽度不再能自由设（由高度间接决定）。
     *
     * 首帧时序（权衡②）：onViewportSizeChanged（seed 名义 10）先 emit 一次，首次 onDraw 回写
     * 实测再 emit 一次。这是"先 seed 后回写"的一次性两段收敛：seed 保证首帧有合法尺寸、
     * 回写保证第二次就是实测值（此后幂等）。服务端会收到两次 resize + 两次重排，但这是
     * 从 seed（名义 10）向实测（真机 11）收敛的必要代价，且**只发生一次**；JVM 测量 stub
     * 下 cellW=1 会在任何 view.draw 时把 cols 打成视口宽/1（见 TermColsGridConvergenceDiscriminationTest
     * 的 JVM 约束注释）——真机走实测、CI 走归一化断言，二者不互相污染（权衡③）。
     *
     * @contract
     * @pre measuredCellW > 0（正像素宽度）
     * @post cellWidth 更新为入参；值 != 旧值则重算行列数，变化则经 [onResizeRequest] 上抛
     * @err none
     * @inv cellHeight 永不因回写改变；同值重复调用为纯 no-op
     */
    fun setMeasuredCellWidth(measuredCellW: Int) {
        if (measuredCellW <= 0) return
        if (measuredCellW == cellWidth) return // 幂等：值稳定即停止，反馈环收敛点
        cellWidth = measuredCellW
        recomputeGeometry()
        // 缺陷②观测点：回写实测推进宽后落一条栅格快照（幂等守卫 `== cellWidth` 已保证
        // 值稳定即停止，不会每帧刷；真机收敛序列 seed 名义 10 → 回写实测 11 → 停）。
        recordGridSnapshot(measuredCellW)
    }

    /**
     * D-38 判别（回炉）：视口行/列数是否**超出**内核当前行/列数——真实视口增长的信号。
     *
     * 挤压只会让视口 <= 内核（首帧被挤压 seed 时 ==，之后收缩 <）；只有真实增长（IME 收起、
     * 分屏/窗口变大、回前台）才会让视口 > 内核，这正是「内核 rows/cols 过时偏低」的信号。
     * 用它做重算触发条件，天然不会把挤压值当真实视口（v1/v2/v3 死因：在 View 层推断 IME）。
     */
    private fun viewportOutgrewEmulator(): Boolean {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || cellWidth <= 0 || cellHeight <= 0) return false
        return (viewportHeightPx / cellHeight) > emulator.rows ||
            (viewportWidthPx / cellWidth) > emulator.cols
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

    /**
     * 缺陷②观测点：上报栅格几何（w-cols-prep 第 5 条测试规格，字段名逐字对齐）。
     *
     * 调用方：TermSurfaceView.measureCells()（绘制层每次量完实测推进宽后喂进来）；
     * **变更守卫**——只在本栅格任一关键量（视口宽 / 名义字格宽 / 实测推进宽）变化时
     * 落一条，避免每帧重复记录刷爆环形缓冲（静默经济红线）。捏合/视口变化自然触发
     * 一次记录，前后对比即「捏合事件前后各值如何变化」。
     *
     * 字段（w-cols-prep 第 5 条规格）：viewport_width_px = 终端 View 像素宽；
     * cell_width_nominal = 上报 cols 用的字格宽（[cellWidth]）；cell_width_measured =
     * 绘制层实测推进宽（measureCells 的 fgPaint.measureText）；reported_cols =
     * floor(viewport_width_px / cell_width_nominal)；canvas_capacity_cols =
     * floor(viewport_width_px / cell_width_measured)；overflow_px = reported_cols >
     * canvas_capacity_cols 时 (canvas_capacity_cols+1)*cell_width_measured -
     * viewport_width_px，否则 0（容量边界列右缘越屏量，半字宽量级，非 reported 末列的
     * 整列量——1260 真机：115*11-1260=5px 才对得上用户主诉）。
     */
    fun recordGridSnapshot(measuredCellW: Int) {
        val vw = viewportWidthPx
        val nominal = cellWidth
        val reported = if (nominal > 0) vw / nominal else 0
        val capacity = if (measuredCellW > 0) vw / measuredCellW else 0
        val overflow = if (reported > capacity && measuredCellW > 0) {
            (capacity + 1) * measuredCellW - vw
        } else {
            0
        }
        if (vw == lastGridVw && nominal == lastGridNominal && measuredCellW == lastGridMeasured) return
        lastGridVw = vw
        lastGridNominal = nominal
        lastGridMeasured = measuredCellW
        DiagLog.record(
            "grid",
            "viewport_width_px=$vw cell_width_nominal=$nominal cell_width_measured=$measuredCellW " +
                "reported_cols=$reported canvas_capacity_cols=$capacity " +
                "overflow_px=$overflow half_cell_px=${measuredCellW / 2.0}",
        )
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

    /**
     * 当前可见行数：未挤压时 = 内核行数；被 IME/输入框挤压时 = 视口像素高 ÷ 字格高。
     *
     * 渲染窗口的上界（[window] 用它），恒钳在 [1, 内核行数]（挤压到只剩 1 行以内视为 1 行，
     * 避免空窗口；不放大——挤压不产生比内核更多的行）。像素/字格非正时回落内核行数。
     */
    private val visibleRows: Int
        get() {
            val override = visibleRowsOverride ?: return emulator.rows
            return override.coerceIn(1, emulator.rows)
        }

    /** 按当前像素视口与字格更新 [visibleRowsOverride]（视口上推的载体；非正输入回落 null）。 */
    private fun updateVisibleRows() {
        if (viewportHeightPx <= 0 || cellHeight <= 0) {
            visibleRowsOverride = null
            return
        }
        visibleRowsOverride = viewportHeightPx / cellHeight
    }

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
