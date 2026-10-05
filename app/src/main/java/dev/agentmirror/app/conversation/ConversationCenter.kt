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

import android.os.Handler
import android.os.Looper
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.diag.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/*
 * conversation_v1 client (docs/contracts/06). Rides the persistent ConnectionManager socket
 * through a transport decorator, exactly like notifications_v1: no second WebSocket, so routing
 * (LAN/tailnet), 15 s ping, foreground self-heal and reconnect are inherited for free.
 *
 * Threading: the OkHttp reader thread only classifies a frame by its type prefix and queues it.
 * Parsing, reducing and timeouts run on one hub thread; UI reads StateFlows and receives
 * callbacks on the main thread. Nothing here can block the main thread.
 */

enum class ConversationSupport { Unknown, Supported, Unsupported }

/** Session stream phase as the conversation screen shows it. */
enum class LinkPhase { Connecting, Live, Reconnecting, Ended, Unavailable }

/** What the managed pane runs: the structured agent, or Pi's own TUI on the same session. */
enum class PaneMode { Rpc, Tui }

data class SlashCommand(val name: String, val description: String, val source: String)

/** One managed conversation. State survives screen exits so re-entry renders instantly. */
class ConversationSession internal constructor(val ref: String) {
    internal val mutableState = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = mutableState.asStateFlow()

    internal val mutablePhase = MutableStateFlow(LinkPhase.Connecting)
    val phase: StateFlow<LinkPhase> = mutablePhase.asStateFlow()

    internal val mutableCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val commands: StateFlow<List<SlashCommand>> = mutableCommands.asStateFlow()

    /** Server-acknowledged pane mode (ready + worker_mode); never inferred from a disconnect. */
    internal val mutableMode = MutableStateFlow(PaneMode.Rpc)
    val mode: StateFlow<PaneMode> = mutableMode.asStateFlow()

    /**
     * Once a stream was ready, this ref is proven structured for the process lifetime: a later
     * disconnect is a reconnect state, never a silent switch to the terminal (dossier §7–8).
     */
    @Volatile
    var everReady: Boolean = false
        internal set

    // Hub-thread only.
    internal var attachCount = 0
    internal var subscribed = false
    internal var lostRetries = 0
    internal var resubscribe: ScheduledFuture<*>? = null
    internal var commandsRequested = false
    internal var lastUse = 0L
}

