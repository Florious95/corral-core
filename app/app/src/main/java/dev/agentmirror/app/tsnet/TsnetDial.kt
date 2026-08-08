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

import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy

/**
 * dial 选择逻辑：tsnet Up 时 OkHttp 走 loopback SOCKS5 进 tailnet，
 * 其余状态直连（LAN ws:// 原路径不受影响）。
 *
 * 这是 conn/service 层消费 tsnet 的唯一公开面：拿 [TsnetState] 配一个
 * OkHttpClient，即可让 ws://<tailnet-ip> 流量全走用户态栈，零系统权限。
 */
object TsnetDial {
    /** 状态到 java.net.Proxy 的映射：仅 Up 给 SOCKS，其余 NO_PROXY（直连）。 */
    fun proxyFor(state: TsnetState): Proxy = when (state) {
        is TsnetState.Up ->
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(state.proxy.host, state.proxy.port))
        else -> Proxy.NO_PROXY
    }

    /** 把代理选择装进 OkHttp builder（链式返回原 builder）。 */
    fun apply(builder: OkHttpClient.Builder, state: TsnetState): OkHttpClient.Builder =
        builder.proxy(proxyFor(state))
}

/**
 * SOCKS5 认证应答器。JDK 的 SOCKS 客户端只走全局
 * [Authenticator.setDefault]（无 per-socket 口子），节点 Up 后安装本类实例。
 *
 * 安全约束：只应答 loopback 代理自身 host:port 的询问——其他任何代理/服务器
 * 的认证请求一律返回 null，防止 tsnet 凭证被外部代理钓走。
 */
class TsnetSocksAuthenticator(private val proxy: TsnetProxy) : Authenticator() {
    /** 纯判定函数（单测面）：目标匹配才给凭据，用户名固定 `tsnet`（tsnet 上游契约）。 */
    fun credentialsFor(host: String?, port: Int): PasswordAuthentication? =
        if (host == proxy.host && port == proxy.port) {
            PasswordAuthentication("tsnet", proxy.cred.toCharArray())
        } else {
            null
        }

    override fun getPasswordAuthentication(): PasswordAuthentication? =
        credentialsFor(requestingHost, requestingPort)
}
