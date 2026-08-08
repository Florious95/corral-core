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

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * tsnet 节点生命周期状态（对外只读，[TsnetManager.state]）。
 * 迁移图：Idle → Starting → Up | Error；stop() 从任意态回 Idle。
 */
sealed interface TsnetState {
    /** 未启动（初始态 / stop 后）。 */
    data object Idle : TsnetState

    /** 后端 start 进行中（控制面握手窗口）。 */
    data object Starting : TsnetState

    /** 节点已入网，[proxy] 为 loopback SOCKS5 接线凭据。 */
    data class Up(val proxy: TsnetProxy) : TsnetState

    /** 启动失败或 authkey 结构非法；可再次 start 重试。 */
    data class Error(val reason: String) : TsnetState
}

/**
 * tsnet 节点管理器：authkey 校验 → 异步起节点 → 状态流转。
 *
 * 线程语义：公开方法可从任意线程调用（内部 synchronized 串行化）；
 * [onState] 在锁内回调，实现方不得在回调里再调 manager 方法（会死锁），
 * 只做状态转发（如投递给 UI StateFlow）。
 *
 * stop-during-starting 用 generation 计数丢弃迟到结果：stop() 令代次+1，
 * 在途 start 结果落地时发现代次不匹配即丢弃并关后端，杜绝"僵尸 Up"。
 */
class TsnetManager(
    private val backend: TsnetBackend,
    /** 后端 start 是阻塞秒级调用，默认单线程执行器；测试注入手动执行器。 */
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tsnet-manager").apply { isDaemon = true }
    },
    private val onState: (TsnetState) -> Unit = {},
) {
    /** 当前状态；写入只在锁内，读允许任意线程。 */
    @Volatile
    var state: TsnetState = TsnetState.Idle
        private set

    /** 在途 start 的代次防伪票据（见类 KDoc）。 */
    private var generation = 0

    /**
     * 启动节点。返回 false 的两种情形：已在 Starting/Up（重复启动被拒，状态不变）、
     * authkey 结构非法（状态置 Error）。返回 true 表示已受理，结果经 [onState] 异步到达。
     */
    @Synchronized
    fun start(stateDir: String, hostname: String, authKey: String): Boolean {
        if (state is TsnetState.Starting || state is TsnetState.Up) return false
        val key = TsnetAuthKeys.normalizeOrNull(authKey)
        if (key == null) {
            transition(TsnetState.Error("authkey 结构非法（空白或含不可见字符）"))
            return false
        }
        val gen = ++generation
        transition(TsnetState.Starting)
        executor.execute { runStart(gen, stateDir, hostname, key) }
        return true
    }

    /** 执行器线程上的实际启动：结果落地前校验代次，迟到即丢弃并关后端。 */
    private fun runStart(gen: Int, stateDir: String, hostname: String, key: String) {
        val result = runCatching { backend.start(stateDir, hostname, key) }
        synchronized(this) {
            if (gen != generation) {
                // stop() 已插队：成功启动的节点也要关掉，不能留僵尸。
                if (result.isSuccess) runCatching { backend.close() }
                return
            }
            result.fold(
                onSuccess = { transition(TsnetState.Up(it)) },
                onFailure = { transition(TsnetState.Error(it.message ?: it.javaClass.simpleName)) },
            )
        }
    }

    /** 停节点回 Idle。Starting 期间调用依赖代次机制让迟到结果自清理。 */
    @Synchronized
    fun stop() {
        val current = state
        generation++
        if (current is TsnetState.Up) runCatching { backend.close() }
        transition(TsnetState.Idle)
    }

    /** 统一状态落点：先写 state 再回调（回调方见到的 state 与参数一致）。 */
    private fun transition(next: TsnetState) {
        state = next
        onState(next)
    }
}
