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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重连退避策略测试：指数 1s 起、上限 30s、抖动（conn 知识基底 §1）。
 * random 固定 0.5 ⇒ 抖动偏移为 0，得到确定性退避序列。
 */
class ConnReconnectPolicyTest {

    @Test
    fun testExponentialSequence() {
        val p = ReconnectPolicy(baseMs = 1000, maxMs = 30000, random = { 0.5 })
        // attempt 0..4 → 1s, 2s, 4s, 8s, 16s。
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), (0..4).map { p.nextDelayMs(it) })
    }

    @Test
    fun testCapsAtMax() {
        val p = ReconnectPolicy(baseMs = 1000, maxMs = 30000, random = { 0.5 })
        // 16s → 30s（cap）→ 30s…
        assertEquals(30000L, p.nextDelayMs(5))
        assertEquals(30000L, p.nextDelayMs(100))
    }

    @Test
    fun testJitterBoundedAndNonNegative() {
        val p = ReconnectPolicy(baseMs = 1000, maxMs = 30000, random = { 1.0 }) // +20%
        val up = p.nextDelayMs(1) // 2000 + 400 = 2400
        val pDown = ReconnectPolicy(baseMs = 1000, maxMs = 30000, random = { 0.0 }) // -20%
        val down = pDown.nextDelayMs(1) // 2000 - 400 = 1600
        assertEquals(2400L, up)
        assertEquals(1600L, down)
        // 任意抖动下结果恒 >= 1。
        val pAny = ReconnectPolicy(baseMs = 1000, maxMs = 30000, random = { kotlin.random.Random.nextDouble() })
        for (i in 0..20) {
            assertTrue(pAny.nextDelayMs(i) >= 1)
        }
    }
}
