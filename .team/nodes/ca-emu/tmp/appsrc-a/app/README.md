<!--
Copyright 2026 AgentMirror Project Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# AgentMirror App（Android 客户端）

本模块承载远程 Agent CLI 的「手机镜子」客户端：原生 Kotlin + Jetpack Compose（Material3），
依据需求 011 技术路线裁定。App 仅是渲染器 + 输入框，客户端彻底无状态（需求 004），
所有状态在主机侧 sidecar（见 `server/`）。

> applicationId 为暂名 `dev.agentmirror.app`，naming 任务定名后统一替换。

## 包分层（后续任务落位，本任务建立包骨架）

- `conn/` 连接层：与主机 sidecar 的 WebSocket 连接、会话枚举、帧协议编解码
- `workspace/` 工作区：两级导航（舰队 → 会话），对应需求 001 舰队视角
- `session/` 会话页：单个 tmux 会话的交互界面
- `termview/` 终端渲染：VT 解析 + 快照/增量渲染（内核见 `:terminal` 模块）
- `pairing/` 配对：扫码连接（路线 a，需求 011）
- `service/` 前台服务：常驻连接 + 通知栏（需求 004）
- `tsnet/` 内嵌 Tailscale 联网（需求 007/011 路线 a）

## 构建

```bash
cd app && ./gradlew -q :app:assembleDebug
```
