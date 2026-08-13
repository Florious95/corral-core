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
 * 红测（审查席，对抗立场，feat-diagnostic-log-export）：
 *
 * [TsnetManager.redactAuthKey]（TsnetManager.kt:122）只对 `error.message`
 * 这一层字符串做精确替换，从未触达异常的 **cause 链**。
 * `TsnetState.Error.reason`（UI 展示用）因此确实是干净的——这条测试的第一个
 * 断言会通过，证明"现状不算已破防"。
 *
 * 但漏洞在于：`runStart` 拿到的原始 `Throwable`（[FakeBackend.failWith] 对应生产
 * 环境里 gomobile/tsnet 抛出的异常）从未被清洗过，只是被**丢弃**——现在没人记录
 * 它，所以安全；诊断日志系统落地后，最自然的实现方式是在这个 catch 分支旁边
 * 加一行 `Log.e(TAG, "tsnet start failed", it)` 把原始异常对象传给 Android Log
 * （这是 Android 里"记录异常"的标准写法，比手动拼 message 字符串更常见）——
 * Android Log 对待第三个 Throwable 参数的方式等价于 `printStackTrace()`：
 * 递归打印整条 cause 链的 message，而不是只打印顶层。
 *
 * 一旦 authkey 出现在某个 **cause**（而不是顶层 message）里——例如 gomobile 原生
 * 层把控制面握手失败包一层再抛，原始 dial 错误作为 cause 保留——
 * `redactAuthKey` 完全看不见它，因为它只读 `error.message`。
 *
 * 本测试用真实的 [TsnetManager] + fake 后端复现这个场景，并用
 * `printStackTrace()` 还原"如果诊断代码直接把原始 Throwable 交给 Log 会打印
 * 什么"，证明 key 原样出现在这份 dump 里。
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
    fun `redactAuthKey 清洗顶层 message,但原始 Throwable 的 cause 链仍原样携带 key`() {
        val authKey = "tskey-auth-cause-chain-fabricated"
        val backend = FakeBackend()

        // 顶层异常不含 key（貌似安全）；key 真正出现在被包裹的 cause 里
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

        // 现状：UI 展示用的 reason 字符串确实是干净的。
        assertFalse(
            "redactAuthKey 对顶层 message 的替换本身没问题，这个断言应当通过",
            reason.contains(authKey),
        )

        // 但原始 Throwable（诊断日志系统一旦直接记录它就会用到）从未被清洗：
        val dump = StringWriter().also { backend.failWith!!.printStackTrace(PrintWriter(it)) }.toString()
        assertTrue(
            "cause 链里的 key 完整出现在原始 Throwable 的标准 dump 中——" +
                "只要诊断代码在这个 catch 分支旁多写一行 Log.e(TAG, msg, it) 把原始异常" +
                "（而不是已脱敏的 reason 字符串）传给 Log，凭据就会经 cause chain 原样落盘，" +
                "现有的 redactAuthKey 完全拦不住。dump=$dump",
            dump.contains(authKey),
        )
    }
}
