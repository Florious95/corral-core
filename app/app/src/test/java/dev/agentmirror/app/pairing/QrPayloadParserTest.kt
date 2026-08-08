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

package dev.agentmirror.app.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QR JSON 解析器测试：契约对齐服务端 qr.go（v1）+ 校验边界。
 *
 * 契约是 wire 级（字段名/顺序不可改）；未知字段忽略（协议 §4.1 前向兼容）。
 */
class QrPayloadParserTest {

    @Test
    fun parsesCanonicalPayload() {
        val p = QrPayloadParser.parse(
            """{"v":1,"url":"ws://192.168.1.5:9900/ws","token":"ABC123","ts_authkey":"tskey-x"}""",
        )
        assertEquals(1, p.version)
        assertEquals("ws://192.168.1.5:9900/ws", p.url)
        assertEquals("ABC123", p.token)
        assertEquals("tskey-x", p.tsAuthKey)
    }

    @Test
    fun ignoresUnknownFieldsForwardCompatible() {
        // 前向兼容（§4.1）：未知字段必须忽略，不报错。
        val p = QrPayloadParser.parse(
            """{"v":1,"url":"ws://h:1/ws","token":"t","ts_authkey":"","future":"x"}""",
        )
        assertEquals("ws://h:1/ws", p.url)
    }

    @Test
    fun missingTsAuthKeyDefaultsEmpty() {
        // ts_authkey 本任务不消费（app-tsnet 接入前占位），缺省按空处理。
        val p = QrPayloadParser.parse("""{"v":1,"url":"ws://h:1/ws","token":"t"}""")
        assertEquals("", p.tsAuthKey)
    }

    @Test
    fun rejectsWrongVersion() {
        assertThrows(QrParseException::class.java) {
            QrPayloadParser.parse("""{"v":2,"url":"ws://h:1/ws","token":"t"}""")
        }
    }

    @Test
    fun rejectsMissingUrl() {
        assertThrows(QrParseException::class.java) {
            QrPayloadParser.parse("""{"v":1,"token":"t"}""")
        }
    }

    @Test
    fun rejectsBadWsUrlScheme() {
        // http(s) 不是 ws 端点（协议 §1），拒绝。
        assertFalse(isValidWsUrl("http://host:1/ws"))
        assertTrue(isValidWsUrl("ws://host:1/ws"))
        assertTrue(isValidWsUrl("wss://host:1/ws"))
    }
}
