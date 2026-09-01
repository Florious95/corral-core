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

    fun isFavorited(ref: String): Boolean {
        if (ref.isEmpty()) return false
        val want = FavoriteKey(ref)
        for (rec in store.load()) {
            if (rec.key == want) return true
        }
        return false
    }

    fun toggle(
        ref: String,
        sessionName: String = "",
        windowIndex: String = "",
        windowName: String = "",
        cwd: String = "",
    ) {
        if (ref.isEmpty()) {
            DiagLog.record(
                "favorite",
                "toggle skipped empty ref session_name='$sessionName' " +
                    "window_index='$windowIndex' window_name='$windowName' cwd='$cwd'",
            )
            return
        }
        val want = FavoriteKey(ref)
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
                    ref = ref,
                    sessionName = sessionName,
                    windowIndex = windowIndex,
                    windowName = windowName,
                    cwd = cwd,
                    addedAt = nowMs(),
                ),
            )
        }
        store.save(next)
        DiagLog.record(
            "favorite",
            "toggle ref=$ref session_name=$sessionName window_index=$windowIndex " +
                "window_name=$windowName cwd=$cwd favorited=${!removed} stored_n=${next.size}",
        )
    }

    /**
     * 用 live 的 ref 对账。未命中 → isOnline=false（不在线 / gray），仍输出该行。
     * 082：live 必须覆盖**每个收藏项自己的工作区**，不能只拿最近进过的那一个。
     * 本函数不负责去取数；取数由 [WorkspaceViewModel.enterFavorites] 按工作区串行订阅。
     */
    fun rows(live: List<L2Entry>): List<FavoriteRow> {
        val byKey = HashMap<FavoriteKey, L2Entry>()
        for (entry in live) {
            byKey[entry.favoriteKey()] = entry
        }
        val snapshot = store.load().filter { it.ref.isNotEmpty() }
        val ordered = snapshot.sortedByDescending { it.addedAt }
        val out = ArrayList<FavoriteRow>(ordered.size)
        var onlineCount = 0
        for (rec in ordered) {
            val hit = byKey[rec.key]
            val isOnline = hit != null
            if (isOnline) onlineCount += 1
            val cwd = hit?.cwd?.takeIf { it.isNotEmpty() } ?: rec.cwd
            DiagLog.record(
                "favorite",
                "rows match stored_ref=${rec.ref} live_ref=${hit?.ref.orEmpty()} " +
                    "equal=${hit != null} cwd_stored=${rec.cwd} cwd_live=${hit?.cwd.orEmpty()} " +
                    "cwd_shown=$cwd",
            )
            out.add(
                FavoriteRow(
                    sessionName = hit?.sessionName?.takeIf { it.isNotEmpty() } ?: rec.sessionName,
                    windowIndex = hit?.windowIndex?.takeIf { it.isNotEmpty() } ?: rec.windowIndex,
                    windowName = hit?.windowName?.takeIf { it.isNotEmpty() } ?: rec.windowName,
                    addedAt = rec.addedAt,
                    isOnline = isOnline,
                    ref = rec.ref,
                    cwd = cwd,
                    title = hit?.title.orEmpty(),
                    status = hit?.status ?: L2Status.UNKNOWN,
                    provider = hit?.provider ?: "unknown",
                    activity = hit?.activity ?: L2Status.UNKNOWN,
                    health = hit?.health ?: "unknown",
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
