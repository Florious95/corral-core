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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 帧编解码单测：消费同一份契约夹具（server/internal/protocol/testdata/）做字节级断言。
 *
 * 夹具是协议的一部分（leader 裁定 013）：本层与 Go 参考实现必须共享同一套 golden
 * 样本，decode→re-encode 字节稳定，拦截协议漂移。
 */
class ConnCodecTest {

    // ---- 控制帧：夹具解码 + 字段断言 ----

    @Test
    fun testGoldenAuthFrame() {
        val f = decode("auth.json")
        val auth = f as AuthFrame
        assertEquals("tok-abc-123", auth.token)
        // 编码不改变语义：decode→re-encode 与夹具语义等价（JSON 键序允许不同）。
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenAuthAckOk() {
        val f = decode("auth_ack_ok.json") as AuthAckFrame
        assertTrue(f.ok)
        assertEquals("", f.reason)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenAuthAckReject() {
        val f = decode("auth_ack_reject.json") as AuthAckFrame
        assertFalse(f.ok)
        assertEquals("bad token", f.reason)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenListFrame() {
        val f = decode("list.json") as ListFrame
        assertEquals(7L, f.reqId)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenListingFrame() {
        val f = decode("listing.json") as ListingFrame
        assertEquals(7L, f.reqId)
        assertEquals(42L, f.seq)
        assertEquals(2, f.workspaces.size)
        val w0 = f.workspaces[0]
        assertEquals("/proj/a", w0.cwd)
        assertEquals(2, w0.sessionCount)
        assertEquals(AgentState.BLOCKED, w0.aggregateState)
        assertEquals(2, w0.sessions.size)
        assertEquals("s1", w0.sessions[0].ref)
        assertEquals("claude", w0.sessions[0].name)
        assertEquals(AgentState.WORKING, w0.sessions[0].state)
        assertEquals(40, w0.sessions[0].rows)
        assertEquals(100, w0.sessions[0].cols)
        val w1 = f.workspaces[1]
        assertEquals("/proj/b", w1.cwd)
        assertEquals(AgentState.UNKNOWN, w1.aggregateState)
        assertEquals(AgentState.UNKNOWN, w1.sessions[0].state)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenListDeltaFrame() {
        val f = decode("list_delta.json") as ListDeltaFrame
        assertEquals(45L, f.seq)
        assertEquals(1, f.addedSessions.size)
        assertEquals("s4", f.addedSessions[0].ref)
        assertEquals(AgentState.IDLE, f.addedSessions[0].state)
        assertEquals(listOf("s1"), f.removedRefs)
        assertEquals(1, f.changedSessions.size)
        assertEquals("s2", f.changedSessions[0].ref)
        assertEquals(AgentState.IDLE, f.changedSessions[0].state)
        assertEquals(1, f.changedWorkspaces.size)
        assertEquals("/proj/a", f.changedWorkspaces[0].cwd)
        assertEquals(AgentState.IDLE, f.changedWorkspaces[0].aggregateState)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenSubscribeFrame() {
        val f = decode("subscribe.json") as SubscribeFrame
        assertEquals("s1", f.ref)
        assertEquals(40, f.rows)
        assertEquals(100, f.cols)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenUnsubscribeFrame() {
        val f = decode("unsubscribe.json") as UnsubscribeFrame
        assertEquals("s1", f.ref)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenInputFrame() {
        val f = decode("input.json") as InputFrame
        assertEquals(9L, f.reqId)
        assertEquals("s1", f.ref)
        assertEquals("/model opus", f.text)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenInputKeysFrame() {
        // R-1 快捷键条夹具（017 裁定）：input_keys.json 是协议的一部分，与 Go 参考实现
        // 共享字节级断言（leader 裁定 013），text 与 keys 互斥——本样本无 text。
        val f = decode("input_keys.json") as InputFrame
        assertEquals(10L, f.reqId)
        assertEquals("s1", f.ref)
        assertEquals("", f.text)
        assertEquals(listOf(InputKey.ESC, InputKey.CTRL_C, InputKey.TAB), f.keys)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenInputAckOk() {
        val f = decode("input_ack_ok.json") as InputAckFrame
        assertEquals(9L, f.reqId)
        assertTrue(f.ok)
        assertEquals(null, f.reason)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenInputAckFail() {
        val f = decode("input_ack_fail.json") as InputAckFrame
        assertEquals(9L, f.reqId)
        assertFalse(f.ok)
        assertEquals(InputFailReason.INJECT_FAILED, f.reason)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenScrollbackFrame() {
        val f = decode("scrollback.json") as ScrollbackFrame
        assertEquals(5L, f.reqId)
        assertEquals("s1", f.ref)
        assertEquals(-300, f.fromLine)
        assertEquals(100L, f.count)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenResizeFrame() {
        val f = decode("resize.json") as ResizeFrame
        assertEquals("s1", f.ref)
        assertEquals(48, f.rows)
        assertEquals(120, f.cols)
        reencodeEquiv(f)
    }

    @Test
    fun testGoldenErrorFrame() {
        val f = decode("error.json") as ErrorFrame
        assertEquals(ErrorCode.SESSION_NOT_FOUND, f.code)
        assertEquals("session s1 vanished", f.reason)
        reencodeEquiv(f)
    }

    // ---- 控制帧：编码形态 + 往返 ----

    @Test
    fun testControlFrameRoundTrips() {
        val frames: List<FramePayload> = listOf(
            AuthFrame("tok"),
            AuthAckFrame(ok = true),
            AuthAckFrame(ok = false, reason = "bad token"),
            ListFrame(7),
            ListingFrame(7, 42, listOf(Workspace("/a", 1, AgentState.IDLE, listOf(Session("s1", "c", "/a", AgentState.WORKING, 40, 100))))),
            ListDeltaFrame(1, addedSessions = listOf(Session("s4", "c", "/c", AgentState.IDLE, 25, 100))),
            SubscribeFrame("s1", 40, 100),
            UnsubscribeFrame("s1"),
            InputFrame(9, "s1", "/model opus"),
            InputFrame(10, "s1"), // 空 text = 仅回车
            InputFrame(11, "s1", keys = listOf(InputKey.ESC, InputKey.UP)), // keys 不附加回车
            InputAckFrame(9, ok = true),
            InputAckFrame(9, ok = false, reason = InputFailReason.INJECT_FAILED),
            ScrollbackFrame(5, "s1", -300, 100),
            ResizeFrame("s1", 48, 120),
            ErrorFrame(ErrorCode.SESSION_NOT_FOUND, "gone"),
        )
        for (f in frames) {
            val text = FrameCodec.encode(f)
            val decoded = FrameCodec.decode(text)
            assertEquals(f, decoded)
        }
    }

    @Test
    fun testEncodeEnvelopeShape() {
        val text = FrameCodec.encode(ListFrame(1))
        assertTrue(text.contains("\"v\":1"))
        assertTrue(text.contains("\"type\":\"list\""))
        assertTrue(text.contains("\"payload\""))
    }

    // ---- 控制帧：红测（坏帧/截断/未知 type/版本不匹配/缺必填/状态越界）----

    @Test
    fun testRedMissingVersion() {
        assertDecodeFails(FrameError.MISSING_VERSION, """{"type":"list","payload":{"req_id":1}}""")
    }

    @Test
    fun testRedUnsupportedVersion() {
        assertDecodeFails(FrameError.UNSUPPORTED_VERSION, """{"v":2,"type":"list","payload":{"req_id":1}}""")
    }

    @Test
    fun testRedUnknownType() {
        assertDecodeFails(FrameError.UNSUPPORTED_TYPE, """{"v":1,"type":"nope","payload":{}}""")
    }

    @Test
    fun testRedEmptyType() {
        assertDecodeFails(FrameError.INVALID_FIELD, """{"v":1,"type":"","payload":{}}""")
    }

    @Test
    fun testRedMalformedJson() {
        assertDecodeFails(FrameError.BAD_FRAME, """{"v":1,"type":"list","payload":{""")
    }

    @Test
    fun testRedPayloadNotObject() {
        assertDecodeFails(FrameError.BAD_FRAME, """{"v":1,"type":"auth","payload":"notanobject"}""")
    }

    @Test
    fun testRedAuthMissingToken() {
        assertDecodeFails(FrameError.INVALID_FIELD, """{"v":1,"type":"auth","payload":{}}""")
    }

    @Test
    fun testRedListMissingReqId() {
        assertDecodeFails(FrameError.INVALID_FIELD, """{"v":1,"type":"list","payload":{}}""")
    }

    @Test
    fun testRedSessionBadState() {
        assertDecodeFails(
            FrameError.INVALID_STATE,
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":""" +
                """[{"cwd":"/x","session_count":1,"aggregate_state":"working","sessions":""" +
                """[{"ref":"s1","name":"c","cwd":"/x","state":"flying","rows":24,"cols":80}]}]}}""",
        )
    }

    @Test
    fun testRedWorkspaceBadAggregate() {
        assertDecodeFails(
            FrameError.INVALID_STATE,
            """{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":""" +
                """[{"cwd":"/x","session_count":1,"aggregate_state":"zombie"}]}}""",
        )
    }

    @Test
    fun testRedSubscribeMissingRef() {
        assertDecodeFails(FrameError.INVALID_FIELD, """{"v":1,"type":"subscribe","payload":{"rows":24,"cols":80}}""")
    }

    @Test
    fun testRedScrollbackZeroCount() {
        assertDecodeFails(
            FrameError.INVALID_FIELD,
            """{"v":1,"type":"scrollback","payload":{"req_id":1,"ref":"s1","from_line":0,"count":0}}""",
        )
    }

    @Test
    fun testRedInputAckFailNoReason() {
        assertDecodeFails(FrameError.INVALID_FIELD, """{"v":1,"type":"input_ack","payload":{"req_id":9,"ok":false}}""")
    }

    @Test
    fun testRedInputAckOkWithReason() {
        assertDecodeFails(
            FrameError.INVALID_FIELD,
            """{"v":1,"type":"input_ack","payload":{"req_id":9,"ok":true,"reason":"internal"}}""",
        )
    }

    @Test
    fun testRedInputAckUnknownReason() {
        assertDecodeFails(
            FrameError.INVALID_FIELD,
            """{"v":1,"type":"input_ack","payload":{"req_id":9,"ok":false,"reason":"who knows"}}""",
        )
    }

    @Test
    fun testRedInputUnknownKey() {
        // keys 是闭集（017 R-1 七键，新增须 bump 版本）：未知键按坏帧拒绝，对齐 Go
        // json_test.go "input unknown key"（ErrInvalidField）。
        assertDecodeFails(
            FrameError.INVALID_FIELD,
            """{"v":1,"type":"input","payload":{"req_id":10,"ref":"s1","keys":["home"]}}""",
        )
    }

    @Test
    fun testRedInputBothTextAndKeys() {
        // 契约 §4.2：text 与 keys 互斥，两者都有判协议错误（对齐 Go validate.go）。
        assertDecodeFails(
            FrameError.INVALID_FIELD,
            """{"v":1,"type":"input","payload":{"req_id":10,"ref":"s1","text":"hi","keys":["esc"]}}""",
        )
    }

    @Test
    fun testRedInputKeysNotArray() {
        // keys 字段类型错（非数组）⇒ 坏帧。
        assertDecodeFails(
            FrameError.BAD_FRAME,
            """{"v":1,"type":"input","payload":{"req_id":10,"ref":"s1","keys":"esc"}}""",
        )
    }

    @Test
    fun testForwardCompatUnknownErrorCode() {
        // 协议 §2 前向兼容：服务端未来可增量新增 error code，客户端必须容忍未识别值。
        val f = FrameCodec.decode("""{"v":1,"type":"error","payload":{"code":"brand_new_code","reason":"x"}}""") as ErrorFrame
        assertEquals(ErrorCode.UNKNOWN, f.code)
    }

    @Test
    fun testEncodeUnknownErrorCodeRejected() {
        // UNKNOWN 是解码回退值，永不上行：编码必须拒绝。
        try {
            FrameCodec.encode(ErrorFrame(ErrorCode.UNKNOWN))
            fail("encode must reject UNKNOWN error code")
        } catch (e: FrameEncodeException) {
            assertEquals(FrameError.INVALID_FIELD, e.code)
        }
    }

    @Test
    fun testRedAuthAckRejectedNoReason() {
        assertDecodeFails(FrameError.INVALID_FIELD, """{"v":1,"type":"auth_ack","payload":{"ok":false}}""")
    }

    @Test
    fun testEncodeRedPaths() {
        // 编码侧校验：无效帧不跨线。
        val invalid: List<FramePayload> = listOf(
            AuthFrame(""),
            ListFrame(0),
            SubscribeFrame("s1", 0, 100),
            InputFrame(0, "s1"),
            InputFrame(1, "s1", text = "hi", keys = listOf(InputKey.ESC)), // text 与 keys 互斥
            InputAckFrame(1, ok = false), // 缺 reason
            InputAckFrame(1, ok = true, reason = InputFailReason.INTERNAL), // ok 带 reason
            ScrollbackFrame(5, "s1", 0, 0),
            ResizeFrame("s1", 48, 0),
            ErrorFrame(ErrorCode.UNKNOWN), // 解码回退值永不上行，编码必须拒绝
            ListingFrame(0, 1, emptyList()),
            ListDeltaFrame(0),
        )
        for (f in invalid) {
            try {
                FrameCodec.encode(f)
                fail("encode should reject invalid frame: $f")
            } catch (e: FrameEncodeException) {
                assertEquals(FrameError.INVALID_FIELD, e.code)
            }
        }
    }

    // ---- 前向兼容：未知字段必须被忽略 ----

    @Test
    fun testIgnoreUnknownFields() {
        val f = FrameCodec.decode(
            """{"v":1,"type":"list","future_header":42,"payload":{"req_id":3,"future_payload":true}}""",
        ) as ListFrame
        assertEquals(3L, f.reqId)
    }

    @Test
    fun testUnmarshalWithNoPayload() {
        // 缺 payload 按零值校验：auth 必须拒绝（缺 token），list_delta 空集合可接受。
        assertDecodeFails(FrameError.INVALID_FIELD, """{"v":1,"type":"auth"}""")
        val d = FrameCodec.decode("""{"v":1,"type":"list_delta","payload":{"seq":1}}""") as ListDeltaFrame
        assertEquals(1L, d.seq)
        assertTrue(d.addedSessions.isEmpty())
    }

    // ---- 二进制流帧：夹具字节级断言 ----

    @Test
    fun testBinarySnapshotFixture() {
        val bytes = FixturePath.read("snapshot.bin")
        val f = BinaryFrameCodec.decode(bytes)
        assertEquals(BinaryKind.SNAPSHOT, f.kind)
        assertEquals("s1", f.ref)
        assertTrue(f.data.contentEquals("[31mred screen[0m\n".toByteArray()))
        assertEquals(0L, f.reqId)
        assertEquals(0L, f.lineCount)
        // 字节级往返：decode→re-encode 必须与夹具字节完全一致。
        assertTrue(BinaryFrameCodec.encode(f).contentEquals(bytes))
    }

    @Test
    fun testBinaryDeltaFixture() {
        val bytes = FixturePath.read("delta.bin")
        val f = BinaryFrameCodec.decode(bytes)
        assertEquals(BinaryKind.DELTA, f.kind)
        assertEquals("s1", f.ref)
        assertTrue(f.data.contentEquals("append".toByteArray()))
        assertTrue(BinaryFrameCodec.encode(f).contentEquals(bytes))
    }

    @Test
    fun testBinaryScrollbackFixture() {
        val bytes = FixturePath.read("scrollback.bin")
        val f = BinaryFrameCodec.decode(bytes)
        assertEquals(BinaryKind.SCROLLBACK, f.kind)
        assertEquals("s1", f.ref)
        assertEquals(5L, f.reqId)
        assertEquals(-100, f.fromLine)
        assertEquals(50L, f.lineCount)
        assertTrue(f.data.contentEquals("history page one".toByteArray()))
        assertTrue(BinaryFrameCodec.encode(f).contentEquals(bytes))
    }

    // ---- 二进制流帧：红测 ----

    @Test
    fun testBinaryRedPaths() {
        // 构造一条合法 delta 帧再逐点破坏。
        fun fresh(): ByteArray =
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.DELTA, "s1", byteArrayOf('x'.code.toByte())))

        fun scroll(): ByteArray = BinaryFrameCodec.encode(
            BinaryFrame(BinaryKind.SCROLLBACK, "s1", byteArrayOf('h'.code.toByte()), reqId = 7, fromLine = -5, lineCount = 1),
        )

        val cases = listOf(
            Triple("too short", byteArrayOf('R'.code.toByte(), 'A'.code.toByte()), FrameError.TRUNCATED),
            Triple("bad magic", byteArrayOf('X'.code.toByte(), 'Y'.code.toByte(), 1, 2, 1, 's'.code.toByte(), 'x'.code.toByte()), FrameError.BAD_MAGIC),
            Triple("bad version byte", fresh().copyOf().also { it[2] = 9 }, FrameError.UNSUPPORTED_VERSION),
            Triple("unknown kind", byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 1, 9, 1, 's'.code.toByte(), 'x'.code.toByte()), FrameError.UNKNOWN_KIND),
            Triple("truncated ref", byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 1, 2, 5, 's'.code.toByte(), '1'.code.toByte(), 'x'.code.toByte()), FrameError.TRUNCATED),
            Triple("empty ref", byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 1, 2, 0, 'x'.code.toByte()), FrameError.INVALID_REF),
            Triple("truncated frame", fresh().copyOfRange(0, 6), FrameError.TRUNCATED),
            Triple("scrollback missing header", scroll().copyOfRange(0, 7), FrameError.TRUNCATED),
            Triple("scrollback reqid zero", scroll().also { it[10] = 0 }, FrameError.INVALID_FIELD),
            Triple("scrollback line_count zero", scroll().also { it[18] = 0 }, FrameError.INVALID_FIELD),
        )
        for ((name, wire, want) in cases) {
            try {
                BinaryFrameCodec.decode(wire)
                fail("decode should reject: $name")
            } catch (e: FrameDecodeException) {
                assertEquals("$name: expected $want", want, e.code)
            }
        }
    }

    @Test
    fun testBinaryEncodeRedPaths() {
        val invalid = listOf(
            BinaryFrame(BinaryKind.DELTA, "", byteArrayOf(1)),
            BinaryFrame(BinaryKind.DELTA, "r".repeat(256), byteArrayOf(1)),
            BinaryFrame(BinaryKind.DELTA, "s1", ByteArray(ProtocolVersion.MAX_BINARY_PAYLOAD + 1)),
            BinaryFrame(BinaryKind.SCROLLBACK, "s1", byteArrayOf(1), reqId = 0, fromLine = -1, lineCount = 1),
            BinaryFrame(BinaryKind.SCROLLBACK, "s1", byteArrayOf(1), reqId = 1, fromLine = -1, lineCount = 0),
        )
        for (f in invalid) {
            try {
                BinaryFrameCodec.encode(f)
                fail("encode should reject: $f")
            } catch (e: FrameEncodeException) {
                // 各类边界：INVALID_REF / REF_TOO_LONG / INVALID_FIELD。
                assertTrue(e.code != FrameError.UNKNOWN_KIND)
            }
        }
    }

    @Test
    fun testBinaryWireLayout() {
        // 钉死字节布局：magic, version, kind, reflen, ref, payload。
        val wire = BinaryFrameCodec.encode(BinaryFrame(BinaryKind.DELTA, "ab", "XY".toByteArray()))
        val want = byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 1, 2, 2, 'a'.code.toByte(), 'b'.code.toByte(), 'X'.code.toByte(), 'Y'.code.toByte())
        assertTrue("layout mismatch: ${wire.toHex()}", wire.contentEquals(want))
    }

    @Test
    fun testBinaryScrollbackHeaderLayout() {
        val wire = BinaryFrameCodec.encode(
            BinaryFrame(BinaryKind.SCROLLBACK, "s1", "page".toByteArray(), reqId = 5, fromLine = -100, lineCount = 50),
        )
        val want = byteArrayOf(
            'R'.code.toByte(), 'A'.code.toByte(), 1, 3, 2, 's'.code.toByte(), '1'.code.toByte(),
            0, 0, 0, 5,             // req_id = 5
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x9c.toByte(), // from_line = -100
            0, 0, 0, 50,            // line_count = 50
            'p'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(), 'e'.code.toByte(),
        )
        assertTrue("scrollback header layout mismatch: ${wire.toHex()}", wire.contentEquals(want))
        // 负 from_line 必须原样回来（不是巨大无符号）。
        val f = BinaryFrameCodec.decode(wire)
        assertEquals(-100, f.fromLine)
        assertEquals(50L, f.lineCount)
    }

    // ---- 工具 ----

    private fun decode(name: String): FramePayload {
        val text = FixturePath.readText(name)
        return FrameCodec.decode(text)
    }

    private fun reencodeEquiv(frame: FramePayload) {
        // decode→re-encode 语义等价：re-encode 后字段与夹具原始解码一致。
        val re = FrameCodec.decode(FrameCodec.encode(frame))
        assertEquals(frame, re)
    }

    private fun assertDecodeFails(expected: FrameError, text: String) {
        try {
            FrameCodec.decode(text)
            fail("decode should fail with $expected: $text")
        } catch (e: FrameDecodeException) {
            assertEquals("for input: $text", expected, e.code)
        }
    }
}
