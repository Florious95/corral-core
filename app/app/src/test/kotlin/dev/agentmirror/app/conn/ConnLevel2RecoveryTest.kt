/* Copyright 2026 AgentMirror Project Authors. Licensed under Apache-2.0. */
package dev.agentmirror.app.conn

import org.junit.Assert.*
import org.junit.Test

class ConnLevel2RecoveryTest {
    private class Harness {
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        val manager = ConnectionManager(ConnectionConfig("ws://host:0/ws", "test-token"),
            TransportFactory { FakeWebSocketTransport().also(transports::add) }, clock)
        val transport get() = transports.last()
        init { manager.start(); ready() }
        fun ready() = transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        fun frames() = transport.sentText.map { FrameCodec.decode(it) }
        fun clear() = transport.sentText.clear()
    }
    @Test fun staleUnsubscribeDoesNotCloseNewSlot() {
        val h = Harness()
        h.manager.subscribeLevel2("/b")
        h.manager.subscribeLevel2("/a")
        h.clear()
        assertTrue(h.manager.unsubscribeLevel2("/b"))
        assertEquals(0, h.frames().filterIsInstance<Level2UnsubscribeFrame>().size)
        assertTrue(h.manager.unsubscribeLevel2("/a"))
        assertEquals(1, h.frames().filterIsInstance<Level2UnsubscribeFrame>().size)
    }
    @Test fun reconnectReplaysOnlyLatestWorkspaceEvenAfterAtoBtoA() {
        val h = Harness()
        h.manager.subscribeLevel2("/a")
        h.manager.subscribeLevel2("/b")
        h.manager.subscribeLevel2("/a")
        h.transport.peerFailure(IllegalStateException("synthetic drop"))
        h.manager.onNetworkAvailable()
        h.ready()
        assertEquals(listOf("/a"), h.frames().filterIsInstance<Level2SubscribeFrame>().map { it.workspace })
    }
    @Test fun expiredRefreshDoesNotPermanentlyBlockForegroundRecovery() {
        val h = Harness()
        h.manager.subscribeLevel2("/a")
        h.clear()
        h.manager.onForegroundResume()
        assertTrue(h.frames().isEmpty())
        h.clock.advance(40_000)
        h.manager.onForegroundResume()
        assertEquals(1, h.frames().filterIsInstance<ListFrame>().size)
        assertEquals(listOf("/a"), h.frames().filterIsInstance<Level2SubscribeFrame>().map { it.workspace })
        h.manager.onForegroundResume()
        assertEquals(1, h.frames().filterIsInstance<Level2SubscribeFrame>().size)
        assertEquals("healthy socket must not be rebuilt", 1, h.transports.size)
    }
    @Test fun heartbeatIsNotAFullSnapshotAcknowledgement() {
        val h = Harness()
        h.manager.subscribeLevel2("/a")
        h.transport.deliverText(FrameCodec.encode(Level2HeartbeatFrame(workspace = "/a", seq = 1)))
        h.clear()
        h.manager.onForegroundResume()
        assertTrue(h.frames().isEmpty())
    }
    @Test fun realSnapshotReleasesForegroundRefreshCoalescing() {
        val h = Harness()
        h.manager.subscribeLevel2("/a")
        h.transport.deliverText(FrameCodec.encode(Level2Frame(workspace = "/a", seq = 1, sessions = emptyList())))
        h.clear()
        h.manager.onForegroundResume()
        assertEquals(1, h.frames().filterIsInstance<Level2SubscribeFrame>().size)
    }
    @Test fun stoppedManagerDoesNotRestartOrSubscribeOnForeground() {
        val h = Harness()
        h.manager.stop()
        h.clear()
        h.clock.advance(80_000)
        h.manager.onForegroundResume()
        assertFalse(h.manager.subscribeLevel2("/a"))
        assertTrue(h.frames().isEmpty())
        assertEquals(1, h.transports.size)
    }
    @Test fun disconnectedSubscriptionsReplaceIntentBeforeReady() {
        val h = Harness()
        h.transport.peerFailure(IllegalStateException("synthetic drop"))
        h.manager.subscribeLevel2("/a")
        h.manager.subscribeLevel2("/b")
        h.manager.unsubscribeLevel2("/a")
        h.manager.onNetworkAvailable()
        h.ready()
        assertEquals(listOf("/b"), h.frames().filterIsInstance<Level2SubscribeFrame>().map { it.workspace })
    }
}
