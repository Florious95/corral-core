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

/** 一次鼠标相关 DEC 私有模式的到达记录（码、开/关）。顺序即到达顺序。 */
data class MouseModeEvent(val code: Int, val on: Boolean)

/**
 * 鼠标 DEC 模式账本：协议组 1000/1002/1003 与编码组互斥、谁后设谁生效。
 *
 * 热路径：只在 CSI `?h`/`?l` 完成时被调用（不是每个字节）。整数比较 + 可选
 * 往 IntArray 追加一个 packed int，无 HashMap、无每字节分配。
 *
 * 1003 请求降为 1002，并留下 `requested=1003 enabled=1002`。
 * UTF-8 / urxvt 编码码会记账但 never 用于发送（契约只用 SGR 1006）。
 */
internal class MouseModeTracker {
    var tracking: Int = 0
        private set
    var encoding: Int = 0
        private set
    var lastDowngradeTrace: String? = null
        private set

    private var order = IntArray(16)
    private var orderLen = 0

    fun reset() {
        tracking = 0
        encoding = 0
        lastDowngradeTrace = null
        orderLen = 0
    }

    /** 非鼠标码立即返回。鼠标码：记账 + 更新互斥组当前值。 */
    fun apply(code: Int, on: Boolean) {
        if (!isMouseCode(code)) return
        record(code, on)
        when (code) {
            1000, 1002 -> tracking = if (on) code else 0
            1003 -> {
                if (on) {
                    tracking = 1002
                    lastDowngradeTrace = "requested=1003 enabled=1002"
                } else {
                    tracking = 0
                }
            }
            1006 -> encoding = if (on) 1006 else 0
            MODE_UTF8_NEVER, MODE_URXVT_NEVER ->
                encoding = if (on) code else 0
        }
    }

    fun canEncode(): Boolean =
        (tracking == 1000 || tracking == 1002) && encoding == 1006

    fun snapshotOrder(): List<MouseModeEvent> {
        val out = ArrayList<MouseModeEvent>(orderLen)
        for (i in 0 until orderLen) {
            val packed = order[i]
            out.add(MouseModeEvent(code = packed ushr 1, on = (packed and 1) == 1))
        }
        return out
    }

    private fun record(code: Int, on: Boolean) {
        if (orderLen == order.size) {
            order = order.copyOf(order.size * 2)
        }
        order[orderLen++] = (code shl 1) or (if (on) 1 else 0)
    }

    private fun isMouseCode(code: Int): Boolean =
        code == 1000 || code == 1002 || code == 1003 ||
            code == 1006 || code == MODE_UTF8_NEVER || code == MODE_URXVT_NEVER

    private companion object {
        /** UTF-8 mouse encoding code. never used for send. */
        const val MODE_UTF8_NEVER = 1005
        /** urxvt mouse encoding. 不用 1015. */
        const val MODE_URXVT_NEVER = 1015
    }
}
