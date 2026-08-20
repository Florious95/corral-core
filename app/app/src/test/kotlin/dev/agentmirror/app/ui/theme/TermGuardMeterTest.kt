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

package dev.agentmirror.app.ui.theme

import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.terminal.TerminalColor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A-meter-branch / A-meter-perf：`indexed` 与 `guardRgbBg` 每条分支必须留下带操作数的
 * [DiagLog] 记录；满屏同色重绘不得每格一条（coalesce + 热路径表）。
 *
 * 先验红：仪表未接时本类失败。⛔ 不改漏斗出口（D2 不在本格）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermGuardMeterTest {

    private lateinit var pal: TermPalette.Scheme

    /** 故意不等于 defaultBg，逼 colorFor 绕开预计算表 / 真彩 memo，走真实分支。 */
    private val skipTables: Int
        get() = pal.defaultFg

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        DiagLog.initialize(null)
        TermPalette.resetBindingForTest()
        TermPalette.bindSelectionForTest("vesper", "vesper")
        pal = TermPalette.of(true)
        DiagLog.resetForTest()
        DiagLog.initialize(null)
    }

    @After
    fun tearDown() {
        TermPalette.resetBindingForTest()
        DiagLog.resetForTest()
    }

    @Test
    fun indexedAndGuardRgbBgEachBranchLogsOperands() {
        val probes = listOf(
            Probe(
                name = "indexed-screen-black",
                color = TerminalColor.Indexed(TermPalette.CUBE_BLACK_INDEX),
                background = true,
                expectTag = TAG_INDEXED,
                expectBranch = "screen-black",
            ),
            Probe(
                name = "indexed-254",
                color = TerminalColor.Indexed(TermPalette.USER_MESSAGE_INDEX),
                background = true,
                expectTag = TAG_INDEXED,
                expectBranch = "index-254",
            ),
            Probe(
                name = "indexed-near-white",
                color = TerminalColor.Indexed(231),
                background = true,
                expectTag = TAG_INDEXED,
                expectBranch = "near-white",
            ),
            Probe(
                name = "indexed-ansi16",
                color = TerminalColor.Indexed(1),
                background = true,
                expectTag = TAG_INDEXED,
                expectBranch = "ansi16",
            ),
            Probe(
                name = "indexed-fallthrough-guard",
                color = TerminalColor.Indexed(232),
                background = true,
                expectTag = TAG_INDEXED,
                expectBranch = "fallthrough-guardRgbBg",
            ),
            Probe(
                name = "indexed-fallthrough-project",
                color = TerminalColor.Indexed(196),
                background = false,
                expectTag = TAG_INDEXED,
                expectBranch = "fallthrough-project",
            ),
            Probe(
                name = "guard-luma-screen-black",
                color = TerminalColor.Rgb(0, 0, 0),
                background = true,
                expectTag = TAG_GUARD,
                expectBranch = "luma-screen-black",
            ),
            Probe(
                name = "guard-luma-achroma-white",
                color = TerminalColor.Rgb(255, 255, 255),
                background = true,
                expectTag = TAG_GUARD,
                expectBranch = "luma-achroma-white",
            ),
            Probe(
                name = "guard-luma-chroma-white",
                color = TerminalColor.Rgb(255, 220, 180),
                background = true,
                expectTag = TAG_GUARD,
                expectBranch = "luma-chroma-white",
            ),
            Probe(
                name = "guard-project",
                color = TerminalColor.Rgb(80, 100, 140),
                background = true,
                expectTag = TAG_GUARD,
                expectBranch = "project",
            ),
        )
        val missing = ArrayList<String>()
        for (p in probes) {
            DiagLog.resetForTest()
            DiagLog.initialize(null)
            TermPalette.colorFor(p.color, p.background, dark = true, againstBg = skipTables)
            val lines = meterLines()
            val hit = lines.firstOrNull { it.contains("[${p.expectTag}]") && it.contains("branch=${p.expectBranch}") }
            if (hit == null) {
                missing += "${p.name}: 无 branch=${p.expectBranch} 于 $lines"
                continue
            }
            val ops = listOf("rgb=", "luma=", "chroma=", "thresh=", "out=")
            val absent = ops.filter { !hit.contains(it) }
            if (absent.isNotEmpty()) {
                missing += "${p.name}: 缺操作数 $absent 于 $hit"
            }
        }
        assertTrue(
            "A-meter-branch 每条分支必须留下含 rgb/luma/chroma/thresh/branch/out 的记录，缺失：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun fullScreenSameColorRedrawIsBounded() {
        val cells = 80 * 24
        repeat(cells) {
            TermPalette.colorFor(
                TerminalColor.Rgb(0, 0, 0),
                background = true,
                dark = true,
                againstBg = skipTables,
            )
        }
        val n = meterLines().size
        assertTrue(
            "A-meter-perf：满屏同色必须至少留下 1 条仪表（否则是恒真上界）。cells=$cells n=$n lines=${meterLines()}",
            n >= 1,
        )
        assertTrue(
            "A-meter-perf：满屏 $cells 格同色不得每格一条（须 coalesce）。n=$n lines=${meterLines()}",
            n <= 8,
        )
    }

    private fun meterLines(): List<String> = DiagLog.snapshotForTest().filter { line ->
        line.contains("[$TAG_INDEXED]") || line.contains("[$TAG_GUARD]")
    }

    private data class Probe(
        val name: String,
        val color: TerminalColor,
        val background: Boolean,
        val expectTag: String,
        val expectBranch: String,
    )

    private companion object {
        const val TAG_INDEXED = "term-indexed"
        const val TAG_GUARD = "term-guard-bg"
    }
}
