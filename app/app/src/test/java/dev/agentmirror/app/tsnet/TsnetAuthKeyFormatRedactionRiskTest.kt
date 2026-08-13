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

package dev.agentmirror.app.tsnet

import dev.agentmirror.app.diag.DiagLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 长期回归闸（审查席，feat-diagnostic-log-export 前置依赖④修复后固化）：
 *
 * [TsnetAuthKeys] 的契约（TsnetAuthKeys.kt KDoc）明确写死：
 *   「不校验厂商前缀：tailscale 官方 `tskey-*` 与 headscale 纯 hex 都必须放行」
 * 也就是说这个项目**合法接受**不带 `tskey-` 前缀的纯 hex 格式凭据
 * （headscale 自建控制面场景）。本文件历史版本曾用一个 naive 的 `tskey-\S+`
 * 前缀正则证明"认前缀"式脱敏必然漏掉这种 key——[DiagLog.registerSecret] 改成
 * 精确字符串匹配（不假设前缀格式）后修复了这个缺口，这里固化成回归闸：
 * 走真实的 [DiagLog.record] → [DiagLog.exportTo] 全链路，喂一个 headscale 格式的
 * 假 key，断言导出文件里**零命中明文**。
 */
class TsnetAuthKeyFormatRedactionRiskTest {

    @Before
    fun setUp() = DiagLog.resetForTest()

    @After
    fun tearDown() = DiagLog.resetForTest()

    @Test
    fun `TsnetAuthKeys 契约确认放行不带 tskey 前缀的纯 hex key`() {
        val headscaleStyleKey = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a1"
        assertEquals(headscaleStyleKey, TsnetAuthKeys.normalizeOrNull(headscaleStyleKey))
    }

    @Test
    fun `registerSecret 精确匹配对 headscale 纯 hex key 生效,导出产物零命中`() {
        val headscaleStyleKey = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a1"
        check(TsnetAuthKeys.normalizeOrNull(headscaleStyleKey) != null) {
            "前提失效：这个 key 应当是本项目认可的合法格式"
        }

        DiagLog.registerSecret(headscaleStyleKey)
        DiagLog.record("tsnet", "dial failed, retried with key $headscaleStyleKey")

        val out = File.createTempFile("diag-headscale-redaction", ".log")
        try {
            val result = DiagLog.exportTo(out)
            assertTrue("导出应成功：$result", result is DiagLog.ExportResult.Success)
            val exported = out.readText()
            assertFalse(
                "headscale 格式（非 tskey- 前缀）的 key 不得出现在导出产物明文中；exported=$exported",
                exported.contains(headscaleStyleKey),
            )
            assertTrue(
                "导出产物应留下脱敏标记，证明这条记录被处理过而非静默丢弃；exported=$exported",
                exported.contains(DiagLog.REDACTED),
            )
        } finally {
            out.delete()
        }
    }
}
