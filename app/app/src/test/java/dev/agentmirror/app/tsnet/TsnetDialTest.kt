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
