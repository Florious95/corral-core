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

package dev.agentmirror.terminal

/**
 * 屏幕快照：渲染层一次性消费的不可变网格状态。
 */
data class ScreenSnapshot(
    val cols: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val altScreen: Boolean,
    val lines: List<List<Cell>>,
)

/**
 * 脏区回调：一次 feed/重放后需要重绘的屏幕行区间（屏幕行号，含端点）。
 *
 * 真实消费方 `TermViewPresenter` 接管本回调后把屏幕脏行换算成逻辑行区间缓存；
 * 渲染层帧循环把脏区当作"画面已变化"的触发信号、整帧重绘（并非据此局部增量刷新）。
 */
fun interface DamageListener {
    /** 通知 [rows] 区间（含端点，屏幕行号）内容已变化。 */
    fun onDamage(rows: IntRange)
}

/**
 * 终端模拟内核门面：字节流进、网格状态出，客户端最大契约的实现主体。
 *
 * 上游两种数据（006/004）：[replaySnapshot] 吃 capture-pane -e 整屏快照清屏重建；
 * [feed] 吃 pipe-pane 增量字节流常规推进。scrollback 本地持有、容量可配，
 * [prependHistory] 支持向头部插入 capture-pane -S 的历史分页。alternate screen
 * 进入即 [historyAvailable]=false（006 边界：全屏 TUI 历史不可用）。不做绘制。
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    scrollbackCapacity: Int = 5000,
) : TermHandler {

    private val main = TerminalGrid(cols, rows)
    private var alt: TerminalGrid? = null
    private val parser = AnsiParser(this)
    private val mouse = MouseModeTracker()

    /** 本地 scrollback（仅主屏滚出的行进入）。 */
    val scrollback = ScrollbackBuffer(scrollbackCapacity)

    private var style = TextStyle.DEFAULT
    private var savedCursor: SavedCursor? = null
    private var altActive = false

    /** 游标是否可见（DECTCEM ?25h/l）。 */
    var cursorVisible: Boolean = true
        private set

    /** 当前鼠标跟踪协议：0=关，1000=按键，1002=cell-motion（1003 请求会落到 1002）。 */
    val mouseTrackingMode: Int get() = mouse.tracking

    /** 当前鼠标编码：0=关，1006=SGR。其它编码码只记账，不用于发送。 */
    val mouseEncodingMode: Int get() = mouse.encoding

    /** 鼠标相关 DEC 模式的到达顺序（码 + 开/关），重连重放用。 */
    val mouseModeOrder: List<MouseModeEvent> get() = mouse.snapshotOrder()

    /** 最近一次 1003→1002 降级留痕，形如 `requested=1003 enabled=1002`。 */
    val lastMouseDowngradeTrace: String? get() = mouse.lastDowngradeTrace

    /** 脏区回调，feed/replay/resize 结束时按合并后的行区间通知一次。 */
    var damageListener: DamageListener? = null

    /**
     * 可选：ASCII `a`–`z` 被写入网格时通知（按键回显量具）。
     * 默认 null；未挂钩时 [print] 只多一次空引用判断，不分配。
     */
    var onAsciiPrint: ((Char) -> Unit)? = null

    /** 历史是否可用：alternate screen（全屏 TUI）期间为 false（006 边界）。 */
    val historyAvailable: Boolean get() = !altActive

    /**
     * 快照重放进行中：LF 附带隐式回车（fix-term-render-debt 缺陷②）。
     *
     * 两条上游的行尾字节形态不同：feed 吃 pipe-pane 原始 pty 字节（经行规程 ONLCR，
     * 行尾 CR LF，LF 保持严格 VT 语义只下移）；replaySnapshot 吃 capture-pane 输出
     * （行间**裸 LF 无 CR**，tmux 行分隔约定）——不补 CR 则每行起点继承上一行末尾列，
     * 即真机截图 term-glyph-after.png 的逐行右移。对已含 CR LF 的输入补 CR 幂等无害。
     */
    private var replayImplicitCr = false

    /** 当前列数。 */
    val cols: Int get() = grid().cols

    /** 当前行数。 */
    val rows: Int get() = grid().rows

    /** Active-grid cursor row. Cheap; does not copy the screen. */
    val cursorY: Int get() = grid().cursorY

    init {
        main.onLineScrolledOut = { line -> scrollback.appendTail(line) }
    }

    // 入口互斥（fix-term-render-debt 缺陷①连带）：帧唤醒落地后「WS 收件线程 feed」与
    // 「主线程帧回调 snapshot」常态并发，网格行数组换行/滚动期间取快照会读到撕裂行；
    // 五个公开入口（feed/replaySnapshot/resize/prependHistory/snapshot）以实例锁串行。
    // 锁序：本锁 → presenter.damageLock（damageListener 在锁内回调），反向不存在，无死锁。

    /**
     * 喂入增量字节流（pipe-pane 语义，可在任意字节处切断）。
     * @contract
     * @pre 0 <= offset <= bytes.size 且 0 <= length <= bytes.size - offset
     * @post 字节按 ANSI 状态机解析进当前网格；结束时合并脏区经 [damageListener] 上抛
     * @err 参数区间越界抛 IndexOutOfBoundsException
     * @inv 半截转义序列跨调用保持（增量语义，留在下一段续解）
     */
    @Synchronized
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        parser.feed(bytes, offset, length)
        flushDamage()
    }

    /**
     * 把一次点击编成 SGR 1006 字节；跟踪未开或编码不是 1006 时返回 null（不发）。
     * [column]/[row] 为 1-based。[button] 0=左。
     */
    @Synchronized
    fun encodeMouse(
        button: Int,
        column: Int,
        row: Int,
        press: Boolean,
        motion: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        ctrl: Boolean = false,
    ): ByteArray? {
        if (!mouse.canEncode()) return null
        return MouseSgr.encode(button, column, row, press, motion, shift, meta, ctrl)
    }

    /** 便捷入口：按 UTF-8 编码喂入文本。 */
    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    /**
     * 快照重放（capture-pane -e 语义）：按 [cols]x[rows] 清屏重建，scrollback 保留。
     *
     * 重连/订阅首帧/resize 后均走此入口（004 无状态：重连即重放）；样式、游标位置与
     * 可见性、alternate screen、半截转义序列全部复位后再喂快照字节（DECSC/DECRC
     * 保存的游标寄存器 [savedCursor] 保留不清）。
     * @contract
     * @pre cols > 0 且 rows > 0
     * @post 主屏按 cols x rows 清屏重建；scrollback 保留；样式/游标可见性/备屏/解析器复位
     * @err none
     * @inv none
     */
    @Synchronized
    fun replaySnapshot(bytes: ByteArray, cols: Int, rows: Int) {
        altActive = false
        alt = null
        style = TextStyle.DEFAULT
        cursorVisible = true
        mouse.reset()
        parser.reset()
        main.resize(cols, rows)
        main.reset()
        // capture-pane 输出以 LF **终结**最后一行（N 行 N 个 LF）：末 LF 是终结符不是
        // 分隔符，不剥则底行触发 scrollUp——整屏上移一行且顶行错误滚入 scrollback
        // （每次重 attach 重放都污染一行历史）。只剥恰好一个尾随 LF。
        val length = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == LF) bytes.size - 1 else bytes.size
        // capture-pane 行间是裸 LF：重放期间 LF 隐式回车（见 replayImplicitCr KDoc）。
        replayImplicitCr = true
        try {
            parser.feed(bytes, 0, length)
        } finally {
            replayImplicitCr = false
        }
        flushDamage()
    }

    /** 便捷入口：按 UTF-8 编码重放快照文本。 */
    fun replaySnapshot(text: String, cols: Int, rows: Int) =
        replaySnapshot(text.toByteArray(Charsets.UTF_8), cols, rows)

    /**
     * 换网格尺寸（只换尺寸不 reflow，内容以随后到达的服务端快照为准，005）。
     * @contract
     * @pre cols > 0 且 rows > 0
     * @post 主屏与备屏尺寸更新；左上角重叠区内容保留；滚动区域回落全屏
     * @err none
     * @inv none
     */
    @Synchronized
    fun resize(cols: Int, rows: Int) {
        main.resize(cols, rows)
        alt?.resize(cols, rows)
        flushDamage()
    }

    /**
     * 向 scrollback 头部插入更老的历史分页（capture-pane -S 输出，SGR 转义保留）。
     *
     * [bytes] 按行解析（LF 分行，仅应用 SGR，其余序列忽略）；alternate screen
     * 期间历史不可用，调用被忽略。
     * @contract
     * @pre none
     * @post 备屏未激活时解析 [bytes] 并头插进 scrollback；激活时无操作
     * @err none
     * @inv none
     */
    @Synchronized
    fun prependHistory(bytes: ByteArray) {
        if (altActive) return
        scrollback.prependHead(parseHistoryLines(bytes))
    }

    /** 便捷入口：按 UTF-8 编码插入历史文本。 */
    fun prependHistory(text: String) = prependHistory(text.toByteArray(Charsets.UTF_8))

    /**
     * 取当前屏幕的不可变快照（渲染层消费）。
     * @contract
     * @pre none
     * @post 返回与当前网格一致的 [ScreenSnapshot]，行列表为新建不可变 List（单元格对象不可变、可安全共享）
     * @err none
     * @inv none
     */
    @Synchronized
    fun snapshot(): ScreenSnapshot {
        val g = grid()
        return ScreenSnapshot(
            cols = g.cols,
            rows = g.rows,
            cursorX = g.cursorX,
            cursorY = g.cursorY,
            cursorVisible = cursorVisible,
            altScreen = altActive,
            lines = g.snapshotRows(),
        )
    }

    // ---- TermHandler：解析事件到网格操作的映射 ----

    override fun print(codePoint: Int) {
        grid().write(codePoint, CharWidth.of(codePoint), style, eraseStyle())
        val hook = onAsciiPrint
        if (hook != null && codePoint in 97..122) hook(codePoint.toChar())
    }

    override fun control(byte: Int) {
        val g = grid()
        when (byte) {
            0x08 -> g.backspace()
            0x09 -> g.tab()
            0x0A, 0x0B, 0x0C -> {
                // 重放模式：capture-pane 裸 LF 补隐式 CR（缺陷②行首漂移）；增量流保持严格 VT。
                if (replayImplicitCr) g.carriageReturn()
                g.lineFeed(eraseStyle())
            }
            0x0D -> g.carriageReturn()
            else -> {}
        }
    }

    override fun esc(final: Int, intermediates: String) {
        if (intermediates.isNotEmpty()) return // 字符集指定等序列忽略
        val g = grid()
        when (final.toChar()) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> g.lineFeed(eraseStyle())
            'E' -> { g.carriageReturn(); g.lineFeed(eraseStyle()) }
            'M' -> g.reverseLineFeed(eraseStyle())
            'c' -> fullReset()
            else -> {}
        }
    }

    override fun csi(params: List<Int>, intermediates: String, prefix: Char?, final: Char) {
        if (intermediates.isNotEmpty()) return
        if (prefix == '?') {
            when (final) {
                'h' -> params.forEach { setPrivateMode(it, true) }
                'l' -> params.forEach { setPrivateMode(it, false) }
            }
            return
        }
        if (prefix != null) return
        val g = grid()
        when (final) {
            'A' -> g.moveCursor(0, -n1(params))
            'B' -> g.moveCursor(0, n1(params))
            'C' -> g.moveCursor(n1(params), 0)
            'D' -> g.moveCursor(-n1(params), 0)
            'E' -> { g.carriageReturn(); g.moveCursor(0, n1(params)) }
            'F' -> { g.carriageReturn(); g.moveCursor(0, -n1(params)) }
            'G', '`' -> g.setCursor(n1(params) - 1, g.cursorY)
            'H', 'f' -> g.setCursor(nAt(params, 1) - 1, nAt(params, 0) - 1)
            'J' -> {
                val mode = params.firstOrNull() ?: 0
                g.eraseDisplay(mode, eraseStyle())
                if (mode == 3 && !altActive) scrollback.clear()
            }
            'K' -> g.eraseLine(params.firstOrNull() ?: 0, eraseStyle())
            'L' -> g.insertLines(n1(params), eraseStyle())
            'M' -> g.deleteLines(n1(params), eraseStyle())
            'P' -> g.deleteChars(n1(params), eraseStyle())
            '@' -> g.insertChars(n1(params), eraseStyle())
            'X' -> g.eraseChars(n1(params), eraseStyle())
            'S' -> g.scrollUp(n1(params), eraseStyle())
            'T' -> g.scrollDown(n1(params), eraseStyle())
            'd' -> g.setCursor(g.cursorX, n1(params) - 1)
            'r' -> {
                g.setScrollRegion(nAt(params, 0) - 1, (params.getOrNull(1)?.takeIf { it > 0 } ?: g.rows) - 1)
                g.setCursor(0, 0)
            }
            'm' -> style = applySgr(style, params)
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> {}
        }
    }

    // ---- 内部实现 ----

    private fun grid(): TerminalGrid = if (altActive) alt!! else main

    /** 擦除填充样式：VT 的 BCE 语义，空白格带当前背景色。 */
    private fun eraseStyle(): TextStyle =
        if (style.bg == TerminalColor.Default) TextStyle.DEFAULT else TextStyle(bg = style.bg)

    /** 首参数按"缺省/0 视为 1"取值（游标移动类序列的通用默认）。 */
    private fun n1(params: List<Int>): Int = nAt(params, 0)

    private fun nAt(params: List<Int>, index: Int): Int =
        (params.getOrNull(index) ?: 1).coerceAtLeast(1)

    private fun setPrivateMode(mode: Int, on: Boolean) {
        mouse.apply(mode, on)
        when (mode) {
            25 -> cursorVisible = on
            47, 1047 -> if (on) enterAlt(saveMainCursor = false) else exitAlt(restoreMainCursor = false)
            1049 -> if (on) enterAlt(saveMainCursor = true) else exitAlt(restoreMainCursor = true)
            else -> {}
        }
    }

    /** 进入 alternate screen：主屏与 scrollback 冻结，全新空白备屏接管。 */
    private fun enterAlt(saveMainCursor: Boolean) {
        if (altActive) return
        if (saveMainCursor) saveCursor()
        alt = TerminalGrid(main.cols, main.rows) // 每次进入都是全新空白屏（1049 清屏语义）
        altActive = true
        alt!!.markAllDirty()
    }

    /** 退出 alternate screen：备屏丢弃，主屏内容原样恢复。 */
    private fun exitAlt(restoreMainCursor: Boolean) {
        if (!altActive) return
        altActive = false
        alt = null
        if (restoreMainCursor) restoreCursor()
        main.markAllDirty()
    }

    private fun saveCursor() {
        val g = grid()
        savedCursor = SavedCursor(g.cursorX, g.cursorY, style)
    }

    private fun restoreCursor() {
        val saved = savedCursor ?: return
        grid().setCursor(saved.x, saved.y)
        style = saved.style
    }

    /** RIS 整机复位：清屏、样式游标复位、退出备屏；scrollback 保留（本地历史归客户端管）。 */
    private fun fullReset() {
        altActive = false
        alt = null
        style = TextStyle.DEFAULT
        cursorVisible = true
        savedCursor = null
        mouse.reset()
        main.reset()
    }

    /** 把活动网格累积的脏行区间上抛给渲染层。 */
    private fun flushDamage() {
        val dirty = grid().takeDirty() ?: return
        damageListener?.onDamage(dirty)
    }

    /** DECSC/DECRC 保存的游标位置与样式。 */
    private data class SavedCursor(val x: Int, val y: Int, val style: TextStyle)

    private companion object {
        /** LF 字节（replaySnapshot 剥尾随行终结符用）。 */
        const val LF = 0x0A.toByte()
    }
}

