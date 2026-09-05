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

package dev.agentmirror.app.pairing

import android.content.Context
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.service.MirrorForegroundService
import dev.agentmirror.app.service.ServiceWire
import dev.agentmirror.app.tsnet.TsnetDial
import dev.agentmirror.app.tsnet.TsnetState
import dev.agentmirror.app.tsnet.TsnetWire
import java.net.URI

/** 串行化启动意图；代次让迟到的 tsnet 终态不能恢复已被重配取代的旧地址。 */
private val persistentConnectionStartLock = Any()
private var persistentConnectionStartGeneration = 0L

/**
 * 启动常驻连接（配对成功与冷启动重连共用的唯一启动入口，fix-cold-start-reconnect P0）。
 *
 * 完整序列与 PairingRoute.onPaired 原实现**同构**（勿只抄 start()，三件套一个不能少）：
 * ①注入常驻连接配置 [ServiceWire.setConfig]（session/workspace 复用）；
 * ②注入上传基地址 [ServiceWire.uploadBaseUrl]（ws→http，协议 §8）；
 * ③启动持久连接 [ServiceWire.manager(...).start()]；
 * ④启动前台服务 [MirrorForegroundService.start]（004/011 前台服务路线：通知栏常驻 +
 *    连接与时钟泵由服务承接，fg-service 接线；幂等，服务已运行只再投 onStartCommand）。
 *
 * [context] 是前台服务启动所需（startForegroundService）；冷启动路径传 [MainActivity]，
 * 配对成功路径传配对页 LocalContext。纯 JVM 测试直接调时传 null——服务启动被跳过但连接
 * 已装配，产品功能仍完整（前台服务是体验增强，非正确性依赖）。
 *
 * 幂等由既有守卫**双层**保障（D10 多订阅替换语义的坑，防双连接）：
 * - [ServiceWire.manager]：`manager != null` 即复用既有单例（synchronized 内二次检查），
 *   不会重建连接管理器——冷启动与配对成功先后触发只持有一条连接；
 * - [ConnectionManager.start]：非 STOPPED 状态直接返回，不二次拨号。
 *
 * 启动失败不阻塞调用方流程：连接未就绪时前台服务/会话页的 [ServiceWire.manager] 兜底会
 * 再尝试（不静默），此处只落日志可判定。
 *
 * @contract
 * @pre none（配置即函数入参；context 可为 null，仅前台服务启动需要）
 * @post 已注入 [ServiceWire.setConfig] + [ServiceWire.uploadBaseUrl]，触发常驻连接启动，
 *       并在 context 非空时启动前台服务（幂等）
 * @err 常驻连接装配/启动失败仅落日志（[ServiceWire.manager]/[ConnectionManager.start] 调用已 runCatching），不抛给调用方；tsnet 起网失败由状态机显式承载
 * @inv 幂等：既有 [ServiceWire.manager] 单例复用、[ConnectionManager.start] 非 STOPPED 直接返回，不产生双连接；host 协调器存在时 LAN 立即、TS 由代次择路；无协调器的 tailnet URL 在节点未 Up 时首拨延后到 [TsnetWire.whenSettled]
 */
fun startPersistentConnection(config: PairingConfig, context: Context? = null) {
    synchronized(persistentConnectionStartLock) {
        val generation = ++persistentConnectionStartGeneration
        // 凭据脱敏前置（registerSecret 坑一：注册前窗口）：配对配置带进装配入口的那一刻就
        // 注册 token 与 authkey——确保后续任何装配/拨号路径的 record 都已被脱敏兜住。
        DiagLog.registerSecret(config.token)
        config.tsAuthKey.takeIf { it.isNotEmpty() }?.let(DiagLog::registerSecret)
        // R2: changing only the TS key while the identity/READY socket is alive is a staged
        // update. It must not stop the existing node or socket; the next generation consumes it.
        val old = ServiceWire.currentConfig()
        val sameIdentity = old?.token == config.token && old?.hostId == config.hostId
        val keyChanged = old?.tsAuthKey != config.tsAuthKey
        if (sameIdentity && keyChanged && old != null) {
            TsnetWire.stagePendingKey(config.tsAuthKey)
            startPersistentConnectionNow(config, context)
            return
        }
        if (config.tsAuthKey.isNotBlank()) {
            TsnetWire.ensureStarted(config.tsAuthKey)
            val usesCoordinator = HostRouter.isValidHostId(config.hostId) ||
                !config.legacyBootstrapUrl.isNullOrBlank()
            val host = runCatching { URI(config.url).host }.getOrNull()
            // hostId 记录由 HostDialCoordinator 择路，不在此全局等待。
            // 无协调器的 tailnet URL 若在 Starting 先直拨，失败后可能卡在 RECONNECTING。
            if (!usesCoordinator &&
                TsnetDial.isTailnetHost(host) &&
                TsnetWire.state !is TsnetState.Up
            ) {
                TsnetWire.whenSettled {
                    synchronized(persistentConnectionStartLock) {
                        if (generation == persistentConnectionStartGeneration) {
                            startPersistentConnectionNow(config, context)
                        }
                    }
                }
                return
            }
        }
        startPersistentConnectionNow(config, context)
    }
}

/**
 * 已满足 tailnet 起网前置条件后的装配：配置注入 + 连接启动 + 前台服务启动。
 *
 * 前台服务经 [MirrorForegroundService.start] 启动（startForegroundService，需要 [Context]）；
 * context 为 null（纯 JVM 测试）时跳过服务启动，连接已装配——产品功能仍完整。
 */
private fun startPersistentConnectionNow(config: PairingConfig, context: Context?) {
    val connectionConfig = ConnectionConfig(
        url = config.url,
        token = config.token,
        hostId = config.hostId,
        port = config.port,
        tsNodeId = config.tsNodeId,
        name = config.name,
        tsAuthKey = config.tsAuthKey,
        legacyBootstrapUrl = config.legacyBootstrapUrl,
        lastTsUrl = config.lastTsUrl,
        lastLanUrl = config.lastLanUrl,
        scanHints = config.scanHints,
    )
    ServiceWire.setConfig(connectionConfig)
    ServiceWire.uploadBaseUrl = config.url.takeIf { it.isNotBlank() }?.let(::deriveUploadBase)
    runCatching { ServiceWire.manager(NoopConnListener).start() }
        .onFailure {
            android.util.Log.w("PersistentConnection", "start persistent connection: ${it.message}")
        }
    // 004/011 前台服务路线：通知栏常驻 + 连接与时钟泵由服务承接（幂等）。
    context?.let(MirrorForegroundService::start)
}

/** [ServiceWire.manager] 需要的空壳监听：事件统一经 [ServiceWire.uiConnector] 扇出到当前 UI VM。 */
private object NoopConnListener : ConnectionManager.Listener {
    override fun onStateChanged(state: ConnectionState) = Unit
    override fun onFrame(frame: FramePayload) = Unit
    override fun onBinary(frame: BinaryFrame) = Unit
    override fun onLocalDecodeError(code: FrameError, message: String) = Unit
    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
    override fun onReconnect(attempt: Int, delayMs: Long) = Unit
}
