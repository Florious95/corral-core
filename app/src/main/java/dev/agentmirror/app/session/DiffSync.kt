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

/**
 * 输入框差分同步（084）：CLI 光标约定在行尾，本地每次变化发最小按键。
 *
 * prefix = 公共前缀(已同步, 当前)
 * 发送 BackSpace × (len(已同步) - prefix) + 当前[prefix:]
 * 已同步 = 当前
 *
 * 纯追加时 prefix == len(已同步) ⇒ 退格 0，键序与逐键直通一致。
 * 不合并、不加延迟（084 §3 实时；§6 量完再决定，本轮不加 50ms 窗）。
 */
internal object DiffSync {
    data class Plan(val backspaces: Int, val typed: String) {
        val keyCount: Int get() = backspaces + typed.length
    }

    fun commonPrefixLength(synced: String, current: String): Int {
        val n = minOf(synced.length, current.length)
        var i = 0
        while (i < n && synced[i] == current[i]) i++
        return i
    }

    fun plan(synced: String, current: String): Plan {
        if (synced == current) return Plan(0, "")
        val prefix = commonPrefixLength(synced, current)
        return Plan(
            backspaces = synced.length - prefix,
            typed = current.substring(prefix),
        )
    }

    /** 行尾光标的 CLI 草稿上应用一次差分（单测断言「两边最终文本相等」）。 */
    fun applyTo(cli: StringBuilder, plan: Plan) {
        repeat(plan.backspaces) {
            if (cli.isNotEmpty()) cli.deleteAt(cli.lastIndex)
        }
        cli.append(plan.typed)
    }
}
