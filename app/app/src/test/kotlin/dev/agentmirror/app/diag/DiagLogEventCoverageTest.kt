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

package dev.agentmirror.app.diag

import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.Connection
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.tsnet.TsnetBackend
import dev.agentmirror.app.tsnet.TsnetManager
import dev.agentmirror.app.tsnet.TsnetProxy
import dev.agentmirror.app.tsnet.TsnetSocks
import dev.agentmirror.app.tsnet.TsnetState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * 事件覆盖红测（验收第 3 条：三类事件各一条断言被记录且字段完整）。
 *
 * 三层验证，分别证明"真实生产者"、"记录管线"、"字段可恢复"：
 * 1. **接线红测**（`*_wiringProbe_emitsRecord`）：驱动**真实生产类**（[TsnetManager] /
 *    [Connection]+[FakeWebSocketTransport]）触发真实事件，断言 DiagLog 缓冲已有对应 tag 记录。
 *    开发席尚未接 DiagLog 调用点时这些是红的；接入后自动转绿。
 * 2. **字段完整**：把真实事件携带的数据经 [DiagLog.record] 落库、导出，断言导出文本里
 *    根因字段（from/to/reason、host/port/异常类型/耗时、code/reason/permanent）可恢复。
 * 3. SOCKS 拨号失败没有天然 catch 点（[TsnetSocks.handshake] 只是抛），故用**真实 REP 码异常**
 *    驱动字段完整性（见 [socksDialFailure_repCodeException_fieldsRecoverable]），并已通知开发席
 *    需要在哪里补记录点。
 */
class DiagLogEventCoverageTest {

    private class FakeBackend : TsnetBackend {
        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy =
            TsnetProxy("127.0.0.1", 1080, "cred-hex")
        override fun close() {}
    }

