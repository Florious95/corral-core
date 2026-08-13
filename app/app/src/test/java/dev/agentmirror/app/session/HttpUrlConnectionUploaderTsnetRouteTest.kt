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

package dev.agentmirror.app.session

import dev.agentmirror.app.tsnet.TsnetBackend
import dev.agentmirror.app.tsnet.TsnetProxy
import dev.agentmirror.app.tsnet.TsnetWire
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * 红测（fix-upload-transport-tsnet）：上传必须与 WebSocket 同一传输通道。
 *
 * 根因（leader 已查实 + 用户真机实证）：WS 经 tsnet loopback SOCKS5 拨号
 * （[dev.agentmirror.app.service.OkHttpTransportFactory] 经 [dev.agentmirror.app.tsnet.TsnetDial.socketFactoryFor]
 * 注入 [dev.agentmirror.app.tsnet.TsnetProxySocketFactory]），而上传走 HttpURLConnection 系统直连
 * ——两条通道不同，tailnet 下 WS 通、上传 connectTimeout（用户报错源地址是蜂窝地址而非 tailnet 地址）。
 *
 * 修法（与 w-up-dev 约定同一接缝）：上传器读与 [dev.agentmirror.app.service.OkHttpWebSocketTransport]
 * 同一份 [TsnetWire.state]，经 [dev.agentmirror.app.tsnet.TsnetDial.socketFactoryFor] 选路——tsnet Up
 * 且目标是 tailnet host（100.64/10）时经 SOCKS 建连，否则保持系统直连（LAN/域名/未 Up 零行为变化）。
 *
 * 为什么断言「CONNECT 到达真实 SOCKS 代理」而非比较 java.net.Proxy 对象：
 * round1（fix-upload-transport-tsnet.round1.json）实证 WS 生产路径**不用**
 * [dev.agentmirror.app.tsnet.TsnetDial.proxyFor] 返回的 java.net.Proxy.Type.SOCKS——Android libcore
 * 内建 SOCKS 客户端对 tsnet 代理的 RFC 1929 认证不生效（[dev.agentmirror.app.tsnet.TsnetSocks] KDoc，
 * 有模拟器实证），WS 实际用自实现握手的 [dev.agentmirror.app.tsnet.TsnetProxySocketFactory]。
 * 若红测按「java.net.Proxy == proxyFor(Up)」写，会放行一条真机上重演「模拟器绿、真机坏」的
 * 伪修复。行为断言（代理真实收到 CONNECT + 请求经代理转发到达服务端）才是「同一通道」的可观察契约。
 *
 * 本类在 JVM 上搭真实 SOCKS5 代理（RFC 1928 + RFC 1929 认证，同 tsnet loopback 契约），验证：
 * - [upload_tsnetUp_tailnetHost_goesThroughSocks]：Up + 100.x → 上传请求真实经 SOCKS 建连
 *   （CONNECT 到达代理）+ 请求被服务端接收成功，Bearer 与 multipart 完整保留；**这是本任务唯一
 *   在 HEAD 上为红的用例**（HEAD 上传器不读 [TsnetWire.state]，恒直连 100.x → 不可达 → Failure）。
 * - [upload_tsnetUp_lanHost_staysDirect]：Up + 127.0.0.1 LAN → 直连，SOCKS 代理零 CONNECT
 *   （不得为了修 tailnet 把 LAN 弄坏）。
 * - [upload_tsnetUp_domainHost_staysDirect]：Up + 域名（localhost）→ 直连（仅 tailnet 段 IP 走代理）。
 * - [upload_tsnetError_staysDirect]：节点 Error → 系统直连（Down 分支不倒退闸）。
 * - [upload_tsnetIdle_directConnect]：未起网 → 系统直连（Down 分支不倒退闸）。
 *
 * 纪律：TsnetWire 进程级单例，@Before/@After 双向复位（TsnetWireTest 同款）；后端注入假件
 * 绝不触达 gomobile native；不读任何真实凭据，测试用假 token。
 */
class HttpUrlConnectionUploaderTsnetRouteTest {

    private lateinit var server: MockWebServer
    private var socks: TestSocks5Server? = null

