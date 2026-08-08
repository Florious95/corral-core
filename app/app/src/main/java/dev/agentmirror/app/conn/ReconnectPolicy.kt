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

package dev.agentmirror.app.conn

/**
 * 重连退避策略（conn 知识基底 §1：指数 1s 起、上限 30s、抖动）。
 *
 * 成功连接后退避计数重置；抖动幅度 [jitterRatio] 默认 ±20%。抖动源 [random]
 * 可注入固定值（rand=0.5 ⇒ 抖动偏移为 0），使单测断言确定性的退避序列。
 */
class ReconnectPolicy(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val jitterRatio: Double = 0.2,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    /**
     * 计算第 [attempt] 次重连的等待时长（attempt 从 0 起）。
     * `exp = min(base * 2^attempt, max)`；抖动为 ±ratio，最小 1ms。
     */
    fun nextDelayMs(attempt: Int): Long {
        val exponent = attempt.coerceAtLeast(0)
        // 指数上限内截断，防左移溢出（2^31 ≈ 21 亿已远超 max 30s）。
        val raw = if (exponent >= 16) maxMs else minOf(baseMs shl exponent, maxMs)
        val jitter = (raw.toDouble() * jitterRatio * (random() * 2 - 1)).toLong()
        return (raw + jitter).coerceAtLeast(1)
    }
}
