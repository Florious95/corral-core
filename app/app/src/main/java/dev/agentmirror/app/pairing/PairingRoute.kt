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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.service.ServiceWire

/**
 * 配对页路由挂载（MainActivity/AgentMirrorApp 接线处唯一入口；仅路由挂载，不含接线层）。
 *
 * - 生产 [PairingViewModel]：真实配置存储（SharedPreferences）+ 真实传输工厂
 *   （[ServiceWire.transportFactory]，默认 OkHttp，leader 裁定 A）建独立试配对连接；
 * - 配对成功：①注入 [ServiceWire.setConfig]（常驻连接配置，session/workspace 复用）、
 *   ②注入 [ServiceWire.uploadBaseUrl]（协议 §8 上传基地址，清偿 session-ui 欠账②）、
 *   ③通知上层切工作区。落库已由 VM 完成，这里只接线。
 * - [onSkip]：首启可跳过（进工作区空态），从设置/重配入口可随时重进（见 AgentMirrorApp）。
 */
@Composable
fun PairingRoute(
    configStore: PairingConfigStore,
    onPaired: () -> Unit,
    onSkip: () -> Unit,
) {
    val viewModel = remember { createPairingViewModel(configStore) }
    PairingScreen(
        viewModel = viewModel,
        onPaired = { cfg ->
            // 配对成功注入（幂等由 PairingScreen 的成功态单次触发保证）：
            // ①常驻连接配置；②上传基地址（ws→http，协议 §8）；③启动常驻连接；
            //   用户配对完直落工作区，列表必须即刻有数据——不等 fg-service onCreate
            //   （fg-service KDoc 允许 UI 先启连，与 SessionRoute 兜底同构；服务自身按既有策略运转）；
            // ④切工作区。
            ServiceWire.setConfig(ConnectionConfig(cfg.url, cfg.token))
            ServiceWire.uploadBaseUrl = deriveUploadBase(cfg.url)
            // 幂等 start：ConnectionManager 非 STOPPED 时不重复；前台服务已由服务启动则此调用无副作用。
            runCatching { ServiceWire.manager(NoopConnListener).start() }
                .onFailure {
                    // 启动失败不阻塞配对成功：工作区/会话页的 SessionRoute 会再尝试（不静默）。
                    android.util.Log.w("PairingRoute", "start persistent connection: ${it.message}")
                }
            onPaired()
        },
        onSkip = onSkip,
    )
}

/** 生产构造 [PairingViewModel]：真实存储 + 真实传输工厂（独立试配对连接，不碰常驻 manager）。 */
private fun createPairingViewModel(configStore: PairingConfigStore): PairingViewModel {
    return PairingViewModel(
        configStore = configStore,
        connectionFactory = { cfg ->
            // 试配对用独立 ConnectionManager：工厂用 ServiceWire.transportFactory（默认 OkHttp）。
            // 配对成功后才由 ServiceWire.setConfig 落常驻连接配置（见 PairingRoute）。
            ConnectionManager(config = cfg, transportFactory = ServiceWire.transportFactory)
        },
    )
}
