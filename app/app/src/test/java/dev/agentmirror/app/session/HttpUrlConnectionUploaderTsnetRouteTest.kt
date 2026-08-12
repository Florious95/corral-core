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
 * 根因（leader 已查实 + 用户实证）：WS 经 tsnet loopback SOCKS5 拨号，而上传走
 * HttpURLConnection 系统直连——两条通道不同，tailnet 下 WS 通、上传 connectTimeout。
 * 修法：上传复用 WS 的选路 [TsnetDial.socketFactoryFor]，tsnet Up 且目标是 tailnet host
 * （100.64/10）时经 SOCKS 建连，否则保持系统直连（LAN 零行为变化）。
 *
 * 本类在 JVM 上搭真实 SOCKS5 代理（RFC 1928 + RFC 1929 认证，同 tsnet loopback 契约），
 * 验证：
 * - [upload_tsnetUp_tailnetHost_goesThroughSocks]：Up + 100.x → 上传请求真实经 SOCKS
 *   建连（CONNECT 到达代理）+ 请求被服务端接收成功，Bearer 与 multipart 完整保留；
 * - [upload_tsnetUp_lanHost_staysDirect]：Up + LAN 目标 → 直连，SOCKS 代理零 CONNECT
 *   （不得为了修 tailnet 把 LAN 弄坏）；
 * - [upload_tsnetIdle_directConnect]：未起网 → 系统直连成功。
 *
 * 纪律：TsnetWire 进程级单例，@Before/@After 双向复位（TsnetWireTest 同款）；后端注入假件
 * 绝不触达 gomobile native；不读任何真实凭据，测试用假 token。
 */
class HttpUrlConnectionUploaderTsnetRouteTest {

    private lateinit var server: MockWebServer
    private var socks: TestSocks5Server? = null

    @Before
    fun setUp() {
        TsnetWire.resetForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        socks?.close()
        TsnetWire.resetForTest()
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

    // ---- 路径 1：tsnet Up + tailnet host → 经 SOCKS 建连 ----

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

    // ---- 路径 3：tsnet 未起网 → 系统直连 ----

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
