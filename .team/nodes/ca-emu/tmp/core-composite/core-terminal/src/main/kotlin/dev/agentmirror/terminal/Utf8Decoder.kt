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
 * 增量 UTF-8 解码器：逐字节喂入、跨 feed 边界续解多字节序列，非法字节产出 U+FFFD。
 *
 * 增量流（pipe-pane）不保证按字符边界分包，解码状态必须跨调用保持；这是它独立成类的原因。
 */
internal class Utf8Decoder {
    private var codePoint = 0
    private var remaining = 0

    /** 喂入一个字节，解出完整码点时经 [emit] 产出（可能零次或一次以上）。 */
    fun feed(byte: Int, emit: (Int) -> Unit) {
        val b = byte and 0xFF
        if (remaining > 0) {
            if (b and 0xC0 == 0x80) {
                codePoint = (codePoint shl 6) or (b and 0x3F)
                remaining--
                if (remaining == 0) {
                    emit(if (codePoint in 0..0x10FFFF) codePoint else REPLACEMENT)
                }
            } else {
                // 续字节缺失：报告替换符后把当前字节按新序列重解。
                remaining = 0
                emit(REPLACEMENT)
                feed(b, emit)
            }
            return
        }
        when {
            b < 0x80 -> emit(b)
            b and 0xE0 == 0xC0 -> { codePoint = b and 0x1F; remaining = 1 }
            b and 0xF0 == 0xE0 -> { codePoint = b and 0x0F; remaining = 2 }
            b and 0xF8 == 0xF0 -> { codePoint = b and 0x07; remaining = 3 }
            else -> emit(REPLACEMENT)
        }
    }

    /** 丢弃未完成的多字节序列状态。 */
    fun reset() {
        remaining = 0
    }

    private companion object {
        const val REPLACEMENT = 0xFFFD
    }
}
