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

import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.WebSocketTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 在屏兜底时钟泵红测（fix-app-runtime-sa ①）。
 *
 * 缺陷现场（阶段三复核 w-stage3-verify 记的 medium gap）：feat-fg-service-wiring 把时钟泵
 * 改为单归属前台服务（[MirrorForegroundService.pumpOnce]，2s 一拍）后，服务被杀时即使 App
 * 在前台也没有泵，界面停止更新——接线前泵由在屏组合 LaunchedEffect 驱动、前台恒有泵，这是
 * 功能回退（踩 004 自检标准：删掉前台服务这一层产品功能应仍完整，只是后台期间体验降级）。
 *
 * 修前红：当前无 [AppClockPump]/[OnScreenFallbackPump]/[ServiceWire.servicePumpActive]，
 * 引用即编译红；即使服务未启动，兜底泵也不存在，断连退避到点无人推进 → 断言重拨红。
 *
 * 三条红测断言：
 * 1. 「服务不可用 + 前台在屏 ⇒ 泵仍在跑」——[AppClockPump.serviceActive] 为 false 时
 *    [AppClockPump.fallbackPumpOnce] 必须推进共享连接（越过退避截止即重拨）；
 * 2. 「服务恢复 ⇒ 不出现双泵」——[ServiceWire.servicePumpActive] 置位（服务泵在跑）时
 *    兜底泵单拍必须让出（零工作，不触发重拨；双泵会让 UI 抖动并白烧 CPU，撞静默经济红线）；
 * 3. 泵归属标记随服务生命周期：onStartCommand 置位 / onDestroy 复位（兜底泵接管判据）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppClockPumpTest {

    /** 脚本化拨号工厂：供"泵推进重连重拨"的确定性断言（与 ForegroundServiceWiringTest 同款）。 */
    private class ScriptedTransportFactory : TransportFactory {
        val created = mutableListOf<FakeWebSocketTransport>()
        val dialScripts = ArrayDeque<Boolean>()
        override fun create(url: String): WebSocketTransport {
            val t = FakeWebSocketTransport()
            t.dialScript = listOf(dialScripts.removeFirstOrNull() ?: true)
            created.add(t)
            return t
        }
    }

    /** ServiceWire.manager 需要的空壳监听（事件经 serviceListener/uiConnector 槽扇出）。 */
    private object NoopListener : ConnectionManager.Listener {
        override fun onStateChanged(state: ConnectionState) = Unit
        override fun onFrame(frame: FramePayload) = Unit
        override fun onBinary(frame: BinaryFrame) = Unit
        override fun onLocalDecodeError(code: FrameError, message: String) = Unit
        override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
        override fun onReconnect(attempt: Int, delayMs: Long) = Unit
    }

    @Before
    fun resetServiceWire() {
        ServiceWire.servicePumpActive = false
        ServiceWire.serviceListener = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    @After
    fun teardown() {
        // 停掉可能残留的前台服务（stopService → onDestroy 复位 servicePumpActive、释放连接）。
        try {
            MirrorForegroundService.stop(RuntimeEnvironment.getApplication())
        } catch (_: Exception) {
        }
        ServiceWire.servicePumpActive = false
        ServiceWire.serviceListener = null
        ServiceWire.transportFactory = NoopTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
    }

    /** 构造拨号失败 → 退避重连（RECONNECTING）的共享连接；返回 (manager, scripted)。 */
    private fun reconnectManager(): Pair<ConnectionManager, ScriptedTransportFactory> {
        val scripted = ScriptedTransportFactory()
        scripted.dialScripts.addLast(false) // 首次拨号失败 → 调度退避（attempt 0，pendingReconnectAt）
        scripted.dialScripts.addLast(true)  // 泵推进重拨成功
        ServiceWire.transportFactory = scripted
        ServiceWire.setConfig(ConnectionConfig("ws://10.0.2.2:9900/ws", "tok-pump"))
        val m = ServiceWire.manager(NoopListener)
        m.start()
        assertEquals("首次拨号失败必须进入退避重连", ConnectionState.RECONNECTING, m.state())
        assertEquals(1, scripted.created.size)
        return m to scripted
    }

    // ---- 红测 1：服务不可用 + 前台在屏 ⇒ 泵仍在跑 ----

    @Test
    fun serviceInactive_fallbackPump_drivesReconnect() {
        // 红测：服务未启动（serviceActive=false）且 App 在前台 → 兜底泵必须推进共享连接。
        // 修前无兜底泵：退避到点无人推进，重拨永不发生 → 断言重拨红。
        val (_, scripted) = reconnectManager()
        assertFalse("前置：服务泵未在跑（服务未启动）", AppClockPump.serviceActive())

        // 越过退避截止的 now → 兜底泵单拍必须触发重拨（泵仍在跑）。
        AppClockPump.fallbackPumpOnce(System.currentTimeMillis() + 60_000)
        assertEquals("服务不可用 + 前台在屏 ⇒ 兜底泵仍推进重连", 2, scripted.created.size)
    }

    // ---- 红测 2：服务恢复 ⇒ 不出现双泵 ----

    @Test
    fun serviceActive_fallbackPump_yields_noDoublePump() {
        // 红测：服务泵恢复（servicePumpActive=true）后，兜底泵单拍必须让出（零工作）。
        // 若兜底泵不判归属、与服务泵并行拍，这里会重复重拨 → 断言重拨次数红（双泵）。
        val (_, scripted) = reconnectManager()
        ServiceWire.servicePumpActive = true // 服务 onStartCommand 置位（服务泵在跑）

        AppClockPump.fallbackPumpOnce(System.currentTimeMillis() + 60_000)
        assertEquals(
            "服务泵在跑时兜底泵必须让出（不得双泵重复拍）",
            1,
            scripted.created.size,
        )
    }

    // ---- 红测 3：泵归属标记随服务生命周期（接管/让出判据） ----

    @Test
    fun servicePumpActive_setOnStartCommand_resetOnDestroy() {
        // 红测：服务启动必须置位 servicePumpActive（兜底泵让出）、销毁必须复位（兜底泵接管）。
        // 若标记不随服务生命周期走，兜底泵接管/让出判据失灵（双泵或前台无泵）。
        ServiceWire.setConfig(ConnectionConfig("ws://10.0.2.2:9900/ws", "tok-pump-flag"))
        val controller = Robolectric.buildService(MirrorForegroundService::class.java)
        controller.create().startCommand(0, 1)
        assertTrue(
            "服务 onStartCommand 必须置位 servicePumpActive（兜底泵让出判据）",
            ServiceWire.servicePumpActive,
        )
        controller.destroy()
        assertFalse(
            "服务 onDestroy 必须复位 servicePumpActive（兜底泵接管判据）",
            ServiceWire.servicePumpActive,
        )
    }

    // ---- 防御：manager 未创建时零工作不崩（静默经济：无连接无待办） ----

    @Test
    fun fallbackPump_nullManager_isNoop() {
        // 无连接（manager 未创建）：兜底泵单拍零工作、不抛异常（常驻泵空闲态有界）。
        AppClockPump.pumpManager(System.currentTimeMillis())
        AppClockPump.fallbackPumpOnce(System.currentTimeMillis())
    }
}
