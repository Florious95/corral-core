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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * dial 选择逻辑单测：tsnet Up 走 loopback SOCKS5，其余状态直连；
 * SOCKS 认证只应答 loopback 代理本身（防凭证外泄给任意代理请求方）；
 * proxyAddr 字符串解析（gomobile 返回 "host:port" 一体串）。
 */
class TsnetDialTest {

    private val proxy = TsnetProxy("127.0.0.1", 49677, "s3cr3t")

    @Test
    fun `非 Up 状态一律直连`() {
        assertEquals(Proxy.NO_PROXY, TsnetDial.proxyFor(TsnetState.Idle))
        assertEquals(Proxy.NO_PROXY, TsnetDial.proxyFor(TsnetState.Starting))
        assertEquals(Proxy.NO_PROXY, TsnetDial.proxyFor(TsnetState.Error("x")))
    }

    @Test
    fun `Up 状态给 SOCKS 代理指向 loopback`() {
        val p = TsnetDial.proxyFor(TsnetState.Up(proxy))
        assertEquals(Proxy.Type.SOCKS, p.type())
        assertEquals(InetSocketAddress("127.0.0.1", 49677), p.address())
    }

    @Test
    fun `apply 把代理装进 OkHttp builder`() {
        val client: OkHttpClient =
            TsnetDial.apply(OkHttpClient.Builder(), TsnetState.Up(proxy)).build()
        assertEquals(Proxy.Type.SOCKS, client.proxy?.type())
        val direct: OkHttpClient =
            TsnetDial.apply(OkHttpClient.Builder(), TsnetState.Idle).build()
        assertEquals(Proxy.NO_PROXY, direct.proxy)
    }

    @Test
    fun `SOCKS 凭证只应答代理自身地址`() {
        val auth = TsnetSocksAuthenticator(proxy)
        val ok = auth.credentialsFor("127.0.0.1", 49677)
        assertEquals("tsnet", ok?.userName)
        assertEquals("s3cr3t", ok?.password?.let { String(it) })
        assertNull(auth.credentialsFor("127.0.0.1", 1080))
        assertNull(auth.credentialsFor("evil.example", 49677))
        assertNull(auth.credentialsFor(null, 49677))
    }

    // ---- feat-ts-wire 红测：按目标地址选路（leader 裁定：仅 CGNAT 段走 SOCKS，不引入全局代理）----

    @Test
    fun `tailnet 段判定 - 100_64 到 100_127 为真其余为假`() {
        // 100.64.0.0/10 边界：first==100 && second in 64..127。
        assertEquals(true, TsnetDial.isTailnetHost("100.64.0.1"))
        assertEquals(true, TsnetDial.isTailnetHost("100.101.2.3"))
        assertEquals(true, TsnetDial.isTailnetHost("100.127.255.254"))
        assertEquals(false, TsnetDial.isTailnetHost("100.63.255.255"))
        assertEquals(false, TsnetDial.isTailnetHost("100.128.0.0"))
        assertEquals(false, TsnetDial.isTailnetHost("192.168.1.5"))
        assertEquals(false, TsnetDial.isTailnetHost("10.20.55.20"))
        // 非 IPv4（主机名/坏串/空）一律不判为 tailnet——直拨，不猜 DNS。
        assertEquals(false, TsnetDial.isTailnetHost("myhost.example"))
        assertEquals(false, TsnetDial.isTailnetHost("100.64.0"))
        assertEquals(false, TsnetDial.isTailnetHost("100.64.0.256"))
        assertEquals(false, TsnetDial.isTailnetHost(""))
        assertEquals(false, TsnetDial.isTailnetHost(null))
    }

    @Test
    fun `按址选路 - tailnet 目标且 Up 才给 SOCKS socket 工厂`() {
        assertNotNull(TsnetDial.socketFactoryFor(TsnetState.Up(proxy), "100.101.2.3"))
    }

    @Test
    fun `按址选路 - LAN 目标即使 Up 也直拨（不引入全局代理）`() {
        assertNull(TsnetDial.socketFactoryFor(TsnetState.Up(proxy), "192.168.1.5"))
        assertNull(TsnetDial.socketFactoryFor(TsnetState.Up(proxy), "myhost.example"))
    }

    @Test
    fun `按址选路 - 无 authkey 降级不回退（tailnet 目标未 Up 直拨自然失败）`() {
        // 红测（验收边界）：节点未起（Idle/Starting/Error）时 tailnet 目标不换路、
        // 不兜底、不给工厂——直拨该地址让失败可见（003），LAN 路径零影响。
        assertNull(TsnetDial.socketFactoryFor(TsnetState.Idle, "100.101.2.3"))
        assertNull(TsnetDial.socketFactoryFor(TsnetState.Starting, "100.101.2.3"))
        assertNull(TsnetDial.socketFactoryFor(TsnetState.Error("bad key"), "100.101.2.3"))
    }

    @Test
    fun `proxyAddr 解析 - v4 与非法输入`() {
        assertEquals(proxy, TsnetProxy.parse("127.0.0.1:49677", "s3cr3t"))
        assertThrows(IllegalArgumentException::class.java) {
            TsnetProxy.parse("no-port", "c")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TsnetProxy.parse("127.0.0.1:notaport", "c")
        }
    }
}
