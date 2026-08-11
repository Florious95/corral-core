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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload

/**
 * 常驻连接前台服务（004/011 Android 前台服务路线；fg-service 知识基底 §1）。
 *
 * 职责是**承载生命周期**，不含业务逻辑（薄 Android 层，业务在纯 JVM 的 [StateWatcher]）：
 * - [onCreate]：建通知渠道，构造 [StateWatcher]（状态沿→状态通知），把本服务监听挂到
 *   [ServiceWire.serviceListener]（连接事件 → 常驻通知文案 + 状态守望）。
 * - [onStartCommand]：startForeground（dataSync 类型，API 29+；低版本走无类型重载，见下）
 *   + 确保共享连接启动（[ServiceWire.managerOrNull] 已存在则复用）+ 驱动时钟泵。
 * - [onDestroy]：停时钟泵、解绑 [ServiceWire.serviceListener]、释放连接（幂等）。
 *
 * **连接承接（004 无状态底线）**：本服务**不持有连接状态**——[ConnectionManager] 是
 * [ServiceWire] 的进程级单例（配置唯一来源是 SharedPreferences），服务只在每次需要时经
 * [ServiceWire.managerOrNull] 读取并驱动（[pumpOnce]）。服务被系统杀 / 被用户划掉 →
 * 冷启动重连即恢复，状态无从丢失（004「不保活、客户端无状态」）。删掉本服务这一层，
 * 产品功能仍完整（连接在 ServiceWire、UI 经 uiConnector 订阅、冷启动重连照常），
 * 只是后台期间不再有泵与通知栏常驻——前台服务是体验增强，不是正确性依赖。
 *
 * **生命周期接线（feat-fg-service-wiring）**：
 * - 启动：配对成功 / 冷启动有配置 / 进入会话 三处经 [MirrorForegroundService.start] 启动
 *   （startPersistentConnection 与 createSessionViewModel 装配入口统一触发，幂等）；
 * - 停止：用户显式断开经 [MirrorForegroundService.stop]（017 R-3 切主机即断开重连：
 *   重配新主机释放旧链路后服务继续跟随新连接；R-5 通知全局开关的停止入口即本 API）。
 * 时钟泵由本服务驱动（2s 一拍），在屏组合不再各自持有（SessionScreen 已移除 onTick）。
 *
 * 断连静默重连：conn 层 ConnectionManager 自动指数退避重连（本服务只反映状态）；重连 READY
 * 后 conn 层自动重新 list + 重放订阅（无状态免疫，004）。
 *
 * @contract
 * @pre 未注入配置（[ServiceWire.manager] 所需）时 [onStartCommand] 的 manager() 调用被
 *      runCatching 兜住（服务可启动但连接不建，不抛给系统）
 * @post [onStartCommand] 返回 START_STICKY；[onDestroy] 停泵、解绑 serviceListener、释放连接（幂等）
 * @err 连接启动失败由 conn 层退避重连消化，不在此抛
 * @inv 本服务不缓存 [ConnectionManager] 引用，每次经 [ServiceWire.managerOrNull] 读取；
 *      pumpRunnable 在 [onDestroy] 后停发
 */
class MirrorForegroundService : Service() {

    /** 通知助手：两条渠道 + 常驻/状态通知。 */
    private lateinit var notifications: NotificationHelper

    /** 状态守望：listing/delta 流 → 状态沿变化通知。 */
    private lateinit var stateWatcher: StateWatcher

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationHelper(this)
        notifications.createChannels()

        stateWatcher = StateWatcher(
            onNotify = { ref, name, state -> notifications.notifyState(ref, name, state) },
            onClear = { ref -> notifications.cancelState(ref) },
        )

