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
 *   已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start]
 *   启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。
 * - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥
 *   （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一
 *   [ConnectionManager]，服务与 UI 都经它访问同一单例。
 *
 * 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。
 * 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源
 * 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵
 * （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时
 * 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进），
 * 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。
 *
 * @consumes dev.agentmirror.app
 * @consumes dev.agentmirror.app.conn
 * @consumes dev.agentmirror.app.diag
 * @consumes dev.agentmirror.app.tsnet
 */
