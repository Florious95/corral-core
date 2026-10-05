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

package dev.agentmirror.app.conversation

import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConversationHubTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val hub = ConversationHub(executor, mainPost = { it.run() }, nowMs = { 1000L })

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun settle() {
        // Two hops: a callback may schedule a drain behind itself.
        repeat(2) { executor.submit {}.get(5, TimeUnit.SECONDS) }
    }

    /** In-memory socket under the decorator: records what reaches the wire. */
    private class FakeSocket : WebSocketTransport {
        val sent = mutableListOf<String>()
        lateinit var listener: TransportListener
        override val isOpen = true
        override fun start(listener: TransportListener) { this.listener = listener }
        override fun sendText(text: String): Boolean { sent += text; return true }
        override fun sendBinary(bytes: ByteArray) = true
        override fun close(reason: String) = Unit
    }

    private class CoreSpy : TransportListener {
        val texts = mutableListOf<String>()
        override fun onOpen() = Unit
        override fun onText(text: String) { texts += text }
        override fun onBinary(bytes: ByteArray) = Unit
        override fun onClosed(code: Int, reason: String) = Unit
        override fun onFailure(throwable: Throwable) = Unit
    }

    private fun connect(): Triple<FakeSocket, ConversationTransport, CoreSpy> {
        val socket = FakeSocket()
        val transport = ConversationTransport(socket, hub)
        val core = CoreSpy()
        transport.start(core)
        transport.sendText("""{"v":1,"type":"auth","payload":{"token":"t","capabilities":["notifications_v1"]}}""")
        socket.listener.onText("""{"v":1,"type":"auth_ack","payload":{"ok":true,"capabilities":["notifications_v1","conversation_v1"]}}""")
        settle()
        return Triple(socket, transport, core)
    }

    private fun frames(socket: FakeSocket, type: String) =
        socket.sent.map { Json.parseToJsonElement(it).jsonObject }.filter { it["type"]!!.jsonPrimitive.content == type }

    @Test
    fun authIsDecoratedOnTheSameSocketAndCoreStillSeesItsFrames() {
        val (socket, _, core) = connect()
        val caps = Json.parseToJsonElement(socket.sent.first()).jsonObject["payload"]!!.jsonObject["capabilities"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("notifications_v1", "conversation_v1"), caps)
        assertEquals(ConversationSupport.Supported, hub.support.value)
        assertTrue(core.texts.single().contains("auth_ack"))
    }

    @Test
    fun readyEventsLossAndReconnectResumeAfterLastSeq() {
        val (socket, _, core) = connect()
        hub.attach("r1")
        settle()
        assertEquals(1, frames(socket, "conversation_subscribe").size)
        socket.listener.onText("""{"v":1,"type":"conversation_ready","payload":{"ref":"r1","stream":"s1","head_seq":2,"reset":true,"history_truncated":false,"running":true,"server_time_ms":1}}""")
        socket.listener.onText("""{"v":1,"type":"conversation_event","payload":{"ref":"r1","seq":1,"ts":5,"event":{"type":"message_start","message":{"role":"assistant","content":[],"timestamp":1}}}}""")
        socket.listener.onText("""{"v":1,"type":"conversation_event","payload":{"ref":"r1","seq":2,"ts":6,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hel"}}}}""")
        socket.listener.onText("""{"v":1,"type":"conversation_event","payload":{"ref":"r1","seq":3,"ts":7,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"lo"}}}}""")
        settle()
        val session = hub.session("r1")
        assertEquals(LinkPhase.Live, session.phase.value)
        assertTrue(session.everReady)
        assertEquals("Hello", (session.state.value.items.single() as AssistantText).text)
        assertEquals(3L, session.state.value.lastSeq)
        assertTrue("conversation frames never reach Core", core.texts.none { it.contains("\"type\":\"conversation_") })
        val sys = frames(socket, "conversation_command").map { it["payload"]!!.jsonObject["command"]!!.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("get_state", "get_commands"), sys)

        // The physical socket drops: reconnecting, never a capability loss.
        socket.listener.onFailure(RuntimeException("network"))
        settle()
        assertEquals(LinkPhase.Reconnecting, session.phase.value)
        assertTrue(session.everReady)

        val (next, _, _) = connect()
        val resume = frames(next, "conversation_subscribe").single()["payload"]!!.jsonObject
        assertEquals("s1", resume["stream"]!!.jsonPrimitive.content)
        assertEquals("3", resume["after_seq"]!!.jsonPrimitive.content)
    }

    @Test
    fun neverReadyUnavailableFallsBackButProvenSessionEndsInPlace() {
        val (socket, _, _) = connect()
        hub.attach("plain")
        settle()
        socket.listener.onText("""{"v":1,"type":"conversation_closed","payload":{"ref":"plain","reason":"unavailable"}}""")
        hub.attach("managed")
        settle()
        socket.listener.onText("""{"v":1,"type":"conversation_ready","payload":{"ref":"managed","stream":"s","head_seq":0,"reset":true,"history_truncated":false,"running":false,"server_time_ms":1}}""")
        socket.listener.onText("""{"v":1,"type":"conversation_closed","payload":{"ref":"managed","reason":"exited"}}""")
        settle()
        assertEquals(LinkPhase.Unavailable, hub.session("plain").phase.value)
        assertFalse(hub.session("plain").everReady)
        assertEquals(LinkPhase.Ended, hub.session("managed").phase.value)
    }

    @Test
    fun promptEchoesImmediatelyAndSettlesOnResponse() {
        val (socket, _, _) = connect()
        hub.attach("r1")
        settle()
        socket.listener.onText("""{"v":1,"type":"conversation_ready","payload":{"ref":"r1","stream":"s","head_seq":0,"reset":true,"history_truncated":false,"running":false,"server_time_ms":1}}""")
        settle()
        var result: Pair<Boolean, String?>? = null
        hub.send("r1", "prompt", "hi") { ok, reason -> result = ok to reason }
        settle()
        val echo = hub.session("r1").state.value.items.single() as UserTurn
        assertEquals(Delivery.Sending, echo.delivery)
        val id = frames(socket, "conversation_command").last()["payload"]!!.jsonObject["id"]!!.jsonPrimitive.content
        socket.listener.onText("""{"v":1,"type":"conversation_event","payload":{"ref":"r1","event":{"id":"$id","type":"response","command":"prompt","success":false,"error":"busy"}}}""")
        settle()
        assertEquals(false to "busy", result)
        assertTrue(hub.session("r1").state.value.items.isEmpty())
    }

    @Test
    fun multilinePromptIsOneCommandWithEveryLine() {
        val (socket, _, _) = connect()
        hub.attach("r1")
        settle()
        socket.listener.onText("""{"v":1,"type":"conversation_ready","payload":{"ref":"r1","stream":"s","head_seq":0,"reset":true,"history_truncated":false,"running":false,"server_time_ms":1}}""")
        settle()
        val before = frames(socket, "conversation_command").size
        val typed = "\n\n  fun main() {\n\n    println(\"你好 👋\")\n  }\nthen explain\n\n"
        val prompt = promptText(typed)
        assertEquals("  fun main() {\n\n    println(\"你好 👋\")\n  }\nthen explain", prompt)
        hub.send("r1", "prompt", prompt)
        settle()
        val commands = frames(socket, "conversation_command").drop(before)
        assertEquals(1, commands.size)
        val command = commands.single()["payload"]!!.jsonObject["command"]!!.jsonObject
        assertEquals("prompt", command["type"]!!.jsonPrimitive.content)
        assertEquals(prompt, command["message"]!!.jsonPrimitive.content)
        assertEquals(prompt, (hub.session("r1").state.value.items.single() as UserTurn).text)
    }

    @Test
    fun controlCallbackSeesTheStateItsResponseProduced() {
        val (socket, _, _) = connect()
        hub.attach("r1")
        settle()
        socket.listener.onText("""{"v":1,"type":"conversation_ready","payload":{"ref":"r1","stream":"s","head_seq":0,"reset":true,"history_truncated":false,"running":false,"server_time_ms":1}}""")
        settle()
        var seen: String? = null
        hub.control("r1", kotlinx.serialization.json.buildJsonObject { put("type", "set_thinking_level"); put("level", "high") }) { ok, _ ->
            if (ok) seen = hub.session("r1").state.value.thinkingLevel
        }
        settle()
        val command = frames(socket, "conversation_command").last()["payload"]!!.jsonObject
        assertEquals("set_thinking_level", command["command"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val id = command["id"]!!.jsonPrimitive.content
        socket.listener.onText("""{"v":1,"type":"conversation_event","payload":{"ref":"r1","seq":1,"ts":1,"event":{"type":"thinking_level_changed","level":"high"}}}""")
        socket.listener.onText("""{"v":1,"type":"conversation_event","payload":{"ref":"r1","event":{"id":"$id","type":"response","command":"set_thinking_level","success":true}}}""")
        settle()
        assertEquals("high", seen)
    }

    @Test
    fun paneModeFollowsTheWorkerAndASwitchReportsBusy() {
        val (socket, _, _) = connect()
        hub.attach("r1")
        settle()
        socket.listener.onText("""{"v":1,"type":"conversation_ready","payload":{"ref":"r1","stream":"s","head_seq":0,"reset":true,"history_truncated":false,"running":false,"server_time_ms":1,"mode":"tui"}}""")
        settle()
        assertEquals(PaneMode.Tui, hub.session("r1").mode.value)
        socket.listener.onText("""{"v":1,"type":"conversation_event","payload":{"ref":"r1","event":{"type":"worker_mode","mode":"rpc"}}}""")
        settle()
        assertEquals(PaneMode.Rpc, hub.session("r1").mode.value)

        var result: Triple<Boolean, String?, Boolean>? = null
        hub.switchMode("r1", PaneMode.Tui, force = false) { ok, reason, busy -> result = Triple(ok, reason, busy) }
        settle()
        val command = frames(socket, "conversation_command").last()["payload"]!!.jsonObject
        val sent = command["command"]!!.jsonObject
        assertEquals("switch_mode", sent["type"]!!.jsonPrimitive.content)
        assertEquals("tui", sent["mode"]!!.jsonPrimitive.content)
        assertEquals("false", sent["force"]!!.jsonPrimitive.content)
        val id = command["id"]!!.jsonPrimitive.content
        socket.listener.onText("""{"v":1,"type":"conversation_event","payload":{"ref":"r1","event":{"id":"$id","type":"response","command":"switch_mode","success":false,"error":"当前任务正在运行","data":{"mode":"rpc","busy":true}}}}""")
        settle()
        assertEquals(Triple(false, "当前任务正在运行", true), result)
        assertEquals("a refused switch leaves the mode", PaneMode.Rpc, hub.session("r1").mode.value)
    }

    @Test
    fun listingMarkersDriveTheConversationRefSet() {
        val (socket, _, core) = connect()
        socket.listener.onText("""{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[{"cwd":"/w","sessions":[{"ref":"a","conversation":true},{"ref":"b"}]}]}}""")
        settle()
        assertEquals(setOf("a"), hub.conversationRefs.value)
        socket.listener.onText("""{"v":1,"type":"list_delta","payload":{"seq":2,"added_sessions":[{"ref":"c","conversation":true}],"removed_refs":["a"]}}""")
        settle()
        assertEquals(setOf("c"), hub.conversationRefs.value)
        assertEquals(3, core.texts.size)
    }

    @Test
    fun adjacentDeltasCoalesceIntoOneCopy() {
        fun delta(seq: Long, text: String) = Triple(seq, seq, Json.parseToJsonElement(
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"$text"}}""",
        ).jsonObject)
        val merged = ConversationHub.coalesceDeltas(listOf(delta(1, "a"), delta(2, "b"), delta(3, "c")))
        assertEquals(1, merged.size)
        assertEquals(3L, merged.single().first)
        assertEquals("abc", merged.single().third["assistantMessageEvent"]!!.jsonObject["delta"]!!.jsonPrimitive.content)
    }
}
