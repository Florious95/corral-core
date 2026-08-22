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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-23 侧滑返回返回栈测试（022 裁定：侧滑/返回键回上一级而非退出 App）。
 *
 * 返回栈链：会话页 → 工作区列表 → 配对页；配对页为根（返回 = 退出 App）。
 * 断言面分两层：
 * - [MainNavState.onSystemBack] 纯 JVM 裁决（不依赖 Compose 渲染，同 MainNavState 设计）；
 * - Robolectric 接线验证：Activity 的 [androidx.activity.OnBackPressedDispatcher] 触发后
 *   根 [androidx.activity.compose.BackHandler] 真的把导航态降一级（当前代码无根 BackHandler
 *   ⇒ 会话页返回直接 finish 退 App，此层为红测）。
 *
 * 路由优先级（AgentMirrorApp）：会话 > 配对 > 设置 > 工作区。返回裁决与优先级同序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackGestureNavTest {

    // ---- 纯 JVM 裁决：MainNavState.onSystemBack ----

    @Test
    fun onSystemBack_session_clearsToWorkspace() {
        // 红测：当前 MainNavState 无 onSystemBack（编译红）；修复后会话页返回 → 清 activeSession 回工作区。
        val nav = MainNavState(initialShowPairing = false)
        nav.activeSession = "ref-A" to "Agent A"
        assertTrue(nav.onSystemBack())
        assertNull("会话页返回必须回到工作区", nav.activeSession)
    }

    @Test
    fun onSystemBack_session_returnsToSessionSelectionLevel() {
        // D-32 红测：会话页返回必须回到「会话选择」层级（保留所选工作区 cwd），不得跳级回工作区列表。
        // 修前 MainNavState 无 selectedWorkspaceCwd，onSystemBack 只清 activeSession → 本断言红。
        val nav = MainNavState(initialShowPairing = false)
        nav.selectedWorkspaceCwd = "/workspace/a"
        nav.activeSession = "ref-A" to "Agent A"
        assertTrue(nav.onSystemBack())
        assertNull(nav.activeSession)
        assertEquals("会话页返回必须停在会话选择页（工作区 cwd 保留）", "/workspace/a", nav.selectedWorkspaceCwd)
    }

    @Test
    fun onSystemBack_session_withoutSelection_returnsToListLevel() {
        // 深链直达（未选中任何工作区）：会话返回落到工作区一级列表。
        val nav = MainNavState(initialShowPairing = false)
        nav.activeSession = "ref-A" to "Agent A"
        assertTrue(nav.onSystemBack())
        assertNull(nav.activeSession)
        assertNull(nav.selectedWorkspaceCwd)
    }

    @Test
    fun onSystemBack_workspaceLevel2_returnsToListLevel1() {
        // D-32：工作区二级（会话选择页）返回 → 回工作区一级列表（逐级，不跳级）。
        val nav = MainNavState(initialShowPairing = false)
        nav.selectedWorkspaceCwd = "/workspace/a"
        assertTrue(nav.onSystemBack())
        assertNull("二级返回必须清空所选 cwd 回一级列表", nav.selectedWorkspaceCwd)
    }

    @Test
    fun onSystemBack_pairing_isRootNoOp() {
        // 配对页是返回栈根：onSystemBack 返回 false（不迁移导航态），放行系统默认退出。
        val nav = MainNavState(initialShowPairing = true)
        assertFalse(nav.onSystemBack())
        assertTrue("配对页仍停住（根，不迁移）", nav.showPairing)
    }

    @Test
    fun onSystemBack_settings_returnsToWorkspace() {
        val nav = MainNavState(initialShowPairing = false)
        nav.showSettings = true
        assertTrue(nav.onSystemBack())
        assertFalse("设置页返回必须回工作区", nav.showSettings)
    }

    @Test
    fun onSystemBack_workspaceList_opensPairing() {
        // 返回栈链：工作区列表 → 配对页（工作区一级返回回到上一级配对页，而非退出 App）。
        val nav = MainNavState(initialShowPairing = false)
        assertTrue(nav.onSystemBack())
        assertTrue("工作区返回必须回到配对页", nav.showPairing)
    }

    @Test
    fun onSystemBack_sessionPriority_overPairingAndSettings() {
        // 路由优先级 会话>配对>设置：会话在屏时返回只清会话（配对/设置标志不动）。
        val nav = MainNavState(initialShowPairing = true)
        nav.showSettings = true
        nav.activeSession = "ref-A" to "Agent A"
        assertTrue(nav.onSystemBack())
        assertNull(nav.activeSession)
        assertTrue("配对标志不动（会话优先）", nav.showPairing)
        assertTrue("设置标志不动（会话优先）", nav.showSettings)
    }

    // ---- D-32 红测：所选工作区 cwd 随 Activity 生命周期持久（重建后仍在会话选择层级）----

    @Test
    fun writeToRestoreFrom_keepsWorkspaceSelection() {
        val nav = MainNavState(initialShowPairing = false)
        nav.selectedWorkspaceCwd = "/workspace/a"
        val bundle = android.os.Bundle()
        nav.writeTo(bundle)
        val restored = MainNavState(initialShowPairing = false)
        restored.restoreFrom(bundle)
        assertEquals("/workspace/a", restored.selectedWorkspaceCwd)
    }

    // ---- Robolectric 接线：根 BackHandler 真的响应系统返回 ----

    @Test
    fun systemBack_onSession_returnsToWorkspace() {
        // 红测：当前无根 BackHandler，onBackPressedDispatcher 无回调 → 默认 finish 退 App，
        // activeSession 不清空；修复后根回调把会话降回工作区。
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.navState.showPairing = false
        activity.navState.activeSession = "ref-A" to "Agent A"
        activity.onBackPressedDispatcher.onBackPressed()
        assertNull("会话页系统返回必须回工作区", activity.navState.activeSession)
    }

    @Test
    fun systemBack_onSession_keepsWorkspaceSelection() {
        // D-32 红测（接线层）：系统返回从会话页退回会话选择页——所选工作区 cwd 必须保留
        // （修前 onSystemBack 只清 activeSession 且无 cwd 概念 → 直接回工作区一级列表，跳级）。
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.navState.showPairing = false
        activity.navState.selectedWorkspaceCwd = "/workspace/a"
        activity.navState.activeSession = "ref-A" to "Agent A"
        activity.onBackPressedDispatcher.onBackPressed()
        assertNull(activity.navState.activeSession)
        assertEquals("系统返回必须停在会话选择页", "/workspace/a", activity.navState.selectedWorkspaceCwd)
    }

    @Test
    fun systemBack_onWorkspaceLevel2_returnsToLevel1() {
        // D-32（接线层）：会话选择页系统返回 → 工作区一级列表（cwd 清空，逐级回退）。
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.navState.showPairing = false
        activity.navState.selectedWorkspaceCwd = "/workspace/a"
        activity.onBackPressedDispatcher.onBackPressed()
        assertNull("会话选择页返回必须回工作区一级", activity.navState.selectedWorkspaceCwd)
    }

    @Test
    fun systemBack_onWorkspaceList_opensPairing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.navState.showPairing = false
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("工作区列表系统返回必须回配对页", activity.navState.showPairing)
    }

    @Test
    fun systemBack_onPairing_keepsRootState() {
        // 配对页为根：返回不迁移导航态（退出交系统默认处理）。
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertTrue("首启无配置应在配对页", activity.navState.showPairing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("配对页是根，返回不迁移", activity.navState.showPairing)
    }
}
