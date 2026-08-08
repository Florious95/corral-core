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
import android.content.Intent
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
 * - [onCreate]：建通知渠道，构造 [StateWatcher]（状态沿→状态通知），持久 [ConnectionManager]
 *   （来自 [ServiceWire]，UI 侧经 [ServiceWire.uiConnector] 订阅同一连接）。
 * - [onStartCommand]：startForeground（dataSync 类型，常驻通知）+ 启动连接并驱动时钟泵
 *   （[ConnectionManager.pump] 重连到点 + [ConnectionManager.resolveExpiredInputs] 输入超时）。
 *   电量策略（004 裁定）：**是否启动本服务**由 UI/配对层决定（仅在有活跃订阅或用户开启
 *   后台守望时 startService；无订阅时 stop），本服务自身不决策。
 * - [onDestroy]：停时钟泵、释放连接（幂等）、停用通知渠道通知。
 *
 * 断连静默重连：conn 层 ConnectionManager 自动指数退避重连（本服务只反映状态，见
 * 任务目标「断连静默重连（conn 层已管，服务只反映）」）；重连 READY 后 conn 层自动
 * 重新 list + 重放订阅（无状态免疫，004）。服务被系统杀 → 冷启动重连即恢复，无状态，
 * 没有丢失可言。
 */
class MirrorForegroundService : Service() {

    /** 通知助手：两条渠道 + 常驻/状态通知。 */
    private lateinit var notifications: NotificationHelper

    /** 状态守望：listing/delta 流 → 状态沿变化通知。 */
    private lateinit var stateWatcher: StateWatcher

    /** 连接管理器（持久单例，UI 侧经 [ServiceWire.uiConnector] 共享）。 */
    private var manager: ConnectionManager? = null

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationHelper(this)
        notifications.createChannels()

        stateWatcher = StateWatcher(
            onNotify = { ref, name, state -> notifications.notifyState(ref, name, state) },
            onClear = { ref -> notifications.cancelState(ref) },
        )

        manager = ServiceWire.manager(connListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notifications.persistent("正在连接…")
        // 前台服务类型 dataSync：携带运行数据（后台状态镜像），Android 14+ 必声（manifest 属性 + 此处）。
        ServiceCompat.startForeground(
            this,
            NotificationHelper.ID_PERSISTENT,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        notifications.notifyPersistent(connectionText(manager?.state() ?: ConnectionState.CONNECTING))
        // 启动连接（幂等：非 STOPPED 时不重复）并驱动重连/输入超时时钟泵。
        manager?.start()
        handler.post(pumpRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 停时钟泵并释放连接（幂等）；前台服务随进程死亡由 Activity 冷启动重连恢复（004 无状态免疫）。
        handler.removeCallbacks(pumpRunnable)
        ServiceWire.releaseManager()
        manager = null
        super.onDestroy()
    }

    /** 时钟泵：周期推进 ConnectionManager（重连到点触发 + 输入超时裁决）。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val pumpRunnable = object : Runnable {
        override fun run() {
            val m = manager
            if (m != null) {
                val now = System.currentTimeMillis()
                m.pump(now)
                m.resolveExpiredInputs(now)
            }
            handler.postDelayed(this, PUMP_INTERVAL_MS)
        }
    }

    /** 连接状态映射为常驻通知文案（服务只反映状态，不决策）。 */
    private fun connectionText(state: ConnectionState): String = when (state) {
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> "正在连接…"
        ConnectionState.READY -> "已连接"
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
            notifications.notifyPersistent("连接中断，正在重连…（$attempt）")
        }
    }

    companion object {
        private const val TAG = "MirrorForegroundService"

        /** 时钟泵周期：重连到点判定 + 输入超时裁决（2s 一拍，延迟可接受）。 */
        private const val PUMP_INTERVAL_MS = 2_000L
    }
}
