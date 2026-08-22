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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executor

/**
 * 根因探针：缺陷⑤ —— 内嵌 tsnet 回前台永远连不上
 *
 * 缺陷描述（2026-08-14 用户报告）：
 * App 内嵌 tsnet（TS token 配对）连上 → 切后台 → 回前台 → 永远连不上。
 *
 * 根因（static code analysis 确认）：
 * [TsnetWire.state] == [TsnetState.Up] 的语义是「native Go tsnet 节点 start() 成功过」，
 * 不是「当前 DERP 连接可用 + SOCKS 真实可拨通」。后台冻结期间 DERP TCP 超时断裂，
 * Go goroutine 恢复后 native tsnet 不回调 Java 层 state 变化（内部自主重连）。
 * 于是 state 停在 Up，[TsnetDial.socketFactoryFor] 继续返回 SOCKS 工厂，
 * 所有重连重试走 SOCKS → 路由不通 → 立即报错 → 退避 → 循环。
 * 更关键：[TsnetWire.ensureStarted] 幂等守卫「m.state is Up → return」
 * 使得任何外部重启触发都无效——节点死亡不自愈。
 *
 * 探针策略：
 * - 用 [FakeBackendWithClosedProxy] 模拟「SOCKS 端口曾开，后来关闭（DERP 断裂）」的场景
 * - 三条探针分别证伪：幂等守卫拦截重启、socketFactoryFor 无健康检查、state 永久停在 Up
 *
 * 命中条件：探针全绿 → 缺陷存在（state 说谎 + 无自愈机制）
 * 不命中：有任何修复已合入 → 立即停下报 leader
 */
class TsnetResumeReconnectProbeTest {

    private val direct = Executor { it.run() }
    private val validKey = "tskey-auth-testkey1234567890abc"

    @After
    fun tearDown() {
        TsnetWire.resetForTest()
    }

    /**
     * 探针①：SOCKS 端口关闭后再次调用 ensureStarted 因幂等守卫无法重启节点。
     *
     * 模拟路径：正常起网（配对成功）→ 后台冻结期间 SOCKS 端口关闭（DERP 断裂）
     * → 回前台触发 ensureStarted（冷启动/onResume 路径）→ 幂等守卫拦截 → 节点未重启。
     *
     * 命中条件：
     *   backend.startCount == 1（第二次 ensureStarted 被幂等守卫拦截）
     *   TsnetWire.state 仍为 Up（无状态变化通知）
     */
    @Test
    fun `probe1 state 停在 Up 且 ensureStarted 因幂等守卫不重启节点`() {
        val backend = FakeBackendWithClosedProxy()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { backend }
        TsnetWire.executorForTest = direct

        // 步骤1：正常起网（配对成功场景）
        TsnetWire.ensureStarted(validKey)
        assertTrue("前置失败：起网后应为 Up，实际=${TsnetWire.state}", TsnetWire.state is TsnetState.Up)
        assertEquals("前置失败：应触发一次 backend.start", 1, backend.startCount)

        // 步骤2：模拟后台冻结期间 SOCKS 端口被关闭（DERP 断裂，Go goroutine 暂停时 OS 回收 TCP）
        backend.closeProxy()

        // 步骤3：回前台触发 ensureStarted（模拟冷启动 / onResume 路径），同 key 同 state
        TsnetWire.ensureStarted(validKey)

        // 探针断言：幂等守卫（m.state is Up → return）导致 state 仍为 Up，节点不重启
        assertTrue(
            "探针命中：state 仍为 Up（幂等守卫锁死状态，无法重启）；实际=${TsnetWire.state}",
            TsnetWire.state is TsnetState.Up,
        )
        assertEquals(
            "探针命中：backend.start 只调用一次（第二次 ensureStarted 被幂等守卫拦截）",
            1, backend.startCount,
        )
    }

