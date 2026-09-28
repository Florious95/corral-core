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

import android.content.Context
import android.os.Build
import dev.agentmirror.app.tsnet.TsnetWire
import java.io.File

/**
 * tsnet 运行环境注入（feat-ts-wire）：Context → 纯字符串环境，喂给
 * [TsnetWire.environment]（tsnet 包保持 JVM 可测，不碰 Context）。
 *
 * 调用点：[NetworkConnectivityWatcher.register]（MainActivity.onCreate 网络接线段，
 * 早于冷启动 startPersistentConnection 的首次拨号）与 PairingRoute（配对页兜底）。
 * 幂等：已注入不重算。
 */
object TsnetBootstrap {
    fun install(context: Context) {
        if (TsnetWire.environment != null) return
        TsnetWire.environment = TsnetWire.Environment(
            // 状态根放 filesDir/tsnet；节点状态由 TsnetWire 按 key 指纹隔离，卸载即清。
            stateDir = File(context.applicationContext.filesDir, "tsnet").absolutePath,
            // 节点名 = 设备型号归一化（tailnet 管理台可辨认）。
            hostname = TsnetWire.sanitizeHostname(Build.MODEL ?: "device"),
        )
    }
}
