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

package dev.agentmirror.app.service

import android.content.Context
import android.content.Intent
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.WebSocketTransport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 前台服务静默经济三态量测（工程红线第 1 条，feat-fg-service-wiring 自证）。
 *
 * 服务常驻期间，唯一的周期性背景工作是时钟泵（[MirrorForegroundService.pumpRunnable]，
 * 2s 一拍：重连到点判定 + 输入超时裁决）。三态口径参照服务端
 * `e2e/artifacts/test-api-user-scenarios-perf/baseline.json` 的三态（零连接 /
 * 已连接零订阅 / 已连接单订阅）：
 * - 零连接：manager 未创建（config 未注入）→ 泵单拍 `managerOrNull()` 为 null，零工作；
 * - 已连接零订阅：manager READY、无活跃订阅 → 泵单拍 pump()/resolveExpiredInputs() 均无待办；
 * - 已连接单订阅：manager READY、一个会话订阅 → 泵单拍同为零待办（有界，不随舰队规模线性增长）。
 *
 * 量测方式：直接驱动 [MirrorForegroundService.pumpOnce]（生产泵单拍同一逻辑），对每态跑
 * [ITERATIONS] 拍取 wall-clock 平均成本。子进程派生：App 侧无任何 ProcessBuilder/Runtime.exec
 * （代码审计，见证据），服务常驻期间子进程数为 0。
 *
 * 断言只锁定"有界"（单拍 < 1ms，实际远小于此）；实测数字写入交付证据的 economy_measurements。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundServiceEconomyTest {

    /** 脚本化拨号工厂：READY 态构造用（默认全部拨号成功）。 */
    private class ReadyTransportFactory : TransportFactory {
        val created = mutableListOf<FakeWebSocketTransport>()
        override fun create(url: String): WebSocketTransport {
            val t = FakeWebSocketTransport()
            created.add(t)
            return t
        }
    }

    /** ServiceWire.manager 需要的空壳监听。 */
    private object NoopListener : ConnectionManager.Listener {
        override fun onStateChanged(state: ConnectionState) = Unit
        override fun onFrame(frame: dev.agentmirror.app.conn.FramePayload) = Unit
        override fun onBinary(frame: dev.agentmirror.app.conn.BinaryFrame) = Unit
        override fun onLocalDecodeError(code: dev.agentmirror.app.conn.FrameError, message: String) = Unit
        override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
        override fun onReconnect(attempt: Int, delayMs: Long) = Unit
    }

    private var factory = ReadyTransportFactory()

    @Before
    fun resetServiceWire() {
        factory = ReadyTransportFactory()
        ServiceWire.transportFactory = factory
        ServiceWire.serviceListener = null
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    @After
    fun teardown() {
        try {
            MirrorForegroundService.stop(RuntimeEnvironment.getApplication())
        } catch (_: Exception) {
        }
        ServiceWire.serviceListener = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    /** 构造一个 READY 的共享连接（可选单订阅），供已连接两态测量。 */
    private fun readyConnectedManager(subscribeRef: String?): ConnectionManager {
        ServiceWire.setConfig(ConnectionConfig("ws://10.0.2.2:9900/ws", "tok-econ"))
        ServiceWire.transportFactory = factory
        val m = ServiceWire.manager(NoopListener)
        m.start()
        factory.created.last().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        assertEqualsLocal(ConnectionState.READY, m.state())
        subscribeRef?.let { m.subscribe(it, 24, 80) }
        return m
    }

    /** 构造服务并跑 N 拍泵单拍，返回 (总耗时 ns, 单拍均耗时 us)。 */
    private fun measurePump(iterations: Int, setup: () -> Unit): Pair<Long, Long> {
        setup()
        val controller = Robolectric.buildService(MirrorForegroundService::class.java)
        val svc = controller.create().startCommand(0, 1).get()
        // 预热（首拍含对象/类装载噪声，不计入）。
        repeat(100) { svc.pumpOnce(System.currentTimeMillis()) }
        val t0 = System.nanoTime()
        repeat(iterations) { svc.pumpOnce(System.currentTimeMillis()) }
        val elapsed = System.nanoTime() - t0
        controller.destroy()
        return elapsed to (elapsed / iterations / 1000)
    }

    @Test
    fun economy_threeStates_pumpCostIsBounded() {
        val iterations = 10_000

        // 态 1：零连接（config 未注入，manager 未创建 → 泵零工作）。
        ServiceWire.resetConfigForTest()
        val zeroConnection = measurePump(iterations) {}

        // 态 2：已连接零订阅。
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        val connectedZeroSub = measurePump(iterations) { readyConnectedManager(null) }

        // 态 3：已连接单订阅。
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        val connectedOneSub = measurePump(iterations) { readyConnectedManager("s1") }

        val summary = mapOf(
            "zero_connection" to zeroConnection,
            "connected_zero_sub" to connectedZeroSub,
            "connected_one_sub" to connectedOneSub,
        )

        // 断言：三态单拍均 < 1ms（实际 µs 级）——服务常驻的周期性 CPU 有界。
        // 每 2s 一拍：单拍 < 1ms ⇒ 常驻 CPU 占比 < 0.05%，随舰队规模不线性增长。
        for ((state, cost) in summary) {
            assertTrue(
                "$state 泵单拍成本必须有界（<1000us），实测=${cost.second}us",
                cost.second < 1000,
            )
        }
        // 打印实测数字（证据 economy_measurements 的直接来源）。
        println("ECONOMY_MEASUREMENTS_TOTAL_NS=$summary")
        println("ECONOMY_MEASUREMENTS_US=" + summary.mapValues { "${it.value.second}us/tick, ${it.value.first}ns/$iterations" })
    }

    private fun assertEqualsLocal(expected: ConnectionState, actual: ConnectionState) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