class ConversationHub(
    private val executor: ScheduledExecutorService,
    private val mainPost: (Runnable) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** One physical connection; [send] writes straight to that socket. */
    class Link internal constructor(internal val send: (String) -> Boolean) {
        @Volatile internal var negotiated = false
    }

    private class PendingCommand(val ref: String, val id: String, val echo: Boolean, val onResult: ((Boolean, String?, JsonObject?) -> Unit)?, val timeout: ScheduledFuture<*>)
    private class PendingCreate(val onResult: (Boolean, String?, String?) -> Unit, val timeout: ScheduledFuture<*>)

    private val _support = MutableStateFlow(ConversationSupport.Unknown)
    val support: StateFlow<ConversationSupport> = _support.asStateFlow()

    private val _refs = MutableStateFlow<Set<String>>(emptySet())
    /** Refs the host marked as managed conversations (listing marker + local creations). */
    val conversationRefs: StateFlow<Set<String>> = _refs.asStateFlow()

    private val sessions = ConcurrentHashMap<String, ConversationSession>()
    private val inbound = ConcurrentLinkedQueue<Pair<Link, String>>()
    private val drainScheduled = AtomicBoolean(false)
    private val ids = AtomicLong(0)
    private val pendingCommands = HashMap<String, PendingCommand>()
    private val pendingCreates = HashMap<Long, PendingCreate>()
    @Volatile private var link: Link? = null

    fun session(ref: String): ConversationSession =
        sessions.getOrPut(ref) { ConversationSession(ref) }.also { trimSessions(keep = ref) }

    fun isConversation(ref: String): Boolean = ref in _refs.value

    fun newLink(send: (String) -> Boolean): Link = Link(send)

    // ---- transport callbacks (OkHttp reader thread) -------------------------------------

    fun onAuthAck(link: Link, text: String) = executor.execute {
        val payload = runCatching { Json.parseToJsonElement(text).jsonObject.obj("payload") }.getOrNull() ?: return@execute
        if (payload.bool("ok") != true) return@execute
        val caps = payload.arr("capabilities")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (ConversationCodec.CAPABILITY !in caps) {
            _support.value = ConversationSupport.Unsupported
            sessions.values.filter { it.attachCount > 0 && !it.everReady }.forEach { it.mutablePhase.value = LinkPhase.Unavailable }
            return@execute
        }
        link.negotiated = true
        this.link = link
        _support.value = ConversationSupport.Supported
        sessions.values.filter { it.attachCount > 0 }.forEach { subscribe(it) }
    }

    fun onFrame(link: Link, text: String) {
        inbound.add(link to text)
        if (drainScheduled.compareAndSet(false, true)) executor.execute(::drain)
    }

    fun onListing(text: String) = executor.execute {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@execute
        val payload = root.obj("payload") ?: return@execute
        when (root.str("type")) {
            "listing" -> {
                val marked = payload.arr("workspaces").orEmpty().flatMap { ws ->
                    (ws as? JsonObject)?.arr("sessions").orEmpty().mapNotNull(::markedRef)
                }
                _refs.value = marked.toSet()
            }
            "list_delta" -> {
                val added = (payload.arr("added_sessions").orEmpty() + payload.arr("changed_sessions").orEmpty())
                val marked = added.mapNotNull(::markedRef)
                val unmarked = added.mapNotNull { s -> (s as? JsonObject)?.takeIf { it.bool("conversation") != true }?.str("ref") }
                val removed = payload.arr("removed_refs").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                _refs.update { it + marked - unmarked.toSet() - removed.toSet() }
            }
            "level2_frame" -> _refs.update { it + payload.arr("sessions").orEmpty().mapNotNull(::markedRef) }
        }
    }

    fun onClosed(link: Link) = executor.execute {
        link.negotiated = false
        if (this.link !== link) return@execute
        this.link = null
        sessions.values.forEach { s ->
            s.subscribed = false
            s.resubscribe?.cancel(false)
            if (s.attachCount > 0) s.mutablePhase.value = if (s.everReady) LinkPhase.Reconnecting else LinkPhase.Connecting
        }
        pendingCommands.values.toList().forEach { finishCommand(it, false, "Connection lost before the host confirmed") }
        pendingCreates.values.toList().forEach { it.timeout.cancel(false) }
        val creates = pendingCreates.values.toList()
        pendingCreates.clear()
        creates.forEach { p -> mainPost { p.onResult(false, null, "连接中断，请重试") } }
    }

    // ---- UI entry points (any thread) ----------------------------------------------------

    fun attach(ref: String) = executor.execute {
        val s = session(ref)
        s.attachCount++
        s.lastUse = nowMs()
        when {
            _support.value == ConversationSupport.Unsupported && !s.everReady -> s.mutablePhase.value = LinkPhase.Unavailable
            link != null && !s.subscribed -> subscribe(s)
            link == null -> s.mutablePhase.value = if (s.everReady) LinkPhase.Reconnecting else LinkPhase.Connecting
        }
    }

    fun detach(ref: String) = executor.execute {
        val s = sessions[ref] ?: return@execute
        s.attachCount = (s.attachCount - 1).coerceAtLeast(0)
        s.lastUse = nowMs()
        if (s.attachCount > 0) return@execute
        s.resubscribe?.cancel(false)
        if (s.subscribed) {
            s.subscribed = false
            link?.send?.invoke(ConversationCodec.unsubscribe(ref))
        }
    }

    /** Manual "reconnect" from the inline banner. */
    fun retry(ref: String) = executor.execute {
        val s = sessions[ref] ?: return@execute
        s.lostRetries = 0
        if (link != null && s.attachCount > 0) subscribe(s)
    }

    /**
     * Sends one agent command. Prompts get an optimistic echo so the bubble lands on the same
     * frame as the tap; the host's `response` (or a 15 s deadline) settles [onResult].
     */
    fun send(
        ref: String,
        kind: String,
        message: String? = null,
        attachmentPaths: List<String> = emptyList(),
        streamingBehavior: String? = null,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) = executor.execute {
        val s = session(ref)
        val l = link
        if (l == null || s.mutablePhase.value != LinkPhase.Live) {
            onResult?.let { cb -> mainPost { cb(false, "Not connected") } }
            return@execute
        }
        val id = "c${ids.incrementAndGet()}"
        val echo = message != null && (kind == "prompt" || kind == "steer" || kind == "follow_up")
        if (echo) s.mutableState.update { it.withLocalEcho(id, message!!, attachmentPaths.size, nowMs()) }
        val command = buildJsonObject {
            put("type", kind)
            if (message != null) put("message", message)
            if (streamingBehavior != null) put("streamingBehavior", streamingBehavior)
            if (attachmentPaths.isNotEmpty()) putJsonArray("attachment_paths") { attachmentPaths.forEach { add(JsonPrimitive(it)) } }
        }
        dispatch(l, ref, id, command, echo, onResult?.let { cb -> { ok, reason, _ -> cb(ok, reason) } })
    }

    /**
     * A non-prompt agent command with its own fields (set_model, set_thinking_level, …): same
     * correlation id, 15 s deadline and main-thread [onResult] as [send], no echo.
     */
    fun control(ref: String, command: JsonObject, onResult: ((Boolean, String?) -> Unit)? = null) =
        controlWithData(ref, command, onResult?.let { cb -> { ok, reason, _ -> cb(ok, reason) } })

    /** [control] whose callback also receives the response's data object (e.g. switch_mode's busy). */
    fun controlWithData(ref: String, command: JsonObject, onResult: ((Boolean, String?, JsonObject?) -> Unit)?) = executor.execute {
        val s = session(ref)
        val l = link
        if (l == null || s.mutablePhase.value != LinkPhase.Live) {
            onResult?.let { cb -> mainPost { cb(false, "Not connected", null) } }
            return@execute
        }
        dispatch(l, ref, "c${ids.incrementAndGet()}", command, false, onResult)
    }

    private fun dispatch(l: Link, ref: String, id: String, command: JsonObject, echo: Boolean, onResult: ((Boolean, String?, JsonObject?) -> Unit)?) {
        val timeout = executor.schedule({
            pendingCommands[id]?.let { finishCommand(it, false, "The host did not confirm in time") }
        }, COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val pending = PendingCommand(ref, id, echo, onResult, timeout)
        pendingCommands[id] = pending
        if (!l.send(ConversationCodec.command(ref, id, command))) finishCommand(pending, false, "Not connected")
    }

    /**
     * Asks the pane's worker to run Pi's TUI ([PaneMode.Tui]) or the structured agent on the same
     * session. [onResult] gets (ok, reason, busy): busy means work is in flight and the user must
     * consent ([force]) to drop it. The mode itself moves only when the worker confirms.
     */
    fun switchMode(ref: String, mode: PaneMode, force: Boolean, onResult: (Boolean, String?, Boolean) -> Unit) {
        val command = buildJsonObject {
            put("type", "switch_mode")
            put("mode", if (mode == PaneMode.Tui) "tui" else "rpc")
            put("force", force)
        }
        controlWithData(ref, command) { ok, reason, data ->
            if (ok) sessions[ref]?.mutableMode?.value = mode
            onResult(ok, reason, data?.bool("busy") == true)
        }
    }

    /** conversation_create; [onResult] gets (ok, ref, reason) on the main thread. */
    fun create(workspace: String, anchorRef: String, provider: String, name: String, onResult: (Boolean, String?, String?) -> Unit): Boolean {
        val l = link ?: return false
        val reqId = ids.incrementAndGet() % Int.MAX_VALUE + 1
        executor.execute {
            val timeout = executor.schedule({
                pendingCreates.remove(reqId)?.let { mainPost { it.onResult(false, null, "主机未在时限内确认") } }
            }, CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            pendingCreates[reqId] = PendingCreate(onResult, timeout)
            if (!l.send(ConversationCodec.create(reqId, workspace, anchorRef, provider, name))) {
                timeout.cancel(false)
                pendingCreates.remove(reqId)
                mainPost { onResult(false, null, "创建请求发送失败") }
            }
        }
        return true
    }

    // ---- hub thread ------------------------------------------------------------------------

    private fun subscribe(s: ConversationSession) {
        val l = link ?: return
        s.resubscribe?.cancel(false)
        val state = s.mutableState.value
        if (l.send(ConversationCodec.subscribe(s.ref, state.stream, state.lastSeq))) {
            s.subscribed = true
            if (!s.everReady) s.mutablePhase.value = LinkPhase.Connecting
        }
    }

    private fun drain() {
        drainScheduled.set(false)
        // Group this burst per session so a token storm costs one state publication.
        val batches = LinkedHashMap<ConversationSession, MutableList<Triple<Long, Long, JsonObject>>>()
        // Command callbacks run after the burst is reduced, so a caller sees the state its own
        // response produced (a confirmed model, a clamped thinking level), never the one before.
        val settled = ArrayList<() -> Unit>()
        while (true) {
            val (from, text) = inbound.poll() ?: break
            if (from !== link) continue
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val payload = root.obj("payload") ?: continue
            val ref = payload.str("ref")
            when (root.str("type")) {
                "conversation_event" -> {
                    val s = sessions[ref] ?: continue
                    val event = payload.obj("event") ?: continue
                    if (event.str("type") == "response") onResponse(s, event)?.let(settled::add)
                    if (event.str("type") == "worker_mode") s.mutableMode.value = paneMode(event.str("mode"))
                    // A new session or a resumed process: header facts come from Pi again.
                    if (event.str("type") == "session_reset") settled += { internalCommand(s, "get_state") }
                    batches.getOrPut(s) { ArrayList() }.add(Triple(payload.long("seq") ?: 0, payload.long("ts") ?: 0, event))
                }
                "conversation_ready" -> {
                    batches.remove(sessions[ref])?.let { flush(sessions[ref]!!, it) }
                    onReady(ref, payload)
                }
                "conversation_closed" -> {
                    batches.remove(sessions[ref])?.let { flush(sessions[ref]!!, it) }
                    onStreamClosed(ref, payload.str("reason"))
                }
                "conversation_created" -> onCreated(payload)
            }
        }
        batches.forEach { (s, events) -> flush(s, events) }
        settled.forEach { it() }
    }

    private fun flush(s: ConversationSession, events: List<Triple<Long, Long, JsonObject>>) {
        val merged = coalesceDeltas(events)
        s.mutableState.update { state -> merged.fold(state) { acc, (seq, ts, e) -> acc.apply(seq, ts, e) } }
    }

    private fun onReady(ref: String, payload: JsonObject) {
        val s = sessions[ref] ?: return
        if (s.attachCount == 0) return
        val stream = payload.str("stream")
        s.mutableState.update { state ->
            val base = if (payload.bool("reset") == true || state.stream != stream) {
                state.reset(stream, payload.bool("history_truncated") == true)
            } else {
                state
            }
            base.copy(
                running = payload.bool("running") == true,
                serverSkewMs = (payload.long("server_time_ms") ?: nowMs()) - nowMs(),
            )
        }
        s.everReady = true
        s.lostRetries = 0
        s.mutableMode.value = paneMode(payload.str("mode"))
        s.mutablePhase.value = LinkPhase.Live
        _refs.update { it + ref }
        DiagLog.record("conversation", "ready ref_hash=${ref.hashCode()} stream=$stream head=${payload.long("head_seq")} reset=${payload.bool("reset")}")
        internalCommand(s, "get_state")
        if (!s.commandsRequested) {
            s.commandsRequested = true
            internalCommand(s, "get_commands")
        }
    }

    private fun onStreamClosed(ref: String, reason: String) {
        val s = sessions[ref] ?: return
        s.subscribed = false
        DiagLog.record("conversation", "closed ref_hash=${ref.hashCode()} reason=$reason ever_ready=${s.everReady} attached=${s.attachCount}")
        if (s.attachCount == 0) return
        when (reason) {
            "lost" -> {
                s.mutablePhase.value = if (s.everReady) LinkPhase.Reconnecting else LinkPhase.Connecting
                val delay = (RESUBSCRIBE_BASE_MS shl s.lostRetries.coerceAtMost(4)).coerceAtMost(RESUBSCRIBE_MAX_MS)
                s.lostRetries++
                s.resubscribe = executor.schedule({ if (s.attachCount > 0 && link != null) subscribe(s) }, delay, TimeUnit.MILLISECONDS)
            }
            else -> s.mutablePhase.value = if (s.everReady) LinkPhase.Ended else LinkPhase.Unavailable
        }
    }

    private fun onCreated(payload: JsonObject) {
        val reqId = payload.long("req_id") ?: return
        val pending = pendingCreates.remove(reqId) ?: return
        pending.timeout.cancel(false)
        val ok = payload.bool("ok") == true
        val ref = payload.str("ref").ifBlank { null }
        if (ok && ref != null) _refs.update { it + ref }
        mainPost { pending.onResult(ok, ref, payload.str("reason").ifBlank { null }) }
    }

    private fun onResponse(s: ConversationSession, event: JsonObject): (() -> Unit)? {
        val id = event.str("id")
        if (event.str("command") == "get_commands" && event.bool("success") == true) {
            s.mutableCommands.value = event.obj("data")?.arr("commands").orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                SlashCommand(o.str("name").ifBlank { return@mapNotNull null }, o.str("description"), o.str("source"))
            }
        }
        val pending = pendingCommands[id] ?: return null
        return { finishCommand(pending, event.bool("success") == true, event.str("error").ifBlank { null }, settleEcho = false, data = event.obj("data")) }
    }

    private fun finishCommand(pending: PendingCommand, ok: Boolean, reason: String?, settleEcho: Boolean = true, data: JsonObject? = null) {
        pendingCommands.remove(pending.id)
        pending.timeout.cancel(false)
        if (!ok && pending.echo && settleEcho) sessions[pending.ref]?.mutableState?.update { it.dropLocalEcho(pending.id) }
        pending.onResult?.let { cb -> mainPost { cb(ok, reason, data) } }
    }

    private fun internalCommand(s: ConversationSession, kind: String) {
        val l = link ?: return
        l.send(ConversationCodec.command(s.ref, "sys${ids.incrementAndGet()}", buildJsonObject { put("type", kind) }))
    }

    private fun trimSessions(keep: String) {
        if (sessions.size <= MAX_CACHED_SESSIONS) return
        sessions.values.filter { it.ref != keep && it.attachCount == 0 }
            .sortedBy { it.lastUse }
            .take(sessions.size - MAX_CACHED_SESSIONS)
            .forEach { sessions.remove(it.ref) }
    }

    private fun paneMode(wire: String) = if (wire == "tui") PaneMode.Tui else PaneMode.Rpc

    private fun markedRef(element: JsonElement): String? =
        (element as? JsonObject)?.takeIf { it.bool("conversation") == true }?.str("ref")?.ifBlank { null }

    companion object {
        const val COMMAND_TIMEOUT_MS = 15_000L
        const val CREATE_TIMEOUT_MS = 20_000L
        const val RESUBSCRIBE_BASE_MS = 300L
        const val RESUBSCRIBE_MAX_MS = 5_000L
        const val MAX_CACHED_SESSIONS = 8

        /** Adjacent deltas of one block collapse into one, so a burst copies the text once. */
        internal fun coalesceDeltas(events: List<Triple<Long, Long, JsonObject>>): List<Triple<Long, Long, JsonObject>> {
            if (events.size < 2) return events
            val out = ArrayList<Triple<Long, Long, JsonObject>>(events.size)
            for (current in events) {
                val prev = out.lastOrNull()
                val merged = prev?.let { mergeDelta(it.third, current.third) }
                if (merged != null) out[out.lastIndex] = Triple(current.first, current.second, merged) else out += current
            }
            return out
        }

        private fun mergeDelta(a: JsonObject, b: JsonObject): JsonObject? {
            if (a.str("type") != "message_update" || b.str("type") != "message_update") return null
            val ua = a.obj("assistantMessageEvent") ?: return null
            val ub = b.obj("assistantMessageEvent") ?: return null
            val kind = ua.str("type")
            if (!kind.endsWith("_delta") || kind != ub.str("type") || ua.int("contentIndex") != ub.int("contentIndex")) return null
            return JsonObject(a + ("assistantMessageEvent" to JsonObject(ua + ("delta" to JsonPrimitive(ua.str("delta") + ub.str("delta"))))))
        }
    }
}

/** Wire builders and frame classification for conversation_v1. */
internal object ConversationCodec {
    const val CAPABILITY = "conversation_v1"

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the envelope type from the head of a frame without parsing the payload. */
    fun frameType(text: String): String? {
        val at = text.indexOf("\"type\":\"")
        if (at < 0 || at > 48) return null
        val start = at + 8
        val end = text.indexOf('"', start)
        return if (end < 0 || end - start > 40) null else text.substring(start, end)
    }

    /** Outbound auth frames gain conversation_v1 next to any capability already declared. */
    fun withCapability(text: String): String {
        if (frameType(text) != "auth") return text
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text
        val payload = root.obj("payload") ?: return text
        val caps = payload.arr("capabilities")?.toList().orEmpty()
        if (caps.any { (it as? JsonPrimitive)?.content == CAPABILITY }) return text
        val decorated = JsonObject(payload + ("capabilities" to JsonArray(caps + JsonPrimitive(CAPABILITY))))
        return JsonObject(root + ("payload" to decorated)).toString()
    }

    fun subscribe(ref: String, stream: String?, afterSeq: Long): String = envelope("conversation_subscribe") {
        put("ref", ref)
        if (stream != null) {
            put("stream", stream)
            put("after_seq", afterSeq)
        }
    }

    fun unsubscribe(ref: String): String = envelope("conversation_unsubscribe") { put("ref", ref) }

    fun command(ref: String, id: String, command: JsonObject): String = envelope("conversation_command") {
        put("ref", ref)
        put("id", id)
        put("command", command)
    }

    fun create(reqId: Long, workspace: String, anchorRef: String, provider: String, name: String): String =
        envelope("conversation_create") {
            put("req_id", reqId)
            put("workspace", workspace)
            put("anchor_ref", anchorRef)
            put("provider", provider)
            put("name", name)
        }

    private fun envelope(type: String, payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("v", 1)
            put("type", type)
            putJsonObject("payload", payload)
        }.toString()
}

/**
 * Transport decorator on the persistent connection: declares conversation_v1, routes
 * conversation frames to the hub before Core sees them, and lets everything else through
 * byte-for-byte (Core ignores conversation markers inside listings).
 */
internal class ConversationTransport(
    private val inner: WebSocketTransport,
    private val hub: ConversationHub,
) : WebSocketTransport {
    override val isOpen: Boolean get() = inner.isOpen

    override fun start(listener: TransportListener) {
        val link = hub.newLink(inner::sendText)
        inner.start(object : TransportListener {
            override fun onOpen() = listener.onOpen()

            override fun onText(text: String) {
                val type = ConversationCodec.frameType(text)
                if (type != null && type.startsWith("conversation_")) {
                    hub.onFrame(link, text)
                    return
                }
                listener.onText(text)
                when (type) {
                    "auth_ack" -> hub.onAuthAck(link, text)
                    "listing" -> hub.onListing(text)
                    "list_delta", "level2_frame" -> if (text.contains("\"conversation\"") || text.contains("removed_refs")) hub.onListing(text)
                }
            }

            override fun onBinary(bytes: ByteArray) = listener.onBinary(bytes)

            override fun onClosed(code: Int, reason: String) {
                hub.onClosed(link)
                listener.onClosed(code, reason)
            }

            override fun onFailure(throwable: Throwable) {
                hub.onClosed(link)
                listener.onFailure(throwable)
            }
        })
    }

    override fun sendText(text: String): Boolean = inner.sendText(ConversationCodec.withCapability(text))

    override fun sendBinary(bytes: ByteArray): Boolean = inner.sendBinary(bytes)

    override fun close(reason: String) = inner.close(reason)
}

/** Process-wide entry point shared by the foreground service and UI. */
object ConversationCenter {
    val hub: ConversationHub by lazy {
        // Plain-JVM tests have no main looper; callbacks then run on the hub thread.
        val main = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
        ConversationHub(
            executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "conversation-hub").apply { isDaemon = true } },
            mainPost = { task -> if (main != null) main.post(task) else task.run() },
        )
    }

    /** Wraps only the persistent connection's factory (pairing probes do not go through here). */
    fun wrap(factory: TransportFactory): TransportFactory =
        TransportFactory { url -> ConversationTransport(factory.create(url), hub) }
}
