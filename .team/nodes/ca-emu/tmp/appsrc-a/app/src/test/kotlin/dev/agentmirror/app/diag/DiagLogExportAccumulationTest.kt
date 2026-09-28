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
 * 红测（审查席 round2，资源有界主攻线）：
 *
 * [DiagLog.exportTo] 只保证**单个导出文件** ≤ [DiagLog.Config.maxFileBytes]（见
 * [DiagLogBoundedTest]，已验证）。但生产环境的真实调用方
 * `SettingsScreen.exportDiagLog`（app/app/src/main/java/dev/agentmirror/app/SettingsScreen.kt:190-198）
 * 每次导出都用 `File(dir, "diag-${System.currentTimeMillis()}.log")` 造一个**全新文件**，
 * 从不复用、从不清理旧导出——`DiagLog.listExports()` 存在（KDoc 写"设置页展示历史导出用"）
 * 但 `SettingsScreen.kt` 里搜不到任何调用点，是个未接线的死接口，没有任何代码会删除
 * `filesDir/diag/` 下的旧导出文件。
 *
 * 这条任务的验收判据就是"用户复现一次、导出一份日志"——**复现多次意味着导出多次**
 * （用户很可能反复触发同一个缺陷来确认复现，或者被要求"再导一次给我们看新变化"）。
 * 每次导出都留下一个新文件，单文件有上限不等于目录有上限：这里没有任何机制阻止
 * `filesDir/diag/` 随导出次数线性增长。
 *
 * 本测试复刻 `exportDiagLog` 的真实文件命名模式（同一目录、时间戳文件名前缀），
 * 反复调用 [DiagLog.exportTo]，直接测量目录总字节数，证明它随导出次数线性增长、
 * 不收敛到任何上限——这是「资源有界」红线里 `DiagLog.exportTo` 本身测不出、
 * 只有从调用方（SettingsScreen）的真实使用模式才能看出的缺口。
 */
class DiagLogExportAccumulationTest {

    @Before
    fun setUp() = DiagLog.resetForTest()

    @After
    fun tearDown() = DiagLog.resetForTest()

    /** 复刻 SettingsScreen.exportDiagLog 的真实文件命名模式（仅去掉时钟依赖，改用自增序号防同毫秒重名）。 */
    private fun newExportFile(dir: File, seq: Int): File = File(dir, "diag-$seq.log")

    @Test
    fun `重复导出如 SettingsScreen 真实模式,目录总字节数无上限地线性增长`() {
        val dir = File.createTempFile("diag-export-dir-", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        // 单文件上限设小一点（500 字节），复刻默认 1MiB 的相对比例，加速验证不改变结论。
        DiagLog.initialize(null, DiagLog.Config(maxEntries = 4096, maxFileBytes = 500))

        val exportCount = 30
        repeat(exportCount) { i ->
            // 每次导出前产生一批新记录（模拟"用户又复现了一次，产生新事件后再导出"）。
            repeat(20) { j -> DiagLog.record("t", "session-$i-event-$j-" + "x".repeat(20)) }
            val f = newExportFile(dir, i)
            val result = DiagLog.exportTo(f)
            check(result is DiagLog.ExportResult.Success) { "第 $i 次导出失败：$result" }
            // 单文件确实守住了上限——这条不是本测试要推翻的，是要证明"守住单文件≠守住目录"。
            check(f.length() <= 500) { "单文件上限被破——不是本测试要证明的缺口，前提已不成立" }
        }

        val totalBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val fileCount = dir.listFiles()?.size ?: 0

        assertTrue(
            "导出了 $exportCount 次，目录里应当留下 $exportCount 个文件（证明没有任何清理/复用机制）；" +
                "实际文件数=$fileCount",
            fileCount == exportCount,
        )
        assertTrue(
            "单文件上限是 500 字节，但目录总字节数随导出次数线性增长，远超单文件上限——" +
                "这就是「资源有界」红线在真实调用模式下的破防点：DiagLog.exportTo 只护住了" +
                "『这一个文件』，没人护住『这个目录』。totalBytes=$totalBytes, 单文件上限=500," +
                "如果目录真的有界，totalBytes 不应该随 exportCount 继续增长。",
            totalBytes > 500L * 5, // 远超单文件上限的任意倍数，证明"目录级别根本没有上限"这个结构性事实
        )
    }
}
