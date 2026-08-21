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
                    FrameType.SCROLL_WHEEL -> json.decodeFromJsonElement(ScrollWheelFrame.serializer(), el)
                    FrameType.ATTACH_PREVIEW -> json.decodeFromJsonElement(AttachPreviewFrame.serializer(), el)
                    FrameType.PANE_MODE_CHANGED -> json.decodeFromJsonElement(PaneModeChangedFrame.serializer(), el)
                    FrameType.LEVEL2_FRAME -> json.decodeFromJsonElement(Level2Frame.serializer(), el)
                    FrameType.LEVEL2_HEARTBEAT -> json.decodeFromJsonElement(Level2HeartbeatFrame.serializer(), el)
                    FrameType.LEVEL2_SUBSCRIBE -> json.decodeFromJsonElement(Level2SubscribeFrame.serializer(), el)
                    FrameType.LEVEL2_UNSUBSCRIBE -> json.decodeFromJsonElement(Level2UnsubscribeFrame.serializer(), el)
                    FrameType.OVERLAY_FRAME -> json.decodeFromJsonElement(OverlayFrame.serializer(), el)
                    FrameType.OVERLAY_SUBSCRIBE -> json.decodeFromJsonElement(OverlaySubscribeFrame.serializer(), el)
                    FrameType.OVERLAY_UNSUBSCRIBE -> json.decodeFromJsonElement(OverlayUnsubscribeFrame.serializer(), el)
                    FrameType.CLOSE_SESSION -> json.decodeFromJsonElement(CloseSessionFrame.serializer(), el)
                    FrameType.CLOSE_SESSION_ACK -> json.decodeFromJsonElement(CloseSessionAckFrame.serializer(), el)
                    FrameType.CREATE_SESSION -> json.decodeFromJsonElement(CreateSessionFrame.serializer(), el)
                    FrameType.CREATE_SESSION_ACK -> json.decodeFromJsonElement(CreateSessionAckFrame.serializer(), el)
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
                is ScrollWheelFrame -> json.encodeToJsonElement(ScrollWheelFrame.serializer(), frame)
                is AttachPreviewFrame -> json.encodeToJsonElement(AttachPreviewFrame.serializer(), frame)
                // PaneModeChangedFrame is S→C only; client never encodes it upstream.
                is PaneModeChangedFrame -> throw FrameEncodeException(
                    FrameError.INVALID_FIELD,
                    "pane_mode_changed is server-to-client only, never sent upstream",
                )
                is Level2Frame -> throw FrameEncodeException(
                    FrameError.INVALID_FIELD,
                    "level2_frame is server-to-client only, never sent upstream",
                )
                is Level2HeartbeatFrame -> throw FrameEncodeException(
                    FrameError.INVALID_FIELD,
                    "level2_heartbeat is server-to-client only, never sent upstream",
                )
                is Level2SubscribeFrame -> json.encodeToJsonElement(Level2SubscribeFrame.serializer(), frame)
                is Level2UnsubscribeFrame -> json.encodeToJsonElement(Level2UnsubscribeFrame.serializer(), frame)
                is OverlayFrame -> throw FrameEncodeException(
                    FrameError.INVALID_FIELD,
                    "overlay_frame is server-to-client only, never sent upstream",
                )
                is OverlaySubscribeFrame -> json.encodeToJsonElement(OverlaySubscribeFrame.serializer(), frame)
                is OverlayUnsubscribeFrame -> json.encodeToJsonElement(OverlayUnsubscribeFrame.serializer(), frame)
                is CloseSessionFrame -> json.encodeToJsonElement(CloseSessionFrame.serializer(), frame)
                is CloseSessionAckFrame -> json.encodeToJsonElement(CloseSessionAckFrame.serializer(), frame)
                is CreateSessionFrame -> json.encodeToJsonElement(CreateSessionFrame.serializer(), frame)
                is CreateSessionAckFrame -> json.encodeToJsonElement(CreateSessionAckFrame.serializer(), frame)
            }
        }
    }
}

/**
 * 配对握手 C→S（token 一次性上行，任何回复不回显）。
 *
 * @contract
 * @pre token 非空
 * @post 服务端凭 token 判定身份后回 AuthAckFrame；token 不出现在任何回执里
 * @err validate() 对空 token 返回非空原因（编码时抛 [FrameEncodeException]）
 * @inv token 绝不被记录或回显
 */
