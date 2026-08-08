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

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * PairingViewModel 测试：配对状态机（扫码/手填 → 试配对 → 成功持久化/明确失败）。
 *
 * 经验基（pairing-ui 知识基底 §4）：
 * - QR JSON 缺字段/坏版本拒绝；手填非法 url 校验；auth 失败明确报错；
 * - 配对成功持久化并路由（[PairingStatus.Success] + pendingConfig）；
 * - token 不进日志：错误文案恒不含 token 值（协议 §9 红线，阳性对照）。
 */
class PairingViewModelTest {

    /** 记录型假存储：断言 save/clear。 */
    private class FakeStore : PairingConfigStore {
        var saved: PairingConfig? = null
        var cleared = false
        override fun load(): PairingConfig? = saved
        override fun save(config: PairingConfig) {
            saved = config
        }

        override fun clear() {
            cleared = true
        }
    }

    /**
     * 测试夹具：脚本化拨号 + 假时钟的 VM。
     * 每开始一次配对，工厂创建一个连接（携带独立 FakeWebSocketTransport）。
     */
    private class Harness {
        val store = FakeStore()
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        var nextConfig: ConnectionConfig? = null

        val vm = PairingViewModel(
            configStore = store,
            connectionFactory = { cfg ->
                nextConfig = cfg
                val t = FakeWebSocketTransport()
                transports.add(t)
                ConnectionManager(
                    config = cfg,
                    transportFactory = TransportFactory { t },
                    clock = clock,
                )
            },
            nowMs = { clock.nowMs() },
        )

        fun lastTransport(): FakeWebSocketTransport = transports.last()

        /** 拨号成功 + auth_ack ok → READY（配对成功）。 */
        fun authOk() {
            lastTransport().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        }

        /** auth_ack ok:false → 拒绝（随后连接关闭）。 */
        fun authReject(reason: String = "token_mismatch") {
            lastTransport().deliverText(
                """{"v":1,"type":"auth_ack","payload":{"ok":false,"reason":"$reason"}}""",
            )
        }

        /** 推进假时钟越过配对超时并触发 onTick。 */
        fun tickPastPairTimeout() {
            clock.advance(PairTimeoutMs + 1)
            vm.onTick(clock.nowMs())
        }
    }

    companion object {
        /** 与 VM 内 PAIR_TIMEOUT_MS 对齐（测试驱动超时裁决）。 */
        const val PairTimeoutMs = 15_000L
    }

    // ---- QR 解析（扫码入口）----

