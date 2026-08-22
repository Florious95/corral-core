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

/**
 * 实测基准（审查席 round2，静默经济主攻线之一：热路径开销）。
 *
 * `DiagLog.record` 被调用点覆盖了拨号（[dev.agentmirror.app.tsnet.TsnetSocks.connect]）与
 * 渲染栅格（[dev.agentmirror.app.termview.TermViewPresenter.recordGridSnapshot]）这类热路径——
 * 这里不读代码猜"反正就几行字符串拼接肯定快"，直接在 JVM 里量：单次 `record()` 的墙钟耗时
 * （含锁临界区 + 正则脱敏 + 环形缓冲写入），预热后取平均，附上真实数字。
 *
 * 说明：这是 JVM 而非设备实测（受限于本轮未能获得一份反映最新 diag 代码的可安装 APK，
 * 见 docs/diag-log-review.md round2 节的说明）——JVM 数字仍能回答"这是不是重到会拖慢热路径"
 * 这个量级问题（微秒级 vs 毫秒级），但不能替代设备上的真实帧率影响测量。
 */
class DiagLogHotPathBenchmarkTest {

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        DiagLog.initialize(null)
    }

    @After
    fun tearDown() = DiagLog.resetForTest()

    @Test
    fun `record 单次调用平均耗时基准 - 预热后打印真实数字`() {
        // 预热：JIT 暖机，避免第一批调用的解释执行开销污染测量。
        repeat(5_000) { DiagLog.record("warmup", "warmup-message-$it") }
        DiagLog.resetForTest()
        DiagLog.initialize(null)

        val iterations = 50_000
        // 用一条接近真实调用点长度的消息（TsnetSocks 的 dial fail 行大致这个量级）。
        val sampleMessage = "dial fail host=100.64.0.1 port=8080 ex=SocketTimeoutException msg=connect timed out ms=3007"

        val startNs = System.nanoTime()
        repeat(iterations) { i -> DiagLog.record("bench", "$sampleMessage seq=$i") }
        val elapsedNs = System.nanoTime() - startNs

        val avgNs = elapsedNs.toDouble() / iterations
        val avgUs = avgNs / 1000.0

        println(
            "[DiagLog 热路径基准] $iterations 次 record() 调用，总耗时=${elapsedNs / 1_000_000}ms，" +
                "单次平均=${"%.2f".format(avgUs)}µs",
        )

        // 量级判据：单次调用均值不应到毫秒级（那才会在渲染帧/拨号超时窗口里量出影响）。
        // 10µs 是留了 10 倍以上余量的宽松上限——真出现毫秒级说明有锁竞争/正则回溯等真实问题。
        assertTrue(
            "record() 单次平均耗时=${avgUs}µs，超出预期量级（<10µs），需要排查是否有性能回归" +
                "（正则回溯/锁竞争/字符串复制过多）",
            avgUs < 10.0,
        )
    }
}
