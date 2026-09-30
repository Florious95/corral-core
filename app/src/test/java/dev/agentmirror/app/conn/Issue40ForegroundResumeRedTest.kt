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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent red contract for Issue #40 foreground recovery.
 *
 * These tests deliberately model a READY transport that is still open locally but no longer
 * responsive, followed by a failure after a foreground edge. The foreground path must own a
 * bounded liveness decision and bypass the ordinary background reconnect delay.
 */
class Issue40ForegroundResumeRedTest {
    private class Harness {
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        val listener = RecordingConnListener()
        val manager = ConnectionManager(
            config = ConnectionConfig("ws://host:0/ws", "tok"),
            transportFactory = TransportFactory {
                FakeWebSocketTransport().also { transports += it }
            },
            clock = clock,
            policy = ReconnectPolicy(baseMs = 30_000, maxMs = 30_000, random = { 0.5 }),
        ).also { it.setListener(listener) }

        fun transport(): FakeWebSocketTransport = transports.last()

        fun ready() {
            transport().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        }

        fun clearResponses() {
            transport().deliverText(
                """{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[]}}""",
            )
            transport().sentText.clear()
        }
    }

    @Test
    fun foregroundResume_silentReadySocket_mustDialWithinLivenessBudget() {
        val h = Harness()
        h.manager.start()
        h.ready()
        h.clearResponses()

        // The socket remains locally READY/open, but no response is delivered after resume.
        h.manager.onForegroundResume()
        h.clock.advance(6_000)
        h.manager.pump(h.clock.nowMs())

        assertTrue(
            "foreground resume must not trust a silent READY socket indefinitely; " +
                "dialCount=${h.transports.size}",
            h.transports.size >= 2,
        )
    }

    @Test
    fun foregroundResume_thenFailure_mustDialWithoutBackgroundBackoff() {
        val h = Harness()
        h.manager.start()
        h.ready()
        h.clearResponses()

        // The process is in the foreground after this edge when the old socket fails.
        h.manager.onForegroundResume()
        h.transport().peerFailure(IllegalStateException("silent old socket failed"))

        assertEquals(
            "foreground failure must dial immediately instead of paying the 30s backoff; " +
                "dialCount=${h.transports.size}",
            2,
            h.transports.size,
        )
    }
}
