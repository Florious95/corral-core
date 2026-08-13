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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长期回归闸（审查席，feat-diagnostic-log-export 前置依赖①②修复后固化）：
 *
 * [AuthFrame] 与 [ConnectionConfig] 都在 KDoc 里承诺"token 绝不被记录或回显"。
 * 曾经两者都是普通 data class，没有覆盖 `toString()`——Kotlin 默认生成的
 * `toString()` 会把每个构造参数原样拼进字符串，包括 token（本文件历史版本
 * 曾用 `assertTrue(...contains(fakeToken))` 证明过这一泄露，w-diag-dev 已修复）。
 *
 * 这两个类是诊断日志系统最容易被直接 `Log.d(TAG, "frame=$frame")` /
 * `"config=$config")` 记录的对象——一旦默认 toString() 卷土重来，token 会
 * 经环形缓冲整串外泄，任何写入点脱敏都拦不住，因为问题出在"记录了什么对象"，
 * 不是"记录后有没有做正则脱敏"。
 *
 * 断言方向：**不包含明文 token，且必须留下 `[redacted]` 之类的可见标记**——
 * 只断言"不包含"不够，因为把整个 toString() 删空或改成不透露"这里本来有敏感
 * 字段"的哑实现也能让"不包含"通过，那不是修复，是把线索抹掉。
 */
class AuthCredentialToStringLeakTest {

    @Test
    fun `AuthFrame toString 不泄露 token 且留下脱敏标记`() {
        val fakeToken = "pairtoken-review-seat-fabricated-0001"
        val frame = AuthFrame(token = fakeToken)
        val text = frame.toString()

        assertFalse("AuthFrame.toString() 不得包含明文 token；实际输出=$text", text.contains(fakeToken))
        assertTrue(
            "AuthFrame.toString() 应留下脱敏标记（如 [redacted]），证明这里本来有敏感字段被处理过，" +
                "而不是被静默删空；实际输出=$text",
            text.contains("redacted", ignoreCase = true),
        )
    }

    @Test
    fun `ConnectionConfig toString 不泄露 token 且留下脱敏标记`() {
        val fakeToken = "pairtoken-review-seat-fabricated-0002"
        val config = ConnectionConfig(url = "wss://example.invalid/ws", token = fakeToken)
        val text = config.toString()

        assertFalse("ConnectionConfig.toString() 不得包含明文 token；实际输出=$text", text.contains(fakeToken))
        assertTrue(
            "ConnectionConfig.toString() 应留下脱敏标记（如 [redacted]）；实际输出=$text",
            text.contains("redacted", ignoreCase = true),
        )
    }
}
