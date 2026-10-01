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

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import dev.agentmirror.app.notify.NotificationRepositoryTest.Companion.record
import dev.agentmirror.app.service.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 系统通知：独立 HIGH 渠道、BigText 全文、tag 身份与点按深链。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSystemAlertTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val nm: NotificationManager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun taskChannel_isHighImportanceWithVibration_persistentChannelUnchanged() {
        NotificationHelper(app).createChannels()
        val task = nm.getNotificationChannel(NotificationHelper.CHANNEL_AGENT_TASKS)
        assertEquals("agent_tasks_v1", task.id)
        assertEquals("Agent 任务消息", task.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, task.importance)
        assertTrue(task.shouldVibrate())
        assertNotNull("explicit vibration pattern", task.vibrationPattern)
        assertNotNull("default notification sound", task.sound)
        assertEquals(android.media.AudioAttributes.USAGE_NOTIFICATION, task.audioAttributes.usage)
        assertTrue("heads-up capable", NotificationHelper(app).taskAlertsPopUp())
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(NotificationHelper.CHANNEL_PERSISTENT).importance)
    }

    @Test
    fun post_usesBigTextFullBodyTagIdAndDeepLink() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val helper = NotificationHelper(app).apply { createChannels() }
        val body = "第一行\n第二行\n\n  缩进保留\n" + "长".repeat(400)
        val r = record("7", body = body)
        helper.postTaskNotification(r)

        val posted = shadowOf(nm).getNotification("task:h:id-7", NotificationHelper.ID_AGENT_TASK)
        assertNotNull(posted)
        assertEquals(NotificationHelper.CHANNEL_AGENT_TASKS, posted.channelId)
        assertEquals("标题 7", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("BigTextStyle carries the full body", body, posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals(Notification.CATEGORY_MESSAGE, posted.category)
        @Suppress("DEPRECATION")
        assertEquals("pre-O heads-up priority", Notification.PRIORITY_HIGH, posted.priority)
        assertNotNull("lock screen shows a redacted public version", posted.publicVersion)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)

        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(NotificationHelper.ACTION_OPEN_NOTIFICATION, intent.action)
        assertEquals("corral://notification/h/id-7", intent.dataString)
        assertEquals(r.sessionRef, intent.getStringExtra(NotificationHelper.EXTRA_SESSION_REF))
        assertEquals(NotificationTarget("h", "id-7", r.sessionRef), NotificationTarget.fromIntent(intent))
    }

    @Test
    fun loweredTaskChannel_isReportedAsNotPoppingUp() {
        nm.createNotificationChannel(
            android.app.NotificationChannel(NotificationHelper.CHANNEL_AGENT_TASKS, "Agent 任务消息", NotificationManager.IMPORTANCE_DEFAULT),
        )
        assertFalse(NotificationHelper(app).taskAlertsPopUp())
    }

    @Test
    fun post_withoutPermission_isSkippedButMessageStaysReadable() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val helper = NotificationHelper(app).apply { createChannels() }
        helper.postTaskNotification(record("8"))
        assertNull(shadowOf(nm).getNotification("task:h:id-8", NotificationHelper.ID_AGENT_TASK))
    }
}
