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

package dev.agentmirror.app.manifest

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * 红测（fix-app-network-manifest）：merged manifest 出网能力断言（验收 --tests "*Manifest*"，
 * 类名含 Manifest 命中过滤器）。
 *
 * e2e 实证缺陷：App 无法建立任何出网连接——main manifest 缺 INTERNET 权限，且 targetSdk 35
 * 默认禁明文，ws:// 连接必抛 CleartextNotPermitted（模拟器手填 ws://10.0.2.2:9902/ws 停在
 * 配对 15s 超时，daemon 零连接到达）。根因链：①缺权限；②缺明文放行。
 *
 * 本测试对 debug 与 release 两个变体的**最终 merged manifest**
 * （build/intermediates/merged_manifests/{variant}/，AGP 合并主/依赖 manifest 的产物）断言：
 * ① android.permission.INTERNET 已声明；
 * ② <application> 带 android:usesCleartextTraffic="true"（明文策略 leader 裁定，debug/release
 *    一致，见 requirement 007/011：ws:// 是出厂传输，tailnet 层已 WireGuard 加密，LAN 明文属
 *    用户自网；TLS 列后续版本议题）。
 *
 * 红测先行：修复前 debug/release merged 均缺 INTERNET 与 usesCleartextTraffic，本测试红；
 * 修复后绿。release merged 产物由 :app:processReleaseManifest 任务在 build.gradle.kts 中
 * 挂为 testDebugUnitTest 前置依赖保证（debug 的由 AGP 自身依赖链保证）。
 */
class ManifestNetworkPolicyTest {

    /** 定位某变体的最终 merged manifest：扫描复数目录取 AndroidManifest.xml（task 名随 AGP 版本可变）。 */
    private fun loadMergedManifest(variant: String): Document {
        val dir = File("build/intermediates/merged_manifests/$variant")
        assertTrue("merged manifest 目录缺失: $dir（先跑 gradle 合并任务再执行本测试）", dir.isDirectory)
        val xml = dir.walkTopDown().firstOrNull { it.isFile && it.name == "AndroidManifest.xml" }
        assertTrue("merged_manifests/$variant 下未找到 AndroidManifest.xml（目录: $dir）", xml != null)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
    }

    /** 顶层直接子元素按标签名收集（uses-permission / application 均位于 manifest 根下）。 */
    private fun Document.directChildren(name: String): List<Element> {
        val out = mutableListOf<Element>()
        var node: Node? = documentElement.firstChild
        while (node != null) {
            if (node is Element && node.nodeName == name) out.add(node)
            node = node.nextSibling
        }
        return out
    }

    /** 取属性原文（非 namespaceAware 解析下保留 "android:xxx" 前缀字面量）。 */
    private fun Element.attr(name: String): String? = attributes.getNamedItem(name)?.nodeValue

    // ---- debug 变体 ----

    /** debug merged manifest 声明了 INTERNET 权限（无此权限则所有 socket 连接被系统拒绝）。 */
    @Test
    fun debugMergedManifest_declaresInternetPermission() {
        val doc = loadMergedManifest("debug")
        val hasInternet =
            doc.directChildren("uses-permission").any { it.attr("android:name") == "android.permission.INTERNET" }
        assertTrue("debug merged manifest 缺 android.permission.INTERNET", hasInternet)
    }

    /** debug merged manifest 的 <application> 允许明文（ws:// 出厂传输，裁定引 007/011）。 */
    @Test
    fun debugMergedManifest_allowsCleartextTraffic() {
        val app = loadMergedManifest("debug").directChildren("application").single()
        assertEquals(
            "debug merged manifest 的 <application> 应 usesCleartextTraffic=true（明文策略）",
            "true",
            app.attr("android:usesCleartextTraffic"),
        )
    }

    // ---- release 变体（与 debug 同为出厂传输载体，明文裁定一致） ----

    /** release merged manifest 声明了 INTERNET 权限（与 debug 一致，防只修 debug 的回归）。 */
    @Test
    fun releaseMergedManifest_declaresInternetPermission() {
        val doc = loadMergedManifest("release")
        val hasInternet =
            doc.directChildren("uses-permission").any { it.attr("android:name") == "android.permission.INTERNET" }
        assertTrue("release merged manifest 缺 android.permission.INTERNET", hasInternet)
    }

    /** release merged manifest 的 <application> 允许明文（与 debug 一致）。 */
    @Test
    fun releaseMergedManifest_allowsCleartextTraffic() {
        val app = loadMergedManifest("release").directChildren("application").single()
        assertEquals(
            "release merged manifest 的 <application> 应 usesCleartextTraffic=true（明文策略）",
            "true",
            app.attr("android:usesCleartextTraffic"),
        )
    }
}
