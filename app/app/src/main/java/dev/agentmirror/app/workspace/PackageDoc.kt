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
 * 工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
 *
 * - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 →
 *   UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。
 * - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。
 *
 * 二级导航进入会话页由根路由（AgentMirrorApp）占位跳转，会话页归 session-ui 任务。
 */
