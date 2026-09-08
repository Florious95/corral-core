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

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.OkHttpWebSocketTransport
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.terminal.TerminalColor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        const val BURST_BYTES = 16 * 1024 * 1024

        const val OLD_MARKER = "A6_OLD_RESIDUAL"
        const val BEGIN_MARKER = "P16_A6_BURST_BEGIN"
        const val END_MARKER = "P16_A6_BURST_END"
        const val AFTER_MARKER = "P16_A6_AFTER"

    }

    private lateinit var client: OkHttpClient
    private var vm: SessionViewModel? = null
    private var serviceController: ServiceController<MirrorForegroundService>? = null
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
            OkHttpWebSocketTransport(url, client)
        }
    }

    @After
    fun tearDown() {
        healthy?.close()
        healthy = null
        vm?.dispose()
        vm = null
        serviceController?.destroy()
        serviceController = null
        ServiceWire.uiConnector = null
        ServiceWire.listConnector = null
        ServiceWire.serviceListener = null
        ServiceWire.transportFactory = dev.agentmirror.app.service.OkHttpTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
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
            val output = process.inputStream.bufferedReader().readText()
            assertTrue("tmux command timed out: $argv", process.waitFor(3, TimeUnit.SECONDS))
            assertEquals("tmux command failed: $output", 0, process.exitValue())
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
        for (row in 0 until ROWS) {
            assertEquals("row $row width", COLS, screen.lines[row].size)
        }
        for (row in 7 until ROWS) {
            assertEquals("blank row $row", "", visible(screen.lines[row]))
        }

        val titleStyle = screen.lines[0][0].style
        assertEquals(TerminalColor.Indexed(4), titleStyle.fg)
        assertTrue(titleStyle.bold)
        assertFalse(titleStyle.italic)
        for (col in 0 until "RECOVERED TITLE".length) {
            assertEquals("title style col $col", titleStyle, screen.lines[0][col].style)
        }
        val cjk = screen.lines[1]
        assertEquals(2, cjk[0].width)
        assertEquals(0, cjk[1].width)
        assertEquals(2, cjk[2].width)
        assertEquals(0, cjk[3].width)
        assertEquals(2, cjk[4].width)
        assertEquals(0, cjk[5].width)
        assertTrue(cjk[0].style.italic)
        assertTrue(screen.lines[2][0].style.bold)
        assertEquals(TerminalColor.Default, screen.lines[2][0].style.fg)
    }

    @Test
    fun realGoServerTmuxProxyAbort_servicePumpReauthListSubscribeClearsStaticScreen() {
        val token = required(TOKEN_ENV)
        val ref = required(REF_ENV)
        val proxyUrl = required(PROXY_URL_ENV)
        val directUrl = required(DIRECT_URL_ENV)

        healthy = HealthyPeer(client, directUrl, token, ref)
        healthy!!.start()

        ServiceWire.setConfig(ConnectionConfig(proxyUrl, token))
        vm = createSessionViewModel(ref)
        assertTrue("production SessionRoute factory must construct VM", vm != null)
        ServiceWire.uiConnector = vm
        val manager = ServiceWire.managerOrNull()
        assertTrue("ServiceWire must own the manager", manager != null)

        serviceController = Robolectric.buildService(MirrorForegroundService::class.java).create()
        val service = serviceController!!.startCommand(0, 1).get()

        waitUntil("healthy peer initial snapshot") { healthy!!.snapshotSeen.getCount() == 0L }
        waitUntil("old residual applied") {
            visible(vm!!.emulator.snapshot().lines[0]).contains(OLD_MARKER)
        }
        waitUntil("App READY before source loss") { manager!!.state() == ConnectionState.READY }

        // The source's first line is deliberately held until this production
        // test drives the isolated pane, so the loss starts after both peers
        // have completed their real auth/list/subscribe handshakes.
        sendTmuxLine("go")
        waitUntil("App transport loss") { manager!!.state() == ConnectionState.RECONNECTING }
        healthy!!.awaitMarker(BEGIN_MARKER, 30, TimeUnit.SECONDS)
        healthy!!.awaitMarker(END_MARKER, 30, TimeUnit.SECONDS)

        // This is the production Android service pump entry point.  No direct
        // manager.pump/start/subscribe call is used for the recovery step.
        service.pumpOnce(System.currentTimeMillis() + 5_000)
        waitUntil("App READY after real Go reconnect") { manager!!.state() == ConnectionState.READY }
        waitUntil("recovered static screen") {
            visible(vm!!.emulator.snapshot().lines[0]) == "RECOVERED TITLE"
        }
        assertStaticOracle()

        // The pane is held still until the replay oracle has passed.  The
        // direct healthy peer must remain live and observe output afterward.
        sendTmuxLine("release")
        healthy!!.awaitMarker(AFTER_MARKER, 10, TimeUnit.SECONDS)
        healthy!!.assertBurstContiguous(BURST_BYTES)
        assertTrue("healthy peer must not close before teardown", !healthy!!.closed.get())
    }

    private class HealthyPeer(
        private val client: OkHttpClient,
        private val url: String,
        private val token: String,
        private val ref: String,
    ) {
        val snapshotSeen = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val beginSeen = CountDownLatch(1)
        private val endSeen = CountDownLatch(1)
        private val afterSeen = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>(null)
        private val stream = ByteArrayOutputStream()
        private var suffix = ByteArray(0)
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
                        val chunk = bytes.toByteArray()
                        synchronized(stream) {
                            stream.write(chunk)
                        }
                        snapshotSeen.countDown()
                        val combined = ByteArray(suffix.size + chunk.size)
                        suffix.copyInto(combined)
                        chunk.copyInto(combined, suffix.size)
                        if (combined.containsAscii(BEGIN_MARKER)) {
                            beginSeen.countDown()
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

        fun awaitMarker(marker: String, timeout: Long, unit: TimeUnit) {
            val latch = when (marker) {
                BEGIN_MARKER -> beginSeen
                END_MARKER -> endSeen
                AFTER_MARKER -> afterSeen
                else -> throw AssertionError("unknown healthy marker $marker")
            }
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (!latch.await(20, TimeUnit.MILLISECONDS)) {
                failure.get()?.let { throw AssertionError("healthy peer failed before $marker", it) }
                if (System.nanoTime() >= deadline) {
                    throw AssertionError("healthy peer never observed $marker")
                }
            }
            failure.get()?.let { throw AssertionError("healthy peer failed before $marker", it) }
        }

        fun assertBurstContiguous(expectedBytes: Int) {
            failure.get()?.let { throw AssertionError("healthy peer failed before burst oracle", it) }
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
