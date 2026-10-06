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

package dev.agentmirror.app.conversation

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Pi RPC (delta-only wire, docs/json.md of Pi 1.0.0) → render model.
 *
 * Every assistant content block is its own list item keyed "<message>#<contentIndex>", so a
 * streaming token recomposes one paragraph, never the transcript. Message keys come from the
 * worker's seq of message_start: identical across replays, so LazyColumn keys never churn.
 */

/** One row of the conversation transcript. [key] is the stable LazyColumn key. */
sealed interface ConversationItem {
    val key: String
}

enum class Delivery { Sending, Queued, Delivered }

data class UserTurn(
    override val key: String,
    val text: String,
    val imageCount: Int = 0,
    val delivery: Delivery = Delivery.Delivered,
    val timestamp: Long = 0,
    /** Set when [text] is Pi's own `/skill:name` expansion; rendered as a folded card. */
    val skill: SkillBlock? = null,
) : ConversationItem

/**
 * Pi's explicit skill invocation as the model receives it (agent-session.js parseSkillBlock):
 * `<skill name location>` wrapper, the SKILL.md body, then the user's own request, if any.
 */
data class SkillBlock(val name: String, val location: String, val content: String, val request: String?)

data class AssistantText(
    override val key: String,
    val text: String,
    val streaming: Boolean,
) : ConversationItem

data class Reasoning(
    override val key: String,
    val text: String,
    val streaming: Boolean,
    val startedAt: Long = 0,
    val endedAt: Long? = null,
    val redacted: Boolean = false,
) : ConversationItem

enum class ToolPhase { Composing, Pending, Running, Succeeded, Failed, Interrupted }

data class ToolCall(
    override val key: String,
    val id: String,
    val name: String,
    /** Final arguments as compact JSON, once the model finished writing them. */
    val arguments: JsonObject? = null,
    /** Raw streamed argument JSON while the model is still writing the call. */
    val argumentsDraft: String = "",
    val output: String = "",
    val phase: ToolPhase = ToolPhase.Composing,
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    val startedAt: Long = 0,
    val endedAt: Long? = null,
) : ConversationItem {
    val finished: Boolean get() = phase == ToolPhase.Succeeded || phase == ToolPhase.Failed || phase == ToolPhase.Interrupted

    /** Render activity is not completion: unfinished historical Pending stays pending but static. */
    fun shouldAnimate(streaming: Boolean): Boolean = streaming && (phase == ToolPhase.Composing || phase == ToolPhase.Running)
}

enum class NoticeTone { Info, Warning, Error, Divider }

data class Notice(
    override val key: String,
    val tone: NoticeTone,
    val title: String,
    val detail: String? = null,
) : ConversationItem

/** A model the session can switch to (worker-projected get_available_models entry). */
data class ModelChoice(val id: String, val name: String, val provider: String, val reasoning: Boolean)

/**
 * How the last compaction ended, as the agent reported it (Pi's compaction_end). [before] and
 * [after] are Pi's own estimates; either may be absent. [seq] lets a caller tell a fresh outcome
 * from one that predates its request.
 */
data class CompactionOutcome(val seq: Long, val before: Long?, val after: Long?, val error: String?, val aborted: Boolean, val commandOnly: Boolean = false, val feedback: String? = null)

/** "估算减少约 67%" only when both estimates are comparable; never a fabricated saving. */
internal fun compactionDelta(before: Long?, after: Long?): String? = when {
    before == null || after == null || before <= 0 -> null
    after >= before -> "估算未减少"
    else -> "估算减少约 ${((before - after) * 100.0 / before).roundToInt()}%"
}

data class RetryStatus(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val reason: String, val since: Long)

/** The message currently streaming; [blocks] maps contentIndex → item key. */
data class ActiveMessage(val key: String, val blocks: Map<Int, String> = emptyMap())

/**
 * @contract reduce() is pure: (state, record) → state. Records with seq ≤ [lastSeq] are
 * ignored, so a replay that overlaps live delivery is idempotent.
 * @inv items and every displayed payload are bounded; overflow sets [historyTruncated].
 */
