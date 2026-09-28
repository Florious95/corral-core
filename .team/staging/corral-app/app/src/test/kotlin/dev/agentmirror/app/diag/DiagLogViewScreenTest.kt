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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * App 内展示页红测（feat-diag-inapp-view）：三条红线各一条断言。
 *
 * - 脱敏不许绕过：喂 token/authkey 进日志，展示页拿到的文本零命中。
 * - 资源有界：满缓冲（4096 条）下 [DiagLog.recentLines] 恒 ≤ 请求条数，且构造展示文本
 *   的耗时给出实测数据（不允许"应该没问题"）——本仓无 Android 模拟器可跑 Compose
 *   instrumented 测试，这里在 JVM 层测量 [DiagLog] 侧数据抽取 + [DiagLogViewState]
 *   字符串拼接的真实开销；Compose 渲染层面的有界性由设计保证：无论缓冲多大，
 *   [DiagLogViewScreen] 恒只消费 [DiagLog.recentLines] 的返回值（≤ maxRendered 条），
 *   逐行 LazyColumn 渲染，从不把整个缓冲拼成一个 Text。
 * - 静默经济：[loadDiagLogViewState] 是纯函数式单次读取，不引入线程/定时器（无需断言，
 *   看函数签名即可证——它没有 receiver 持有任何后台句柄）。
 */
class DiagLogViewScreenTest {

    @Before
    fun setUp() {
        DiagLog.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    /** 脱敏不许绕过：注册 secret 后写入含凭据的日志，展示页文本必须零命中原文。 */
    @Test
    fun displayText_neverLeaksRegisteredSecrets() {
        DiagLog.initialize(null)
        val tokenSecret = "pairing-token-abcdef1234567890"
        val authkeySecret = "ts-authkey-zzzz9999yyyy8888"
        DiagLog.registerSecret(tokenSecret)
        DiagLog.registerSecret(authkeySecret)

        DiagLog.record("pair", "connecting with token=$tokenSecret")
        DiagLog.record("tsnet", "starting tsnet authkey=$authkeySecret")
        DiagLog.record("tsnet", "Bearer $authkeySecret rejected")
        DiagLog.record("http", "GET https://user:$tokenSecret@example.com/api")

        val state = loadDiagLogViewState(maxRendered = 50)

        assertFalse("展示文本不得包含原始 token", state.displayText.contains(tokenSecret))
        assertFalse("展示文本不得包含原始 authkey", state.displayText.contains(authkeySecret))
        assertTrue("脱敏占位符必须出现", state.displayText.contains(DiagLog.REDACTED))
    }

    /** 展示页只暴露字符串，不暴露原始 Entry/缓冲对象——用返回类型本身即可验证（编译期约束）。 */
    @Test
    fun recentLines_returnsPlainStringsOnly() {
        DiagLog.initialize(null)
        DiagLog.record("t", "hello")
        val lines: List<String> = DiagLog.recentLines(10)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("hello"))
    }

    /** 只取最近 N 条：写超过 N 条后，展示状态里最旧的不在，最新的在，顺序保持时间序。 */
    @Test
    fun loadDiagLogViewState_capsAtMaxRendered_keepsNewestInOrder() {
        DiagLog.initialize(null)
        repeat(1000) { DiagLog.record("t", "evt-$it") }

        val state = loadDiagLogViewState(maxRendered = 300)

        assertEquals(1000, state.totalCount)
        assertEquals(300, state.renderedLines.size)
        assertTrue("最新记录必须在展示范围", state.renderedLines.last().contains("evt-999"))
        assertFalse("超出渲染上限的旧记录不得出现", state.displayText.contains("evt-699"))
        assertTrue("展示上限边界的记录必须出现", state.displayText.contains("evt-700"))
        assertEquals("共 1000 条，展示最近 300 条（更早的 700 条未展示）", state.hintText())
    }

    /** 缓冲未写满渲染上限时，提示文案不出现"更早"字样，总数与展示数相等。 */
    @Test
    fun hintText_noOmissionWhenBufferSmallerThanCap() {
        DiagLog.initialize(null)
        repeat(10) { DiagLog.record("t", "evt-$it") }
        val state = loadDiagLogViewState(maxRendered = 300)
        assertEquals(10, state.totalCount)
        assertEquals(10, state.renderedLines.size)
        assertEquals("共 10 条", state.hintText())
    }

    /**
     * 资源有界实测：满缓冲（4096 条，单条逼近 maxLineBytes）下，
     * [DiagLog.recentLines] 提取 + [DiagLogViewState] 拼接展示文本的真实耗时与字节数。
     * 断言给出硬数据（而非"应该没问题"）：耗时必须在数十毫秒量级（<500ms 视为异常回归），
     * 拼接后的展示文本字节数必须远小于整个 4096 条缓冲的字节数（证明"只取最近 N 条"确实
     * 生效，不是名义上限、实际仍处理全量）。
     */
    @Test
    fun fullBuffer_recentLinesExtraction_isBoundedAndMeasured() {
        DiagLog.initialize(null, DiagLog.Config(maxEntries = 4096, maxLineBytes = 2048))
        val longPayload = "x".repeat(2000)
        repeat(4096) { DiagLog.record("t", "$longPayload-$it") }
        assertEquals(4096, DiagLog.size())

        val startNs = System.nanoTime()
        val state = loadDiagLogViewState(maxRendered = DEFAULT_MAX_RENDERED)
        val text = state.displayText
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        val fullBufferBytes = DiagLog.snapshotForTest().sumOf { it.toByteArray(Charsets.UTF_8).size }
        val renderedBytes = text.toByteArray(Charsets.UTF_8).size

        println(
            "[diag-view-perf] fullBufferEntries=4096 renderedEntries=${state.renderedLines.size} " +
                "fullBufferBytes=$fullBufferBytes renderedBytes=$renderedBytes elapsedMs=$elapsedMs",
        )

        assertEquals(DEFAULT_MAX_RENDERED, state.renderedLines.size)
        assertTrue(
            "满缓冲提取+拼接耗时异常：${elapsedMs}ms（阈值 500ms）",
            elapsedMs < 500.0,
        )
        assertTrue(
            "展示文本字节数($renderedBytes)必须远小于整个缓冲字节数($fullBufferBytes)，" +
                "否则'只渲染最近 N 条'未生效",
            renderedBytes < fullBufferBytes / 4,
        )
    }
}
