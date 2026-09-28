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

package dev.agentmirror.app.tsnet

/**
 * authkey 结构校验与归一化。
 *
 * 契约：只做结构校验——trim 后非空、纯可见 ASCII（0x21..0x7e）。
 * 不校验厂商前缀：tailscale 官方 `tskey-*` 与 headscale 纯 hex 都必须放行
 * （服务端 tsnetd 对接哪家控制面是部署侧自由，App 不越权预判）。
 * 语义有效性（key 过期/吊销）由控制面握手裁定，失败会以 Error 状态浮出。
 */
object TsnetAuthKeys {
    /** 返回归一化（trim）后的 key；结构非法返回 null。 */
    fun normalizeOrNull(raw: String): String? {
        val key = raw.trim()
        if (key.isEmpty()) return null
        if (key.any { it.code < 0x21 || it.code > 0x7e }) return null
        return key
    }
}
