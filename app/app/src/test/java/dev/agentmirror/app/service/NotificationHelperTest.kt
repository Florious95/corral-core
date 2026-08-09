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
import android.app.NotificationManager
import android.content.Context
import dev.agentmirror.app.MainActivity
import dev.agentmirror.app.conn.AgentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NotificationHelper 接缝零测（test-app-android-seams 交付物之一）。
 *
 * 覆盖知识基底 §0 第一类：渠道创建、通知构建（含深链 PendingIntent 形状——fix-app-nav
 * 之后有消费方了）、通知权限缺失时降级不崩。Robolectric 基建复用 fix-app-nav 模板
 * （@Config sdk=[34]，起 JVM 模拟，不打真网）。
 *
 * 深链形状断言经 ShadowPendingIntent.savedIntent：只验 PendingIntent 携带的 intent
 * 载荷（ACTION_OPEN_SESSION + EXTRA_SESSION_REF），不验证消费方路由（MainActivityNavTest 已覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationHelperTest {

    private lateinit var app: Context
    private lateinit var helper: NotificationHelper
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        helper = NotificationHelper(app)
        nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // ---- 渠道创建 ----

    @Test
    fun createChannels_createsBothChannelsWithImportance() {
        // 幂等创建两条渠道：常驻 LOW / 状态 HIGH（minSdk 26 直接可用，无需 Builder 分支）。
        helper.createChannels()

        val persistent = nm.getNotificationChannel(NotificationHelper.CHANNEL_PERSISTENT)
        assertNotNull(persistent)
        assertEquals(NotificationManager.IMPORTANCE_LOW, persistent!!.importance)

        val state = nm.getNotificationChannel(NotificationHelper.CHANNEL_STATE)
        assertNotNull(state)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, state!!.importance)
    }

    @Test
    fun createChannels_isIdempotent() {
        // 重复创建不抛、不覆盖渠道语义（渠道已存在时 create 是 no-op）。
        helper.createChannels()
        helper.createChannels()
        val persistent = nm.getNotificationChannel(NotificationHelper.CHANNEL_PERSISTENT)
        assertEquals(NotificationManager.IMPORTANCE_LOW, persistent!!.importance)
    }

    // ---- 常驻通知形状 ----

    @Test
    fun persistent_notificationShape() {
        // 常驻：标题固定、文本透传、ongoing、CATEGORY_SERVICE、落 CHANNEL_PERSISTENT。
        val n = helper.persistent("连接中…")

        assertEquals("Agent Mirror 后台连接", shadowOf(n).contentTitle)
        assertEquals("连接中…", shadowOf(n).contentText)
        assertTrue(shadowOf(n).isOngoing)
        assertEquals(Notification.CATEGORY_SERVICE, n.category)
        // channelId 是 API 26+ 的公开方法；ShadowNotification 无 channelId 属性（编译期确认）。
        assertEquals(NotificationHelper.CHANNEL_PERSISTENT, n.getChannelId())
    }

    @Test
    fun persistent_contentIntent_targetsMainActivity() {
        // 常驻通知点按打开应用根 Activity（无会话深链），FLAG 保 SINGLE_TOP+CLEAR_TOP。
        val n = helper.persistent("已连接")
        val saved = shadowOf(n.contentIntent).savedIntent

        assertNotNull(saved)
        assertEquals(MainActivity::class.java.name, saved.component?.className)
        assertEquals(0, saved.action?.length ?: 0) // 无深链 action
        val flags = saved.flags
        assertTrue(flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun notifyPersistent_publishesUnderFixedId() {
        // 同 id 覆盖发布（ID_PERSISTENT 固定）；ShadowNotificationManager 可读到已发布通知。
        helper.createChannels()
        helper.notifyPersistent("连接中…")
        val n = shadowOf(nm).getNotification(NotificationHelper.ID_PERSISTENT)
        assertNotNull(n)
        assertEquals("连接中…", shadowOf(n).contentText)
    }

    // ---- 状态通知：文案逐态 + 深链形状 ----

    @Test
    fun notifyState_blocked_textRequiresInput() {
        helper.createChannels()
        helper.notifyState("ref-A", "Agent A", AgentState.BLOCKED)
        val n = shadowOf(nm).getNotification(NotificationHelper.stateNotificationId("ref-A"))
        assertNotNull(n)
        assertEquals("Agent A", shadowOf(n).contentTitle)
        assertEquals("需要你输入", shadowOf(n).contentText)
    }

    @Test
    fun notifyState_done_textCompleted() {
        helper.createChannels()
        helper.notifyState("ref-A", "Agent A", AgentState.DONE)
        val n = shadowOf(nm).getNotification(NotificationHelper.stateNotificationId("ref-A"))
        assertEquals("任务已完成", shadowOf(n).contentText)
    }

    @Test
    fun notifyState_otherStates_prefixedWireText() {
        // blocked/done 之外的状态（working/idle/unknown）落到"状态：{wire}"——永不带 ref/token。
        helper.createChannels()
        helper.notifyState("ref-A", "Agent A", AgentState.WORKING)
        val n = shadowOf(nm).getNotification(NotificationHelper.stateNotificationId("ref-A"))
        assertEquals("状态：working", shadowOf(n).contentText)
    }

    @Test
    fun notifyState_deepLinkPendingIntent_shape() {
        // 深链形状（D-2 消费方已接线）：ACTION_OPEN_SESSION + EXTRA_SESSION_REF 必带。
        helper.createChannels()
        helper.notifyState("ref-xyz", "Agent X", AgentState.BLOCKED)
        val n = shadowOf(nm).getNotification(NotificationHelper.stateNotificationId("ref-xyz"))
        val saved = shadowOf(n.contentIntent).savedIntent

        assertNotNull(saved)
        assertEquals(MainActivity::class.java.name, saved.component?.className)
        assertEquals(NotificationHelper.ACTION_OPEN_SESSION, saved.action)
        assertEquals("ref-xyz", saved.getStringExtra(NotificationHelper.EXTRA_SESSION_REF))
    }

    // ---- 通知 id：同 ref 稳定、跨 ref 近唯一 ----

    @Test
    fun stateNotificationId_stablePerRef() {
        // 同 ref → 同一稳定 id（blocked→done 只刷新不换 id）；且在 ID_STATE_BASE 之上。
        assertEquals(
            NotificationHelper.stateNotificationId("ref-A"),
            NotificationHelper.stateNotificationId("ref-A"),
        )
        assertTrue(NotificationHelper.stateNotificationId("ref-A") >= NotificationHelper.ID_STATE_BASE)
    }

    @Test
    fun stateNotificationId_distinctAcrossRefs() {
        // 不同 ref → 近唯一（hash 模 1e8 偏移；ref-A/ref-B 实测不等）。
        assertNotEquals(
            NotificationHelper.stateNotificationId("ref-A"),
            NotificationHelper.stateNotificationId("ref-B"),
        )
    }

    // ---- 权限缺失降级：不崩（静默失效猎杀，失败落日志可判定） ----

    @Test
    fun notifyState_permissionMissing_doesNotCrash() {
        // Android 13+（sdk 34）通知需 POST_NOTIFICATIONS；权限缺失时 notify 路径被 try-catch
        // 兜住（Log.w 可判定），绝不把崩溃抛给调用方（fg-service 常驻线）。
        // 注：denyPermissions 挂在 ShadowApplication（Application 重载）——app:Context 的重载
        // 只到 ShadowContextWrapper，无此方法；故用 RuntimeEnvironment.getApplication()。
        shadowOf(RuntimeEnvironment.getApplication())
            .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        helper.createChannels()
        // 无权限下调用不得抛异常（降级 = 静默失败可见化，不崩）。
        helper.notifyState("ref-A", "Agent A", AgentState.BLOCKED)
        helper.notifyPersistent("连接中…")
        // 到这里即说明降级路径未崩（权限缺失的失败已落日志，调用方无感知）。
    }

    // ---- 取消 ----

    @Test
    fun cancelState_removesNotification() {
        helper.createChannels()
        helper.notifyState("ref-A", "Agent A", AgentState.BLOCKED)
        assertNotNull(shadowOf(nm).getNotification(NotificationHelper.stateNotificationId("ref-A")))

        helper.cancelState("ref-A")
        // 同 ref 再查：通知已被移除（ShadowNotificationManager 返回 null）。
        assertTrue(shadowOf(nm).getNotification(NotificationHelper.stateNotificationId("ref-A")) == null)
    }

    @Test
    fun cancelState_unpublished_isNoOp() {
        // 取消一个从未发布的状态通知：静默 no-op，不崩。
        helper.cancelState("never-notified-ref")
    }
}
