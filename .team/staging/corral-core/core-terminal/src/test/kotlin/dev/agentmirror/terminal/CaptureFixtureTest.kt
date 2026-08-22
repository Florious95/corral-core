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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 真实 tmux 字节夹具回归（fix-term-render-debt 缺陷②：行首 x 漂移）。
 *
 * 夹具由隔离 tmux（3.6a，80x24）现场采集，与服务端两条数据通道逐字节同构：
 * - `capture/snapshot.bin`：`capture-pane -e -p`（server bridge.go 同款命令）——
 *   行间是**裸 LF 无 CR**（capture-pane 行分隔约定）；
 * - `capture/delta.bin`：`pipe-pane -o` 原始 pty 字节（clear+printf 序列）——
 *   经 pty 行规程 ONLCR，行尾是 **CR LF**。
 *
 * 缺陷②根因：内核 LF 严格 VT 语义（只下移不归零列）对 feed（pipe-pane）正确，
 * 但 replaySnapshot 吃的 capture-pane 输出没有 CR ⇒ 每行起点继承上一行末尾列，
 * 肉眼即 e2e/artifacts/ui-review/term-glyph-after.png 的逐行右移。
 * 断言：两条通道喂完后所有内容行**行首都在第 0 列**。
 */
class CaptureFixtureTest {

    /** 读测试资源夹具字节（缺失即测试基建损坏，立刻失败而非静默跳过）。 */
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(name)) { "缺夹具资源 $name" }
            .use { it.readBytes() }

    /** 缺陷②红测主体：capture-pane 快照（裸 \n）重放后每行列归零。 */
    @Test
    fun snapshotReplayWithBareLfStartsEveryRowAtColumnZero() {
        val emu = TerminalEmulator(80, 24)
        emu.replaySnapshot(fixture("/capture/snapshot.bin"), 80, 24)
        // 四行 printf 内容必须整齐落在 0..3 行、各自从第 0 列开始。
        assertEquals("GLYPH_OK ┌─┐", emu.rowText(0))
        assertEquals("│ A │ green", emu.rowText(1))
        assertEquals("└─┘ ▀▄█", emu.rowText(2))
        assertEquals("plain tail", emu.rowText(3))
        // 夹具带真实的尾随行终结 LF（24 行 24 个 LF）：重放不许触发底行滚动，
        // 否则每次重 attach 都把顶行错误滚入 scrollback（历史污染）。
        assertEquals(0, emu.scrollback.size)
    }

    /** 基线：pipe-pane 增量（CR LF）feed 的列归零语义不许被②的修复破坏。 */
    @Test
    fun deltaFeedWithCrLfKeepsColumnsAligned() {
        val emu = TerminalEmulator(80, 24)
        emu.feed(fixture("/capture/delta.bin"))
        // 序列内含 clear（ESC[H ESC[J），之后的四行内容行首都在第 0 列。
        assertEquals("GLYPH_OK ┌─┐", emu.rowText(0))
        assertEquals("│ A │ green", emu.rowText(1))
        assertEquals("└─┘ ▀▄█", emu.rowText(2))
        assertEquals("plain tail", emu.rowText(3))
    }

    /** feed 严格 VT 语义留档：裸 LF 在增量流里仍只下移不归零（pipe-pane 已带 CR，
     *  归零属于 CR 的职责；此语义被 curses 类 cud1=\n 依赖，不许连带改掉）。 */
    @Test
    fun bareLfInDeltaStreamKeepsStrictVtSemantics() {
        val emu = TerminalEmulator(80, 24)
        emu.feed("abc\ndef")
        assertEquals("abc", emu.rowText(0))
        // 严格 VT：第二行从上一行末尾列（3）接着写。
        assertEquals("   def", emu.rowText(1))
    }
}
