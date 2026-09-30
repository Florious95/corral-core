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

package dev.agentmirror.app.notify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * notifications_v1 线上模型（docs/contracts/01-agent-notification-contract.md §2–§4）。
 * 同一个 [NotificationRecord] 既是实时帧 payload、历史页条目，也是本地库的落盘形状。
 */

enum class NotificationLevel(val wire: String) {
    Info("info"),
    Success("success"),
    Warning("warning"),
    Error("error"),
    ;

    companion object {
        fun fromWire(wire: String): NotificationLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/** 客户端记录主键 `(host_id, id)`（契约 §2.1）。 */
data class NotificationKey(val hostId: String, val id: String)

@Serializable
data class NotificationRecord(
    val id: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("stream_id") val streamId: String,
    val seq: String,
    val timestamp: String,
    val title: String,
    val body: String,
    @SerialName("session_ref") val sessionRef: String? = null,
    @SerialName("session_instance") val sessionInstance: String? = null,
    val workspace: String? = null,
    @SerialName("agent_name") val agentName: String? = null,
    val level: String,
) {
    val key: NotificationKey get() = NotificationKey(hostId, id)

    /** seq 是十进制 uint64 字符串：比较必须按数值，禁止字典序。 */
    val seqNumber: ULong get() = seq.toULongOrNull() ?: 0uL

    val levelKind: NotificationLevel get() = NotificationLevel.fromWire(level) ?: NotificationLevel.Info

    /** 必填字段齐全、seq 为无前导零正整数、level 属于闭集；非法记录不进业务存储。 */
    fun isValid(): Boolean =
        id.isNotBlank() && hostId.isNotBlank() && streamId.isNotBlank() &&
            DECIMAL_SEQ.matches(seq) && seq.toULongOrNull() != null &&
            title.isNotBlank() && body.isNotBlank() &&
            NotificationLevel.fromWire(level) != null &&
            sessionRef?.isEmpty() != true
}

/** 十进制 uint64、1 起、无前导零（契约 §2.1）。 */
private val DECIMAL_SEQ = Regex("[1-9][0-9]*")

@Serializable
data class NotificationCursor(
    @SerialName("stream_id") val streamId: String,
    val seq: String,
)

/** auth_ack 协商成功时的握手观察值；head 不是已同步游标（契约 §3）。 */
@Serializable
data class NotificationState(
    @SerialName("host_id") val hostId: String,
    @SerialName("stream_id") val streamId: String,
    @SerialName("head_seq") val headSeq: String,
    @SerialName("retained_from_seq") val retainedFromSeq: String = "0",
)

/** 一页历史；[records] 已剔除非法条目。 */
data class NotificationPage(
    val reqId: Long,
    val ok: Boolean,
    val hostId: String?,
    val streamId: String?,
    val snapshotCursor: NotificationCursor?,
    val resetReason: String?,
    val records: List<NotificationRecord>,
    val nextPageToken: String?,
)

/** 本连接关心的入站文本帧；其余帧原样交给 Core。 */
sealed interface NotificationInbound {
    data class AuthAck(val ok: Boolean, val capabilities: List<String>, val state: NotificationState?) : NotificationInbound
    data class Live(val record: NotificationRecord?) : NotificationInbound
    data class Page(val page: NotificationPage) : NotificationInbound
}

/**
 * notifications_v1 编解码。Core AAR 不认识新 type，也不带 capabilities，这里只在持久连接的
 * 传输层做最小的文本改写 / 截获，其余帧逐字节透传。
 */
object NotificationCodec {
    const val CAPABILITY = "notifications_v1"
    const val PAGE_SIZE = 50

    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * 识别 auth_ack / notification / notifications_page；不是这三类（或无法解析）返回 null，
     * 由调用方原样交给 Core。
     */
    fun peek(text: String): NotificationInbound? {
        if (!text.contains("notification") && !text.contains("auth_ack")) return null
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val payload = root["payload"] as? JsonObject
        return when (root.string("type")) {
            "auth_ack" -> payload?.let(::authAck)
            "notification" -> NotificationInbound.Live(payload?.let(::record))
            "notifications_page" -> payload?.let(::page)?.let(NotificationInbound::Page)
            else -> null
        }
    }

    /** 出站 auth 帧追加 `capabilities:["notifications_v1"]`；其余帧原样返回。 */
    fun withCapability(text: String): String {
        if (!text.contains("\"auth\"")) return text
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return text
        val payload = root["payload"] as? JsonObject ?: return text
        if (root.string("type") != "auth" || payload.containsKey("capabilities")) return text
        val decorated = JsonObject(payload + ("capabilities" to JsonArray(listOf(JsonPrimitive(CAPABILITY)))))
        return JsonObject(root + ("payload" to decorated)).toString()
    }

    /** 首页请求：cursor=null 为 bootstrap（契约 §4.2）。 */
    fun syncRequest(reqId: Long, cursor: NotificationCursor?, pageSize: Int = PAGE_SIZE): String =
        buildJsonObject {
            put("v", 1)
            put("type", "notifications_sync")
            putJsonObject("payload") {
                put("req_id", reqId)
                if (cursor != null) {
                    putJsonObject("cursor") {
                        put("stream_id", cursor.streamId)
                        put("seq", cursor.seq)
                    }
                }
                put("page_size", pageSize)
            }
        }.toString()

    /** 续页请求只带 req_id + page_token（契约 §4.3）。 */
    fun continuation(reqId: Long, pageToken: String): String =
        buildJsonObject {
            put("v", 1)
            put("type", "notifications_sync")
            putJsonObject("payload") {
                put("req_id", reqId)
                put("page_token", pageToken)
            }
        }.toString()

    fun record(element: JsonElement): NotificationRecord? =
        runCatching { json.decodeFromJsonElement<NotificationRecord>(element) }.getOrNull()?.takeIf { it.isValid() }

    private fun authAck(payload: JsonObject): NotificationInbound.AuthAck {
        val capabilities = (payload["capabilities"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()
        val state = payload["notification_state"]?.let {
            runCatching { json.decodeFromJsonElement<NotificationState>(it) }.getOrNull()
        }
        return NotificationInbound.AuthAck(
            ok = (payload["ok"] as? JsonPrimitive)?.booleanOrNull == true,
            capabilities = capabilities,
            state = state,
        )
    }

    private fun page(payload: JsonObject): NotificationPage? {
        val reqId = (payload["req_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
        val cursor = payload["snapshot_cursor"]?.let {
            runCatching { json.decodeFromJsonElement<NotificationCursor>(it) }.getOrNull()
        }
        return NotificationPage(
            reqId = reqId,
            ok = (payload["ok"] as? JsonPrimitive)?.booleanOrNull == true,
            hostId = payload.string("host_id"),
            streamId = payload.string("stream_id"),
            snapshotCursor = cursor,
            resetReason = payload.string("reset_reason"),
            records = (payload["items"] as? JsonArray)?.mapNotNull(::record).orEmpty(),
            nextPageToken = payload.string("next_page_token")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull
}
