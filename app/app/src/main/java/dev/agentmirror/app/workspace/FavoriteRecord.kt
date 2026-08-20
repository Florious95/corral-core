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
 * 收藏身份：与服务端 session ref 同源（socket + U+001F + pane id）。
 *
 * @contract
 * @pre ref 即 listing/level2 下发的 Session.ref，客户端不得另拼
 * @post 相等只比较 ref
 * @inv 不含展示摘要 / 标题
 */
data class FavoriteKey(
    val ref: String,
)

/**
 * 落盘记录。身份只有 ref；其余字段仅供失联时展示。
 *
 * @contract
 * @pre addedAt 为加入时的 epoch ms；ref 非空才是有效收藏
 * @post 往返 JSON 含 ref；不含 title
 * @inv 展示字段不参与相等
 */
@Serializable
data class FavoriteRecord(
    @SerialName("ref") val ref: String = "",
    @SerialName("session_name") val sessionName: String = "",
    @SerialName("window_index") val windowIndex: String = "",
    @SerialName("window_name") val windowName: String = "",
    @SerialName("cwd") val cwd: String = "",
    @SerialName("added_at") val addedAt: Long, @SerialName("provider") val provider: String = "",
) {
    val key: FavoriteKey
        get() = FavoriteKey(ref)
}

/**
 * 收藏行（左栏 / 对账结果）。失联行 isOnline=false、gray=true，标「不在线」。
 *
 * @contract
 * @pre isOnline 由当前 live 的 ref 是否命中决定，不改落盘
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
    val title: String = "",
    val status: L2Status = L2Status.UNKNOWN, val provider: String = "",
) {
    val gray: Boolean get() = !isOnline

    val identityLabel: String
        get() = sessionDisplayName(
            windowName = windowName,
            sessionName = sessionName,
            name = windowName,
            title = title,
        )

    val key: FavoriteKey
        get() = FavoriteKey(ref)
}

internal fun L2Entry.favoriteKey(): FavoriteKey = FavoriteKey(ref)
