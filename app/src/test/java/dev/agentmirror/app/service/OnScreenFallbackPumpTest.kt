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

import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 在屏兜底泵 Compose 红测（fix-app-runtime-sa ① 生产路径）。
 *
 * 渲染真实 [OnScreenFallbackPump]（挂在 AgentMirrorApp 根组合的同一组合），让生产泵自然
 * 首拍驱动共享连接（与 PairingScreenClockPumpTest 同款「测试假泵掩盖生产无泵」教训：这里
 * 测的是生产组合，不是直接调 [AppClockPump.fallbackPumpOnce] 的测试通路）。
 *
 * 两条红测：
 * 1. 服务泵不可用（[ServiceWire.servicePumpActive]=false）+ 组合在屏（RESUMED）⇒ 兜底泵
 *    首拍必须推进共享连接（退避到点即重拨）；
 * 2. 服务泵恢复（servicePumpActive=true）⇒ 兜底泵首拍必须让出（不重拨，不双泵）。
 *
 * 时序：Compose 泵的 delay 重拍不随 Robolectric 影子时钟推进，但**首拍在组合帧落定即触发**
 * （PairingScreenClockPumpTest 定域）；waitForIdle 同步组合帧与副作用。nowMs 注入
 * `System.currentTimeMillis() + 60_000` 保证越过退避截止，重拨判定确定性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnScreenFallbackPumpTest {

    @get:Rule
    val compose = createComposeRule()

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

    /** 构造拨号失败 → 退避重连的共享连接，返回 (manager, scripted)。 */
    private fun reconnectManager(): Pair<ConnectionManager, ScriptedTransportFactory> {
        val scripted = ScriptedTransportFactory()
        scripted.dialScripts.addLast(false) // 首次拨号失败 → RECONNECTING + pendingReconnectAt
        scripted.dialScripts.addLast(true)  // 兜底泵推进重拨成功
        ServiceWire.transportFactory = scripted
        ServiceWire.setConfig(ConnectionConfig("ws://10.0.2.2:9900/ws", "tok-pump-ui"))
        val m = ServiceWire.manager(NoopListener)
        m.start()
        assertEquals("首次拨号失败必须进入退避重连", ConnectionState.RECONNECTING, m.state())
        assertEquals(1, scripted.created.size)
        return m to scripted
    }

    /** 在屏渲染生产兜底泵：nowMs 注入越过退避截止，首拍重拨判定确定性。 */
    private fun renderFallbackPump() {
        compose.setContent {
            OnScreenFallbackPump(nowMs = { System.currentTimeMillis() + 60_000 })
        }
        compose.waitForIdle()
    }

    @Test
    fun screenVisible_serviceInactive_fallbackPumpDrivesReconnect() {
        // 红测：服务泵不可用 + 在屏组合 ⇒ 生产兜底泵首拍必须推进共享连接（重拨）。
        // 修前无兜底泵：服务被杀后前台界面无泵，退避到点无人推进 → 断言重拨红。
        val (_, scripted) = reconnectManager()
        assertFalse("前置：服务泵未在跑", ServiceWire.servicePumpActive)

        renderFallbackPump()

        assertEquals(
            "在屏兜底泵必须在服务不可用时推进重连（重拨 2nd 拨号）",
            2,
            scripted.created.size,
        )
    }

    @Test
    fun screenVisible_serviceActive_fallbackPumpYields_noDoublePump() {
        // 红测：服务泵恢复（servicePumpActive=true）⇒ 在屏兜底泵首拍必须让出（不重拨）。
        // 双泵会让 UI 抖动并白烧 CPU（静默经济红线），断言重拨次数不增。
        val (_, scripted) = reconnectManager()
        ServiceWire.servicePumpActive = true // 服务 onStartCommand 置位（服务泵在跑）

        renderFallbackPump()

        assertTrue("前置：服务泵在跑", ServiceWire.servicePumpActive)
        assertEquals(
            "服务泵在跑时在屏兜底泵必须让出（不得双泵）",
            1,
            scripted.created.size,
        )
    }
}
