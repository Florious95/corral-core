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

package dev.agentmirror.app.pairing

import androidx.compose.ui.test.junit4.createComposeRule
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 配对页时钟泵红测（fix-pairing-timeout-pump，红线5：配对超时永不触发）。
 *
 * **渲染真实 PairingScreen**，让生产泵自然驱动超时裁决，断言 Failed(TIMEOUT) 上屏可见
 * （StatusArea 直接渲染 status.message）。
 *
 * 修前红：当前 PairingScreen **无时钟泵**——全仓唯一 onTick 调用在 SessionScreen.kt:85
 * （那是 SessionViewModel 的），配对页无人调 onTick → PAIR_TIMEOUT_MS 永不裁决 →
 * 地址不可达/握手静默挂起时无限「连接中…」。本测修前必红：渲染后 status 仍是 Pairing，
 * 断言 Failed(TIMEOUT) 失败。
 *
 * 修后绿：PairingScreen 加 LaunchedEffect 时钟泵（与 SessionScreen.kt:85 同构）→
 * 配对开始后首拍 onTick 即裁决超时。
 *
 * **「测试假泵掩盖生产无泵」教训**：PairingViewModelTest 的超时用例
 * （pairTimeoutSurfacesExplicitFailure）走直接调 onTick 的测试通路——正因如此单测绿而
 * 生产死。本测渲染真实屏幕、由生产泵驱动（createComposeRule 只负责同步组合帧，不替泵），
 * 任何「只有测试在泵、生产无人泵」的假绿都被当场揭穿。
 *
 * 时序设计（Robolectric 定域：Compose 泵的 delay 重拍不随影子时钟推进，但首拍在组合帧
 * 落定即触发；System.currentTimeMillis 在 Robolectric 返回真实墙钟）：
 * 1. 先 onQrText 开始配对，VM 以注入时钟记录配对开始时刻（假时钟基准 1e6 ms）；
 * 2. 再 setContent 渲染屏幕：LaunchedEffect 泵启动，首拍调 onTick(System.currentTimeMillis)；
 * 3. 因假时钟基准远小于真实墙钟（约 1.7e12 ms），首拍 `now - pairingStartedAt >
 *    PAIR_TIMEOUT_MS` 恒成立，超时裁决确定性触发（生产两者同源，此处人为拉开使裁决
 *    无需真等 15s）。
 * 拨号成功但不回 auth → 连接停在 AUTHENTICATING，唯一收场路径就是超时。
 *
 * 基建用 [createComposeRule]（test-app-android-seams 引入，StateBadgeTest 同款）：与裸
 * `Looper.idle()` 不同，`waitForIdle()` 在 Robolectric 下正确同步组合帧与副作用——全量
 * 套件负载下裸 idle 可能漏跑泵的启动帧导致假红（实测）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingScreenClockPumpTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        /** 假时钟基准：远小于真实墙钟（真实 System.currentTimeMillis 约 1.7e12）。 */
        const val FakeNowBaseMs = 1_000_000L
    }

    @Test
    fun timeoutBecomesVisibleOnScreen() {
        val store = object : PairingConfigStore {
            var saved: PairingConfig? = null
            override fun load(): PairingConfig? = saved
            override fun save(config: PairingConfig) {
                saved = config
            }

            override fun clear() {
                saved = null
            }
        }
        val transports = mutableListOf<FakeWebSocketTransport>()
        val vm = PairingViewModel(
            configStore = store,
            connectionFactory = { cfg: ConnectionConfig ->
                val t = FakeWebSocketTransport() // 拨号成功但永不回 auth → 只剩超时能收场
                transports.add(t)
                ConnectionManager(cfg, TransportFactory { t })
            },
            nowMs = { FakeNowBaseMs },
        )

        // 先开始配对（记录配对开始时刻 = 假时钟基准），再渲染屏幕让泵首拍驱动超时。
        vm.onQrText("""{"v":1,"url":"ws://host:9900/ws","token":"T0K","ts_authkey":""}""")
        val start = vm.pairingStatus
        assertTrue("pairing should start, got $start", start is PairingStatus.Pairing)

        var paired: PairingConfig? = null
        compose.setContent {
            PairingScreen(viewModel = vm, onPaired = { paired = it }, onSkip = {})
        }
        // waitForIdle 同步组合帧：泵首拍 onTick(真实墙钟) → now - FakeNowBaseMs > PAIR_TIMEOUT_MS
        // → 超时裁决（生产泵真实驱动，非测试假泵）。
        compose.waitForIdle()

        // 断言超时失败上屏可见（红线5 失败可见；StatusArea 渲染 status.message 含「超时」）。
        val st = vm.pairingStatus
        assertTrue("expected Failed(TIMEOUT), got $st", st is PairingStatus.Failed)
        assertEquals(PairingFailCause.TIMEOUT, (st as PairingStatus.Failed).cause)
        assertTrue((st as PairingStatus.Failed).message.contains("超时"))
        // 失败不落配置、不误导航（无 Success → onPaired 不触发）。
        assertTrue(paired == null)
        assertTrue(store.saved == null)
    }
}
