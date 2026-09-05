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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor

/**
 * tsnet 进程级接线点（feat-ts-wire；同构 ServiceWire：service/pairing 层之外唯一入口）。
 *
 * 生命周期裁定：节点一经起网便随进程存活（常驻连接的 tailnet 拨号随时要用，
 * 配对页离屏不停），不提供 UI 停网入口；换 key（重新配对/扫新码）才重启节点。
 *
 * 分层红线（TsnetBackend KDoc）：默认后端工厂才触达 gomobile native；JVM 单测
 * 一律注入假件（[backendFactory]），绝不加载 native。本对象自身纯 JVM 可测。
 *
 * authkey 红线（协议 §2.1，同 token §9 级）：本对象绝不记录/透出 key 值；
 * 状态回调只携带 [TsnetState]（Error 文案由 TsnetManager 保证不含 key）。
 */
object TsnetWire {

    /**
     * 节点运行环境（Android 侧注入：stateDir=filesDir/tsnet 状态根，hostname=设备名归一化）。
     * 用纯字符串而非 Context，保持本包 JVM 可测；注入点见 service.TsnetBootstrap。
     */
    data class Environment(val stateDir: String, val hostname: String)

    /** 运行环境（进程内一次注入即可；未注入时 ensureStarted 显式 Error，003 失败可见）。 */
    @Volatile
    var environment: Environment? = null

    /** 后端工厂（默认真 gomobile；测试注入假件——native 隔离红线）。 */
    @Volatile
    var backendFactory: () -> TsnetBackend = { GomobileTsnetBackend() }

    /** 仅测试用：注入直通执行器让起网同步完成（生产 null = TsnetManager 默认单线程）。 */
    @Volatile
    var executorForTest: Executor? = null

    /** 当前节点状态（transport 工厂按它选路；写入只在 manager 回调）。 */
    @Volatile
    var state: TsnetState = TsnetState.Idle
        private set

    /** UI 侧状态监听（PairingRoute 挂 PairingViewModel::onTsnetState；单槽足够）。 */
    @Volatile
    var stateListener: ((TsnetState) -> Unit)? = null

    /**
     * 冷启动 tailnet 首拨的一次性等待者。持久连接只有一个配置，后来的等待覆盖旧配置正是
     * 重配语义；PairingRoute 的常驻 UI 监听仍走 [stateListener]，两者互不抢槽。
     */
    private var settledListener: ((TsnetState) -> Unit)? = null

    /** 节点管理器（懒建；换 key 时重建）。 */
    private var manager: TsnetManager? = null

    /** 当前节点使用的 key（trim 后）；幂等判定与换 key 重启的依据。 */
    private var currentKey: String? = null

    /** READY 期间只持久化最新待用值；下一连接代次开始前才消费。 */
    @Volatile
    private var pendingKey: String? = null

    /**
     * 确保节点以 [authKey] 起网（扫码/手填/冷启动三入口共用）：
     * - 同 key 且已在 Starting/Up：幂等 no-op（重复扫码/冷启动不重复起网）；
     * - 换 key：停旧节点重建重起（重新配对语义，同 ServiceWire.setConfig 重建先例）；
     * - 环境未注入：显式 Error（003 失败可见，不静默不崩溃）。
     */
    @Synchronized
    fun ensureStarted(authKey: String) {
        val key = authKey.trim()
        // 空 key 是明确的禁用请求：不允许旧 manager 的 Up 状态继续作为候选。
        if (key.isEmpty()) {
            pendingKey = null
            manager?.stop()
            manager = null
            currentKey = null
            onState(TsnetState.Idle)
            return
        }
        // 凭据脱敏前置（registerSecret 坑一：注册前窗口）：值刚进本方法就注册，把
        // 「值在内存」到「registerSecret 生效」的窗口压到零——本方法内任何 record 都已被
        // 脱敏兜住。注册表本身绝不进缓冲/导出（private，无 toString 暴露面）。
        DiagLog.registerSecret(key)
        val env = environment
        if (env == null) {
            onState(TsnetState.Error("tsnet 环境未初始化（内部接线缺陷，请重启 App）"))
            return
        }
        val m = manager
        if (m != null && key == currentKey && (m.state is TsnetState.Starting || m.state is TsnetState.Up)) {
            // 缺陷⑤核心观测点：ensureStarted 被调用但被幂等守卫拦下。真机复现的「回前台
            // 永远连不上」若根因是节点停在 Up 而实际链路断了，这条记录会让日志里出现
            // 「ensureStarted 被拦 state=Up」而 SOCKS 拨号持续失败——两个信号对撞即定位。
            // 不记录 key 任何片段（含前缀），只记是否被拦与当前态。
            DiagLog.record("tsnet", "ensureStarted 被幂等守卫拦下（重复 key，state=${m.state}）")
            return
        }
        // 换 key 或上次失败：停旧建新。stop() 的 Idle 回调经 onState 短暂可见，随后 Starting 覆盖。
        m?.stop()
        currentKey = key
        val exec = executorForTest
        val created = if (exec != null) {
            TsnetManager(backend = backendFactory(), executor = exec, onState = ::onState)
        } else {
            TsnetManager(backend = backendFactory(), onState = ::onState)
        }
        manager = created
        created.start(stateDirForKey(env.stateDir, key), env.hostname, key)
    }

