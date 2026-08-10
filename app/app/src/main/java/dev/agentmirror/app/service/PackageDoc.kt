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

package dev.agentmirror.app.service

/**
 * 前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
 *
 * 分层（fg-service 知识基底 §1）：
 * - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta
 *   流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。
 * - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链
 *   PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。
 * - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定
 *   [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。
 *   已在 manifest 声明，但**当前无任何生产代码启动它**（死件家族第六例，接线留待后案，
 *   见 fix-reconnect-stale-config 收口提交）——常驻连接实际由 [ServiceWire] 直接持有，
 *   配对/冷启动入口（`startPersistentConnection`）经 [ServiceWire.manager] 启动。
 * - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥、
 *   连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问。
 *
 * 电量策略（004 裁定）：仅在有活跃订阅或用户开启后台守望时运行前台服务；服务被系统杀 →
 * 冷启动重连即恢复（客户端无状态，没有丢失可言）。当前前台服务**未接线**：连接由
 * [ServiceWire.manager] 在配对成功/冷启动时直接启动（不经本服务），退避泵由在屏组合
 * 的 LaunchedEffect 驱动（[SessionScreen]/[PairingScreen]），服务启动留待后案。
 *
 * @consumes dev.agentmirror.app
 * @consumes dev.agentmirror.app.conn
 * @consumes dev.agentmirror.app.tsnet
 */
