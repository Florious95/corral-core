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
 * 控制帧载荷的密封基类：携带 type 判别符与必填字段校验。
 *
 * 编解码入口在伴生对象 [decode]/[encode]：按线上 "type" 判别符派发到具体帧类型，
 * 解码后再统一校验（与 Go 参考实现 MarshalFrame/UnmarshalFrame 语义对齐——
 * 校验在编码前与解码后各执行一次，无效帧不跨线）。
 */
sealed interface FramePayload {
    /** 线上 "type" 判别符（docs/protocol.md §4.1）。 */
    val frameType: String

    /** 必填字段完整性校验；返回非空 = 校验失败原因。 */
    fun validate(): String? = null

    companion object {
        /**
         * 按 "type" 判别符把信封 payload 解析成具体帧。
         *
         * - payload 省略或为 null ⇒ 按零值校验（缺必填字段即错误，契约 §4.1）。
         * - 缺必填字段 ⇒ [FrameError.INVALID_FIELD]；结构无法解析 ⇒ [FrameError.BAD_FRAME]。
         * - 未知 type ⇒ [FrameError.UNSUPPORTED_TYPE]。
         */
        fun decode(type: String, element: kotlinx.serialization.json.JsonElement?): FramePayload {
            val el = element ?: kotlinx.serialization.json.JsonObject(emptyMap())
            val frame: FramePayload = try {
                when (type) {
                    FrameType.AUTH -> json.decodeFromJsonElement(AuthFrame.serializer(), el)
                    FrameType.AUTH_ACK -> json.decodeFromJsonElement(AuthAckFrame.serializer(), el)
                    FrameType.LIST -> json.decodeFromJsonElement(ListFrame.serializer(), el)
                    FrameType.LISTING -> json.decodeFromJsonElement(ListingFrame.serializer(), el)
                    FrameType.LIST_DELTA -> json.decodeFromJsonElement(ListDeltaFrame.serializer(), el)
                    FrameType.SUBSCRIBE -> json.decodeFromJsonElement(SubscribeFrame.serializer(), el)
                    FrameType.UNSUBSCRIBE -> json.decodeFromJsonElement(UnsubscribeFrame.serializer(), el)
                    FrameType.INPUT -> json.decodeFromJsonElement(InputFrame.serializer(), el)
                    FrameType.INPUT_ACK -> json.decodeFromJsonElement(InputAckFrame.serializer(), el)
                    FrameType.SCROLLBACK -> json.decodeFromJsonElement(ScrollbackFrame.serializer(), el)
                    FrameType.RESIZE -> json.decodeFromJsonElement(ResizeFrame.serializer(), el)
                    FrameType.ERROR -> json.decodeFromJsonElement(ErrorFrame.serializer(), el)
                    else -> throw FrameDecodeException(
                        FrameError.UNSUPPORTED_TYPE,
                        "unknown frame type: $type",
                    )
                }
            } catch (e: kotlinx.serialization.MissingFieldException) {
                // 缺必填字段：Go 参考实现给 ErrInvalidField，此处对齐。
                throw FrameDecodeException(
                    FrameError.INVALID_FIELD,
                    "missing required field: ${e.missingFields}",
                )
            } catch (e: kotlinx.serialization.SerializationException) {
                // payload 结构与声明类型不符（类型错、结构坏）。
                throw FrameDecodeException(FrameError.BAD_FRAME, e.message ?: "malformed payload")
            }
            frame.validate()?.let {
                throw FrameDecodeException(FrameError.INVALID_FIELD, it)
            }
            return frame
        }

        /** 把具体帧编码回 payload 的 JsonElement（不包含信封；缺省字段按零值省略）。 */
        fun encode(frame: FramePayload): kotlinx.serialization.json.JsonElement {
            frame.validate()?.let {
                throw FrameEncodeException(FrameError.INVALID_FIELD, it)
            }
            return when (frame) {
                is AuthFrame -> json.encodeToJsonElement(AuthFrame.serializer(), frame)
                is AuthAckFrame -> json.encodeToJsonElement(AuthAckFrame.serializer(), frame)
                is ListFrame -> json.encodeToJsonElement(ListFrame.serializer(), frame)
                is ListingFrame -> json.encodeToJsonElement(ListingFrame.serializer(), frame)
                is ListDeltaFrame -> json.encodeToJsonElement(ListDeltaFrame.serializer(), frame)
                is SubscribeFrame -> json.encodeToJsonElement(SubscribeFrame.serializer(), frame)
                is UnsubscribeFrame -> json.encodeToJsonElement(UnsubscribeFrame.serializer(), frame)
                is InputFrame -> json.encodeToJsonElement(InputFrame.serializer(), frame)
                is InputAckFrame -> json.encodeToJsonElement(InputAckFrame.serializer(), frame)
                is ScrollbackFrame -> json.encodeToJsonElement(ScrollbackFrame.serializer(), frame)
                is ResizeFrame -> json.encodeToJsonElement(ResizeFrame.serializer(), frame)
                is ErrorFrame -> {
                    // ErrorCode.UNKNOWN 仅作客户端解码回退，永不上行（§7.2 + 本地纪律）。
                    if (frame.code == ErrorCode.UNKNOWN) {
                        throw FrameEncodeException(
                            FrameError.INVALID_FIELD,
                            "error code UNKNOWN is decode-fallback only, never sent upstream",
                        )
                    }
                    json.encodeToJsonElement(ErrorFrame.serializer(), frame)
                }
            }
        }
    }
}

