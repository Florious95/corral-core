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

package dev.agentmirror.app.notify

import android.content.Intent
import dev.agentmirror.app.MainNavState
import dev.agentmirror.app.service.NotificationHelper

/**
 * 系统通知点按携带的待路由目标。只是索引：真正的会话 ref 以本地可信记录为准
 * （MainActivity 是 exported，外部 Intent 的 extras 不可信）。
 */
data class NotificationTarget(val hostId: String, val id: String, val sessionRef: String?) {
    val key: NotificationKey get() = NotificationKey(hostId, id)

    companion object {
        /** 非本应用通知深链（action 不符 / 缺 host 或 id）返回 null。 */
        fun fromIntent(intent: Intent?): NotificationTarget? {
            if (intent?.action != NotificationHelper.ACTION_OPEN_NOTIFICATION) return null
            val hostId = intent.getStringExtra(NotificationHelper.EXTRA_HOST_ID)?.takeIf { it.isNotBlank() } ?: return null
            val id = intent.getStringExtra(NotificationHelper.EXTRA_NOTIFICATION_ID)?.takeIf { it.isNotBlank() } ?: return null
            return NotificationTarget(hostId, id, intent.getStringExtra(NotificationHelper.EXTRA_SESSION_REF))
        }
    }
}

/** 一条通知的导航结论：直达终端，或停在消息中心（无链接 / 已裁剪 / 他机 / ref 不符）。 */
sealed interface NotificationRoute {
    val key: NotificationKey?

    data class Session(
        override val key: NotificationKey,
        val ref: String,
        val name: String,
        val workspace: String?,
    ) : NotificationRoute

    data class MessageCenter(override val key: NotificationKey?) : NotificationRoute
}

/** 这条记录能否直达终端：有 ref，且来自当前（或最近一次协商的）通知来源主机。 */
fun NotificationRecord.canOpenSession(sourceHostId: String?): Boolean =
    sessionRef != null && (sourceHostId == null || sourceHostId == hostId)

/** 会话页标题：Agent 标签优先，其次消息标题。 */
fun NotificationRecord.sessionDisplayName(): String = agentName?.takeIf { it.isNotBlank() } ?: title

/**
 * 把点按目标解析成路由（契约 §8）：只信本地记录；extras 的 ref 与记录不符按不可信处理。
 */
fun resolveNotificationTarget(
    target: NotificationTarget,
    items: List<NotificationItem>,
    sourceHostId: String?,
): NotificationRoute {
    val record = items.firstOrNull { it.key == target.key }?.record
        ?: return NotificationRoute.MessageCenter(null)
    val ref = record.sessionRef
    if (ref == null || !record.canOpenSession(sourceHostId) ||
        (target.sessionRef != null && target.sessionRef != ref)
    ) {
        return NotificationRoute.MessageCenter(record.key)
    }
    return NotificationRoute.Session(record.key, ref, record.sessionDisplayName(), record.workspace)
}

/**
 * 消费挂起的点按目标：本地库加载完、且不在配对页时才解析；命中的记录标已读后导航。
 *
 * @return true = 已处理并清空 [MainNavState.pendingNotification]
 */
fun MainNavState.consumePendingNotification(repository: NotificationRepository): Boolean {
    val target = pendingNotification ?: return false
    if (!repository.loaded.value || showPairing) return false
    pendingNotification = null
    val route = resolveNotificationTarget(target, repository.items.value, repository.sourceHostId.value)
    route.key?.let(repository::markRead)
    applyNotificationRoute(route)
    return true
}
