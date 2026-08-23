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

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * 输入透传第 2 步：客户端 InputFrame 裸字节表达。
 * R1 base64 往返 / R2 text·keys·bytes 互斥 / R3 不带 bytes 时线格式兼容（逐字段 identical）。
 */
class InputBytesCodecTest {

    // R1: 含控制字节的序列必须能被 InputFrame 表达，JSON 走标准 base64（非 URL-safe）。
    @Test
    fun testRawBytesControlSequenceStandardBase64RoundTrip() {
        val raw = byteArrayOf(0x03, 0x1b, '['.code.toByte(), 'A'.code.toByte(), 0xfb.toByte())
        val frame = InputFrame(reqId = 91, ref = "s1", bytes = raw)
        val wire = FrameCodec.encode(frame)
        val payload = json.parseToJsonElement(wire).jsonObject["payload"]!!.jsonObject
        assertTrue("R1: payload must carry bytes", payload.containsKey("bytes"))
        val b64 = payload["bytes"]!!.jsonPrimitive.content
        val std = Base64.getEncoder().encodeToString(raw)
        val url = Base64.getUrlEncoder().encodeToString(raw)
        assertEquals("R1: wire bytes must be standard base64, not URL-safe", std, b64)
        assertFalse("R1: must not use URL-safe base64", b64 == url && b64 != std)
        val decoded = Base64.getDecoder().decode(b64)
        assertTrue("R1: standard base64 decode must match original bytes", decoded.contentEquals(raw))
        val round = FrameCodec.decode(wire) as InputFrame
        assertTrue(round.bytes!!.contentEquals(raw))
    }

    // R2: 同一 InputFrame 里 text 与 bytes 同时非空必须被拒（互斥 / mutual exclusive）。
    @Test
    fun testInputTextAndBytesMutuallyExclusive() {
        val both = InputFrame(
            reqId = 1,
            ref = "s1",
            text = "hi",
            bytes = byteArrayOf(0x03),
        )
        try {
            FrameCodec.encode(both)
            fail("R2: encode must reject text+bytes 同时非空")
        } catch (e: FrameEncodeException) {
            assertEquals(FrameError.INVALID_FIELD, e.code)
        }
        val keysAndBytes = InputFrame(
            reqId = 1,
            ref = "s1",
            keys = listOf(InputKey.ESC),
            bytes = byteArrayOf(0x1b),
        )
        try {
            FrameCodec.encode(keysAndBytes)
            fail("R2: encode must reject keys+bytes 同时非空")
        } catch (e: FrameEncodeException) {
            assertEquals(FrameError.INVALID_FIELD, e.code)
        }
    }

    // R3: 不带 bytes 时序列化与改前 golden 逐字段 identical（兼容 / compat / unchanged）。
    @Test
    fun testPassthroughCompatOmitBytesFieldUnchanged() {
        val golden = json.parseToJsonElement(ProtocolFixture.readText("input.json")).jsonObject
        val encoded = json.parseToJsonElement(
            FrameCodec.encode(InputFrame(9, "s1", "/model opus")),
        ).jsonObject
        val goldenPayload = golden["payload"]!!.jsonObject
        val encodedPayload = encoded["payload"]!!.jsonObject
        assertEquals("R3: payload keys 逐字段 identical", goldenPayload.keys, encodedPayload.keys)
        for (k in goldenPayload.keys) {
            assertEquals("R3: payload[$k] unchanged", goldenPayload[k], encodedPayload[k])
        }
        assertFalse("R3: 不带 bytes 时不得出现 bytes 字段", encodedPayload.containsKey("bytes"))

        val keysGolden = json.parseToJsonElement(ProtocolFixture.readText("input_keys.json")).jsonObject
        val keysEncoded = json.parseToJsonElement(
            FrameCodec.encode(
                InputFrame(10, "s1", keys = listOf(InputKey.ESC, InputKey.CTRL_C, InputKey.TAB)),
            ),
        ).jsonObject
        val kg = keysGolden["payload"]!!.jsonObject
        val ke = keysEncoded["payload"]!!.jsonObject
        assertEquals("R3 keys payload keys identical", kg.keys, ke.keys)
        assertFalse(ke.containsKey("bytes"))
    }

    @Test
    fun testRawBytesTooLargeRejected() {
        val big = ByteArray(ProtocolVersion.MAX_INPUT_BYTES + 1) { 'a'.code.toByte() }
        try {
            FrameCodec.encode(InputFrame(reqId = 94, ref = "s1", bytes = big))
            fail("oversize bytes must not encode")
        } catch (e: FrameEncodeException) {
            assertEquals(FrameError.INVALID_FIELD, e.code)
        }
    }
}
