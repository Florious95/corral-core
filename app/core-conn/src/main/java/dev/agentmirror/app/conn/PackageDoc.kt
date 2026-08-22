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
 * 分层（自底向上）：
 * 1. [FrameCodec] / [BinaryFrameCodec] —— 纯函数编解码：JSON 控制帧（信封 + 12 类帧
 *    载荷，docs/protocol.md §4）+ 二进制流帧（§6，含 scrollback 12 字节收敛区间头）。
 *    编解码都消费同一份契约夹具做字节级断言（server/internal/protocol/testdata/）。
 * 2. [Connection] —— 单条 WS 生命周期状态机（握手 → 就绪 → 关闭）。
 * 3. [ConnectionManager] —— 重连策略 + 订阅簿记：重连后自动重放 auth + 全部活跃
 *    subscribe（004 无状态铁律的重放语义）；listing seq 不连续 → 自动重新 list。
 *
 * 上层（UI/service）只见回调（[ConnectionManager.Listener] / [Connection.Listener]），不见
 * WS 细节。本层不持久任何会话状态。
 *
 * 诊断/仪表出口经 [ConnDiag]/[ConnPerf] 由 app 壳注入（核模块零 Android）。
 * addBinaryListener 按 ref 分发仍在本模块。
 */
