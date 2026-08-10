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

/**
 * 网卡快照 → tsnetbind 行文本编码（feat-ts-wire）。
 *
 * 背景：Android API 30+ SELinux 禁 app 进程 netlink RTM_GETLINK，Go 侧
 * net.Interfaces() 必死（模拟器实证 netlinkrib: permission denied）——网卡枚举
 * 必须由 Java 层喂给 gomobile（官方 Android 客户端同款 RegisterInterfaceGetter 方案）。
 *
 * 行格式是与 tools/tsnetbind/tsnetbind.go parseInterfaces 的**跨语言 wire 契约**
 * （两端各有契约锁测，改一端必须同改另一端）：
 *
 *     name|index|mtu|up|loopback|multicast|ptp|cidr1,cidr2
 *
 * 布尔 1/0；地址 CIDR 逗号连接，可为空。调用频度 = netmon 秒级轮询（后台路径，
 * 非终端渲染热路径；每次编码的分配量 = 网卡数 × 行长，量级可忽略）。
 */
data class NetIfSnapshot(
    val name: String,
    val index: Int,
    val mtu: Int,
    val up: Boolean,
    val loopback: Boolean,
    val multicast: Boolean,
    val ptp: Boolean,
    /** 已含前缀长度的 CIDR 串（如 192.168.1.5/24；IPv6 zone id 由采集方剥除）。 */
    val cidrs: List<String>,
)

object TsnetInterfaceCodec {

    /** 编码全表；每卡一行。名字里的分隔符字符防御性替换（Java 层不该出现，但坏值不毁表）。 */
    fun encode(ifs: List<NetIfSnapshot>): String = buildString {
        ifs.forEachIndexed { i, s ->
            if (i > 0) append('\n')
            append(s.name.replace('|', '_').replace('\n', '_'))
            append('|').append(s.index)
            append('|').append(s.mtu)
            append('|').append(if (s.up) '1' else '0')
            append('|').append(if (s.loopback) '1' else '0')
            append('|').append(if (s.multicast) '1' else '0')
            append('|').append(if (s.ptp) '1' else '0')
            append('|')
            s.cidrs.forEachIndexed { j, c ->
                if (j > 0) append(',')
                append(c)
            }
        }
    }
}