    /**
     * READY 期间更新配置只写待用值，不 stop/close 当前节点；下一代连接开始时调用
     * [applyPendingKey] 才会启用它。这是 R2 的关键边界。
     */
    @Synchronized
    fun stagePendingKey(authKey: String) {
        val key = authKey.trim()
        if (key.isNotEmpty()) DiagLog.registerSecret(key)
        pendingKey = key
    }

    /** Consume the latest staged key exactly at a new connection generation. */
    @Synchronized
    fun applyPendingKey() {
        val staged = pendingKey ?: return
        pendingKey = null
        if (staged.isEmpty()) {
            ensureStarted("")
        } else if (staged != currentKey || manager == null) {
            ensureStarted(staged)
        }
    }

    /** True only while the current manager is actually Starting/Up. */
    @Synchronized
    fun hasActiveNode(): Boolean = manager?.state is TsnetState.Starting || manager?.state is TsnetState.Up

    /** Read a typed peer snapshot; unsupported/failed is fail-closed. */
    fun peerSnapshot(knownId: String? = null, cursor: String? = null): TsPeerSnapshot =
        synchronized(this) { manager }
            ?.let { runCatching { it.peerSnapshot(knownId, cursor) }.getOrElse { TsPeerSnapshot(emptyList(), null, false) } }
            ?: TsPeerSnapshot(emptyList(), null, false)

    /**
     * tsnet 会在已有持久节点可运行时忽略新 authkey；按 key 的 SHA-256 指纹隔离状态，
     * 才能让重配到另一 tailnet 真正注册新节点。目录名不含凭证明文且同 key 跨冷启动稳定。
     */
    private fun stateDirForKey(root: String, authKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(authKey.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 0x0f])
            }
        }
        return File(root, "node-$hex").path
    }

    /**
     * manager 状态回调（在 manager 锁内到达，只做转发不回调 manager——死锁纪律）：
     * 落 [state] → 转投 UI 监听。SOCKS 认证不走全局 Authenticator（Android libcore
     * 内建 SOCKS 客户端认证不生效，模拟器实证）——由 [TsnetDial.socketFactoryFor]
     * 按拨号逐连接注入自实现握手（[TsnetSocks]），零全局态。
     */
    private fun onState(next: TsnetState) {
        val uiListener: ((TsnetState) -> Unit)?
        val oneShot: ((TsnetState) -> Unit)?
        synchronized(this) {
            state = next
            uiListener = stateListener
            oneShot = if (next is TsnetState.Up || next is TsnetState.Error) {
                settledListener.also { settledListener = null }
            } else {
                null
            }
        }
        uiListener?.invoke(next)
        oneShot?.invoke(next)
    }

    /**
     * 节点到达 Up/Error 时回调一次；注册时已经终态则立即补播。用于 tailnet 冷启动避免
     * Starting 阶段先直拨后因无时钟泵永久卡在重连态。回调不携带 authkey。
     */
    internal fun whenSettled(listener: (TsnetState) -> Unit) {
        val current = synchronized(this) {
            when (val s = state) {
                is TsnetState.Up, is TsnetState.Error -> s
                else -> {
                    settledListener = listener
                    null
                }
            }
        }
        current?.let(listener)
    }

    /**
     * hostname 归一化：设备型号（Build.MODEL 常含空格/大写）→ DNS 友好节点名，
     * 统一 agentmirror- 前缀便于 tailnet 管理台辨认；全非法字符时兜底 device。
     */
    fun sanitizeHostname(raw: String): String {
        val cleaned = raw.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
        return "agentmirror-" + cleaned.ifEmpty { "device" }
    }

    /** 仅测试用：复位全部进程级状态（单例泄漏会污染后续用例，ServiceWire 同款纪律）。 */
    @Synchronized
    internal fun resetForTest() {
        manager?.stop()
        manager = null
        currentKey = null
        pendingKey = null
        environment = null
        backendFactory = { GomobileTsnetBackend() }
        executorForTest = null
        stateListener = null
        settledListener = null
        state = TsnetState.Idle
    }
}
