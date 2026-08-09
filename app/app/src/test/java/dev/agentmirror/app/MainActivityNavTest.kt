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

package dev.agentmirror.app

import android.content.Context
import android.content.Intent
import dev.agentmirror.app.service.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 导航壳 Robolectric 测试（fix-app-nav 验收 `--tests "*Nav*"`，D-2+D-3 合并修复）。
 *
 * 红测先行：前三条在当前代码（MainActivity 不读 intent / 无 onNewIntent 处理 / remember
 * 丢导航态）下均为红，修复后绿。基建：Robolectric 4.16.1（maven 实测存在）；
 * [Config] sdk=[34] 避开 compileSdk 36 的 Robolectric 支持面差异（知识基底 §3 兼容性小样
 * 验证）。后续 seams 任务共用此基建。
 *
 * 断言面：直接读 MainActivity.navState（导航态提升到 Activity 的设计，见 [MainNavState]），
 * 不依赖 Compose 渲染断言——渲染面更薄、更稳。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityNavTest {

    @Before
    fun clearPairingPrefs() {
        // 清空配对配置残留：保证 navState 初值（showPairing）与真实首启一致，且不依赖
        // 前置用例写入的 SharedPreferences（Robolectric 每用例独立，防御性保留）。
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("pairing_config", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** 构造通知深链 intent（与 NotificationHelper.openSessionPendingIntent 同 action/extra）。 */
    private fun deepLinkIntent(ref: String): Intent =
        Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).apply {
            action = NotificationHelper.ACTION_OPEN_SESSION
            putExtra(NotificationHelper.EXTRA_SESSION_REF, ref)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    // ---- D-2 深链：冷启动直达 + 在屏 onNewIntent 切换 ----

    @Test
    fun deepLinkColdStart_navigatesToRef() {
        // 红测：当前 MainActivity 不读 intent，activeSession 恒 null（审计 D-2 断链）。
        val controller = Robolectric.buildActivity(MainActivity::class.java, deepLinkIntent("ref-A"))
        val activity = controller.setup().get()
        assertEquals("ref-A" to "ref-A", activity.navState.activeSession)
    }

    @Test
    fun onNewIntent_switchesToNewRef() {
        // 红测：无 onNewIntent 处理，深链不切换会话（审计 D-2：MainActivity 无 onNewIntent）。
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.navState.activeSession = "ref-A" to "Agent A" // 模拟已在会话页
        controller.newIntent(deepLinkIntent("ref-B")) // singleTop 在屏：Robolectric 经 onNewIntent 投递
        assertEquals("ref-B" to "ref-B", activity.navState.activeSession)
    }

    // ---- D-3 重建保态：旋转/进程回收重建后仍停在会话页 ----

    @Test
    fun recreation_keepsActiveSession() {
        // 红测：remember 不经 savedInstanceState 持久化，重建后 activeSession 归 null
        // （审计 D-3：旋转/回收重建即被踢回列表页）。
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().navState.activeSession = "ref-A" to "Agent A"
        controller.recreate() // 模拟旋转/进程回收：onSaveInstanceState → onCreate(saved)
        assertEquals("ref-A" to "Agent A", controller.get().navState.activeSession)
    }

    // ---- 防御：不误导航（halt 纪律：缺字段不猜）----

    @Test
    fun launcherIntent_doesNotOpenSession() {
        // 普通 launcher 启动（无深链 action）不得误入会话页。
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull(activity.navState.activeSession)
    }

    @Test
    fun deepLinkWithoutRef_isIgnored() {
        // 深链 action 但缺 ref：忽略不导航（缺字段不猜，回工作区）。
        val intent = deepLinkIntent("ref-A").apply {
            removeExtra(NotificationHelper.EXTRA_SESSION_REF)
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        assertNull(activity.navState.activeSession)
    }
}
