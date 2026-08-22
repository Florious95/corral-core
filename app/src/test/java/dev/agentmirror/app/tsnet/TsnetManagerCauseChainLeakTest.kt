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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executor

/**
 * 长期回归闸（审查席，feat-diagnostic-log-export 前置依赖③修复后固化）：
 *
 * 曾经 `TsnetManager.redactAuthKey` 只对顶层 `error.message` 做精确替换，
 * 从未触达异常的 **cause 链**——如果 authkey 出现在某个 cause 里（例如
 * gomobile 原生层把控制面握手失败包一层再抛，原始 dial 错误作为 cause 保留），
 * 旧实现完全看不见它，只有等未来诊断代码直接 `Log.e(TAG, msg, it)` 把原始
 * Throwable 交给 Log（等价于 `printStackTrace()`，会递归打印整条 cause 链）
 * 才会暴露（本文件历史版本证明过这条泄露路径，w-diag-dev 已修复）。
 *
 * 修复：新增顶层函数 [redactCauseChain]，遍历 `generateSequence(error){it.cause}`
 * 对每一层 message 都做替换。`redactAuthKey` 现在改走它。
 *
 * 断言方向：
 * 1. `redactCauseChain` 的输出深层 cause 也不含 key（这是本次修复的核心，必须绿）。
 * 2. **保留**"裸 `printStackTrace()` 含 key"这条断言——这不是回归，是不变量：
 *    原始 Throwable 对象本身永远无法被"修好"（那是它的原生内容），真正的防线是
 *    "任何代码路径都不得把原始 Throwable 直接交给 Log/环形缓冲，必须先过
 *    `redactCauseChain`"。这条断言留在这里提醒未来的人：看到裸 Throwable 被传给
 *    日志函数时要警惕，而不是误以为"反正 dump 里有 key 说明测试坏了"。
 */
class TsnetManagerCauseChainLeakTest {

    private class FakeBackend : TsnetBackend {
        var failWith: Exception? = null
        override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
            failWith?.let { throw it }
            return TsnetProxy("127.0.0.1", 1080, "cred-hex")
        }
        override fun close() {}
    }

    private class ImmediateExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    @Test
    fun `redactAuthKey 经 redactCauseChain 清洗整条 cause 链,深层 key 也不再出现`() {
        val authKey = "tskey-auth-cause-chain-fabricated"
        val backend = FakeBackend()

        // 顶层异常不含 key；key 出现在被包裹的 cause 里
        // ——这是 gomobile/native 库常见的"重新包装再抛"模式。
        val leakyCause = RuntimeException("upstream dial retried with authkey=$authKey")
        backend.failWith = IllegalStateException("control plane handshake failed", leakyCause)

        var reasonSeen: String? = null
        val manager = TsnetManager(backend, ImmediateExecutor()) { state ->
            if (state is TsnetState.Error) reasonSeen = state.reason
        }

        assertTrue(manager.start("/dir", "phone", authKey))
        val reason = reasonSeen
        checkNotNull(reason) { "manager 未落到 Error 态，测试前提不成立" }

        // 修复后：UI 展示用的 reason 字符串（经 redactCauseChain 清洗过整条 cause 链）不含 key。
        assertFalse(
            "redactAuthKey 现在经 redactCauseChain 清洗整条 cause 链，深层 key 也不应出现；reason=$reason",
            reason.contains(authKey),
        )
        assertTrue(
            "redactCauseChain 的输出应留下脱敏标记，证明深层 cause 被处理过；reason=$reason",
            reason.contains("redacted", ignoreCase = true),
        )

        // 不变量：原始 Throwable 对象本身永远无法被"修好"——它就是 native 层抛出的原文。
        // 真正的防线是"任何代码路径都不得把它直接交给 Log/环形缓冲，必须先过 redactCauseChain"。
        // 这条断言留着提醒未来的人：看到裸 Throwable 被传给日志函数时要警惕。
        val dump = StringWriter().also { backend.failWith!!.printStackTrace(PrintWriter(it)) }.toString()
        assertTrue(
            "不变量：裸 printStackTrace() 的 dump 天然含 key，这不是本测试要修的对象——" +
                "它证明的是「谁都不该把原始 Throwable 直接丢给 Log」，而不是 redactCauseChain 失败。" +
                "dump=$dump",
            dump.contains(authKey),
        )
    }
}
