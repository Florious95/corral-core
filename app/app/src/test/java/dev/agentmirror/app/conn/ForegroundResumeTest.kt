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
 * Foreground resume contract: one shared edge refreshes the retained connection without
 * rebuilding a healthy socket, and crosses a pending reconnect deadline immediately.
 */
class ForegroundResumeTest {

    private class Harness {
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        val listener = RecordingConnListener()
        val manager = ConnectionManager(
            config = ConnectionConfig("ws://host:0/ws", "tok"),
            transportFactory = TransportFactory {
                FakeWebSocketTransport().also {
                    transports += it
                }
            },
            clock = clock,
            policy = ReconnectPolicy(baseMs = 30_000, maxMs = 30_000, random = { 0.5 }),
        ).also { it.setListener(listener) }

        fun transport(): FakeWebSocketTransport = transports.last()

        fun ready() {
            transport().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        }

        fun clearInitialResponses(workspace: String) {
            transport().deliverText(
                """{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[]}}""",
            )
            transport().deliverText(
                """{"v":1,"type":"level2_frame","payload":{"workspace":"$workspace","seq":1,"sessions":[]}}""",
            )
            transport().sentText.clear()
        }
    }

    @Test
    fun readyResumeRefreshesListingAndLevel2Once() {
        val h = Harness()
        h.manager.start()
        h.ready()
        h.manager.subscribeLevel2("/proj")
        h.clearInitialResponses("/proj")

        h.manager.onForegroundResume()

        val sent = h.transport().sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
        assertEquals(1, sent.count { it is ListFrame })
        assertEquals(1, sent.count { it is Level2SubscribeFrame })
        assertEquals(ConnectionState.READY, h.manager.state())
    }

    @Test
    fun resumeRefreshInFlightIsCoalescedUntilResponsesArrive() {
        val h = Harness()
        h.manager.start()
        h.ready()
        h.manager.subscribeLevel2("/proj")
        h.clearInitialResponses("/proj")

        h.manager.onForegroundResume()
        h.manager.onForegroundResume()

        val sent = h.transport().sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
        assertEquals(1, sent.count { it is ListFrame })
        assertEquals(1, sent.count { it is Level2SubscribeFrame })
    }

    @Test
    fun reconnectingResumeCancelsBackoffAndDialsExactlyOnce() {
        val h = Harness()
        h.manager.start()
        h.ready()
        h.transport().peerClose(1006, "dropped")
        assertEquals(ConnectionState.RECONNECTING, h.manager.state())

        h.manager.onForegroundResume()
        h.manager.onForegroundResume()

        assertEquals(2, h.transports.size)
        assertEquals(ConnectionState.AUTHENTICATING, h.manager.state())
        assertTrue(h.listener.reconnectEvents.isNotEmpty())
    }

    @Test
    fun stoppedResumeDoesNotRestartExplicitlyStoppedConnection() {
        val h = Harness()
        h.manager.start()
        h.manager.stop()

        h.manager.onForegroundResume()

        assertEquals(ConnectionState.STOPPED, h.manager.state())
        assertEquals(1, h.transports.size)
    }
}