/**
 * 把 capture-pane -S 历史输出解析成带样式的行列表（仅应用 SGR，其余序列忽略）。
 */
private fun parseHistoryLines(bytes: ByteArray): List<List<Cell>> {
    val builder = HistoryBuilder()
    AnsiParser(builder).feed(bytes)
    return builder.finish()
}

/**
 * 历史行装配器：print 追加单元格、LF 收行、SGR 换样式，游标移动类序列一律忽略。
 */
private class HistoryBuilder : TermHandler {
    private val lines = ArrayList<List<Cell>>()
    private var current = ArrayList<Cell>()
    private var style = TextStyle.DEFAULT

    override fun print(codePoint: Int) {
        when (val width = CharWidth.of(codePoint)) {
            0 -> {
                val last = current.indexOfLast { it.width > 0 }
                if (last >= 0) {
                    val cell = current[last]
                    current[last] = cell.copy(text = cell.text + String(Character.toChars(codePoint)))
                }
            }
            else -> {
                current.add(Cell(String(Character.toChars(codePoint)), style, width))
                if (width == 2) current.add(Cell("", style, 0))
            }
        }
    }

    override fun control(byte: Int) {
        if (byte == 0x0A) {
            lines.add(current)
            current = ArrayList()
        }
    }

    override fun esc(final: Int, intermediates: String) {}

    override fun csi(params: List<Int>, intermediates: String, prefix: Char?, final: Char) {
        if (prefix == null && intermediates.isEmpty() && final == 'm') {
            style = applySgr(style, params)
        }
    }

    /** 收尾：末行无换行符也计入，返回全部历史行。 */
    fun finish(): List<List<Cell>> {
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }
}
