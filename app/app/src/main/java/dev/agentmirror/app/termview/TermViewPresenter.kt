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
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.terminal.Cell
import dev.agentmirror.terminal.DamageListener
import dev.agentmirror.terminal.ScreenSnapshot
import dev.agentmirror.terminal.TerminalEmulator

/**
 * 终端视口状态机：跟随/锁定历史、可见行窗口、字格像素→行列数换算、脏区合并（渲染逻辑与 Android View 分离的可测核心）。
 *
 * 本地滚动（006）：滚动只改视口顶行（本地 scrollback 行号，零网络）。跟随底部时 [topLine] 为 null，
 * 新输出到达窗口自动贴底；用户上滚即锁定历史，[topLine] 冻结为具体逻辑行号，锁定态新输出到达不动视口；
 * 拖回底部或点"回到底部"恢复跟随。
 *
 * 字号→尺寸（feat-font-size-setting-drop-pinch，取代原 005 捏合）：字号是设置页选定、进入会话前
 * 已持久化的独立输入；View 层用实测字形度量（measureText/fontMetrics）换算出 cellWidth/cellHeight
 * 后经 [seedCellMetrics] 一次性写入，早于任何视口事件——几何只算一次，不再有「名义值播种→实测值
 * 回写」两段收敛（该模式随捏合一起拆除，原注释描述的真机收敛序列不再存在）。
 *
 * resize 抑制（raw/019 裁定②，fix-ime-no-resize）：[onViewportSizeChanged] 只在**首次真实视口**
 * 建立时换算一次 rows/cols 并上抛（「仅首次进入 CLI 时 resize 一次」）；此后 IME 弹起 / 输入框
 * 变高引起的视口收缩（及复原）只更新 [visibleRows]（可见行数）——渲染窗口随之下移/上推露出底行
 * （视口上推，内容区平移，最后一行始终可见，D-20），**不再**改 rows/cols、不再上抛 resize。
 */