/** 配对握手 C→S（token 一次性上行，任何回复不回显）。 */
@Serializable
data class AuthFrame(
    @SerialName("token") val token: String,
) : FramePayload {
    override val frameType: String get() = FrameType.AUTH
    override fun validate(): String? =
        if (token.isEmpty()) "auth token must be non-empty" else null
}

/** 握手裁决 S→C：ok:false 必须带 reason（随后关闭连接）。 */
@Serializable
data class AuthAckFrame(
    @SerialName("ok") val ok: Boolean,
    @SerialName("reason") val reason: String = "",
) : FramePayload {
    override val frameType: String get() = FrameType.AUTH_ACK
    override fun validate(): String? = when {
        !ok && reason.isEmpty() -> "rejected auth_ack must carry a reason"
        ok && reason.isNotEmpty() -> "accepted auth_ack must not carry a reason"
        else -> null
    }
}

/** 全量列表请求 C→S（req_id >= 1）。 */
@Serializable
data class ListFrame(
    @SerialName("req_id") val reqId: Long,
) : FramePayload {
    override val frameType: String get() = FrameType.LIST
    override fun validate(): String? = if (reqId <= 0) "list req_id must be >= 1" else null
}

/**
 * 一级分组（按 cwd 聚合）与二级会话条目。aggregate_state 由服务端权威计算，
 * 客户端只渲染不重算。delta 的 changed_workspaces 中 sessions 可省略。
 */
@Serializable
data class Workspace(
    @SerialName("cwd") val cwd: String,
    @SerialName("session_count") val sessionCount: Int,
    @SerialName("aggregate_state") val aggregateState: AgentState,
    @SerialName("sessions") val sessions: List<Session> = emptyList(),
)

/** 单个被镜像的 Agent CLI 面板；ref 是寻址键，name 是展示标签（可重名）。 */
@Serializable
data class Session(
    @SerialName("ref") val ref: String,
    @SerialName("name") val name: String,
    @SerialName("cwd") val cwd: String,
    @SerialName("state") val state: AgentState,
    @SerialName("rows") val rows: Int,
    @SerialName("cols") val cols: Int,
)

/** 全量列表回复 S→C（req_id 对应 list；seq 单调递增）。 */
@Serializable
data class ListingFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("seq") val seq: Long,
    @SerialName("workspaces") val workspaces: List<Workspace>,
) : FramePayload {
    override val frameType: String get() = FrameType.LISTING
    override fun validate(): String? = when {
        reqId <= 0 -> "listing req_id must be >= 1"
        seq <= 0 -> "listing seq must be >= 1"
        else -> null
    }
}

/**
 * 列表增量推送 S→C（主动，无轮询）。四组字段两两不相交；seq 单调递增。
 * seq 不连续于上次见过的值 ⇒ 客户端必须重新 list 拉全量（无状态恢复）。
 */
@Serializable
data class ListDeltaFrame(
    @SerialName("seq") val seq: Long,
    @SerialName("added_sessions") val addedSessions: List<Session> = emptyList(),
    @SerialName("removed_refs") val removedRefs: List<String> = emptyList(),
    @SerialName("changed_sessions") val changedSessions: List<Session> = emptyList(),
    @SerialName("changed_workspaces") val changedWorkspaces: List<Workspace> = emptyList(),
) : FramePayload {
    override val frameType: String get() = FrameType.LIST_DELTA
    override fun validate(): String? = if (seq <= 0) "list_delta seq must be >= 1" else null
}

