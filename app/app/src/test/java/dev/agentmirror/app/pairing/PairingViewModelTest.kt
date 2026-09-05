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
        var failSave = false
        override fun load(): PairingConfig? = saved
        override fun save(config: PairingConfig) {
            if (failSave) error("keystore rejected fake-secret-material")
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
    private class Harness(
        /** feat-ts-wire：记录型起网入口（生产由 PairingRoute 接 TsnetWire.ensureStarted）。 */
        tsnetStarter: (String) -> Unit = {},
    ) {
        val store = FakeStore()
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        var nextConfig: ConnectionConfig? = null

        /** 下一次拨号脚本（默认成功）；见 [dialFails]。 */
        var nextDialScript: List<Boolean>? = null

        private val identityVerifier = object : HostIdentityVerifier {
            override fun whoami(endpoint: HostEndpoint) =
                HostCandidate("host-1234", "test", listOf(endpoint))

            override fun identify(
                endpoint: HostEndpoint,
                hostId: String?,
                token: String,
                legacyUrl: String?,
            ) = HostIdentifyResult.Proven(
                HostIdentity(hostId ?: "legacy-host", "test", endpoint, endpoint.authority),
            )
        }

        val vm = PairingViewModel(
            configStore = store,
            tsnetStarter = tsnetStarter,
            identifyClient = identityVerifier,
            connectionFactory = { cfg ->
                nextConfig = cfg
                val t = FakeWebSocketTransport()
                if (failingDial) {
                    t.dialScript = listOf(false)
                } else {
                    nextDialScript?.let { t.dialScript = it }
                }
                nextDialScript = null
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

        /** 让下一次配对拨号失败（模拟地址不可达——缺陷 A 的 TUN 地址场景）。 */
        fun dialFails() {
            nextDialScript = listOf(false)
        }

        /** 后续每次拨号都失败（候选逐试全败场景）。 */
        fun alwaysDialFails() {
            nextDialScript = null
            failingDial = true
        }

        /** 拨号脚本出队前若为真则整条脚本全 false（alwaysDialFails 的实现）。 */
        var failingDial = false

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

        /** 断言失败态并返回其 cause（配对状态机统一走 Failed(cause, message)）。 */
        fun failedCause(): PairingFailCause {
            val st = vm.pairingStatus
            assertTrue("expected Failed, got $st", st is PairingStatus.Failed)
            return (st as PairingStatus.Failed).cause
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
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC123","ts_authkey":""}""")
        // 进入试配对：配置已携带 URL+token。
        assertEquals("ws://192.168.1.5:9900/ws", h.nextConfig?.url)
        assertEquals("ABC123", h.nextConfig?.token)
        // auth 通过 → 成功 + 持久化。
        h.authOk()
        assertEquals(PairingStatus.Success, h.vm.pairingStatus)
        assertEquals("ws://192.168.1.5:9900/ws", h.store.saved?.url)
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
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws"}""")
        val st = h.vm.pairingStatus
        assertTrue(st is PairingStatus.Failed)
        assertTrue((st as PairingStatus.Failed).message.contains("token"))
    }

    @Test
    fun scanRejectsBadVersion() {
        val h = Harness()
        h.vm.onQrText("""{"v":2,"url":"ws://192.168.1.5:9900/ws","token":"ABC123"}""")
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

    // ---- feat-ts-wire 红测：ts_authkey 消费（011 预授权：扫码即入网；手填通道保留）----

    @Test
    fun scanWithTsAuthKeyStartsTsnetAndPersistsKey() {
        val started = mutableListOf<String>()
        val h = Harness(tsnetStarter = { started.add(it) })
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC123","ts_authkey":"tskey-qr-1"}""")
        // 扫码携带 authkey → 立即触发起网（011 扫码即入网，先于/伴随试配对）。
        assertEquals(listOf("tskey-qr-1"), started)
        // 红线：key 不回填手填输入框（QR 是唯一分发出口，不主动上屏）。
        assertEquals("", h.vm.manualTsAuthKey)
        // 配对成功 → key 随配置持久化（冷启动重连需要它重新起网）。
        h.authOk()
        assertEquals("tskey-qr-1", h.store.saved?.tsAuthKey)
    }

    @Test
    fun scanWithoutTsAuthKeyDoesNotStartTsnet() {
        val started = mutableListOf<String>()
        val h = Harness(tsnetStarter = { started.add(it) })
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC123","ts_authkey":""}""")
        // 无 key 降级：绝不触发起网（验收边界：降级不回退，LAN 路径零影响）。
        assertTrue(started.isEmpty())
        h.authOk()
        assertEquals("", h.store.saved?.tsAuthKey)
    }

    @Test
    fun manualTsAuthKeyStartsTsnetOnSubmit() {
        val started = mutableListOf<String>()
        val h = Harness(tsnetStarter = { started.add(it) })
        h.vm.manualUrl = "ws://192.168.1.5:9900/ws"
        h.vm.manualToken = "ABC123"
        h.vm.manualTsAuthKey = "  tskey-manual-1  "
        h.vm.submitManual()
        // 手填通道（FIELD 裁定：输入框接活）：提交时以 trim 后 key 起网。
        assertEquals(listOf("tskey-manual-1"), started)
        h.authOk()
        assertEquals("tskey-manual-1", h.store.saved?.tsAuthKey)
    }

    @Test
    fun manualEmptyTsAuthKeyDoesNotStartTsnet() {
        val started = mutableListOf<String>()
        val h = Harness(tsnetStarter = { started.add(it) })
        h.vm.manualUrl = "ws://192.168.1.5:9900/ws"
        h.vm.manualToken = "ABC123"
        h.vm.submitManual()
        assertTrue(started.isEmpty())
    }

    @Test
    fun tsnetStateSurfacesToObservable() {
        val h = Harness()
        // 起网状态可视（018 标准5）：外部（TsnetWire 监听）投递的状态落 observable。
        h.vm.onTsnetState(dev.agentmirror.app.tsnet.TsnetState.Starting)
        assertEquals(dev.agentmirror.app.tsnet.TsnetState.Starting, h.vm.tsState)
        val err = dev.agentmirror.app.tsnet.TsnetState.Error("入网失败")
        h.vm.onTsnetState(err)
        assertEquals(err, h.vm.tsState)
    }

    @Test
    fun manualTailnetWaitsForTsnetUpBeforeFirstDial() {
        lateinit var h: Harness
        h = Harness(tsnetStarter = {
            h.vm.onTsnetState(dev.agentmirror.app.tsnet.TsnetState.Starting)
        })
        h.vm.manualUrl = "ws://100.101.2.3:9900/ws"
        h.vm.manualToken = "ABC123"
        h.vm.manualTsAuthKey = "tskey-manual-1"

        h.vm.submitManual()

        // tailnet 首拨必须等节点真正 Up；Starting 期间不能先直拨制造一次假失败。
        assertTrue(h.transports.isEmpty())
        assertTrue(h.vm.pairingStatus is PairingStatus.Pairing)

        h.vm.onTsnetState(
            dev.agentmirror.app.tsnet.TsnetState.Up(
                dev.agentmirror.app.tsnet.TsnetProxy("127.0.0.1", 1080, "test-cred"),
            ),
        )

        assertEquals(1, h.transports.size)
        assertEquals("ws://100.101.2.3:9900/ws", h.nextConfig?.url)
    }

    @Test
    fun mixedCandidatesTryLanButDelayTailnetUntilTsnetUp() {
        lateinit var h: Harness
        h = Harness(tsnetStarter = {
            h.vm.onTsnetState(dev.agentmirror.app.tsnet.TsnetState.Starting)
        })
        h.dialFails()

        h.vm.onQrText(
            """{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC123","ts_authkey":"tskey-qr-1","candidates":["ws://192.168.1.5:9900/ws","ws://100.101.2.3:9900/ws"]}""",
        )

        // LAN 主选照常立即试且失败；tailnet 候选停在等待态，不在 Up 前直拨。
        assertEquals(1, h.transports.size)
        assertTrue(h.vm.pairingStatus is PairingStatus.Pairing)

        h.vm.onTsnetState(
            dev.agentmirror.app.tsnet.TsnetState.Up(
                dev.agentmirror.app.tsnet.TsnetProxy("127.0.0.1", 1080, "test-cred"),
            ),
        )

        assertEquals(2, h.transports.size)
        assertEquals("ws://100.101.2.3:9900/ws", h.nextConfig?.url)
    }

    @Test
    fun waitingTailnetFailsVisiblyOnTsnetErrorWithoutDial() {
        lateinit var h: Harness
        h = Harness(tsnetStarter = {
            h.vm.onTsnetState(dev.agentmirror.app.tsnet.TsnetState.Starting)
        })
        h.vm.manualUrl = "ws://100.101.2.3:9900/ws"
        h.vm.manualToken = "ABC123"
        h.vm.manualTsAuthKey = "tskey-manual-1"
        h.vm.submitManual()

        h.vm.onTsnetState(dev.agentmirror.app.tsnet.TsnetState.Error("control plane unreachable"))

        assertTrue(h.transports.isEmpty())
        assertEquals(PairingFailCause.UNREACHABLE, h.failedCause())
        assertTrue((h.vm.pairingStatus as PairingStatus.Failed).message.contains("入网失败"))
    }

    @Test
    fun retryAfterTsnetErrorRestartsNodeBeforeTailnetDial() {
        lateinit var h: Harness
        var starts = 0
        h = Harness(tsnetStarter = {
            starts++
            h.vm.onTsnetState(dev.agentmirror.app.tsnet.TsnetState.Starting)
        })
        h.vm.manualUrl = "ws://100.101.2.3:9900/ws"
        h.vm.manualToken = "ABC123"
        h.vm.manualTsAuthKey = "fake-auth-key"
        h.vm.submitManual()
        h.vm.onTsnetState(dev.agentmirror.app.tsnet.TsnetState.Error("control plane unreachable"))
        assertEquals(PairingFailCause.UNREACHABLE, h.failedCause())

        h.vm.retry()
        assertEquals(2, starts)
        assertTrue(h.transports.isEmpty())
        h.vm.onTsnetState(
            dev.agentmirror.app.tsnet.TsnetState.Up(
                dev.agentmirror.app.tsnet.TsnetProxy("127.0.0.1", 1080, "fake-cred"),
            ),
        )
        assertEquals(1, h.transports.size)
    }

    @Test
    fun secureStoreFailureBecomesFixedVisibleError() {
        val h = Harness()
        h.store.failSave = true
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC123"}""")
        h.authOk()

        assertEquals(PairingFailCause.PROTOCOL_ERROR, h.failedCause())
        val message = (h.vm.pairingStatus as PairingStatus.Failed).message
        assertTrue(message.contains("安全存储"))
        assertFalse(message.contains("fake-secret-material"))
        assertNull(h.vm.pendingConfig)
    }

    // ---- 扫码回填 + 拨号失败快反（fix-pairing-scan-flow 红测：修前红）----

    @Test
    fun scanAutoFillsManualFormForEditRetry() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC123","ts_authkey":""}""")
        // 缺陷 B 整改点③：识别值自动回填手填表单（url+token 落输入框），
        // 用户可改地址重试——正是绕过缺陷 A（TUN 地址不可达）的自救通路。
        assertEquals("ws://192.168.1.5:9900/ws", h.vm.manualUrl)
        assertEquals("ABC123", h.vm.manualToken)
    }

    @Test
    fun scanDialFailureSurfacesImmediatelyNotSilent() {
        val h = Harness()
        h.dialFails()
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC"}""")
        // 缺陷 B 整改点②：拨号失败（地址不可达）必须立即显式失败，
        // 而不是静默挂起 15 秒等超时（003 静默失效最高罪）。
        val st = h.vm.pairingStatus
        assertTrue(st is PairingStatus.Failed)
        assertTrue((st as PairingStatus.Failed).message.contains("不可达"))
        assertEquals(PairingFailCause.UNREACHABLE, (st as PairingStatus.Failed).cause)
        // 失败不落配置（已有配置不被污染）。
        assertNull(h.store.saved)
    }

    @Test
    fun scanImmediatelyAutoConnectsWithTargetUrlVisible() {
        val h = Harness()
        // 缺陷 B 整改点①：识别成功→立即自动发起配对并显示「连接中…」进度态（含目标地址）。
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC"}""")
        val st = h.vm.pairingStatus
        assertTrue("expected Pairing, got $st", st is PairingStatus.Pairing)
        assertEquals("ws://192.168.1.5:9900/ws", (st as PairingStatus.Pairing).targetUrl)
    }

    @Test
    fun failThenRetryReconnectsWithSameConfig() {
        val h = Harness()
        h.dialFails()
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC"}""")
        assertEquals(PairingFailCause.UNREACHABLE, h.failedCause())
        // 失败态可重试（非解析失败）：以失败时配置重拨（不重新解析 QR）。
        assertTrue(h.vm.canRetry)
        h.vm.retry()
        val st = h.vm.pairingStatus
        assertTrue("expected Pairing, got $st", st is PairingStatus.Pairing)
        assertEquals("ws://192.168.1.5:9900/ws", h.nextConfig?.url)
        assertEquals("ABC", h.nextConfig?.token)
    }

    @Test
    fun scannedFieldsEditableThenSubmitUsesEditedAddress() {
        val h = Harness()
        // 缺陷 A 自救通路：识别值回填手填表单 → 用户改地址 → 手填「连接」以新地址重试。
        h.vm.onQrText("""{"v":1,"url":"ws://198.18.0.1:9900/ws","token":"ABC","ts_authkey":""}""")
        assertEquals("ws://198.18.0.1:9900/ws", h.vm.manualUrl)
        assertEquals("ABC", h.vm.manualToken)
        h.vm.manualUrl = "ws://192.168.1.5:9900/ws"
        h.vm.submitManual()
        assertNull(h.vm.formError)
        assertEquals("ws://192.168.1.5:9900/ws", h.nextConfig?.url)
        assertEquals("ABC", h.nextConfig?.token)
        // 地址上屏、token 绝不上屏（§9 红线）。
        assertEquals("ws://192.168.1.5:9900/ws", h.vm.recognizedUrl)
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
        h.vm.manualUrl = "ws://192.168.1.5:1/ws"
        h.vm.manualToken = "   "
        h.vm.submitManual()
        assertTrue(h.vm.formError!!.contains("token"))
        assertNull(h.nextConfig)
    }

    // ---- 配对结果状态机 ----

    @Test
    fun authFailureSurfacesExplicitRejection() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"WRONG"}""")
        h.authReject("token_mismatch")
        // 拒绝须分类明确（003 失败可见 + 区分原因供 UI 指引）。
        assertEquals(PairingFailCause.REJECTED, h.failedCause())
        assertTrue((h.vm.pairingStatus as PairingStatus.Failed).message.contains("拒绝"))
        // 失败不落配置（已有配置不被污染）。
        assertNull(h.store.saved)
    }

    @Test
    fun pairTimeoutSurfacesExplicitFailure() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"SLOW"}""")
        h.tickPastPairTimeout()
        assertEquals(PairingFailCause.TIMEOUT, h.failedCause())
        assertNull(h.store.saved)
    }

    @Test
    fun resetReturnsToIdleWithoutReject() {
        val h = Harness()
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"X"}""")
        // 配对中 reset：不得误报"拒绝"（旧探针 stop 的 STOPPED 回调被短路）。
        h.vm.reset()
        assertEquals(PairingStatus.Idle, h.vm.pairingStatus)
        // 可立即重新配对。
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"Y"}""")
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
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"$secret"}""")
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

    // ---- candidates 候选逐试（fix-pairing-candidates：主选失败 → 自动逐试 → 首个可达即连）----

    @Test
    fun scanFailsPrimaryThenAutoTriesCandidatesSuccess() {
        val h = Harness()
        // 主选拨号失败（真实不可达路径：传输未建立即 onFailure → 可重连 → 推进逐试）。
        // 注意不能用 peerFailure：那模拟"已建立传输/auth 已发后掉线"，会被 Connection 判为
        // 永久关闭/拒绝（authSent=true → permanent），而非地址不可达推进候选。
        h.dialFails()
        h.vm.onQrText(
            """{"v":1,"url":"ws://192.168.1.6:1/ws","token":"T","ts_authkey":"","candidates":["ws://192.168.1.6:1/ws","ws://192.168.1.7:1/ws"]}""",
        )
        // 拨号失败→推进逐试全在 onQrText 内同步完成：返回时已自动推进到第二候选 good 且拨号成功。
        assertEquals(2, h.transports.size)
        assertEquals("ws://192.168.1.7:1/ws", h.nextConfig?.url)
        assertEquals("T", h.nextConfig?.token)
        // 候选 good：拨号成功 → auth_ack ok → 配对成功（FIELD.md 模拟器走查：错误主选+正确候选）。
        h.authOk()
        assertEquals(PairingStatus.Success, h.vm.pairingStatus)
        assertEquals("ws://192.168.1.7:1/ws", h.store.saved?.url)
        assertEquals("T", h.store.saved?.token)
    }

    @Test
    fun scanFailsPrimaryTimeoutAdvancesToNextCandidate() {
        val h = Harness()
        h.vm.onQrText(
            """{"v":1,"url":"ws://192.168.1.8:1/ws","token":"T","candidates":["ws://192.168.1.8:1/ws","ws://192.168.1.9:1/ws"]}""",
        )
        // 主选拨号成功但不回 auth → 超时（候选预算 3s）→ 自动推进逐试。
        h.clock.advance(3_001L)
        h.vm.onTick(h.clock.nowMs())
        assertEquals(2, h.transports.size)
        assertEquals("ws://192.168.1.9:1/ws", h.nextConfig?.url)
    }

    @Test
    fun allCandidatesFailShowsFullCandidateListAndCanRetryCandidate() {
        val h = Harness()
        h.alwaysDialFails()
        val candidates = """["ws://192.168.1.10:1/ws","ws://192.168.1.11:1/ws"]"""
        h.vm.onQrText(
            """{"v":1,"url":"ws://192.168.1.10:1/ws","token":"T","ts_authkey":"","candidates":$candidates}""",
        )
        // 逐试完两个候选（主选 a + 候选 b）全败 → 落最终失败态 + 候选列表可见。
        assertEquals(listOf("ws://192.168.1.10:1/ws", "ws://192.168.1.11:1/ws"), h.vm.candidateUrls)
        val st = h.vm.pairingStatus
        assertTrue("expected Failed, got $st", st is PairingStatus.Failed)
        assertTrue((st as PairingStatus.Failed).message.contains("候选"))
        assertEquals(PairingFailCause.UNREACHABLE, h.failedCause())
        assertNull(h.store.saved)
        // 失败后候选列表一键重试：点 b 单次试配（不复逐试序列）。先关掉全败拨号脚本，
        // 让候选 b 这次能拨通进入 Pairing；候选列表在单候选重试时保留可见。
        h.failingDial = false
        h.vm.retryCandidate("ws://192.168.1.11:1/ws")
        assertTrue(h.vm.pairingStatus is PairingStatus.Pairing)
        assertEquals("ws://192.168.1.11:1/ws", h.nextConfig?.url)
        assertEquals(listOf("ws://192.168.1.10:1/ws", "ws://192.168.1.11:1/ws"), h.vm.candidateUrls)
    }

    @Test
    fun failureClearsInProgressRecognitionText() {
        // leader 追加范围：失败态不得残留「正在连接」进行中文案。
        val h = Harness()
        h.dialFails()
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"T"}""")
        assertTrue(h.vm.pairingStatus is PairingStatus.Failed)
        assertNull(h.vm.recognizedUrl)
    }

    @Test
    fun noCandidatesKeepsLegacyBehavior() {
        // 无 candidates 的旧 QR：单次试配 + 15s 超时，失败即落失败态（前向兼容，契约 §2.1）。
        val h = Harness()
        h.alwaysDialFails()
        h.vm.onQrText("""{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"T"}""")
        assertEquals(1, h.transports.size)
        assertEquals(PairingFailCause.UNREACHABLE, h.failedCause())
        assertTrue(h.vm.candidateUrls.isEmpty())
    }

    @Test
    fun scanCandidatesDeduplicatedWithPrimary() {
        // 主选 + 候选重复项去重：逐试队列不重复拨同一地址。
        val h = Harness()
        h.alwaysDialFails()
        h.vm.onQrText(
            """{"v":1,"url":"ws://192.168.1.10:1/ws","token":"T","candidates":["ws://192.168.1.10:1/ws","ws://192.168.1.11:1/ws","ws://192.168.1.10:1/ws"]}""",
        )
        assertEquals(2, h.transports.size)
        assertEquals(listOf("ws://192.168.1.10:1/ws", "ws://192.168.1.11:1/ws"), h.vm.candidateUrls)
    }
}
