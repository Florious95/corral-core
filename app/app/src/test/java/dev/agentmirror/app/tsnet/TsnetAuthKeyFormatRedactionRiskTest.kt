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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前瞻性红测（审查席，对抗立场，feat-diagnostic-log-export）：
 *
 * diag 模块尚未落地，这里没有真实的"诊断日志脱敏函数"可以直接测。但
 * [TsnetAuthKeys] 的契约（TsnetAuthKeys.kt KDoc）已经明确写死：
 *   「不校验厂商前缀：tailscale 官方 `tskey-*` 与 headscale 纯 hex 都必须放行」
 *
 * 也就是说，这个项目**合法接受**不带 `tskey-` 前缀的纯 hex 格式凭据
 * （headscale 自建控制面场景）。这条测试锚定这份真实契约，反证一种最常见的
 * "省事"脱敏实现——用 `tskey-\S+` 之类前缀正则去匹配要脱敏的字符串——
 * 在这个项目里**必然**漏掉 headscale 格式的 key。
 *
 * 这不是在测某个已存在的 bug，是在给开发席一个可执行的反例：
 * 日志系统的脱敏实现如果走"认前缀"的正则捷径，headscale 部署下的用户
 * 一用日志导出就会把 key 明文交出去，而 tskey- 格式的测试用例会全绿，
 * 看不出任何异常——直到线上事故发生。
 */
class TsnetAuthKeyFormatRedactionRiskTest {

    @Test
    fun `TsnetAuthKeys 契约确认放行不带 tskey 前缀的纯 hex key`() {
        val headscaleStyleKey = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a1"
        assertEquals(headscaleStyleKey, TsnetAuthKeys.normalizeOrNull(headscaleStyleKey))
    }

    @Test
    fun `若脱敏实现走 tskey 前缀正则,headscale 格式 key 会原样漏出`() {
        val headscaleStyleKey = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a1"
        check(TsnetAuthKeys.normalizeOrNull(headscaleStyleKey) != null) {
            "前提失效：这个 key 应当是本项目认可的合法格式"
        }

        // 代表一种常见但错误的"捷径"实现：只认 tskey- 前缀。
        val naiveTskeyOnlyRedactor = Regex("""tskey-\S+""")
        val logLine = "tsnet dial failed, retried with key $headscaleStyleKey"
        val afterNaiveRedaction = naiveTskeyOnlyRedactor.replace(logLine, "[redacted]")

        assertTrue(
            "naive 的 tskey- 前缀正则脱敏对 headscale 格式 key 完全无效——" +
                "脱敏后的行原样保留了 key：$afterNaiveRedaction。" +
                "结论：诊断日志的脱敏必须按 TsnetAuthKeys 的真实格式契约做（可见 ASCII、" +
                "长度阈值等格式特征），不能假设凭据一定带 tskey- 前缀。",
            afterNaiveRedaction.contains(headscaleStyleKey),
        )
    }
}
