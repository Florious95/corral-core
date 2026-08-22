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

import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.tsnet.TsnetBackend
import dev.agentmirror.app.tsnet.TsnetProxy
import dev.agentmirror.app.tsnet.TsnetState
import dev.agentmirror.app.tsnet.TsnetWire
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 根因探针：缺陷⑤ —— 系统代理（Clash/Shadowrocket）渗入 tsnet SOCKS 路径
 *
 * 用户真机日志（2026-08-14 12:00，真机复现）：
 *   6 次 SOCKS 拨号里 5 次目标是 127.0.0.1:7892（Clash 本地代理端口），随后均 "unexpected
 *   end of stream"；唯一 1 次目标是 100.75.207.88:9900（真实服务器），立刻 READY。
 *
 * 根因：[OkHttpTransportFactory.create] 在 sf != null（tailnet 路径）时构建的 OkHttpClient
 * 没有 .proxy(Proxy.NO_PROXY)，OkHttp 默认继承 ProxySelector.getDefault()（系统代理）；
 * 系统代理（Clash 等）返回 127.0.0.1:7892，OkHttp 先连该地址再走 HTTP CONNECT 到服务器，
 * 但 tsnet netstack 内 127.0.0.1:7892 即系统 loopback：连通但 Clash 无法路由 tailnet IP。
 *
 * 修法（leader 裁定）：OkHttpTransportFactory.kt sf != null 分支追加 .proxy(Proxy.NO_PROXY)。
 *
 * 探针策略：
 * - T1（红测）：直接调用 [OkHttpTransportFactory.create] 真实路径（sf != null），
 *   用自定义 ProxySelector 统计 select() 被调用次数。
 *   OkHttp 有 .proxy(NO_PROXY) 时完全跳过 ProxySelector.select()；无时必调用。
 *   当前 HEAD 无 NO_PROXY → select() 被调用（count > 0）→ T1 FAIL（红）。
 *   追加 .proxy(Proxy.NO_PROXY) 后 → select() 不被调用（count == 0）→ T1 PASS（绿）。
 * - T2（防过度修复）：sf == null 代码路径（无 socketFactory 注入）→ ProxySelector 仍应被访问。
 *   修法只在 sf != null 分支加 NO_PROXY；sf == null 分支不变 → T2 改动前后均 PASS（绿）。
 *
 * 命中条件：T1 RED（当前 HEAD） + T2 GREEN（当前 HEAD 及修法后）。
 */
class SystemProxyLeakProbeTest {

    private val originalProxySelector: ProxySelector = ProxySelector.getDefault()

    @After
    fun tearDown() {
        ProxySelector.setDefault(originalProxySelector)
        TsnetWire.resetForTest()
    }

