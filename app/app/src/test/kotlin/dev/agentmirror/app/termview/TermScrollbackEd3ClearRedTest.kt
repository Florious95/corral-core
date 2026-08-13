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

import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ED3 清 scrollback 回归门（leader 裁定 2026-08-13：`CSI 3 J` 标准语义就是清除 scrollback，
 * CLI 主动发它 = 明确要求清历史；本地跟随是正确终端行为）。
 *
 * 本门锚「收到 ED3 时 scrollback 被清空」——防止将来有人为了修 D-36 顺手把它改掉（那会破坏
 * 正确的终端语义）。
 *
 * 背景：D-36 上滑失效查证中曾假设「CLI 清屏抹本地历史导致上滑失效」，但 w-base-v2 实测
 * Claude Code 发消息 ED2/ED3 均 0 命中，排除该方向。ED3 清 scrollback 保留为正确行为。
 *
 * 数值：scrollback 有 7 行 → feed ED3 → scrollback.size 变 0（被清）。
 */
class TermScrollbackEd3ClearRedTest {

    /** 回归门：ED3 必须清空本地 scrollback（正确终端语义）。 */
    @Test
    fun ed3ClearsLocalScrollback() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        emulator.replaySnapshot("abc\ndef\nghi", cols = 10, rows = 3)
        // 增量输出滚出 7 行进 scrollback。
        emulator.feed("jkl\nmno\npqr\ns\nt\nu\nv\n")
        assertTrue("前置：ED3 前应有历史", emulator.scrollback.size > 0)
        val before = emulator.scrollback.size

        emulator.feed(byteArrayOf(0x1b, 0x5b, 0x33, 0x4a)) // ESC [ 3 J = ED3（清 scrollback）

        assertEquals(
            "ED3 必须清空本地 scrollback（标准语义，CLI 主动清历史）——ED3 前=$before，ED3 后=${emulator.scrollback.size}",
            0,
            emulator.scrollback.size,
        )
    }
}
