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

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * 自实现 SOCKS5 CONNECT 客户端（RFC 1928 + RFC 1929 用户名/密码认证）。
 *
 * 为什么不用 JDK 内建 SOCKS（java.net.Proxy.Type.SOCKS + Authenticator）：
 * 模拟器实证 Android libcore 的 SOCKS 客户端对 tsnet loopback 代理的 RFC1929
 * 认证不生效（拨号恒败且不可诊断——libcore 行为是黑盒），且全局
 * Authenticator.setDefault 本身是进程级可变全局态。自实现握手只有 ~60 行字节
 * 协议，纯流上操作可单测（[handshake]），OkHttp 经 [TsnetProxySocketFactory]
 * 注入，无任何全局态。
 *
 * 凭证红线：cred 只写进代理握手字节流，任何异常消息不携带它。
 */
object TsnetSocks {

    /**
     * 在已连接到代理的流上执行 SOCKS5 握手，令代理与 [host]:[port] 建立通道。
     * 成功返回后流即为目标连接的透明通道；失败抛 [IOException]（消息含 REP 码
     * 语义，不含凭证）。[host] 为 IPv4 字面量走 ATYP=IPv4，否则 ATYP=DOMAIN
     * （tailnet 目标恒为 100.x IPv4，DOMAIN 只是兜底）。
     */
    fun handshake(input: InputStream, output: OutputStream, host: String, port: Int, user: String, pass: String) {
        // 问候：VER=5，提供 no-auth 与 user/pass 两法（服务端按需选 0x02）。
        output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        output.flush()
        val ver = input.readByteOrThrow("greeting")
        val method = input.readByteOrThrow("greeting")
        if (ver != 0x05) throw IOException("SOCKS5 问候应答版本非法: $ver")
        when (method) {
            0x00 -> Unit // 无需认证
            0x02 -> {
                // RFC 1929 子协商：VER=1 ULEN USER PLEN PASS → VER STATUS。
                val u = user.toByteArray(Charsets.ISO_8859_1)
                val p = pass.toByteArray(Charsets.ISO_8859_1)
                if (u.size > 255 || p.size > 255) throw IOException("SOCKS5 凭证超长")
                output.write(byteArrayOf(0x01, u.size.toByte()))
                output.write(u)
                output.write(byteArrayOf(p.size.toByte()))
                output.write(p)
                output.flush()
                input.readByteOrThrow("auth") // 子协商版本字节（宽容：不校验具体值）
                if (input.readByteOrThrow("auth") != 0x00) {
                    throw IOException("SOCKS5 代理认证被拒")
                }
            }
            else -> throw IOException("SOCKS5 代理要求不支持的认证法: $method")
        }
        // CONNECT 请求：VER CMD=1 RSV ATYP ADDR PORT。
        val v4 = parseIpv4(host)
        if (v4 != null) {
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01))
            output.write(v4)
        } else {
            val d = host.toByteArray(Charsets.ISO_8859_1)
            if (d.size > 255) throw IOException("SOCKS5 目标域名超长")
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, d.size.toByte()))
            output.write(d)
        }
        output.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
        output.flush()
        // 应答：VER REP RSV ATYP BND.ADDR BND.PORT；REP!=0 即失败。
        if (input.readByteOrThrow("reply") != 0x05) throw IOException("SOCKS5 应答版本非法")
        val rep = input.readByteOrThrow("reply")
        if (rep != 0x00) throw IOException("SOCKS5 代理建链失败: ${repText(rep)}")
        input.readByteOrThrow("reply") // RSV
        val bndLen = when (val atyp = input.readByteOrThrow("reply")) {
            0x01 -> 4
            0x03 -> input.readByteOrThrow("reply")
            0x04 -> 16
            else -> throw IOException("SOCKS5 应答地址类型非法: $atyp")
        }
        repeat(bndLen + 2) { input.readByteOrThrow("reply") } // 排干 BND.ADDR+PORT
    }

    /** 读一个字节；EOF 即协议中断（代理关连接常见于拒绝），显式抛错。 */
    private fun InputStream.readByteOrThrow(stage: String): Int {
        val b = read()
        if (b < 0) throw IOException("SOCKS5 $stage 阶段代理关闭连接")
        return b
    }

    /** 字面量 IPv4 → 4 字节；非法返回 null（复用 [TsnetDial.isTailnetHost] 同款纯解析）。 */
    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n.toByte()
        }
        return out
    }

    /** REP 码 → 可读原因（RFC 1928 §6）。 */
    private fun repText(rep: Int): String = when (rep) {
        0x01 -> "general failure"
        0x02 -> "connection not allowed"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "rep=$rep"
    }
}

/**
 * 经 tsnet loopback SOCKS5 拨号的 Socket：connect() 先连代理再握手到真实目标。
 * OkHttp 经 [TsnetProxySocketFactory] 消费（createSocket 无参 + connect 两段式）。
 */
private class TsnetProxySocket(private val proxy: TsnetProxy) : Socket() {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress
            ?: throw IOException("SOCKS5 拨号目标类型非法: $endpoint")
        super.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
        // 握手期间限时读（默认 0=无限会让坏代理挂死拨号线程）；完成后还原由
        // OkHttp 统一管理的读超时语义。
        val prev = soTimeout
        soTimeout = if (timeout > 0) timeout else HANDSHAKE_TIMEOUT_MS
        try {
            val host = target.address?.hostAddress ?: target.hostString
            TsnetSocks.handshake(getInputStream(), getOutputStream(), host, target.port, "tsnet", proxy.cred)
        } finally {
            soTimeout = prev
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}

/** OkHttp socketFactory 注入面：每 createSocket 一条经代理的两段式 Socket。 */
class TsnetProxySocketFactory(private val proxy: TsnetProxy) : SocketFactory() {
    override fun createSocket(): Socket = TsnetProxySocket(proxy)

    // OkHttp 只用无参形态；带参形态按契约补全（建+连）。
    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket =
        createSocket(address, port)
}
