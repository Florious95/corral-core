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

/** 用户可见的实际拨号网络类型；label 是界面/通知使用的固定文案。 */
enum class ConnectionPath(val label: String) {
    LAN("LAN"),
    TAILNET("tailnet"),
}

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

    /**
     * 按目标地址选路（feat-ts-wire，leader 裁定）：仅目标 host 落在 tailscale CGNAT 段
     * （100.64.0.0/10）且节点 Up 才给经 loopback SOCKS5 的 socket 工厂（自实现握手，
     * [TsnetSocks]——Android libcore 内建 SOCKS 客户端认证不生效，模拟器实证），其余
     * （LAN/域名/未 Up）返回 null = 直拨。未 Up 时 tailnet 目标直拨自然失败（降级不
     * 回退，失败经 conn 态可见）。
     */
    fun socketFactoryFor(state: TsnetState, host: String?): TsnetProxySocketFactory? =
        if (isTailnetHost(host) && state is TsnetState.Up) TsnetProxySocketFactory(state.proxy) else null

    /**
     * 目标是否 tailscale CGNAT 段（100.64.0.0/10）字面 IPv4。纯字符串判定：
     * 绝不走 InetAddress（会触发 DNS 解析）；主机名/坏串一律 false（直拨，不猜）。
     * 段判定与服务端 pairing/probe.go tailnetNet 同一契约。
     */
    fun isTailnetHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return false
            if (n !in 0..255) return false
            octets[i] = n
        }
        // /10 掩码展开：首字节 100，次字节高两位 01 → 64..127。
        return octets[0] == 100 && octets[1] in 64..127
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
