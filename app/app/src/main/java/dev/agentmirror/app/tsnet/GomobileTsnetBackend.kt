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

import java.io.File
import java.net.NetworkInterface

/**
 * [TsnetBackend] 的真实现：包 libs/tsnetbind.aar（gomobile 绑定，
 * 由 tools/tsnetbind 构建）。**native 触达仅此一处**——tsnetbind 生成类的
 * static 块会 System.loadLibrary("gojni")，因此 JVM 单测绝不实例化本类
 * （测试用 fake，见 TsnetManagerTest）；本类正确性归 e2e 实机手册。
 *
 * feat-ts-wire 两处 Android 现实修正：
 * 1. 起网前注册 Java 层网卡枚举（[installInterfaceProvider]）——API 30+ 禁 app
 *    进程 netlink，Go 侧枚举必死（模拟器实证 netlinkrib: permission denied）；
 * 2. Start 语义为「阻塞至真正入网」（tsnetbind 内部 tsnet.Up），key 无效在这里
 *    显式抛错，不再出现"看似成功实未入网"的假 Up（018 状态可视失实）。
 */
private object HostRouterLike {
    fun isLiteralIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        if (!parts.all { it.isNotEmpty() && (it.toIntOrNull() ?: -1) in 0..255 &&
                (it.length == 1 || !it.startsWith('0'))
        }) return false
        val octets = parts.map(String::toInt)
        return octets.any { it != 0 } &&
            octets[0] != 127 &&
            !(octets[0] == 169 && octets[1] == 254) &&
            !(octets[0] == 198 && octets[1] in 18..19) &&
            octets[0] !in 224..239 &&
            value != "255.255.255.255"
    }
}

class GomobileTsnetBackend : TsnetBackend {
    /** 运行中的 tsnet 节点句柄；生命周期与 start/close 对齐。 */
    private var node: tsnetbind.Node? = null

    @Synchronized
    override fun start(stateDir: String, hostname: String, authKey: String): TsnetProxy {
        installInterfaceProvider()
        // 控制面 URL 接缝：优先节点目录，其次共享状态根的 control_url.txt（headscale 等
        // 自建控制面，011 部署侧自由；也是模拟器自验通道）。空 = 官方控制面。
        // TsnetWire 按 authkey 指纹隔离节点目录，因此共享文件位于其父目录。
        // authkey 红线：本方法任何路径不落日志。
        val controlUrl = runCatching {
            val nodeDir = File(stateDir)
            val controlFile = File(nodeDir, CONTROL_URL_FILE).takeIf { it.isFile }
                ?: nodeDir.parentFile?.let { File(it, CONTROL_URL_FILE) }?.takeIf { it.isFile }
            controlFile?.readText()?.trim()
        }.getOrNull().orEmpty()
        // gomobile 层 Start 阻塞至节点 Running 并起好 loopback SOCKS5（Go 侧 Up+Loopback）。
        val n = tsnetbind.Tsnetbind.start(stateDir, hostname, authKey, controlUrl)
        node = n
        return TsnetProxy.parse(n.proxyAddr(), n.proxyCred())
    }

    @Synchronized
    override fun peerSnapshot(knownId: String?, cursor: String?): TsPeerSnapshot {
        val n = node ?: return TsPeerSnapshot(emptyList(), null, supported = false)
        val result = n.peerSnapshot(knownId.orEmpty(), cursor.orEmpty())
        val peers = result.getLines().lineSequence().mapNotNull { line ->
            val fields = line.split('\t', limit = 4)
            if (fields.size != 4) return@mapNotNull null
            val ips = fields[2].split(',').filter { HostRouterLike.isLiteralIpv4(it) }
            TsPeer(
                stableId = fields[0],
                online = fields[1] == "1",
                ipv4 = ips,
                hostname = fields[3],
            )
        }.toList()
        return TsPeerSnapshot(peers, result.getNextCursor().takeIf { it.isNotEmpty() })
    }

    @Synchronized
    override fun close() {
        node?.let { runCatching { it.close() } }
        node = null
    }

    private companion object {
        const val CONTROL_URL_FILE = "control_url.txt"

        /** 进程级一次注册（tsnetbind 侧是全局钩子，重复注册无益）。 */
        @Volatile
        private var providerInstalled = false

        fun installInterfaceProvider() {
            if (providerInstalled) return
            providerInstalled = true
            tsnetbind.Tsnetbind.setInterfaceProvider(AndroidInterfaceProvider)
        }
    }

    /**
     * Java 层网卡枚举 → 行文本（[TsnetInterfaceCodec] 契约）。netmon 秒级轮询调用
     * （后台路径非渲染热路径）。单卡查询异常（SocketException 等）跳过该卡不毁表；
     * IPv6 zone id（fe80::1%wlan0）剥除——Go net.ParseCIDR 不认。
     */
    private object AndroidInterfaceProvider : tsnetbind.InterfaceProvider {
        override fun interfaces(): String {
            val out = ArrayList<NetIfSnapshot>()
            val en = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return ""
            for (ni in en) {
                runCatching {
                    val cidrs = ni.interfaceAddresses.mapNotNull { ia ->
                        val host = ia.address?.hostAddress ?: return@mapNotNull null
                        host.substringBefore('%') + "/" + ia.networkPrefixLength
                    }
                    out.add(
                        NetIfSnapshot(
                            name = ni.name ?: return@runCatching,
                            index = ni.index,
                            mtu = runCatching { ni.mtu }.getOrDefault(0),
                            up = ni.isUp,
                            loopback = ni.isLoopback,
                            multicast = runCatching { ni.supportsMulticast() }.getOrDefault(false),
                            ptp = ni.isPointToPoint,
                            cidrs = cidrs,
                        ),
                    )
                } // 坏卡跳过：单卡异常不拖垮全表（Go 侧全表为空才报错）。
            }
            return TsnetInterfaceCodec.encode(out)
        }
    }
}
