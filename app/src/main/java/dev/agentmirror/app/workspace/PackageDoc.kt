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
 * 工作区：一级菜单（舰队 → 工作区），对应需求 001「舰队视角」、002「一级分组」。
 *
 * - [WorkspaceViewModel]：纯 JVM 视图模型，消费 listing/list_delta 与
 *   level2_frame / level2_heartbeat。二级只收推送，不轮询。
 * - [WorkspaceScreen] / [WorkspaceRow] / [L2SessionList]：一级工作区 + 二级会话列表。
 * - [FavoriteBook] / [FavoriteStore]：收藏落盘与失联置灰对账（067）。
 *
 * @consumes dev.agentmirror.app.conn
 * @consumes dev.agentmirror.app.tsnet
 * @consumes dev.agentmirror.app.ui.theme
 */
