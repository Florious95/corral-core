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

import dev.agentmirror.app.conn.json
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.tsnet.TsnetDial
import dev.agentmirror.app.tsnet.TsnetProxySocketFactory
import dev.agentmirror.app.tsnet.TsnetWire
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

/** 服务端上传回复（协议 §8：`{"path": "/绝对/路径"}`，HTTP 响应体而非 WS 帧）。 */
@Serializable
internal data class UploadResponseDto(
    @SerialName("path") val path: String,
)

/**
 * 生产附件上传器（协议 §8 multipart）：`POST {baseUrl}/upload` → 主机绝对路径。
 *
 * 传输通道与 WebSocket 同一选路（fix-upload-transport-tsnet）：tsnet 状态 Up 且目标是
 * tailnet host（100.64/10）时经 tsnet loopback SOCKS5 建连，否则保持系统直连——
 * 根因是 WS 走 SOCKS 而上传承系统网络栈直连，两条通道不同导致 tailnet 下上传
 * connectTimeout（用户 2026-08-12 真机实证：Timeout 而非 401；源地址是蜂窝地址
 * 而非 tailnet 地址）。复用嵌入式 tsnet 节点已实证可用的隧道 = 由构造保证正确，
 * 不依赖系统 VPN 对 100.x 段的路由行为。
 *
 * 直连路径用 JDK [HttpURLConnection]（LAN/域名/未 Up 时，原路径零行为变化，
 * 一次性短连接不做连接池）；SOCKS 路径用 OkHttp 复用与 WS 相同的
 * [TsnetProxySocketFactory]（自实现 RFC 1929 握手——Android libcore 内建 SOCKS
 * 客户端对 tsnet 代理认证不生效，见 TsnetSocks KDoc）。选路照抄服务层
 * [dev.agentmirror.app.service.OkHttpTransportFactory] 同款模式（拨号时刻现查
 * [TsnetWire.state]），避免两通道选路不一致。
 *
 * multipart 字段名取 "file"；服务端按携带 filename 的首个 part 取文件，不校验字段名
 * （见 server/internal/api/upload.go findFilePart）。响应非 2xx / JSON 无 path /
 * 网络异常 ⇒ 明确失败，绝不静默（003 静默失效猎杀）。
 *
 * @contract
 * @pre baseUrl 非空 http(s) 基地址；uploadToken 为配对配置中的认证 token；attachment.bytes 非空
 * @post 成功返回 [UploadOutcome.Success]（主机绝对路径）；失败返回 [UploadOutcome.Failure]（人类可读原因）
 * @err 网络/IO 异常、非 2xx 响应、JSON 无 path 或 path 为空，全部折叠为 [UploadOutcome.Failure]，不抛出
 * @inv token 只进入 Authorization 请求头，不进入日志/结果；每次调用独立建连，finally 必断开（无连接池）；
 *      选路复用 [TsnetDial.socketFactoryFor]，与 WS 读同一份 [TsnetWire.state]——LAN/域名/未 Up 恒直连
 */
class HttpUrlConnectionUploader : AttachmentUploader {

    /**
     * 无认证入口禁止发起请求：保留 SAM 契约兼容性，但让错误用法立即显式失败。
     *
     * @contract
     * @pre 无
     * @post 返回明确的未配置认证失败，且不建立 HTTP 连接
     * @err none
     * @inv 不允许无 token 请求静默退化为服务端 401
     */
    override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome =
        UploadOutcome.Failure("未配置上传认证")

    override fun upload(baseUrl: String, uploadToken: String?, attachment: Attachment): UploadOutcome {
        val endpoint = baseUrl.trimEnd('/') + "/upload"
        val boundary = "AgentMirrorBoundary${System.currentTimeMillis()}"
        val body = buildMultipartBody(boundary, attachment)

        val start = System.currentTimeMillis()
        // 缺陷观测点：上传目标地址、选路走了 SOCKS 还是直连、结果、耗时。
        // 选路在拨号前决定（sf==null → 直连），记录的是实际路径；结果在 finally 统一落。
        return try {
            // 与 WebSocket 同一选路（fix-upload-transport-tsnet）：仅 tailnet 段 host 且节点
            // Up 才经 tsnet loopback SOCKS5（[TsnetProxySocketFactory]，自实现握手），其余
            // （LAN/域名/未 Up）直连——读与 [OkHttpTransportFactory] 同一份 [TsnetWire.state]，
            // 避免两通道选路不一致。LAN 路径零行为变化。
            val host = runCatching { java.net.URI(endpoint).host }.getOrNull()
            val sf = TsnetDial.socketFactoryFor(TsnetWire.state, host)
            val viaSocks = sf != null
            DiagLog.record("upload", "attempt endpoint=$endpoint host=$host via=${if (viaSocks) "socks" else "direct"}")
            val outcome = if (sf == null) {
                uploadViaHttpUrlConnection(endpoint, boundary, body, uploadToken)
            } else {
                uploadViaOkHttp(endpoint, boundary, body, uploadToken, sf)
            }
            DiagLog.record(
                "upload",
                "result ${if (outcome is UploadOutcome.Success) "ok" else "fail"} " +
                    "via=${if (viaSocks) "socks" else "direct"} ms=${System.currentTimeMillis() - start} " +
                    "detail=${outcome.toString().take(200)}",
            )
            outcome
        } catch (e: Exception) {
            DiagLog.record("upload", "fail ex=${e.javaClass.simpleName} msg=${e.message?.take(200)}")
            UploadOutcome.Failure("上传失败：${e.message ?: "网络异常"}")
        }
    }

