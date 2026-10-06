/* Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0.
 * See http://www.apache.org/licenses/LICENSE-2.0 for terms.
 */
package dev.agentmirror.app.input

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.InputFrame
import dev.agentmirror.app.conn.RecordingConnListener
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.terminal.MouseSgr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Uses the exact pinned Maven ConnectionManager/FrameCodec, not a mocked send/ACK API. */
class TouchInputTransportTest {
    internal class Harness(ackDuringWrite: Boolean = false) {
        val posts = ArrayDeque<() -> Unit>()
        val gate = TouchInputDispatcher { posts.addLast(it) }
        val wire = FakeWebSocketTransport()
        val clock = FakeClock()
        val events = RecordingConnListener()
        lateinit var decorated: TouchInputTransport
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://fixture:0/ws", token = "fixture-token"),
            transportFactory = TransportFactory {
                val socket = if (ackDuringWrite) object : WebSocketTransport by wire {
                    override fun sendText(text: String): Boolean {
                        val sent = wire.sendText(text)
                        val input = runCatching { FrameCodec.decode(text) as? InputFrame }.getOrNull()
                        if (sent && input != null) wire.deliverText(
                            """{"v":1,"type":"input_ack","payload":{"req_id":${input.reqId},"ok":true}}""",
                        )
                        return sent
                    }
                } else wire
                TouchInputTransport(socket, gate).also { decorated = it }
            },
            clock = clock,
            inputTimeoutMs = 1000,
        )
        init {
            manager.setListener(object : ConnectionManager.Listener by events {
                override fun onStateChanged(state: ConnectionState) {
                    if (state != ConnectionState.READY) gate.suspend()
                    events.onStateChanged(state)
                }
                override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) {
                    if (!ok) gate.failed(reqId, reason)
                    events.onInputResult(reqId, ok, reason)
                }
            })
            manager.start()
            wire.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            assertEquals(ConnectionState.READY, manager.state())
        }
        fun inputs() = wire.sentText.mapNotNull { runCatching { FrameCodec.decode(it) as? InputFrame }.getOrNull() }
        fun mouse(row: Int, press: Boolean, motion: Boolean = false): Boolean =
            gate.mouse("pane", TouchInputDispatcher.Cell(46, row, false, false, false), press, motion) {
                manager.sendRawBytes("pane", MouseSgr.encode(button = 0, column = 46, row = row, press = press, motion = motion))
            }
        fun receive(text: String) {
            wire.deliverText(text)
            drain()
        }
        fun drain() { while (posts.isNotEmpty()) posts.removeFirst().invoke() }
        fun ack(id: Long) = receive("""{"v":1,"type":"input_ack","payload":{"req_id":$id,"ok":true}}""")
    }

    @Test fun actualCoreReceivesOnlyDownThenLatestEndpointAndReleaseAfter500msSlowAck() {
        val h = Harness()
        h.mouse(1, true)
        repeat(1200) { assertTrue(h.mouse(it + 2, true, true)) }
        assertTrue(h.mouse(1201, false))
        h.clock.advance(500)
        h.manager.pump(h.clock.nowMs())
        h.manager.resolveExpiredInputs(h.clock.nowMs())
        assertEquals(1, h.inputs().size) // no 250ms timeout auto-open
        assertEquals(ConnectionState.READY, h.manager.state())
        h.ack(h.inputs().single().reqId)
        assertEquals(3, h.inputs().size)
        assertArrayEquals("\u001b[<32;46;1201M".toByteArray(), h.inputs()[1].bytes)
        assertArrayEquals("\u001b[<0;46;1201m".toByteArray(), h.inputs()[2].bytes)
        h.manager.stop()
    }

    @Test fun malformedVersionEnvelopeAndUnknownIdCannotOpenTheGate() {
        val h = Harness()
        h.mouse(1, true); h.mouse(42, true, true)
        val id = h.inputs().single().reqId
        h.receive("""{"v":2,"type":"input_ack","payload":{"req_id":$id,"ok":true}}""")
        assertTrue(h.events.decodeErrors.isNotEmpty())
        assertEquals(1, h.inputs().size)
        h.ack(id + 1000)
        assertEquals(1, h.inputs().size)
        h.ack(id)
        assertEquals(2, h.inputs().size)
        h.manager.stop()
    }

    @Test fun validAckWithWhitespaceUsesTheSameCoreProtocolValidator() {
        val h = Harness()
        h.mouse(1, true); h.mouse(42, true, true)
        val id = h.inputs().single().reqId
        h.receive("""{ "v": 1, "type": "input_ack", "payload": { "req_id": $id, "ok": true } }""")
        assertEquals(2, h.inputs().size)
        h.manager.stop()
    }

    @Test fun negativeAckFailsConnectionAndDoesNotInjectQueuedEndpointOrRelease() {
        val h = Harness()
        h.mouse(1, true); h.mouse(42, true, true); h.mouse(42, false)
        val id = h.inputs().single().reqId
        h.receive("""{"v":1,"type":"input_ack","payload":{"req_id":$id,"ok":false,"reason":"inject_failed"}}""")
        assertEquals(ConnectionState.RECONNECTING, h.manager.state())
        assertEquals(1, h.inputs().size)
        assertTrue(h.events.inputResults.any { it.first == id && !it.second })
        h.manager.stop()
    }

    @Test fun realCoreTimeoutFailsOnlyTheOneWrittenInputNot1200DiscardedMotions() {
        val h = Harness()
        h.mouse(1, true)
        repeat(1200) { h.mouse(it + 2, true, true) }
        h.mouse(1201, false)
        h.clock.advance(1001)
        h.manager.pump(h.clock.nowMs())
        h.manager.resolveExpiredInputs(h.clock.nowMs())
        h.drain()
        assertEquals(ConnectionState.RECONNECTING, h.manager.state())
        assertEquals(1, h.inputs().size)
        assertEquals(1, h.events.inputResults.count { !it.second })
        h.manager.stop()
    }

    @Test fun multipleCoreTimeoutsDoNotCloseReentrantlyInsideItsPendingMapIteration() {
        val h = Harness()
        repeat(12) { h.gate.barrier("pane") { h.manager.sendKeystroke("pane", "key$it") } }
        h.clock.advance(1001)
        h.manager.pump(h.clock.nowMs())
        h.manager.resolveExpiredInputs(h.clock.nowMs()) // Must not throw ConcurrentModificationException.
        h.drain()
        assertEquals(ConnectionState.RECONNECTING, h.manager.state())
        assertEquals(12, h.events.inputResults.count { !it.second })
        h.manager.stop()
    }

    @Test fun sgrLookingTextIsNeverCoalescedAndOrdinaryTypingKeepsItsImmediatePath() {
        val h = Harness()
        val text = "\u001b[<32;46;42M"
        repeat(12) { assertTrue(h.gate.barrier("pane") { h.manager.sendKeystroke("pane", text) }) }
        assertEquals(List(12) { text }, h.inputs().map { it.text })
        h.manager.stop()
    }

    @Test fun modeReplacementCannotOvertakeUnsentOldTuiEndpointAndRelease() {
        val h = Harness()
        val mode = """{"v":1,"type":"conversation_command","payload":{"ref":"pane","id":"mode","command":{"type":"switch_mode","mode":"rpc","force":false}}}"""
        h.mouse(1, true)
        assertFalse(h.decorated.sendText(mode)) // still-held pointer
        h.mouse(42, true, true); h.mouse(42, false)
        assertFalse(h.decorated.sendText(mode)) // accepted endpoint/UP are not written yet
        assertFalse(h.wire.sentText.contains(mode))
        h.ack(h.inputs().single().reqId)
        assertEquals(3, h.inputs().size)
        assertTrue(h.decorated.sendText(mode))
        assertEquals(mode, h.wire.sentText.last()) // follows UP in the same socket's FIFO
        h.manager.stop()
    }

    @Test fun sameRefReentryCannotLetOldDeferredLeaveRemoveTheNewCoreSubscription() {
        for (newRows in listOf(44, 45)) {
            val h = Harness()
            h.manager.subscribe("pane", 44, 46)
            h.mouse(1, true); h.mouse(42, true, true); h.mouse(42, false)
            val down = h.inputs().single().reqId
            assertTrue(h.gate.leave("pane") { h.manager.unsubscribe("pane") })
            h.manager.subscribe("pane", newRows, 46)
            val newSize = h.manager.subscriptionSize("pane")
            assertNotNull(newSize)
            h.ack(down)
            assertEquals(newSize, h.manager.subscriptionSize("pane"))
            assertEquals(3, h.inputs().size)
            assertArrayEquals("\u001b[<32;46;42M".toByteArray(), h.inputs()[1].bytes)
            assertArrayEquals("\u001b[<0;46;42m".toByteArray(), h.inputs()[2].bytes)
            assertFalse(h.wire.sentText.any { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content == "unsubscribe" })
            // The new owner's eventual ordinary leave must still remove its own subscription.
            assertTrue(h.gate.leave("pane") { h.manager.unsubscribe("pane") })
            assertNull(h.manager.subscriptionSize("pane"))
            h.manager.stop()
        }
    }

    @Test fun repeatedSameRefReacquisitionIsBoundedAndStillPreservesOneFinalEndpointAndUp() {
        val h = Harness()
        h.manager.subscribe("pane", 44, 46)
        h.mouse(1, true); h.mouse(42, true, true); h.mouse(42, false)
        val down = h.inputs().single().reqId
        repeat(100) {
            assertTrue(h.gate.leave("pane") { h.manager.unsubscribe("pane") })
            assertTrue(h.manager.subscribe("pane", 44 + it % 2, 46))
        }
        val newSize = h.manager.subscriptionSize("pane")
        h.ack(down)
        assertEquals(ConnectionState.READY, h.manager.state())
        assertEquals(newSize, h.manager.subscriptionSize("pane"))
        assertEquals(3, h.inputs().size)
        h.manager.stop()
    }

    @Test fun sameRefReentryCancelsOnlyItsOldTeardownNotAnotherRefsLeave() {
        val h = Harness()
        h.manager.subscribe("pane", 44, 46)
        h.manager.subscribe("other", 30, 80)
        h.mouse(1, true); h.mouse(42, true, true); h.mouse(42, false)
        val down = h.inputs().single().reqId
        h.gate.leave("pane") { h.manager.unsubscribe("pane") }
        h.gate.leave("other") { h.manager.unsubscribe("other") }
        h.manager.subscribe("pane", 45, 46)
        val newSize = h.manager.subscriptionSize("pane")
        h.ack(down)
        assertEquals(newSize, h.manager.subscriptionSize("pane"))
        assertNull(h.manager.subscriptionSize("other"))
        assertEquals(3, h.inputs().size) // accepted old endpoint and UP are not globally cleared
        h.manager.stop()
    }

    @Test fun actualAckBeforeCoreRegistersPendingStillDeliversExactSubmissionReceipt() {
        val h = Harness(ackDuringWrite = true)
        var delivered: Long? = null
        assertTrue(h.gate.barrier("pane", onDelivered = { delivered = it }) {
            h.manager.sendKeystroke("pane", "key")
        })
        assertTrue(h.events.inputResults.isEmpty()) // Core had not registered PendingInput yet.
        h.drain()
        assertEquals(h.inputs().single().reqId, delivered)
        h.clock.advance(1001)
        h.manager.resolveExpiredInputs(h.clock.nowMs())
        h.drain()
        assertEquals(ConnectionState.READY, h.manager.state()) // stale SDK timeout is not delivery failure
        assertEquals(h.inputs().single().reqId, delivered)
        h.manager.stop()
    }

    @Test fun allNativeSubscriptionsDeclareMobileAndPreserveGridAndRetainFields() {
        val h = Harness()
        assertTrue(h.manager.subscribe("pane", 44, 46, retainPaneSize = true))
        val root = h.wire.sentText.map { Json.parseToJsonElement(it).jsonObject }
            .last { it["type"]?.jsonPrimitive?.content == "subscribe" }
        val payload = root.getValue("payload").jsonObject
        assertEquals("mobile", payload.getValue("client_type").jsonPrimitive.content)
        assertEquals("44", payload.getValue("rows").jsonPrimitive.content)
        assertEquals("46", payload.getValue("cols").jsonPrimitive.content)
        assertEquals("true", payload.getValue("retain_pane_size").jsonPrimitive.content)
        h.manager.stop()
    }
}
