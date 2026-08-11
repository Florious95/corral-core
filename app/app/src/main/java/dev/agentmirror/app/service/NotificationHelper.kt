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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.agentmirror.app.MainActivity
import dev.agentmirror.app.R
import dev.agentmirror.app.conn.AgentState

/**
 * 通知助手：两条渠道 + 两类通知（fg-service 知识基底 §1）。
 *
 * - [CHANNEL_PERSISTENT] 常驻通知渠道（IMPORTANCE_LOW，无声）：前台服务必须在通知栏常驻，
 *   随连接状态更新内容（已连接/重连中…），不可滑走（setOngoing）。
 * - [CHANNEL_STATE] 状态通知渠道（IMPORTANCE_HIGH，可提醒）：blocked/done 沿变化时的唤醒通知，
 *   按会话 ref 取稳定通知 id，同 ref 更新同一通知（blocked→done 只刷新内容不再响铃），
 *   点按深链到对应会话页（[ACTION_OPEN_SESSION] 路由，消费方是 [MainActivity] 的
 *   handleDeepLink，经 [MainActivity.navState] 路由到会话页）。
 *
 * 静默失效猎杀：发送/取消一律 try-catch，失败落 Log.w 可判定，绝不静默吞。
 * 线程安全：NotificationManager.notify/cancel 线程安全，可在 conn 收件线程直接调用。
 */
class NotificationHelper(context: Context) {

    private val appContext: Context = context.applicationContext
    private val nm: NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 创建两条通知渠道（幂等；minSdk 26 = API 26，NotificationChannel 自 API 26 起可用）。 */
    fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PERSISTENT,
                "后台连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "前台服务常驻状态" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATE,
                "Agent 状态",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "agent 需要输入 / 任务完成时提醒" },
        )
    }

    /**
     * 构建常驻通知（不发布）：前台服务 startForeground 需要通知对象本身。
     * 点按打开应用（无会话深链）。
     */
    fun persistent(text: String): Notification =
        Notification.Builder(appContext, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Agent Mirror 后台连接")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openAppPendingIntent())
            .build()

    /**
     * 常驻通知：发布/更新前台服务常驻通知（同 id 覆盖）。
     * 失败落日志可判定，不静默吞。
     */
    fun notifyPersistent(text: String) {
        try {
            nm.notify(ID_PERSISTENT, persistent(text))
        } catch (e: RuntimeException) {
            Log.w(TAG, "persistent notification failed: ${e.message}", e)
        }
    }

    /**
     * 状态通知：blocked/done 沿变化唤醒。ref → 稳定 id，同 ref 更新同一通知
     * （blocked→done 刷新内容，setOnlyAlertOnce 不重复响铃）。失败落日志可判定。
     */
    fun notifyState(ref: String, name: String, state: AgentState) {
        try {
            val text = when (state) {
                AgentState.BLOCKED -> "需要你输入"
                AgentState.DONE -> "任务已完成"
                else -> "状态：${state.wire}"
            }
            nm.notify(
                stateNotificationId(ref),
                Notification.Builder(appContext, CHANNEL_STATE)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(openSessionPendingIntent(ref))
                    .build(),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "state notification failed (ref=$ref state=$state): ${e.message}", e)
        }
    }

    /** 取消某会话的状态通知（会话离开 blocked/done 或消失时）。 */
    fun cancelState(ref: String) {
        try {
            nm.cancel(stateNotificationId(ref))
        } catch (e: RuntimeException) {
            Log.w(TAG, "state notification cancel failed (ref=$ref): ${e.message}", e)
        }
    }

    /** 打开应用根 Activity（常驻通知点按）。 */
    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            ID_PERSISTENT,
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 深链到对应会话页：ACTION_OPEN_SESSION + ref 到 MainActivity。
     *
     * 消费方是 [MainActivity] 的 handleDeepLink（onCreate 冷启动 / onNewIntent 在屏两处
     * 读取该 action/extra 打开会话页）；此处只负责构造携带 ref 的 PendingIntent
     * （服务职责，见 fg-service 知识基底 §1）。
     */
    private fun openSessionPendingIntent(ref: String): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            stateNotificationId(ref),
            Intent(appContext, MainActivity::class.java).apply {
                action = ACTION_OPEN_SESSION
                putExtra(EXTRA_SESSION_REF, ref)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** 常驻通知渠道。 */
        const val CHANNEL_PERSISTENT = "fg_persistent"

        /** 状态通知渠道。 */
        const val CHANNEL_STATE = "fg_state"

        /** 常驻通知 id（固定）。 */
        const val ID_PERSISTENT = 1

        /** 状态通知 id 基址：ref 哈希偏移，同 ref 稳定、跨 ref 近唯一。 */
        const val ID_STATE_BASE = 1000

        /** 深链 action：打开某会话页（[MainActivity] 消费）。 */
        const val ACTION_OPEN_SESSION = "dev.agentmirror.app.action.OPEN_SESSION"

        /** 深链 extra：会话 ref（[MainActivity] 消费）。 */
        const val EXTRA_SESSION_REF = "dev.agentmirror.app.extra.SESSION_REF"

        /** 状态通知 id：ref → 稳定正整数（碰撞概率低；requestCode 同源）。 */
        fun stateNotificationId(ref: String): Int =
            ID_STATE_BASE + (ref.hashCode() and 0x7fffffff) % 100_000_000

        private const val TAG = "NotificationHelper"
    }
}
