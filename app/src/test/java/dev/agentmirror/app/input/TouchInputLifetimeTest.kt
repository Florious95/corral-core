/* Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0.
 * See http://www.apache.org/licenses/LICENSE-2.0 for terms.
 */
package dev.agentmirror.app.input

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class TouchInputLifetimeTest {
    @Test fun oldAckMustNotReplayOldMotionIntoNewSocketBeforeOldOnClosed() {
        val tasks = ArrayDeque<() -> Unit>()
        val failures = mutableListOf<String>()
        val gate = TouchInputDispatcher { tasks.add(it) }
        val old = gate.newLink { failures.add(it) }
        var active = old
        var next = 1L
        val sent = mutableListOf<String>()
        fun write(value: String): Boolean {
            if (!gate.outbound(active, next++)) return false
            sent.add(value)
            return true
        }
        val cell = TouchInputDispatcher.Cell(1, 1, false, false, false)
        gate.mouse("pane", cell, true, false) { write("down-old") }
        gate.mouse("pane", cell.copy(row = 42), true, true) { write("motion-old") }
        active = gate.newLink { failures.add(it) }
        gate.acknowledged(old, 1, true)
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        assertEquals(listOf("down-old"), sent)
        assertEquals(1, failures.size)
    }

    @Test fun connectionStateClearsGestureBeforeDelayedCloseAndOldCloseCannotClearNewGesture() {
        val tasks = ArrayDeque<() -> Unit>()
        val gate = TouchInputDispatcher { tasks.add(it) }
        val old = gate.newLink { fail(it) }
        val fresh = gate.newLink { fail(it) }
        val cell = TouchInputDispatcher.Cell(1, 1, false, false, false)
        gate.selected(old)
        gate.mouse("pane", cell, true, false) { gate.outbound(old, 1) }
        gate.mouse("pane", cell.copy(row = 10), true, true) { fail("stale motion"); false }
        gate.suspend()
        gate.selected(fresh)
        assertFalse(gate.mouse("pane", cell.copy(row = 11), true, true) { fail("old gesture"); false })
        assertTrue(gate.mouse("pane", cell, true, false) { gate.outbound(fresh, 2) })
        gate.closed(old)
        var written = false
        assertTrue(gate.mouse("pane", cell.copy(row = 12), true, true) {
            written = true
            gate.outbound(fresh, 3)
        })
        gate.acknowledged(old, 2, true)
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        assertFalse(written)
        gate.acknowledged(fresh, 2, true)
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        assertTrue(written)
    }

    @Test fun nonInputBarrierCannotCrossSocketGenerationEither() {
        val tasks = ArrayDeque<() -> Unit>()
        val failures = mutableListOf<String>()
        val gate = TouchInputDispatcher { tasks.add(it) }
        val old = gate.newLink { failures.add(it) }
        val fresh = gate.newLink { failures.add(it) }
        var active = old
        val cell = TouchInputDispatcher.Cell(1, 1, false, false, false)
        gate.mouse("pane", cell, true, false) { gate.outbound(old, 1) }
        gate.mouse("pane", cell.copy(row = 10), true, true) {
            val written = gate.outbound(old, 2)
            active = fresh // Core swaps after the endpoint write, before the next barrier.
            written
        }
        var leaked = false
        gate.barrier("pane") {
            if (!gate.permits(active)) false else { leaked = true; true }
        }
        gate.acknowledged(old, 1, true)
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        assertFalse(leaked)
        assertEquals(1, failures.size)
    }
}
