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

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A-pf-bench：200000 次 colorFor（256 扩展 + 真彩）必须低于声明上限。
 * 优化前 JVM 读数 171.522ms（见 .team/nodes/perf-remap/throughput-before.txt）。
 * 上限 80ms = 优化前的 ~47%，缓存未命中会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemapThroughputTest {

    @After
    fun tearDown() {
        TermPalette.resetBindingForTest()
    }

    @Test
    fun twoHundredThousandColorForBelowDeclaredCap() {
        TermPalette.resetBindingForTest()
        val workload = RemapGoldSamples.throughputWorkload()
        repeat(4_000) { i ->
            val (c, bg, dark) = workload[i % workload.size]
            TermPalette.colorFor(c, bg, dark)
        }
        val n = 200_000
        val t0 = System.nanoTime()
        for (i in 0 until n) {
            val (c, bg, dark) = workload[i % workload.size]
            TermPalette.colorFor(c, bg, dark)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val node = File("/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/perf-remap")
        node.mkdirs()
        File(node, "throughput-after.txt").writeText(
            "n=$n ms=${"%.3f".format(ms)} cap_ms=$CAP_MS before_ms=$BEFORE_MS\n",
        )
        println("THROUGHPUT_AFTER n=$n ms=${"%.3f".format(ms)} cap=$CAP_MS before=$BEFORE_MS")
        assertTrue(
            "200000 colorFor ${"%.3f".format(ms)}ms 超过上限 ${CAP_MS}ms（优化前 ${BEFORE_MS}ms）",
            ms < CAP_MS,
        )
    }

    private companion object {
        const val BEFORE_MS = 171.522
        const val CAP_MS = 80.0
    }
}
