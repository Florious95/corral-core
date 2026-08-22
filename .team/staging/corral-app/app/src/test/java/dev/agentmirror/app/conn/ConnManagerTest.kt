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

package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重连策略 + 订阅簿记测试（docs/protocol.md §3 重连语义、004 无状态铁律）。
 *
 * 掉线 → 指数退避重连 → READY 后重新 list + 重放全部活跃 subscribe（快照重放）。
 * listing seq 不连续 / delta 先于 listing ⇒ 自动重新 list。input 以 input_ack 完结，
 * 超时 = 明确失败。上层只见 Flow/回调，不见 WS 细节。
 */
class ConnManagerTest {

    /** 管理器测试夹具：每次连接尝试出队一条新假传输，拨号脚本逐条消费。 */
    private class Harness(
        val clock: FakeClock = FakeClock(),
        val policy: ReconnectPolicy = ReconnectPolicy(baseMs = 1000, maxMs = 30000, random = { 0.5 }),
    ) {
        val transports = mutableListOf<FakeWebSocketTransport>()
        val listener = RecordingConnListener()

        /** 每条新传输的拨号脚本（默认全部成功）；按尝试顺序消费。 */
        val dialScripts = ArrayDeque<List<Boolean>>()

        lateinit var manager: ConnectionManager

        init {
            manager = ConnectionManager(
                config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
                transportFactory = TransportFactory {
                    val t = FakeWebSocketTransport()
                    t.dialScript = dialScripts.removeFirstOrNull() ?: listOf(true)
                    transports.add(t)
                    t
                },
                clock = clock,
                policy = policy,
            )
            manager.setListener(listener)
        }

        fun transport(): FakeWebSocketTransport = transports.last()
        fun start() = manager.start()
        fun dial(index: Int): FakeWebSocketTransport = transports[index]
    }

    private fun ready(t: FakeWebSocketTransport) {
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
    }

    private fun authAckSent(t: FakeWebSocketTransport): Boolean {
        // READY 前第一条上行是 auth；ready 后第一条是 list。
        return t.sentText.any { runCatching { FrameCodec.decode(it) }.getOrNull() is AuthFrame }
    }

    // ---- 启动与握手 ----

    @Test
    fun testStartAuthThenReady() {
        val h = Harness()
        h.start()
        // 假传输同步 onOpen ⇒ start() 返回时已进入 AUTHENTICATING（auth 已发出）。
        assertEquals(ConnectionState.AUTHENTICATING, h.manager.state())
        assertTrue(h.transport().sentText.any { runCatching { FrameCodec.decode(it) }.getOrNull() is AuthFrame })

        ready(h.transport())
        assertEquals(ConnectionState.READY, h.manager.state())
        // READY 后自动 list 拉全量（无状态恢复）。
        val sent = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        assertTrue(sent.any { it is ListFrame })
    }

    @Test
    fun testSubscribeBeforeReadyIsBookkeptAndReplayed() {
        val h = Harness()
        h.start()
        // READY 前订阅：记簿待重放，重连后必达。
        assertTrue(h.manager.subscribe("s1", 40, 100))
        assertTrue(h.manager.activeRefs().contains("s1"))

        ready(h.transport())
        // READY 后发送了 list + 重放的 subscribe。
        val sent = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        assertTrue(sent.any { it is ListFrame })
        val sub = sent.filterIsInstance<SubscribeFrame>()
        assertEquals(1, sub.size)
        assertEquals("s1", sub[0].ref)
    }

    // ---- 重连：退避 + auth/subscribe 重放 ----

