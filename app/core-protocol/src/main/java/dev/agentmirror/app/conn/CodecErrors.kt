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

import kotlinx.serialization.json.Json

/**
 * 协议编解码用共享 Json 实例。
 *
 * - [ignoreUnknownKeys] = true 是实现契约前向兼容的必要配置：信封与 payload 中
 *   的未知字段必须被忽略（docs/protocol.md §4.1），旧客户端必须能存活于新服务端。
 * - 不做 primitive coercion：类型不匹配的字段是坏帧（对齐 Go ErrBadPayload），
 *   不能静默降级；缺必填字段（无默认值）抛 MissingFieldException ⇒ INVALID_FIELD，
 *   带默认值的可选字段落默认值后由各帧 validate() 兜底（对齐 Go 零值 + Validate 两步）。
 * - [encodeDefaults] = false：与默认值相同的字段不序列化（reason 空串等缺省即省略）。
 */
val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/**
 * 帧编解码失败分类（控制帧与二进制流帧共用；本地编解码错误，非线上下发错误帧）。
 *
 * 语义对齐 Go 参考实现的哨兵错误（server/internal/protocol/errors.go），
 * 供上层决定恢复策略与日志级别。
 */
enum class FrameError {
    /** 帧缺 "v" 字段。 */
    MISSING_VERSION,

    /** "v" 字段（或二进制版本字节）不是当前支持版本。 */
    UNSUPPORTED_VERSION,

    /** 未知帧 type 判别符。 */
    UNSUPPORTED_TYPE,

    /** payload 结构无法按声明类型解析。 */
    BAD_FRAME,

    /** 必填字段缺失或值出闭集。 */
    INVALID_FIELD,

    /** 状态字符串不是五值闭集成员。 */
    INVALID_STATE,

    /** 二进制帧未以 magic "RA" 开头。 */
    BAD_MAGIC,

    /** 二进制帧 kind 字节不在闭集内。 */
    UNKNOWN_KIND,

    /** 二进制帧在声明头完成前截断。 */
    TRUNCATED,

    /** 会话 ref 为空。 */
    INVALID_REF,

    /** 会话 ref 超过 255 字节。 */
    REF_TOO_LONG,
}

/** 本地解码失败：携带分类码与人类可读原因。 */
class FrameDecodeException(
    val code: FrameError,
    message: String,
) : Exception(message)

/** 本地编码失败：校验不过（无效帧不跨线）。 */
class FrameEncodeException(
    val code: FrameError,
    message: String,
) : Exception(message)
