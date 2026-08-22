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

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.agentmirror.app.perf.PerfTrace

/**
 * 导航壳状态（D-3 修复核心）。
 *
 * 旋转/进程回收丢导航态的根因（审计 D-3）：导航开关用 `remember` 而非可持久化状态，
 * Activity 重建即被踢回列表页。修复：把导航态从组合内 remember 提升到
 * Activity 持有的本对象，经 [writeTo]/[restoreFrom]（onSaveInstanceState 机制）随 Activity
 * 生命周期保存恢复。与 `rememberSaveable` 是同一套 Android saved-state 机制（rememberSaveable
 * 底层即委托 Activity 的 saved-state 保存），本实现显式持有实例，使 Robolectric 测试
 * （MainActivityNavTest）可直接断言导航态，无需依赖 Compose UI-test 渲染断言（fix-app-nav
 * 知识基底 §3：Robolectric sdk 兼容性先小样验证）。
 *
 * 状态用 Compose [mutableStateOf]：组合内读取即订阅、写入自动重组（与 remember 语义一致）；
 * MainActivity 直接持有时同样可读写。
 *
 * @contract
 * @pre 构造参数 initialShowPairing 为首启配对判定结果（无配对配置 → true，进配对页）
 * @post [writeTo] 落全部非空导航态（无工作区/会话时不写对应键）；[restoreFrom] 仅在
 *       savedInstanceState 含 KEY_SHOW_PAIRING 时恢复，否则整体跳过保持初值
 * @err none
 * @inv activeSession 非空时恒为 (ref, name) 二元组；writeTo/restoreFrom 往返还原同一导航态
 */
class MainNavState(initialShowPairing: Boolean) {

    /** 配对页开关：首启无配置 / 重配入口。重建后恢复（D-3）。 */
    var showPairing by mutableStateOf(initialShowPairing)

    /** 设置页开关：工作区顶栏进入；重建后仍停在设置页。三栏里与 [homePane]==Settings 同步。 */
    var showSettings by mutableStateOf(false)

    /** 三栏当前页；冷启动默认中间会话页。 */
    var homePane by mutableStateOf(ThreePane.Sessions)

    /** 当前会话（ref, name）；null = 不在会话页（工作区/配对页）。重建后恢复（D-3）。 */
    var activeSession by mutableStateOf<Pair<String, String>?>(null)

    /**
     * 用户点开会话（列表 / 收藏 / 悬浮窗切换）。旋转恢复走 [restoreFrom]，不走这里。
     *
     * @contract
     * @pre ref 非空
     * @post [activeSession] = (ref, name)；开关开时为本打开生成 open_id 并打 tap
     * @err none
     * @inv 关时最外层短路，不 beginOpen
     */
    fun openSession(ref: String, name: String) {
        if (PerfTrace.isEnabled()) {
            PerfTrace.onUserOpen(ref) // tap
        }
        activeSession = ref to name
    }

    /** 当前选中的工作区 cwd；非空 = 工作区二级会话选择页。重建后恢复（D-32）。 */
    var selectedWorkspaceCwd by mutableStateOf<String?>(null)

    /**
     * 系统返回逐级裁决（D-23/D-32）：会话 → 会话选择 → 工作区列表 → 配对根。
     *
     * @contract
     * @pre none
     * @post 有上一级时仅清当前最高优先级导航态并返回 true；配对根不改状态并返回 false
     * @err none
     * @inv 每次调用至多迁移一级，优先级恒为 activeSession → selectedWorkspaceCwd →
     *      showSettings → showPairing；低优先级导航态不抢先消费返回事件
     */
    fun onSystemBack(): Boolean {
        if (activeSession != null) {
            activeSession = null
            return true
        }
        if (selectedWorkspaceCwd != null) {
            selectedWorkspaceCwd = null
            return true
        }
        if (showSettings || homePane != ThreePane.Sessions) {
            showSettings = false
            homePane = ThreePane.Sessions
            return true
        }
        if (!showPairing) {
            showPairing = true
            return true
        }
        return false
    }

    /** 序列化到 Activity 的 outState（onSaveInstanceState 时调用）。 */
    fun writeTo(outState: Bundle) {
        outState.putBoolean(KEY_SHOW_PAIRING, showPairing)
        outState.putBoolean(KEY_SHOW_SETTINGS, showSettings)
        outState.putInt(KEY_HOME_PANE, homePane.ordinal)
        selectedWorkspaceCwd?.let { outState.putString(KEY_WORKSPACE_CWD, it) }
        activeSession?.let { (ref, name) ->
            outState.putString(KEY_SESSION_REF, ref)
            outState.putString(KEY_SESSION_NAME, name)
        }
    }

    /** 从 savedInstanceState 恢复；无对应键（首启/未保存过）则保持初值不覆盖。 */
    fun restoreFrom(savedInstanceState: Bundle?) {
        if (savedInstanceState == null || !savedInstanceState.containsKey(KEY_SHOW_PAIRING)) return
        showPairing = savedInstanceState.getBoolean(KEY_SHOW_PAIRING)
        showSettings = savedInstanceState.getBoolean(KEY_SHOW_SETTINGS)
        val paneOrd = savedInstanceState.getInt(KEY_HOME_PANE, ThreePane.Sessions.ordinal)
        homePane = ThreePane.entries.getOrElse(paneOrd) { ThreePane.Sessions }
        selectedWorkspaceCwd = savedInstanceState.getString(KEY_WORKSPACE_CWD)
        val ref = savedInstanceState.getString(KEY_SESSION_REF)
        val name = savedInstanceState.getString(KEY_SESSION_NAME)
        activeSession = if (ref != null) ref to (name ?: ref) else null
    }

    private companion object {
        const val KEY_SHOW_PAIRING = "nav_show_pairing"
        const val KEY_SHOW_SETTINGS = "nav_show_settings"
        const val KEY_HOME_PANE = "nav_home_pane"
        const val KEY_WORKSPACE_CWD = "nav_workspace_cwd"
        const val KEY_SESSION_REF = "nav_session_ref"
        const val KEY_SESSION_NAME = "nav_session_name"
    }
}
