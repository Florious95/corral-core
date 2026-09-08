/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.app.session

import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.BinaryFrameCodec
import dev.agentmirror.app.conn.BinaryKind
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.OkHttpWebSocketTransport
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.terminal.Cell
import dev.agentmirror.terminal.TerminalColor
import dev.agentmirror.terminal.TextStyle
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * #16 S+A6 composition: real Go API/bridge/tmux fixture plus the real App
 * OkHttp -> ConnectionManager -> ServiceWire -> SessionViewModel -> emulator
 * route.  A localhost directional proxy freezes the first App connection
 * after its initial binary snapshot, making the server's bounded mirror path
 * fail for a real transport rather than treating a read deadline as loss.
 *
 * This test is intentionally not part of the MockWebServer A1-A5 class.  The
 * workflow starts an isolated Go api.NewServer, a private tmux socket/pane,
 * and a direct healthy protocol observer; the App connection is the only
 * peer whose transport is gated and must recover through the production pump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Perf16AppRecoveryA6ScenarioTest {

    private companion object {
        const val ROWS = 40
        const val COLS = 120
        const val REF_ENV = "PERF16_A6_REF"
        const val TOKEN_ENV = "PERF16_A6_TOKEN"
        const val PROXY_URL_ENV = "PERF16_A6_PROXY_WS_URL"
        const val DIRECT_URL_ENV = "PERF16_A6_DIRECT_WS_URL"
        const val TMUX_SOCKET_ENV = "PERF16_A6_TMUX_SOCKET"
        const val TMUX_PANE_ENV = "PERF16_A6_TMUX_PANE"
        const val STAGE_ENV = "PERF16_A6_STAGE"
        const val BURST_DONE_ENV = "PERF16_A6_BURST_DONE_FILE"
        const val BRIDGE_READY_ENV = "PERF16_A6_BRIDGE_READY_FILE"
        const val BRIDGE_RELEASE_ENV = "PERF16_A6_BRIDGE_RELEASE_FILE"
        const val PROXY_RELEASE_ENV = "PERF16_A6_PROXY_RELEASE_FILE"
        const val BURST_BYTES = 16 * 1024 * 1024

        const val OLD_MARKER = "A6_OLD_RESIDUAL"
        const val BEGIN_MARKER = "P16_A6_BURST_BEGIN"
        const val END_MARKER = "P16_A6_BURST_END"
        const val AFTER_MARKER = "P16_A6_AFTER"

    }

    private val dials = AtomicInteger(0)
    private val wireEvents = CopyOnWriteArrayList<String>()
    private val wireFailure = AtomicReference<Throwable?>(null)
    private lateinit var client: OkHttpClient
    private var vm: SessionViewModel? = null
    private var serviceController: ServiceController<MirrorForegroundService>? = null
    private var binaryObserver: SnapshotObserver? = null
    private var healthy: HealthyPeer? = null

    @Before
    fun setUp() {
        client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
        ServiceWire.uiConnector = null
        ServiceWire.listConnector = null
        ServiceWire.serviceListener = null
        ServiceWire.uploadBaseUrl = null
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        ServiceWire.transportFactory = dev.agentmirror.app.conn.TransportFactory { url ->
            val generation = dials.incrementAndGet()
            val real = OkHttpWebSocketTransport(url, client)
            // Observe the real transport, without synthesizing or replacing frames.
            object : WebSocketTransport by real {
                override fun sendText(text: String): Boolean {
                    val frame = JSONObject(text)
                    val type = frame.getString("type")
                    val ref = if (type == "subscribe") frame.getJSONObject("payload").getString("ref") else ""
                    wireEvents.add("$generation:out:$type:$ref")
                    return real.sendText(text)
                }

                override fun start(listener: TransportListener) {
                    real.start(object : TransportListener by listener {
                        override fun onText(text: String) {
                            val frame = JSONObject(text)
                            wireEvents.add("$generation:in:${frame.getString("type")}")
                            listener.onText(text)
                        }

                        override fun onBinary(bytes: ByteArray) {
                            val frame = BinaryFrameCodec.decode(bytes)
                            if (generation != dials.get()) {
                                wireFailure.compareAndSet(null, AssertionError("late binary from generation $generation"))
                            }
                            wireEvents.add("$generation:binary:${frame.kind}:${frame.ref}")
                            listener.onBinary(bytes)
                        }
                    })
                }
            }
        }
    }

    @After
    fun tearDown() {
        try {
            vm?.dispose()
        } finally {
            vm = null
            try {
                binaryObserver?.let { observer ->
                    ServiceWire.managerOrNull()?.removeBinaryListener(observer.ref, observer)
                }
            } finally {
                binaryObserver = null
                try {
                    serviceController?.destroy()
                } finally {
                    serviceController = null
                    ServiceWire.uiConnector = null
                    ServiceWire.listConnector = null
                    ServiceWire.serviceListener = null
                    ServiceWire.transportFactory = dev.agentmirror.app.service.OkHttpTransportFactory
                    try {
                        ServiceWire.releaseManager()
                    } finally {
                        try {
                            ServiceWire.resetConfigForTest()
                        } finally {
                            try {
                                healthy?.close()
                            } finally {
                                healthy = null
                                client.dispatcher.cancelAll()
                                client.dispatcher.executorService.shutdown()
                                try {
                                    assertTrue(
                                        "OkHttp executor must terminate after A6 teardown",
                                        client.dispatcher.executorService.awaitTermination(3, TimeUnit.SECONDS),
                                    )
                                } finally {
                                    client.connectionPool.evictAll()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun required(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() }
            ?: throw AssertionError("missing required hosted A6 fixture variable $name")

    private fun sendTmuxLine(line: String) {
        val socket = required(TMUX_SOCKET_ENV)
        val pane = required(TMUX_PANE_ENV)
        val send = listOf(
            listOf("tmux", "-S", socket, "-f", "/dev/null", "send-keys", "-t", pane, "-l", "--", line),
            listOf("tmux", "-S", socket, "-f", "/dev/null", "send-keys", "-t", pane, "Enter"),
        )
        for (argv in send) {
            val process = ProcessBuilder(argv).redirectErrorStream(true).start()
            try {
                val finished = process.waitFor(3, TimeUnit.SECONDS)
                assertTrue("tmux command timed out: $argv", finished)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals("tmux command failed: $output", 0, process.exitValue())
            } finally {
                if (process.isAlive) process.destroyForcibly()
                assertTrue("tmux child must exit after cleanup", process.waitFor(3, TimeUnit.SECONDS))
                process.inputStream.close()
                process.outputStream.close()
                process.errorStream.close()
            }
        }
    }

    private fun waitUntil(name: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("timed out waiting for $name", condition())
    }

    private fun visible(row: List<dev.agentmirror.terminal.Cell>): String =
        row.filter { it.width > 0 }.joinToString("") { it.text }.trimEnd()

    private fun expectedStaticGrid(): List<List<Cell>> {
        val grid = MutableList(ROWS) { MutableList(COLS) { Cell.BLANK } }

        fun put(row: Int, col: Int, text: String, style: TextStyle) {
            text.forEachIndexed { offset, character ->
                grid[row][col + offset] = Cell(character.toString(), style, 1)
            }
        }

        fun putWide(row: Int, col: Int, text: String, style: TextStyle) {
            var cursor = col
            text.forEach { character ->
                grid[row][cursor] = Cell(character.toString(), style, 2)
                grid[row][cursor + 1] = Cell("", style, 0)
                cursor += 2
            }
        }

        val titleStyle = TextStyle(fg = TerminalColor.Indexed(4), bold = true)
        val cjkStyle = TextStyle(italic = true)
        val cursorStyle = TextStyle(bold = true)
        put(0, 0, "RECOVERED TITLE", titleStyle)
        putWide(1, 0, "日本語", cjkStyle)
        put(1, 6, " ", cjkStyle)
        put(1, 7, "✓", cjkStyle)
        put(2, 0, "CURSOR_ORACLE", cursorStyle)
        put(6, 12, "RECOVERY_DONE", TextStyle.DEFAULT)
        return grid
    }

    private fun assertStaticOracle() {
        val screen = vm!!.emulator.snapshot()
        assertEquals(COLS, screen.cols)
        assertEquals(ROWS, screen.rows)
        assertEquals(25, screen.cursorX)
        assertEquals(6, screen.cursorY)
        assertTrue(screen.cursorVisible)
        assertEquals("RECOVERED TITLE", visible(screen.lines[0]))
        assertEquals("日本語 ✓", visible(screen.lines[1]))
        assertEquals("CURSOR_ORACLE", visible(screen.lines[2]))
        assertEquals("            RECOVERY_DONE", visible(screen.lines[6]))

        val expected = expectedStaticGrid()
        for (row in 0 until ROWS) {
            assertEquals("row $row width", COLS, screen.lines[row].size)
            for (col in 0 until COLS) {
                assertEquals("cell [$row,$col]", expected[row][col], screen.lines[row][col])
            }
        }
        val screenText = screen.lines.joinToString("\n", transform = ::visible)
        assertTrue("old residual must be absent", !screenText.contains(OLD_MARKER))
        assertTrue("old late frame must be absent", !screenText.contains("OLD_LATE_FRAME"))
    }

    @Test
    fun realGoServerTmuxBridgeOverflow_servicePumpReauthListSubscribeClearsStaticScreen() =
        runScenario("bridge")

    @Test
    fun realGoServerTmuxWsQueueOverflow_servicePumpReauthListSubscribeClearsStaticScreen() =
        runScenario("ws")

    private fun runScenario(stage: String) {
        assertEquals(stage, required(STAGE_ENV))
        val token = required(TOKEN_ENV)
        val ref = required(REF_ENV)
        val appUrl = if (stage == "bridge") required(DIRECT_URL_ENV) else required(PROXY_URL_ENV)
        val directUrl = required(DIRECT_URL_ENV)

        File(required("PERF16_A6_FIXTURE_DIR"), "app-start").writeText("start")
        healthy = HealthyPeer(client, directUrl, token, ref)
        healthy!!.start()
        // The healthy peer must own server connection #1 before the App dials;
        // bridge stage connection #2 is the deterministic held/lost target.
        healthy!!.awaitSnapshot(30, TimeUnit.SECONDS)
        assertEquals("healthy connection must have one initial snapshot", 1, healthy!!.snapshotCount.get())

        ServiceWire.setConfig(ConnectionConfig(appUrl, token))
        val observer = SnapshotObserver(ref, dials)
        // Create without dialing, so both state and binary observers precede
        // the production factory's first subscription and initial snapshot.
        val manager = ServiceWire.manager(observer)
        assertNotNull("ServiceWire must own the manager", manager)
        binaryObserver = observer
        manager.addBinaryListener(ref, observer)
        vm = createSessionViewModel(ref)
        assertTrue("production SessionRoute factory must construct VM", vm != null)
        ServiceWire.uiConnector = vm
        assertSame("factory must reuse the observed manager", manager, ServiceWire.managerOrNull())

        serviceController = Robolectric.buildService(MirrorForegroundService::class.java).create()
        serviceController!!.startCommand(0, 1).get()
        assertSame("service must reuse the observed manager", manager, ServiceWire.managerOrNull())

        observer.awaitSnapshot(30, TimeUnit.SECONDS)
        waitUntil("old residual applied") {
            visible(vm!!.emulator.snapshot().lines[0]).contains(OLD_MARKER)
        }
        waitUntil("App READY before source loss") { manager.state() == ConnectionState.READY }
        if (stage == "bridge") {
            waitUntil("bridge relay gate installed") { File(required(BRIDGE_READY_ENV)).exists() }
        }

        // The source's first line is deliberately held until this production
        // test drives the isolated pane, so the loss starts after both peers
        // have completed their real auth/list/subscribe handshakes.
        File(required("PERF16_A6_FIXTURE_DIR"), "source-go").writeText("go")
        sendTmuxLine("go")
        // Fixture releases its relay/writer only at the actual stage loss
        // boundary. Source flush and proxy release are not loss evidence.
        observer.awaitLoss(30, TimeUnit.SECONDS)
        healthy!!.awaitMarker(BEGIN_MARKER, 30, TimeUnit.SECONDS)
        healthy!!.awaitMarker(END_MARKER, 30, TimeUnit.SECONDS)
        // Negative control: the independently drained healthy peer must stay
        // live while only the gated target transport is reconnecting.
        assertTrue("healthy peer must stay open while target is lost", !healthy!!.closed.get())

        // Advance the actual MirrorForegroundService Handler runnable.  The
        // test does not fabricate a future clock or call manager.pump/start/
        // subscribe; the real Android scheduler crosses the reconnect delay.
        ShadowLooper.runUiThreadTasks()
        Thread.sleep(1_300)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        observer.awaitSnapshot(30, TimeUnit.SECONDS, expectedCount = 2)
        waitUntil("App READY after real Go reconnect") { manager.state() == ConnectionState.READY }
        waitUntil("recovered static screen") {
            visible(vm!!.emulator.snapshot().lines[0]) == "RECOVERED TITLE"
        }
        assertEquals("target replay must provide exactly one second snapshot", 2, observer.snapshotCount.get())
        assertStaticOracle()
        observer.assertGenerations()
        wireFailure.get()?.let { throw AssertionError("wire generation failed", it) }
        assertEquals("exactly one automatic redial", 2, dials.get())
        for (generation in 1..2) {
            val events = wireEvents.filter { it.startsWith("$generation:") }
            val requiredEvents = listOf(
                "$generation:out:auth:", "$generation:in:auth_ack",
                "$generation:out:list:", "$generation:in:listing",
            )
            requiredEvents.forEach { assertTrue("missing $it in $events", events.contains(it)) }
            assertTrue("auth must precede list", events.indexOf(requiredEvents[1]) < events.indexOf(requiredEvents[2]))
            val subscribe = "$generation:out:subscribe:$ref"
            val snapshot = "$generation:binary:SNAPSHOT:$ref"
            assertEquals("one target subscription per generation", 1, events.count { it == subscribe })
            assertEquals("one target snapshot per generation", 1, events.count { it == snapshot })
            assertTrue("subscription before snapshot", events.indexOf(subscribe) < events.indexOf(snapshot))
        }

        // The pane is held still until the replay oracle has passed.  The
        // direct healthy peer must remain live and observe output afterward.
        sendTmuxLine("release")
        healthy!!.awaitMarker(AFTER_MARKER, 10, TimeUnit.SECONDS)
        healthy!!.assertBurstContiguous(BURST_BYTES)
        assertTrue("healthy peer must not close before teardown", !healthy!!.closed.get())
        val service = serviceController!!.get()
        val handler = ReflectionHelpers.getField<android.os.Handler>(service, "handler")
        val pump = ReflectionHelpers.getField<Runnable>(service, "pumpRunnable")
        assertTrue("production pump must be scheduled before destroy", handler.hasCallbacks(pump))
        serviceController!!.destroy()
        serviceController = null
        assertTrue("destroy removes the actual pump callback", !handler.hasCallbacks(pump))
        assertTrue("service destruction releases manager", ServiceWire.managerOrNull() == null)
        assertTrue("service destruction clears pump ownership", !ServiceWire.servicePumpActive)
        val stoppedDials = dials.get()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertEquals("destroyed service must not redial", stoppedDials, dials.get())
        assertTrue("pump callback must remain absent", !handler.hasCallbacks(pump))
        File(required("PERF16_A6_FIXTURE_DIR"), "app-events.json").writeText(
            org.json.JSONArray(wireEvents).toString(),
        )
        assertEquals(ConnectionState.STOPPED, manager.state())
    }

    private class SnapshotObserver(val ref: String, private val dials: AtomicInteger) : ConnectionManager.Listener {
        val snapshotCount = AtomicInteger(0)
        private val firstSnapshot = CountDownLatch(1)
        private val replaySnapshot = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>(null)

        private val loss = CountDownLatch(1)
        private val states = CopyOnWriteArrayList<ConnectionState>()
        private val snapshots = CopyOnWriteArrayList<Int>()
        private val reconnects = AtomicInteger(0)

        override fun onStateChanged(state: ConnectionState) {
            states.add(state)
            if (state == ConnectionState.RECONNECTING) loss.countDown()
        }

        override fun onFrame(frame: FramePayload) = Unit

        override fun onBinary(frame: BinaryFrame) {
            if (frame.ref != ref) {
                failure.compareAndSet(
                    null,
                    AssertionError("target observer received wrong ref ${frame.ref}"),
                )
                return
            }
            if (frame.kind != BinaryKind.SNAPSHOT) return
            snapshots.add(dials.get())
            when (snapshotCount.incrementAndGet()) {
                1 -> firstSnapshot.countDown()
                2 -> replaySnapshot.countDown()
            }
        }

        override fun onLocalDecodeError(code: FrameError, message: String) {
            failure.compareAndSet(null, AssertionError("target decode error $code: $message"))
        }

        override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit

        override fun onReconnect(attempt: Int, delayMs: Long) {
            reconnects.incrementAndGet()
        }

        fun awaitLoss(timeout: Long, unit: TimeUnit) {
            assertTrue("no RECONNECTING event", loss.await(timeout, unit))
        }

        fun assertGenerations() {
            assertEquals("snapshots must cross two real dials", listOf(1, 2), snapshots.toList())
            assertEquals("exactly one loss schedule", 1, reconnects.get())
            val firstReady = states.indexOf(ConnectionState.READY)
            val lost = states.indexOf(ConnectionState.RECONNECTING)
            assertTrue("READY must precede loss: $states", firstReady >= 0 && lost > firstReady)
            assertTrue("new READY must follow loss: $states", states.lastIndexOf(ConnectionState.READY) > lost)
        }

        fun awaitSnapshot(timeout: Long, unit: TimeUnit, expectedCount: Int = 1) {
            val latch = when (expectedCount) {
                1 -> firstSnapshot
                2 -> replaySnapshot
                else -> throw AssertionError("unsupported snapshot count $expectedCount")
            }
            assertTrue(
                "target observer never received snapshot $expectedCount",
                latch.await(timeout, unit),
            )
            failure.get()?.let { throw AssertionError("target observer failed: ${it.message}") }
        }
    }

    private class HealthyPeer(
        private val client: OkHttpClient,
        private val url: String,
        private val token: String,
        private val ref: String,
    ) {
        val snapshotCount = AtomicInteger(0)
        private val snapshotSeen = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val beginSeen = CountDownLatch(1)
        private val endSeen = CountDownLatch(1)
        private val afterSeen = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>(null)
        private val stream = ByteArrayOutputStream()
        private var suffix = ByteArray(0)
        private var burstStartOffset: Int? = null
        private var socket: WebSocket? = null

        fun start() {
            socket = client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            protocolFrame("auth", JSONObject().put("token", token)),
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when {
                            text.contains("\"type\":\"auth_ack\"") &&
                                text.contains("\"ok\":true") -> {
                                webSocket.send(protocolFrame("list", JSONObject().put("req_id", 416)))
                            }
                            text.contains("\"type\":\"listing\"") -> {
                                webSocket.send(
                                    protocolFrame(
                                        "subscribe",
                                        JSONObject()
                                            .put("ref", ref)
                                            .put("rows", ROWS)
                                            .put("cols", COLS),
                                    ),
                                )
                            }
                            text.contains("\"type\":\"error\"") -> {
                                failure.compareAndSet(null, AssertionError("healthy peer error: $text"))
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val frame = runCatching { BinaryFrameCodec.decode(bytes.toByteArray()) }
                            .getOrElse { error ->
                                failure.compareAndSet(
                                    null,
                                    AssertionError("healthy peer received undecodable binary frame: ${error.message}"),
                                )
                                return
                            }
                        if (frame.ref != ref) {
                            failure.compareAndSet(
                                null,
                                AssertionError("healthy peer received wrong ref ${frame.ref}"),
                            )
                            return
                        }
                        when (frame.kind) {
                            BinaryKind.SNAPSHOT -> {
                                val count = snapshotCount.incrementAndGet()
                                if (count > 1) {
                                    failure.compareAndSet(
                                        null,
                                        AssertionError("healthy peer received unexpected snapshot $count"),
                                    )
                                }
                                snapshotSeen.countDown()
                            }
                            BinaryKind.DELTA -> recordDelta(frame.data)
                            BinaryKind.SCROLLBACK -> failure.compareAndSet(
                                null,
                                AssertionError("healthy peer received scrollback on live stream"),
                            )
                        }
                    }

                    private fun recordDelta(chunk: ByteArray) {
                        synchronized(stream) {
                            stream.write(chunk)
                        }
                        val combined = ByteArray(suffix.size + chunk.size)
                        suffix.copyInto(combined)
                        chunk.copyInto(combined, suffix.size)
                        val beginBytes = ("\u001b]0;$BEGIN_MARKER\u0007").toByteArray()
                        val beginIndex = combined.indexOfBytes(beginBytes)
                        if (beginIndex >= 0 && burstStartOffset == null) {
                            burstStartOffset = stream.size() - combined.size + beginIndex + beginBytes.size
                            beginSeen.countDown()
                        }
                        burstStartOffset?.let { start ->
                            val ack = File(System.getenv("PERF16_A6_FIXTURE_DIR"), "healthy-ack")
                            val next = File(ack.path + ".next")
                            try {
                                next.writeText((stream.size() - start).toString())
                                check(next.renameTo(ack)) { "healthy ack atomic rename failed" }
                            } catch (error: Exception) {
                                failure.compareAndSet(null, error)
                            }
                        }
                        if (combined.containsAscii(END_MARKER)) {
                            endSeen.countDown()
                        }
                        if (combined.containsAscii(AFTER_MARKER)) {
                            afterSeen.countDown()
                        }
                        suffix = if (combined.size <= 256) {
                            combined
                        } else {
                            combined.copyOfRange(combined.size - 256, combined.size)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        closed.set(true)
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.set(true)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        failure.compareAndSet(null, t)
                        closed.set(true)
                    }
                },
            )
        }

        fun awaitSnapshot(timeout: Long, unit: TimeUnit) {
            assertTrue("healthy peer never received an initial snapshot", snapshotSeen.await(timeout, unit))
            failure.get()?.let { throw AssertionError("healthy peer failed before initial snapshot: ${it.message}") }
            assertTrue("healthy peer closed before initial snapshot", !closed.get())
        }

        fun awaitMarker(marker: String, timeout: Long, unit: TimeUnit) {
            val latch = when (marker) {
                BEGIN_MARKER -> beginSeen
                END_MARKER -> endSeen
                AFTER_MARKER -> afterSeen
                else -> throw AssertionError("unknown healthy marker $marker")
            }
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (!latch.await(20, TimeUnit.MILLISECONDS)) {
                failure.get()?.let { throw AssertionError("healthy peer failed before $marker: ${it.message}") }
                if (closed.get()) {
                    throw AssertionError("healthy peer closed before $marker")
                }
                if (System.nanoTime() >= deadline) {
                    throw AssertionError("healthy peer never observed $marker")
                }
            }
            failure.get()?.let { throw AssertionError("healthy peer failed before $marker: ${it.message}") }
            assertTrue("healthy peer closed before $marker", !closed.get())
        }

        fun assertBurstContiguous(expectedBytes: Int) {
            failure.get()?.let { throw AssertionError("healthy peer failed before burst oracle: ${it.message}") }
            val data = synchronized(stream) { stream.toByteArray() }
            val begin = data.indexOfBytes(("\u001b]0;$BEGIN_MARKER\u0007").toByteArray())
            val endMarker = ("\u001b]0;$END_MARKER\u0007").toByteArray()
            val end = data.indexOfBytes(endMarker, begin + 1)
            assertTrue("healthy peer missed burst begin marker", begin >= 0)
            assertTrue("healthy peer missed burst end marker", end >= 0)
            val burstStart = begin + ("\u001b]0;$BEGIN_MARKER\u0007").length
            assertEquals("healthy burst byte count", expectedBytes, end - burstStart)
            for (index in burstStart until end) {
                assertEquals("healthy burst byte at $index", 'X'.code, data[index].toInt())
            }
        }

        fun close() {
            socket?.close(1000, "test teardown")
            socket?.cancel()
        }
    }
}

private fun protocolFrame(type: String, payload: JSONObject): String =
    JSONObject().put("v", 1).put("type", type).put("payload", payload).toString()

private fun ByteArray.containsAscii(value: String): Boolean =
    indexOfBytes(value.toByteArray()) >= 0

private fun ByteArray.indexOfBytes(needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty()) return from.coerceAtMost(size)
    val start = from.coerceAtLeast(0)
    if (start + needle.size > size) return -1
    outer@ for (index in start..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[index + offset] != needle[offset]) continue@outer
        }
        return index
    }
    return -1
}