@Serializable
data class AuthFrame(
    @SerialName("token") val token: String,
) : FramePayload {
    override val frameType: String get() = FrameType.AUTH
    override fun validate(): String? =
        if (token.isEmpty()) "auth token must be non-empty" else null

    /**
     * 安全 toString（前置任务①，w-diag-rev 对抗预审发现）：data class 默认 toString 会把
     * token 明文吐进日志/崩溃 trace。覆盖为占位，token 值绝不出现在字符串表示里。
     * 序列化仍走 [AuthFrame.serializer]（@Serializable 不受 toString 影响）。
     */
    override fun toString(): String = "AuthFrame(token=[redacted])"
}

/**
 * 握手裁决 S→C：ok:false 必须带 reason（随后关闭连接）。
 *
 * @contract
 * @pre 无
 * @post ok:false 时 reason 非空；ok:true 时 reason 为空
 * @err validate() 对 ok/reason 矛盾组合返回非空原因
 * @inv reason 存在当且仅当 ok=false（一字段一义）
 */
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

/**
 * 全量列表请求 C→S（req_id >= 1）。
 *
 * @contract
 * @pre reqId ≥ 1（0 与"未设置"不可区分）
 * @post 服务端以 reqId 对应回复 ListingFrame
 * @err validate() 对 reqId ≤ 0 返回非空原因
 * @inv reqId 由调用方单调递增
 */
@Serializable
data class ListFrame(
    @SerialName("req_id") val reqId: Long,
) : FramePayload {
    override val frameType: String get() = FrameType.LIST
    override fun validate(): String? = if (reqId <= 0) "list req_id must be >= 1" else null
}

/**
 * 一级分组（按 cwd 聚合）与二级会话条目。delta 的 changed_workspaces 中 sessions
 * 可省略。
 *
 * 060 uproot（2026-08-15）：会话状态相关字段随状态判定整体拔除。
 */
@Serializable
data class Workspace(
    @SerialName("cwd") val cwd: String,
    @SerialName("session_count") val sessionCount: Int,
    @SerialName("sessions") val sessions: List<Session> = emptyList(),
)

/** 单个被镜像的 Agent CLI 面板；ref 是寻址键，name 是展示标签（可重名）。 */
@Serializable
data class Session(
    @SerialName("ref") val ref: String,
    @SerialName("name") val name: String,
    @SerialName("cwd") val cwd: String,
    @SerialName("rows") val rows: Int,
    @SerialName("cols") val cols: Int,
    /** pane_title 原样。App 禁止从本字段抠状态或身份。一级 listing 可缺省。 */
    @SerialName("title") val title: String = "",
    /** 只许 working / idle / unknown。缺省或乱值一律当 unknown，不得回落 idle。 */
    @SerialName("status") val status: String = "",
    /** tmux session_name 结构字段；缺省空。跳转身份只用结构字段。 */
    @SerialName("session_name") val sessionName: String = "",
    /** tmux window_index 结构字段（字符串）；缺省空。 */
    @SerialName("window_index") val windowIndex: String = "",
    /** tmux window_name 结构字段；缺省空。展示名优先于 [name]。 */
    @SerialName("window_name") val windowName: String = "",
    /** 白名单 Provider id；缺省空，UI 走通用 glyph，不得猜成 claude。 */
    @SerialName("provider") val provider: String = "",
)

/**
 * 全量列表回复 S→C（req_id 对应 list；seq 单调递增）。
 *
 * @contract
 * @pre reqId ≥ 1 且 seq ≥ 1
 * @post workspaces 为服务端权威快照；客户端只渲染不重算聚合
 * @err validate() 对 reqId/seq ≤ 0 返回非空原因
 * @inv seq 单调递增
 */
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
 *
 * @contract
 * @pre seq ≥ 1
 * @post 客户端按 added/removed/changed 四组应用增量；seq 不连续时须回退全量 list
 * @err validate() 对 seq ≤ 0 返回非空原因
 * @inv 四组字段两两不相交；seq 单调递增
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

