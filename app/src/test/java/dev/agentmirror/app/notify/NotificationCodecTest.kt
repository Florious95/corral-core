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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** notifications_v1 编解码：契约 §2.2 / §3 / §4 的样例逐字段对齐。 */
class NotificationCodecTest {

    @Test
    fun liveFrame_decodesContractExampleExactly() {
        val msg = NotificationCodec.peek(liveFrame()) as NotificationInbound.Live
        val r = msg.record!!
        assertEquals("720a303f-c883-445f-a3df-c316d901a065", r.id)
        assertEquals("host_0123456789abcdef", r.hostId)
        assertEquals("4e4ca540-df59-4b79-a285-7d1f7523c7ab", r.streamId)
        assertEquals("42", r.seq)
        assertEquals(42uL, r.seqNumber)
        assertEquals("2026-10-01T01:00:00.123Z", r.timestamp)
        assertEquals("验收完成", r.title)
        assertEquals("Issue #42 的契约与验收方案已完成。\n详见报告。", r.body)
        assertEquals("/tmp/tmux-501/team\u001f%3", r.sessionRef)
        assertEquals("pane-instance-example-01", r.sessionInstance)
        assertEquals("/work/project", r.workspace)
        assertEquals("Sol", r.agentName)
        assertEquals(NotificationLevel.Success, r.levelKind)
        assertEquals(NotificationKey("host_0123456789abcdef", "720a303f-c883-445f-a3df-c316d901a065"), r.key)
    }

    @Test
    fun liveFrame_nullableFieldsAndUnknownFieldsTolerated() {
        val text = """{"v":1,"type":"notification","payload":{"id":"a","host_id":"h","stream_id":"s","seq":"7",""" +
            """"timestamp":"2026-10-01T01:00:00.123Z","title":"t","body":"  多行\n\t正文  ","session_ref":null,""" +
            """"session_instance":null,"workspace":null,"agent_name":null,"level":"info","future":{"x":1}}}"""
        val r = (NotificationCodec.peek(text) as NotificationInbound.Live).record!!
        assertNull(r.sessionRef)
        assertNull(r.agentName)
        assertEquals("  多行\n\t正文  ", r.body)
        assertEquals(NotificationLevel.Info, r.levelKind)
    }

    @Test
    fun liveFrame_invalidRecordsAreRejectedNotForwarded() {
        for (bad in listOf(
            liveFrame(level = "fatal"),
            liveFrame(seq = "0"),
            liveFrame(seq = "042"),
            liveFrame(seq = "abc"),
            liveFrame(body = "   "),
            liveFrame(title = ""),
        )) {
            val msg = NotificationCodec.peek(bad)
            assertTrue("notification frames never go to Core: $bad", msg is NotificationInbound.Live)
            assertNull("invalid record must not enter storage: $bad", (msg as NotificationInbound.Live).record)
        }
    }

    @Test
    fun otherFrames_passThroughEvenWhenTheyMentionNotification() {
        assertNull(NotificationCodec.peek("""{"v":1,"type":"listing","payload":{"name":"notification-bot"}}"""))
        assertNull(NotificationCodec.peek("""{"v":1,"type":"input_ack","payload":{"req_id":1,"ok":true}}"""))
        assertNull(NotificationCodec.peek("not json but notification"))
    }

    @Test
    fun authAck_parsesNegotiatedCapabilityAndState() {
        val ack = NotificationCodec.peek(
            """{"v":1,"type":"auth_ack","payload":{"ok":true,"capabilities":["notifications_v1"],""" +
                """"notification_state":{"host_id":"host_0123456789abcdef","stream_id":"4e4ca540-df59-4b79-a285-7d1f7523c7ab",""" +
                """"head_seq":"41","retained_from_seq":"1"}}}""",
        ) as NotificationInbound.AuthAck
        assertTrue(ack.ok)
        assertEquals(listOf("notifications_v1"), ack.capabilities)
        assertEquals("host_0123456789abcdef", ack.state!!.hostId)
        assertEquals("41", ack.state!!.headSeq)
    }

    @Test
    fun authAck_oldServerHasNoCapabilities() {
        val ack = NotificationCodec.peek("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""") as NotificationInbound.AuthAck
        assertTrue(ack.ok)
        assertTrue(ack.capabilities.isEmpty())
        assertNull(ack.state)
    }

    @Test
    fun page_parsesItemsTokenAndDropsInvalidItems() {
        val page = (NotificationCodec.peek(pageFrame(reqId = 7, items = listOf(record("1"), record("0")), next = "tok")) as NotificationInbound.Page).page
        assertEquals(7L, page.reqId)
        assertTrue(page.ok)
        assertEquals(listOf("1"), page.records.map { it.seq })
        assertEquals("tok", page.nextPageToken)
        assertEquals(NotificationCursor("s", "43"), page.snapshotCursor)
    }

