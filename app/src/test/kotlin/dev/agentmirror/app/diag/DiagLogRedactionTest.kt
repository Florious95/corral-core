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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 脱敏红测（硬红线，feat-diagnostic-log-export 验收第 1 条）。
 *
 * 本工程 2026-08-13 / 08-14 各发生过一次 TS authkey 泄露。测试用**自造假凭据**，
 * 绝不读 `.team/current/profiles/` 下任何 `.env`。
 *
 * ## 判别「写入点脱敏」vs「导出时过滤」
 *
 * 导出时过滤 = 内存环形缓冲里留原文、导出时才替换 —— 掩耳盗铃（绕开导出仍可读内存）。
 * 写入点脱敏 = `DiagLog.record()` 落缓冲前就替换，缓冲里已是 [REDACTED]。
 * 判别点：[snapshotForTest()]（内存缓冲）在**任何导出之前**就不得含原文。
 *
 * ## 自证（纪律⑨：新仪表要先自证它测的就是你以为的东西）
 *
 * [leakProbe_unregisteredOpaqueToken_reachesBufferVerbatim] 是**负对照**：一条无结构、
 * 未注册的随机串必须如实进缓冲——证明上面的"零命中"不是"消息根本没落缓冲"的假阳性。
 */
class DiagLogRedactionTest {

    // ---- 自造假凭据（任何时刻不得来自 .env）----
    private val pairToken = "pair-tok-9f3a2c7e1b5d4a8f" // 配对 token 形态
    private val tsAuthKey = "tskey-auth-abc123def4567890xyz" // TS authkey 形态
    private val bearerTok = "Be4r3rT0k3nXyZ99887766" // Bearer 头 token 形态
    private val opaque = "yQ7nVp3mKx2rTz9w" // 无结构随机串（负对照）

    private fun tmpExport(): File = File.createTempFile("diag-redact-", ".log").apply { deleteOnExit() }

    private fun exportedText(file: File): String {
        DiagLog.exportTo(file)
        return file.readText()
    }

    @Before
    fun setUp() {
        DiagLog.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    /**
     * ★ 判别点：注册后的配对 token，**导出前**内存缓冲就不得含原文。
     * 若实现只在导出时过滤，缓冲里会有原文 → 本断言红（掩耳盗铃被抓）。
     */
    @Test
    fun registeredToken_redactedInMemoryBuffer_beforeAnyExport() {
        DiagLog.registerSecret(pairToken)
        DiagLog.record("pair", "connecting with token=$pairToken")

        val buffered = DiagLog.snapshotForTest()
        assertTrue("夹具失效：记录未落缓冲", buffered.isNotEmpty())
        assertFalse(
            "【脱敏·写入点】内存缓冲在导出前就含原文 token——" +
                "缓冲里留原文、导出时才过滤是掩耳盗铃（导出路径可被绕过）",
            buffered.any { it.contains(pairToken) },
        )
        assertTrue("脱敏占位符必须已写入缓冲", buffered.last().contains(DiagLog.REDACTED))
    }

    /** 注册后，五条记录路径（直接 log / URL 参数 / header / 异常消息 / 堆栈）导出产物必须零命中。 */
    @Test
    fun registeredToken_redactedAcrossAllPaths_exportZeroHit() {
        DiagLog.registerSecret(pairToken)
        DiagLog.registerSecret(bearerTok)

        DiagLog.record("direct", "pair token is $pairToken")
        DiagLog.record("url", "ws://100.64.0.1:29900/pair?token=$pairToken")
        DiagLog.record("hdr", "Authorization: Bearer $bearerTok")
        DiagLog.record("exc", RuntimeException("dial rejected for $pairToken").message ?: "exc")
        DiagLog.record("stack", IllegalStateException("cred check failed for $pairToken").stackTraceToString())

        // 导出前：内存缓冲零命中。
        for (line in DiagLog.snapshotForTest()) {
            assertFalse("内存缓冲含原文配对 token", line.contains(pairToken))
            assertFalse("内存缓冲含原文 Bearer token", line.contains(bearerTok))
        }
        // 导出后：文件零命中。
        val text = exportedText(tmpExport())
        assertFalse("导出产物含配对 token", text.contains(pairToken))
        assertFalse("导出产物含 Bearer token", text.contains(bearerTok))
    }

    /** 结构兜底：TS authkey（tskey-auth-*）即使未注册也必须被拦。 */
    @Test
    fun tskeyStructuralRedaction_withoutRegistration() {
        DiagLog.record("ts", "up node with $tsAuthKey")
        val text = exportedText(tmpExport())
        assertFalse("未注册的 tskey-auth-* 必须被结构兜底脱敏", text.contains(tsAuthKey))
        assertTrue("结构兜底应替换为占位符", text.contains(DiagLog.REDACTED))
    }

    /** 结构兜底：Bearer 头 token 即使未注册也必须被拦。 */
    @Test
    fun bearerHeaderStructuralRedaction_withoutRegistration() {
        DiagLog.record("hdr", "Authorization: Bearer $bearerTok")
        val text = exportedText(tmpExport())
        assertFalse("未注册的 Bearer token 必须被结构兜底脱敏", text.contains(bearerTok))
    }

    /** 结构兜底：URI userinfo（http://user:pass@host）密码段被替换、host 保留可诊断。 */
    @Test
    fun uriUserinfoRedaction_keepsHost() {
        DiagLog.record("upload", "POST http://bob:$pairToken@100.64.0.1:8080/upload")
        val text = exportedText(tmpExport())
        assertFalse("URI userinfo 的密码段不得入日志", text.contains(pairToken))
        assertTrue(
            "userinfo 整段应替换为占位符且保留 host",
            text.contains("http://${DiagLog.REDACTED}@100.64.0.1:8080/upload"),
        )
    }

    /**
     * 负对照（自证）：无结构、未注册的随机串必须如实进缓冲。
     * 如果上面的"零命中"是因为消息根本没落缓冲（假阳性），本断言会暴露。
     */
    @Test
    fun leakProbe_unregisteredOpaqueToken_reachesBufferVerbatim() {
        DiagLog.record("probe", "opaque=$opaque")
        val buffered = DiagLog.snapshotForTest()
        assertTrue("负对照失效：记录未落缓冲", buffered.isNotEmpty())
        assertTrue(
            "负对照失败：未注册的无结构串被吞了——可能混入全局脱敏，" +
                "导致正例的零命中不可信（假阳性）",
            buffered.last().contains(opaque),
        )
    }
}
