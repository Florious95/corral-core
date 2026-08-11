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

package dev.agentmirror.app.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.OnScreenFallbackPump
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.tsnet.ConnectionPath

/**
 * 会话页路由挂载（[AgentMirrorApp] 的 Session 分支唯一入口；本组合同时承担接线层：
 * 把当前会话 VM 挂到 [ServiceWire.uiConnector] 扇出，并安全创建共享连接）。
 *
 * 共享 [ConnectionManager] 单例的创建方是 [ServiceWire.manager]（首次调用时构造），
 * 生产路径经 [startPersistentConnection]（配对成功/冷启动）或本文件的
 * [createSessionViewModel] 触发；UI 侧经 [ServiceWire.uiConnector] 扇出订阅同一连接
 * （见 ServiceWire KDoc）。本组合：
 * - 安全获取共享 manager：连接配置未注入（配对层未落地）时明确返回等待态，不崩溃不白屏；
 * - 把会话 VM 挂到 [ServiceWire.uiConnector]（当前屏持有，退出即复位）；
 * - [SessionViewModel] 构造时已 subscribe（conn 层记簿，重连自动重放，004 无状态）。
 *
 * 连接与时钟泵归属（feat-fg-service-wiring + fix-app-runtime-sa）：连接由前台服务承接
 * （[MirrorForegroundService] 持有 [ServiceWire.manager] 单例并驱动时钟泵），在屏组合
 * **不再各自持有**——会话页不再有 LaunchedEffect 时钟泵，[SessionViewModel.onTick] 的
 * 宿主节奏改由服务泵单拍驱动（服务 [MirrorForegroundService.pumpOnce] 调
 * manager.pump/resolveExpiredInputs）。服务被杀时由根组合的 [OnScreenFallbackPump] 兜底
 * 接管（服务恢复即让出，不双泵）。
 *
 * 上传地址（协议 §8 同端口 `POST /upload`）统一读 [ServiceWire.uploadBaseUrl]——由
 * [startPersistentConnection] 统一装配入口注入（fix-reconnect-stale-config 同根并案：
 * 此前硬编码传 null 绕过统一入口 → 真机实证「未配置上传地址」；统一收口后与连接配置
 * 变更同源生效）。未注入时 VM 明确报错「未配置上传地址」，不静默。
 */
@Composable
fun SessionRoute(
    ref: String,
    name: String,
    connectionPath: ConnectionPath? = null,
    onBack: () -> Unit,
) {
    val sessionContext = LocalContext.current
    var viewModel by remember(ref) { mutableStateOf<SessionViewModel?>(null) }
    if (viewModel == null) {
        viewModel = remember(ref) { createSessionViewModel(ref, sessionContext) }
    }
    val vm = viewModel

    if (vm == null) {
        // 连接未配置（配对层未落地）：明确提示，非静默白屏。
        ConnectionNotReady(onBack = onBack)
        return
    }

    // 接线：把当前会话 VM 接入 ServiceWire.uiConnector 扇出；退出即复位 + 退订镜像。
    DisposableEffect(vm) {
        ServiceWire.uiConnector = vm
        onDispose {
            if (ServiceWire.uiConnector === vm) ServiceWire.uiConnector = null
            vm.dispose()
        }
    }

    SessionScreen(
        viewModel = vm,
        name = name,
        connectionPath = connectionPath,
        onBack = onBack,
    )
}

/** 安全构造会话 VM：获取共享 manager（未配置抛错则返回 null）→ 启动连接 → 注入生产上传器。
 *  internal（fix-reconnect-stale-config 同根并案）：上传基地址统一收口锁定的测试缝。
 *  [context] 为前台服务启动所需（进入会话即确保服务在运行，幂等）；纯 JVM 测试传 null。 */
internal fun createSessionViewModel(ref: String, context: Context? = null): SessionViewModel? {
    val manager = runCatching {
        // connListener 传空壳：manager 已存在时被忽略；新建时包装监听把事件经 uiConnector 扇出，
        // 本 VM 走 uiConnector 收事件（SessionViewModel.init 不自行 setListener，见其 KDoc）。
        ServiceWire.manager(NoopUiListener)
    }.getOrNull() ?: return null
    // 启动连接（幂等）：manager 已存在（startPersistentConnection 已启动）时 start 为 no-op；
    // 冷启动配对层先于本路径经 startPersistentConnection 启动，此处兜底再 start 一次。
    manager.start()
    // feat-fg-service-wiring：进入会话即确保前台服务在运行（幂等）——连接与时钟泵由服务
    // 承接（后台期间通知栏常驻 + 重连/超时裁决不依赖在屏组合）。若冷启动已启动过服务，
    // 这里只是再投一次 onStartCommand（系统对已运行服务幂等）。Context 未注入（纯 JVM 测试）
    // 时跳过——连接已装配，产品功能仍完整（前台服务是体验增强）。
    context?.let(MirrorForegroundService::start)
    // fix-reconnect-stale-config 同根并案：上传基地址统一读 ServiceWire.uploadBaseUrl
    // （startPersistentConnection 统一装配入口注入；此前硬编码 null 绕过 → 真机实证
    // 「未配置上传地址」）。未注入（连接配置未落地）时 VM 明确报错，不静默。
    return SessionViewModel(manager, HttpUrlConnectionUploader(), ServiceWire.uploadBaseUrl, ref, INITIAL_ROWS, INITIAL_COLS)
}

/** 连接未配置的明确等待态（halt 纪律：缺字段不猜，不静默白屏）。 */
@Composable
private fun ConnectionNotReady(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "会话不可用：连接尚未配置\n请先完成配对后进入会话。",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onBack) { Text("返回") }
        }
    }
}

/** ServiceWire.manager 需要的空壳监听：事件统一经 uiConnector 扇出到当前 UI VM。 */
private object NoopUiListener : ConnectionManager.Listener {
    override fun onStateChanged(state: ConnectionState) = Unit
    override fun onFrame(frame: FramePayload) = Unit
    override fun onBinary(frame: BinaryFrame) = Unit
    override fun onLocalDecodeError(code: FrameError, message: String) = Unit
    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
    override fun onReconnect(attempt: Int, delayMs: Long) = Unit
}

private const val INITIAL_ROWS = 40
private const val INITIAL_COLS = 120