    private class ManualExecutor : Executor {
        private val q = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            q.addLast(command)
        }
        fun runAll() {
            while (q.isNotEmpty()) q.removeFirst().run()
        }
    }

    private fun tmpExport(): File = File.createTempFile("diag-event-", ".log").apply { deleteOnExit() }

    private fun exportedText(): String {
        val f = tmpExport()
        DiagLog.exportTo(f)
        return f.readText()
    }

    @Before
    fun setUp() {
        DiagLog.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    // ---------------------------------------------------------------------------
    // 接线红测：驱动真实生产者 → 断言 DiagLog 有对应 tag 记录（当前红，接入后绿）
    // ---------------------------------------------------------------------------

    @Test
    fun tsnetTransition_wiringProbe_emitsRecord() {
        val manager = TsnetManager(FakeBackend(), ManualExecutor()) {}
        manager.start("/dir", "phone", "tskey-auth-abc1234567")
        // 真实迁移路径已走：Idle → Starting。
        assertTrue("夹具失效：迁移未发生", manager.state is TsnetState.Starting)

        val text = exportedText()
        assertTrue(
            "【tsnet 接线红测】驱动真实 TsnetManager 启动后 DiagLog 必须有 [tsnet] 记录——" +
                "缺陷⑤要能从日志看状态迁移，tsnet 得先把它记下来",
            text.contains("[tsnet]"),
        )
    }

    @Test
    fun wsClose_wiringProbe_emitsRecord() {
        val t = FakeWebSocketTransport()
        val conn = Connection(t, "tok", noopListener())
        conn.start()
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""") // READY
        t.peerClose(1006, "abnormal closure")

        val text = exportedText()
        assertTrue(
            "【ws 接线红测】驱动真实 Connection 关闭后 DiagLog 必须有 [ws] 记录——" +
                "WS 关闭原因要可观测，conn 得先把它记下来",
            text.contains("[ws]"),
        )
    }

    // ---------------------------------------------------------------------------
    // 字段完整：真实事件数据 → record → 导出 → 根因字段可恢复
    // ---------------------------------------------------------------------------

    /** tsnet 状态迁移（含迁移原因）：from/to/reason 从导出日志可恢复。 */
    @Test
    fun tsnetTransition_fieldsComplete() {
        // 驱动真实状态机拿真实迁移（Idle→Starting）：初始态 Idle（onState 不重发初值）、
        // start 后态 Starting——"from"取迁移前真实 state，"to"取迁移后。
        val manager = TsnetManager(FakeBackend(), ManualExecutor()) {}
        assertEquals("夹具失效：初始态应为 Idle", TsnetState.Idle, manager.state)
        val from = "Idle"
        manager.start("/dir", "phone", "tskey-auth-abc1234567")
        assertEquals("夹具失效：迁移未发生", TsnetState.Starting, manager.state)
        val to = "Starting"

        DiagLog.record("tsnet", "state from=$from to=$to reason=start accepted")
        val text = exportedText()
        assertTrue("tsnet 迁移未被记录", text.contains("[tsnet]"))
        assertTrue("缺 from 字段", text.contains("from=$from"))
        assertTrue("缺 to 字段", text.contains("to=$to"))
        assertTrue("缺 reason 字段", text.contains("reason=start accepted"))
    }

    /** SOCKS 拨号失败：真实 REP 码异常 → host/port/异常类型/失败原因/耗时从导出可恢复。 */
    @Test
    fun socksDialFailure_fieldsComplete() {
        // 真实失败：脚本化服务端回 REP=0x05 connection refused。
        val script = byteArrayOf(
            0x05, 0x00, // 问候：无需认证
            0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0, // CONNECT：connection refused
        )
        val host = "100.64.0.1"
        val port = 29900
        val e = assertThrows(IOException::class.java) {
            TsnetSocks.handshake(
                ByteArrayInputStream(script), ByteArrayOutputStream(),
                host, port, "tsnet", "cred-hex",
            )
        }
        assertTrue("夹具失效：未得到 refused", e.message!!.contains("connection refused"))

        DiagLog.record(
            "socks",
            "dial host=$host port=$port exc=${e.javaClass.simpleName} msg=${e.message} ms=1200",
        )
        val text = exportedText()
        assertTrue("socks 拨号失败未被记录", text.contains("[socks]"))
        assertTrue("缺 host", text.contains("host=$host"))
        assertTrue("缺 port", text.contains("port=$port"))
        assertTrue("缺异常类型", text.contains("exc=IOException"))
        assertTrue("缺失败原因", text.contains("connection refused"))
        assertTrue("缺耗时", text.contains("ms=1200"))
    }

    /** WS 关闭原因：真实 Connection 关闭 → code/reason/permanent 从导出可恢复。 */
    @Test
    fun wsClose_fieldsComplete() {
        val t = FakeWebSocketTransport()
        val closures = mutableListOf<Pair<Boolean, String>>()
        val conn = Connection(t, "tok", noopListener { closures.add(it) })
        conn.start()
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""") // READY
        t.peerClose(1006, "abnormal closure")

        // 真实事件数据：READY 掉线 → 非永久关闭 + reason。
        assertEquals(1, closures.size)
        val (permanent, reason) = closures[0]
        assertFalse(permanent)
        assertEquals("abnormal closure", reason)

        DiagLog.record("ws", "close code=1006 reason=$reason permanent=$permanent")
        val text = exportedText()
        assertTrue("ws 关闭未被记录", text.contains("[ws]"))
        assertTrue("缺 code", text.contains("code=1006"))
        assertTrue("缺 reason", text.contains("reason=abnormal closure"))
        assertTrue("缺 permanent", text.contains("permanent=$permanent"))
    }

    /** 无操作监听（接线探针用）：只捕获关闭回调。 */
    private fun noopListener(
        onClose: (Pair<Boolean, String>) -> Unit = {},
    ): Connection.Listener = object : Connection.Listener {
        override fun onOpened() {}
        override fun onReady() {}
        override fun onFrame(frame: FramePayload) {}
        override fun onBinary(frame: BinaryFrame) {}
        override fun onLocalDecodeError(code: FrameError, message: String) {}
        override fun onClosed(permanent: Boolean, reason: String) {
            onClose(permanent to reason)
        }
    }
}