/**
 * 订阅会话镜像 C→S（成功后 S 先发 snapshot 再流 delta）。
 *
 * @contract
 * @pre ref 非空且 rows/cols ≥ 1
 * @post 服务端成功后先发 BinaryKind.SNAPSHOT 再流 BinaryKind.DELTA
 * @err validate() 对空 ref 或 rows/cols ≤ 0 返回非空原因
 * @inv rows/cols 是订阅时的终端尺寸；随后可经 ResizeFrame 调整
 */
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

/**
 * 停止镜像 C→S（幂等；连接关闭即全部退订）。
 *
 * @contract
 * @pre ref 非空
 * @post 服务端停止对该 ref 的镜像推送
 * @err validate() 对空 ref 返回非空原因
 * @inv 幂等；重复退订与退订未订阅会话均为合法
 */
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
 * 特殊键且**不附加回车**（快捷键条语义 = 按一下那个键）。(text 或 attachmentPath) 与 keys
 * 一帧至多其一，两者都有判协议错误（契约 §4.2）；三者皆无 = 仅回车（既有语义不变）。
 *
 * attachmentPath（feat-image-upload-inline，需求 042，可选字段前向兼容增量不 bump 版本）：
 * 已上传图片的主机绝对路径。服务端收到非空 attachmentPath 时按三步序列注入
 * （见 server/internal/bridge/bridge.go: InjectWithAttachment）——单独一次 bracketed
 * paste 只贴路径本身（不掺 text），再单独发 text（若非空），最后一次 Enter 提交；
 * 这样 Claude Code 自己的粘贴路径识别才会把它内联成 `[Image #N]`，而不是把路径和文字
 * 混进同一次粘贴导致识别失败、甚至撞上粘贴异步处理与 Enter 的时序竞态
 * （fix-image-upload-input-box 回炉记录：两者混在一次 paste 里，Enter 会被吞，消息卡在
 * 输入框发不出去）。attachmentPath 为空时行为与该字段引入前逐字节一致。
 *
 * @contract
 * @pre reqId ≥ 1、ref 非空、(text 或 attachmentPath) 与 keys 至多一类非空
 * @post 该帧在 wire 上合法（validate 通过）；空 text 空 keys 空 attachmentPath 是合法的裸 Enter
 * @err validate() 对 reqId ≤ 0 / 空 ref / (text/attachmentPath)+keys 并存返回非空原因
 * @inv keys 注入不附加回车；attachmentPath 为空时与该字段引入前逐字节一致
 */
@Serializable
data class InputFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("ref") val ref: String,
    @SerialName("text") val text: String = "",
    @SerialName("keys") val keys: List<InputKey> = emptyList(),
    @SerialName("attachment_path") val attachmentPath: String = "",
) : FramePayload {
    override val frameType: String get() = FrameType.INPUT
    override fun validate(): String? = when {
        reqId <= 0 -> "input req_id must be >= 1"
        ref.isEmpty() -> "input ref must be non-empty"
        // 契约 §4.2：(text/attachmentPath) 与 keys 互斥，一帧至多其一（对齐 Go validate.go）。
        (text.isNotEmpty() || attachmentPath.isNotEmpty()) && keys.isNotEmpty() ->
            "input carries both text/attachment_path and keys; at most one is allowed"
        else -> null
    }
}

/**
 * 注入回执 S→C（必达）。ok:true 表示字节已进面板；ok:false 必须带 reason。
 * reason 存在当且仅当 ok:false（一字段一义）。
 *
 * @contract
 * @pre reqId ≥ 1
 * @post ok:false 时 reason 非 null；ok:true 时 reason 为 null
 * @err validate() 对 reqId ≤ 0 / ok:false 缺 reason / ok:true 带 reason 返回非空原因
 * @inv reason 存在当且仅当 ok=false
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

/**
 * 按行区间拉历史 C→S（from_line 按 tmux capture-pane 语义；count >= 1）。
 *
 * @contract
 * @pre reqId ≥ 1、ref 非空、count ≥ 1
 * @post 服务端返回 BinaryKind.SCROLLBACK 帧，其 reqId/fromLine/lineCount 为实际返回区间
 * @err validate() 对 reqId ≤ 0 / 空 ref / count ≤ 0 返回非空原因
 * @inv fromLine 可为负（历史行）；变更 fromLine 不影响帧合法性，由服务端收敛
 */
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

