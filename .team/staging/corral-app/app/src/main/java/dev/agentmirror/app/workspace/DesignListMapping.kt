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
import dev.agentmirror.app.ui.model.WorkspaceItem
import dev.agentmirror.app.ui.model.sessionStatusFromL2

/** 一级 cwd 聚合 → 设计包 [WorkspaceItem]。显示名取末段，路径不截断。 */
internal fun WorkspaceUi.toWorkspaceItem(): WorkspaceItem = WorkspaceItem(
    id = cwd,
    name = cwdDisplayName(cwd),
    path = cwd,
    sessionCount = sessionCount,
)

/** 二级行 → 设计包 [SessionItem]。displayName 走 076 §3a，不预截断。 */
internal fun L2Entry.toSessionItem(starred: Boolean): SessionItem = SessionItem(
    id = ref,
    displayName = identityLabel,
    path = cwd,
    status = sessionStatusFromL2(status),
    starred = starred,
    isOnline = true,
)

/** 收藏对账行 → 设计包 [SessionItem]。失联仍输出，isOnline=false。 */
internal fun FavoriteRow.toSessionItem(): SessionItem = SessionItem(
    id = ref.ifEmpty { "legacy-$addedAt-$sessionName-$windowIndex-$windowName" },
    displayName = identityLabel,
    path = cwd,
    status = sessionStatusFromL2(status),
    starred = true,
    isOnline = isOnline,
)
