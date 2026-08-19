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

import dev.agentmirror.app.diag.DiagLog

/**
 * 收藏账本：toggle 立刻落盘；对账时失联行保留并置灰（keep_gray），绝不按 live 丢掉。
 *
 * @contract
 * @pre store 可读写
 * @post toggle 后 load 即新集合；rows 按 addedAt 倒序
 * @inv 失联行仍在 rows 里，isOnline=false / gray=true
 */
class FavoriteBook(
    private val store: FavoriteStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    fun records(): List<FavoriteRecord> = store.load()

    fun isFavorited(sessionName: String, windowIndex: String, windowName: String): Boolean {
        val want = FavoriteKey(sessionName, windowIndex, windowName)
        for (rec in store.load()) {
            if (rec.key == want) return true
        }
        return false
    }

    fun toggle(sessionName: String, windowIndex: String, windowName: String) {
        if (sessionName.isEmpty() && windowIndex.isEmpty() && windowName.isEmpty()) return
        val want = FavoriteKey(sessionName, windowIndex, windowName)
        val current = store.load()
        val next = ArrayList<FavoriteRecord>(current.size + 1)
        var removed = false
        for (rec in current) {
            if (rec.key == want) {
                removed = true
            } else {
                next.add(rec)
            }
        }
        if (!removed) {
            next.add(
                FavoriteRecord(
                    sessionName = sessionName,
                    windowIndex = windowIndex,
                    windowName = windowName,
                    addedAt = nowMs(),
                ),
            )
        }
        store.save(next)
        DiagLog.record(
            "favorite",
            "toggle session_name=$sessionName window_index=$windowIndex window_name=$windowName " +
                "favorited=${!removed} stored_n=${next.size}",
        )
    }

    /**
     * 用 live 结构键对账。未命中 → isOnline=false（不在线 / gray），仍输出该行。
     */
    fun rows(live: List<L2Entry>): List<FavoriteRow> {
        val byKey = HashMap<FavoriteKey, L2Entry>()
        for (entry in live) {
            byKey[entry.favoriteKey()] = entry
        }
        val snapshot = store.load()
        val ordered = snapshot.sortedByDescending { it.addedAt }
        val out = ArrayList<FavoriteRow>(ordered.size)
        var onlineCount = 0
        for (rec in ordered) {
            val hit = byKey[rec.key]
            val isOnline = hit != null
            if (isOnline) onlineCount += 1
            out.add(
                FavoriteRow(
                    sessionName = rec.sessionName,
                    windowIndex = rec.windowIndex,
                    windowName = rec.windowName,
                    addedAt = rec.addedAt,
                    isOnline = isOnline,
                    ref = hit?.ref.orEmpty(),
                    cwd = hit?.cwd.orEmpty(),
                ),
            )
        }
        DiagLog.record(
            "favorite",
            "rows stored=${snapshot.size} live=${live.size} online=$onlineCount " +
                "offline=${snapshot.size - onlineCount}",
        )
        return out
    }
}