    /**
     * 环境净化（shear）：本机测试 JVM 会从 shell 环境继承 Shadowrocket 的
     * http.proxyHost=http://127.0.0.1:1082。桌面 JDK 的 `URL.openConnection()` 默认读取
     * http.proxyHost/http.proxyPort 系统属性——于是「直连」路径在本机被系统 HTTP 代理劫持，
     * 连不可达的 tailnet 地址也会被代理转发"成功"，污染红测判据（纪律⑨实证）。真机（Android
     * HttpURLConnection 走全局 ProxySelector）无此环境变量，直连就是直连。测试要测的是代码
     * 选路（通道），不是桌面环境——保存并清除系统代理属性，测完还原，零副作用。
     */
    private val ambientProxyProps = listOf(
        "http.proxyHost", "http.proxyPort", "http.nonProxyHosts",
        "https.proxyHost", "https.proxyPort",
        "socksProxyHost", "socksProxyPort", "socksNonProxyHosts",
        "java.net.useSystemProxies",
    ).associateWith { System.getProperty(it) }

    private fun clearAmbientProxy() {
        for (k in ambientProxyProps.keys) System.clearProperty(k)
    }

    private fun restoreAmbientProxy() {
        for ((k, v) in ambientProxyProps) {
            if (v == null) System.clearProperty(k) else System.setProperty(k, v)
        }
    }

    @Before
    fun setUp() {
        clearAmbientProxy()
        TsnetWire.resetForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        socks?.close()
        TsnetWire.resetForTest()
        restoreAmbientProxy()
    }

    /** 起 tsnet 假后端到 Up：代理指向真实测试 SOCKS 服务器，执行器直通（同步起网）。 */
    private fun bringTsnetUp(proxy: TsnetProxy) {
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-test", "agentmirror-test")
        TsnetWire.executorForTest = Executor { it.run() }
        TsnetWire.backendFactory = { object : TsnetBackend {
            override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy = proxy
            override fun close() = Unit
        } }
        TsnetWire.ensureStarted("tskey-test")
        assertTrue("tsnet 必须到 Up，实际=${TsnetWire.state}", TsnetWire.state is dev.agentmirror.app.tsnet.TsnetState.Up)
    }

    private fun attachment(name: String = "photo.png") =
        Attachment(name = name, mimeType = "image/png", bytes = byteArrayOf(1, 2, 3, 4, 5))

    @Test
    fun debug_whoConnectsToSocksWithoutTsnet() {
        socks = TestSocks5Server()
        // 不 bringTsnetUp——纯看有没有别的东西连到 SOCKS 端口。
        Thread.sleep(2000)
        println("DEBUG-NOTSOCKS connectedTargets=${socks!!.connectedTargets}")
    }

