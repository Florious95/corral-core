/*
 * Copyright 2026 AgentMirror Project Authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package dev.agentmirror.app.input

import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.InputAckFrame
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.conversation.ConversationCodec
import dev.agentmirror.app.diag.DiagLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observes the pinned Core artifact's actual input writes and raw ACKs; no Core fork or guessed
 * req_id. Each physical socket owns a Link, so late/duplicate/wrong-link ACKs cannot release
 * another generation. Binary terminal frames and non-input messages pass through unchanged.
 * @consumes dev.agentmirror.app — pinned Maven conn/protocol types share the app namespace
 * @consumes dev.agentmirror.app.conversation — existing envelope-head classifier, no second socket
 * @consumes dev.agentmirror.app.diag — bounded, redacted failure diagnostics
 * @inv subscription explicitly declares mobile; only a received input_ack can drain motion
 */
internal class TouchInputTransport(
    private val inner: WebSocketTransport,
    private val inputs: TouchInputDispatcher,
) : WebSocketTransport {
    private val terminated = AtomicBoolean(false)
    private var listener: TransportListener? = null
    private val link = inputs.newLink(::failTransport)

    private fun failTransport(reason: String) {
        if (terminated.compareAndSet(false, true)) {
            inputs.closed(link)
            DiagLog.recordCritical("touch-input", reason)
            inner.close("input_backpressure_failed")
            listener?.onFailure(IOException(reason))
        }
    }

    override val isOpen: Boolean get() = inner.isOpen && !terminated.get()

    override fun start(listener: TransportListener) {
        this.listener = listener
        inner.start(object : TransportListener {
            override fun onOpen() = listener.onOpen()
            override fun onBinary(bytes: ByteArray) = listener.onBinary(bytes)
            override fun onText(text: String) {
                listener.onText(text) // Core resolves its own request before the UI drain is posted.
                if (wireType(text) != "input_ack") return
                // Same installed protocol validator as Core: malformed/version-mismatched
                // envelopes are not acknowledgements even if they contain a matching number.
                val ack = runCatching { FrameCodec.decode(text) as? InputAckFrame }.getOrNull() ?: return
                inputs.acknowledged(link, ack.reqId, ack.ok)
            }
            override fun onClosed(code: Int, reason: String) {
                inputs.closed(link)
                if (terminated.compareAndSet(false, true)) listener.onClosed(code, reason)
            }
            override fun onFailure(throwable: Throwable) {
                inputs.closed(link)
                if (terminated.compareAndSet(false, true)) listener.onFailure(throwable)
            }
        })
    }

    override fun sendText(text: String): Boolean {
        if (terminated.get()) return false
        if (!inputs.permits(link)) {
            failTransport("queued action socket generation rejected")
            return false
        }
        var outbound = text
        when (wireType(text)) {
            "input" -> {
                val id = (payload(text)?.get("req_id") as? JsonPrimitive)?.longOrNull ?: return false
                if (!inputs.outbound(link, id)) {
                    failTransport("input socket generation/correlation rejected req_id=$id")
                    return false
                }
            }
            "conversation_command" -> {
                val body = payload(text)
                val command = body?.get("command") as? JsonObject
                if ((command?.get("type") as? JsonPrimitive)?.content == "switch_mode") {
                    val ref = (body?.get("ref") as? JsonPrimitive)?.content ?: return false
                    if (!inputs.modeSwitchAllowed(ref)) return false
                }
            }
            "list" -> if (!inputs.selected(link)) return false
            "subscribe" -> {
                if (!inputs.selected(link)) return false
                val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
                val payload = root["payload"] as? JsonObject ?: return false
                val ref = (payload["ref"] as? JsonPrimitive)?.content ?: return false
                val cancelled = inputs.reacquired(ref)
                if (cancelled > 0) DiagLog.recordCritical("touch-input", "ref_reacquired ref=$ref stale_teardown=$cancelled preserved_input=true")
                outbound = JsonObject(root + ("payload" to JsonObject(payload + ("client_type" to JsonPrimitive("mobile"))))).toString()
            }
        }
        val sent = inner.sendText(outbound)
        if (!sent && wireType(text) == "input") {
            (payload(text)?.get("req_id") as? JsonPrimitive)?.longOrNull?.let { inputs.failed(it, "write_failed") }
        }
        return sent
    }

    override fun sendBinary(bytes: ByteArray): Boolean = !terminated.get() && inner.sendBinary(bytes)
    override fun close(reason: String) {
        inputs.closed(link)
        inner.close(reason)
    }

    private fun wireType(text: String): String? = ConversationCodec.frameType(text) ?: runCatching {
        (Json.parseToJsonElement(text).jsonObject["type"] as? JsonPrimitive)?.content
    }.getOrNull()

    private fun payload(text: String): JsonObject? =
        runCatching { Json.parseToJsonElement(text).jsonObject["payload"] as? JsonObject }.getOrNull()
}