        // 本服务监听挂到 ServiceWire.serviceListener：连接事件（状态→通知文案、帧→状态守望）
        // 与 UI 的 uiConnector 并行扇出。manager 不在 onCreate 建——连接归属 ServiceWire 单例，
        // 服务只在 onStartCommand 确保其启动（004：状态唯一来源是 prefs/conn 层，非本服务）。
        ServiceWire.serviceListener = connListener
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notifications.persistent("正在连接…")
        // 前台服务类型 dataSync（携带运行数据，后台状态镜像）。Android 14+ 必声（manifest 属性
        // + 此处）；类型常量 ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC 是 API 29 引入，
        // minSdk 26 直用会触发 InlinedApi（lint stage3 #11）——低版本走无类型重载（API 26-28
        // 前台服务本无类型之分），29+ 用带类型重载（Android 14 target 要求）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.ID_PERSISTENT,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NotificationHelper.ID_PERSISTENT, notification)
        }
        notifications.notifyPersistent(connectionText(ServiceWire.managerOrNull()?.state() ?: ConnectionState.CONNECTING))
        // 确保共享连接启动（幂等：manager 已存在则复用、start 非 STOPPED 不重复拨号）。
        // 配置未注入（START_STICKY 重建但 prefs 已清）时不建连接不崩，靠冷启动路径恢复。
        runCatching { ServiceWire.manager(NoopManagerListener).start() }
            .onFailure { Log.w(TAG, "start persistent connection from service: ${it.message}") }
        handler.removeCallbacks(pumpRunnable)
        handler.post(pumpRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 停时钟泵并释放连接（幂等）；前台服务随进程死亡由 Activity 冷启动重连恢复（004 无状态免疫）。
        handler.removeCallbacks(pumpRunnable)
        ServiceWire.serviceListener = null
        ServiceWire.releaseManager()
        super.onDestroy()
    }

    /**
     * 时钟泵单拍（生产 pumpRunnable 与测试共用的推进点）：重连到点判定 + 输入超时裁决。
     *
     * 归属服务（feat-fg-service-wiring）：在屏组合不再各自持有时钟泵。连接经
     * [ServiceWire.managerOrNull] 读取——服务不缓存引用（004：服务杀后由冷启动恢复，
     * 连接状态不在本服务）。
     *
     * @contract
     * @pre 无（任意时刻可调；manager 为 null 时零工作）
     * @post manager 存在时推进其重连调度与输入超时裁决（见 [dev.agentmirror.app.conn.ConnectionManager.pump]）
     * @err none
     * @inv 可重复调用；单拍成本恒定（不随舰队规模线性增长，静默经济红线见 ForegroundServiceEconomyTest）
     */
    fun pumpOnce(nowMs: Long) {
        val m = ServiceWire.managerOrNull()
        if (m != null) {
            m.pump(nowMs)
            m.resolveExpiredInputs(nowMs)
        }
    }

    /** 时钟泵：周期调用 [pumpOnce]（重连到点 + 输入超时）。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val pumpRunnable = object : Runnable {
        override fun run() {
            pumpOnce(System.currentTimeMillis())
            handler.postDelayed(this, PUMP_INTERVAL_MS)
        }
    }

    /** 连接状态映射为常驻通知文案（服务只反映状态，不决策）。 */
    private fun connectionText(state: ConnectionState): String = when (state) {
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> "正在连接…"
        ConnectionState.READY -> ServiceWire.connectionPath()?.let { "已连接 · ${it.label}" } ?: "已连接"
        ConnectionState.RECONNECTING -> "连接中断，正在重连…"
        ConnectionState.STOPPED -> "连接已停止"
    }

    /** 连接监听：连接状态 → 常驻通知文案；listing/list_delta → 状态守望。 */
    private val connListener = object : ConnectionManager.Listener {
        override fun onStateChanged(state: ConnectionState) {
            notifications.notifyPersistent(connectionText(state))
        }

        override fun onFrame(frame: FramePayload) {
            stateWatcher.onFrame(frame)
        }

        override fun onBinary(frame: BinaryFrame) {
            // 镜像通道与状态层严格解耦（008）：本服务只关心控制帧状态，忽略镜像字节。
        }

        override fun onLocalDecodeError(code: FrameError, message: String) {
            // 协议坏帧经 conn 层上浮：落日志可判定，不静默吞（静默失效猎杀）。
            Log.w(TAG, "local decode error: $code $message")
        }

        override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) {
            // 输入回执归会话页 UI（ServiceWire.uiConnector），本服务不消费。
        }

        override fun onReconnect(attempt: Int, delayMs: Long) {
            // 018 标准5 失败可见：重连中通知展示当前拨号地址 + 已试次数（attempt 从 0 起）。
            // 地址取自 ConnectionManager.dialUrl()（管理器实际拨向的地址，非 ServiceWire 配置字段）
            // ——stale-config 时期此值能直接暴露"重连正拨旧址"（改配置后仍拨旧地址）。
            val url = ServiceWire.managerOrNull()?.dialUrl() ?: "?"
            notifications.notifyPersistent("连接中断，正在重连…（第 ${attempt + 1} 次，拨号 $url）")
        }
    }

    companion object {
        private const val TAG = "MirrorForegroundService"

        /** 时钟泵周期：重连到点判定 + 输入超时裁决（2s 一拍，延迟可接受）。 */
        private const val PUMP_INTERVAL_MS = 2_000L

        /**
         * 启动前台服务（配对成功 / 冷启动有配置 / 进入会话的接线点）。
         *
         * 幂等：系统对已运行服务只投 onStartCommand，不重复实例化。startForegroundService
         * 是 minSdk 26 可用 API（本服务即为其常驻载体）。
         *
         * @contract
         * @pre 无（任意 Context 可调）
         * @post 服务被调度启动（已在运行则仅再投 onStartCommand）；无配置时服务可启动但连接不建
         * @err none（服务启动失败由系统回调 onStartCommand 处理，不在此抛）
         * @inv 可重复调用；不重复实例化服务
         */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MirrorForegroundService::class.java))
        }

        /**
         * 停止前台服务（用户显式断开 / R-5 通知开关的停止入口；feat-fg-service-wiring）。
         *
         * 017 R-3「切主机即断开重连」：重配新主机走配置变更（[ServiceWire.setConfig]）释放
         * 旧链路后继续跟随新连接，无需停服务；本 API 是「用户显式断开」的规范停止点——
         * 停止触发 [onDestroy]：停泵、解绑监听、[ServiceWire.releaseManager]（连接随之停止）。
         *
         * @contract
         * @pre 无（未运行则 stopService 为幂等 no-op）
         * @post 服务被调度停止；onDestroy 停泵、解绑 serviceListener、释放连接（幂等）
         * @err none（未运行/已停止时无操作）
         * @inv 可重复调用；与 [start] 配对使用
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorForegroundService::class.java))
        }
    }
}

/** [ServiceWire.manager] 需要的空壳监听：服务的事件经 serviceListener 槽收，不经此参数。 */
private object NoopManagerListener : ConnectionManager.Listener {
    override fun onStateChanged(state: ConnectionState) = Unit
    override fun onFrame(frame: FramePayload) = Unit
    override fun onBinary(frame: BinaryFrame) = Unit
    override fun onLocalDecodeError(code: FrameError, message: String) = Unit
    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
    override fun onReconnect(attempt: Int, delayMs: Long) = Unit
}
