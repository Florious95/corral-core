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
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.service.ServiceWire

/**
 * 配对页路由挂载（MainActivity/AgentMirrorApp 接线处唯一入口；仅路由挂载，不含接线层）。
 *
 * - 生产 [PairingViewModel]：真实配置存储（SharedPreferences）+ 真实传输工厂
 *   （[ServiceWire.transportFactory]，默认 OkHttp，leader 裁定 A）建独立试配对连接；
 * - 配对成功：经 [startPersistentConnection] 注入常驻连接（完整序列：连接配置 + 上传
 *   基地址 + 持久连接启动，与冷启动重连共用同一入口，杜绝序列漂移）+ 通知上层切工作区。
 *   落库已由 VM 完成，这里只接线。
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
            // 配对成功：注入常驻连接并启动（幂等由 startPersistentConnection 双层守卫保证，
            // 配对成功态单次触发；用户配对完直落工作区，列表必须即刻有数据——不等 fg-service
            // onCreate，fg-service KDoc 允许 UI 先启连）；随后切工作区。
            startPersistentConnection(cfg)
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
