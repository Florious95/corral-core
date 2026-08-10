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
 * tsnet loopback SOCKS5 代理信息（节点 Up 后的唯一接线凭据）。
 *
 * tsnet 的 Loopback() 只提供 SOCKS5（上游 HTTP CONNECT 仍是 TODO，
 * 见 docs/decisions/app-tsnet.md），认证为用户名 `tsnet` + [cred] 口令。
 */
data class TsnetProxy(val host: String, val port: Int, val cred: String) {
    /** loopback 代理凭据同样是秘密，禁止 data class 默认 toString 明文展开。 */
    override fun toString(): String = "TsnetProxy(host=$host, port=$port, cred=[redacted])"

    companion object {
        /**
         * 解析 gomobile 层返回的 "host:port" 一体串（tsnet loopback 监听 127.0.0.1，
         * 无 IPv6 括号形态）。非法输入抛 [IllegalArgumentException]，由调用方兜成 Error 状态。
         */
        fun parse(addr: String, cred: String): TsnetProxy {
            val i = addr.lastIndexOf(':')
            require(i > 0) { "proxy addr 缺端口: $addr" }
            val port = addr.substring(i + 1).toIntOrNull()
            require(port != null && port in 1..65535) { "proxy addr 端口非法: $addr" }
            return TsnetProxy(addr.substring(0, i), port, cred)
        }
    }
}

/**
 * tsnet 节点后端薄适配接口。
 *
 * 架构红线：gomobile native 绑定只允许 [GomobileTsnetBackend] 一处触达
 * （其生成类的 static 块会 loadLibrary），状态机/校验/dial 全部面向本接口，
 * JVM 单测用 fake 实现即可覆盖——绝不加载 native。
 */
interface TsnetBackend {
    /**
     * 阻塞式起节点（内部含控制面握手，秒级），成功返回 loopback 代理信息，
     * 失败抛异常。[stateDir] 为节点状态目录（Android 用 filesDir 子目录），
     * [hostname] 为节点在 tailnet 内的名字，[authKey] 已经 [TsnetAuthKeys] 归一化。
     */
    fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy

    /** 停节点并释放资源；未启动时调用应无害。 */
    fun close()
}
