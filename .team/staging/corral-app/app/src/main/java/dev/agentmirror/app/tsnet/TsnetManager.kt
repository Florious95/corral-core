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
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * tsnet 节点生命周期状态（对外只读，[TsnetManager.state]）。
 * 迁移图：Idle → Starting → Up | Error；stop() 从任意态回 Idle。
 * @contract
 * @pre none（状态对象可直接实例化，测试即用 [TsnetState.Up]/[TsnetState.Error] 构造）
 * @post [Up] 携带 [TsnetProxy] 接线凭据；[Error] 携带描述文案
 * @err none（错误面以 [Error] 状态承载，不抛异常）
 * @inv [Up] 恒携带非空 [proxy]；状态对象不可变，可跨线程安全读（authkey 剔除由
 *       [TsnetManager] 生产路径保证，非本类型约束）
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
 * @contract
 * @pre none（backend/executor/onState 由构造注入）
 * @post start() 返回 true 表示已受理，节点终态（Up/Error）经 [onState] 异步到达；
 *       返回 false 分两种：已在 Starting/Up（状态不变）或 authkey 结构非法
 *       （状态置 [TsnetState.Error]）；stop() 从任意态回 Idle
 * @err 后端启动异常不抛出，折叠为 [TsnetState.Error]（文案经 redactAuthKey 剔除 key）
 * @inv state 恒属 [TsnetState] 闭集；重复 start 在 Starting/Up 时被拒；代次不匹配的
 *       迟到结果丢弃并关后端（无僵尸 Up）
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
        if (state is TsnetState.Starting || state is TsnetState.Up) {
            // 缺陷⑤观测点：幂等守卫拦下重复 start。真实缺陷场景是 state 停在 Up（语义
            // 「曾经通」）而自愈路径被这条 guard 堵死——diag 记录被拦时的当前态，日志里
            // 能看出「ensureStarted 被调用但被拦下」。
            DiagLog.record("tsnet", "start 被幂等守卫拦下 state=$state（key 指纹相同）")
            return false
        }
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
                // gomobile/控制面错误文本不受本项目控制，可能把调用参数带回；保留可诊断
                // 原因但先剔除本次 authkey，状态随后会进入 UI，绝不能原样外泄凭证。
                onFailure = {
                    // start 可能在创建 native 节点后、读取代理信息前失败；接口约定未启动时
                    // close 也无害，因此失败路径统一 best-effort 收尾，杜绝半启动节点泄漏。
                    runCatching { backend.close() }
                    transition(TsnetState.Error(redactAuthKey(it, key)))
                },
            )
        }
    }

    /** 后端错误的 UI 安全文案：精确替换本次归一化 key，不记录也不返回原凭证。 */
    private fun redactAuthKey(error: Throwable, authKey: String): String =
        redactCauseChain(error, authKey).replace("\n", " ")

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
        // 缺陷⑤观测点：每次迁移带原因（from→to）。能看出节点状态如何、何时走到 Up 又停在
        // Up；SOCKS 拨号失败但 state 仍是 Up 的错位在日志里一目了然。
        DiagLog.record("tsnet", "state $state → $next")
        state = next
        onState(next)
    }
}

/**
 * 递归清洗整条 cause 链（前置任务③，w-diag-rev 对抗预审发现）：
 * 原 [TsnetManager.redactAuthKey] 只替换顶层 `error.message`，一旦 authkey 出现在被
 * 包裹的 **cause** 里（gomobile/native 层"重包装再抛"的常见模式），`Log.e(TAG, msg,
 * throwable)` 打印整条链就会把 key 原样带出。
 *
 * 语义：逐层取 `message`（null 回落类名）做 `[authKey] → "[redacted]"` 替换，按
 * `\ncaused by: ` 拼接；返回**不含 authKey** 的多行文本。任何要落日志/落 diag 的
 * 异常路径必须先过本函数，**绝不把原始 Throwable 直接丢给 Log**。
 * @contract
 * @pre none（error 任意 Throwable，authKey 为待脱敏的归一化 key）
 * @post 返回串遍历整条 cause 链且每个可见层都做过替换；返回串不含 authKey 原文
 * @err none（不抛异常；cause 循环（环）由 [generateSequence] 天然截断于重复节点）
 * @inv 顶层 message 与全部 cause message 均被处理；类名兜底（message 为 null 时）
 */
internal fun redactCauseChain(error: Throwable, authKey: String): String {
    val chain = generateSequence(error) { it.cause }
        .map { it.message ?: it.javaClass.simpleName }
        .joinToString("\ncaused by: ") { it.replace(authKey, "[redacted]") }
    // 空串兜底：异常无 message 且类名也被替换成空（authKey 恰等于类名，极端情况）。
    return chain.ifEmpty { "start failed" }
}
