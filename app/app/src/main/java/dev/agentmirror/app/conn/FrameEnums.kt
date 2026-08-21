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

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * error 帧的机器可读 code 闭集（docs/protocol.md §7.2）。
 *
 * 客户端 switch 该值决定恢复策略。按协议 §2 前向兼容原则，服务端未来可**增量新增**
 * error code；客户端收到未识别 code 字符串时必须容忍而非崩溃——因此解码时未知串
 * 经 [fromWire] 落回 [UNKNOWN]（一等公民本地回退，非错误）。
 */
@Serializable(with = ErrorCodeSerializer::class)
enum class ErrorCode(val wire: String) {
    /** 未认证即操作。 */
    UNAUTHORIZED("unauthorized"),

    /** 控制帧无法解析。 */
    BAD_FRAME("bad_frame"),

    /** 必填字段缺失或越界（与 bad_frame 解码失败分离，reason 带字段名）。 */
    INVALID_FIELD("invalid_field"),

    /** 版本不匹配（随后关闭）。 */
    UNSUPPORTED_VERSION("unsupported_version"),

    /** 未知帧类型。 */
    UNSUPPORTED_TYPE("unsupported_type"),

    /** ref 无对应存活会话。 */
    SESSION_NOT_FOUND("session_not_found"),

    /** 服务端内部错误。 */
    INTERNAL("internal"),

    /**
     * 未识别 code 的客户端解码回退值（协议正文无此值）。
     *
     * 仅用于本地解码回退，**永不上行发送**（编码路径拒绝携带该值，见
     * FramePayload.encode 的 ErrorFrame 守卫）；KDoc 纪律：不可与线上值混用。
     */
    UNKNOWN("unknown");

    companion object {
        /**
         * 按线上字符串解析；未识别值返回 [UNKNOWN]（前向兼容回退，不抛错）。
         *
         * @contract
         * @pre 无
         * @post 返回 entries 中 wire 等于 value 的成员；无匹配返回 [UNKNOWN]
         * @err 无（不抛异常；前向兼容容忍未知 code）
         * @inv 返回值恒为非 null；[UNKNOWN] 仅本地解码回退、永不上行
         */
        fun fromWire(value: String): ErrorCode =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * input_ack ok:false 时的机器可读 reason 闭集（docs/protocol.md §7.3）。
 *
 * reason 存在当且仅当 ok:false（一字段一义）；ok:true 时不得携带。
 * 解码遇未识别 reason 由序列化器直接抛 [FrameDecodeException] INVALID_FIELD
 * （闭集，未知值按坏帧处理）。
 */
@Serializable(with = InputFailReasonSerializer::class)
enum class InputFailReason(val wire: String) {
    /** 目标会话已不存在。 */
    SESSION_NOT_FOUND("session_not_found"),

    /** 未订阅该会话即注入。 */
    NOT_SUBSCRIBED("not_subscribed"),

    /** tmux send-keys 被拒。 */
    INJECT_FAILED("inject_failed"),

    /** 文本超服务端大小上限。 */
    TOO_LARGE("too_large"),

    /** 服务端内部错误。 */
    INTERNAL("internal");

    companion object {
        /**
         * 按线上字符串解析；未识别值返回 null（解码器据此判坏帧）。
         *
         * @contract
         * @pre 无
         * @post 返回 entries 中 wire 等于 value 的成员；无匹配返回 null
         * @err 无（不抛异常；未识别 reason 由解码器报 [FrameError.INVALID_FIELD]）
         * @inv 返回值为 null 或闭集成员
         */
        fun fromWire(value: String): InputFailReason? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * 从 wire 字符串反序列化闭集枚举的通用实现；未知值抛 [FrameDecodeException]。
 *
 * 用于语义上"闭集、未知即坏帧"的枚举（input_ack reason、input keys）。error code
 * 不走这里：它按 §2 前向兼容回退 [ErrorCode.UNKNOWN]。
 * package 内可见：Keys.kt 的 [InputKey] 序列化器复用同一实现（闭集外即坏帧，对齐 Go）。
 */
internal inline fun <reified E : Enum<E>> strictDeserialize(
    decoder: Decoder,
    error: FrameError,
    fromWire: (String) -> E?,
): E {
    val v = decoder.decodeString()
    return fromWire(v) ?: throw FrameDecodeException(
        error,
        "unknown ${E::class.simpleName} value: \"$v\"",
    )
}

/** error 帧 code 序列化器：按线上字符串；解码未识别值回退 [ErrorCode.UNKNOWN]。 */
internal object ErrorCodeSerializer : KSerializer<ErrorCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ErrorCode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ErrorCode) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): ErrorCode {
        // 前向兼容：未识别 code 字符串不抛错，落回 UNKNOWN（§2）。
        val v = decoder.decodeString()
        return ErrorCode.fromWire(v)
    }
}

/**
 * close_session_ack ok:false 时的机器可读 reason 闭集（契约 088 E12）。
 * reason 存在当且仅当 ok:false；ok:true 时不得携带。
 */
@Serializable(with = CloseFailReasonSerializer::class)
enum class CloseFailReason(val wire: String) {
    SESSION_NOT_FOUND("session_not_found"),
    CLOSE_FAILED("close_failed"),
    INTERNAL("internal");

    companion object {
        /**
         * @contract
         * @pre 无
         * @post 返回闭集成员或 null
         * @err 无
         * @inv 未知串返回 null，不回落
         */
        fun fromWire(value: String): CloseFailReason? =
            entries.firstOrNull { it.wire == value }
    }
}

/** input_ack reason 序列化器：按线上字符串；未知值抛错。 */
internal object InputFailReasonSerializer : KSerializer<InputFailReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InputFailReason", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: InputFailReason) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): InputFailReason =
        strictDeserialize(decoder, FrameError.INVALID_FIELD) { InputFailReason.fromWire(it) }
}

@Serializable(with = CreateFailReasonSerializer::class)
enum class CreateFailReason(val wire: String) {
    CWD_NOT_FOUND("cwd_not_found"),
    NO_TMUX_ANCHOR("no_tmux_anchor"),
    CREATE_FAILED("create_failed"),
    INTERNAL("internal");

    companion object {
        /**
         * @contract
         * @pre 无
         * @post 闭集成员或 null
         * @err 无
         * @inv 未知串返回 null
         */
        fun fromWire(value: String): CreateFailReason? =
            entries.firstOrNull { it.wire == value }
    }
}

internal object CloseFailReasonSerializer : KSerializer<CloseFailReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CloseFailReason", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CloseFailReason) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): CloseFailReason =
        strictDeserialize(decoder, FrameError.INVALID_FIELD) { CloseFailReason.fromWire(it) }
}

internal object CreateFailReasonSerializer : KSerializer<CreateFailReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CreateFailReason", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CreateFailReason) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): CreateFailReason =
        strictDeserialize(decoder, FrameError.INVALID_FIELD) { CreateFailReason.fromWire(it) }
}
