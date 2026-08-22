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

package dev.agentmirror.app.perf

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.agentmirror.app.MainNavState
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.BinaryFrameCodec
import dev.agentmirror.app.conn.BinaryKind
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.ReconnectPolicy
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.session.AttachmentUploader
import dev.agentmirror.app.session.SessionViewModel
import dev.agentmirror.app.session.UploadOutcome
import dev.agentmirror.app.termview.TermSurfaceView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 产品接线测（t.rv.instr 条 3）：不改现有三红测。
 * 路径：openSession → beginRoute → subscribe 未就绪再 replay → onBinary 快照 → onDraw 有字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfTraceWiringTest {

    private val captured = ConcurrentLinkedQueue<Pair<String, String>>()

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        PerfTrace.resetForTest()
        captured.clear()
        PerfTrace.setSinkForTest(PerfTrace.Sink { tag, line -> captured.add(tag to line) })
    }

    @After
    fun tearDown() {
        PerfTrace.resetForTest()
        DiagLog.resetForTest()
    }

    @Test
    fun 产品链路_未就绪重放与重连可见且八事件同open_id() {
        val ref = "s1"
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory {
                FakeWebSocketTransport().also { transports.add(it) }
            },
            clock = clock,
            policy = ReconnectPolicy(random = { 0.5 }),
        )
        manager.start()
        assertEquals(1, transports.size)

        val nav = MainNavState(initialShowPairing = false)
        nav.openSession(ref, "n")
        val routeId = PerfTrace.beginRoute(ref)
        PerfTrace.routeEnter(routeId)

        val vm = SessionViewModel(
            manager,
            AttachmentUploader { _, _ -> UploadOutcome.Failure("noop") },
            "http://host:0",
            ref,
            24,
            80,
        )
        manager.setListener(vm)

        val linesAfterSubscribe = captured.map { it.second }
        assertTrue(
            "未就绪必须打 subscribe_sent emitted=0 reason=not_ready，实际=$linesAfterSubscribe",
            linesAfterSubscribe.any {
                it.contains("ev=subscribe_sent") && it.contains("emitted=0") &&
                    it.contains("reason=not_ready") && it.contains("ready=0") && it.contains("conn=1")
            },
        )

        transports.last().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val afterReady = captured.map { it.second }
        assertTrue(
            "首次发出必须 subscribe_sent emitted=1 replay=0，实际=$afterReady",
            afterReady.any {
                it.contains("ev=subscribe_sent") && it.contains("emitted=1") &&
                    it.contains("replay=0") && it.contains("ok=1")
            },
        )
        assertTrue("首次必须 geom_seed rows=24 cols=80", afterReady.any { it.contains("ev=geom_seed") && it.contains("rows=24") && it.contains("cols=80") && !it.contains("emitted=0") })

        val snap = "hello"
        transports.last().deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, ref, snap.toByteArray())),
        )

        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.sessionRef = ref
        view.nightOverride = false
        view.presenter = vm.presenter
        view.layout(0, 0, 400, 160)
        val bmp = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        bmp.recycle()

        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(PerfTrace.LAYOUT_SETTLED_QUIET_MS.toLong()))

        val openId = routeId
        val byEv = captured.map { parseKv(it.second) }.groupBy { it["ev"] }
        val eight = listOf(
            "tap", "route_enter", "subscribe_sent", "geom_seed",
            "first_frame_recv", "snapshot_applied", "first_draw", "layout_settled",
        )
        eight.forEach { ev ->
            val rows = byEv[ev].orEmpty()
            assertTrue("必须有 ev=$ev 且 open_id=$openId，实际=${captured.map { it.second }}", rows.any { it["open_id"] == openId })
        }
        val draw = byEv.getValue("first_draw").first { it["open_id"] == openId && it["emitted"] != "0" }
        assertTrue("first_draw glyphs>0 实际=$draw", (draw["glyphs"]?.toIntOrNull() ?: 0) > 0)
        val settled = byEv.getValue("layout_settled").first { it["open_id"] == openId && it["emitted"] != "0" }
        assertEquals(PerfTrace.LAYOUT_SETTLED_QUIET_MS.toString(), settled["quiet_ms"])
        assertTrue("layout_settled 必须带 rows/cols 实际=$settled", settled.containsKey("rows") && settled.containsKey("cols"))

        transports.last().peerClose(1006, "dropped")
        clock.advance(1000)
        manager.pump(clock.nowMs())
        transports.last().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val replayLines = captured.map { it.second }
        assertTrue(
            "重连重放必须再打 subscribe_sent emitted=1 replay=1，不能被 take 吞。实际=$replayLines",
            replayLines.any {
                it.contains("ev=subscribe_sent") && it.contains("emitted=1") &&
                    it.contains("replay=1") && it.contains("take_before=1")
            },
        )
    }

    @Test
    fun 产品调用点_关闭时不beginOpen且零行() {
        PerfTrace.setEnabledForTest(false)
        assertFalse(PerfTrace.isEnabled())
        val ref = "s1"
        MainNavState(false).openSession(ref, "n")
        assertEquals("", PerfTrace.beginOpen())
        assertEquals("", PerfTrace.beginRoute(ref))
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { FakeWebSocketTransport().also { transports.add(it) } },
            clock = clock,
            policy = ReconnectPolicy(random = { 0.5 }),
        )
        manager.start()
        val vm = SessionViewModel(
            manager,
            AttachmentUploader { _, _ -> UploadOutcome.Failure("noop") },
            "http://host:0",
            ref,
            24,
            80,
        )
        manager.setListener(vm)
        transports.last().deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        transports.last().deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, ref, "hello".toByteArray())),
        )
        assertTrue("关时调用点零行，实际=${captured.toList()}", captured.isEmpty())
    }

    @Test
    fun onDraw无字_打first_draw_emitted0_glyphs0() {
        val ref = "s1"
        val id = PerfTrace.beginOpen()
        PerfTrace.bind(ref, id)
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.sessionRef = ref
        view.nightOverride = false
        view.presenter = dev.agentmirror.app.termview.TermViewPresenter(
            dev.agentmirror.terminal.TerminalEmulator(20, 4),
        ) { _, _ -> }
        view.layout(0, 0, 400, 160)
        val bmp = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        bmp.recycle()
        val lines = captured.map { it.second }
        assertTrue(
            "空屏必须有 first_draw emitted=0 glyphs=0，实际=$lines",
            lines.any {
                it.contains("ev=first_draw") && it.contains("emitted=0") &&
                    it.contains("reason=glyphs_zero") && it.contains("glyphs=0")
            },
        )
    }

    @Test
    fun unbind取消layout_settled必须打reason() {
        val ref = "s1"
        val id = PerfTrace.beginOpen()
        PerfTrace.bind(ref, id)
        PerfTrace.noteReflow(ref, "subscribe", 24, 80)
        PerfTrace.unbind(ref)
        val lines = captured.map { it.second }
        assertTrue(
            "unbind 必须打 layout_settled emitted=0 reason=unbind rows/cols，实际=$lines",
            lines.any {
                it.contains("ev=layout_settled") && it.contains("emitted=0") &&
                    it.contains("reason=unbind") && it.contains("rows=24") && it.contains("cols=80")
            },
        )
    }

    private fun parseKv(line: String): Map<String, String> =
        line.trim().split(Regex("\\s+")).mapNotNull { tok ->
            val i = tok.indexOf('=')
            if (i <= 0) null else tok.substring(0, i) to tok.substring(i + 1)
        }.toMap()
}