    @Test
    fun testDropAfterReadyReconnectsWithReplay() {
        val h = Harness()
        h.start()
        ready(h.transport())
        h.manager.subscribe("s1", 40, 100)
        h.manager.subscribe("s2", 24, 80)
        // 清空已发送帧，观察重连后的重放。
        h.transport().sentText.clear()

        h.transport().peerClose(1006, "dropped")
        assertEquals(ConnectionState.RECONNECTING, h.manager.state())
        // 退避序列：attempt 0 → 1s（random=0.5 ⇒ 抖动 0）。
        assertEquals(listOf(0 to 1000L), h.listener.reconnectEvents)

        // 假时钟推进到点 → 重连；假传输同步 onOpen ⇒ 已进入 AUTHENTICATING。
        h.clock.advance(1000)
        h.manager.pump(h.clock.nowMs())
        assertEquals(ConnectionState.AUTHENTICATING, h.manager.state())

        val t2 = h.dial(1)
        ready(t2)
        assertEquals(ConnectionState.READY, h.manager.state())
        // 重连后：重新 auth + list + 重放全部活跃 subscribe。
        val sent = t2.sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        assertTrue(sent.any { it is AuthFrame })
        assertTrue(sent.any { it is ListFrame })
        val subs = sent.filterIsInstance<SubscribeFrame>().map { it.ref }.toSet()
        assertEquals(setOf("s1", "s2"), subs)
    }

