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

package dev.agentmirror.app.notify

import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.notify.NotificationCodecTest.Companion.pageFrame
import dev.agentmirror.app.notify.NotificationCodecTest.Companion.record
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * 持久连接装饰器：同一条 WebSocket 上声明能力、截获通知帧、按页补历史；
 * 其余帧与旧服务端行为逐字节不变。
 */
class NotificationTransportTest {

    private class CoreListener : TransportListener {
        val texts = mutableListOf<String>()
        var opened = false
        var closed = false
        override fun onOpen() { opened = true }
        override fun onText(text: String) { texts += text }
        override fun onBinary(bytes: ByteArray) = Unit
        override fun onClosed(code: Int, reason: String) { closed = true }
        override fun onFailure(throwable: Throwable) { closed = true }
    }

    private val alerts = mutableListOf<NotificationRecord>()
    private val repository = NotificationRepository(null, Executor { it.run() })
    private val hub = NotificationHub(repository) { alerts += it }

    private fun connect(): Pair<FakeWebSocketTransport, CoreListener> {
        val inner = FakeWebSocketTransport()
        val core = CoreListener()
        val transport = NotificationTransport(inner) { hub }
        transport.start(core)
        assertTrue(core.opened)
        // Core 在 onOpen 里发 auth：经装饰器声明能力。
        assertTrue(transport.sendText("""{"v":1,"type":"auth","payload":{"token":"T"}}"""))
        return inner to core
    }

    private fun authAck(head: String, withCapability: Boolean = true) =
        if (withCapability) {
            """{"v":1,"type":"auth_ack","payload":{"ok":true,"capabilities":["notifications_v1"],""" +
                """"notification_state":{"host_id":"h","stream_id":"s","head_seq":"$head","retained_from_seq":"1"}}}"""
        } else {
            """{"v":1,"type":"auth_ack","payload":{"ok":true}}"""
        }

    private fun live(seq: String) = """{"v":1,"type":"notification","payload":${record(seq)}}"""

    private fun payload(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject

    @Test
    fun negotiatesBootstrapsHistoryPagesAndCommitsCursorOnlyAfterLastPage() {
        val (inner, core) = connect()
        val auth = payload(inner.sentText[0])
        assertEquals("T", auth["token"]!!.jsonPrimitive.content)
        assertTrue(auth.containsKey("capabilities"))

        inner.deliverText(authAck(head = "2"))
        assertEquals("auth_ack reaches Core first (READY)", 1, core.texts.size)
        assertEquals(NotificationSupport.Supported, hub.support.value)
        val first = payload(inner.sentText[1])
        assertFalse("first connection bootstraps without cursor", first.containsKey("cursor"))
        val reqId = first["req_id"]!!.jsonPrimitive.content.toLong()

        inner.deliverText(pageFrame(reqId, listOf(record("1")), next = "tok", head = "2"))
        val cont = payload(inner.sentText[2])
        assertEquals("tok", cont["page_token"]!!.jsonPrimitive.content)
        assertEquals(1, repository.items.value.size)

        inner.deliverText(pageFrame(cont["req_id"]!!.jsonPrimitive.content.toLong(), listOf(record("2")), next = null, head = "2"))
        assertEquals(3, inner.sentText.size)
        assertEquals(2, repository.items.value.size)
        assertTrue("history never rings", alerts.isEmpty())
        assertEquals("notification frames never reach Core", 1, core.texts.size)

        // 下一次连接从已确认游标增量同步。
        val (inner2, _) = connect()
        inner2.deliverText(authAck(head = "2"))
        val cursor = payload(inner2.sentText[1])["cursor"]!!.jsonObject
        assertEquals("s", cursor["stream_id"]!!.jsonPrimitive.content)
        assertEquals("2", cursor["seq"]!!.jsonPrimitive.content)
    }

    @Test
    fun liveFrame_alertsOnlyAboveHandshakeHeadAndOnlyOnce() {
        val (inner, core) = connect()
        inner.deliverText(authAck(head = "5"))
        inner.deliverText(live("5"))
        assertTrue("seq <= head is backlog, not a new event", alerts.isEmpty())
        inner.deliverText(live("6"))
        inner.deliverText(live("6"))
        assertEquals(listOf("id-6"), alerts.map { it.id })
        assertEquals(2, repository.items.value.size)
        assertEquals(1, core.texts.size)
    }

    @Test
    fun liveFrame_fromOtherSourceIsStoredWithoutAlert() {
        val (inner, _) = connect()
        inner.deliverText(authAck(head = "0"))
        inner.deliverText("""{"v":1,"type":"notification","payload":${record("9", hostId = "other")}}""")
        assertTrue(alerts.isEmpty())
        assertEquals(1, repository.items.value.size)
    }

    @Test
    fun oldServer_noCapability_noRequestsAndMarkedUnsupported() {
        val (inner, core) = connect()
        inner.deliverText(authAck(head = "0", withCapability = false))
        assertEquals(1, inner.sentText.size)
        assertEquals(1, core.texts.size)
        assertEquals(NotificationSupport.Unsupported, hub.support.value)
        inner.deliverText(live("1"))
        assertTrue("un-negotiated connection must not accept notifications", repository.items.value.isEmpty())
    }

    @Test
    fun pageForAnotherRequest_isIgnored() {
        val (inner, _) = connect()
        inner.deliverText(authAck(head = "1"))
        inner.deliverText(pageFrame(reqId = 999, items = listOf(record("1")), next = null))
        assertTrue(repository.items.value.isEmpty())
    }

    @Test
    fun closedLink_dropsLateFrames() {
        val (inner, core) = connect()
        inner.deliverText(authAck(head = "0"))
        inner.peerClose(1006, "gone")
        assertTrue(core.closed)
        inner.deliverText(live("1"))
        assertTrue(repository.items.value.isEmpty())
    }

    @Test
    fun withoutInstalledCenter_transportIsByteForBytePassThrough() {
        val inner = FakeWebSocketTransport()
        val core = CoreListener()
        val transport = NotificationTransport(inner) { null }
        transport.start(core)
        val auth = """{"v":1,"type":"auth","payload":{"token":"T"}}"""
        transport.sendText(auth)
        assertEquals(auth, inner.sentText.single())
        inner.deliverText(live("1"))
        assertEquals("without a store the frame goes to Core exactly as before", 1, core.texts.size)
    }
}
