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

package dev.agentmirror.app.pairing

/**
 * 配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
 *
 * 负责相机扫码、地址解析与配对握手、配置持久化与常驻连接装配；替代
 * "终端 App + Tailscale App + SSH 配置"三件套（需求 001 单一 App 原则）。
 * 配对成功与冷启动重连共用 [startPersistentConnection] 作为唯一装配入口。
 *
 * @consumes dev.agentmirror.app.conn
 * @consumes dev.agentmirror.app.service
 * @consumes dev.agentmirror.app.tsnet
 * @consumes dev.agentmirror.app.ui.theme
 */