/** 订阅会话镜像 C→S（成功后 S 先发 snapshot 再流 delta）。 */
@Serializable
data class SubscribeFrame(
    @SerialName("ref") val ref: String,
    @SerialName("rows") val rows: Int,
    @SerialName("cols") val cols: Int,
) : FramePayload {
    override val frameType: String get() = FrameType.SUBSCRIBE
    override fun validate(): String? = when {
        ref.isEmpty() -> "subscribe ref must be non-empty"
        rows <= 0 || cols <= 0 -> "subscribe rows/cols must be >= 1"
        else -> null
    }
}

/** 停止镜像 C→S（幂等；连接关闭即全部退订）。 */
@Serializable
data class UnsubscribeFrame(
    @SerialName("ref") val ref: String,
) : FramePayload {
    override val frameType: String get() = FrameType.UNSUBSCRIBE
    override fun validate(): String? = if (ref.isEmpty()) "unsubscribe ref must be non-empty" else null
}

/**
 * 整条文本注入 C→S（send-keys 语义，非逐键）；text 为空 = 仅回车，允许。
 *
 * keys（R-1 快捷键条，017 裁定，可选字段前向兼容增量不 bump 版本）：携带时发送命名
 * 特殊键且**不附加回车**（快捷键条语义 = 按一下那个键）。text 与 keys 一帧至多其一，
 * 两者都有判协议错误（契约 §4.2）；两者皆无 = 仅回车（既有语义不变）。
 */
@Serializable
data class InputFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("ref") val ref: String,
    @SerialName("text") val text: String = "",
    @SerialName("keys") val keys: List<InputKey> = emptyList(),
) : FramePayload {
    override val frameType: String get() = FrameType.INPUT
    override fun validate(): String? = when {
        reqId <= 0 -> "input req_id must be >= 1"
        ref.isEmpty() -> "input ref must be non-empty"
        // 契约 §4.2：text 与 keys 互斥，一帧至多其一（对齐 Go validate.go）。
        text.isNotEmpty() && keys.isNotEmpty() ->
            "input carries both text and keys; at most one is allowed"
        else -> null
    }
}

/**
 * 注入回执 S→C（必达）。ok:true 表示字节已进面板；ok:false 必须带 reason。
 * reason 存在当且仅当 ok:false（一字段一义）。
 */
@Serializable
data class InputAckFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("ok") val ok: Boolean,
    @SerialName("reason") val reason: InputFailReason? = null,
) : FramePayload {
    override val frameType: String get() = FrameType.INPUT_ACK
    override fun validate(): String? = when {
        reqId <= 0 -> "input_ack req_id must be >= 1"
        !ok && reason == null -> "failed input_ack must carry a reason"
        ok && reason != null -> "accepted input_ack must not carry a reason"
        else -> null
    }
}

/** 按行区间拉历史 C→S（from_line 按 tmux capture-pane 语义；count >= 1）。 */
@Serializable
data class ScrollbackFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("ref") val ref: String,
    @SerialName("from_line") val fromLine: Int,
    @SerialName("count") val count: Long,
) : FramePayload {
    override val frameType: String get() = FrameType.SCROLLBACK
    override fun validate(): String? = when {
        reqId <= 0 -> "scrollback req_id must be >= 1"
        ref.isEmpty() -> "scrollback ref must be non-empty"
        count <= 0 -> "scrollback count must be >= 1"
        else -> null
    }
}

/** 上报手机行列数 C→S（只作用于已订阅会话）。 */
@Serializable
data class ResizeFrame(
    @SerialName("ref") val ref: String,
    @SerialName("rows") val rows: Int,
    @SerialName("cols") val cols: Int,
) : FramePayload {
    override val frameType: String get() = FrameType.RESIZE
    override fun validate(): String? = when {
        ref.isEmpty() -> "resize ref must be non-empty"
        rows <= 0 || cols <= 0 -> "resize rows/cols must be >= 1"
        else -> null
    }
}

/** 协议级错误 S→C（坏帧/未知类型/缺会话/版本不支持/内部错误）。 */
@Serializable
data class ErrorFrame(
    @SerialName("code") val code: ErrorCode,
    @SerialName("reason") val reason: String = "",
) : FramePayload {
    override val frameType: String get() = FrameType.ERROR
}
