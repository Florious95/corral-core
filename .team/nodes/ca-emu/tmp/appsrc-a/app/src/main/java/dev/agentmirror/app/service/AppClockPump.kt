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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * 时钟泵单一事实源 + 在屏兜底接管（fix-app-runtime-sa）。
 *
 * 泵拍什么：共享连接的重连调度（[dev.agentmirror.app.conn.ConnectionManager.pump]）与输入
 * 超时裁决（[dev.agentmirror.app.conn.ConnectionManager.resolveExpiredInputs]）。
 * feat-fg-service-wiring 把泵从在屏组合挪进前台服务（[MirrorForegroundService]，2s 一拍）后，
 * 服务被杀时即使 App 在前台也没有泵（功能回退，踩 004 自检标准——删掉前台服务这一层产品
 * 功能应仍完整，只是后台期间体验降级；现状是**前台也降级**）。本对象恢复在屏兜底：服务泵
 * 不可用（[ServiceWire.servicePumpActive] 复位——服务 onStartCommand 置位 / onDestroy 复位）
 * 时由 [OnScreenFallbackPump] 接管，服务恢复即让出（不双泵：双泵会让 UI 抖动并白烧 CPU，
 * 撞静默经济红线）。
 *
 * @contract
 * @pre none
 * @post [fallbackPumpOnce] 在服务泵不可用时推进共享连接；服务泵在跑时零工作（让出）
 * @err none（manager 未创建时零工作）
 * @inv 任意时刻至多一个泵在拍共享连接：服务泵（[MirrorForegroundService.pumpOnce]）或
 *      兜底泵（[fallbackPumpOnce]）之一；[serviceActive] 是归属判据
 */
object AppClockPump {
    /** 兜底泵周期：与服务泵一致（2s 一拍），接管/让出不跳拍。 */
    const val FALLBACK_INTERVAL_MS = 2_000L

    /** 服务泵是否在跑（泵归属判据：服务 onStartCommand 置位、onDestroy 复位）。 */
    fun serviceActive(): Boolean = ServiceWire.servicePumpActive

    /** 泵的实际推进点（服务泵与兜底泵共用；manager 未创建时零工作，成本恒定）。 */
    fun pumpManager(nowMs: Long) {
        val m = ServiceWire.managerOrNull() ?: return
        m.pump(nowMs)
        m.resolveExpiredInputs(nowMs)
    }

    /** 在屏兜底泵单拍：服务泵在跑则让出（不双泵），否则推进共享连接。 */
    fun fallbackPumpOnce(nowMs: Long) {
        if (serviceActive()) return
        pumpManager(nowMs)
    }
}

/**
 * 在屏兜底时钟泵（fix-app-runtime-sa）：App 前台可见（Activity RESUMED）且服务泵不可用时
 * 接管共享连接的重连调度与输入超时裁决；服务泵恢复即让出（[AppClockPump.serviceActive]）。
 *
 * 挂在 [dev.agentmirror.app.AgentMirrorApp] 根组合：任一屏（工作区/会话/设置/配对）在屏即
 * 有一个兜底泵。生命周期门控 RESUMED：后台期间不泵（004 后台体验降级可接受；不白烧 CPU，
 * 服务常驻时的泵归服务，兜底泵只在服务缺失时补位）。LaunchedEffect 首拍即执行（组合帧落定
 * 触发），测试以此确定性驱动单拍断言；[nowMs] 仅测试注入时间源，生产默认真实墙钟。
 *
 * @contract
 * @pre none
 * @post RESUMED 且服务泵不可用时周期推进共享连接；否则零工作
 * @err none
 * @inv 生命周期离开 RESUMED 即取消协程（停泵），回到 RESUMED 重新起拍；兜底泵与服务泵
 *      互斥（[AppClockPump.fallbackPumpOnce] 让出判据），不双泵
 */
@Composable
fun OnScreenFallbackPump(nowMs: () -> Long = { System.currentTimeMillis() }) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE -> false
                else -> resumed
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!resumed) return
    LaunchedEffect(Unit) {
        while (true) {
            AppClockPump.fallbackPumpOnce(nowMs())
            delay(AppClockPump.FALLBACK_INTERVAL_MS)
        }
    }
}
