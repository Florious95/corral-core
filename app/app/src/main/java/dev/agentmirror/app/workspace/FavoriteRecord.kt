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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 收藏身份：只用 tmux 结构字段。
 *
 * @contract
 * @pre 三元组一起构成身份；任一字段单独不构成键
 * @post 相等仅比较三个结构字段
 * @inv 不含展示摘要
 */
data class FavoriteKey(
    val sessionName: String,
    val windowIndex: String,
    val windowName: String,
)

/**
 * 落盘记录。字段只有结构身份 + 加入时刻。
 *
 * @contract
 * @pre addedAt 为加入时的 epoch ms
 * @post 往返 JSON 只含 session_name / window_index / window_name / added_at
 * @inv 不含展示摘要
 */
@Serializable
data class FavoriteRecord(
    @SerialName("session_name") val sessionName: String,
    @SerialName("window_index") val windowIndex: String,
    @SerialName("window_name") val windowName: String,
    @SerialName("added_at") val addedAt: Long,
) {
    val key: FavoriteKey
        get() = FavoriteKey(sessionName, windowIndex, windowName)
}

/**
 * 收藏行（左栏 / 对账结果）。失联行 isOnline=false、gray=true，标「不在线」。
 *
 * @contract
 * @pre isOnline 由当前 live 结构键是否命中决定，不改落盘
 * @post gray == !isOnline
 * @inv 落盘消失只能由用户取消收藏触发
 */
data class FavoriteRow(
    val sessionName: String,
    val windowIndex: String,
    val windowName: String,
    val addedAt: Long,
    val isOnline: Boolean,
    val ref: String = "",
    val cwd: String = "",
) {
    val gray: Boolean get() = !isOnline

    val identityLabel: String
        get() = windowName.ifEmpty { sessionName }

    val key: FavoriteKey
        get() = FavoriteKey(sessionName, windowIndex, windowName)
}

internal fun L2Entry.favoriteKey(): FavoriteKey = FavoriteKey(
    sessionName = sessionName,
    windowIndex = windowIndex,
    windowName = windowName,
)