    /**
     * T1（红测）：[OkHttpTransportFactory.create] 在 sf != null 路径缺少 .proxy(NO_PROXY) 时
     * OkHttp 将查询系统代理（ProxySelector.select() 被调用）。
     *
     * 观测量：ProxySelector.select() 调用次数。OkHttp 文档保证：
     * - client.proxy() 显式设置（含 NO_PROXY）→ 绕过 ProxySelector（select() == 0）
     * - client.proxy() == null → 调用 ProxySelector.select()（count ≥ 1）
     *
     * 当前 HEAD（无 NO_PROXY）：select() count ≥ 1 → assertEquals(0, count) FAIL（红）。
     * 修法后（有 NO_PROXY）：select() count == 0 → PASS（绿）。
     *
     * 使用真实 [OkHttpTransportFactory.create]（不自建 OkHttpClient 副本），
     * 经 TsnetWire 假后端注入让 state 合法走到 Up → socketFactoryFor 返回非 null →
     * sf != null 分支被真实执行。
     */
    @Test
    fun `T1 tsnet socketFactory 注入时系统代理不应被访问`() {
        // ProxySelector 调用计数器：OkHttp 有 .proxy(NO_PROXY) 时不会调用 select()
        val selectCount = AtomicInteger(0)
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                // "socket://" 是 JVM SocksSocketImpl 内部为裸 TCP Socket.connect() 查询
                // 系统 SOCKS 代理时使用的伪协议（TsnetProxySocket.super.connect() 必触发），
                // 与 OkHttp 是否有 .proxy(NO_PROXY) 无关，不计入。
                // OkHttp 代理选择使用 "http://"（ws:// 内部归一化为 http://），这才是目标信号。
                if (uri.scheme == "socket") return emptyList()

                selectCount.incrementAndGet()
                return emptyList() // 不提供真实代理，仅统计是否被查询
            }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
        })

        // 注入假后端让 TsnetWire.state 合法走到 Up（不直接赋值 private set，走正常路径）
        val fakeBackend = object : TsnetBackend {
            private val proxySocket = ServerSocket(0)
            override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy =
                TsnetProxy("127.0.0.1", proxySocket.localPort, "probe-sl-cred")
            override fun close() = proxySocket.close()
        }
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-probe-sl", "sl-probe")
        TsnetWire.backendFactory = { fakeBackend }
        TsnetWire.executorForTest = Executor { it.run() }
        TsnetWire.ensureStarted("tskey-auth-slprobe-1234")
        assertTrue("前置：TsnetWire.state 应为 Up（使 socketFactoryFor 返回非 null）", TsnetWire.state is TsnetState.Up)

        // 调用真实 OkHttpTransportFactory.create（sf != null 分支被执行）
        // recordConnectionPath=false 跳过 ServiceWire 调用（JVM 测试无 Android Context）
        val transport = OkHttpTransportFactory.create("ws://100.75.207.88:9900/ws", recordConnectionPath = false)

        // 触发 OkHttp 连接尝试并等待终结回调（连接必然失败：假 SOCKS 代理接受后即关闭）
        val latch = CountDownLatch(1)
        transport.start(object : TransportListener {
            override fun onOpen() = latch.countDown()
            override fun onText(text: String) {}
            override fun onBinary(bytes: ByteArray) {}
            override fun onClosed(code: Int, reason: String) = latch.countDown()
            override fun onFailure(throwable: Throwable) = latch.countDown()
        })
        latch.await(5, TimeUnit.SECONDS)

        // T1 断言（当前 HEAD 无 NO_PROXY → FAIL → 红测命中）：
        // 修法加 .proxy(Proxy.NO_PROXY) 后 select() 不被调用 → count == 0 → PASS。
        assertEquals(
            "T1 红测命中：sf != null 时 OkHttp 查询了 ProxySelector（缺少 .proxy(NO_PROXY)）；" +
                "修法追加 .proxy(Proxy.NO_PROXY) 后 count 应为 0",
            0,
            selectCount.get(),
        )
    }

    /**
     * T2（防过度修复）：sf == null 路径（无 socketFactory 注入）系统代理仍应被查询。
     *
     * 修法只在 sf != null 分支加 NO_PROXY；sf == null 分支（LAN 直拨）不变——
     * 若粗暴在 baseClient 上全局加 NO_PROXY 会把强制代理网络下的非 tailnet 场景弄坏。
     * T2 确保修法不引入过度覆盖：sf==null 下 ProxySelector 仍被访问（记账代理被 TCP 连）。
     *
     * 当前 HEAD：PASS（绿）。修法后：仍 PASS（绿）。如果 T2 变红说明修法过度。
     */
    @Test
    fun `T2 无 tsnet socketFactory 时系统代理应正常生效（防过度修复）`() {
        val accountingProxy = AccountingProxy()

        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", accountingProxy.port)),
            )
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
        })

        // 构造方式与 OkHttpTransportFactory.create(sf==null) 一致（line 203）：
        //   val chosen = if (sf == null) client else ...
        // sf == null → 直接用 baseClient（不注入 socketFactory，不加 NO_PROXY）。
        val baseClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        try {
            baseClient.newCall(
                Request.Builder().url("http://100.75.207.88:9900/ws").build(),
            ).execute().close()
        } catch (_: Exception) {}
        baseClient.dispatcher.executorService.shutdown()

        accountingProxy.close()

        // T2 断言（当前 HEAD 及修法后均应 PASS）：系统代理被访问次数 > 0。
        assertTrue(
            "T2 防过度修复：sf == null（非 tailnet）路径系统代理应正常生效（记账代理应被访问）",
            accountingProxy.connectionCount.get() > 0,
        )
    }

    // ---- 辅助类 ----

    /**
     * 记账代理：本地 ServerSocket，统计被 TCP 连接的次数。
     * 接受连接即计数后关闭（模拟拒绝服务），触发 OkHttp 连接失败并回调 connectFailed。
     * T2 用于断言 sf==null 路径下系统代理被访问（防过度修复）。
     */
    private class AccountingProxy : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        val connectionCount = AtomicInteger(0)

        init {
            val t = Thread {
                while (!server.isClosed) {
                    try {
                        server.accept().use { connectionCount.incrementAndGet() }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            t.isDaemon = true
            t.start()
        }

        override fun close() = server.close()
    }
}