    @Test
    fun scanValidQrPairsAndPersists() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://host:9900/ws","token":"ABC123","ts_authkey":""}""")
        // 进入试配对：配置已携带 URL+token。
        assertEquals("ws://host:9900/ws", h.nextConfig?.url)
        assertEquals("ABC123", h.nextConfig?.token)
        // auth 通过 → 成功 + 持久化。
        h.authOk()
        assertEquals(PairingStatus.Success, h.vm.pairingStatus)
        assertEquals("ws://host:9900/ws", h.store.saved?.url)
        assertEquals("ABC123", h.store.saved?.token)
    }

    @Test
    fun scanRejectsMalformedJson() {
        val h = Harness()
        h.vm.onQrText("not-json")
        val st = h.vm.pairingStatus
        assertTrue(st is PairingStatus.Failed)
        // 明确报错，不静默；未进入试配对（无配置、无连接）。
        assertNull(h.nextConfig)
        assertTrue(h.transports.isEmpty())
    }

    @Test
    fun scanRejectsMissingToken() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://host:9900/ws"}""")
        val st = h.vm.pairingStatus
        assertTrue(st is PairingStatus.Failed)
        assertTrue((st as PairingStatus.Failed).message.contains("token"))
    }

    @Test
    fun scanRejectsBadVersion() {
        val h = Harness()
        h.vm.onQrText("""{"v":2,"url":"ws://host:9900/ws","token":"ABC123"}""")
        val st = h.vm.pairingStatus
        assertTrue(st is PairingStatus.Failed)
        assertTrue((st as PairingStatus.Failed).message.contains("版本"))
    }

    @Test
    fun scanRejectsInvalidUrl() {
        val h = Harness()
        // http:// 前缀不是 ws 端点（协议 §1），拒绝。
        h.vm.onQrText("""{"v":1,"url":"http://host:9900/ws","token":"ABC123"}""")
        val st = h.vm.pairingStatus
        assertTrue(st is PairingStatus.Failed)
        assertTrue((st as PairingStatus.Failed).message.contains("地址"))
    }

    // ---- 手填（兜底入口）----

    @Test
    fun manualSubmitValidPairsAndPersists() {
        val h = Harness()
        h.vm.manualUrl = "ws://192.168.1.5:9900/ws"
        h.vm.manualToken = "MANUAL-T0K"
        h.vm.submitManual()
        assertNull(h.vm.formError)
        assertEquals("ws://192.168.1.5:9900/ws", h.nextConfig?.url)
        assertEquals("MANUAL-T0K", h.nextConfig?.token)
        h.authOk()
        assertEquals(PairingStatus.Success, h.vm.pairingStatus)
        assertEquals("MANUAL-T0K", h.store.saved?.token)
    }

    @Test
    fun manualRejectsInvalidUrl() {
        val h = Harness()
        h.vm.manualUrl = "htp://bad"
        h.vm.manualToken = "tok"
        h.vm.submitManual()
        assertTrue(h.vm.formError!!.contains("地址"))
        assertNull(h.nextConfig) // 未进入试配对
    }

    @Test
    fun manualRejectsBlankToken() {
        val h = Harness()
        h.vm.manualUrl = "ws://host:1/ws"
        h.vm.manualToken = "   "
        h.vm.submitManual()
        assertTrue(h.vm.formError!!.contains("token"))
        assertNull(h.nextConfig)
    }

    // ---- 配对结果状态机 ----

    @Test
    fun authFailureSurfacesExplicitRejection() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://host:9900/ws","token":"WRONG"}""")
        h.authReject("token_mismatch")
        val st = h.vm.pairingStatus
        assertTrue(st is PairingStatus.Failed)
        assertTrue((st as PairingStatus.Failed).message.contains("拒绝"))
        // 失败不落配置（已有配置不被污染）。
        assertNull(h.store.saved)
    }

    @Test
    fun pairTimeoutSurfacesExplicitFailure() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://host:9900/ws","token":"SLOW"}""")
        h.tickPastPairTimeout()
        val st = h.vm.pairingStatus
        assertTrue(st is PairingStatus.Failed)
        assertTrue((st as PairingStatus.Failed).message.contains("超时"))
        assertNull(h.store.saved)
    }

    @Test
    fun resetReturnsToIdleWithoutReject() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://host:9900/ws","token":"X"}""")
        // 配对中 reset：不得误报"拒绝"（旧探针 stop 的 STOPPED 回调被短路）。
        h.vm.reset()
        assertEquals(PairingStatus.Idle, h.vm.pairingStatus)
        // 可立即重新配对。
        h.vm.onQrText("""{"v":1,"url":"ws://host:9900/ws","token":"Y"}""")
        h.authOk()
        assertEquals(PairingStatus.Success, h.vm.pairingStatus)
        assertEquals("Y", h.store.saved?.token)
    }

    // ---- token 不落日志（协议 §9 红线，阳性对照）----

    @Test
    fun errorMessageNeverContainsTokenValue() {
        val h = Harness()
        // 用含特殊字符的 token 触发解析失败/拒绝，断言错误文案不含 token 原值。
        val secret = "S3CRET_T0K3N_!@#"
        h.vm.onQrText("""{"v":1,"url":"ws://host:9900/ws","token":"$secret"}""")
        h.authReject("token_mismatch")
        val msg = (h.vm.pairingStatus as PairingStatus.Failed).message
        assertFalse(msg.contains(secret))
    }

    // ---- 上传基地址推导（欠账②：ws → http）----

    @Test
    fun deriveUploadBaseMapsWsToHttp() {
        assertEquals("http://192.168.1.5:9900", deriveUploadBase("ws://192.168.1.5:9900/ws"))
        assertEquals("https://ts.host:443", deriveUploadBase("wss://ts.host:443/ws"))
    }
}
