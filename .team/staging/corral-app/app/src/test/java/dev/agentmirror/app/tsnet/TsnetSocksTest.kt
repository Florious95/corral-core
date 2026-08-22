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

package dev.agentmirror.app.tsnet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 自实现 SOCKS5 CONNECT 握手的字节级单测（feat-ts-wire）：脚本化服务端应答流，
 * 断言客户端写出的每个字节（RFC 1928/1929 契约）与失败语义。
 * 红线阳性对照：认证被拒的异常消息不携带凭证。
 */
class TsnetSocksTest {

    /** 服务端脚本：问候选 user/pass → 认证通过 → CONNECT 成功（IPv4 BND）。 */
    private fun okScript(): ByteArray = byteArrayOf(
        0x05, 0x02, // 问候应答：选 0x02 认证
        0x01, 0x00, // 认证：成功
        0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, // CONNECT 应答：成功 + IPv4 BND
    )

    @Test
    fun `握手成功 - 客户端字节流逐字节正确`() {
        val out = ByteArrayOutputStream()
        TsnetSocks.handshake(ByteArrayInputStream(okScript()), out, "100.64.0.1", 29900, "tsnet", "c3")

        val expected = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x02, 0x00, 0x02)) // 问候：提供 no-auth+userpass
            write(byteArrayOf(0x01, 0x05)) // 认证：VER=1 ULEN=5
            write("tsnet".toByteArray())
            write(byteArrayOf(0x02)) // PLEN=2
            write("c3".toByteArray())
            write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 100, 64, 0, 1)) // CONNECT IPv4
            write(byteArrayOf(((29900 shr 8) and 0xff).toByte(), (29900 and 0xff).toByte()))
        }.toByteArray()
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun `认证被拒 - 显式失败且消息不含凭证`() {
        val script = byteArrayOf(0x05, 0x02, 0x01, 0x01) // 问候选认证 → 认证失败
        val e = assertThrows(IOException::class.java) {
            TsnetSocks.handshake(ByteArrayInputStream(script), ByteArrayOutputStream(), "100.64.0.1", 1, "tsnet", "secret-cred")
        }
        assertTrue(e.message!!.contains("认证被拒"))
        assertTrue("凭证不得出现在异常消息", !e.message!!.contains("secret-cred"))
    }

    @Test
    fun `CONNECT 被拒 - REP 码映射可读原因`() {
        val script = byteArrayOf(
            0x05, 0x00, // 问候：无需认证
            0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0, // CONNECT：connection refused
        )
        val e = assertThrows(IOException::class.java) {
            TsnetSocks.handshake(ByteArrayInputStream(script), ByteArrayOutputStream(), "100.64.0.1", 1, "u", "p")
        }
        assertTrue(e.message!!.contains("connection refused"))
    }

    @Test
    fun `代理半途关连接 - EOF 显式报错`() {
        val e = assertThrows(IOException::class.java) {
            TsnetSocks.handshake(ByteArrayInputStream(byteArrayOf(0x05)), ByteArrayOutputStream(), "h", 1, "u", "p")
        }
        assertTrue(e.message!!.contains("关闭连接"))
    }

    @Test
    fun `域名目标走 ATYP=DOMAIN`() {
        val script = byteArrayOf(
            0x05, 0x00,
            0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
        )
        val out = ByteArrayOutputStream()
        TsnetSocks.handshake(ByteArrayInputStream(script), out, "h.example", 80, "u", "p")
        val bytes = out.toByteArray()
        // 问候 4 字节后是 CONNECT：05 01 00 03 LEN 'h.example' PORT。
        assertEquals(0x03, bytes[7].toInt())
        assertEquals(9, bytes[8].toInt())
    }
}
