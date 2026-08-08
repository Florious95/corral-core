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

package dev.agentmirror.app.termview

/**
 * 终端渲染：VT 解析 + 快照/增量渲染（60fps 本地滚动，需求 006）。
 *
 * 终端内核（Apache-2.0 兼容来源）规划在 :terminal 模块，本包承载 Compose 侧
 * 渲染画布与快照增量同步。本包为占位骨架，由 termview 任务落位实现。
 */
