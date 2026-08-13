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
 *
 * resize 抑制（raw/019 裁定②，fix-ime-no-resize）：[onViewportSizeChanged] 只在**首次真实视口**
 * 建立时换算一次 rows/cols 并上抛（「仅首次进入 CLI 时 resize 一次」）；此后 IME 弹起 / 输入框
 * 变高引起的视口收缩（及复原）只更新 [visibleRows]（可见行数）——渲染窗口随之下移/上推露出底行
 * （视口上推，内容区平移，最后一行始终可见，D-20），**不再**改 rows/cols、不再上抛 resize。
 * 捏合改字号（[onFontSizeChanged]）仍按像素换算并上抛（005 契约，D-29 同族另行立案，不得误伤）。
 */
// FORENSICS-TEMP: D-36 取证用，收工必删。
/** D-36 取证桥接（临时诊断，w-base-v2 用，收工后移除）：持有当前存活 presenter，
 *  供 MainActivity 的调试广播接收器在外部触发时机点读一次 [TermViewPresenter.forensicsSnapshot]。 */
object D36ForensicsBridge {
    var current: TermViewPresenter? = null
}

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

    /**
     * 稳定视口基准（扣除 IME inset 后的窗口像素尺寸），D-38 真根因判据的载体。
     *
     * 设计（leader 打回几何推断后定稿）：**presenter 不做任何关于 IME 的推断**——「回前台那一刻
     * IME 在不在屏」是 View 层通过 WindowInsets 直接可知的事实，由 View 层扣除 IME inset 后把
     * 稳定窗口高度传给 [onRealViewportChanged]。presenter 收到即视为真实视口，重算并更新本基准。
     *
     * 为什么不能几何推断：分屏/多窗口拖拽/系统栏变化都可能是「宽度不变 + 高度变小」，与 IME 挤压
     * 同形。若 presenter 猜「小高度=IME」会把一次真实的几何变化当成 IME 忽略，制造更难查的假阴性
     * （leader 打回理由）。raw/019 的直接翻译是「IME 从不相干 → 扣掉它」，而非「猜到它然后忽略」。
     *
     * 本基准由 View 层传的「扣除 IME 后高度」驱动；在首帧 seed（[onViewportSizeChanged]）与每次
     * 真实视口变化（[onRealViewportChanged]）时更新。仅作「高度复原/增长」这类自愈判定的参照。
     */
    private var stableWidthPx: Int = 0
    private var stableHeightPx: Int = 0

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

    /**
     * 几何自愈纠正累计次数（D-38 真根因，可观测探针）。
     *
     * 自愈规则（leader 裁定）：当「无挤压时的像素 rows > 内核 rows」时，说明内核曾被错误
     * emit 钉在旧小值上（回前台 IME 挤压 rebase 的竞态后果），补发一次 resize 纠正。
     * 正常路径下本值**恒为 0**——挤压时像素 rows < 内核、复原时 == 内核，从不 > 内核；
     * 若本值在正常路径也增长，说明主路径有问题（那才是真正要修的东西，leader 硬约束）。
     */
    var geometryCorrectionCount: Int = 0
        private set

    /**
     * 历史最大上报 rows（自愈判据的必要成分）。
     *
     * 自愈只应在「内核曾被从更大值错误钉小」时触发。区分两个同形场景：
     *   - 首帧被 IME 挤压 seed（内核=92，从未到过更大值）→ 复原 96>92 但 maxReportedRows==92，
     *     不满足「历史更大」→ 不触发（正常路径，leader 收工条件：count 恒 0）；
     *   - 竞态 rebase（内核从 96 被钉到 86）→ 复原 96>86 且 maxReportedRows==96 > 86 → 触发。
     * 用「历史最大上报 rows」而非「当前内核」做参照，才不漏掉竞态、不误伤首帧挤压 seed。
     */
    private var maxReportedRows: Int = 0

    /** 统一 emit 出口：更新历史最大上报 rows 后调用注入的 [onResizeRequest]。 */
    private fun emitResize(rows: Int, cols: Int) {
        if (rows > maxReportedRows) maxReportedRows = rows
        onResizeRequest(rows, cols)
    }

    init {
        // 接管内核脏区回调：把屏幕脏行换算为逻辑行区间后缓存，作"画面已变化"的数据驱动
        // 唤醒信号（缺陷①：增量流到达的唯一唤醒点）。渲染层不据此局部重绘——View 帧回调
        // 取走即弃（仅排空缓冲防无界增长），实际整帧全窗口重绘（见 TermSurfaceView）。
        // 首帧必全绘由内核首次 feed/replay 的整屏脏区承载（内核构造后初始脏区=整屏，
        // 首次 flushDamage 整屏上抛），Present 不再重复标全屏。
        emulator.damageListener = DamageListener { markScreenRowsDirty(it) }
        D36ForensicsBridge.current = this // FORENSICS-TEMP: D-36 取证用，收工必删。
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
     * 视口/历史内省快照（常驻只读，D-36 仪表化 + w-dev-cols 巡检层共用）。
     *
     * 全部是 getter 组合、零副作用：不产生 resize、不碰帧循环、不改任何状态、不暴露可变引用。
     * 供外部（模拟器取证/w-dev-cols 巡检）直接读「本来就是数」的指标（maxTop/logicalCount/
     * scrollbackSize/topLine），比从像素反推准确。任意线程可安全调用。
     *
     * @contract
     * @pre none
     * @post 返回当前视口状态的只读快照；不改变任何内部状态、不触发回调
     * @err none
     * @inv 调用前后 resize 次数/脏区/帧请求均不变（有测试锚住）
     */
    fun forensicsSnapshot(): ForensicsSnapshot {
        val height = visibleRows
        val maxTop = (logicalCount - height).coerceAtLeast(0)
        return ForensicsSnapshot(
            scrollbackSize = emulator.scrollback.size,
            logicalCount = logicalCount,
            visibleRows = height,
            maxTop = maxTop,
            topLine = topLine,
            isFollowingBottom = topLine == null,
        )
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
        // D-36 鸡生蛋打破：本地 buffer 为空（打开会话初始态/ED3 清屏后）时 maxTop == 0，
        // 上滑（deltaLines > 0）本无可滚空间、next 被钳到 0 且恒触底 → topLine 恒 null（跟随），
        // 「滚到顶才拉历史」的补页条件（SessionViewModel.syncFromPresenter: atHistoryTop =
        // locked && window.first == 0）永远走不到，上滑完全失效。空 buffer 上滑显式锁定到
        // 逻辑行 0（可补页锚点），使上层补页条件命中、历史并入后 buffer 有内容可滚。
        if (maxTop == 0 && deltaLines > 0) {
            topLine = 0
        }
        // 视口移动即需重画（真机实证 swipe 无效与缺陷①同根：无人请求帧）。
        onFrameRequested?.invoke()
    }

    /**
     * 历史分页头插并入 [merged] 行后，已锁定历史区的视口随之平移：头插让所有旧逻辑行号
     * +[merged]，冻结的 [topLine] 必须同步平移，否则视口跳到并入的旧页而非停在原内容处
     * （D-36：连续滚动的锚定保持）。跟随态（topLine == null）贴底不动，无需平移。
     *
     * @contract
     * @pre [merged] >= 0（本页实际并入缓冲的历史行数，非请求 count——容量满时并入数更少）
     * @post 锁定态 topLine += merged（并入后仍指向同一内容行）；跟随态不变；随后必触发一次 [onFrameRequested]
     * @err none
     * @inv none
     */
    fun onHistoryPrepend(merged: Int) {
        if (merged <= 0) return
        topLine?.let { topLine = it + merged }
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
     * 真实视口变化（回前台/旋转/分屏/窗口尺寸变更）：重放当前像素几何。
     *
     * fix-viewport-restore-d38 判据：**两个入口语义正交**——
     * [onViewportSizeChanged] 是布局挤压（IME/输入框），只推可见行、不重算；
     * 本入口是真实视口变化，必须重算 rows/cols，内核尺寸不一致则 emit 一次 resize。
     *
     * 为什么「一律 emit」和「一律不 emit」都错、而必须给真实视口变化一个独立入口：
     * - 一律 emit：IME 弹起/收起都会扰动服务端（用户原话「绝对难以接受」）；
     * - 一律不 emit：回前台时没人负责把几何重新对齐到当前 View（D-38 根因）——View 高复原
     *   但 [onSizeChanged] 未必回调，内核 rows 停在离开前/首帧被挤压的旧小几何上
     *   （用户截图：终端只占顶部约 1/4、下方大片空黑）；emit 抑制进一步让本来能顺带纠正
     *   的路径也不纠正，但根因是「回前台无对齐入口」，叠加因素不是病根；
     * - 独立入口：View 层只有知道「这是回前台/窗口变更」的时点才调它（见 TermSurfaceView
     *   onWindowVisibilityChanged），自然区分了「临时挤压」与「真实视口变化」两种语义。
     *
     * [visibleRowsOverride] 的清除与重算在此一举：后台期间收缩残留的挤压随像素高复原被清掉
     * （updateVisibleRows 按当前像素重算），几何事件强制重算并（按需）emit 一次。
     *
     * @contract
     * @pre 入参为当前 View 的真实像素尺寸（可为 0x0——布局尚未就绪时调用应安全忽略）
     * @post viewportWidthPx/HeightPx 更新为入参；非正尺寸安全忽略（正尺寸才重算几何）；
     *       visibleRows 按当前像素重算（挤压残留被清除）；内核尺寸不一致则 emit 一次 resize；
     *       随后必触发一次 [onFrameRequested]（几何事件必重画）
     * @err none
     * @inv 仅当尺寸正且内核几何不一致才 emit；与 [onViewportSizeChanged] 的语义互斥
     */
    fun onRealViewportChanged(widthPx: Int, heightPx: Int) {
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        val rowsBefore = visibleRows
        updateVisibleRows()

        // 真实视口变化：presenter 不做 IME 推断——View 层传入的已是「扣除 IME inset 后的稳定
        // 窗口高度」（TermSurfaceView 从 WindowInsets 拿 IME 可见性与高度），presenter 收到即视为
        // 真实几何，重算并按需 emit（内核尺寸一致则跳过）。这样分屏/旋转/多窗口传真实变化正常
        // 重算；回前台 IME 在屏时 View 层传扣除后的全高，不会 rebase 到挤压值。
        if (widthPx > 0 && heightPx > 0) {
            stableWidthPx = widthPx
            stableHeightPx = heightPx
            recomputeGeometry()
        }
        if (visibleRows != rowsBefore) {
            onFrameRequested?.invoke()
        }
        // 几何事件本身即需重画（即使像素高未变——如回前台 View 高复原、onSizeChanged 未回调）。
        onFrameRequested?.invoke()
    }

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
            // 稳定基准 = 首帧 seed 的高度（IME 未开时的全高；若在屏则后续「真增长」分支自愈）。
            stableWidthPx = widthPx
            stableHeightPx = heightPx
            recomputeGeometry()
        }
        // 首帧之后：挤压/复原只改可见行数（视口上推），不再 emit resize。
        val rowsBefore = visibleRows
        updateVisibleRows()
        // 可见行数变化（挤压/复原）即需重画——视口上推露出底行，本回调是唯一信号
        // （旧链路经 emulator.resize→flushDamage 间接唤醒，现在 resize 不再走，须直呼）。
        if (visibleRows != rowsBefore) {
            onFrameRequested?.invoke()
        }
        // 几何自愈（D-38 真根因，leader 硬约束）：若当前像素能推出的全高 rows **大于**内核 rows，
        // 且**历史最大上报 rows 也大于内核**——说明内核曾被从更大值错误 emit 钉小（回前台 IME
        // 挤压 rebase 的竞态后果，错误状态黏住不消失），补发一次 resize 纠正。
        // 「历史最大」条件是首帧挤压 seed 的护栏：seed 到 92（从未到过 96）时复原 96>92 但
        // maxReportedRows==92 不满足「历史更大」→ 不触发。只有竞态 rebase（内核曾 96 被钉到 86）
        // 才满足 96>86 且 maxReportedRows(96)>86 → 触发。正常路径下本分支恒不走（count 恒 0）。
        if (viewportHeightPx > 0 && cellHeight > 0 &&
            viewportHeightPx / cellHeight > emulator.rows &&
            maxReportedRows > emulator.rows
        ) {
            geometryCorrectionCount++
            val rows = viewportHeightPx / cellHeight
            val cols = if (viewportWidthPx > 0 && cellWidth > 0) viewportWidthPx / cellWidth else emulator.cols
            emitResize(rows, cols)
        }
    }

    /**
     * 捏合改字号（**预览语义**，fix-pinch-preview-commit / raw/041 裁定）。
     *
     * 捏合过程中每次 onScale 调本方法：只更新 [cellWidth]/[cellHeight] 与可见行数、
     * 请求重画（本地预览生效），**绝不 emit resize**——服务端不被每次手势步扰动。
     * 手势结束时 View 层调 [onPinchCommit] 才发那一次 resize。
     *
     * 守卫1：预览必须实时生效（字号变化可见），不能为了少发帧连预览都不做——
     * 本方法更新字号 + 请求帧，预览即时。
     *
     * @contract
     * @pre newCellWidth / newCellHeight 为正整数
     * @post cellWidth/cellHeight 更新为入参；**不 emit resize**；更新可见行数并请求重画
     * @err none
     * @inv onResizeRequest 绝不在本方法内被调用（预览不重排）
     */
    fun onFontSizeChanged(newCellWidth: Int, newCellHeight: Int) {
        cellWidth = newCellWidth
        cellHeight = newCellHeight
        // 捏合改字格后可见行数随之变化（同一视口高 ÷ 新字格高），重排跟随新栅格。
        updateVisibleRows()
        // 栅格几何变了（即使行列数没变，格子像素尺寸也变了），必须重画。
        onFrameRequested?.invoke()
    }

    /**
     * 捏合手势结束：用最终字号发**一次** resize（fix-pinch-preview-commit / raw/041 裁定）。
     *
     * 一次完整捏合（多个 onScale 预览 + 手势结束）→ 本方法恰好被调一次 → [recomputeGeometry]
     * 用**最终** cellWidth/cellHeight 算 rows/cols 并 emit 一次。守卫2：提交带最终行列数，
     * 不是中间某步的。
     *
     * @contract
     * @pre 至少一次 [onFontSizeChanged] 已调用（cellWidth/cellHeight 为最终预览值）
     * @post 行列数与内核不一致则经 [onResizeRequest] 上抛一次（用最终字号）；随后请求重画
     * @err none
     * @inv 一次手势至多 emit 一次（幂等：内核已一致则 no-op）
     */
    fun onPinchCommit() {
        recomputeGeometry()
        onFrameRequested?.invoke()
    }

    /**
     * 实测字形推进宽回写（fix-cols-grid-convergence 修法 1）：View 层每帧测量出的真实
     * 列推进宽写回 [cellWidth]，使 [recomputeGeometry] 的 cols 与绘制推进**同一栅格来源**。
     *
     * 这是「最右列被截」的根治点：此前 cols 用名义 [DEFAULT_CELL_WIDTH]=10 算、绘制用
     * 实测 cellW 算，两套栅格永不收敛，cols 偏大时末列画到视口外被 Canvas 裁。
     *
     * **只回写 cellWidth、绝不回写 cellHeight**（收敛性 + 不动 IME 成果的双重原因）：
     * - cellW 是 cellHeight 的函数（measureCells 里 textSize = cellHeight*0.85 决定字形度量），
     *   回写 cellW 不改变下一次测量的 cellW → 首次回写后即幂等收敛（**至多一次**
     *   recomputeGeometry，可证明：同值直接 return）；回写 cellH 则会触发
     *   cellH→textSize→cellH 的反馈环（真机字体度量随字号缩放，需证明收敛，违反约束一）。
     * - rows = viewportHeight / cellHeight 用 cellHeight 算；动 cellHeight 会扰动
     *   fix-ime-no-resize 锚定的首帧 rows（TermViewImeResizePresenterProbeTest
     *   断言 firstViewportEmitsInitialResize = (96 to 108)，基于默认 cellHeight=20）。
     *
     * @contract
     * @pre measuredCellW > 0（View 层已 guard）
     * @post cellWidth 更新为入参；若与旧值不同则重算行列数并按需经 [onResizeRequest] 上抛
     *       一次（cols = viewportWidth / 实测宽，与绘制同源）、随后必触发一次 [onFrameRequested]
     *       （栅格几何变了）；同值调用为幂等 no-op（不触发任何 emit / 帧请求——反馈环收敛点）
     * @err none
     * @inv 同值二次调用不产生任何副作用；cellHeight 永不被本方法改动
     */
    fun setMeasuredCellWidth(measuredCellW: Int) {
        if (measuredCellW <= 0) return
        if (measuredCellW == cellWidth) return // 幂等：已收敛，绝不重复 emit（反馈环收敛点）
        cellWidth = measuredCellW
        recomputeGeometry()
        onFrameRequested?.invoke()
    }

    /**
     * 渲染层右缘护栏已 engage 的累计次数（fix-cols-grid-convergence 修法 3 的可观测信号）。
     *
     * 约束三（leader）：护栏不许变成静默遮羞布——必须可观测，正常条件从不 engage。
     * View 层 drawLine 检测到宽字符背景矩形右缘越过视口宽时递增本值并收边（把矩形裁进
     * 视口），测试断言本值 > 0 证明护栏在异常条件（网格超宽 = A 回归）确实兜住了，
     * 而非悄悄裁掉无人知晓。正常条件（cols 与画布同源）下本值恒为 0。
     */
    var clipGuardEngageCount: Int = 0
        private set

    /** View 层护栏收边时上报（可观测，非静默）：递增 [clipGuardEngageCount]。 */
    fun onClipGuardEngaged() {
        clipGuardEngageCount++
    }

    /** 按视口像素与字格像素重算 rows/cols；内核尺寸已一致则跳过（避免重复 resize）。 */
    private fun recomputeGeometry() {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || cellWidth <= 0 || cellHeight <= 0) return
        val rows = viewportHeightPx / cellHeight
        val cols = viewportWidthPx / cellWidth
        if (rows != emulator.rows || cols != emulator.cols) {
            emitResize(rows, cols)
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

/**
 * 视口/历史内省快照（[TermViewPresenter.forensicsSnapshot] 的返回，常驻只读）。
 *
 * 全部字段为不可变 Int/Boolean，不暴露任何可变引用。供模拟器取证（D-36）与巡检层
 * （w-dev-cols）直接读取「本来就是数」的视口指标。构造后不可变，调用无副作用。
 */
data class ForensicsSnapshot(
    val scrollbackSize: Int,
    val logicalCount: Int,
    val visibleRows: Int,
    val maxTop: Int,
    /** 视口顶行（逻辑行）；null = 跟随底部。 */
    val topLine: Int?,
    val isFollowingBottom: Boolean,
)
