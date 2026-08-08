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
 * - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent。
 * - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定
 *   [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。
 * - [ServiceWire]：接线点——传输工厂（默认 [NoopTransportFactory]）、UI 监听桥、连接配置注入。
 *
 * 电量策略（004 裁定）：仅在有活跃订阅或用户开启后台守望时运行前台服务；服务被系统杀 →
 * 冷启动重连即恢复（客户端无状态，没有丢失可言）。UI/配对层经 [ServiceWire] 控制启动/停止。
 */
