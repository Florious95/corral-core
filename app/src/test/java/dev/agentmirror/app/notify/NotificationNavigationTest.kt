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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.agentmirror.app.MainActivity
import dev.agentmirror.app.MainNavState
import dev.agentmirror.app.notify.NotificationRepositoryTest.Companion.record
import dev.agentmirror.app.service.NotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** 通知 → 导航：本地记录裁决、返回链、保存恢复、冷 / 热启动深链消费。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationNavigationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("pairing_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        NotificationCenter.installForTest(null)
    }

    private fun items(vararg records: NotificationRecord) = records.map { NotificationItem(it, read = false) }

    // ---- 目标裁决：只信本地记录 ----

    @Test
    fun linkedRecord_opensItsSession() {
        val r = record("1")
        val route = resolveNotificationTarget(NotificationTarget("h", "id-1", r.sessionRef), items(r), "h")
        assertEquals(NotificationRoute.Session(r.key, r.sessionRef!!, "Sol", "/work/project"), route)
    }

    @Test
    fun missingRecord_fallsBackToMessageCenter() {
        val route = resolveNotificationTarget(NotificationTarget("h", "gone", "%1"), emptyList(), "h")
        assertEquals(NotificationRoute.MessageCenter(null), route)
    }

    @Test
    fun unlinkedOtherHostOrForgedRef_staysInMessageCenter() {
        val unlinked = record("1", sessionRef = null)
        assertEquals(
            NotificationRoute.MessageCenter(unlinked.key),
            resolveNotificationTarget(NotificationTarget("h", "id-1", null), items(unlinked), "h"),
        )
        val linked = record("2")
        assertEquals(
            "a different paired host must never open this ref",
            NotificationRoute.MessageCenter(linked.key),
            resolveNotificationTarget(NotificationTarget("h", "id-2", linked.sessionRef), items(linked), "other-host"),
        )
        assertEquals(
            "extras that disagree with the stored record are untrusted",
            NotificationRoute.MessageCenter(linked.key),
            resolveNotificationTarget(NotificationTarget("h", "id-2", "/tmp/evil\u001f%9"), items(linked), "h"),
        )
    }

    @Test
    fun sessionName_prefersAgentLabel() {
        assertEquals("Sol", record("1").sessionDisplayName())
        assertEquals("标题 1", record("1", agentName = null).sessionDisplayName())
    }

    // ---- 导航壳：消息中心层级与保存恢复 ----

    @Test
    fun sessionOpenedFromMessages_backReturnsToMessageCenterThenHome() {
        val nav = MainNavState(initialShowPairing = false)
        nav.openSessionFromMessages("%3", "Sol", "/work/project")
        assertEquals("%3" to "Sol", nav.activeSession)
        assertTrue(nav.showMessageCenter)
        assertEquals("/work/project", nav.sessionWorkspaceHint)

        assertTrue(nav.onSystemBack())
        assertNull(nav.activeSession)
        assertTrue("terminal back lands on the message center", nav.showMessageCenter)

        assertTrue(nav.onSystemBack())
        assertFalse(nav.showMessageCenter)
        assertFalse(nav.showPairing)
        assertEquals("message center back lands in the session's own workspace", "/work/project", nav.selectedWorkspaceCwd)

        assertTrue(nav.onSystemBack())
        assertNull(nav.selectedWorkspaceCwd)
    }

    @Test
    fun crossWorkspaceOpen_selectsTargetWorkspaceBeforeSession() {
        val nav = MainNavState(initialShowPairing = false)
        nav.selectedWorkspaceCwd = "/work/other"
        nav.openSessionFromMessages("%3", "Sol", "/work/project")
        assertEquals("/work/project", nav.selectedWorkspaceCwd)
        assertEquals("%3" to "Sol", nav.activeSession)

        // 没有工作区快照的旧记录不改动当前选择。
        nav.openSessionFromMessages("%4", "Luna", null)
        assertEquals("/work/project", nav.selectedWorkspaceCwd)
    }

    @Test
    fun normalSessionOpen_clearsNotificationWorkspaceHint() {
        val nav = MainNavState(initialShowPairing = false)
        nav.openSessionFromMessages("%3", "Sol", "/work/project")
        nav.openSession("%4", "other")
        assertNull(nav.sessionWorkspaceHint)
    }

    @Test
    fun messageCenterRoute_leavesCurrentSession() {
        val nav = MainNavState(initialShowPairing = false)
        nav.openSession("%1", "busy")
        nav.applyNotificationRoute(NotificationRoute.MessageCenter(null))
        assertNull(nav.activeSession)
        assertTrue(nav.showMessageCenter)
    }

    @Test
    fun saveRestore_keepsMessageCenterAndPendingTarget() {
        val nav = MainNavState(initialShowPairing = false)
        nav.showMessageCenter = true
        nav.pendingNotification = NotificationTarget("h", "id-1", "/tmp/t\u001f%3")
        val bundle = Bundle().also(nav::writeTo)

        val restored = MainNavState(initialShowPairing = true).apply { restoreFrom(bundle) }
        assertTrue(restored.showMessageCenter)
        assertEquals(NotificationTarget("h", "id-1", "/tmp/t\u001f%3"), restored.pendingNotification)
    }

    @Test
    fun pendingTap_waitsForPairingThenOpensTerminalAndMarksRead() {
        val repository = NotificationRepository(null, Executor { it.run() })
        repository.acceptHistory(listOf(record("1")))
        repository.beginSync("h") {}
        val nav = MainNavState(initialShowPairing = true)
        nav.pendingNotification = NotificationTarget("h", "id-1", record("1").sessionRef)

        assertFalse("re-pairing in progress is never interrupted", nav.consumePendingNotification(repository))
        assertEquals(1, repository.unreadCount.value)

        nav.showPairing = false
        assertTrue(nav.consumePendingNotification(repository))
        assertNull(nav.pendingNotification)
        assertEquals(record("1").sessionRef to "Sol", nav.activeSession)
        assertTrue(nav.showMessageCenter)
        assertEquals(0, repository.unreadCount.value)
    }

    // ---- PendingIntent 身份与 Activity 消费 ----

    @Test
    fun notificationIntent_hasUniqueDataUriAndRoundTripsTarget() {
        val a = NotificationHelper.openNotificationIntent(context, record("1"))
        val b = NotificationHelper.openNotificationIntent(context, record("2"))
        assertEquals(NotificationHelper.ACTION_OPEN_NOTIFICATION, a.action)
        assertEquals("dev.agentmirror.app.action.OPEN_NOTIFICATION", a.action)
        assertEquals("corral://notification/h/id-1", a.data.toString())
        assertNotEquals("each notification needs its own PendingIntent identity", a.data, b.data)
        assertEquals(NotificationTarget("h", "id-1", record("1").sessionRef), NotificationTarget.fromIntent(a))
        assertNull(NotificationTarget.fromIntent(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun coldStart_consumesNotificationIntentOnce() {
        installHubWith(record("1"))
        val intent = NotificationHelper.openNotificationIntent(context, record("1"))
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        // 未配对（配对页在屏）时点按目标挂起等待，不把用户从配对页拽走。
        assertEquals(NotificationTarget("h", "id-1", record("1").sessionRef), activity.navState.pendingNotification)
        assertNull(activity.navState.activeSession)

        // 旋转重建带回同一启动 Intent：已处理的点按不得再次强制导航。
        activity.navState.pendingNotification = null
        controller.recreate()
        assertNull(controller.get().navState.pendingNotification)
    }

    @Test
    fun warmStart_onNewIntentConsumesEachTap() {
        installHubWith(record("1"), record("2"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNull(activity.navState.pendingNotification)

        controller.newIntent(NotificationHelper.openNotificationIntent(context, record("2")))
        assertEquals(NotificationTarget("h", "id-2", record("2").sessionRef), activity.navState.pendingNotification)
    }

    private fun installHubWith(vararg records: NotificationRecord) {
        val repository = NotificationRepository(null, Executor { it.run() })
        repository.acceptHistory(records.toList())
        repository.beginSync("h") {}
        NotificationCenter.installForTest(NotificationHub(repository) {})
    }
}
