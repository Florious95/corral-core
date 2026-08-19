/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package dev.agentmirror.app.conn

import dev.agentmirror.app.session.AttachmentUploader
import dev.agentmirror.app.session.SessionViewModel
import dev.agentmirror.app.session.UploadOutcome
import dev.agentmirror.app.session.sessionSocketFromRef
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 070 A-wire-roundtrip ①：用 App 真正的 [FrameCodec] 把每一个 C→S 帧
 * 序列化成线上字节，落盘 app/wire-fixtures 下的 json。
 *
 * overlay_subscribe 走生产路径：[SessionViewModel.openOverlay] →
 * [sessionSocketFromRef] → [ConnectionManager.subscribeOverlay]，
 * 不手写 OverlaySubscribeFrame 再在 App 里断言（那是同语言闭环）。
 */
class WireFixtureExportTest {

    @Test
    fun exportAllClientToServerFrames() {
        val outDir = resolveOutDir()
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }

        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok-wire"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")

        // listing 金样里的 ref（无 U+001F）。现有悬浮窗单测也用这个。
        val listingRef = "s1"
        // 服务端 sessionRef = socket + U+001F + pane_id。先做成线上 listing JSON，
        // 再经 App FrameCodec 解出 Session.ref —— 这才是「列表点进会话」的真路径。
        val socketPath = "/tmp/tmux-1000/default"
        val listingWire = """{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[{"cwd":"/proj/a","session_count":1,"sessions":[{"ref":"$socketPath\\u001f%3","name":"claude","cwd":"/proj/a","rows":24,"cols":80}]}]}}"""
        val listing = FrameCodec.decode(listingWire) as ListingFrame
        val structuralRef = listing.workspaces[0].sessions[0].ref

        manager.list()
        manager.subscribe(listingRef, 24, 80)
        manager.sendInput(listingRef, "/model opus")
        manager.sendInputKeys(listingRef, InputKey.ESC)
        manager.scrollback(listingRef, -300, 100)
        manager.resize(listingRef, 48, 120)
        manager.sendScrollWheel(listingRef, -1)
        manager.sendAttachPreview(listingRef, "/host/img.png")
        manager.subscribeLevel2("/proj/a")
        manager.unsubscribeLevel2("/proj/a")
        manager.unsubscribe(listingRef)

        val extractedListing = sessionSocketFromRef(listingRef)
        val extractedStructural = sessionSocketFromRef(structuralRef)
        File(outDir, "overlay_subscribe.operands.txt").writeText(
            buildString {
                appendLine("listing_ref=${listingRef.toJs()}")
                appendLine("listing_sep=${listingRef.indexOf('\u001f')}")
                appendLine("listing_extracted=${extractedListing.toJs()}")
                appendLine("structural_ref=${structuralRef.toJs()}")
                appendLine("structural_sep=${structuralRef.indexOf('\u001f')}")
                appendLine("structural_extracted=${extractedStructural.toJs()}")
                appendLine("structural_ref_codepoints=${structuralRef.map { it.code }.joinToString(",")}")
            },
        )

        // 金样 listing token：现有悬浮窗单测 / testdata 的 ref=s1（无 U+001F）。
        val vmListing = SessionViewModel(
            manager = manager,
            uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("unused") },
            baseUrl = null,
            ref = listingRef,
            initialRows = 24,
            initialCols = 80,
        )
        manager.setListener(vmListing)
        val beforeListing = transport.sentText.size
        vmListing.openOverlay()
        val listingOverlay = transport.sentText.drop(beforeListing).lastOrNull {
            wireType(it) == "overlay_subscribe"
        }
        if (listingOverlay != null) {
            File(outDir, "overlay_subscribe_from_listing_token.json").writeText(listingOverlay)
        }
        vmListing.closeOverlay()

        // 服务端真 ref（listing JSON → App 解码 → openOverlay）。
        val vm = SessionViewModel(
            manager = manager,
            uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("unused") },
            baseUrl = null,
            ref = structuralRef,
            initialRows = 24,
            initialCols = 80,
        )
        val beforeOverlay = transport.sentText.size
        vm.openOverlay()
        val overlayWires = transport.sentText.drop(beforeOverlay)
        File(outDir, "overlay_subscribe.sent.txt").writeText(overlayWires.joinToString("\n"))
        vm.closeOverlay()

        val byType = linkedMapOf<String, String>()
        for (raw in transport.sentText) {
            val type = wireType(raw) ?: continue
            byType[type] = raw
        }
        // 生产路径发出的 overlay_subscribe 覆盖同 type 的最后一帧前值。
        // 以 listing ref 那次为准（用户真路径）。
        overlayWires.lastOrNull { wireType(it) == "overlay_subscribe" }?.let {
            byType["overlay_subscribe"] = it
        }

        val wantTypes = listOf(
            "auth",
            "list",
            "subscribe",
            "unsubscribe",
            "input",
            "scrollback",
            "resize",
            "scroll_wheel",
            "attach_preview",
            "level2_subscribe",
            "level2_unsubscribe",
            "overlay_subscribe",
            "overlay_unsubscribe",
        )
        val missing = wantTypes.filter { it !in byType }
        assertTrue(
            "C→S 帧未全部发出（encode 被拒或未走生产路径）: $missing sent=${byType.keys}",
            missing.isEmpty(),
        )
        for ((type, raw) in byType) {
            File(outDir, "$type.json").writeText(raw)
        }
        println("WIRE_EXPORT dir=${outDir.absolutePath} types=${byType.keys}")
        println("WIRE_EXPORT overlay_subscribe=${byType["overlay_subscribe"]}")
        println(
            "WIRE_EXPORT operands listing_extracted=${extractedListing.toJs()} " +
                "structural_extracted=${extractedStructural.toJs()}",
        )
    }

    private fun wireType(raw: String): String? {
        val m = Regex(""""type"\s*:\s*"([^"]+)"""").find(raw) ?: return null
        return m.groupValues[1]
    }

    private fun String.toJs(): String = buildString {
        append('"')
        for (ch in this@toJs) {
            when (ch) {
                '\u001f' -> append("\\u001f")
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun resolveOutDir(): File {
        val candidates = listOf(
            File("../wire-fixtures"),
            File("../../app/wire-fixtures"),
            File("app/wire-fixtures"),
        )
        return candidates.firstOrNull { it.parentFile?.exists() == true }
            ?: File("../wire-fixtures")
    }
}