    /**
     * 探针②：SOCKS 端口关闭后 socketFactoryFor 仍返回 SOCKS 工厂（无健康检查，选路死锁）。
     *
     * [OkHttpTransportFactory.create] 每次拨号只读 [TsnetWire.state]，不检查 SOCKS 端口是否可达。
     * state == Up → socketFactoryFor 返回非 null → OkHttp 走 SOCKS → 连接建立时立即被代理拒绝。
     * 所有重连重试都困在这个死路里，无出口。
     *
     * 命中条件：SOCKS 端口关闭后，对 tailnet 地址 socketFactoryFor 仍返回非 null
     */
    @Test
    fun `probe2 SOCKS 端口关闭后 socketFactoryFor 仍返回 SOCKS 工厂无健康检查`() {
        val backend = FakeBackendWithClosedProxy()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { backend }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted(validKey)
        assertTrue("前置：应为 Up", TsnetWire.state is TsnetState.Up)

        // 模拟后台期间 SOCKS 端口关闭
        backend.closeProxy()
        assertTrue("前置：端口应已关闭", backend.isProxyClosed)

        // 关键断言：选路逻辑不验证 SOCKS 可达性，state.Up → 持续返回 SOCKS 工厂
        val factory = TsnetDial.socketFactoryFor(TsnetWire.state, "100.64.0.1")
        assertNotNull(
            "探针命中：端口关闭后 socketFactoryFor 仍返回非 null（无健康检查，所有重连困在死路）",
            factory,
        )

        // 反向验证：非 tailnet 地址应返回 null（确认选路逻辑本身正确，只是缺健康检查）
        assertNull(
            "对照：非 tailnet 地址 socketFactoryFor 返回 null（直拨）",
            TsnetDial.socketFactoryFor(TsnetWire.state, "192.168.1.100"),
        )
    }

    /**
     * 探针③：多次 ensureStarted 均被幂等守卫拦截，state 永久停在 Up（无自愈机制）。
     *
     * 模拟回前台后网络变化事件反复触发 ensureStarted 的场景（如 onNetworkAvailable
     * 间接触发重新起网逻辑）。每次均被幂等守卫「m.state is Up → return」拦截。
     * 这是结构性缺失：没有任何机制探测 Up 状态是否真实可拨通。
     *
     * 命中条件：5 次 ensureStarted 后 backend.startCount 仍为 1，state 仍为 Up
     */
    @Test
    fun `probe3 多次 ensureStarted 均被幂等守卫拦截 state 永久停在 Up`() {
        val backend = FakeBackendWithClosedProxy()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { backend }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted(validKey)
        backend.closeProxy()

        // 模拟回前台后反复触发（每次网络变化都会触发相关代码路径）
        repeat(5) { TsnetWire.ensureStarted(validKey) }

        assertTrue(
            "探针命中：state 永久停在 Up，无自愈机制（实际=${TsnetWire.state}）",
            TsnetWire.state is TsnetState.Up,
        )
        assertEquals(
            "探针命中：5 次回前台触发均被幂等守卫拦截，backend 从未重启",
            1, backend.startCount,
        )
    }

    // ---- 测试桩 ----

    /**
     * 模拟「SOCKS 代理端口曾开，后台期间被关闭」的假后端。
     *
     * start() 绑定一个随机端口模拟 SOCKS 代理（只绑不处理，模拟端口存在但路由不通）。
     * closeProxy() 关闭端口模拟后台冻结期间 DERP 断裂 / OS 回收 TCP 连接。
     * 关键：closeProxy 不调用 TsnetManager.onState 回调——native 不会主动通知 Java 层，
     * 这正是「state 停在 Up，但 SOCKS 实际不可用」的核心模型。
     */
    private class FakeBackendWithClosedProxy : TsnetBackend {
        var startCount = 0
        private var proxyServer: ServerSocket? = null
        var isProxyClosed = false
            private set

        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
            startCount++
            val ss = ServerSocket(0)
            proxyServer = ss
            isProxyClosed = false
            return TsnetProxy("127.0.0.1", ss.localPort, "fake-cred-for-probe")
        }

        /**
         * 关闭 SOCKS 代理端口（模拟后台冻结期间 DERP 断裂 → Go goroutine 不回调 Java）。
         *
         * 故意不修改 TsnetWire.state——这正是缺陷的核心：native 不通知，state 永停 Up。
         */
        fun closeProxy() {
            proxyServer?.let { runCatching { it.close() } }
            proxyServer = null
            isProxyClosed = true
        }

        override fun close() {
            closeProxy()
        }
    }
}