    @Test
    fun page_failureAndFinalPage() {
        val failed = (NotificationCodec.peek(
            """{"v":1,"type":"notifications_page","payload":{"req_id":8,"ok":false,"error":{"code":"invalid_page_token","reason":"x"}}}""",
        ) as NotificationInbound.Page).page
        assertFalse(failed.ok)
        assertTrue(failed.records.isEmpty())
        val last = (NotificationCodec.peek(pageFrame(reqId = 9, items = emptyList(), next = null)) as NotificationInbound.Page).page
        assertNull(last.nextPageToken)
    }

    @Test
    fun withCapability_decoratesOnlyTheAuthFrameAndKeepsToken() {
        val auth = """{"v":1,"type":"auth","payload":{"token":"EXAMPLE-ONLY"}}"""
        val decorated = Json.parseToJsonElement(NotificationCodec.withCapability(auth)).jsonObject
        val payload = decorated["payload"]!!.jsonObject
        assertEquals("EXAMPLE-ONLY", payload["token"]!!.jsonPrimitive.content)
        assertEquals(listOf("notifications_v1"), (payload["capabilities"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("auth", decorated["type"]!!.jsonPrimitive.content)
        // 幂等：已有 capabilities 不重复追加；非 auth 帧逐字节透传。
        val again = NotificationCodec.withCapability(NotificationCodec.withCapability(auth))
        assertEquals(NotificationCodec.withCapability(auth), again)
        val input = """{"v":1,"type":"input","payload":{"ref":"%1","text":"auth"}}"""
        assertEquals(input, NotificationCodec.withCapability(input))
    }

    @Test
    fun syncRequests_matchContractShapes() {
        val first = Json.parseToJsonElement(NotificationCodec.syncRequest(7, NotificationCursor("s", "41"))).jsonObject
        assertEquals("notifications_sync", first["type"]!!.jsonPrimitive.content)
        val p = first["payload"]!!.jsonObject
        assertEquals("7", p["req_id"]!!.jsonPrimitive.content)
        assertEquals("41", (p["cursor"] as JsonObject)["seq"]!!.jsonPrimitive.content)
        assertEquals("50", p["page_size"]!!.jsonPrimitive.content)

        val bootstrap = Json.parseToJsonElement(NotificationCodec.syncRequest(1, null)).jsonObject["payload"]!!.jsonObject
        assertFalse(bootstrap.containsKey("cursor"))

        val next = Json.parseToJsonElement(NotificationCodec.continuation(8, "opaque")).jsonObject["payload"]!!.jsonObject
        assertEquals(setOf("req_id", "page_token"), next.keys)
    }

    companion object {
        fun record(
            seq: String,
            id: String = "id-$seq",
            timestamp: String = "2026-10-01T01:00:00.123Z",
            level: String = "success",
            title: String = "标题 $seq",
            body: String = "正文 $seq",
            sessionRef: String? = "/tmp/tmux-501/team\u001f%3",
            hostId: String = "h",
            streamId: String = "s",
        ): String {
            fun q(v: String?) = if (v == null) "null" else Json.encodeToString(kotlinx.serialization.serializer<String>(), v)
            return """{"id":${q(id)},"host_id":${q(hostId)},"stream_id":${q(streamId)},"seq":${q(seq)},""" +
                """"timestamp":${q(timestamp)},"title":${q(title)},"body":${q(body)},"session_ref":${q(sessionRef)},""" +
                """"session_instance":${q(sessionRef?.let { "inst" })},"workspace":"/work/project","agent_name":"Sol","level":${q(level)}}"""
        }

        fun liveFrame(
            level: String = "success",
            seq: String = "42",
            body: String = "Issue #42 的契约与验收方案已完成。\n详见报告。",
            title: String = "验收完成",
        ): String {
            fun q(v: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), v)
            return """{"v":1,"type":"notification","payload":{"id":"720a303f-c883-445f-a3df-c316d901a065",""" +
                """"host_id":"host_0123456789abcdef","stream_id":"4e4ca540-df59-4b79-a285-7d1f7523c7ab","seq":${q(seq)},""" +
                """"timestamp":"2026-10-01T01:00:00.123Z","title":${q(title)},"body":${q(body)},""" +
                """"session_ref":"/tmp/tmux-501/team\u001f%3","session_instance":"pane-instance-example-01",""" +
                """"workspace":"/work/project","agent_name":"Sol","level":${q(level)}}}"""
        }

        fun pageFrame(reqId: Long, items: List<String>, next: String?, head: String = "43", reset: String? = null): String =
            """{"v":1,"type":"notifications_page","payload":{"req_id":$reqId,"ok":true,"host_id":"h","stream_id":"s",""" +
                """"snapshot_cursor":{"stream_id":"s","seq":"$head"},"retained_from_seq":"1",""" +
                """"reset_reason":${reset?.let { "\"$it\"" } ?: "null"},"order":"asc","items":[${items.joinToString(",")}]""" +
                (next?.let { ""","next_page_token":"$it"""" } ?: "") + "}}"
    }
}