/**
 * 上报手机行列数 C→S（只作用于已订阅会话）。
 *
 * @contract
 * @pre ref 非空且 rows/cols ≥ 1
 * @post 服务端按新尺寸调整已订阅会话的镜像
 * @err validate() 对空 ref 或 rows/cols ≤ 0 返回非空原因
 * @inv 未订阅会话的 resize 是空操作（服务端忽略）
 */
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

/**
 * 协议级错误 S→C（坏帧/未知类型/缺会话/版本不支持/内部错误）。
 *
 * @contract
 * @pre 无
 * @post code 为 [ErrorCode] 闭集成员（客户端解码未识别值回退 [ErrorCode.UNKNOWN]）
 * @err 无本地错误面；[ErrorCode.UNKNOWN] 仅解码回退、编码必拒
 * @inv reason 为人类可读补充，可为空
 */
@Serializable
data class ErrorFrame(
    @SerialName("code") val code: ErrorCode,
    @SerialName("reason") val reason: String = "",
) : FramePayload {
    override val frameType: String get() = FrameType.ERROR
}

/**
 * 滚轮手势 C→S（缺陷④ 远端滚动投送，docs/remote-scroll-forward-design.md）。
 *
 * delta < 0 = 向上滚（看历史）；delta > 0 = 向下滚。单位：档位（每档约 3 行）。
 * 服务端无成功 ack（屏幕内容变化即反馈）；失败时服务端回 ErrorFrame。
 *
 * @contract
 * @pre ref 非空；delta 非零
 * @post 服务端按 delta 方向对远端 pane 执行滚动（mouse-tracking 路径注入字节；
 *       copy-mode 降级路径进入 copy-mode 并返回 PaneModeChangedFrame）
 * @err validate() 对空 ref / 零 delta 返回非空原因
 * @inv delta 永不为 0（零档位是 no-op，编码前拒绝）
 */
@Serializable
data class ScrollWheelFrame(
    @SerialName("ref") val ref: String,
    @SerialName("delta") val delta: Int,
) : FramePayload {
    override val frameType: String get() = FrameType.SCROLL_WHEEL
    override fun validate(): String? = when {
        ref.isEmpty() -> "scroll_wheel ref must be non-empty"
        delta == 0 -> "scroll_wheel delta must be non-zero"
        else -> null
    }
}

/**
 * 发图预贴 C→S（需求 057，对 003 第 1 条"一次性注入"的显式例外）：图片一上传成功就贴进
 * CLI pane（不回车），让 Claude Code 的解码/缩放/重编码在用户打字期间悄悄跑完，而不是等
 * 点发送才贴、让用户等那 1 秒。path 只能是图片路径本身，绝不能掺文字——掺了会落回
 * Claude Code 那条慢分支（fork osascript 查剪贴板），紧随其后的 Enter 会被吞
 * （见 fix-image-upload-input-box 回炉记录）。
 *
 * 服务端成功无 ack（镜像流里能看到 `[Image #N]` 就是反馈，同 ScrollWheel 的路数）；
 * 失败发 ErrorFrame。**服务端从不清理 pane 里已贴的内容**（需求 057 第 3 款）：
 * 选了图不发，那个占位符会留在 CLI 输入框里——这不是静默残留，App 是 pane 的镜像，
 * 用户屏幕上看得见。
 *
 * @contract
 * @pre ref 非空、path 非空
 * @post 服务端把 path 贴进 pane（bracketed paste，不回车）；成功无回执，失败见 ErrorFrame
 * @err validate() 对空 ref / 空 path 返回非空原因
 * @inv 从不清理 pane 已有内容；path 永不与其它内容共享同一次粘贴
 */
@Serializable
data class AttachPreviewFrame(
    @SerialName("ref") val ref: String,
    @SerialName("path") val path: String,
) : FramePayload {
    override val frameType: String get() = FrameType.ATTACH_PREVIEW
    override fun validate(): String? = when {
        ref.isEmpty() -> "attach_preview ref must be non-empty"
        path.isEmpty() -> "attach_preview path must be non-empty"
        else -> null
    }
}

