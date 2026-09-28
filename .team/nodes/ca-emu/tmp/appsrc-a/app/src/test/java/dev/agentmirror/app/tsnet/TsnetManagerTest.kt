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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * TsnetManager 状态机单测（JVM 纯测，fake 后端——绝不触达 gomobile native）。
 *
 * 覆盖：authkey 校验拦截、成功入网、后端失败、停止、并发拒绝、
 * stop-during-starting 的迟到结果丢弃（generation 语义）。
 */
class TsnetManagerTest {

    /** 可编程 fake 后端：记录调用、可注入失败。 */
    private class FakeBackend : TsnetBackend {
        var startCalls = 0
        var closeCalls = 0
        var lastKey: String? = null
        var failWith: Exception? = null
        val proxy = TsnetProxy("127.0.0.1", 1080, "cred-hex")

        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
            startCalls++
            lastKey = authKey
            failWith?.let { throw it }
            return proxy
        }

        override fun close() {
            closeCalls++
        }
    }

    /** 手动执行器：任务入队不执行，测试点名推进——模拟异步窗口。 */
    private class ManualExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue.addLast(command)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val backend = FakeBackend()
    private val executor = ManualExecutor()
    private val states = mutableListOf<TsnetState>()
    private val manager = TsnetManager(backend, executor) { states.add(it) }

    @Test
    fun `无效 authkey 直接 Error 且不触达后端`() {
        assertFalse(manager.start("/dir", "phone", "   "))
        assertTrue(manager.state is TsnetState.Error)
        assertEquals(0, backend.startCalls)
    }

    @Test
    fun `成功启动 - Starting 到 Up 且 authkey 已归一化`() {
        assertTrue(manager.start("/dir", "phone", "  tskey-auth-abc-def  "))
        assertEquals(TsnetState.Starting, manager.state)
        executor.runAll()
        assertEquals(TsnetState.Up(backend.proxy), manager.state)
        assertEquals("tskey-auth-abc-def", backend.lastKey)
        assertEquals(listOf<TsnetState>(TsnetState.Starting, TsnetState.Up(backend.proxy)), states)
    }

    @Test
    fun `后端失败 - Error 保留原因但脱敏 authkey`() {
        val authKey = "tskey-auth-secret"
        backend.failWith = IllegalStateException("control plane unreachable for $authKey")
        assertTrue(manager.start("/dir", "phone", authKey))
        executor.runAll()
        val s = manager.state
        assertTrue(s is TsnetState.Error && s.reason.contains("control plane unreachable"))
        assertFalse((s as TsnetState.Error).reason.contains(authKey))
        assertEquals("失败路径也必须释放后端可能已创建的部分资源", 1, backend.closeCalls)
    }

    @Test
    fun `stop 从 Up 收尾 - 关后端回 Idle`() {
        manager.start("/dir", "phone", "tskey-auth-abc")
        executor.runAll()
        manager.stop()
        assertEquals(TsnetState.Idle, manager.state)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun `Starting 或 Up 期间重复 start 被拒`() {
        assertTrue(manager.start("/dir", "phone", "tskey-auth-abc"))
        assertFalse(manager.start("/dir", "phone", "tskey-auth-abc"))
        executor.runAll()
        assertFalse(manager.start("/dir", "phone", "tskey-auth-abc"))
        assertEquals(1, backend.startCalls)
    }

    @Test
    fun `stop during Starting - 迟到的 Up 被丢弃且后端被关`() {
        manager.start("/dir", "phone", "tskey-auth-abc")
        manager.stop()
        executor.runAll()
        assertEquals(TsnetState.Idle, manager.state)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun `stop 后可再次 start`() {
        manager.start("/dir", "phone", "tskey-auth-abc")
        executor.runAll()
        manager.stop()
        assertTrue(manager.start("/dir", "phone", "tskey-auth-xyz"))
        executor.runAll()
        assertEquals(TsnetState.Up(backend.proxy), manager.state)
        assertEquals(2, backend.startCalls)
    }
}
