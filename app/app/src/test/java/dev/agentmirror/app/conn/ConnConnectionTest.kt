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
 * 单条 WebSocket 生命周期状态机测试（docs/protocol.md §3）。
 *
 * 握手：传输建立 → auth 上行 → auth_ack → READY。auth_ack ok:false / auth 后
 * 立即断开 ⇒ 视作拒绝（永久关闭）；READY 掉线 ⇒ 非永久（上层重连）；拨号失败 ⇒ 非永久。
 * 本地解码失败必须显式上浮（静默失效猎杀）。
 */
class ConnConnectionTest {

    private fun newConn(
        transport: FakeWebSocketTransport = FakeWebSocketTransport(),
        token: String = "tok",
        listener: Connection.Listener = RecordingConnectionListener(),
    ): Pair<Connection, RecordingConnectionListener> {
        val l = listener as RecordingConnectionListener
        return Connection(transport, token, l) to l
    }

    /** 记录型 Connection 监听。 */
    private class RecordingConnectionListener : Connection.Listener {
        val opened = mutableListOf<Boolean>()
        val readies = mutableListOf<Boolean>()
        val frames = mutableListOf<FramePayload>()
        val binaries = mutableListOf<BinaryFrame>()
        val decodeErrors = mutableListOf<Pair<FrameError, String>>()
        val closures = mutableListOf<Pair<Boolean, String>>()

        override fun onOpened() {
            opened.add(true)
        }

        override fun onReady() {
            readies.add(true)
        }

        override fun onFrame(frame: FramePayload) {
            frames.add(frame)
        }

        override fun onBinary(frame: BinaryFrame) {
            binaries.add(frame)
        }

        override fun onLocalDecodeError(code: FrameError, message: String) {
            decodeErrors.add(code to message)
        }

        override fun onClosed(permanent: Boolean, reason: String) {
            closures.add(permanent to reason)
        }
    }

    @Test
    fun testHappyPathAuthThenReady() {
        val t = FakeWebSocketTransport()
        val (conn, l) = newConn(t)
        conn.start()

        assertEquals(1, l.opened.size)
        assertEquals(1, t.sentText.size)
        // 建立后第一条就是 auth（token 一次性上行）。
        val auth = FrameCodec.decode(t.sentText[0]) as AuthFrame
        assertEquals("tok", auth.token)
        assertFalse(conn.isReady)

        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        assertEquals(1, l.readies.size)
        assertTrue(conn.isReady)
        // auth_ack 透传给上层。
        assertTrue(l.frames.any { it is AuthAckFrame && it.ok })
    }

    @Test
    fun testAuthRejectedIsPermanent() {
        val t = FakeWebSocketTransport()
        val (conn, l) = newConn(t)
        conn.start()
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":false,"reason":"bad token"}}""")

        assertFalse(conn.isReady)
        assertTrue(l.frames.any { it is AuthAckFrame && !it.ok })
        // 拒绝 ⇒ 永久关闭（S 随后立即关闭连接）。
        assertEquals(1, l.closures.size)
        val (permanent, _) = l.closures[0]
        assertTrue(permanent)
    }

    @Test
    fun testDropAfterAuthBeforeAckIsRejected() {
        // auth 后立即断开 = 视作拒绝（即使没收到 auth_ack），permanent。
        val t = FakeWebSocketTransport()
        val (conn, l) = newConn(t)
        conn.start()
        assertEquals(1, t.sentText.size) // auth 已发
        t.peerClose(1000, "server closed")

        assertTrue(l.closures.size == 1)
        assertTrue(l.closures[0].first)
    }

    @Test
    fun testDialFailureIsReconnectable() {
        val t = FakeWebSocketTransport().apply { dialScript = listOf(false) }
        val (conn, l) = newConn(t)
        conn.start()

        // 拨号失败（auth 未发出）⇒ 非永久关闭。
        assertEquals(1, l.closures.size)
        val (permanent, _) = l.closures[0]
        assertFalse(permanent)
        assertTrue(t.sentText.isEmpty())
    }

    @Test
    fun testDropAfterReadyIsReconnectable() {
        val t = FakeWebSocketTransport()
        val (conn, l) = newConn(t)
        conn.start()
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        assertTrue(conn.isReady)

        t.peerClose(1006, "abnormal closure")
        assertEquals(1, l.closures.size)
        assertFalse(l.closures[0].first) // READY 掉线 ⇒ 可重连
    }

    @Test
    fun testSendBeforeReadyFails() {
        val t = FakeWebSocketTransport()
        val (conn, _) = newConn(t)
        conn.start()
        // READY 前发送控制帧必须返回 false（调用方必须能判定失败）。
        assertFalse(conn.send(ListFrame(1)))
        assertFalse(conn.sendBinary(BinaryFrame(BinaryKind.DELTA, "s1", byteArrayOf(1))))
    }

    @Test
    fun testSendAfterReadyReachesTransport() {
        val t = FakeWebSocketTransport()
        val (conn, _) = newConn(t)
        conn.start()
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")

        assertTrue(conn.send(InputFrame(9, "s1", "hi")))
        val sent = FrameCodec.decode(t.sentText.last()) as InputFrame
        assertEquals(9L, sent.reqId)
        assertEquals("s1", sent.ref)

        // 二进制下发同样落到传输。
        assertTrue(conn.sendBinary(BinaryFrame(BinaryKind.SNAPSHOT, "s1", "xy".toByteArray())))
        assertEquals(1, t.sentBinary.size)
    }

    @Test
    fun testLocalDecodeErrorSurfaces() {
        val t = FakeWebSocketTransport()
        val (conn, l) = newConn(t)
        conn.start()
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")

        // 坏帧必须显式上浮，不静默。
        t.deliverText("""{"v":1,"type":"nope","payload":{}}""")
        assertTrue(l.decodeErrors.any { it.first == FrameError.UNSUPPORTED_TYPE })
        // 版本不匹配也必须上浮。
        t.deliverText("""{"v":9,"type":"list","payload":{"req_id":1}}""")
        assertTrue(l.decodeErrors.any { it.first == FrameError.UNSUPPORTED_VERSION })
        // 非法帧不得破坏连接（后续合法帧仍可达）。
        assertTrue(conn.isReady)
    }

    @Test
    fun testBinaryFrameRoutesToListener() {
        val t = FakeWebSocketTransport()
        val (conn, l) = newConn(t)
        conn.start()
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")

        val snapshot = BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, "s1", "screen".toByteArray()))
        t.deliverBinary(snapshot)
        assertEquals(1, l.binaries.size)
        assertEquals(BinaryKind.SNAPSHOT, l.binaries[0].kind)
        assertEquals("s1", l.binaries[0].ref)
    }

    @Test
    fun testCloseAfterReadyIsPermanent() {
        val t = FakeWebSocketTransport()
        val (conn, l) = newConn(t)
        conn.start()
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        conn.close()
        assertEquals(1, l.closures.size)
        assertTrue(l.closures[0].first) // 显式关闭 ⇒ 永久
    }
}
