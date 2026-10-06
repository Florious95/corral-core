/*
 * Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package dev.agentmirror.app.input

import java.util.ArrayDeque

/**
 * App-side TUI input ordering. Only the typed mouse entry point coalesces: never inspect text
 * or bytes for an SGR-looking substring. Work is a *send action*, not a Core input frame, so
 * overwritten motions never allocate Core req_ids, pendingInputs or timeout entries.
 *
 * @contract
 * @pre send actions use the same decorated persistent transport; drain is posted to the UI
 * @post motion waits for every outstanding input's exact, physical-connection input_ack
 * @err write failure, negative ACK, timeout or overflow closes the link visibly; no auto-open
 * @inv one replaceable tail motion; barriers seal endpoints in bounded FIFO order; no idle timer
 */
class TouchInputDispatcher(private val post: (() -> Unit) -> Unit) {
    class Link internal constructor(internal val fail: (String) -> Unit) {
        internal var closed = false
    }
    data class Cell(val column: Int, val row: Int, val shift: Boolean, val meta: Boolean, val ctrl: Boolean)
    private data class Work(
        val ref: String, val motion: Boolean, val send: () -> Boolean,
        val onWrite: ((Long) -> Unit)? = null, val epoch: Link? = null,
        val onDelivered: ((Long) -> Unit)? = null, val teardown: Boolean = false,
    )

    private var link: Link? = null
    private val outstanding = HashSet<Long>()
    private val deliveries = HashMap<Long, (Long) -> Unit>()
    private val queue = ArrayDeque<Work>()
    private var heldRef: String? = null
    private var lastCell: Cell? = null
    private var draining = false
    private var writing: Work? = null

    fun newLink(fail: (String) -> Unit): Link = Link(fail)

    /** Called before the socket write, not its completion: even an immediate ACK is correlated. */
    @Synchronized
    fun outbound(source: Link, reqId: Long): Boolean {
        if (source.closed) return false
        val expected = writing?.epoch
        if (expected != null && expected !== source) {
            fail("queued input crossed socket generation")
            return false
        }
        if (link !== source) {
            if (link != null) clear()
            link = source
        }
        if (outstanding.size >= MAX_OUTSTANDING || !outstanding.add(reqId)) {
            fail("input tracking overflow/duplicate req_id=$reqId pending=${outstanding.size}")
            return false
        }
        writing?.onDelivered?.let { deliveries[reqId] = it }
        writing?.onWrite?.invoke(reqId)
        return true
    }

    /** Core's chosen list/subscribe selects the socket before any touch can be queued. */
    @Synchronized
    fun selected(source: Link): Boolean {
        if (source.closed) return false
        if (link !== source) {
            clear()
            link = source
        }
        return true
    }

    /** New ownership of this ref invalidates only its queued old leave, never accepted input. */
    @Synchronized
    fun reacquired(ref: String): Int {
        val before = queue.size
        queue.removeAll { it.ref == ref && it.teardown }
        return before - queue.size
    }

    /** Connection state invalidates the old gesture before a delayed socket onClosed. */
    @Synchronized
    fun suspend() {
        link?.closed = true
        clear()
        link = null
    }

    /** A mode replacement must not overtake an old pane's unsent input or held pointer. */
    @Synchronized
    fun modeSwitchAllowed(ref: String): Boolean = heldRef != ref && queue.none { it.ref == ref }

    /** Applies the queued action's epoch to non-input barriers too (wheel/unsubscribe/preview). */
    @Synchronized
    fun permits(source: Link): Boolean = !source.closed && (writing?.epoch == null || writing?.epoch === source)

    /** Only the transport's real input_ack calls this. Local timeout callbacks never open it. */
    @Synchronized
    fun acknowledged(source: Link, reqId: Long, ok: Boolean): Unit {
        if (link !== source || source.closed || !outstanding.remove(reqId)) return
        if (!ok) {
            fail("input rejected req_id=$reqId queued=${queue.size}")
            return
        }
        val delivered = deliveries.remove(reqId)
        post {
            // Actual raw ACK is authoritative even if Core registered its timeout *after*
            // a very fast ACK. UI callbacks still compare the captured submission req_id.
            delivered?.invoke(reqId)
            synchronized(this) { if (link === source && !source.closed) drain() }
        }
    }

