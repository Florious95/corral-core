/* Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0.
 * See http://www.apache.org/licenses/LICENSE-2.0 for terms.
 */
package dev.agentmirror.app.input

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class TouchInputDispatcherTest {
    private class Harness {
        val posts = ArrayDeque<() -> Unit>()
        val failures = mutableListOf<String>()
        val gate = TouchInputDispatcher { posts.addLast(it) }
        var link = gate.newLink { failures.add(it) }
        var nextId = 1L
        val writes = mutableListOf<Pair<Long, String>>()
        fun send(value: String): Boolean {
            val id = nextId++
            if (!gate.outbound(link, id)) return false
            writes.add(id to value)
            return true
        }
        fun cell(row: Int, shift: Boolean = false) = TouchInputDispatcher.Cell(46, row, shift, false, false)
        fun down(row: Int = 1) = gate.mouse("pane", cell(row), true, false) { send("down:$row") }
        fun move(row: Int, shift: Boolean = false) = gate.mouse("pane", cell(row, shift), true, true) { send("move:$row:$shift") }
        fun up(row: Int) = gate.mouse("pane", cell(row), false, false) { send("up:$row") }
        fun ack(id: Long, ok: Boolean = true, source: TouchInputDispatcher.Link = link) {
            gate.acknowledged(source, id, ok)
            while (posts.isNotEmpty()) posts.removeFirst().invoke()
        }
        fun values() = writes.map { it.second }
    }

    @Test fun latest1200PositionsRemainOutsideCoreUntilRealAckAndUpSealsFinalPosition() {
        val h = Harness()
        assertTrue(h.down())
        repeat(1200) { assertTrue(h.move(it + 2)) }
        assertTrue(h.up(1201))
        assertEquals(listOf("down:1"), h.values())
        assertEquals(2L, h.nextId) // no discarded motion allocates an input request
        h.ack(9999) // no >= high-water guesses
        assertEquals(listOf("down:1"), h.values())
        h.ack(1)
        assertEquals(listOf("down:1", "move:1201:false", "up:1201"), h.values())
    }

    @Test fun aSecondMotionWaitsForBothEndpointAndBarrierAcks() {
        val h = Harness()
        h.down(); h.ack(1)
        h.move(2)
        h.move(3)
        assertTrue(h.gate.barrier("pane") { h.send("key") })
        h.move(4)
        assertEquals(listOf("down:1", "move:2:false"), h.values())
        h.ack(2)
        assertEquals(listOf("down:1", "move:2:false", "move:3:false", "key"), h.values())
        h.ack(3)
        assertEquals(4, h.writes.size) // key still in flight
        h.ack(3) // duplicate does not release key
        assertEquals(4, h.writes.size)
        h.ack(4)
        assertEquals("move:4:false", h.values().last())
    }

    @Test fun repeatedGestureEndpointAndModifiersAreNotDeduplicatedAcrossDownUp() {
        val h = Harness()
        repeat(2) {
            h.down(1); h.move(7); h.move(7); h.up(7)
            // Drain every written request, including the two endpoint/barrier requests.
            var at = if (it == 0) 0 else 3
            while (at < h.writes.size) h.ack(h.writes[at++].first)
        }
        assertEquals(listOf("down:1", "move:7:false", "up:7", "down:1", "move:7:false", "up:7"), h.values())
        h.down(); h.ack(7)
        h.move(9); h.move(9, shift = true); h.up(9)
        h.ack(8)
        assertEquals("move:9:true", h.values()[h.values().size - 2])
    }

    @Test fun textThatLooksExactlyLikeSgrIsLosslessAndOrdinaryTypingIsNotThrottled() {
        val h = Harness()
        val text = "\u001b[<32;46;44M"
        repeat(100) { assertTrue(h.gate.barrier("pane") { h.send(text) }) }
        assertEquals(List(100) { text }, h.values())
        h.down(); h.move(44)
        for (id in 1L..100L) h.ack(id)
        assertEquals("down:1", h.values().last())
        h.ack(101)
        assertEquals("move:44:false", h.values().last())
    }

    @Test fun wrongConnectionAndOldGestureCannotReleaseNewConnection() {
        val h = Harness()
        h.down(); h.move(2)
        val old = h.link
        h.gate.closed(old)
        h.link = h.gate.newLink { h.failures.add(it) }
        assertFalse(h.move(3))
        assertFalse(h.up(3))
        h.down(); h.move(4)
        h.ack(2, source = old)
        assertEquals(listOf("down:1", "down:1"), h.values())
        h.ack(2)
        assertEquals("move:4:false", h.values().last())
    }

    @Test fun negativeAckAndLocalTimeoutFailVisiblyInsteadOfAutoOpening() {
        val h = Harness()
        h.down(); h.move(10); h.up(10)
        h.gate.failed(999, "timeout")
        assertTrue(h.failures.isEmpty())
        h.ack(1, ok = false)
        assertEquals(1, h.failures.size)
        assertEquals(listOf("down:1"), h.values())
        val t = Harness()
        t.down(); t.move(10); t.up(10)
        t.gate.failed(1, "timeout")
        t.ack(1)
        assertEquals(1, t.failures.size)
        assertEquals(listOf("down:1"), t.values())
    }

    @Test fun rapidTapsBoundTheBarrierQueueAndNeverSilentlyLoseAcceptedRelease() {
        val h = Harness()
        h.down(); h.move(10); h.up(10)
        var failed = false
        repeat(100) { if (!h.down() || !h.up(1)) failed = true }
        assertTrue(failed)
        assertEquals(1, h.failures.size)
        assertEquals(listOf("down:1"), h.values())
    }

    @Test fun screenExitPreservesQueuedEndpointAndReleaseBeforeUnsubscribe() {
        val h = Harness()
        h.down(); h.move(10); h.up(10)
        assertTrue(h.gate.leave("pane") { h.send("unsubscribe") })
        assertTrue(h.failures.isEmpty())
        assertEquals(listOf("down:1"), h.values())
        h.ack(1)
        assertEquals(listOf("down:1", "move:10:false", "up:10", "unsubscribe"), h.values())
        assertTrue(h.failures.isEmpty())
    }

    @Test fun abandoningPointerWithoutReleaseIsVisibleFailure() {
        val h = Harness()
        h.down(); h.move(10)
        assertFalse(h.gate.leave("pane") { h.send("unsubscribe") })
        assertEquals(1, h.failures.size)
        h.ack(1)
        assertEquals(listOf("down:1"), h.values())
    }

    @Test fun ackBeforeWriteReturnsDoesNotStrandGateAndReceiptIsExact() {
        val posts = ArrayDeque<() -> Unit>()
        val gate = TouchInputDispatcher { posts.add(it) }
        val link = gate.newLink { fail(it) }
        val received = mutableListOf<Long>()
        assertTrue(gate.barrier("pane", { received.add(it) }) {
            assertTrue(gate.outbound(link, 1))
            gate.acknowledged(link, 1, true)
            true
        })
        assertEquals(listOf(1L), received)
        assertTrue(gate.mouse("pane", TouchInputDispatcher.Cell(1, 1, false, false, false), true, false) {
            assertTrue(gate.outbound(link, 2))
            gate.acknowledged(link, 2, true)
            true
        })
        assertTrue(gate.mouse("pane", TouchInputDispatcher.Cell(1, 2, false, false, false), true, true) {
            assertTrue(gate.outbound(link, 3))
            gate.acknowledged(link, 3, true)
            true
        })
        while (posts.isNotEmpty()) posts.removeFirst().invoke()
        assertTrue(gate.barrier("pane") { true })
    }
}
