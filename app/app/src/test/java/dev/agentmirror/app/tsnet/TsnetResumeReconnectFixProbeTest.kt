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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * 缺陷⑤修复生效探针（leader 2026-08-14 裁定：新增 probe4/5/6 作为「修复生效」判据）。
 *
 * 与 [TsnetResumeReconnectProbeTest]（回归哨兵，三条修复后仍绿）不同：
 * 本文件三条探针指向修复新增的 [TsnetWire.notifySocksRouteFailure]——该函数修复前
 * 不存在，因此在当前 HEAD 上本文件**编译红**（编译红也算红，leader 裁定）。修复合入后
 * 三条探针断言全绿 = 失败驱动自愈生效。
 *
 * 探针语义（命中条件）：
 * - probe4：notifySocksRouteFailure 触发节点重启（修复生效核心判据）
 * - probe5：30s 节流生效——连环拨号失败只重启一次（防重启风暴，静默经济红线）
 * - probe6：官方 TS 并存不误触发——state==Idle 时 notifySocksRouteFailure 必须 no-op
 *
 * 命中条件：探针全绿 = 修复有效（失败驱动自愈已实现且节流/隔离正确）。
 */
class TsnetResumeReconnectFixProbeTest {

    private val direct = Executor { it.run() }
    private val validKey = "tskey-auth-testkey1234567890abc"

    @After
    fun tearDown() {
        TsnetWire.resetForTest()
    }

    /**
     * 探针④：notifySocksRouteFailure 触发节点重启（修复生效核心判据）。
     *
     * 场景：节点 Up（配对成功）→ SOCKS 路由断裂（DERP 死）→ 常驻连接拨号失败 →
     * notifySocksRouteFailure → 用已存 currentKey 停旧建新（startCount 1→2）。
     *
     * 命中条件：触发后 backend 重启一次（startCount==2）、state 恢复 Up、沿用同一 key。
     */
    @Test
    fun `probe4 notifySocksRouteFailure 触发节点重启`() {
        val created = mutableListOf<CountingBackend>()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { CountingBackend().also { created.add(it) } }
        TsnetWire.executorForTest = direct

        TsnetWire.ensureStarted(validKey)
        assertTrue("前置：起网后应为 Up，实际=${TsnetWire.state}", TsnetWire.state is TsnetState.Up)
        assertEquals("前置：应触发一次 backend.start", 1, totalStarts(created))

        // 修复前本方法不存在 → 本文件编译红；修复后触发即重启。
        TsnetWire.notifySocksRouteFailure()

        assertEquals(
            "触发自愈应停旧建新（startCount 1→2）",
            2, totalStarts(created),
        )
        assertTrue("重启后节点应为 Up，实际=${TsnetWire.state}", TsnetWire.state is TsnetState.Up)
        assertEquals("重启应沿用已存 currentKey（不接收调用方参数）", validKey, created.last().lastAuthKey)
    }

    /**
     * 探针⑤：30s 节流生效——连环拨号失败只重启一次（防重启风暴）。
     *
     * 场景：T=0 触发重启成功 → T=10s（<30s）再失败应被节流拦下 → T=35s（距上次重启
     * 35s > 30s）再失败应再次重启。注入假时钟推进节流窗口（TsnetWire.clockForTest）。
     *
     * 命中条件：10s 处不重启（startCount 不变），35s 处重启（startCount 递增）。
     */
    @Test
    fun `probe5 30s 节流 连环失败只重启一次`() {
        // 假时钟从真实墙钟基线起（lastRestartAtMs 初始 0L：now - 0 远大于节流窗口 ⇒
        // 首次失败立即重启，无幽灵节流）。用 epoch 基线才能模拟生产墙钟语义。
        var now = 1_000_000_000L
        val created = mutableListOf<CountingBackend>()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.backendFactory = { CountingBackend().also { created.add(it) } }
        TsnetWire.executorForTest = direct
        TsnetWire.clockForTest = { now }

        TsnetWire.ensureStarted(validKey)
        assertTrue(TsnetWire.state is TsnetState.Up)
        assertEquals(1, totalStarts(created))

        // 基线时刻：首次失败立即重启（now - lastRestartAtMs(0) 远超 30s，不过节流）。
        TsnetWire.notifySocksRouteFailure()
        assertEquals("首次失败应立即重启", 2, totalStarts(created))

        // +10s：距上次重启 10s < 30s 节流窗口，不得再重启。
        now += 10_000L
        TsnetWire.notifySocksRouteFailure()
        assertEquals("30s 内连环失败不应再重启（防风暴）", 2, totalStarts(created))

        // +35s：距上次重启 35s > 30s，节流窗口已过，允许再次重启。
        now += 25_000L
        TsnetWire.notifySocksRouteFailure()
        assertEquals("节流窗口过后可再次重启", 3, totalStarts(created))
    }