    @Synchronized
    fun failed(reqId: Long, reason: String?) {
        if (reqId in outstanding) {
            // Core calls this while iterating pendingInputs. Close admission now, but defer
            // socket teardown until that iteration returns (no reentrant map mutation).
            fail("input unresolved req_id=$reqId reason=$reason pending=${outstanding.size} queued=${queue.size}", deferred = true)
        }
    }

    @Synchronized
    fun closed(source: Link) {
        source.closed = true
        if (link === source) {
            clear()
            link = null
        }
    }

    /** Ordinary input stays immediate when no pointer endpoint is waiting (IME fast path). */
    @Synchronized
    fun barrier(ref: String, onWrite: ((Long) -> Unit)? = null, onDelivered: ((Long) -> Unit)? = null, send: () -> Boolean): Boolean {
        return submit(Work(ref, false, send, onWrite, link, onDelivered))
    }

    /** DOWN/UP are barriers. A new DOWN resets dedup, including repeated identical gestures. */
    @Synchronized
    fun mouse(ref: String, cell: Cell, press: Boolean, motion: Boolean, send: () -> Boolean): Boolean {
        if (!motion) {
            if (press) {
                heldRef = ref
                lastCell = null
            } else if (heldRef != ref) {
                return false // Old gesture after a disconnect must not enter a new connection.
            }
            val accepted = barrier(ref, send = send)
            if (accepted && press && link != null) {
                heldRef = ref
                lastCell = cell
            }
            if (!press || !accepted) {
                heldRef = null
                lastCell = null
            }
            return accepted
        }
        if (heldRef != ref || link == null) return false
        if (lastCell == cell) return true
        val work = Work(ref, true, send, epoch = link)
        val tail = queue.peekLast()
        if (tail?.motion == true && tail.ref == ref) {
            queue.removeLast()
            queue.addLast(work)
        } else if (!enqueue(work)) {
            return false
        }
        lastCell = cell
        drain()
        return true
    }

    /** Preserve an accepted UP before unsubscribe. An abandoned, still-held pointer fails visibly. */
    @Synchronized
    fun leave(ref: String, unsubscribe: () -> Boolean): Boolean {
        if (heldRef == ref) fail("pointer screen closed without release ref=$ref")
        return submit(Work(ref, false, unsubscribe, epoch = link, teardown = true))
    }

    private fun submit(work: Work): Boolean {
        if (queue.isEmpty()) {
            val sent = write(work)
            if (!sent) fail("input write failed ref=${work.ref}")
            return sent
        }
        return enqueue(work)
    }

    private fun enqueue(work: Work): Boolean {
        if (queue.size >= MAX_QUEUED) {
            fail("input barrier overflow limit=$MAX_QUEUED queued=${queue.size}")
            return false
        }
        queue.addLast(work)
        drain()
        return true
    }

    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                // A barrier can follow its flushed endpoint immediately in WebSocket FIFO.
                // The *next motion* still waits for both ACKs; barriers never bypass a motion.
                if (queue.first.motion && outstanding.isNotEmpty()) break
                val work = queue.removeFirst()
                if (!write(work)) {
                    fail("input write failed ref=${work.ref} motion=${work.motion}")
                    break
                }
            }
        } finally {
            draining = false
        }
    }

    private fun write(work: Work): Boolean {
        val previous = writing
        writing = work
        return try { work.send() } finally { writing = previous }
    }

    private fun fail(reason: String, deferred: Boolean = false) {
        val old = link
        clear()
        link = null
        if (old != null && !old.closed) {
            old.closed = true
            if (deferred) post { old.fail(reason) } else old.fail(reason)
        }
    }

    private fun clear() {
        outstanding.clear()
        deliveries.clear()
        queue.clear()
        heldRef = null
        lastCell = null
    }

    private companion object {
        // Motions occupy one mutable slot. Only lossless barriers/endpoints can fill this FIFO.
        const val MAX_QUEUED = 64
        const val MAX_OUTSTANDING = 4096
    }
}