class TermViewPresenter(
    private val emulator: TerminalEmulator,
    private val onResizeRequest: (rows: Int, cols: Int, reason: String) -> Unit,
) {
    constructor(
        emulator: TerminalEmulator,
        onResizeRequest: (rows: Int, cols: Int) -> Unit,
    ) : this(emulator, { rows, cols, _ -> onResizeRequest(rows, cols) })

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

    /** 当前等宽字格像素尺寸（View 层实测字形度量后经 [seedCellMetrics] 写入）。 */
    var cellWidth: Int = DEFAULT_CELL_WIDTH
        private set
    var cellHeight: Int = DEFAULT_CELL_HEIGHT
        private set

    /** 视图像素尺寸，字号→行列数换算的基准。 */
    private var viewportWidthPx: Int = 0
    private var viewportHeightPx: Int = 0

    /** 上次栅格快照的关键量（recordGridSnapshot 的变更守卫，防每帧重复记录刷缓冲）。 */
    private var lastGridVw: Int = -1
    private var lastGridNominal: Int = -1
    private var lastGridMeasured: Int = -1

    /**
     * 下一次 [onResizeRequest] 的原因（081：resize 日志必须带 reason）。
     * 调用方（SessionViewModel）在回调里读它，再传给 ConnectionManager.resize。
     */
    var lastResizeReason: String = "user"
        private set

    /**
     * 首次真实视口是否已建立（raw/019：唯一合法的一次 resize 已上抛）。
     *
     * 置位后 [onViewportSizeChanged] 只更新像素基准与可见行数，不再重算 rows/cols、
     * 不再上抛 resize——IME/输入框挤压不得再扰动服务端。首帧前的 0x0 预布局 / 非正尺寸
     * 不算真实视口，不置位（旋转重建 VM 后重新走本门，保证旋转仍是合法 resize）。
     */
    private var viewportSeeded = false

    /**
     * [seedCellMetrics] 是否已调用（防静默失效守卫：区分"字号已实测落定"与"仍是构造期
     * DEFAULT_CELL_WIDTH/HEIGHT 占位值"）。[onViewportSizeChanged]/[onRealViewportChanged]
     * 的首次真实视口分支必须先检查本标志，未置位则显式抛异常，不许用占位值悄悄算几何。
     *
     * 公开只读（[TermSurfaceView.presenter] 注入时据此判断是否需要自动补 seed——已被
     * 显式 seed 过的 presenter 不应被 View 的默认字号悄悄覆盖）。
     */
    var cellMetricsSeeded: Boolean = false
        private set

    /**
     * 字格相对上次写入是否变了（090 §2.5）。[seedCellMetrics] 置位，
     * [onRealViewportChanged] 消费后清掉。回前台时 View 可能重新实测出更大的字格，
     * 像素视口却没变——[viewportOutgrewEmulator] 为 false，若不据此重算就会
     * 按旧的多列画新的大字格，右侧被切（用户截图）。
     */
    private var cellMetricsDirty: Boolean = false

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

    // ---- 字号 → 行列数换算（feat-font-size-setting-drop-pinch：让 CLI 自己重画）----

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
        // 仪表（leader 2026-08-14 补充裁定）：一进来就记入参与旧值——这是排查「回前台后
        // 只画上面三分之一」这类问题时判断「到底有没有被调用」的第一手证据，不等后续分支。
        DiagLog.record(
            "viewport",
            "source=onViewportSizeChanged oldW=$viewportWidthPx oldH=$viewportHeightPx " +
                "newW=$widthPx newH=$heightPx viewportSeeded=$viewportSeeded " +
                "emulatorRows=${emulator.rows} emulatorCols=${emulator.cols}",
        )
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        var resized = false
        lastResizeReason = if (!viewportSeeded) "user" else "rotate"
        // 0x0 预布局 / 非正尺寸不是真实视口：不落 seed（否则旋转重建后首帧被吞成非 resize）。
        if (!viewportSeeded && widthPx > 0 && heightPx > 0) {
            // 防静默失效（leader 2026-08-14 补充裁定）：未先 seedCellMetrics 就不许用
            // DEFAULT_CELL_WIDTH/HEIGHT 占位值悄悄算几何——那等于把刚拆掉的「名义值播种」
            // 换个更隐蔽的形式（连"两次上报"这个可观察信号都没有）请回来。
            check(cellMetricsSeeded) {
                "TermViewPresenter.onViewportSizeChanged 在 seedCellMetrics 之前被调用" +
                    "（当前 cellWidth=$cellWidth cellHeight=$cellHeight 仍是占位值）——" +
                    "字号选定后必须先调用 seedCellMetrics 喂入实测字形度量，不许静默用占位值继续算 rows/cols"
            }
            viewportSeeded = true
            resized = recomputeGeometry()
        }
        // 首帧之后：挤压/复原只改可见行数（视口上推），不再 emit resize。
        val rowsBefore = visibleRows
        updateVisibleRows()
        // D-38（回炉）：首帧后视口若真实增长超出内核 rows/cols（IME 收起、分屏/窗口变大、
        // 回前台），必须重算几何——否则 emulator.rows 停在旧的挤压小值，visibleRows 被
        // coerceIn(1, rows) 上限夹住，窗口画不满 View（用户截图：56 行空黑）。
        // 判据见 [viewportOutgrewEmulator]：挤压恒 <= 内核，等于/小于都不触发；只有
        // 「内核行数过时偏低」才重算并 emit——这就是 v1/v2/v3 找不到的区分判据。
        val outgrew = viewportOutgrewEmulator(source = "onViewportSizeChanged")
        if (outgrew) {
            resized = recomputeGeometry() || resized
        }
        // 可见行数变化（挤压/复原/增长恢复）即需重画——视口上推露出底行，本回调是唯一信号
        // （旧链路经 emulator.resize→flushDamage 间接唤醒，现在 resize 不再走，须直呼）。
        if (visibleRows != rowsBefore) {
            onFrameRequested?.invoke()
        }
        // 仪表：结果与守卫状态，含守卫算出的"若重算会得到的候选行列数"——即使守卫拦下也记，
        // 让「该重算而没重算」（candidate != emulator 但 outgrewGuard=false）与「重算了但算
        // 错了」（resized=true 但 emulatorRows/Cols 仍不对）能光看日志区分开。
        recordViewportResult(source = "onViewportSizeChanged", resized = resized, outgrewGuard = outgrew)
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
     * 090 §2.5：字格在回前台被重新实测且与内核行列不一致时也必须重算。这不是挤压——
     * 像素没变、格子变大，candidate 更小，outgrew 为 false，旧实现会留下「大字号 + 右侧被切」。
     *
     * @contract
     * @pre none
     * @post viewportWidthPx/HeightPx 更新为入参；首帧未 seed 且尺寸为正则按首次视口 seed；
     *       视口超出内核行列数则重算并 emit；[cellMetricsDirty] 且推导行列与内核不一致则重算；
     *       可见行数变化即请求帧
     * @err none
     * @inv 挤压（视口 < 内核）且字格未变不产生重算/emit；字格已变则按真实视口重算
     */
    fun onRealViewportChanged(widthPx: Int, heightPx: Int) {
        // 仪表（leader 2026-08-14 补充裁定）：一进来就记——用户报「回前台后只画上面三分之一」，
        // 长后台（3.7 分钟）被系统回收 Surface 时 Activity ON_STOP/ON_START 与本回调（源自
        // View.onWindowVisibilityChanged）是否对得上，现在无从判断，光看日志就要能回答。
        DiagLog.record(
            "viewport",
            "source=onRealViewportChanged oldW=$viewportWidthPx oldH=$viewportHeightPx " +
                "newW=$widthPx newH=$heightPx viewportSeeded=$viewportSeeded " +
                "emulatorRows=${emulator.rows} emulatorCols=${emulator.cols}",
        )
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        lastResizeReason = "resume"
        recordResumeOperands()
        var resized = false
        var outgrew = false
        val cellsDirty = cellMetricsDirty
        cellMetricsDirty = false
        // 首帧尚未建立（onSizeChanged 未到，先来的是窗口可见事件）：按首次真实视口 seed，
        // 之后 onViewportSizeChanged 因 viewportSeeded 已置位不再 emit——两种事件顺序只 seed 一次。
        if (!viewportSeeded && widthPx > 0 && heightPx > 0) {
            // 防静默失效：同 onViewportSizeChanged 的守卫，理由见其注释。
            check(cellMetricsSeeded) {
                "TermViewPresenter.onRealViewportChanged 在 seedCellMetrics 之前被调用" +
                    "（当前 cellWidth=$cellWidth cellHeight=$cellHeight 仍是占位值）——" +
                    "字号选定后必须先调用 seedCellMetrics 喂入实测字形度量，不许静默用占位值继续算 rows/cols"
            }
            viewportSeeded = true
            // 仪表必须先走守卫：首帧路径以前硬编码 outgrewGuard=true 且不调用本函数，
            // 日志里「没被调用」和「算过 true」同形。
            outgrew = viewportOutgrewEmulator(source = "onRealViewportChanged")
            resized = recomputeGeometry()
            updateVisibleRows()
            onFrameRequested?.invoke()
            recordViewportResult(source = "onRealViewportChanged", resized = resized, outgrewGuard = outgrew)
            return
        }
        val rowsBefore = visibleRows
        outgrew = viewportOutgrewEmulator(source = "onRealViewportChanged")
        val mismatch = derivedGeometryDiffersFromEmulator()
        DiagLog.record(
            "viewport",
            "source=onRealViewportChanged cellsDirty=$cellsDirty " +
                "resume_rows=${derivedRows()} emulator_rows=${emulator.rows} " +
                "resume_cols=${derivedCols()} emulator_cols=${emulator.cols} " +
                "outgrew=$outgrew mismatch=$mismatch → ${outgrew || (cellsDirty && mismatch)}",
        )
        if (outgrew || (cellsDirty && mismatch)) {
            resized = recomputeGeometry()
        }
        updateVisibleRows()
        if (visibleRows != rowsBefore) {
            onFrameRequested?.invoke()
        }
        recordViewportResult(source = "onRealViewportChanged", resized = resized, outgrewGuard = outgrew)
    }

    /**
     * 081：回前台必须把推导列数与内核上次列数两边都记下。
     * derived_cols = 可用视口宽 / 字格宽（[viewportWidthPx] 已扣左右内边距）。
     * last_sent_cols 此处用内核 cols（与成功 resize 后的服务端协商值对齐）；
     * 服务端认的列宽另在 SNAPSHOT 路径记 `frame cols`。
     */
    private var lastReflowKey: String? = null

    private fun recordResumeOperands() {
        val derived = if (cellWidth > 0) viewportWidthPx / cellWidth else -1
        val key = "$viewportWidthPx|$cellWidth|$derived|${emulator.cols}"
        if (key == lastReflowKey) return
        lastReflowKey = key
        DiagLog.record(
            "reflow",
            "source=resume view_width_px=$viewportWidthPx cell_width_px=$cellWidth " +
                "derived_cols=$derived last_sent_cols=${emulator.cols}",
        )
    }

    /** 仪表：视口事件处理结果的统一落记（[onViewportSizeChanged]/[onRealViewportChanged] 共用）。 */
    private fun recordViewportResult(source: String, resized: Boolean, outgrewGuard: Boolean) {
        DiagLog.record(
            "viewport",
            "source=$source result resized=$resized outgrewGuard=$outgrewGuard " +
                "candidateRows=${if (cellHeight > 0) viewportHeightPx / cellHeight else -1} " +
                "candidateCols=${if (cellWidth > 0) viewportWidthPx / cellWidth else -1} " +
                "emulatorRows=${emulator.rows} emulatorCols=${emulator.cols}",
        )
    }

    /**
     * 字号 → 单元尺寸的唯一写入口（feat-font-size-setting-drop-pinch 契约①②④）：View 层
     * 用实测字形度量（measureText/fontMetrics，禁止查表配常量）算出的 cellWidth/cellHeight，
     * 在 presenter 注入或字号变化时调用一次，且必须早于任何 [onViewportSizeChanged]。
     *
     * 不在此处调用 [recomputeGeometry]/[onResizeRequest]：几何计算统一由视口事件
     * （[onViewportSizeChanged] 首次调用）承担，本方法只落定尺寸——避免同一次「进入会话」
     * 产生两次不同上报（旧「名义值播种 → 实测值回写」两段收敛模式已随捏合一起拆除）。
     *
     * @contract
     * @pre cellW / cellH 为实测值（非查表常量）；非正值不拒绝——JVM 测试环境（Robolectric
     *      legacy graphics）的字形度量在部分路径下是 stub，可能诚实地测出 0（见下）
     * @post cellWidth/cellHeight 更新为入参；[cellMetricsSeeded] 置位（解除
     *       [onViewportSizeChanged]/[onRealViewportChanged] 的防静默失效守卫）；
     *       宽高与上次不同则置 [cellMetricsDirty]（供回前台入口消费）；
     *       本方法自身不触发重算/上抛/请求帧
     * @err none（不校验正负——本方法只负责「记下调用方测出的值」，不负责评判测量质量；
     *      非正值会让 [recomputeGeometry] 的既有 guard 继续跳过几何计算，行为等同「尚未
     *      有可用几何」，但不影响 [cellMetricsSeeded]：防静默失效守卫防的是「没测就用
     *      DEFAULT 占位值硬算」，不是「测出了一个退化值」——二者不是同一件事）
     * @inv 正常生命周期内应在首次 [onViewportSizeChanged] 之前调用（进入会话前尺寸即定，契约④）
     */
    fun seedCellMetrics(cellW: Int, cellH: Int) {
        if (cellWidth != cellW || cellHeight != cellH) {
            cellMetricsDirty = true
        }
        cellWidth = cellW
        cellHeight = cellH
        cellMetricsSeeded = true
    }

    /**
     * D-38 判别（回炉）：视口行/列数是否**超出**内核当前行/列数——真实视口增长的信号。
     *
     * 挤压只会让视口 <= 内核（首帧被挤压 seed 时 ==，之后收缩 <）；只有真实增长（IME 收起、
     * 分屏/窗口变大、回前台）才会让视口 > 内核，这正是「内核 rows/cols 过时偏低」的信号。
     * 用它做重算触发条件，天然不会把挤压值当真实视口（v1/v2/v3 死因：在 View 层推断 IME）。
     *
     * 仪表：无论结果真假、操作数是否就绪，都把两边原始数值和结论写下来。
     * 早期 return false 若不记，日志里「没被调用 / 守卫拦下 / 算错了」三种同形。
     */
    private fun viewportOutgrewEmulator(source: String): Boolean {
        val ready = viewportWidthPx > 0 && viewportHeightPx > 0 && cellWidth > 0 && cellHeight > 0
        val candidateRows = derivedRows()
        val candidateCols = if (cellWidth > 0 && viewportWidthPx > 0) {
            minOf(viewportWidthPx / cellWidth, TerminalMetrics.maxCols)
        } else {
            -1
        }
        val out = ready && (candidateRows > emulator.rows || candidateCols > emulator.cols)
        DiagLog.record(
            "viewport",
            "viewportOutgrewEmulator: source=$source " +
                "viewport_rows=$candidateRows emulator_rows=${emulator.rows} " +
                "viewport_cols=$candidateCols emulator_cols=${emulator.cols} " +
                "viewport_w=$viewportWidthPx viewport_h=$viewportHeightPx " +
                "cell_w=$cellWidth cell_h=$cellHeight operandsReady=$ready → $out",
        )
        return out
    }

    private fun derivedRows(): Int =
        if (cellHeight > 0 && viewportHeightPx > 0) viewportHeightPx / cellHeight else -1

    private fun derivedCols(): Int =
        if (cellWidth > 0 && viewportWidthPx > 0) {
            minOf(viewportWidthPx / cellWidth, TerminalMetrics.maxCols).coerceAtLeast(1)
        } else {
            -1
        }

    private fun derivedGeometryDiffersFromEmulator(): Boolean {
        val rows = derivedRows()
        val cols = derivedCols()
        return rows > 0 && cols > 0 && (rows != emulator.rows || cols != emulator.cols)
    }

    /** 按视口像素与字格像素重算 rows/cols；内核尺寸已一致则跳过（避免重复 resize）。
     *  每次重算落一条栅格快照（[recordGridSnapshot]），可观测缺陷②是否回归。
     *  @return 是否实际上抛了 resize（供调用方仪表落记，见 [recordViewportResult]）。 */
    private fun recomputeGeometry(): Boolean {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || cellWidth <= 0 || cellHeight <= 0) return false
        val rows = viewportHeightPx / cellHeight
        val cols = minOf(viewportWidthPx / cellWidth, TerminalMetrics.maxCols).coerceAtLeast(1)
        val changed = rows != emulator.rows || cols != emulator.cols
        if (changed) {
            onResizeRequest(rows, cols, lastResizeReason)
        }
        recordGridSnapshot(cellWidth)
        return changed
    }

    /**
     * 缺陷②可观测性金丝雀（w-cols-prep 第 5 条测试规格，字段名逐字对齐；沿用旧字段名
     * 保持日志消费方兼容，语义已变）：单一实测来源架构下 cell_width_nominal 与
     * cell_width_measured 恒相等（[cellWidth] 本身就是实测值，不再有另一套"名义值"），
     * 故 reported_cols 与 canvas_capacity_cols 结构性恒相等、overflow_px 结构性恒为 0——
     * 这条日志因此变成「① ② 契约仍在生效」的持续证据，而非诊断工具。
     *
     * 调用方：[recomputeGeometry]（几何每次重算即落一条，含 [seedCellMetrics] 生效后的
     * 首次视口建立）。**变更守卫**——只在本栅格任一关键量（视口宽/字格宽）变化时落一条，
     * 避免重复记录刷爆环形缓冲（静默经济红线）。
     *
     * 字段：viewport_width_px = 终端 View 像素宽；cell_width_nominal = cell_width_measured
     * = [cellWidth]（单一来源）；reported_cols = floor(viewport_width_px / cellWidth)；
     * canvas_capacity_cols 同 reported_cols；overflow_px 结构性恒为 0。
     */
    fun recordGridSnapshot(measuredCellW: Int) {
        val vw = viewportWidthPx
        val nominal = cellWidth
        val reported = if (nominal > 0) minOf(vw / nominal, TerminalMetrics.maxCols) else 0
        val capacity = if (measuredCellW > 0) minOf(vw / measuredCellW, TerminalMetrics.maxCols) else 0
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
        /** [seedCellMetrics] 落定前的占位值（构造后到 View 注入 presenter 之间的间隙，
         *  正常生命周期内不会被任何几何计算实际使用）。 */
        const val DEFAULT_CELL_WIDTH = 10
        const val DEFAULT_CELL_HEIGHT = 20
    }
}
