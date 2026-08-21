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

/**
 * 新建 Agent 的 argv 组装（088 E13）。E14 的启动命令 store 尚未并入 main 时，
 * 本格用契约 §4 出厂表；不经 shell。
 */
object NewAgentProviders {
    val ids: List<String> = listOf(
        "claude_code", "codex", "copilot", "cursor", "grok", "pi",
    )

    fun displayName(id: String): String = when (id) {
        "claude_code" -> "Claude Code"
        "codex" -> "Codex"
        "copilot" -> "Copilot"
        "cursor" -> "Cursor"
        "grok" -> "Grok"
        "pi" -> "Pi"
        else -> id
    }
}

private val defaultCommand = mapOf(
    "claude_code" to "claude",
    "codex" to "codex",
    "copilot" to "copilot",
    "cursor" to "cursor-agent",
    "grok" to "grok",
    "pi" to "pi",
)

private val defaultBypass = mapOf(
    "claude_code" to "--dangerously-skip-permissions",
    "codex" to "--dangerously-bypass-approvals-and-sandbox",
    "copilot" to "--yolo",
    "cursor" to "--force",
    "grok" to "--always-approve",
    "pi" to "",
)

/**
 * 按契约 088 §4 组装 argv。Grok + bypass 必含 always-approve 旗。
 *
 * @contract
 * @pre providerId 为白名单 id
 * @post bypass=false 时仅为命令分词；bypass=true 时追加出厂旗（Pi 不加）
 * @err 未知 id 返回仅含 id 的列表
 * @inv 不经 shell
 */
fun buildNewAgentArgv(providerId: String, bypass: Boolean): List<String> {
    val cmd = defaultCommand[providerId] ?: providerId
    val argv = cmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (!bypass) return argv
    val flag = defaultBypass[providerId].orEmpty()
    if (flag.isEmpty()) return argv
    if (argv.contains(flag)) return argv
    return argv + flag
}