    @Test
    fun debug_doesEnsureStartedConnectToSocks() {
        socks = TestSocks5Server()
        bringTsnetUp(TsnetProxy("127.0.0.1", socks!!.port, "test-cred"))
        Thread.sleep(1500)
        println("DEBUG-AFTER-UP connectedTargets=${socks!!.connectedTargets}")
        // 直接问 JDK ProxySelector：它对 tailnet 目标 URL 选什么代理。
        val uri = java.net.URI("http://100.101.2.3:${server.port}/")
        val sel = java.net.ProxySelector.getDefault()
        println("DEBUG-SELECTOR class=${sel?.javaClass?.name} selected=${sel?.select(uri)}")
        println("DEBUG-EFFPROXY httpProxyHost=${System.getProperty("http.proxyHost")} httpProxyPort=${System.getProperty("http.proxyPort")} socksProxyHost=${System.getProperty("socksProxyHost")}")
        // 用 java.net.Socket 直连（无 ProxySelector 介入），看能不能连到 100.101.2.3。
        val s = java.net.Socket()
        s.soTimeout = 3000
        val t0 = System.currentTimeMillis()
        try {
            s.connect(java.net.InetSocketAddress("100.101.2.3", server.port), 3000)
            println("DEBUG-RAW-CONNECT ok in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            println("DEBUG-RAW-CONNECT failed: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            s.close()
        }
        // 检查 java.net.http.HttpClient 与 SocketFactory 默认值（可能被 Robolectric 或 env 全局改过）。
        println("DEBUG-SOCKETFACTORY default=${javax.net.SocketFactory.getDefault()?.javaClass?.name}")
        println("DEBUG-NETPROPS " + System.getProperties().entries.filter { it.key.toString().lowercase().contains("proxy") }.joinToString { "${it.key}=${it.value}" })
        val result = HttpUrlConnectionUploader().upload(
            "http://100.101.2.3:${server.port}/", FAKE_UPLOAD_TOKEN, attachment(),
        )
        println("DEBUG-AFTER-UPLOAD connectedTargets=${socks!!.connectedTargets} result=$result")
    }

    // ---- 路径 1：tsnet Up + tailnet host → 经 SOCKS 建连（本缺陷，HEAD 上必须红）----

    @Test
    fun upload_tsnetUp_tailnetHost_goesThroughSocks() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/host/uploads/probe.png"}"""))
        socks = TestSocks5Server()
        // tailnet 目标 100.x：URL 端口即 MockWebServer 端口，代理把 CONNECT 转发到 127.0.0.1 同端口。
        val baseUrl = "http://100.101.2.3:${server.port}/"
        bringTsnetUp(TsnetProxy("127.0.0.1", socks!!.port, "test-cred"))

        val result = HttpUrlConnectionUploader().upload(baseUrl, FAKE_UPLOAD_TOKEN, attachment())

        // 决定性断言：请求真实经 SOCKS 代理建连（CONNECT 目标 = 上传 endpoint 的 tailnet host:port）。
        val connects = socks!!.connectedTargets
        println(
            "DEBUG-CONNECTS=$connects RESULT=$result " +
                "useSysProxies=${System.getProperty("java.net.useSystemProxies")} " +
                "httpProxy=${System.getProperty("http.proxyHost")}:${System.getProperty("http.proxyPort")}",
        )
        assertTrue("必须经 SOCKS 代理收到 CONNECT，实际=$connects", connects.contains("100.101.2.3" to server.port))
        // 且请求经代理转发后被服务端接收成功（端到端通）。
        assertTrue("经 SOCKS 的上传必须成功，实际=${result}", result is UploadOutcome.Success)

        // D-22 不回归：SOCKS 路径同样携带 Bearer 与完整 multipart。
        val req = server.takeRequest()
        assertEquals("Bearer $FAKE_UPLOAD_TOKEN", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().startsWith("--AgentMirrorBoundary"))
    }

    // ---- 路径 2：tsnet Up 但目标是 LAN host → 保持直连（不得破坏 LAN）----

    @Test
    fun upload_tsnetUp_lanHost_staysDirect() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/host/uploads/lan.png"}"""))
        socks = TestSocks5Server()
        bringTsnetUp(TsnetProxy("127.0.0.1", socks!!.port, "test-cred"))

        val result = HttpUrlConnectionUploader().upload(server.url("/").toString(), FAKE_UPLOAD_TOKEN, attachment())

        assertTrue("LAN 目标即使 tsnet Up 也必须直连成功，实际=${result}", result is UploadOutcome.Success)
        assertEquals("LAN 直连不得触发 SOCKS CONNECT", emptyList<Pair<String, Int>>(), socks!!.connectedTargets)
        server.takeRequest() // 请求直接到达假端点（未被代理转发）
    }

    // ---- 路径 3：tsnet Up 但目标是域名（非 tailnet 段 IP）→ 保持直连 ----

    @Test
    fun upload_tsnetUp_domainHost_staysDirect() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/host/uploads/domain.png"}"""))
        socks = TestSocks5Server()
        bringTsnetUp(TsnetProxy("127.0.0.1", socks!!.port, "test-cred"))
        // localhost 是域名（非 100.64/10 字面 IPv4）→ isTailnetHost=false → 直连；
        // MockWebServer 绑在 127.0.0.1，localhost 解析到回环。
        val baseUrl = server.url("/").toString().replace("127.0.0.1", "localhost").trimEnd('/')

        val result = HttpUrlConnectionUploader().upload(baseUrl, FAKE_UPLOAD_TOKEN, attachment())

        assertTrue("域名目标即使 tsnet Up 也必须直连成功，实际=${result}", result is UploadOutcome.Success)
        assertEquals("域名直连不得触发 SOCKS CONNECT", emptyList<Pair<String, Int>>(), socks!!.connectedTargets)
        server.takeRequest()
    }

    // ---- 路径 4：tsnet 节点 Error → 系统直连（Down 分支不倒退闸）----

    @Test
    fun upload_tsnetError_staysDirect() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/host/uploads/error.png"}"""))
        TsnetWire.environment = TsnetWire.Environment("/tmp/ts-test", "agentmirror-test")
        TsnetWire.executorForTest = Executor { it.run() }
        TsnetWire.backendFactory = { object : TsnetBackend {
            override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy =
                throw IllegalStateException("boom")
            override fun close() = Unit
        } }
        TsnetWire.ensureStarted("tskey-test")
        assertTrue("tsnet 必须到 Error", TsnetWire.state is dev.agentmirror.app.tsnet.TsnetState.Error)

        val result = HttpUrlConnectionUploader().upload(server.url("/").toString(), FAKE_UPLOAD_TOKEN, attachment())

        assertTrue("tsnet Error 时必须保持系统直连，实际=${result}", result is UploadOutcome.Success)
        server.takeRequest()
    }

    // ---- 路径 5：tsnet 未起网 → 系统直连 ----

    @Test
    fun upload_tsnetIdle_directConnect() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/host/uploads/idle.png"}"""))
        // 不 ensureStarted：state 保持 Idle。
        val result = HttpUrlConnectionUploader().upload(server.url("/").toString(), FAKE_UPLOAD_TOKEN, attachment())

        assertTrue("未起网必须保持系统直连，实际=${result}", result is UploadOutcome.Success)
        server.takeRequest()
    }

    private companion object {
        const val FAKE_UPLOAD_TOKEN = "fake-upload-token"
    }
}

