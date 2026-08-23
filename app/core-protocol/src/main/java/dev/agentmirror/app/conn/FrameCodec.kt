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

package dev.agentmirror.app.conn

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 协议版本常量（docs/protocol.md §2）。
 *
 * 当前 v = 1：每个 JSON 控制帧的 "v" 字段、每个二进制流帧的版本字节必须等于它。
 * 版本不匹配 ⇒ S 发 error(unsupported_version) 后关闭；本地解码则抛 [FrameDecodeException]。
 */
object ProtocolVersion {
    const val VALUE = 1
    const val BINARY = 1
    const val MAGIC = "RA"
    const val MAX_BINARY_PAYLOAD = 1 shl 20 // 1 MiB
    /** 与服务端 max-input-bytes 缺省一致：一帧 Input.bytes / text 上限 1 MiB。 */
    const val MAX_INPUT_BYTES = 1 shl 20
    const val MAX_REF_LEN = 255
}

/**
 * 控制帧编解码：一个 text WebSocket 消息 = 一个 JSON 信封（docs/protocol.md §4）。
 *
 * 编码：`{"v":1,"type":<判别符>,"payload":{...}}`，编码前先校验（无效帧不跨线）。
 * 解码：先验 "v"（缺/不匹配即错），再按 "type" 派发到具体帧并校验；未知信封/字段
 * 被忽略（向前兼容），未知 "type" 是错误。payload 省略或 null ⇒ 按零值校验。
 *
 * 返回解析后的 [FramePayload]，由上层按具体类型消费。
 */
object FrameCodec {
    /**
     * 编码一个控制帧为完整的 JSON 文本消息（WS text 载荷）。
     * 校验失败抛 [FrameEncodeException]（无效帧不跨线）。
     *
     * @contract
     * @pre frame 非空且 validate() 返回 null（无效帧不跨线）
     * @post 返回含 "v"=ProtocolVersion.VALUE、type=frame.frameType、payload 为 JSON 编码正文的完整信封
     * @err frame.validate() 失败抛 [FrameEncodeException]（INVALID_FIELD）；ErrorFrame 携带
     *      [ErrorCode.UNKNOWN]（解码回退值）编码必拒（INVALID_FIELD）
     * @inv 纯函数，无外部副作用；输出 "type" 与 frame.frameType 一致
     */
    fun encode(frame: FramePayload): String {
        val payloadEl: JsonElement = FramePayload.encode(frame) // 内含 validate
        val env = JsonObject(
            mapOf(
                "v" to JsonPrimitive(ProtocolVersion.VALUE),
                "type" to JsonPrimitive(frame.frameType),
                "payload" to payloadEl,
            ),
        )
        return env.toString()
    }

    /**
     * 解码一条 JSON 文本消息为具体控制帧。
     *
     * @contract
     * @pre text 是一个完整的 JSON 控制帧消息
     * @post 返回的 [FramePayload] 的 frameType 等于 wire "type" 且 validate() 已通过；出错时抛异常
     * @err 分类码见 [FrameError]：
     *   - 缺 "v" ⇒ MISSING_VERSION；非当前版本 ⇒ UNSUPPORTED_VERSION
     *   - JSON 无法解析 / 信封非对象 ⇒ BAD_FRAME
     *   - 缺 / 空 "type" ⇒ INVALID_FIELD；未知 "type" ⇒ UNSUPPORTED_TYPE
     *   - payload 结构无法按声明类型解析 ⇒ BAD_FRAME；必填字段缺失 / 校验失败 ⇒ INVALID_FIELD
     * @inv 纯函数，无外部副作用；未知信封/字段被忽略（向前兼容），未知 "type" 是错误
     */
    fun decode(text: String): FramePayload {
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw FrameDecodeException(FrameError.BAD_FRAME, "malformed json: ${e.message}")
        }
        val obj = root as? JsonObject
            ?: throw FrameDecodeException(FrameError.BAD_FRAME, "envelope must be a JSON object")

        // 版本：缺 = MISSING_VERSION，非整数 = BAD_FRAME，不匹配 = UNSUPPORTED_VERSION。
        val vRaw = obj["v"] ?: throw FrameDecodeException(FrameError.MISSING_VERSION, "missing protocol version")
        val v = (vRaw as? JsonPrimitive)?.intOrNull
            ?: throw FrameDecodeException(FrameError.BAD_FRAME, "protocol version must be an integer")
        if (v != ProtocolVersion.VALUE) {
            throw FrameDecodeException(
                FrameError.UNSUPPORTED_VERSION,
                "unsupported protocol version: $v",
            )
        }

        // type：缺 / 空 = INVALID_FIELD，未知 = UNSUPPORTED_TYPE（由 FramePayload.decode 抛）。
        val typeRaw = obj["type"]
        val type = (typeRaw as? JsonPrimitive)?.content ?: ""
        if (type.isEmpty()) {
            throw FrameDecodeException(FrameError.INVALID_FIELD, "empty frame type")
        }

        // payload：省略或 null ⇒ null，按零值校验。
        val payloadEl = obj["payload"]?.takeIf { it !is JsonNull }
        return FramePayload.decode(type, payloadEl)
    }
}
