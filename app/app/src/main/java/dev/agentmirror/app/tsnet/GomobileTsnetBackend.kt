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
 * [TsnetBackend] 的真实现：包 libs/tsnetbind.aar（gomobile 绑定，
 * 由 tools/tsnetbind 构建）。**native 触达仅此一处**——tsnetbind 生成类的
 * static 块会 System.loadLibrary("gojni")，因此 JVM 单测绝不实例化本类
 * （测试用 fake，见 TsnetManagerTest）；本类正确性归 e2e 实机手册。
 */
class GomobileTsnetBackend : TsnetBackend {
    /** 运行中的 tsnet 节点句柄；生命周期与 start/close 对齐。 */
    private var node: tsnetbind.Node? = null

    @Synchronized
    override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
        // gomobile 层 Start 阻塞至节点可用并起好 loopback SOCKS5（Go 侧 Loopback()）。
        val n = tsnetbind.Tsnetbind.start(stateDir, hostname, authKey)
        node = n
        return TsnetProxy.parse(n.proxyAddr(), n.proxyCred())
    }

    @Synchronized
    override fun close() {
        node?.let { runCatching { it.close() } }
        node = null
    }
}
