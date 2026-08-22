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

import dev.agentmirror.app.conn.TransportListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener as OkWebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * OkHttpWebSocketTransport 测试（leader 裁定 A②，MockWebServer 驱动真实 OkHttp 栈）。
 *
 * 覆盖：连接成功 + auth 帧往返、binary 透传、服务端关闭上抛、拨号失败上抛。
 * 每个用例用独立 [MockWebServer] + 记录型监听；终结回调必须到达且不双发（静默吞错最高罪）。
 */
class OkHttpWebSocketTransportTest {

    private lateinit var server: MockWebServer

    private lateinit var client: OkHttpClient

    private class RecordingTransportListener : TransportListener {
        val events = LinkedBlockingQueue<String>()
        val binaries = mutableListOf<ByteArray>()

        override fun onOpen() {
            events.add("open")
        }

        override fun onText(text: String) {
            events.add("text:$text")
        }

        override fun onBinary(bytes: ByteArray) {
            binaries.add(bytes)
            events.add("binary:${bytes.size}")
        }

        override fun onClosed(code: Int, reason: String) {
            events.add("closed:$code:$reason")
        }

        override fun onFailure(throwable: Throwable) {
            events.add("failure:${throwable.message}")
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun wsUrl(): String = server.url("/ws").toString().replaceFirst("http", "ws")

    /** 让服务端接受一次 WS 升级：收到 text 回 text、收到 binary 回 binary（echo）；收到关闭回 close 完成握手。 */
    private fun enqueueEchoUpgrade() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : OkWebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        webSocket.send(bytes)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // 收到客户端关闭帧：回 close 完成握手，客户端 onClosed 才能到达。
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
    }

    @Test
    fun connectsSendsAuthReceivesAck() {
        enqueueEchoUpgrade()
        val listener = RecordingTransportListener()
        val transport = OkHttpWebSocketTransport(wsUrl(), client)
        transport.start(listener)
        // 拨号成功 → onOpen。
        assertEquals("open", listener.events.poll(3, TimeUnit.SECONDS))
        assertTrue(transport.isOpen)
        // 送 auth 帧（text）→ 服务端 echo → onText 收到同一内容（auth_ack 往返语义）。
        val authFrame = """{"v":1,"type":"auth","payload":{"token":"t"}}"""
        assertTrue(transport.sendText(authFrame))
        assertEquals("text:$authFrame", listener.events.poll(3, TimeUnit.SECONDS))
        // 主动关闭：close() → 终结回调到达（closed）。
        transport.close("done")
        val closed = listener.events.poll(3, TimeUnit.SECONDS) ?: ""
        assertTrue(closed.startsWith("closed:"))
        assertFalse(transport.isOpen)
    }

    @Test
    fun binaryFramePassesThrough() {
        enqueueEchoUpgrade()
        val listener = RecordingTransportListener()
        val transport = OkHttpWebSocketTransport(wsUrl(), client)
        transport.start(listener)
        assertEquals("open", listener.events.poll(3, TimeUnit.SECONDS))
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x7F.toByte())
        assertTrue(transport.sendBinary(payload))
        // 服务端 echo binary → onBinary 原样收到。
        val ev = listener.events.poll(3, TimeUnit.SECONDS) ?: ""
        assertTrue(ev.startsWith("binary:"))
        assertEquals(4, listener.binaries.firstOrNull()?.size)
    }

    @Test
    fun serverCloseSurfacesOnClosed() {
        enqueueEchoUpgrade()
        val listener = RecordingTransportListener()
        val transport = OkHttpWebSocketTransport(wsUrl(), client)
        transport.start(listener)
        assertEquals("open", listener.events.poll(3, TimeUnit.SECONDS))
        // 服务端主动关闭：shutdown 关闭连接 → 客户端必须收到终结回调（closed 或 failure）。
        server.shutdown()
        val closed = listener.events.poll(3, TimeUnit.SECONDS) ?: ""
        assertTrue(closed.startsWith("closed:") || closed.startsWith("failure:"))
        assertFalse(transport.isOpen)
    }

    @Test
    fun dialFailureSurfacesOnFailure() {
        // 拨号失败：server 已停，指向已停端口的 URL 拨号必失败。
        val url = server.url("/ws").toString().replaceFirst("http", "ws")
        server.shutdown()
        val listener = RecordingTransportListener()
        val transport = OkHttpWebSocketTransport(url, client)
        transport.start(listener)
        val event = listener.events.poll(3, TimeUnit.SECONDS) ?: ""
        assertTrue(event.startsWith("failure:"))
        assertFalse(transport.isOpen)
    }

    @Test
    fun sendBeforeOpenReturnsFalse() {
        // 未 open 时 send 返回 false，可判定（静默失败猎杀）。
        val listener = RecordingTransportListener()
        val transport = OkHttpWebSocketTransport(wsUrl(), client)
        assertFalse(transport.sendText("x"))
        assertFalse(transport.sendBinary(byteArrayOf(1)))
    }
}