data class ConversationState(
    val items: List<ConversationItem> = emptyList(),
    val running: Boolean = false,
    val compacting: Boolean = false,
    val compaction: CompactionOutcome? = null,
    val retry: RetryStatus? = null,
    val queued: Int = 0,
    val model: String? = null,
    /** Identity of [model] for set_model; the display name alone cannot address it. */
    val modelId: String? = null,
    val modelProvider: String? = null,
    /** CLI identity, distinct from the model's API vendor; legacy conversation_v1 is Pi. */
    val agentProvider: String = "pi",
    /** Switchable models; null until the picker asked for them. */
    val models: List<ModelChoice>? = null,
    /** Levels the current model supports, in Pi's order; ["off"] for a non-reasoning model. */
    val thinkingLevels: List<String> = emptyList(),
    val thinkingLevel: String? = null,
    val sessionName: String? = null,
    val sessionId: String? = null,
 val interactions: List<NativeInteraction> = emptyList(),
 val extensionEditor: Pair<Long, String>? = null,
 val extensionTitle: String? = null,
    val historyTruncated: Boolean = false,
    val historyLoadedItems: Int? = null,
    val historyContentClipped: Boolean = false,
    val stream: String? = null,
    val lastSeq: Long = 0,
    val active: ActiveMessage? = null,
    /** Host clock minus phone clock at the last ready; ticks live elapsed times in host time. */
    val serverSkewMs: Long = 0,
) {
    /** Working, but nothing on screen is visibly moving (between turns, before the first token). */
    val quietlyWorking: Boolean
        get() = running && when (val last = items.lastOrNull()) {
            is AssistantText -> !last.streaming
            is Reasoning -> !last.streaming
            is ToolCall -> last.finished
            else -> true
        }

    fun apply(seq: Long, ts: Long, event: JsonObject): ConversationState {
        if (seq > 0 && seq <= lastSeq) return this
        val next = reduce(seq, ts, event)
        return if (seq > lastSeq) next.copy(lastSeq = seq) else next
    }

    /** Server said the stream restarted: keep only optimistic echoes not yet delivered. */
    fun reset(stream: String, truncated: Boolean): ConversationState = ConversationState(
        items = items.filter { it is UserTurn && it.delivery != Delivery.Delivered },
        model = model,
        modelId = modelId,
        modelProvider = modelProvider,
        agentProvider = agentProvider,
        models = models,
        thinkingLevels = thinkingLevels,
        thinkingLevel = thinkingLevel,
        sessionName = sessionName,
        historyTruncated = truncated,
        stream = stream,
    )

    fun withLocalEcho(id: String, text: String, imageCount: Int, now: Long): ConversationState =
        copy(items = bounded(items + UserTurn(localKey(id), text, imageCount, Delivery.Sending, now)))

    fun dropLocalEcho(id: String): ConversationState =
        copy(items = items.filterNot { it.key == localKey(id) })

    private fun reduce(seq: Long, ts: Long, e: JsonObject): ConversationState = when (e.str("type")) {
        "agent_start" -> copy(running = true, retry = null)
        "agent_settled" -> settle(ts)
        "message_start" -> messageStart(seq, ts, e.obj("message") ?: JsonObject(emptyMap()))
        "message_update" -> messageUpdate(ts, e.obj("assistantMessageEvent") ?: JsonObject(emptyMap()))
        "message_end" -> messageEnd(seq, ts, e.obj("message") ?: JsonObject(emptyMap()))
        "tool_execution_start" -> toolStart(ts, e)
        "tool_execution_update" -> toolUpdate(e)
        "tool_execution_end" -> toolEnd(ts, e)
        "queue_update" -> copy(queued = (e.arr("steering")?.size ?: 0) + (e.arr("followUp")?.size ?: 0))
        "compaction_start" -> copy(compacting = true)
        "compaction_end" -> compactionEnd(seq, e)
        "auto_retry_start" -> copy(
            retry = RetryStatus(
                attempt = e.int("attempt") ?: 1,
                maxAttempts = e.int("maxAttempts") ?: 1,
                delayMs = e.long("delayMs") ?: 0,
                reason = e.str("errorMessage"),
                since = ts,
            ),
        )
        "auto_retry_end" -> if (e.bool("success") == false) {
            copy(retry = null).notice(seq, NoticeTone.Error, "多次重试后仍然失败", e.str("finalError").ifBlank { null })
        } else {
            copy(retry = null)
        }
        "extension_ui_request" -> when (val method = e.str("method")) {
            "notify" -> notice(
                seq,
                when (e.str("notifyType")) {
                    "error" -> NoticeTone.Error
                    "warning" -> NoticeTone.Warning
                    else -> NoticeTone.Info
                },
                e.str("message").ifBlank { "扩展提示" },
            )
            "confirm", "select", "input", "editor", "permission" -> nativeInteraction(e)?.let { request ->
                if (request.sessionId != sessionId && sessionId != null) this else copy(interactions = (interactions.filterNot { it.id == request.id } + request).takeLast(16))
            } ?: this
            "setStatus" -> extensionNotice(e.str("statusKey"), e.str("statusText"))
            "setWidget" -> extensionNotice(e.str("widgetKey"), e.arr("widgetLines").orEmpty().joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() })
            "setTitle" -> copy(extensionTitle = e.str("title").take(640).ifBlank { null })
            "set_editor_text" -> copy(extensionEditor = seq to e.str("text").take(200000))
            else -> this
        }
        "interaction_resolved" -> copy(interactions = interactions.filterNot { it.id == e.str("id") }).notice(seq, NoticeTone.Info, e.str("reason"))
        "extension_error" -> notice(seq, NoticeTone.Error, "扩展出错", e.str("error").ifBlank { null })
        "session_info_changed" -> copy(sessionName = e.str("name").ifBlank { null })
        "thinking_level_changed" -> copy(thinkingLevel = e.str("level").ifBlank { null })
        "history_window" -> copy(
            historyTruncated = historyTruncated || e.bool("older_omitted") == true || e.bool("content_clipped") == true,
            historyLoadedItems = e.int("loaded_items"),
            historyContentClipped = e.bool("content_clipped") == true,
        )
        "session_reset" -> ConversationState(
            items = if (e.bool("replace") == true) emptyList() else items.filter { it is UserTurn && it.delivery != Delivery.Delivered },
            model = model,
            modelId = modelId,
            modelProvider = modelProvider,
            agentProvider = agentProvider,
            models = models,
            thinkingLevels = thinkingLevels,
            thinkingLevel = thinkingLevel,
            sessionId = e.str("sessionId").ifBlank { null },
            historyTruncated = historyTruncated,
            stream = stream,
        )
        "response" -> response(e)
        else -> this
    }

    private fun settle(ts: Long): ConversationState = copy(
        running = false,
        compacting = false,
        retry = null,
        active = null,
        items = items.map { item ->
            when {
                item is ToolCall && !item.finished -> item.copy(phase = ToolPhase.Interrupted, endedAt = item.endedAt ?: ts)
                item is AssistantText && item.streaming -> item.copy(streaming = false)
                item is Reasoning && item.streaming -> item.copy(streaming = false, endedAt = item.endedAt ?: ts)
                else -> item
            }
        },
    )

    private fun messageStart(seq: Long, ts: Long, message: JsonObject): ConversationState {
        val key = "m$seq"
        return when (message.str("role")) {
            "user" -> {
                val content = userContent(message["content"])
                // Prompts are delivered in send order: the oldest optimistic echo is this one.
                val echo = items.indexOfFirst { it is UserTurn && it.delivery != Delivery.Delivered }
                val base = if (echo >= 0) items.toMutableList().apply { removeAt(echo) } else items
                val turn = UserTurn(key, content.text, content.images, Delivery.Delivered, message.long("timestamp") ?: ts, content.skill)
                copy(items = bounded(base + turn))
            }
            "assistant" -> copy(active = ActiveMessage(key))
            "compactionSummary", "branchSummary" -> notice(seq, NoticeTone.Divider, "上下文已摘要")
            "custom" -> if (message.bool("display") == true) {
                notice(seq, NoticeTone.Info, userContent(message["content"]).text.ifBlank { "扩展消息" })
            } else {
                this
            }
            else -> this
        }
    }

    private fun messageUpdate(ts: Long, u: JsonObject): ConversationState {
        val active = active ?: return this
        val index = u.int("contentIndex") ?: return this
        val blockKey = active.blocks[index] ?: "${active.key}#$index"
        return when (u.str("type")) {
            "text_start" -> startBlock(active, index, blockKey, ts, AssistantText(blockKey, "", streaming = true))
            "text_delta" -> updateOrAdd(blockKey, active, index, AssistantText(blockKey, "", streaming = true)) {
                (it as? AssistantText)?.copy(text = clip(it.text + u.str("delta")))
            }
            "text_end" -> updateOrAdd(blockKey, active, index, AssistantText(blockKey, "", streaming = false)) {
                (it as? AssistantText)?.copy(text = clip(u.str("content").ifEmpty { it.text }), streaming = false)
            }
            "thinking_start" -> startBlock(active, index, blockKey, ts, Reasoning(blockKey, "", streaming = true, startedAt = ts))
            "thinking_delta" -> updateOrAdd(blockKey, active, index, Reasoning(blockKey, "", streaming = true, startedAt = ts)) {
                (it as? Reasoning)?.copy(text = clip(it.text + u.str("delta")))
            }
            "thinking_end" -> updateOrAdd(blockKey, active, index, Reasoning(blockKey, "", streaming = false, startedAt = ts)) {
                (it as? Reasoning)?.copy(text = clip(u.str("content").ifEmpty { it.text }), streaming = false, endedAt = ts)
            }
            "toolcall_start" -> {
                val id = u.str("id").ifBlank { return this }
                val key = toolKey(id)
                startBlock(active, index, key, ts, existing(key) ?: ToolCall(key, id, u.str("toolName"), startedAt = ts))
            }
            "toolcall_delta" -> update(blockKey) {
                (it as? ToolCall)?.let { tool ->
                    if (tool.phase == ToolPhase.Composing) tool.copy(argumentsDraft = clip(tool.argumentsDraft + u.str("delta"))) else tool
                }
            }
            "toolcall_end" -> {
                val call = u.obj("toolCall") ?: return this
                val id = call.str("id").ifBlank { return this }
                val key = toolKey(id)
                val mapped = copy(active = active.copy(blocks = active.blocks + (index to key)))
                mapped.upsertTool(key) { tool ->
                    (tool ?: ToolCall(key, id, call.str("name"), startedAt = ts)).copy(
                        name = call.str("name").ifBlank { tool?.name.orEmpty() },
                        arguments = call.obj("arguments"),
                        argumentsDraft = "",
                        phase = if (tool == null || tool.phase == ToolPhase.Composing) ToolPhase.Pending else tool.phase,
                    )
                }
            }
            else -> this
        }
    }

    private fun messageEnd(seq: Long, ts: Long, message: JsonObject): ConversationState = when (message.str("role")) {
        "user" -> {
            val content = userContent(message["content"])
            val stamp = message.long("timestamp")
            val at = items.indexOfLast { it is UserTurn && it.delivery == Delivery.Delivered }
            val existing = items.getOrNull(at) as? UserTurn
            if (existing != null && (stamp == null || existing.timestamp == stamp)) {
                replaceAt(at, existing.copy(text = content.text, imageCount = content.images, skill = content.skill))
            } else {
                messageStart(seq, ts, message)
            }
        }
        "assistant" -> assistantEnd(seq, ts, message)
        else -> this
    }

    /** message_end is authoritative: rebuild this message's blocks from its final content. */
    private fun assistantEnd(seq: Long, ts: Long, message: JsonObject): ConversationState {
        val active = active ?: ActiveMessage("m$seq")
        var state = copy(active = active)
        val content = message.arr("content") ?: JsonArray(emptyList())
        val keep = HashSet<String>()
        content.forEachIndexed { index, element ->
            val block = element as? JsonObject ?: return@forEachIndexed
            val current = state.active ?: active
            when (block.str("type")) {
                "text" -> {
                    val text = block.str("text")
                    if (text.isBlank()) return@forEachIndexed
                    val key = current.blocks[index] ?: "${active.key}#$index"
                    keep += key
                    state = state.updateOrAdd(key, current, index, AssistantText(key, clip(text), streaming = false)) {
                        (it as? AssistantText)?.copy(text = clip(text), streaming = false)
                    }
                }
                "thinking" -> {
                    val text = block.str("thinking")
                    val redacted = block.bool("redacted") == true
                    if (text.isBlank() && !redacted) return@forEachIndexed
                    val key = current.blocks[index] ?: "${active.key}#$index"
                    keep += key
                    state = state.updateOrAdd(key, current, index, Reasoning(key, clip(text), false, ts, ts, redacted)) {
                        (it as? Reasoning)?.copy(text = clip(text), streaming = false, endedAt = it.endedAt ?: ts, redacted = redacted)
                    }
                }
                "toolCall" -> {
                    val id = block.str("id").ifBlank { return@forEachIndexed }
                    val key = toolKey(id)
                    keep += key
                    state = state.copy(active = current.copy(blocks = current.blocks + (index to key)))
                    state = state.upsertTool(key) { tool ->
                        (tool ?: ToolCall(key, id, block.str("name"), startedAt = ts)).copy(
                            name = block.str("name").ifBlank { tool?.name.orEmpty() },
                            arguments = block.obj("arguments") ?: tool?.arguments,
                            argumentsDraft = "",
                            phase = if (tool == null || tool.phase == ToolPhase.Composing) ToolPhase.Pending else tool.phase,
                        )
                    }
                }
            }
        }
        // Streamed blocks the final message does not contain (e.g. an empty text block) go away.
        val prefix = "${active.key}#"
        val items = state.items.filter { !(it.key.startsWith(prefix) && it.key !in keep) }
            .map { item ->
                if (item is Reasoning && item.key.startsWith(prefix) && item.endedAt == null) item.copy(endedAt = ts) else item
            }
        state = state.copy(items = items, active = null)
        return when (message.str("stopReason")) {
            "error" -> state.notice(seq, NoticeTone.Error, "模型返回了错误", message.str("errorMessage").ifBlank { null })
            "aborted" -> state.notice(seq, NoticeTone.Info, "已停止")
            "length" -> state.notice(seq, NoticeTone.Warning, "回复达到输出上限被截断")
            else -> state
        }
    }

    private fun toolStart(ts: Long, e: JsonObject): ConversationState {
        val id = e.str("toolCallId").ifBlank { return this }
        return upsertTool(toolKey(id)) { tool ->
            (tool ?: ToolCall(toolKey(id), id, e.str("toolName"))).copy(
                name = e.str("toolName").ifBlank { tool?.name.orEmpty() },
                arguments = tool?.arguments ?: e.obj("args"),
                argumentsDraft = "",
                phase = ToolPhase.Running,
                startedAt = ts,
            )
        }
    }

    private fun toolUpdate(e: JsonObject): ConversationState {
        val id = e.str("toolCallId").ifBlank { return this }
        val partial = e.obj("partialResult")
        return upsertTool(toolKey(id)) { tool ->
            (tool ?: ToolCall(toolKey(id), id, e.str("toolName"))).copy(
                arguments = tool?.arguments ?: e.obj("args"),
                output = partial?.let(::resultText)?.let(::clip) ?: tool?.output.orEmpty(),
                phase = if (tool?.finished == true) tool.phase else ToolPhase.Running,
            )
        }
    }

    private fun toolEnd(ts: Long, e: JsonObject): ConversationState {
        val id = e.str("toolCallId").ifBlank { return this }
        val result = e.obj("result")
        val structured = result?.obj("structuredContent")
        val failed = e.bool("isError") == true
        return upsertTool(toolKey(id)) { tool ->
            (tool ?: ToolCall(toolKey(id), id, e.str("toolName"), startedAt = ts)).copy(
                name = e.str("toolName").ifBlank { tool?.name.orEmpty() },
                argumentsDraft = "",
                output = clip(result?.let(::resultText) ?: tool?.output.orEmpty()),
                phase = if (failed) ToolPhase.Failed else ToolPhase.Succeeded,
                exitCode = structured?.int("exit_code") ?: tool?.exitCode,
                truncated = structured?.bool("truncated") == true,
                endedAt = ts,
            )
        }
    }

    private fun compactionEnd(seq: Long, e: JsonObject): ConversationState {
        val result = e.obj("result")
        val before = result?.long("tokensBefore")
        val after = result?.long("estimatedTokensAfter")
        val aborted = result == null && e.bool("aborted") == true
        val error = if (result == null && !aborted) e.str("errorMessage").ifBlank { "Agent 未说明原因" } else null
        val commandOnly = result?.bool("commandOnly") == true
        val feedback = result?.str("summary")?.takeIf { commandOnly && it.isNotBlank() }?.take(1600)
        val done = copy(compacting = false, compaction = CompactionOutcome(seq, before, after, error, aborted, commandOnly, feedback))
        return when {
            result != null -> done.notice(
                seq,
                NoticeTone.Divider,
                if (commandOnly) "原生压缩命令已完成" else "上下文已压缩",
                if (commandOnly) feedback else if (before != null && after != null) {
                    listOfNotNull("${tokens(before)} → ${tokens(after)} tokens", compactionDelta(before, after)).joinToString(" · ")
                } else null,
            )
            aborted -> done
            else -> done.notice(seq, NoticeTone.Error, "压缩失败", e.str("errorMessage").ifBlank { null })
        }
    }

    private fun response(e: JsonObject): ConversationState {
        val id = e.str("id")
        val ok = e.bool("success") == true
        val echo = localKey(id)
        return when (e.str("command")) {
            "get_state" -> {
                val data = e.obj("data") ?: return this
                val model = data.obj("model")
                (model?.let(::withModel) ?: this).copy(
                    agentProvider = data.str("agentProvider").ifBlank { agentProvider },
                    thinkingLevel = data.str("thinkingLevel").ifBlank { null } ?: thinkingLevel,
                    sessionName = data.str("sessionName").ifBlank { null },
                    sessionId = data.str("sessionId").ifBlank { null } ?: sessionId,
                    running = if (data.bool("isStreaming") == true) true else running,
                )
            }
            "set_model" -> if (ok) e.obj("data")?.let(::withModel) ?: this else this
            "get_available_models" -> if (!ok) this else copy(
                models = e.obj("data")?.arr("models").orEmpty().mapNotNull { m ->
                    val o = m as? JsonObject ?: return@mapNotNull null
                    val id = o.str("id").ifBlank { return@mapNotNull null }
                    ModelChoice(id, o.str("name").ifBlank { id }, o.str("provider"), o.bool("reasoning") == true)
                },
            )
            "get_available_thinking_levels" -> if (!ok) this else copy(
                thinkingLevels = e.obj("data")?.arr("levels").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
            "prompt", "steer", "follow_up" -> when {
                items.none { it.key == echo } -> this
                !ok -> dropLocalEcho(id)
                e.obj("data")?.str("disposition") == "handled" -> dropLocalEcho(id)
                e.obj("data")?.str("disposition") == "queued" ->
                    copy(items = items.map { if (it.key == echo && it is UserTurn) it.copy(delivery = Delivery.Queued) else it })
                else -> this
            }
            else -> this
        }
    }

    private fun extensionNotice(key: String, text: String): ConversationState = copy(
        items = bounded(items.filterNot { it.key == "extension:${key.take(160)}" } + if (text.isBlank()) emptyList() else listOf(Notice("extension:${key.take(160)}", NoticeTone.Info, text.take(1600)))),
    )

    private fun withModel(model: JsonObject): ConversationState = copy(
        model = model.str("name").ifBlank { model.str("id") }.ifBlank { null } ?: this.model,
        modelId = model.str("id").ifBlank { null } ?: modelId,
        modelProvider = model.str("provider").ifBlank { null } ?: modelProvider,
    )

    // ---- item helpers ----------------------------------------------------------------

    private fun existing(key: String): ConversationItem? = items.lastOrNull { it.key == key }

    private fun indexOf(key: String): Int {
        for (i in items.indices.reversed()) if (items[i].key == key) return i
        return -1
    }

    private fun replaceAt(index: Int, item: ConversationItem): ConversationState =
        copy(items = items.toMutableList().also { it[index] = item })

    private fun startBlock(active: ActiveMessage, index: Int, key: String, ts: Long, item: ConversationItem): ConversationState {
        // A new block ends any reasoning still open in this message.
        val closed = items.map {
            if (it is Reasoning && it.endedAt == null && it.key.startsWith("${active.key}#")) it.copy(streaming = false, endedAt = ts) else it
        }
        val withItem = if (closed.any { it.key == key }) closed else bounded(closed + item)
        return copy(items = withItem, active = active.copy(blocks = active.blocks + (index to key)))
    }

    private fun update(key: String, transform: (ConversationItem) -> ConversationItem?): ConversationState {
        val at = indexOf(key)
        if (at < 0) return this
        val next = transform(items[at]) ?: return this
        return if (next === items[at]) this else replaceAt(at, next)
    }

    private fun updateOrAdd(
        key: String,
        active: ActiveMessage,
        index: Int,
        seed: ConversationItem,
        transform: (ConversationItem) -> ConversationItem?,
    ): ConversationState {
        val at = indexOf(key)
        if (at >= 0) return update(key, transform)
        val created = transform(seed) ?: seed
        return copy(items = bounded(items + created), active = active.copy(blocks = active.blocks + (index to key)))
    }

    private fun upsertTool(key: String, transform: (ToolCall?) -> ToolCall): ConversationState {
        val at = indexOf(key)
        return if (at >= 0) {
            replaceAt(at, transform(items[at] as? ToolCall))
        } else {
            copy(items = bounded(items + transform(null)))
        }
    }

    private fun notice(seq: Long, tone: NoticeTone, title: String, detail: String? = null): ConversationState {
        val key = "n$seq"
        if (items.any { it.key == key }) return this
        return copy(items = bounded(items + Notice(key, tone, title, detail?.let(::clip))))
    }

    private fun bounded(next: List<ConversationItem>): List<ConversationItem> =
        if (next.size <= MAX_ITEMS) next else next.takeLast(MAX_ITEMS)

    companion object {
        const val MAX_ITEMS = 1500
        const val MAX_TEXT = 200_000

        fun localKey(id: String) = "local:$id"
        fun toolKey(id: String) = "tool:$id"
    }
}

