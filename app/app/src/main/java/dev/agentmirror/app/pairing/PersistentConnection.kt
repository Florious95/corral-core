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

import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
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
 * ③启动持久连接 [ServiceWire.manager(...).start()]。
 *
 * 幂等由既有守卫**双层**保障（D10 多订阅替换语义的坑，防双连接）：
 * - [ServiceWire.manager]：`manager != null` 即复用既有单例（synchronized 内二次检查），
 *   不会重建连接管理器——冷启动与配对成功先后触发只持有一条连接；
 * - [ConnectionManager.start]：非 STOPPED 状态直接返回，不二次拨号。
 *
 * 启动失败不阻塞调用方流程：连接未就绪时工作区/会话页的 [ServiceWire.manager] 兜底会再
 * 尝试（不静默），此处只落日志可判定。
 */
fun startPersistentConnection(config: PairingConfig) {
    synchronized(persistentConnectionStartLock) {
        val generation = ++persistentConnectionStartGeneration
        // feat-ts-wire：配置携带 authkey（配对时扫码/手填带入并持久化）→ 先确保 tsnet
        // 节点起网（幂等）。tailnet 地址的拨号依赖节点 Up 后的 SOCKS 通道，因此首拨等节点
        // 明确 Up/Error；LAN 地址仍立即直连（transport 工厂拨号时刻现查状态）。key 值不落日志。
        if (config.tsAuthKey.isNotBlank()) {
            TsnetWire.ensureStarted(config.tsAuthKey)
            val host = runCatching { URI(config.url).host }.getOrNull()
            if (TsnetDial.isTailnetHost(host) && TsnetWire.state !is TsnetState.Up) {
                // 冷启动没有配对页时钟泵；tailnet 目标若在 Starting 阶段先直拨，失败后可能
                // 永久停在 RECONNECTING。等节点明确 Up/Error 再首拨；LAN 地址仍立即直连。
                TsnetWire.whenSettled {
                    synchronized(persistentConnectionStartLock) {
                        if (generation == persistentConnectionStartGeneration) {
                            startPersistentConnectionNow(config)
                        }
                    }
                }
                return
            }
        }
        startPersistentConnectionNow(config)
    }
}

/** 已满足 tailnet 起网前置条件后的原有三件套；提取只为一次性状态回调复用。 */
private fun startPersistentConnectionNow(config: PairingConfig) {
    ServiceWire.setConfig(ConnectionConfig(config.url, config.token))
    ServiceWire.uploadBaseUrl = deriveUploadBase(config.url)
    runCatching { ServiceWire.manager(NoopConnListener).start() }
        .onFailure {
            android.util.Log.w("PersistentConnection", "start persistent connection: ${it.message}")
        }
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