/**
 * pane copy-mode 状态变更通知 S→C（缺陷④）。
 *
 * 服务端在 pane 进入或退出 copy-mode 时推送（进：scroll 降级触发；退：handleInput 兜底）。
 * 客户端据此显示 copy-mode 指示器，防止用户在 copy-mode 中打字无响应。
 * 此帧为 S→C only，客户端**不上行**。
 *
 * @contract
 * @pre ref 非空
 * @post 客户端更新 inCopyMode 状态；为 true 时显示 copy-mode 指示；为 false 时隐藏
 * @err validate() 对空 ref 返回非空原因
 * @inv inCopyMode 反映服务端当前所知的 pane mode 状态（最终一致）
 */
@Serializable
data class PaneModeChangedFrame(
    @SerialName("ref") val ref: String,
    @SerialName("in_copy_mode") val inCopyMode: Boolean,
) : FramePayload {
    override val frameType: String get() = FrameType.PANE_MODE_CHANGED
    override fun validate(): String? = if (ref.isEmpty()) "pane_mode_changed ref must be non-empty" else null
}

/**
 * 二级菜单全量快照 S→C（061）。wire type 必须是 [FrameType.LEVEL2_FRAME]（`level2_frame`）。
 *
 * @contract
 * @pre workspace 非空、seq ≥ 1
 * @post 客户端以 sessions 整体替换该工作区的二级视图
 * @inv title 原样；身份与状态不从 title 推导
 */
@Serializable
data class Level2Frame(
    @SerialName("workspace") val workspace: String,
    @SerialName("seq") val seq: Long,
    @SerialName("sessions") val sessions: List<Session>,
) : FramePayload {
    override val frameType: String get() = FrameType.LEVEL2_FRAME
    override fun validate(): String? = when {
        workspace.isEmpty() -> "level2_frame workspace must be non-empty"
        seq <= 0 -> "level2_frame seq must be >= 1"
        else -> null
    }
}

/**
 * 二级低频心跳 S→C（061）：本周期无变更时推，App 用来区分「没变化」和「连接死了」。
 * 无 sessions，不得清列表。
 */
@Serializable
data class Level2HeartbeatFrame(
    @SerialName("workspace") val workspace: String,
    @SerialName("seq") val seq: Long,
) : FramePayload {
    override val frameType: String get() = FrameType.LEVEL2_HEARTBEAT
    override fun validate(): String? = when {
        workspace.isEmpty() -> "level2_heartbeat workspace must be non-empty"
        seq <= 0 -> "level2_heartbeat seq must be >= 1"
        else -> null
    }
}

/** 二级订阅 C→S：进入二级菜单时发。 */
@Serializable
data class Level2SubscribeFrame(
    @SerialName("workspace") val workspace: String,
) : FramePayload {
    override val frameType: String get() = FrameType.LEVEL2_SUBSCRIBE
    override fun validate(): String? =
        if (workspace.isEmpty()) "level2_subscribe workspace must be non-empty" else null
}

/** 二级退订 C→S：离开二级菜单时发。幂等。 */
@Serializable
data class Level2UnsubscribeFrame(
    @SerialName("workspace") val workspace: String,
) : FramePayload {
    override val frameType: String get() = FrameType.LEVEL2_UNSUBSCRIBE
    override fun validate(): String? = null
}

/**
 * 会话内悬浮窗抓屏帧 S→C（064）。[text] 是服务端抓到的 choose-tree 画面，App 原样画。
 */
@Serializable
data class OverlayFrame(
    @SerialName("text") val text: String = "",
    @SerialName("seq") val seq: Long = 1,
    @SerialName("rows") val rows: Int = 0,
    @SerialName("cols") val cols: Int = 0,
) : FramePayload {
    override val frameType: String get() = FrameType.OVERLAY_FRAME
    override fun validate(): String? = if (seq <= 0) "overlay_frame seq must be >= 1" else null
}

/** 打开悬浮窗时订抓屏流 C→S（065：必须带当前会话所属 socket）。 */
@Serializable
data class OverlaySubscribeFrame(
    @SerialName("socket") val socket: String = "",
    @SerialName("rows") val rows: Int = 0,
    @SerialName("cols") val cols: Int = 0,
) : FramePayload {
    override val frameType: String get() = FrameType.OVERLAY_SUBSCRIBE
    override fun validate(): String? = when {
        socket.isEmpty() -> "overlay_subscribe socket must be non-empty"
        rows < 0 -> "overlay_subscribe rows must be >= 0"
        cols < 0 -> "overlay_subscribe cols must be >= 0"
        else -> null
    }
}

