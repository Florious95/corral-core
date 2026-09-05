/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 82: after a host coordinator has cancelled its round on READY, a later
 * non-permanent peer close must still scheduleReconnect (new identify generation).
 *
 * HostDialCoordinator.onReady = cancel, so onTargetFailed after READY is a no-op
 * (round == null). The no-coordinator path already reconnects; this is the shared
 * close path when a coordinator is present.
 */
class CoordinatorReadyDropReconnectTest {

    /**
     * Mirrors HostDialCoordinator after a successful proof: begin emits one target,
     * onReady cancels the round, later onTargetFailed does not emit or exhaust.
     */
    private class CancelOnReadyCoordinator : AsyncDialCoordinator {
        var beginCount = 0
        var failCount = 0
        var readyCount = 0
        var live = false
        private var onTarget: ((DialTarget) -> Unit)? = null
        private var onExhausted: (() -> Unit)? = null

        override fun begin(generation: Long, onTarget: (DialTarget) -> Unit, onExhausted: () -> Unit) {
            beginCount++
            live = true
            this.onTarget = onTarget
            this.onExhausted = onExhausted
            onTarget(DialTarget("ws://host:0/ws", "lan"))
        }

        override fun onTargetFailed(generation: Long, url: String, reason: String) {
            failCount++
            if (!live) return
            onExhausted?.invoke()
        }

        override fun onReady(generation: Long) {
            readyCount++
            live = false
            onTarget = null
            onExhausted = null
        }

        override fun cancel(generation: Long) {
            live = false
            onTarget = null
            onExhausted = null
        }
    }

    /**
     * Pre-READY next-target: begin emits the first URL; a non-permanent fail
     * emits the second. Used to keep the AUTHENTICATING/dial-fail coordinator
     * advance path from being collapsed into scheduleReconnect.
     */
    private class NextTargetCoordinator : AsyncDialCoordinator {
        private val urls = ArrayDeque(listOf("ws://first:0/ws", "ws://second:0/ws"))
        var beginCount = 0
        var failCount = 0
        private var onTarget: ((DialTarget) -> Unit)? = null

        override fun begin(generation: Long, onTarget: (DialTarget) -> Unit, onExhausted: () -> Unit) {
            beginCount++
            this.onTarget = onTarget
            onTarget(DialTarget(urls.removeFirst(), "first"))
        }

        override fun onTargetFailed(generation: Long, url: String, reason: String) {
            failCount++
            if (urls.isNotEmpty()) {
                onTarget?.invoke(DialTarget(urls.removeFirst(), "second"))
            }
        }

        override fun onReady(generation: Long) {}

        override fun cancel(generation: Long) {}
    }

    private class Harness(
        coordinator: AsyncDialCoordinator,
        val clock: FakeClock = FakeClock(),
        val policy: ReconnectPolicy = ReconnectPolicy(baseMs = 1000, maxMs = 30_000, random = { 0.5 }),
        dialScripts: ArrayDeque<List<Boolean>> = ArrayDeque(),
    ) {
        val transports = mutableListOf<FakeWebSocketTransport>()
        val listener = RecordingConnListener()
        val manager = ConnectionManager(
            config = ConnectionConfig("ws://host:0/ws", "tok"),
            transportFactory = TransportFactory {
                FakeWebSocketTransport().also {
                    it.dialScript = dialScripts.removeFirstOrNull() ?: listOf(true)
                    transports += it
                }
            },
            clock = clock,
            policy = policy,
            dialCoordinator = coordinator,
        ).also { it.setListener(listener) }

        fun transport(): FakeWebSocketTransport = transports.last()

        fun ready() {
            transport().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        }
    }

    @Test
    fun readyPeerCloseWithCancelledCoordinatorSchedulesReconnectAndReplaysSubscribe() {
        val coord = CancelOnReadyCoordinator()
        val h = Harness(coord)
        h.manager.start()
        assertEquals(1, h.transports.size)
        h.ready()
        assertEquals(ConnectionState.READY, h.manager.state())
        assertEquals(1, coord.readyCount)
        assertFalse(coord.live)
        assertTrue(h.manager.subscribe("fixture-codex", 24, 80))

        h.transport().peerClose(1006, "dropped")

        assertEquals(
            "READY drop with cancelled coordinator must leave RECONNECTING, not a dead READY",
            ConnectionState.RECONNECTING,
            h.manager.state(),
        )
        assertEquals(1, h.listener.reconnectEvents.size)
        assertEquals(0 to 1000L, h.listener.reconnectEvents.single())
        assertFalse(
            "send must fail until the replacement socket is READY",
            h.manager.sendInput("fixture-codex", "hello"),
        )

        h.clock.advance(1000)
        h.manager.pump(h.clock.nowMs())
        assertEquals("replacement transport after reconnect pump", 2, h.transports.size)
        assertEquals("new identify generation via coordinator.begin", 2, coord.beginCount)
        assertEquals(ConnectionState.AUTHENTICATING, h.manager.state())

        h.ready()
        assertEquals(ConnectionState.READY, h.manager.state())
        val sent = h.transport().sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        assertTrue(sent.any { it is AuthFrame })
        assertTrue(sent.any { it is ListFrame })
        assertEquals(listOf("fixture-codex"), sent.filterIsInstance<SubscribeFrame>().map { it.ref })
        assertTrue(h.manager.sendInput("fixture-codex", "hello"))
        assertTrue(h.transport().sentText.any { runCatching { FrameCodec.decode(it) }.getOrNull() is InputFrame })
    }

    @Test
    fun reconnectingResumeAfterReadyDropDialsReplacementWithoutWaitingBackoff() {
        val coord = CancelOnReadyCoordinator()
        val h = Harness(coord)
        h.manager.start()
        h.ready()
        assertTrue(h.manager.subscribe("fixture-codex", 24, 80))
        h.transport().peerClose(1006, "dropped")
        assertEquals(ConnectionState.RECONNECTING, h.manager.state())

        h.manager.onForegroundResume()
        h.manager.onForegroundResume()

        assertEquals(2, h.transports.size)
        assertEquals(2, coord.beginCount)
        assertEquals(ConnectionState.AUTHENTICATING, h.manager.state())
        h.ready()
        assertEquals(ConnectionState.READY, h.manager.state())
        assertTrue(h.manager.sendInput("fixture-codex", "hello"))
    }

    @Test
    fun readyResumeWithCoordinatorDoesNotRebuildHealthySocket() {
        val coord = CancelOnReadyCoordinator()
        val h = Harness(coord)
        h.manager.start()
        h.ready()
        assertEquals(ConnectionState.READY, h.manager.state())

        h.manager.onForegroundResume()
        h.manager.onForegroundResume()

        assertEquals(1, h.transports.size)
        assertEquals(1, coord.beginCount)
        assertEquals(ConnectionState.READY, h.manager.state())
    }

    @Test
    fun preReadyDialFailureAsksCoordinatorForNextTargetWithoutReconnectSchedule() {
        val coord = NextTargetCoordinator()
        val h = Harness(
            coordinator = coord,
            dialScripts = ArrayDeque(listOf(listOf(false), listOf(true))),
        )
        h.manager.start()

        assertEquals(2, h.transports.size)
        assertEquals(1, coord.beginCount)
        assertEquals(1, coord.failCount)
        assertEquals(ConnectionState.AUTHENTICATING, h.manager.state())
        assertTrue(h.listener.reconnectEvents.isEmpty())
        assertEquals("ws://second:0/ws", h.manager.dialUrl())
    }
}
