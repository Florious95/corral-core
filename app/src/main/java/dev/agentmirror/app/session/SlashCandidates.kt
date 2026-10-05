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

package dev.agentmirror.app.session

import androidx.compose.ui.text.input.TextFieldValue

/*
 * Slash suggestions for the terminal dock's local draft (input sync off). Two honest sources only:
 * the CLI's own built-ins where they are documented for that provider, and the user's configured
 * shortcuts for that provider. Picking one only rewrites the local draft — nothing reaches the CLI
 * until the user presses send.
 */

/** One suggestion; [insert] replaces the whole draft. */
data class SlashCandidate(
    val key: String,
    val title: String,
    val detail: String,
    val insert: String,
    val shortcut: Boolean,
    /** Length of the query prefix [title] starts with, for highlighting; 0 when not a prefix. */
    val match: Int,
)

data class SlashBuiltin(val name: String, val description: String)

/**
 * Built-in commands per provider, registered only from the CLI's own documentation/binary:
 * Pi 1.0.0 docs/slash-commands.md; Codex TUI command table; Claude Code's stable core set.
 * Unknown providers get none — the panel then offers shortcuts only and says so.
 */
internal val ProviderSlashBuiltins: Map<String, List<SlashBuiltin>> = mapOf(
    "pi" to listOf(
        SlashBuiltin("model", "选择模型"),
        SlashBuiltin("thinking", "设置思考强度"),
        SlashBuiltin("compact", "压缩上下文，可附加说明"),
        SlashBuiltin("new", "开始一个新会话"),
        SlashBuiltin("resume", "切换到另一个已保存的会话"),
        SlashBuiltin("tree", "浏览会话树"),
        SlashBuiltin("fork", "从更早的一条消息分叉新会话"),
        SlashBuiltin("clone", "在当前位置复制会话"),
        SlashBuiltin("name", "设置会话名称"),
        SlashBuiltin("session", "查看会话信息与统计"),
        SlashBuiltin("copy", "复制最后一条回复"),
        SlashBuiltin("export", "导出为 HTML 或 JSONL"),
        SlashBuiltin("share", "上传会话并返回查看链接"),
        SlashBuiltin("settings", "打开设置"),
        SlashBuiltin("scoped-models", "配置循环切换的模型"),
        SlashBuiltin("login", "添加 Provider 认证"),
        SlashBuiltin("logout", "移除 Provider 认证"),
        SlashBuiltin("reload", "重载快捷键、扩展、技能与上下文文件"),
        SlashBuiltin("hotkeys", "查看快捷键"),
        SlashBuiltin("changelog", "查看更新日志"),
        SlashBuiltin("quit", "退出 Pi"),
    ),
    "codex" to listOf(
        SlashBuiltin("model", "选择模型与推理强度"),
        SlashBuiltin("new", "在对话中开始新聊天"),
        SlashBuiltin("compact", "总结对话，避免触及上下文上限"),
        SlashBuiltin("review", "审查当前改动并找出问题"),
        SlashBuiltin("diff", "查看 git diff（含未跟踪文件）"),
        SlashBuiltin("status", "查看会话配置与 token 用量"),
        SlashBuiltin("mention", "引用一个文件"),
        SlashBuiltin("init", "创建 AGENTS.md 说明文件"),
        SlashBuiltin("quit", "退出 Codex"),
    ),
    "claude_code" to listOf(
        SlashBuiltin("compact", "压缩对话，保留要点"),
        SlashBuiltin("clear", "清空对话历史"),
        SlashBuiltin("help", "查看帮助"),
        SlashBuiltin("model", "切换模型"),
        SlashBuiltin("resume", "恢复一个会话"),
        SlashBuiltin("review", "审查代码改动"),
        SlashBuiltin("init", "生成 CLAUDE.md"),
        SlashBuiltin("cost", "查看本次会话花费"),
    ),
)

/** The managed Pi worker's own console (RPC mode) understands only these. */
internal val ManagedConsoleBuiltins = listOf(
    SlashBuiltin("compact", "压缩上下文"),
    SlashBuiltin("new", "开始一个新会话"),
    SlashBuiltin("help", "查看可用命令"),
)

/**
 * Shared ranking (GUI and terminal): name prefix, then name contains, then description contains.
 * Null means no match. An empty query matches everything at rank 1.
 */
fun slashRank(query: String, name: String, description: String): Int? {
    val q = query.lowercase()
    val n = name.lowercase()
    return when {
        q.isEmpty() -> 1
        n.startsWith(q) -> 0
        n.contains(q) -> 1
        description.lowercase().contains(q) -> 2
        else -> null
    }
}

/**
 * The query a draft is asking for: `/` then a command word, caret at its end, no whitespace yet.
 * Null when the draft is anything else (prose, an argument already typed, a second line).
 */
fun slashQuery(draft: TextFieldValue): String? {
    val text = draft.text
    if (!text.startsWith("/") || text.any { it.isWhitespace() }) return null
    if (draft.selection.start != text.length || draft.selection.end != text.length) return null
    return text.substring(1)
}

/** Built-ins first (they are what `/` means in that CLI), then the user's own shortcuts. */
fun slashCandidates(query: String, builtins: List<SlashBuiltin>, shortcuts: List<ShortcutCommand>, provider: String): List<SlashCandidate> {
    val fromCli = builtins.mapNotNull { b ->
        val rank = slashRank(query, b.name, b.description) ?: return@mapNotNull null
        SlashCandidate("cli:${b.name}", "/${b.name}", b.description, "/${b.name} ", false, if (rank == 0) query.length else 0) to rank
    }
    val fromUser = shortcuts.mapNotNull { command ->
        val text = (resolveShortcutCommand(command, provider) as? ShortcutResolution.Found)?.text ?: return@mapNotNull null
        val word = text.removePrefix("/").substringBefore(' ').substringBefore('\n')
        val rank = listOfNotNull(slashRank(query, command.name, text), slashRank(query, word, "")).minOrNull() ?: return@mapNotNull null
        SlashCandidate("shortcut:${command.id}", command.name, text.lineSequence().first(), text, true, 0) to rank + 1
    }
    return (fromCli + fromUser).sortedBy { it.second }.map { it.first }
}