    /** 直连路径：JDK HttpURLConnection（LAN/域名/未 Up 时；保持既有行为与测试契约）。 */
    private fun uploadViaHttpUrlConnection(
        endpoint: String,
        boundary: String,
        body: ByteArray,
        uploadToken: String?,
    ): UploadOutcome {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Content-Length", body.size.toString())
            if (!uploadToken.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $uploadToken")
            }
            conn.outputStream.use { it.write(body) }
            // 非 2xx 时 getInputStream() 抛异常（JDK 契约），只能经 responseCode 判定；
            // 2xx 才读响应体。text 对非 2xx 无意义（readHttpResponse 先判 code）。
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                ""
            }
            return readHttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * SOCKS 路径：OkHttp + 与 WS 相同的 [TsnetProxySocketFactory]。一次性 client，
     * 零连接池（与直连路径语义一致），用完 shutdown 执行器 + evictAll 清连接池
     * （进程卫生，审查席 F1——JVM OkHttp 无 close()，此为正确释放）。Content-Type
     * 与直连路径同源（boundary 同串），D-22 Bearer 链保持不变。
     */
    private fun uploadViaOkHttp(
        endpoint: String,
        boundary: String,
        body: ByteArray,
        uploadToken: String?,
        sf: TsnetProxySocketFactory,
    ): UploadOutcome {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            // socketFactory 已自实现 SOCKS 隧道（socket.connect 拦截），显式 NO_PROXY 防
            // 系统 ProxySelector 叠加一跳。
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .socketFactory(sf)
            .build()
        try {
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
            if (!uploadToken.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $uploadToken")
            }
            val resp = client.newCall(requestBuilder.build()).execute()
            try {
                val text = resp.body?.string()
                return when {
                    resp.code !in 200..299 -> UploadOutcome.Failure("上传失败（HTTP ${resp.code}）")
                    text == null -> UploadOutcome.Failure("上传响应无法解析")
                    else -> readHttpResponse(resp.code, text)
                }
            } finally {
                resp.close()
            }
        } finally {
            // 审查席 F1 复核：JVM OkHttp 4.12.0 的 OkHttpClient 无 close()（Closeable 是
            // okhttp-kotlin 新 API，本依赖是 canonical JVM 版）。正确释放 = 停执行器 +
            // 清连接池；代码本就 shutdown 了执行器，补 evictAll() 清连接池（一次性 client
            // 用完即弃，ConnectionPool(0,1,ns) 下连接池本就空，但显式清是正确用法）。
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** 共享响应折叠：非 2xx / JSON 无 path / path 空 → 明确失败（两路径同一契约）。 */
    private fun readHttpResponse(code: Int, text: String): UploadOutcome {
        if (code !in 200..299) return UploadOutcome.Failure("上传失败（HTTP $code）")
        val dto = try {
            json.decodeFromString(UploadResponseDto.serializer(), text)
        } catch (e: Exception) {
            return UploadOutcome.Failure("上传响应无法解析")
        }
        if (dto.path.isEmpty()) return UploadOutcome.Failure("上传响应缺少路径")
        return UploadOutcome.Success(dto.path)
    }

    /** 组装 multipart/form-data 请求体（单文件段 + 结束边界）。 */
    private fun buildMultipartBody(boundary: String, attachment: Attachment): ByteArray {
        val out = ByteArrayOutputStream()
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${sanitize(attachment.name)}\"\r\n" +
            "Content-Type: ${attachment.mimeType}\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(attachment.bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /** 文件名中的 CR/LF 一律剥离（防止 multipart 头注入）。 */
    private fun sanitize(name: String): String =
        name.replace("\r", "").replace("\n", "")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val WRITE_TIMEOUT_MS = 30_000
    }
}
