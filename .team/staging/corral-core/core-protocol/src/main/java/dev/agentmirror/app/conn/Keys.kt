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
 * input 帧 keys 命名键闭集（docs/protocol.md §4.2 input.keys，R-1 快捷键条 017 裁定；
 * backspace 由直通输入 059 加入）。
 *
 * 闭集八键：esc / ctrl_c / tab / up / down / left / right / backspace（对应 tmux 命名键
 * Escape / C-c / Tab / Up / Down / Left / Right / BSpace——Claude Code 日常硬依赖 +
 * 删除键直通）。新增键须 bump 协议版本；与 Go 参考实现 keys.go IsValid() 对齐，未识别键
 * 按坏帧拒绝（闭集外即错，防协议漂移）。
 *
 * 使用约束（契约 §4.2，InputFrame.validate 兜底）：
 * - text 与 keys 一帧至多其一（互斥，两者都有判协议错误）；
 * - keys 注入**不附加回车**（快捷键条语义 = 按一下那个键）。
 */
@Serializable(with = InputKeySerializer::class)
enum class InputKey(val wire: String) {
    /** Escape（中断 agent 当前步骤）。 */
    ESC("esc"),

    /** Ctrl-C（SIGINT）。 */
    CTRL_C("ctrl_c"),

    /** Tab（补全）。 */
    TAB("tab"),

    /** ↑（历史 / 菜单选择）。 */
    UP("up"),

    /** ↓（历史 / 菜单选择）。 */
    DOWN("down"),

    /** ←（菜单选择）。 */
    LEFT("left"),

    /** →（菜单选择）。 */
    RIGHT("right"),

    /** 删除键（直通输入 059：虚拟键盘删除键同样直通 CLI，不经 App 本地消费）。 */
    BACKSPACE("backspace");

    companion object {
        /**
         * 按线上字符串解析；未识别值返回 null（解码器据此判坏帧）。
         *
         * @contract
         * @pre 无
         * @post 返回 entries 中 wire 等于 value 的成员；无匹配返回 null
         * @err 无（不抛异常；未识别键由解码器报 [FrameError.INVALID_FIELD]）
         * @inv 返回值为 null 或闭集七键成员
         */
        fun fromWire(value: String): InputKey? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * input keys 序列化器：按线上 wire 字符串编解码（默认按 Kotlin 名，错）。
 * keys 是闭集（新增键须 bump 版本）⇒ 未知键抛 [FrameDecodeException] INVALID_FIELD，
 * 对齐 Go ErrInvalidField（json_test.go "input unknown key"）。
 */
internal object InputKeySerializer : KSerializer<InputKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InputKey", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: InputKey) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): InputKey =
        strictDeserialize(decoder, FrameError.INVALID_FIELD) { InputKey.fromWire(it) }
}
