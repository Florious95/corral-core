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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 导出目录有界红测（round2 缺口回归闸）：导出目录文件数必须 ≤ maxExportFiles。
 *
 * round1 只约束了环形缓冲（内存）与单文件（落盘 1MiB），导出产物目录无人管——
 * 每次导出用时间戳造新文件、listExports 从不被清理调用 → 目录线性增长不收敛，
 * 撞工程红线「资源有界：内存/磁盘增长有界或有轮转」。
 *
 * 断言：连续导出远超上限次数后，目录里 .log 文件数恒 ≤ maxExportFiles，
 * 且保留的是最新（最旧被轮转删除）。
 */
class DiagLogExportDirBoundedTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        dir = File.createTempFile("diag-export-dir-", "").apply { delete() }.also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
        dir.deleteRecursively()
    }

    @Test
    fun exportDir_fileCountCappedAfterManyExports() {
        val cap = 3
        DiagLog.initialize(dir.path, DiagLog.Config(maxEntries = 50, maxFileBytes = 100_000, maxExportFiles = cap))
        DiagLog.record("t", "some content")

        // 连续导出远超上限次数（20 次，远超 cap=3）。
        repeat(20) { i ->
            val file = File(dir, "diag-$i.log")
            val result = DiagLog.exportTo(file)
            assertTrue("第 $i 次导出失败：$result", result is DiagLog.ExportResult.Success)
        }

        // 目录文件数必须 ≤ cap。
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?: emptyArray()
        assertTrue(
            "导出目录文件数超上限 cap=$cap，实际=${files.size}——导出产物目录无轮转",
            files.size <= cap,
        )

        // 保留的是最新（最后一次导出 diag-19 必须在）。
        val names = files.map { it.name }
        assertTrue("最新导出必须保留（轮转应删最旧）", names.contains("diag-19.log"))
        assertTrue("最旧导出必须被轮转删除", !names.contains("diag-0.log"))
    }

    @Test
    fun exportDir_defaultCapIsBounded() {
        // 默认 Config() 的 maxExportFiles 也必须有界。
        DiagLog.initialize(dir.path)
        DiagLog.record("t", "content")
        repeat(30) { i ->
            val file = File(dir, "d-$i.log")
            DiagLog.exportTo(file)
        }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: emptyArray()
        assertTrue(
            "默认导出目录上限失效：${files.size} > ${DiagLog.DEFAULT_MAX_EXPORT_FILES}",
            files.size <= DiagLog.DEFAULT_MAX_EXPORT_FILES,
        )
    }
}
