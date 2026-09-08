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

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.agentmirror.app.diag.DiagLog
import androidx.activity.enableEdgeToEdge
import dev.agentmirror.app.pairing.SharedPreferencesPairingConfigStore
import dev.agentmirror.app.pairing.startPersistentConnection
import dev.agentmirror.app.service.NetworkConnectivityWatcher
import dev.agentmirror.app.service.NotificationHelper
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.workspace.SharedPreferencesFavoriteStore
import dev.agentmirror.app.workspace.WorkspaceViewModel

/**
 * 应用唯一 Activity 入口（Compose 单 Activity，singleTop）。
 *
 * 依需求 004「客户端无状态」，本 Activity 不持有业务状态，仅挂载 Compose 根 [AgentMirrorApp]，
 * 导航壳状态（[navState]）经 onSaveInstanceState 随生命周期保存恢复。
 *
 * 深链（D-2 修复）：通知 PendingIntent（NotificationHelper）携带 ACTION_OPEN_SESSION +
 * EXTRA_SESSION_REF，本 Activity 在 [onCreate]（冷启动直达）与 [onNewIntent]（singleTop
 * 在屏切换）两处消费，直达对应会话页。launchMode=singleTop（manifest）：通知点按在屏时走
 * onNewIntent 而非重建，不闪白、不丢当前屏。
 *
 * @consumes dev.agentmirror.app.diag
 */
class MainActivity : ComponentActivity() {

    /** 导航壳状态（D-3 修复核心）：activeSession/showPairing 提升到 Activity，重建保态。 */
    internal lateinit var navState: MainNavState

    /** 工作区 VM（fix-workspace-wiring 接线修复）：从 AgentMirrorApp 根 remember 提升到
     *  Activity，与 navState 同模式——接线层在 Compose 把 [ServiceWire.uiConnector] 挂到它
     *  上（见 AgentMirrorApp 工作区分支），Robolectric 测试可直接断言其状态。 */
    internal lateinit var workspaceViewModel: WorkspaceViewModel

    /** True only after this Activity has crossed a real visible-stop boundary. */
    private var stoppedForResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 崩溃兜底（设计答复①，leader 认可）：崩溃/ANR trace 是 cause 链最长的地方，
        // 必须经 DiagLog.recordCrash 递归脱敏后才进缓冲。链式安装：不替换既有 handler，
        // 崩溃记录完仍转交原 handler（保留系统默认崩溃行为）。回调里 recordCrash 已 try/catch
        // 包住，不会在崩溃回调里再抛（吞掉原始崩溃）。
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DiagLog.recordCrash("crash", throwable)
            prev?.uncaughtException(thread, throwable)
        }
        // 缺陷⑤观测点：前后台生命周期（回前台永远连不上的场景，需要 ON_STOP/ON_START
        // 时间线来对齐「何时切后台 → 何时回前台 → 连接在哪个点失败」）。DiagLog 用时间戳
        // 已由 record 自带，这里只记事件名；切换时刻即记录时刻。
        DiagLog.record("lifecycle", "ON_CREATE")
        enableEdgeToEdge()
        // IME 重排（ui-redesign，图31 空洞根因修复）：edge-to-edge（decorFitsSystemWindows=false）
        // 下 manifest 未声明 softInputMode 时系统默认解析为 adjustPan——键盘弹出整窗上移，
        // 再叠加 Compose imePadding 的内容补偿 = 双重位移，终端区与输入区之间出现整屏空洞。
        // 显式锁 adjustResize：API 30+ edge-to-edge 窗口不真截断，只保证不 pan 并把 IME insets
        // 交给 Compose（会话页底部集群 imePadding 单点消费），键盘弹出内容重排跟随不留洞。
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // fix-reconnect-stale-config 扩权（leader 裁定）：注册网络可达性回调（生命周期注册段）。
        // 锁屏 WiFi 休眠断连后，网络恢复必须打断退避立即重拨（否则退避爬到长间隔无人打断，
        // 无限「重连中」空转，真机实证）。进程级幂等，旋转重建不重复注册；onDestroy 注销。
        NetworkConnectivityWatcher.register(this)
        // 冷启动重连（fix-cold-start-reconnect P0）：首启判定读取配对配置，有配置即启动
        // 常驻连接——force-stop/重开后自动重连回列表，顶栏不再永远「连接中…」（004 核心承诺
        // 「被杀即无所谓，重开自动恢复」）。序列与 PairingRoute.onPaired 同构（幂等，防双连接）；
        // 无配置则落配对页（首启语义）。navState.restoreFrom 后判 showPairing：旋转/回收重建
        // 仍停在配对页（用户正重配）时不打扰后台启动旧连接。
        val storedConfig = SharedPreferencesPairingConfigStore(this).load()
        navState = MainNavState(initialShowPairing = storedConfig == null)
        navState.restoreFrom(savedInstanceState) // D-3：旋转/进程回收重建后恢复导航态
        // D-23/D-32：根系统返回统一走导航壳逐级裁决；配对页为根，放行 Activity 默认退出。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navState.onSystemBack()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
        // feat-fg-service-wiring：冷启动传 Activity 作前台服务启动所需 Context
        // （startForegroundService 在 startPersistentConnection 内完成；this 即应用上下文）。
        if (!navState.showPairing && storedConfig != null) {
            startPersistentConnection(storedConfig, this)
        }
        // 工作区 VM 与 Activity 同生命周期；列表状态由 conn 层 READY+全量 listing 恢复（004 无状态）。
        workspaceViewModel = WorkspaceViewModel(
            favoriteStore = SharedPreferencesFavoriteStore(this),
        )
        // 069：旋转重建不是「进入菜单」，不得再发 list / 重订二级。
        if (savedInstanceState != null) {
            workspaceViewModel.suppressNextEnterRefresh()
        }
        setContent {
            AgentMirrorApp(navState = navState, workspaceViewModel = workspaceViewModel)
        }
    }

    /** D-2：singleTop 在屏时通知点按经此投递（不重建 Activity）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** D-3：Activity 重建（旋转/进程回收）前保存导航态。 */
    override fun onSaveInstanceState(outState: Bundle) {
        navState.writeTo(outState)
        super.onSaveInstanceState(outState)
    }

    /** 缺陷⑤观测点：回前台（后台冻结窗口结束，连接自愈触发点——若此处 ensureStarted
     *  被幂等守卫拦下、且 SOCKS 持续失败，日志里两个信号对撞即定位根因）。 */
    override fun onStart() {
        super.onStart()
        val resumeEdge = stoppedForResume
        stoppedForResume = false
        DiagLog.record("lifecycle", "ON_START resume_edge=$resumeEdge")
        if (resumeEdge) ServiceWire.onForegroundResume()
    }

    /** 缺陷⑤观测点：切后台（系统可能冻结进程/挂起连接，回前台时状态错位的起点）。 */
    override fun onStop() {
        stoppedForResume = true
        DiagLog.record("lifecycle", "ON_STOP resume_edge_pending=true")
        super.onStop()
    }

    // fix-reconnect-stale-config 扩权（leader 裁定）：Activity 销毁时注销网络回调。
    // 生命周期对称（onCreate 注册 / onDestroy 注销），防回调泄漏与重复注册竞态。
    override fun onDestroy() {
        NetworkConnectivityWatcher.unregister(this)
        super.onDestroy()
    }
}
