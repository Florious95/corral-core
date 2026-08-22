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

package dev.agentmirror.app.tsnet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * TsnetWire 进程级接线点单测（feat-ts-wire 红测先行）。
 *
 * 纪律同 ServiceWire 测试先例：单例全局态，用例 teardown 必须 resetForTest 复位，
 * 防泄漏污染后续用例。后端经 [TsnetWire.backendFactory] 注入假件——JVM 单测绝不
 * 触达 gomobile native（TsnetBackend KDoc 红线）；执行器注入直通（同步跑完起网）。
 */
class TsnetWireTest {

    /** 记录型假后端：不触 native，start 返回固定代理。 */
    private class FakeBackend : TsnetBackend {
        var startCount = 0
        var closed = false
        var lastAuthKey: String? = null
        var lastStateDir: String? = null
        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
            startCount++
            lastAuthKey = authKey
            lastStateDir = stateDir
            return TsnetProxy("127.0.0.1", 40000, "cred")
        }

        override fun close() {
            closed = true
        }
    }

    private val direct = Executor { it.run() }

    @After
    fun tearDown() {
        TsnetWire.resetForTest()
    }

    @Test
    fun `环境未注入时 ensureStarted 显式失败不崩溃`() {
        val states = mutableListOf<TsnetState>()
        TsnetWire.stateListener = { states.add(it) }
        TsnetWire.ensureStarted("tskey-abc")
        // 003 失败可见：无环境（stateDir/hostname 未接入）不是静默 no-op，是 Error 态。
        assertTrue("state=${TsnetWire.state}", TsnetWire.state is TsnetState.Error)
        assertTrue(states.last() is TsnetState.Error)
    }

    @Test
    fun `注入环境与假后端后 ensureStarted 到 Up`() {
        val backend = FakeBackend()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { backend }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted("tskey-abc")

        assertTrue("state=${TsnetWire.state}", TsnetWire.state is TsnetState.Up)
        assertEquals(1, backend.startCount)
        assertEquals("tskey-abc", backend.lastAuthKey)
    }

    @Test
    fun `同 key 重复 ensureStarted 幂等（不重复起网）`() {
        val backend = FakeBackend()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { backend }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted("tskey-abc")
        TsnetWire.ensureStarted("tskey-abc")

        assertEquals("同 key 幂等：只允许一次真实起网", 1, backend.startCount)
    }

    @Test
    fun `换 key 重启节点（旧节点关闭新 key 起网）`() {
        val first = FakeBackend()
        val second = FakeBackend()
        val backends = ArrayDeque(listOf<TsnetBackend>(first, second))
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { backends.removeFirst() }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted("tskey-old")
        TsnetWire.ensureStarted("tskey-new")

        assertTrue("旧节点必须被关闭", first.closed)
        assertEquals("tskey-new", second.lastAuthKey)
        assertNotEquals("新 key 不能复用会忽略 authkey 的旧 tsnet 状态目录", first.lastStateDir, second.lastStateDir)
        assertFalse(first.lastStateDir.orEmpty().contains("tskey-old"))
        assertFalse(second.lastStateDir.orEmpty().contains("tskey-new"))
        assertTrue(TsnetWire.state is TsnetState.Up)
    }

    @Test
    fun `结构非法 key 显式 Error 且文案不含 key`() {
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { FakeBackend() }
        TsnetWire.executorForTest = direct

        val badKey = "bad key with spaces"
        TsnetWire.ensureStarted(badKey)

        val st = TsnetWire.state
        assertTrue("state=$st", st is TsnetState.Error)
        // authkey 红线（同 token §9）：错误文案绝不携带 key 值。
        assertTrue(!(st as TsnetState.Error).reason.contains(badKey))
    }

    @Test
    fun `hostname 归一化 - 非法字符替换与小写`() {
        // tailnet 节点名（Build_MODEL 直用会含空格/大写），归一为 DNS 友好形态。
        assertEquals("agentmirror-pixel-8-pro", TsnetWire.sanitizeHostname("Pixel 8 Pro"))
        assertEquals("agentmirror-device", TsnetWire.sanitizeHostname("  "))
    }
}
