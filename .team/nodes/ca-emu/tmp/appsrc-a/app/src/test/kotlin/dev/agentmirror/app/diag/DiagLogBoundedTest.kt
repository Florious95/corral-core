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

package dev.agentmirror.app.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 有界红测（验收第 2 条：缓冲有界——资源有界红线）。
 *
 * 断言两条边界都成立：
 * - 内存环形缓冲条数恒 ≤ maxEntries，写满覆盖最旧（环形语义：最旧的被覆盖、最新的还在）；
 * - 落盘文件字节恒 ≤ maxFileBytes，超限截断最旧行。
 *
 * 用极小注入容量（不是默认 4096/1MiB）验证覆盖语义；再单独验证默认值本身有界。
 */
class DiagLogBoundedTest {

    private fun tmpExport(): File = File.createTempFile("diag-bounded-", ".log").apply { deleteOnExit() }

    private fun exportResult(file: File): DiagLog.ExportResult = DiagLog.exportTo(file)

    @Before
    fun setUp() {
        DiagLog.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    /** 环形语义：写远超容量后，最旧被覆盖、最新还在、缓冲条数恒 ≤ 容量。 */
    @Test
    fun ringBuffer_overwritesOldest_keepsNewest() {
        DiagLog.initialize(null, DiagLog.Config(maxEntries = 5, maxFileBytes = 10_000))
        repeat(50) { DiagLog.record("t", "msg-$it") }

        assertEquals("写满后缓冲条数必须恒 ≤ 容量", 5, DiagLog.size())
        val lines = DiagLog.snapshotForTest()
        assertTrue("最新记录必须在缓冲", lines.any { it.contains("msg-49") })
        assertFalse("最旧记录必须被覆盖", lines.any { it.contains("msg-0") })
        assertTrue("环形窗口应保留最后 5 条", lines.any { it.contains("msg-45") })
    }

    /** 重写压力：10k 条远超容量，内存占用必须恒 ≤ 上限。 */
    @Test
    fun memoryNeverExceedsMaxEntries_underHeavyWrite() {
        DiagLog.initialize(null, DiagLog.Config(maxEntries = 64, maxFileBytes = 10_000))
        repeat(10_000) { DiagLog.record("t", "x=$it") }
        assertTrue("内存缓冲条数超上限 64，实际=${DiagLog.size()}", DiagLog.size() <= 64)
    }

    /** 磁盘有界：大量长记录导出后，文件字节恒 ≤ 上限。 */
    @Test
    fun diskFile_cappedAtMaxFileBytes() {
        val cap = 500
        DiagLog.initialize(null, DiagLog.Config(maxEntries = 4096, maxFileBytes = cap))
        repeat(200) { DiagLog.record("t", "line-of-content-number-$it-".repeat(10)) }

        val file = tmpExport()
        val result = exportResult(file)
        assertTrue("导出失败：$result", result is DiagLog.ExportResult.Success)
        assertTrue(
            "磁盘占用超上限 cap=$cap，实际=${file.length()} 字节",
            file.length() <= cap,
        )
    }

    /** 磁盘超限后必须截断最旧行：最新记录保留、最旧记录消失。 */
    @Test
    fun diskFile_truncatesOldestLines_keepsNewest() {
        val cap = 600
        DiagLog.initialize(null, DiagLog.Config(maxEntries = 4096, maxFileBytes = cap))
        repeat(200) { DiagLog.record("t", "payload-marker-$it-" + "z".repeat(30)) }

        val file = tmpExport()
        exportResult(file)
        val text = file.readText()
        assertTrue("最新记录必须保留在磁盘", text.contains("payload-marker-199"))
        assertFalse("最旧记录必须被截断", text.contains("payload-marker-0"))
        assertTrue("截断后仍不得超上限", file.length() <= cap)
    }

    /** 默认值也必须是有界护栏：不注入任何配置，重写 5000 条后内存 ≤ 4096、磁盘 ≤ 1MiB。 */
    @Test
    fun defaults_areBounded() {
        DiagLog.initialize(null) // 默认 Config()
        repeat(5000) { DiagLog.record("t", "n=$it") }
        assertTrue(
            "默认内存上限失效：size=${DiagLog.size()} > ${DiagLog.DEFAULT_MAX_ENTRIES}",
            DiagLog.size() <= DiagLog.DEFAULT_MAX_ENTRIES,
        )

        val file = tmpExport()
        exportResult(file)
        assertTrue(
            "默认磁盘上限失效：${file.length()} > ${DiagLog.DEFAULT_MAX_FILE_BYTES}",
            file.length() <= DiagLog.DEFAULT_MAX_FILE_BYTES,
        )
    }
}
