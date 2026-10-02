package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionSnapshotRecoveryTest {
    private val ref = "/tmp/test\u001f%166"

    private class Fixture {
        var now = 1_000L
        val transports = mutableListOf<Transport>()
        val manager = ConnectionManager(
            ConnectionConfig("ws://test", "fixture"),
            TransportFactory { Transport().also { transports.add(it) } },
            object : Clock { override fun nowMs() = now },
        )
        init { manager.start(); ready() }
        fun ready() {
            transports.last().listener.onOpen()
            transports.last().listener.onText(FrameCodec.encode(AuthAckFrame(ok = true)))
        }
        fun at(time: Long) { now = time; manager.pump(now) }
        fun binary(ref: String, kind: BinaryKind = BinaryKind.SNAPSHOT, transport: Transport = transports.last()) {
            transport.listener.onBinary(BinaryFrameCodec.encode(BinaryFrame(kind, ref, "ready".toByteArray())))
        }
        fun listing() = transports.last().listener.onText(FrameCodec.encode(ListingFrame(1, 1, emptyList())))
    }

    /** Like OkHttp close(), this records a handshake request without a synchronous callback. */
    private class Transport : WebSocketTransport {
        override val isOpen = true
        lateinit var listener: TransportListener
        val frames = mutableListOf<FramePayload>()
        var closeReason: String? = null
        override fun start(listener: TransportListener) { this.listener = listener }
        override fun sendText(text: String): Boolean { frames.add(FrameCodec.decode(text)); return true }
        override fun sendBinary(bytes: ByteArray) = true
        override fun close(reason: String) { closeReason = reason }
        fun subscriptions() = frames.filterIsInstance<SubscribeFrame>()
    }

    @Test fun retriesOnceWithLatestGeometryThenRedialsWithoutCloseCallback() {
        val f = Fixture()
        assertTrue(f.manager.subscribe(ref, 24, 80, retainPaneSize = true))
        assertTrue(f.manager.resize(ref, 30, 100))
        val old = f.transports.single()
        f.at(2_999)
        assertEquals(1, old.subscriptions().size)
        f.at(3_000)
        assertEquals(2, old.subscriptions().size)
        assertEquals(SubscribeFrame(ref, 30, 100, retainPaneSize = true), old.subscriptions().last())
        f.at(4_000)
        assertEquals(2, old.subscriptions().size)
        f.at(5_000)
        assertEquals(2, f.transports.size)
        assertTrue(old.closeReason!!.contains("snapshot timeout"))
        f.ready()
        assertEquals(SubscribeFrame(ref, 30, 100, retainPaneSize = true), f.transports.last().subscriptions().single())
        f.binary(ref)
        f.at(20_000)
        assertEquals(2, f.transports.size)
    }

    @Test fun lateFirstPumpHonorsOriginalDeadline() {
        val f = Fixture()
        f.manager.onForegroundResume()
        f.listing()
        f.manager.subscribe(ref, 24, 80)
        f.at(7_000)
        assertEquals(2, f.transports.size)
    }

    @Test fun listingDeltaAndOtherRefsCannotAcknowledgeSnapshot() {
        val f = Fixture()
        f.manager.subscribe(ref, 24, 80)
        f.now = 2_000
        f.listing()
        f.binary(ref, BinaryKind.DELTA)
        f.binary("/tmp/test\u001f%0")
        f.at(5_000)
        assertEquals(2, f.transports.size)
    }

    @Test fun matchingSnapshotKeepsHealthyConnectionWithoutRetry() {
        val f = Fixture()
        f.manager.subscribe(ref, 24, 80)
        f.binary(ref)
        f.at(20_000)
        assertEquals(1, f.transports.size)
        assertEquals(1, f.transports.single().subscriptions().size)
    }

    @Test fun repeatedSubscribeCannotExtendBudget() {
        val f = Fixture()
        f.manager.subscribe(ref, 24, 80)
        f.at(3_000)
        f.now = 4_900
        f.manager.subscribe(ref, 30, 100)
        f.at(5_000)
        assertEquals(2, f.transports.size)
    }

    @Test fun unsubscribeAndStopCancelSnapshotWaits() {
        val f = Fixture()
        f.manager.subscribe(ref, 24, 80)
        f.manager.unsubscribe(ref)
        f.at(7_000)
        assertEquals(1, f.transports.size)
        f.manager.subscribe(ref, 24, 80)
        f.manager.stop()
        f.at(20_000)
        assertEquals(ConnectionState.STOPPED, f.manager.state())
        assertEquals(1, f.transports.size)
    }

    @Test fun closedSocketCannotClearReplayedWaitOrTriggerAnotherReconnect() {
        val f = Fixture()
        f.manager.subscribe(ref, 24, 80)
        val old = f.transports.single()
        f.at(5_000)
        f.ready()
        f.binary(ref, transport = old)
        old.listener.onClosed(1000, "late close")
        old.listener.onFailure(IllegalStateException("late failure"))
        assertEquals(2, f.transports.size)
        assertEquals(ConnectionState.READY, f.manager.state())
        f.at(9_000)
        assertEquals(3, f.transports.size)
    }

    @Test fun nonReadySubscribeIsGuardedOnlyAfterReplayIsSent() {
        val f = Fixture()
        f.transports.single().listener.onFailure(IllegalStateException("offline"))
        f.manager.subscribe(ref, 24, 80)
        f.now = 20_000
        f.manager.onNetworkAvailable()
        assertEquals(2, f.transports.size)
        f.ready()
        f.at(21_999)
        assertEquals(1, f.transports.last().subscriptions().size)
        f.at(22_000)
        assertEquals(2, f.transports.last().subscriptions().size)
        f.at(24_000)
        assertEquals(3, f.transports.size)
    }
}
