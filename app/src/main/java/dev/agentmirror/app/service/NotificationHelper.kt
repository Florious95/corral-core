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

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.agentmirror.app.MainActivity
import dev.agentmirror.app.R
import dev.agentmirror.app.notify.NotificationRecord

/**
 * 通知助手：常驻通知渠道 + Agent 任务消息渠道（fg-service 知识基底 §1、契约 §9）。
 *
 * - [CHANNEL_PERSISTENT] 常驻通知渠道（IMPORTANCE_LOW，无声）：前台服务必须在通知栏常驻，
 *   随连接状态更新内容（已连接/重连中…），不可滑走（setOngoing）。
 * - [CHANNEL_AGENT_TASKS] Agent 主动任务消息（IMPORTANCE_HIGH，默认声音 + 振动）：只由
 *   notifications_v1 实时帧触发，历史补拉绝不走这里。点按经 [ACTION_OPEN_NOTIFICATION]
 *   深链回 [MainActivity]，每条通知的 data URI 唯一，PendingIntent 互不覆盖。
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
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENT_TASKS,
                "Agent 任务消息",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Agent 主动推送的任务完成与提醒"
                // 横幅弹出依赖 HIGH + 声音/振动：显式写明，不依赖 ROM 的渠道缺省值。
                enableVibration(true)
                vibrationPattern = TASK_VIBRATION
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /**
     * 任务渠道能否以横幅弹出：渠道被用户 / ROM 降到 HIGH 以下时只进通知栏不弹出。
     * 渠道尚未创建视为可弹（createChannels 会按 HIGH 建）。
     */
    fun taskAlertsPopUp(): Boolean =
        (nm.getNotificationChannel(CHANNEL_AGENT_TASKS)?.importance ?: NotificationManager.IMPORTANCE_HIGH) >=
            NotificationManager.IMPORTANCE_HIGH

    /**
     * 发布一条 Agent 任务系统通知（正文 BigTextStyle 可展开全文）。
     * 权限被拒 / 渠道被关时静默让位：消息仍在消息中心完整可读。
     */
    fun postTaskNotification(record: NotificationRecord) {
        val compat = NotificationManagerCompat.from(appContext)
        if (!compat.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val title = record.title.ifBlank { "Agent 任务完成" }
        val public = NotificationCompat.Builder(appContext, CHANNEL_AGENT_TASKS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText("有新的 Agent 任务消息")
            .build()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_AGENT_TASKS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(record.body)
            .setSubText(record.agentName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(record.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openNotificationPendingIntent(record))
            .build()
        try {
            compat.notify(taskTag(record), ID_AGENT_TASK, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "task notification denied: ${e.message}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "task notification failed: ${e.message}", e)
        }
    }

    /** 通知点按：显式 MainActivity + 唯一 data URI（requestCode 固定，不靠 extras 区分）。 */
    private fun openNotificationPendingIntent(record: NotificationRecord): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            0,
            openNotificationIntent(appContext, record),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 构建常驻通知（不发布）：前台服务 startForeground 需要通知对象本身。
     * 点按打开应用（无会话深链）。
     */
    fun persistent(text: String): Notification =
        Notification.Builder(appContext, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${appContext.getString(R.string.app_name)} 后台连接")
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

        /** Agent 任务消息渠道（HIGH，默认声音 + 振动）。 */
        const val CHANNEL_AGENT_TASKS = "agent_tasks_v1"

        private val TASK_VIBRATION = longArrayOf(0, 220, 120, 220)

        /** 任务通知 id；每条靠 tag `task:<host_id>:<id>` 区分，不与常驻 id=1 冲突。 */
        const val ID_AGENT_TASK = 2

        const val ACTION_OPEN_NOTIFICATION = "dev.agentmirror.app.action.OPEN_NOTIFICATION"
        const val EXTRA_HOST_ID = "dev.agentmirror.app.extra.NOTIFICATION_HOST_ID"
        const val EXTRA_NOTIFICATION_ID = "dev.agentmirror.app.extra.NOTIFICATION_ID"
        const val EXTRA_SESSION_REF = "session_ref"

        fun taskTag(record: NotificationRecord): String = "task:${record.hostId}:${record.id}"

        fun openNotificationIntent(context: Context, record: NotificationRecord): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_NOTIFICATION
                data = Uri.parse("corral://notification/${Uri.encode(record.hostId)}/${Uri.encode(record.id)}")
                putExtra(EXTRA_HOST_ID, record.hostId)
                putExtra(EXTRA_NOTIFICATION_ID, record.id)
                record.sessionRef?.let { putExtra(EXTRA_SESSION_REF, it) }
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        private const val TAG = "NotificationHelper"
    }
}
