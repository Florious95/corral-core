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

/**
 * 通知助手：一条常驻通知渠道（fg-service 知识基底 §1）。
 *
 * - [CHANNEL_PERSISTENT] 常驻通知渠道（IMPORTANCE_LOW，无声）：前台服务必须在通知栏常驻，
 *   随连接状态更新内容（已连接/重连中…），不可滑走（setOngoing）。
 *
 * 060 uproot（2026-08-15）：状态通知（blocked 唤醒 + 会话深链）随状态判定整体拔除——
 * 二级会话列表改为实时流，状态通知链路不再存在。
 *
 * 静默失效猎杀：发送/取消一律 try-catch，失败落 Log.w 可判定，绝不静默吞。
 * 线程安全：NotificationManager.notify/cancel 线程安全，可在 conn 收件线程直接调用。
 */
class NotificationHelper(context: Context) {

    private val appContext: Context = context.applicationContext
    private val nm: NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 创建常驻通知渠道（幂等；minSdk 26 = API 26，NotificationChannel 自 API 26 起可用）。 */
    fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PERSISTENT,
                "后台连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "前台服务常驻状态" },
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

    companion object {
        /** 常驻通知渠道。 */
        const val CHANNEL_PERSISTENT = "fg_persistent"

        /** 常驻通知 id（固定）。 */
        const val ID_PERSISTENT = 1

        private const val TAG = "NotificationHelper"
    }
}
