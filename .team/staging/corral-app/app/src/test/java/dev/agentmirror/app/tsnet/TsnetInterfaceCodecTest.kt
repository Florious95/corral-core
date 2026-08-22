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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨语言 wire 契约锁测：本文件的期望串与 tools/tsnetbind/tsnetbind_test.go 的
 * 解析夹具**逐字节一致**——改行格式必须两端同改（feat-ts-wire）。
 */
class TsnetInterfaceCodecTest {

    @Test
    fun `单卡编码与 Go 端解析夹具逐字节一致`() {
        val line = TsnetInterfaceCodec.encode(
            listOf(
                NetIfSnapshot(
                    name = "wlan0", index = 3, mtu = 1500,
                    up = true, loopback = false, multicast = true, ptp = false,
                    cidrs = listOf("192.168.1.5/24", "fe80::1/64"),
                ),
            ),
        )
        // 与 tsnetbind_test.go TestParseInterfacesSingle 输入完全相同。
        assertEquals("wlan0|3|1500|1|0|1|0|192.168.1.5/24,fe80::1/64", line)
    }

    @Test
    fun `多卡换行连接与空地址表`() {
        val text = TsnetInterfaceCodec.encode(
            listOf(
                NetIfSnapshot("lo", 1, 65536, up = true, loopback = true, multicast = false, ptp = false, cidrs = listOf("127.0.0.1/8")),
                NetIfSnapshot("dummy0", 9, 1400, up = false, loopback = false, multicast = false, ptp = false, cidrs = emptyList()),
            ),
        )
        // 与 tsnetbind_test.go TestParseInterfacesLoopbackAndDown 输入完全相同。
        assertEquals("lo|1|65536|1|1|0|0|127.0.0.1/8\ndummy0|9|1400|0|0|0|0|", text)
    }

    @Test
    fun `名字含分隔符防御性替换`() {
        val line = TsnetInterfaceCodec.encode(
            listOf(NetIfSnapshot("we|ird", 2, 1500, up = true, loopback = false, multicast = false, ptp = false, cidrs = emptyList())),
        )
        assertEquals("we_ird|2|1500|1|0|0|0|", line)
    }
}
