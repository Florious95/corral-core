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

package dev.agentmirror.app.workspace

import kotlinx.serialization.Serializable

/** 白名单 Provider id：claude_code / codex / copilot / cursor / grok / pi。 */
val ProviderIds: List<String> = listOf(
    "claude_code",
    "codex",
    "copilot",
    "cursor",
    "grok",
    "pi",
)

/**
 * 一个 Provider 的启动命令配置（契约 088 E14）。command 按空白分词，不经 shell。
 */
@Serializable
data class ProviderLaunch(
    val providerId: String,
    val command: String,
    val bypassFlag: String,
)

/**
 * 出厂默认（Bypass 列来自契约 088 §4 `--help` 实测，禁止改旗）。
 */
object ProviderLaunchDefaults {
    val all: List<ProviderLaunch> = listOf(
        ProviderLaunch("claude_code", "claude", "--dangerously-skip-permissions"),
        ProviderLaunch("codex", "codex", "--dangerously-bypass-approvals-and-sandbox"),
        ProviderLaunch("copilot", "copilot", "--yolo"),
        ProviderLaunch("cursor", "cursor-agent", "--force"),
        ProviderLaunch("grok", "grok", "--always-approve"),
        ProviderLaunch("pi", "pi", ""),
    )

    /**
     * @contract
     * @pre providerId 属于白名单
     * @post 返回该 id 的出厂默认
     * @err 未知 id 抛 IllegalArgumentException
     * @inv Pi 的 bypassFlag 为空串
     */
    fun byId(providerId: String): ProviderLaunch =
        all.firstOrNull { it.providerId == providerId }
            ?: throw IllegalArgumentException("unknown providerId: $providerId")
}

/**
 * 按空白 split（trim 空段），不走 shell。
 *
 * @contract
 * @pre 无
 * @post 返回非空 token 列表；全空白则空列表
 * @err none
 * @inv 不含空串；不调用 sh -c
 */
fun splitCommand(command: String): List<String> =
    command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * 组装新建 Agent 的 argv。bypass=true 时把 bypassFlag 追加到分词后的 command
 * （已在 argv 里则不重复）。Pi 的 bypassFlag 为空，勾选也不加旗。
 *
 * @contract
 * @pre launch.command 分词后至少 1 段（空命令得到空列表）
 * @post bypass=false 时 == splitCommand(command)；bypass=true 且旗非空且未在 argv 中则追加
 * @err none
 * @inv 从不经 shell；Grok 勾选 Bypass 时 argv 含该条 bypassFlag（出厂值即 always-approve 旗）
 */
fun buildArgv(launch: ProviderLaunch, bypass: Boolean): List<String> {
    val argv = splitCommand(launch.command)
    if (!bypass) return argv
    val flag = launch.bypassFlag.trim()
    if (flag.isEmpty()) return argv
    if (argv.contains(flag)) return argv
    return argv + flag
}
