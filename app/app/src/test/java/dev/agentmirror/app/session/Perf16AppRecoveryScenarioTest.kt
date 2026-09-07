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

import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.BinaryFrameCodec
import dev.agentmirror.app.conn.BinaryKind
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.OkHttpWebSocketTransport
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.terminal.TerminalColor
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * #16 App-side transport seam, using the production route rather than calling
 * VM.onBinary/manager.start/manager.subscribe from the test.  MockWebServer is
 * only the deterministic WebSocket endpoint here; the real Go S+A6 fixture is
 * a separate hosted composition obligation and is not claimed by this test.
 *
 * The first peer receives a deliberately residual screen plus a late delta and
 * is then cancelled abruptly.  The production ServiceWire -> ConnectionManager
 * -> SessionViewModel -> TerminalEmulator chain must recover through the
 * service pump, replay list/subscribe, and clear the old screen completely.
 */
class Perf16AppRecoveryScenarioTest {

    private companion object {
        const val REF = "perf16-current-ref"
        const val TOKEN = "perf16-test-token"
        const val ROWS = 40
        const val COLS = 120

        private const val STATIC_SCREEN =
            "\u001b[1m\u001b[34mRECOVERED TITLE\u001b[0m\n" +
                "\u001b[3;5m日本語 ✓\u001b[0m\n" +
                "\u001b[1;5mCURSOR_ORACLE\u001b[0m\n\n\n\n" +
                "            RECOVERY_DONE"
        private val STATIC_SNAPSHOT = (STATIC_SCREEN + "\u001b[7;26H").toByteArray()
        private val OLD_SNAPSHOT = "\u001b[2J\u001b[HOLD_RESIDUAL".toByteArray()
        private val OLD_DELTA = "\u001b[8;1mOLD_LATE_FRAME".toByteArray()
    }

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var vm: SessionViewModel? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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
        vm?.dispose()
        vm = null
        ServiceWire.uiConnector = null
        ServiceWire.listConnector = null
        ServiceWire.serviceListener = null
        ServiceWire.transportFactory = dev.agentmirror.app.service.OkHttpTransportFactory
        ServiceWire.releaseManager()
        ServiceWire.resetConfigForTest()
        server.shutdown()
    }

    private fun wsUrl(): String = server.url("/ws").toString().replaceFirst("http", "ws")

    private fun authAck(ok: Boolean = true): String =
        if (ok) {
            """{"v":1,"type":"auth_ack","payload":{"ok":true}}"""
        } else {
            """{"v":1,"type":"auth_ack","payload":{"ok":false,"reason":"invalid token"}}"""
        }

    private fun listing(): String =
        """{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[{"cwd":"/perf16","session_count":1,"sessions":[{"ref":"$REF","name":"synthetic","window_name":"perf16","window_index":"0","cwd":"/perf16","title":"","provider":"unknown","activity":"unknown","session_name":null,"health":"unknown","status":"unknown","rows":$ROWS,"cols":$COLS}]}]}}"""

    private fun binary(kind: BinaryKind, ref: String, data: ByteArray): ByteString =
        ByteString.of(*BinaryFrameCodec.encode(BinaryFrame(kind, ref, data)))

    private fun waitUntil(name: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("timed out waiting for $name", condition())
    }

    private fun visible(row: List<dev.agentmirror.terminal.Cell>): String =
        row.filter { it.width > 0 }.joinToString("") { it.text }.trimEnd()

    private fun assertStaticOracle(vm: SessionViewModel) {
        val screen = vm.emulator.snapshot()
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
        assertEquals("bold row style", true, screen.lines[2][0].style.bold)
        assertEquals(TerminalColor.Default, screen.lines[2][0].style.fg)
        assertEquals("cursor-row text", "CURSOR_ORACLE", visible(screen.lines[2]))
    }

    @Test
    fun realOkHttpAbruptClose_servicePump_reauthListSubscribeClearsStaticScreen() {
        val firstSnapshotSent = AtomicBoolean(false)
        val secondSnapshotSent = AtomicBoolean(false)

        fun upgrade(connectionNo: Int): MockResponse = MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when {
                        text.contains("\"type\":\"auth\"") -> webSocket.send(authAck())
                        text.contains("\"type\":\"list\"") -> webSocket.send(listing())
                        text.contains("\"type\":\"subscribe\"") -> {
                            if (connectionNo == 1) {
                                webSocket.send(binary(BinaryKind.SNAPSHOT, REF, OLD_SNAPSHOT))
                                firstSnapshotSent.set(true)
                                webSocket.send(binary(BinaryKind.DELTA, REF, OLD_DELTA))
                                Thread.sleep(100)
                                webSocket.cancel()
                            } else {
                                // A wrong-ref frame before the replay snapshot is the
                                // positive ref-fencing control; it must never enter vm.
                                webSocket.send(binary(BinaryKind.DELTA, "old-ref", OLD_DELTA))
                                webSocket.send(binary(BinaryKind.SNAPSHOT, REF, STATIC_SNAPSHOT))
                                secondSnapshotSent.set(true)
                            }
                        }
                    }
                }
            },
        )
        server.enqueue(upgrade(1))
        server.enqueue(upgrade(2))

        ServiceWire.setConfig(ConnectionConfig(wsUrl(), TOKEN))
        vm = createSessionViewModel(REF)
        assertNotNull("production SessionRoute factory must construct VM", vm)
        ServiceWire.uiConnector = vm
        val manager = ServiceWire.managerOrNull()
        assertNotNull("ServiceWire must own the manager", manager)

        waitUntil("first snapshot", condition = { firstSnapshotSent.get() })
        waitUntil("ready before abrupt close", condition = { manager!!.state() == ConnectionState.RECONNECTING })
        waitUntil("old residual applied", condition = {
            visible(vm!!.emulator.snapshot().lines[7]) == "OLD_LATE_FRAME"
        })

        // This is the production service pump entry point; no direct manager.pump
        // or VM callback substitutes for the runtime recovery path.
        MirrorForegroundService().pumpOnce(System.currentTimeMillis() + 2_000)

        waitUntil("second connection snapshot", condition = { secondSnapshotSent.get() })
        waitUntil("manager READY after reauth/list/subscribe", condition = { manager!!.state() == ConnectionState.READY })
        waitUntil("static screen applied", condition = {
            visible(vm!!.emulator.snapshot().lines[0]) == "RECOVERED TITLE"
        })
        assertStaticOracle(vm!!)
        assertTrue("reconnect must issue a second WebSocket upgrade", server.requestCount >= 2)
    }

    @Test
    fun realOkHttpAuthReject_stopsWithoutReconnect() {
        val authRequests = AtomicInteger(0)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("\"type\":\"auth\"")) {
                            authRequests.incrementAndGet()
                            webSocket.send(authAck(ok = false))
                            webSocket.close(1008, "invalid token")
                        }
                    }
                },
            ),
        )

        ServiceWire.setConfig(ConnectionConfig(wsUrl(), TOKEN))
        vm = createSessionViewModel(REF)
        assertNotNull(vm)
        val manager = ServiceWire.managerOrNull()
        assertNotNull(manager)
        waitUntil("auth rejection STOPPED", condition = { manager!!.state() == ConnectionState.STOPPED })
        Thread.sleep(1_200)
        assertEquals("auth rejection must not schedule reconnect", 1, authRequests.get())
        assertEquals(ConnectionState.STOPPED, manager!!.state())
    }
}
