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

import dev.agentmirror.app.ui.model.SessionItem
import dev.agentmirror.app.ui.model.SessionStatus

/**
 * 088 E9：会话列表排序。
 *
 * @contract
 * @pre none
 * @post 顺序为收藏↓、运行中↓、名称↑（String.compareTo 当前 locale）；不改输入列表
 * @err none
 * @inv 不按 locale 首字母表
 */
fun sortSessions(items: List<SessionItem>): List<SessionItem> =
    items.sortedWith(
        compareByDescending<SessionItem> { it.starred }
            .thenByDescending { it.status == SessionStatus.Busy }
            .thenBy { it.displayName },
    )