/**
 * 测试专用极简 SOCKS5 代理（RFC 1928 + RFC 1929）：要求用户/密码认证（同 tsnet loopback
 * 契约），接受任意 CONNECT 目标并把流量转发到 127.0.0.1:<目标端口>（测试里 tailnet 100.x
 * 不可路由，用 MockWebServer 端口对齐）。记录收到的 CONNECT 目标供断言。
 */
private class TestSocks5Server : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = serverSocket.localPort

    /** 收到的 CONNECT 目标 (host, port) 列表。 */
    val connectedTargets = CopyOnWriteArrayList<Pair<String, Int>>()

    private val acceptThread = Thread({ acceptLoop() }, "test-socks5-accept").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
            Thread({ handle(client) }, "test-socks5-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            // 问候：VER NMETHODS METHODS[] → 选 0x02（user/pass）。
            check(input.read() == 0x05) { "greeting ver" }
            val nMethods = input.read()
            repeat(nMethods) { input.read() }
            output.write(byteArrayOf(0x05, 0x02)); output.flush()
            // RFC 1929 子协商：读用户/口令（不校验值，只保证握手走通），回成功。
            check(input.read() == 0x01) { "auth ver" }
            val uLen = input.read()
            repeat(uLen) { input.read() }
            val pLen = input.read()
            repeat(pLen) { input.read() }
            output.write(byteArrayOf(0x01, 0x00)); output.flush()
            // CONNECT：VER CMD RSV ATYP ADDR PORT。
            check(input.read() == 0x05) { "connect ver" }
            check(input.read() == 0x01) { "connect cmd" }
            check(input.read() == 0x00) { "connect rsv" }
            val host = when (val atyp = input.read()) {
                0x01 -> { val b = ByteArray(4); readFully(input, b); b.joinToString(".") { (it.toInt() and 0xff).toString() } }
                0x03 -> { val l = input.read(); val b = ByteArray(l); readFully(input, b); String(b) }
                0x04 -> { val b = ByteArray(16); readFully(input, b); b.joinToString(":") { (it.toInt() and 0xff).toString() } }
                else -> throw IOException("bad atyp $atyp")
            }
            val targetPort = (input.read() shl 8) or input.read()
            connectedTargets.add(host to targetPort)
            println("DEBUG-SOCKS-CONNECT host=$host port=$targetPort from=${client.inetAddress.hostAddress}:${client.port}")
            val st = Thread.getAllStackTraces()
            for ((thr, frames) in st) {
                val f = frames.take(6).joinToString(" | ") { it.className.substringAfterLast('.') + "." + it.methodName }
                println("DEBUG-STACK thread=${thr.name} frames=$f")
            }
            // 转发到 127.0.0.1:<目标端口>（MockWebServer 就在那里；tailnet IP 在测试里不可路由）。
            val upstream = Socket("127.0.0.1", targetPort).apply { soTimeout = 10_000 }
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
            // 双向管道（阻塞式：客户端写→上游，上游回→客户端）。
            val toUpstream = Thread({
                runCatching { pump(input, upstream.getOutputStream()) }
                runCatching { upstream.shutdownOutput() }
            }).apply { isDaemon = true; start() }
            runCatching { pump(upstream.getInputStream(), output) }
            runCatching { output.close() }
            runCatching { upstream.close() }
            runCatching { client.close() }
        } catch (e: Exception) {
            runCatching { client.close() }
        }
    }

    private fun readFully(input: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            check(n >= 0) { "eof" }
            off += n
        }
    }

    private fun pump(input: java.io.InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        acceptThread.join(1000)
    }
}