private fun clip(text: String): String =
    if (text.length <= ConversationState.MAX_TEXT) text else text.take(ConversationState.MAX_TEXT) + "\n…"

private fun tokens(count: Long): String = if (count >= 1000) "${count / 1000}k" else count.toString()

internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal class UserContent(val text: String, val images: Int, val skill: SkillBlock?)

/**
 * User content is a string or text/image blocks. The skill wrapper is recognised on the raw
 * text, before clipping, so a long skill never loses its closing tag to the display bound.
 */
internal fun userContent(content: JsonElement?): UserContent {
    val (raw, images) = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty() to 0
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
            .joinToString("\n") to content.count { (it as? JsonObject)?.str("type") == "image" }
        else -> "" to 0
    }
    return UserContent(clip(raw), images, parseSkillBlock(raw))
}

private val SkillHead = Regex("""^<skill name="([^"]+)" location="([^"]+)">\n""")
private const val SKILL_CLOSE = "\n</skill>"

/**
 * Exactly Pi's whole-text match `^<skill …>\n(body)\n</skill>(?:\n\n(request))?$`, without
 * regex backtracking over a large body; anything else (a quoted example, a truncated wrapper)
 * stays an ordinary message.
 */
internal fun parseSkillBlock(text: String): SkillBlock? {
    val head = SkillHead.find(text) ?: return null
    val start = head.range.last + 1
    var close = text.indexOf(SKILL_CLOSE, start)
    while (close >= 0) {
        val tail = close + SKILL_CLOSE.length
        val request = when {
            tail == text.length -> ""
            text.startsWith("\n\n", tail) && text.length > tail + 2 -> text.substring(tail + 2)
            else -> null
        }
        if (request != null) {
            val body = text.substring(start, close)
            // Pi prefixes the body with where its references resolve; the card shows the location itself.
            val content = body.replaceFirst(SkillPreamble, "")
            return SkillBlock(head.groupValues[1], head.groupValues[2], clip(content), request.trim().ifEmpty { null }?.let(::clip))
        }
        close = text.indexOf(SKILL_CLOSE, close + 1)
    }
    return null
}

private val SkillPreamble = Regex("""^References are relative to [^\n]*\n\n?""")

/** Tool result text: text blocks, else a structured "output" field. */
internal fun resultText(result: JsonObject): String {
    val text = result.arr("content")?.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
        ?.joinToString("\n").orEmpty()
    if (text.isNotEmpty()) return text
    return result.obj("structuredContent")?.str("output").orEmpty()
}