/** 关闭悬浮窗时退订 C→S（064）。幂等。 */
@Serializable
class OverlayUnsubscribeFrame : FramePayload {
    override val frameType: String get() = FrameType.OVERLAY_UNSUBSCRIBE
    override fun equals(other: Any?) = other is OverlayUnsubscribeFrame
    override fun hashCode(): Int = frameType.hashCode()
}

/**
 * 关闭会话 C→S（契约 088 E12）。未二次确认不得发此帧。
 *
 * @contract
 * @pre reqId ≥ 1、ref 非空
 * @post 服务端回 CloseSessionAckFrame；pane 已不在时 ok=true（幂等）
 * @err validate() 对 reqId ≤ 0 / 空 ref 返回非空原因
 */
@Serializable
data class CloseSessionFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("ref") val ref: String,
) : FramePayload {
    override val frameType: String get() = FrameType.CLOSE_SESSION
    override fun validate(): String? = when {
        reqId <= 0 -> "close_session req_id must be >= 1"
        ref.isEmpty() -> "close_session ref must be non-empty"
        else -> null
    }
}

/**
 * 关闭回执 S→C。ok=true 才允许客户端取消收藏 / 退出会话页。
 *
 * @contract
 * @pre reqId ≥ 1；ok=false 时 reason 非 null；ok=true 时 reason 为 null
 * @post 客户端以 ok 决定收尾
 * @err validate() 对 reqId ≤ 0 / 失败缺 reason / 成功带 reason 返回非空原因
 */
@Serializable
data class CloseSessionAckFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("ok") val ok: Boolean,
    @SerialName("reason") val reason: CloseFailReason? = null,
) : FramePayload {
    override val frameType: String get() = FrameType.CLOSE_SESSION_ACK
    override fun validate(): String? = when {
        reqId <= 0 -> "close_session_ack req_id must be >= 1"
        !ok && reason == null -> "failed close_session_ack must carry a reason"
        ok && reason != null -> "accepted close_session_ack must not carry a reason"
        else -> null
    }
}

/**
 * 新建会话 C→S（088 E13）。argv 已在客户端按空白分词，不经 shell。
 *
 * @contract
 * @pre reqId ≥ 1、cwd 非空、argv 至少 1 段
 * @post 服务端回 CreateSessionAckFrame
 * @err validate() 对缺字段返回非空
 * @inv 不 bump 协议版本
 */
@Serializable
data class CreateSessionFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("cwd") val cwd: String,
    @SerialName("argv") val argv: List<String>,
    @SerialName("provider") val provider: String = "",
) : FramePayload {
    override val frameType: String get() = FrameType.CREATE_SESSION
    override fun validate(): String? = when {
        reqId <= 0 -> "create_session req_id must be >= 1"
        cwd.isEmpty() -> "create_session cwd must be non-empty"
        argv.isEmpty() || argv.any { it.isEmpty() } -> "create_session argv must have non-empty elements"
        else -> null
    }
}

/**
 * 新建回执 S→C。ok 才带 ref。
 *
 * @contract
 * @pre reqId ≥ 1；ok=true 时 ref 非空且 reason 空
 * @post 客户端以 ok 决定是否打开新会话
 * @err validate() 对矛盾组合返回非空
 * @inv reason 存在当且仅当 ok=false
 */
@Serializable
data class CreateSessionAckFrame(
    @SerialName("req_id") val reqId: Long,
    @SerialName("ok") val ok: Boolean,
    @SerialName("ref") val ref: String = "",
    @SerialName("reason") val reason: CreateFailReason? = null,
) : FramePayload {
    override val frameType: String get() = FrameType.CREATE_SESSION_ACK
    override fun validate(): String? = when {
        reqId <= 0 -> "create_session_ack req_id must be >= 1"
        !ok && reason == null -> "failed create_session_ack must carry a reason"
        ok && reason != null -> "accepted create_session_ack must not carry a reason"
        ok && ref.isEmpty() -> "accepted create_session_ack must carry a ref"
        !ok && ref.isNotEmpty() -> "failed create_session_ack must not carry a ref"
        else -> null
    }
}

