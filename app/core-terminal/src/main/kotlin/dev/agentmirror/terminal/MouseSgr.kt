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
 * SGR 1006 鼠标编码（xterm ctlseqs「DEC SET/RESET» 之外的上报格式）。
 *
 * 纯函数：不读仿真器状态。按下 `CSI < Pb ; Px ; Py M`，抬起同形结尾 `m`。
 * Pb/Px/Py 均为十进制 ASCII；Px=列、Py=行，**1-based**。
 * 左键 Pb=0。⛔ 不用 X10 / UTF-8 / urxvt 编码。
 */
object MouseSgr {
    /**
     * @param button 0=左 1=中 2=右；滚轮由调用方传入 64/65
     * @param column 1-based 列
     * @param row 1-based 行
     * @param press true=按下(M) false=抬起(m)
     */
    fun encode(
        button: Int,
        column: Int,
        row: Int,
        press: Boolean,
        motion: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        ctrl: Boolean = false,
    ): ByteArray {
        var pb = button
        if (motion) pb += 32
        if (shift) pb += 4
        if (meta) pb += 8
        if (ctrl) pb += 16
        val buf = ByteArray(24)
        var i = 0
        buf[i++] = 0x1B
        buf[i++] = '['.code.toByte()
        buf[i++] = '<'.code.toByte()
        i = appendDec(buf, i, pb)
        buf[i++] = ';'.code.toByte()
        i = appendDec(buf, i, column)
        buf[i++] = ';'.code.toByte()
        i = appendDec(buf, i, row)
        buf[i++] = if (press) 'M'.code.toByte() else 'm'.code.toByte()
        return buf.copyOf(i)
    }

    private fun appendDec(buf: ByteArray, start: Int, n: Int): Int {
        var v = if (n < 0) 0 else n
        if (v == 0) {
            buf[start] = '0'.code.toByte()
            return start + 1
        }
        val tmp = ByteArray(10)
        var t = 0
        while (v > 0) {
            tmp[t++] = ('0'.code + v % 10).toByte()
            v /= 10
        }
        var i = start
        for (k in t - 1 downTo 0) buf[i++] = tmp[k]
        return i
    }
}
