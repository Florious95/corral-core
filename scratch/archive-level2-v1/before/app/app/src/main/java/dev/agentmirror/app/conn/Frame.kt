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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 控制帧：一个 JSON 对象（docs/protocol.md §4）。
 *
 * 信封 [v] + [type] 判别符 + 类型专用 [payload]。payload 不得包含终端字节
 * ——终端字节只走二进制流帧。未知 type 是错误；未知信封/字段被忽略（向前兼容）。
 *
 * payload 序列化为 JsonElement 后按 [type] 派发到具体帧类型，见 [FrameCodec]。
 * payload 省略时按零值校验（缺必填字段即错误）。
 */
@Serializable
internal data class Envelope(
    @SerialName("v") val version: Int,
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: kotlinx.serialization.json.JsonElement? = null,
)

/** 控制帧 type 判别符常量（docs/protocol.md §4.1）。 */
internal object FrameType {
    const val AUTH = "auth"
    const val AUTH_ACK = "auth_ack"
    const val LIST = "list"
    const val LISTING = "listing"
    const val LIST_DELTA = "list_delta"
    const val SUBSCRIBE = "subscribe"
    const val UNSUBSCRIBE = "unsubscribe"
    const val INPUT = "input"
    const val INPUT_ACK = "input_ack"
    const val SCROLLBACK = "scrollback"
    const val RESIZE = "resize"
    const val ERROR = "error"
    const val SCROLL_WHEEL = "scroll_wheel"
    const val ATTACH_PREVIEW = "attach_preview"
    const val PANE_MODE_CHANGED = "pane_mode_changed"
}
