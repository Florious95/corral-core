package dev.agentmirror.app.conn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
        fun control(frame: FramePayload) = transports.last().listener.onText(FrameCodec.encode(frame))
        fun listing() = control(ListingFrame(1, 1, emptyList()))
        // Golden codec deliberately rejects encoding these S→C-only frames on the client.
        fun level2() = transports.last().listener.onText("""{"v":1,"type":"level2_frame","payload":{"workspace":"/tmp/test","seq":2,"sessions":[]}}""")
        fun heartbeat() = transports.last().listener.onText("""{"v":1,"type":"level2_heartbeat","payload":{"workspace":"/tmp/test","seq":2}}""")
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

    private fun assertForegroundProbeSurvives(reply: (Fixture) -> Unit) {
        val f = Fixture()
        f.manager.subscribe(ref, 24, 80)
        f.binary(ref)
        f.listing()
        f.now = 2_000
        f.manager.onForegroundResume()
        assertEquals(2, f.transports.single().frames.filterIsInstance<ListFrame>().size)
        f.now = 4_000
        reply(f)
        f.at(7_000)
        assertEquals("有效业务数据应解除探活，Listing未回不代表WS失活", 1, f.transports.size)
        assertEquals(ConnectionState.READY, f.manager.state())
        assertEquals(null, f.transports.single().closeReason)
    }

    @Test fun foregroundListingHoldWithDeltaKeepsCurrentSocket() =
        assertForegroundProbeSurvives { it.binary(ref, BinaryKind.DELTA) }

    @Test fun foregroundListingHoldWithSnapshotKeepsCurrentSocket() =
        assertForegroundProbeSurvives { it.binary(ref) }

    @Test fun foregroundListingHoldWithLevel2KeepsCurrentSocket() =
        assertForegroundProbeSurvives { it.level2() }

    @Test fun foregroundListingHoldWithHeartbeatKeepsCurrentSocket() =
        assertForegroundProbeSurvives { it.heartbeat() }

    @Test fun foregroundListingHoldWithControlReplyKeepsCurrentSocket() =
        assertForegroundProbeSurvives { it.control(InputAckFrame(reqId = 2, ok = true)) }

    @Test fun decodedBinaryProvesLivenessEvenBeforeConsumerCallback() {
        val f = Fixture()
        f.listing()
        f.manager.onForegroundResume()
        val decoded = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val previous = ConnPerf.hooks
        ConnPerf.hooks = object : ConnPerfHooks {
            override fun isEnabled() = true
            override fun emitWsBinaryRecv(frameRef: String, kind: String, bytes: Int) {
                decoded.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val receiver = Thread {
            try { f.binary(ref, BinaryKind.DELTA) } catch (error: Throwable) { failure.set(error) }
        }
        try {
            receiver.start()
            assertTrue("装置必须已成功解码，尚未传播给manager", decoded.await(5, TimeUnit.SECONDS))
            f.at(6_000)
            assertEquals("不得因解码后的回调仍在途而掐死活跃WS", 1, f.transports.size)
        } finally {
            release.countDown()
            receiver.join(5_000)
            ConnPerf.hooks = previous
        }
        assertTrue(!receiver.isAlive)
        assertEquals(null, failure.get())
    }

    @Test fun invalidFramesCannotPassForegroundProbe() {
        val f = Fixture()
        f.listing()
        f.manager.onForegroundResume()
        f.transports.single().listener.onText("not json")
        f.transports.single().listener.onText("""{"v":1,"type":"unknown","payload":{}}""")
        f.transports.single().listener.onBinary(byteArrayOf(0))
        f.at(6_000)
        assertEquals(2, f.transports.size)
        assertEquals("foreground liveness timeout", f.transports.first().closeReason)
    }

    @Test fun dataBeforeForegroundEdgeCannotPassNewProbe() {
        val f = Fixture()
        f.listing()
        f.binary(ref, BinaryKind.DELTA)
        f.manager.onForegroundResume()
        f.at(6_000)
        assertEquals(2, f.transports.size)
    }

    @Test fun closedSocketDataCannotPassReplacementSocketProbe() {
        val f = Fixture()
        f.listing()
        val old = f.transports.single()
        f.manager.onForegroundResume()
        f.at(6_000)
        f.ready()
        f.manager.onForegroundResume()
        old.listener.onText("""{"v":1,"type":"level2_heartbeat","payload":{"workspace":"/tmp/test","seq":2}}""")
        f.binary(ref, BinaryKind.DELTA, old)
        f.at(11_000)
        assertEquals(3, f.transports.size)
    }

    @Test fun healthyHeartbeatDoesNotAcknowledgeMissingSubscriptionSnapshot() {
        val f = Fixture()
        f.listing()
        f.manager.onForegroundResume()
        f.manager.subscribe(ref, 24, 80)
        f.heartbeat()
        f.at(5_000)
        assertEquals(2, f.transports.size)
        assertTrue(f.transports.first().closeReason!!.contains("subscription snapshot timeout"))
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

    @Test fun timeoutAndTransportFailureFinishOnlyOnceAcrossThreads() {
        repeat(20) {
            val closedCalls = AtomicInteger()
            val transport = Transport()
            val conn = Connection(transport, "fixture", object : Connection.Listener {
                override fun onOpened() = Unit
                override fun onReady() = Unit
                override fun onFrame(frame: FramePayload) = Unit
                override fun onBinary(frame: BinaryFrame) = Unit
                override fun onLocalDecodeError(code: FrameError, message: String) = Unit
                override fun onClosed(permanent: Boolean, reason: String) { closedCalls.incrementAndGet() }
            })
            conn.start()
            transport.listener.onOpen()
            transport.listener.onText(FrameCodec.encode(AuthAckFrame(ok = true)))
            val start = CountDownLatch(1)
            val threads = (0 until 8).map { index ->
                Thread {
                    start.await()
                    if (index % 2 == 0) conn.closeForReconnect("timeout")
                    else transport.listener.onFailure(IllegalStateException("network failure"))
                }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join(5_000); assertTrue(!it.isAlive) }
            assertEquals(1, closedCalls.get())
        }
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