    /**
     * 探针⑥：官方 TS 并存不误触发——state==Idle 时 notifySocksRouteFailure 必须 no-op。
     *
     * 用户 B 路径（官方 Tailscale App + tailnet 地址直连）当前是好的，修复不得弄坏它：
     * 该路径下 App 内嵌 tsnet 处于 Idle（从未起网），任何 SOCKS 失败信号都不能干预
     * （无内嵌节点可重启，也不能凭空起网）。Error 态同样不干预（启动失败不构成自愈触发）。
     *
     * 命中条件：Idle/Error 态下调用 notifySocksRouteFailure，backend 工厂零次调用、state 不变。
     */
    @Test
    fun `probe6 官方 TS 并存 state 非 Up 不误触发`() {
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state", "agentmirror-test")
        TsnetWire.executorForTest = direct

        // 场景A：state==Idle（官方 Tailscale App 并存，内嵌 tsnet 从未起网）。
        var idleFactoryCalls = 0
        TsnetWire.backendFactory = {
            idleFactoryCalls++
            CountingBackend()
        }
        assertTrue("前置：未起网应为 Idle，实际=${TsnetWire.state}", TsnetWire.state is TsnetState.Idle)
        TsnetWire.notifySocksRouteFailure()
        assertEquals("Idle 态自愈不得创建 backend", 0, idleFactoryCalls)
        assertTrue("Idle 态不得改变状态", TsnetWire.state is TsnetState.Idle)

        // 场景B：state==Error（起网失败），失败信号同样不干预（非 Up 直接 return）。
        var errorFactoryCalls = 0
        TsnetWire.backendFactory = {
            errorFactoryCalls++
            ThrowingBackend()
        }
        TsnetWire.ensureStarted(validKey)
        assertTrue("前置：起网失败应入 Error，实际=${TsnetWire.state}", TsnetWire.state is TsnetState.Error)
        assertEquals(1, errorFactoryCalls)
        TsnetWire.notifySocksRouteFailure()
        assertEquals("Error 态自愈不得再创建 backend", 1, errorFactoryCalls)
        assertTrue("Error 态不得被失败信号改写", TsnetWire.state is TsnetState.Error)
    }

    // ---- 测试桩 ----

    /** 计数型假后端：start 计数 + 记录最近 key；每次 start 返回固定代理。 */
    private class CountingBackend : TsnetBackend {
        var startCount = 0
            private set
        var lastAuthKey: String? = null

        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
            startCount++
            lastAuthKey = authKey
            return TsnetProxy("127.0.0.1", 41111, "cred")
        }

        override fun close() = Unit
    }

    /** 场景B 用：起网必失败的后端（触发 Error 态）。 */
    private class ThrowingBackend : TsnetBackend {
        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy =
            throw IllegalStateException("synthetic start failure")

        override fun close() = Unit
    }

    private fun totalStarts(created: List<CountingBackend>): Int = created.sumOf { it.startCount }
}
