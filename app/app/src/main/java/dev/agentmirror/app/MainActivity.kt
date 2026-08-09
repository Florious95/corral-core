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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.agentmirror.app.pairing.SharedPreferencesPairingConfigStore
import dev.agentmirror.app.service.NotificationHelper

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
 */
class MainActivity : ComponentActivity() {

    /** 导航壳状态（D-3 修复核心）：activeSession/showPairing 提升到 Activity，重建保态。 */
    internal lateinit var navState: MainNavState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navState = MainNavState(initialShowPairing = SharedPreferencesPairingConfigStore(this).load() == null)
        navState.restoreFrom(savedInstanceState) // D-3：旋转/进程回收重建后恢复导航态
        handleDeepLink(intent) // D-2：冷启动直达（ACTION_OPEN_SESSION+EXTRA_SESSION_REF）
        setContent {
            AgentMirrorApp(navState = navState)
        }
    }

    /** D-2：singleTop 在屏时通知点按经此投递（不重建 Activity）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** D-3：Activity 重建（旋转/进程回收）前保存导航态。 */
    override fun onSaveInstanceState(outState: Bundle) {
        navState.writeTo(outState)
        super.onSaveInstanceState(outState)
    }

    /** 消费通知深链：匹配 action 且带 ref 才导航；缺 ref 忽略（halt 纪律：缺字段不猜）。 */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != NotificationHelper.ACTION_OPEN_SESSION) return
        val ref = intent.getStringExtra(NotificationHelper.EXTRA_SESSION_REF) ?: return
        // 通知 PendingIntent 只带 ref 不带 name：展示名以 ref 兜底（会话名由列表/通知标题给，
        // 深链直达时列表尚未加载，ref 是唯一可寻址键）。
        navState.activeSession = ref to ref
    }
}
