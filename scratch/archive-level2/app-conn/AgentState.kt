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
 * 会话生命周期状态（四值闭集，docs/protocol.md §7.1）。
 *
 * 状态只出现在控制帧（listing / list_delta），永不进入二进制镜像通道；
 * 状态层与镜像层严格解耦（008 隔离铁律）：状态判不出不影响镜像与输入。
 *
 * 2026-08-15（用户裁定「去除完成这个状态，就是工作和空闲」，058）：完成态已删。
 * 服务端不再产完成；App 侧同步移除对应枚举。完成语义由 idle + unread 派生，
 * 不在状态值域内。
 *
 * - [UNKNOWN] 是一等公民线上值（wire "unknown"），不是错误：适配器判不出状态时服务端
 *   下发它，客户端解码它，均不报错、不阻断镜像与输入。
 * - 状态**字符串不在闭集内**是坏帧：编解码两侧都拒绝（对齐 Go 参考实现
 *   ErrInvalidState / TestUnmarshalRedPaths"session bad state"），防止协议漂移。
 *   §7.1 的"解析失败降级为 unknown"指状态**值域语义**（适配器判不出时的服务端产出），
 *   不是指放行非法线串。
 */
@Serializable(with = AgentStateSerializer::class)
enum class AgentState(val wire: String) {
    /** 正在产出输出。 */
    WORKING("working"),

    /** 在场但当前无动作。 */
    IDLE("idle"),

    /** 等待输入（如提示符），需要人。 */
    BLOCKED("blocked"),

    /** 一等公民兜底值；不参与聚合、不阻断镜像与输入。 */
    UNKNOWN("unknown");

    companion object {
        /**
         * 按线上字符串解析；未识别值返回 null（解码器据此判坏帧）。
         *
         * @contract
         * @pre 无
         * @post 返回 entries 中 wire 等于 value 的成员；无匹配返回 null
         * @err 无（不抛异常；未识别值由调用方决定判坏帧）
         * @inv 返回值为 null 或闭集成员，绝不返回非闭集构造
         */
        fun fromWire(value: String): AgentState? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * 状态序列化器：枚举按线上 wire 字符串编解码（默认按 Kotlin 名，错）。
 * 未知线串抛 [FrameDecodeException] INVALID_STATE（闭集外即坏帧）。
 */
internal object AgentStateSerializer : KSerializer<AgentState> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AgentState", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AgentState) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): AgentState {
        val v = decoder.decodeString()
        return AgentState.fromWire(v) ?: throw FrameDecodeException(
            FrameError.INVALID_STATE,
            "unknown AgentState value: \"$v\"",
        )
    }
}
