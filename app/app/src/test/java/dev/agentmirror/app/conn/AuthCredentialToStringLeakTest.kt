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

package dev.agentmirror.app.conn

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红测（审查席，对抗立场，feat-diagnostic-log-export）：
 *
 * [AuthFrame] 与 [ConnectionConfig] 都在 KDoc 里承诺"token 绝不被记录或回显"，
 * 但都是普通 data class，没有覆盖 `toString()`。Kotlin 默认生成的 `toString()`
 * 会把每个构造参数原样拼进字符串——包括 token。
 *
 * 对比全仓库其它携带凭据的 data class（[dev.agentmirror.app.pairing.QrPayload]、
 * [dev.agentmirror.app.pairing.PairingConfig]、[dev.agentmirror.app.tsnet.TsnetProxy]）
 * 都显式覆盖了 `toString()` 做 `[redacted]`——这两个类是全仓库唯一的例外。
 *
 * 这不是"如果开发席疏忽会怎样"的假设：诊断日志系统最自然的实现方式就是
 * 在 [dev.agentmirror.app.conn.Connection] / [dev.agentmirror.app.conn.ConnectionManager]
 * 收发帧或装配配置的地方加一行 `Log.d(TAG, "frame=$frame")` /
 * `Log.d(TAG, "config=$config")` 做调试可见性——这条路径一旦落地，
 * token 就会经默认 toString() 整串写入环形缓冲，脱敏无论多严密都拦不住，
 * 因为泄露发生在"记录了什么对象"这一步，而不是"记录后有没有做正则脱敏"。
 *
 * 断言现状：默认 toString() 确实原样吐出 token（本测试证明漏洞存在，非验收测试）。
 * 一旦 [AuthFrame] / [ConnectionConfig] 补上安全 toString() 覆盖，此测试即失败，
 * 届时应改写为断言"不包含"以固化修复。
 */
class AuthCredentialToStringLeakTest {

    @Test
    fun `AuthFrame 默认 toString 明文吐出 token`() {
        val fakeToken = "pairtoken-review-seat-fabricated-0001"
        val frame = AuthFrame(token = fakeToken)

        assertTrue(
            "AuthFrame.toString() 应当（但目前没有）对 token 做 [redacted] 覆盖；" +
                "实际输出=${frame}",
            frame.toString().contains(fakeToken),
        )
    }

    @Test
    fun `ConnectionConfig 默认 toString 明文吐出 token`() {
        val fakeToken = "pairtoken-review-seat-fabricated-0002"
        val config = ConnectionConfig(url = "wss://example.invalid/ws", token = fakeToken)

        assertTrue(
            "ConnectionConfig.toString() 应当（但目前没有）对 token 做 [redacted] 覆盖；" +
                "实际输出=${config}",
            config.toString().contains(fakeToken),
        )
    }
}
