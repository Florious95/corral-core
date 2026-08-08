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

package dev.agentmirror.app.conn

/**
 * 连接层：与主机 sidecar 的 WebSocket 连接、会话枚举、帧协议编解码。
 *
 * 传输协议见需求 011 裁定：JSON 控制帧（列表/订阅/输入/resize/scrollback/状态）+
 * 二进制终端流帧。本包为占位骨架，由 conn 任务落位实现。
 */
