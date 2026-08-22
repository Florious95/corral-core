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

import dev.agentmirror.app.tsnet.TsnetBackend
import dev.agentmirror.app.tsnet.TsnetProxy
import dev.agentmirror.app.tsnet.TsnetState
import dev.agentmirror.app.tsnet.TsnetWire
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * 白记审计（审查席 round2，判据来自 leader）：
 *
 * 「用户复现一次、导出一份日志，我们光看它能不能定位根因？」——对缺陷⑤
 * （内嵌 tsnet 回前台连不上）的判据是：日志里能不能**同时**看到三件事——
 * ① `TsnetWire.state` 报 `Up`；② 同一时刻 SOCKS 拨号在失败；
 * ③ `ensureStarted()` 被调用且被幂等守卫拦下。三条缺一条就定位不了。
 *
 * 本测试驱动**真实**的 [TsnetWire] + 真实的 `TsnetManager`（经 [TsnetWire] 内部持有，
 * 未 mock）+ 假后端，走两轮 `ensureStarted`：第一轮真实起网到 Up（信号①的真实产出），
 * 第二轮同 key 重复调用触发真实的幂等守卫拦截（信号③的真实产出，走的是
 * `TsnetWire.ensureStarted` 里真实的 `DiagLog.record("tsnet", "ensureStarted 被幂等守卫拦下…")`
 * 那行代码，不是本测试手写的字符串）。
 *
 * 信号②（SOCKS 拨号失败）无法在纯 JVM 单测里驱动真实 socket 失败（`TsnetSocks.connect`
 * 需要真实 TCP 连接），所以这里手写一行**与生产代码格式完全一致**的记录
 * （对照 `TsnetSocks.kt` 里 `"dial fail host=$host port=${target.port} ex=... msg=... ms=..."`
 * 的真实拼接模式），明确标注这一行不是驱动真实代码产生的，只用来补全"三信号同时存在"
 * 这个场景，不代表信号②本身的记录逻辑被验证过（那部分覆盖属于测试席职责）。
 *
 * 断言：导出文本里三个信号的关键词都在，且按时间戳可排出"先到 Up → 后续 ensureStarted
 * 被拦 + SOCKS 持续失败"的顺序——这正是用户主诉场景的日志形状。
 */
class DiagLogDefect5WhiteRecordAuditTest {

    private class UpOnceBackend : TsnetBackend {
        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy =
            TsnetProxy("127.0.0.1", 40001, "cred")
        override fun close() {}
    }

    private val direct = Executor { it.run() }

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        DiagLog.initialize(null)
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
        TsnetWire.resetForTest()
    }

    @Test
    fun `真实驱动 TsnetWire 复现缺陷⑤场景,三信号在导出文本里同时可见且可按时间排序`() {
        val backend = UpOnceBackend()
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-state-round2", "agentmirror-round2")
        TsnetWire.backendFactory = { backend }
        TsnetWire.executorForTest = direct

        // 信号①：真实起网到 Up（真实 TsnetManager.transition 落的 "state ... → Up" 记录）。
        TsnetWire.ensureStarted("tskey-defect5-fabricated")
        check(TsnetWire.state is TsnetState.Up) { "前提不成立：起网应到 Up，实际=${TsnetWire.state}" }

        // 信号②：手写、格式对照 TsnetSocks.kt 真实拼接模式（见类 KDoc 说明，非驱动真实 socket）。
        DiagLog.record(
            "socks",
            "dial fail host=100.64.0.1 port=8080 ex=SocketTimeoutException msg=connect timed out ms=3007",
        )

        // 信号③：真实幂等守卫拦截（同 key 再调一次，走 TsnetWire.ensureStarted 里真实的
        // "state is Starting/Up → DiagLog.record(...ensureStarted 被幂等守卫拦下...)" 分支）。
        TsnetWire.ensureStarted("tskey-defect5-fabricated")

        val out = File.createTempFile("diag-defect5-audit-", ".log").apply { deleteOnExit() }
        val result = DiagLog.exportTo(out)
        check(result is DiagLog.ExportResult.Success) { "导出失败：$result" }
        val lines = out.readLines()

        val upLine = lines.indexOfFirst { it.contains("[tsnet]") && it.contains("→ Up") }
        val socksFailLine = lines.indexOfFirst { it.contains("[socks]") && it.contains("dial fail") }
        val guardBlockedLine = lines.indexOfFirst { it.contains("[tsnet]") && it.contains("幂等守卫拦下") }

        assertTrue(
            "信号①缺失：日志里找不到 tsnet 状态迁移到 Up 的记录，缺陷⑤的第一个必要信号就没了。" +
                "导出全文：\n${lines.joinToString("\n")}",
            upLine >= 0,
        )
        assertTrue(
            "信号②缺失：日志里找不到 SOCKS 拨号失败的记录，光有 state=Up 判断不出链路已经断了。",
            socksFailLine >= 0,
        )
        assertTrue(
            "信号③缺失：日志里找不到 ensureStarted 被幂等守卫拦下的记录，看不出自愈路径被谁堵死。",
            guardBlockedLine >= 0,
        )
        // 顺序：Up 必须先于后续的 guard-blocked（守卫拦截的前提就是"已经在 Starting/Up"）——
        // 这是「三条信号能不能对上时间线」这个可用性要求，不是三条各自孤立存在就算数。
        assertTrue(
            "三条信号必须能按时间顺序排列（Up 在前，后续的守卫拦截在后），否则光看行号顺序也拼不出因果；" +
                "upLine=$upLine socksFailLine=$socksFailLine guardBlockedLine=$guardBlockedLine",
            upLine < guardBlockedLine,
        )
    }
}
