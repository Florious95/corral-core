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

package dev.agentmirror.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * 网络可达性观察器（fix-reconnect-stale-config P0 根因② E2 缺口收口）。
 *
 * 审计缺口：ConnectivityManager.NetworkCallback **从未注册**——锁屏 WiFi 休眠断连后，
 * 退避爬到长间隔，解锁网络恢复无人打断退避 → 无限「重连中」空转（真机实证：daemon 侧
 * 全程零连接到达）。LIBRARIAN 撞库回执：需求库无退避算法条目，但**网络恢复必须打断退避**。
 *
 * 本类把 ConnectivityManager 默认网络回调桥接到 [ServiceWire.onNetworkAvailable]——
 * [ConnectionManager.onNetworkAvailable] 已实现"RECONNECTING 中立即重拨"（conn 知识基底
 * §1 钩子接口，此前全仓零调用点）。注册点跟随进程主 Activity 生命周期（[register]/
 * [unregister]），进程级单例防重复注册；不带 Looper 的重载回调在进程主线程投递，与
 * ConnectionManager 状态机同线程串行（无并发）。
 *
 * 注册失败（权限缺失/系统服务不可用）只落日志不崩溃：网络回调是加速项，退避泵本身
 * 仍按节奏重试（不静默吞，但不当硬依赖）。
 */
object NetworkConnectivityWatcher {

    private const val TAG = "NetworkConnectivityWatcher"

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 是否已注册（进程级防重复：旋转/重建多次 onCreate 只注册一次）。 */
    @Volatile
    private var registered = false

    /**
     * 注册默认网络回调。幂等（已注册直接返回）；[Context] 取系统服务，
     * 服务不可用或缺权限时落日志降级（退避泵兜底，不硬失败）。
     *
     * @contract
     * @pre 无（任意时刻可调用；重复调用幂等）
     * @post 进程级至多注册一个默认网络回调；回调已注册时直接返回不重复注册
     * @err 系统服务不可用 / 注册抛异常 → 落日志降级，不抛给调用方
     * @inv registered 标志与回调实例同生命周期（register 置位 / unregister 复位）
     */
    fun register(context: Context) {
        if (registered) return
        // feat-ts-wire：网络接线引导段顺带注入 tsnet 运行环境（幂等）。register 在
        // MainActivity.onCreate 早于冷启动 startPersistentConnection 的首次拨号执行，
        // 是冷启动路径上唯一先于拨号、且持有 Context 的可写钩子——带 authkey 的配置
        // 冷启动重新起网依赖它。
        TsnetBootstrap.install(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: run {
                Log.w(TAG, "connectivity service unavailable; network-callback retry skipped")
                return
            }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络恢复 → 打断退避立即重拨（根因② E2 缺口收口）。
                ServiceWire.onNetworkAvailable()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "register network callback: ${it.message}") }
        callback = cb
        registered = true
    }

    /**
     * 注销默认网络回调。幂等；[Context] 仅用于取系统服务。
     *
     * @contract
     * @pre 无（未注册时调用直接返回）
     * @post 已注册的回调被注销，registered 复位；未注册时无操作
     * @err 注销抛异常 → 落日志降级，不抛给调用方
     * @inv unregister 与 register 配对使用；回调实例注销后置 null
     */
    fun unregister(context: Context) {
        if (!registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = callback
        if (cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
                .onFailure { Log.w(TAG, "unregister network callback: ${it.message}") }
        }
        callback = null
        registered = false
    }

    /** 是否已注册（测试断言用）。 */
    internal fun isRegistered(): Boolean = registered
}
