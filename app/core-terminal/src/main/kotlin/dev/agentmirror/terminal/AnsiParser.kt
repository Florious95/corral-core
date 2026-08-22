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
 * 解析事件接收方：把字节流解析结果（可打印字符/控制符/转义序列）交给终端状态机。
 */
internal interface TermHandler {
    /** 处理一个可打印码点。 */
    fun print(codePoint: Int)

    /** 处理一个 C0 控制字节（BS/TAB/LF/CR 等）。 */
    fun control(byte: Int)

    /** 处理非 CSI/OSC 的 ESC 序列（如 ESC 7 / ESC M），[final] 为终止字节。 */
    fun esc(final: Int, intermediates: String)

    /** 处理 CSI 序列：数值参数、中间字节、私有前缀（如 '?'）与终止字符。 */
    fun csi(params: List<Int>, intermediates: String, prefix: Char?, final: Char)

    /** 处理 OSC 字符串（窗口标题等），默认忽略。 */
    fun osc(content: String) {}
}

/**
 * ANSI/VT 转义序列解析状态机：字节流 → print/control/esc/csi/osc 事件。
 *
 * 覆盖 tmux 上游（capture-pane -e 快照 + pipe-pane 增量流）出现的序列形态：
 * CSI（含私有前缀与 ':' 子参数）、OSC（BEL/ST 终止）、DCS/SOS/PM/APC（整段丢弃）、
 * 其余 ESC 序列。GROUND 态字节走增量 UTF-8 解码，转义序列内按 ASCII 处理。
 */
internal class AnsiParser(private val handler: TermHandler) {

    private enum class State { GROUND, ESC, ESC_INTERMEDIATE, CSI, OSC, OSC_ESC, STR, STR_ESC }

    private var state = State.GROUND
    private val utf8 = Utf8Decoder()
    private val params = ArrayList<Int>()
    private var current = 0
    private var hasCurrent = false
    private var prefix: Char? = null
    private val intermediates = StringBuilder()
    private val oscContent = StringBuilder()

    /** 喂入一段字节流（可在任意位置切断，状态跨调用保持）。 */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        for (i in offset until offset + length) {
            consume(bytes[i].toInt() and 0xFF)
        }
    }

    /** 复位解析状态（快照重放前调用，丢弃残留的半截序列）。 */
    fun reset() {
        state = State.GROUND
        utf8.reset()
        clearSequence()
        oscContent.setLength(0)
    }

    private fun clearSequence() {
        params.clear()
        current = 0
        hasCurrent = false
        prefix = null
        intermediates.setLength(0)
    }

    private fun consume(b: Int) {
        when (state) {
            State.GROUND -> when {
                b == ESC -> enterEsc()
                b < 0x20 -> handler.control(b)
                b == DEL -> {}
                else -> utf8.feed(b) { cp -> handler.print(cp) }
            }
            State.ESC -> when (b) {
                in 0x20..0x2F -> { intermediates.append(b.toChar()); state = State.ESC_INTERMEDIATE }
                '['.code -> state = State.CSI
                ']'.code -> { oscContent.setLength(0); state = State.OSC }
                'P'.code, 'X'.code, '^'.code, '_'.code -> state = State.STR
                ESC -> enterEsc()
                CAN, SUB -> state = State.GROUND
                else -> { state = State.GROUND; handler.esc(b, "") }
            }
            State.ESC_INTERMEDIATE -> when (b) {
                in 0x20..0x2F -> intermediates.append(b.toChar())
                ESC -> enterEsc()
                CAN, SUB -> state = State.GROUND
                else -> {
                    val im = intermediates.toString()
                    state = State.GROUND
                    clearSequence()
                    handler.esc(b, im)
                }
            }
            State.CSI -> when (b) {
                in '0'.code..'9'.code -> {
                    // 参数值封顶，防御畸形序列造成的超大重复计数。
                    current = (current * 10 + (b - '0'.code)).coerceAtMost(65535)
                    hasCurrent = true
                }
                ';'.code, ':'.code -> pushParam()
                in 0x3C..0x3F -> if (prefix == null && params.isEmpty() && !hasCurrent) prefix = b.toChar()
                in 0x20..0x2F -> intermediates.append(b.toChar())
                in 0x40..0x7E -> {
                    if (hasCurrent || params.isNotEmpty()) pushParam()
                    val p = ArrayList(params)
                    val im = intermediates.toString()
                    val pf = prefix
                    state = State.GROUND
                    clearSequence()
                    handler.csi(p, im, pf, b.toChar())
                }
                ESC -> enterEsc()
                CAN, SUB -> { state = State.GROUND; clearSequence() }
                else -> if (b < 0x20) handler.control(b)
            }
            State.OSC -> when (b) {
                BEL -> { state = State.GROUND; handler.osc(oscContent.toString()) }
                ESC -> state = State.OSC_ESC
                else -> if (oscContent.length < MAX_OSC) oscContent.append(b.toChar())
            }
            State.OSC_ESC -> when (b) {
                '\\'.code -> { state = State.GROUND; handler.osc(oscContent.toString()) }
                else -> { state = State.GROUND; handler.osc(oscContent.toString()); consume(ESC); consume(b) }
            }
            State.STR -> when (b) {
                ESC -> state = State.STR_ESC
                BEL -> state = State.GROUND
                else -> {}
            }
            State.STR_ESC -> when (b) {
                '\\'.code -> state = State.GROUND
                else -> state = State.STR
            }
        }
    }

    private fun enterEsc() {
        state = State.ESC
        clearSequence()
    }

    private fun pushParam() {
        if (params.size < MAX_PARAMS) params.add(if (hasCurrent) current else 0)
        current = 0
        hasCurrent = false
    }

    private companion object {
        const val ESC = 0x1B
        const val BEL = 0x07
        const val CAN = 0x18
        const val SUB = 0x1A
        const val DEL = 0x7F
        const val MAX_PARAMS = 32
        const val MAX_OSC = 4096
    }
}
