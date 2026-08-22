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

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HttpUrlConnectionUploader 接缝零测（test-app-android-seams 交付物；B6 实锤：整类零测）。
 *
 * 覆盖知识基底 §0 第二类全部错误分支 + 正常路径：
 * - multipart 组包正确性（boundary/Content-Disposition/文件字节/结束边界/CRLF 剥离）；
 * - 非 200 响应、JSON 不可解析、path 空、base 未配（空串）；
 * - 每个错误分支都有独立文案，逐一断言（003 静默失效猎杀：绝不静默）。
 *
 * 网络：MockWebServer 本地假端点（conn 层既有依赖，复用现有测试形状；知识基底 §3 勿打真网）。
 * 用 `com.sun.net.httpserver` 不可行——Android bootclasspath（android.jar）不暴露该 JDK 模块，
 * `jdk.httpserver` 只在 JVM classpath；MockWebServer 是同仓库已验证的本地假端点方案。
 */
class HttpUrlConnectionUploaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer() // 绑定本地随机端口，零真实外网交互。
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown() // 进程卫生：每用例关闭假端点，零残留监听。
    }

    /** 上传器调用的 base：MockWebServer 根 URL（http://127.0.0.1:port/）。 */
    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun attachment(
        name: String = "photo.png",
        mime: String = "image/png",
        bytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5),
    ) = Attachment(name = name, mimeType = mime, bytes = bytes)

    /** 取刚收到的请求，并断言它的 multipart 组包形状（boundary/段头/字节/结束边界）。 */
    private fun assertMultipartShape(req: okhttp3.mockwebserver.RecordedRequest, attachment: Attachment) {
        val body = req.body.readUtf8()
        // boundary 由 "AgentMirrorBoundary{时间戳}" 派生，确切值从 Content-Type 头回读
        // （组包自洽：头的 boundary == 体的边界行 delimiter，二者同源才可被服务端解析）。
        val contentType = req.getHeader("Content-Type") ?: throw AssertionError("no Content-Type header")
        val boundary = "AgentMirrorBoundary".let { p ->
            Regex("boundary=($p\\d+)").find(contentType)?.groupValues?.get(1)
                ?: throw AssertionError("Content-Type 头无 AgentMirrorBoundary: $contentType")
        }
        val delim = "--$boundary"

        assertTrue("opening boundary missing", body.startsWith("$delim\r\n"))
        assertTrue(
            "file header missing",
            body.contains("Content-Disposition: form-data; name=\"file\"; filename=\"${attachment.name}\""),
        )
        assertTrue("mime missing", body.contains("Content-Type: ${attachment.mimeType}"))
        assertTrue("closing boundary missing", body.endsWith("\r\n$delim--\r\n"))

        // 文件字节原样在段头（\r\n\r\n）之后。
        val headEnd = body.indexOf("\r\n\r\n") + 4
        attachment.bytes.forEachIndexed { i, b ->
            assertTrue("file byte $i not raw", body[i + headEnd].code.toByte() == b)
        }
    }

    // ---- 正常路径 ----

    @Test
    fun upload_success_returnsPath() {
        // 协议 §8 成功形状：200 + {"path":"/host/abs"} → Success(path)。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/tmp/uploads/a.png"}"""))
        val result = HttpUrlConnectionUploader().upload(baseUrl(), FAKE_UPLOAD_TOKEN, attachment())

        assertEquals(UploadOutcome.Success("/tmp/uploads/a.png"), result)
    }

    // ---- multipart 组包正确性 ----

    @Test
    fun multipart_bodyShape_hasBoundariesAndFileHeader() {
        // 组包形状：开/结束边界、单文件段头、文件字节夹中间；Content-Type 头带 boundary。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/x"}"""))
        val fileBytes = byteArrayOf(9, 8, 7, 6)
        HttpUrlConnectionUploader().upload(baseUrl(), FAKE_UPLOAD_TOKEN, attachment(bytes = fileBytes))

        val req = server.takeRequest()
        assertTrue(
            "Content-Type 头未带 boundary",
            (req.getHeader("Content-Type") ?: "").startsWith("multipart/form-data; boundary=AgentMirrorBoundary"),
        )
        assertMultipartShape(req, attachment(name = "photo.png", mime = "image/png", bytes = fileBytes))
    }

    @Test
    fun multipart_sanitizesCrLfInFilename() {
        // CR/LF 剥离（防 multipart 头注入）：文件名带 \r\n 不得出现在组包头里。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/x"}"""))
        HttpUrlConnectionUploader().upload(
            baseUrl(),
            FAKE_UPLOAD_TOKEN,
            attachment(name = "evil\r\nbad.png"),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("filename=\"evilbad.png\"")) // \r\n 被剥离成空
        assertTrue(!body.contains("filename=\"evil\r\n")) // 注入段头不出现
    }

    // ---- 错误分支：每个都有独立文案，逐一断言 ----

    @Test
    fun upload_http413_mapsToFailureText() {
        // B8 锚点：413 上传超限 → App 明确文案映射（"上传失败（HTTP 413）"）。
        server.enqueue(MockResponse().setResponseCode(413).setBody("payload too large"))
        val result = HttpUrlConnectionUploader().upload(baseUrl(), FAKE_UPLOAD_TOKEN, attachment())

        assertTrue(result is UploadOutcome.Failure)
        assertEquals("上传失败（HTTP 413）", (result as UploadOutcome.Failure).reason)
    }

    @Test
    fun upload_http500_mapsToFailureText() {
        // 500 服务端内部错误 → 明确文案。
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val result = HttpUrlConnectionUploader().upload(baseUrl(), FAKE_UPLOAD_TOKEN, attachment())

        assertTrue(result is UploadOutcome.Failure)
        assertEquals("上传失败（HTTP 500）", (result as UploadOutcome.Failure).reason)
    }

    @Test
    fun upload_unparsableJson_mapsToFailureText() {
        // 200 但 JSON 不可解析 → "上传响应无法解析"（坏响应绝不静默吞）。
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))
        val result = HttpUrlConnectionUploader().upload(baseUrl(), FAKE_UPLOAD_TOKEN, attachment())

        assertTrue(result is UploadOutcome.Failure)
        assertEquals("上传响应无法解析", (result as UploadOutcome.Failure).reason)
    }

    @Test
    fun upload_emptyPath_mapsToFailureText() {
        // JSON 可解析但 path 空 → "上传响应缺少路径"。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":""}"""))
        val result = HttpUrlConnectionUploader().upload(baseUrl(), FAKE_UPLOAD_TOKEN, attachment())

        assertTrue(result is UploadOutcome.Failure)
        assertEquals("上传响应缺少路径", (result as UploadOutcome.Failure).reason)
    }

    @Test
    fun upload_baseUrlNotConfigured_mapsToFailureText() {
        // base 未配（空串）：trimEnd 后拼 "/upload" 得 "/upload"——无 scheme，URL 构造抛异常被
        // 兜住，报"上传失败：..."（含原因，绝不静默）。
        val result = HttpUrlConnectionUploader().upload("", FAKE_UPLOAD_TOKEN, attachment())

        assertTrue(result is UploadOutcome.Failure)
        assertTrue((result as UploadOutcome.Failure).reason.startsWith("上传失败"))
    }

    @Test
    fun upload_unreachableServer_mapsToFailureText() {
        // base 配了但端点不可达（先关假端点，端口即释放）→ 连接异常被兜住，明确报失败。
        server.shutdown()
        val result = HttpUrlConnectionUploader().upload(baseUrl(), FAKE_UPLOAD_TOKEN, attachment())

        assertTrue(result is UploadOutcome.Failure)
        assertTrue((result as UploadOutcome.Failure).reason.startsWith("上传失败"))
    }

    @Test
    fun upload_baseUrlTrailingSlash_stillWorks() {
        // base 末尾带斜杠：trimEnd('/') 后拼接，成功路径不受影响。
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"/tmp/x"}"""))
        val result = HttpUrlConnectionUploader().upload("${baseUrl()}/", FAKE_UPLOAD_TOKEN, attachment())
        assertEquals(UploadOutcome.Success("/tmp/x"), result)
    }

    @Test
    fun upload_withoutAuthentication_failsBeforeHttpRequest() {
        val result = HttpUrlConnectionUploader().upload(baseUrl(), attachment())

        assertEquals(UploadOutcome.Failure("未配置上传认证"), result)
        assertEquals("缺少认证时不得发起 HTTP 请求", 0, server.requestCount)
    }

    private companion object {
        const val FAKE_UPLOAD_TOKEN = "fake-upload-token"
    }
}