    @Test
    fun testBackoffSequenceWithoutReady() {
        // 连续拨号失败：退避 1s → 2s → 4s → 8s（attempt 0..3）。
        val h = Harness()
        // 每条新传输的拨号脚本全部失败（auth 未发出 ⇒ 可重连）。
        repeat(4) { h.dialScripts.addLast(listOf(false)) }
        h.manager.start()
        assertEquals(1, h.transports.size) // 第一次拨号即失败

        val delays = mutableListOf<Long>()
        for (i in 1..3) {
            h.clock.advance(10_000)
            h.manager.pump(h.clock.nowMs())
        }
        // 首次拨号失败在 start() 时已上报 1s；每次重连又拨号失败。
        // 完整退避序列 1s → 2s → 4s → 8s（attempt 0..3）。
        val full = h.listener.reconnectEvents.map { it.second }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), full)
        assertEquals(4, h.transports.size)
    }

    @Test
    fun testBackoffCapsAt30s() {
        val h = Harness()
        // 连续拨号失败到退避逼近上限。
        repeat(12) { h.dialScripts.addLast(listOf(false)) }
        h.manager.start()
        val delays = mutableListOf<Long>()
        for (i in 1..11) {
            h.clock.advance(60_000)
            h.manager.pump(h.clock.nowMs())
            delays.add(h.listener.reconnectEvents.last().second)
        }
        // 上限 30s，不再涨。
        assertEquals(30_000L, delays.last())
        assertTrue(delays.max() <= 30_000L)
    }

    @Test
    fun testSuccessfulConnectResetsBackoff() {
        val h = Harness()
        // 第一次拨号失败 → 重连 → 第二次成功 → READY → 退避计数重置。
        h.dialScripts.addLast(listOf(false))
        h.dialScripts.addLast(listOf(true))
        h.manager.start()
        h.clock.advance(10_000)
        h.manager.pump(h.clock.nowMs())
        ready(h.transport())
        assertEquals(ConnectionState.READY, h.manager.state())

        // READY 后掉线：退避从 attempt 0 重新开始（1s）。
        // 首条 reconnect 事件来自最初的拨号失败（attempt 0 → 1s），
        // 成功 READY 后掉线也回到 attempt 0 → 1s；断言末条即证明计数已重置。
        h.transport().peerClose(1006, "dropped again")
        assertEquals(2, h.listener.reconnectEvents.size)
        assertEquals(0 to 1000L, h.listener.reconnectEvents.last())
    }

    @Test
    fun testNetworkAvailableSkipsWait() {
        val h = Harness()
        h.start()
        ready(h.transport())
        h.transport().peerClose(1006, "dropped")
        assertEquals(ConnectionState.RECONNECTING, h.manager.state())

        // 网络可达性变化：不等退避到点立即重连（同步 onOpen ⇒ 已到 AUTHENTICATING）。
        h.manager.onNetworkAvailable()
        assertEquals(ConnectionState.AUTHENTICATING, h.manager.state())
        assertEquals(2, h.transports.size)
    }

    // ---- listing seq 连续性 ----

    @Test
    fun testListingSetsSeq() {
        val h = Harness()
        h.start()
        ready(h.transport())
        // 服务端回 listing。
        h.transport().deliverText(
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":42,"workspaces":[]}}""",
        )
        assertTrue(h.listener.frames.any { it is ListingFrame && it.seq == 42L })
    }

    @Test
    fun testDeltaBeforeListingTriggersRelist() {
        val h = Harness()
        h.start()
        ready(h.transport())
        val before = h.transport().sentText.size
        // list_delta 先于 listing 到达 ⇒ 必须重新 list。
        h.transport().deliverText(
            """{"v":1,"type":"list_delta","payload":{"seq":1,"added_sessions":[]}}""",
        )
        // 重新 list 发出（且未把 delta 透传上去，因为不连续）。
        assertTrue(h.transport().sentText.size > before)
        assertTrue(h.listener.frames.none { it is ListDeltaFrame })
    }

    @Test
    fun testDeltaNonContinuousTriggersRelist() {
        val h = Harness()
        h.start()
        ready(h.transport())
        h.transport().deliverText(
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":42,"workspaces":[]}}""",
        )
        // seq 从 42 跳到 44：不连续 ⇒ 重新 list。
        val before = h.transport().sentText.size
        h.transport().deliverText(
            """{"v":1,"type":"list_delta","payload":{"seq":44,"added_sessions":[]}}""",
        )
        assertTrue(h.transport().sentText.size > before)
        assertTrue(h.listener.frames.none { it is ListDeltaFrame })
    }

    @Test
    fun testDeltaContinuousPassesThrough() {
        val h = Harness()
        h.start()
        ready(h.transport())
        h.transport().deliverText(
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":42,"workspaces":[]}}""",
        )
        // seq = 43 连续 ⇒ 透传。
        h.transport().deliverText(
            """{"v":1,"type":"list_delta","payload":{"seq":43,"added_sessions":[]}}""",
        )
        assertTrue(h.listener.frames.any { it is ListDeltaFrame && it.seq == 43L })
    }

    // ---- input 必达回执 ----

    @Test
    fun testInputAckSuccess() {
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.sendInput("s1", "/model opus"))
        val sent = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        val input = sent.filterIsInstance<InputFrame>().last()
        assertEquals("s1", input.ref)

        h.transport().deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":${input.reqId},"ok":true}}""",
        )
        assertEquals(1, h.listener.inputResults.size)
        val (reqId, ok, reason) = h.listener.inputResults[0]
        assertEquals(input.reqId, reqId)
        assertTrue(ok)
        assertEquals(null, reason)
    }

    @Test
    fun testInputAckFailureReason() {
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.sendInput("s1", "x"))
        val input = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
            .filterIsInstance<InputFrame>().last()

        h.transport().deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":${input.reqId},"ok":false,"reason":"session_not_found"}}""",
        )
        assertEquals(1, h.listener.inputResults.size)
        assertEquals(false, h.listener.inputResults[0].second)
        assertEquals("session_not_found", h.listener.inputResults[0].third)
    }

    @Test
    fun testInputKeysSendsKeysFrameWithoutText() {
        // R-1 快捷键条：sendInputKeys 发出 keys 帧（无 text、无附加回车），且与草稿同款
        // 必达回执（input_ack ok）。契约 §4.2：keys 不附加回车 = 快捷键条语义。
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.sendInputKeys("s1", InputKey.CTRL_C))
        val sent = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        val input = sent.filterIsInstance<InputFrame>().last()
        assertEquals("s1", input.ref)
        assertEquals("", input.text) // keys 帧不得带 text（互斥）
        assertEquals(listOf(InputKey.CTRL_C), input.keys)

        h.transport().deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":${input.reqId},"ok":true}}""",
        )
        assertEquals(1, h.listener.inputResults.size)
        val (reqId, ok, reason) = h.listener.inputResults[0]
        assertEquals(input.reqId, reqId)
        assertTrue(ok)
        assertEquals(null, reason)
    }

    @Test
    fun testInputKeysFailureReasonSurfaces() {
        // keys 帧失败回执同款必达可见：reason 上浮（003 发送必达）。
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.sendInputKeys("s1", InputKey.UP))
        val input = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
            .filterIsInstance<InputFrame>().last()
        h.transport().deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":${input.reqId},"ok":false,"reason":"session_not_found"}}""",
        )
        assertEquals(1, h.listener.inputResults.size)
        assertEquals(false, h.listener.inputResults[0].second)
        assertEquals("session_not_found", h.listener.inputResults[0].third)
    }

    @Test
    fun testInputKeysBeforeReadyFails() {
        val h = Harness()
        h.start()
        assertFalse(h.manager.sendInputKeys("s1", InputKey.TAB)) // 未就绪不可发送
    }

    @Test
    fun testInputTimeoutIsExplicitFailure() {
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.sendInput("s1", "slow"))
        // 超时：未收到 input_ack ⇒ 明确失败（timeout），不是静默。
        h.clock.advance(10_001)
        h.manager.resolveExpiredInputs(h.clock.nowMs())
        assertEquals(1, h.listener.inputResults.size)
        assertEquals(false, h.listener.inputResults[0].second)
        assertEquals("timeout", h.listener.inputResults[0].third)
    }

    @Test
    fun testInputDroppedConnectionFailsPending() {
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.sendInput("s1", "x"))
        // 掉线时未决输入一律判失败（不静默）。
        h.transport().peerClose(1006, "dropped")
        assertEquals(1, h.listener.inputResults.size)
        assertEquals(false, h.listener.inputResults[0].second)
        assertTrue(h.listener.inputResults[0].third!!.startsWith("connection lost"))
    }

    @Test
    fun testInputBeforeReadyFails() {
        val h = Harness()
        h.start()
        assertFalse(h.manager.sendInput("s1", "x")) // 未就绪不可发送
    }

    @Test
    fun testInputLateAckAfterTimeoutIgnored() {
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.sendInput("s1", "x"))
        val input = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
            .filterIsInstance<InputFrame>().last()

        h.clock.advance(10_001)
        h.manager.resolveExpiredInputs(h.clock.nowMs())
        assertEquals(1, h.listener.inputResults.size)

        // 迟到的 ack 不得重复上报。
        h.transport().deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":${input.reqId},"ok":true}}""",
        )
        assertEquals(1, h.listener.inputResults.size)
    }

    // ---- 停止与生命周期 ----

    @Test
    fun testStopPermanentClosesAndFailsPending() {
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.sendInput("s1", "x"))
        h.manager.stop()
        assertEquals(ConnectionState.STOPPED, h.manager.state())
        // 未决输入判失败。
        assertTrue(h.listener.inputResults.any { !it.second })
    }

    @Test
    fun testAuthRejectedStopsManager() {
        val h = Harness()
        h.start()
        // auth_ack ok:false ⇒ 永久关闭 ⇒ manager STOPPED。
        h.transport().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":false,"reason":"bad token"}}""")
        assertEquals(ConnectionState.STOPPED, h.manager.state())
        assertTrue(h.listener.reconnectEvents.isEmpty())
    }

    @Test
    fun testLocalDecodeErrorSurfacesFromManager() {
        val h = Harness()
        h.start()
        ready(h.transport())
        h.transport().deliverText("""{"v":1,"type":"unknown_thing","payload":{}}""")
        assertTrue(h.listener.decodeErrors.any { it.first == FrameError.UNSUPPORTED_TYPE })
    }

    @Test
    fun testBinaryFromManager() {
        val h = Harness()
        h.start()
        ready(h.transport())
        h.transport().deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, "s1", "screen".toByteArray())),
        )
        assertEquals(1, h.listener.binaries.size)
        assertEquals(BinaryKind.SNAPSHOT, h.listener.binaries[0].kind)
    }

    @Test
    fun testScrollbackAndResizeAfterReady() {
        val h = Harness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.scrollback("s1", -300, 100))
        assertTrue(h.manager.resize("s1", 48, 120))
        val sent = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        assertTrue(sent.any { it is ScrollbackFrame && it.fromLine == -300 && it.count == 100L })
        assertTrue(sent.any { it is ResizeFrame && it.rows == 48 && it.cols == 120 })
    }

    @Test
    fun testUnsubscribeIdempotent() {
        val h = Harness()
        h.start()
        ready(h.transport())
        h.manager.subscribe("s1", 40, 100)
        h.manager.unsubscribe("s1")
        assertTrue(h.manager.activeRefs().isEmpty())
        val sent = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        assertTrue(sent.any { it is UnsubscribeFrame && it.ref == "s1" })
    }
}
