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

package dev.agentmirror.app.tsnet

/**
 * 内嵌 Tailscale 联网（需求 007/011 路线 a：服务端/App 内嵌 tailscale）。
 *
 * 提供 LAN 之外的可达通道：QR 可选携带 TS authkey，[TsnetManager] 用它在
 * App 进程内起 tsnet 用户态节点（gomobile 绑定 libs/tsnetbind.aar，无 VpnService、
 * 零系统权限），Up 后经 [TsnetDial] 给 OkHttp 配 loopback SOCKS5 即达 tailnet。
 *
 * 分层（native 隔离红线）：[TsnetBackend] 薄适配接口 → [GomobileTsnetBackend]
 * 唯一触达 native；状态机/authkey 校验/dial 选择均纯 JVM 可测。
 * 路线裁定与实测数字见 docs/decisions/app-tsnet.md。
 */
