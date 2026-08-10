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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 服务端上传回复（协议 §8：`{"path": "/绝对/路径"}`，HTTP 响应体而非 WS 帧）。 */
@Serializable
internal data class UploadResponseDto(
    @SerialName("path") val path: String,
)

/**
 * 生产附件上传器（协议 §8 multipart）：`POST {baseUrl}/upload` → 主机绝对路径。
 *
 * 用 JDK [HttpURLConnection] 实现（:app 当前零 OkHttp 依赖；上传为一次性短连接，
 * 不做连接池）。multipart 字段名取 "file"；服务端按携带 filename 的首个 part 取文件，
 * 不校验字段名（见 server/internal/api/upload.go findFilePart）。响应非 2xx /
 * JSON 无 path / 网络异常 ⇒ 明确失败，绝不静默（003 静默失效猎杀）。
 *
 * @contract
 * @pre baseUrl 非空 http(s) 基地址；attachment.bytes 非空
 * @post 成功返回 [UploadOutcome.Success]（主机绝对路径）；失败返回 [UploadOutcome.Failure]（人类可读原因）
 * @err 网络/IO 异常、非 2xx 响应、JSON 无 path 或 path 为空，全部折叠为 [UploadOutcome.Failure]，不抛出
 * @inv 每次调用独立建连，finally 必断开（无连接池）
 */
class HttpUrlConnectionUploader : AttachmentUploader {

    override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome {
        val endpoint = baseUrl.trimEnd('/') + "/upload"
        val boundary = "AgentMirrorBoundary${System.currentTimeMillis()}"
        val body = buildMultipartBody(boundary, attachment)

        return try {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setRequestProperty("Content-Length", body.size.toString())
                conn.outputStream.use { it.write(body) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    UploadOutcome.Failure("上传失败（HTTP $code）")
                } else {
                    val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val dto = try {
                        json.decodeFromString(UploadResponseDto.serializer(), text)
                    } catch (e: Exception) {
                        return UploadOutcome.Failure("上传响应无法解析")
                    }
                    if (dto.path.isEmpty()) UploadOutcome.Failure("上传响应缺少路径")
                    else UploadOutcome.Success(dto.path)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            UploadOutcome.Failure("上传失败：${e.message ?: "网络异常"}")
        }
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
    }
}
