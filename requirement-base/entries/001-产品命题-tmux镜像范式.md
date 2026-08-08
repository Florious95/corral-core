# 001 产品命题：tmux 的手机镜子（sidecar 范式）

- 状态：已裁定
- 出处：用户开场陈述 + leader 复述确认，2026-08-09 对话

## 观点

主机是唯一运行时，手机只是显示器 + 键盘。服务端是 sidecar——attach 到用户**已经在跑**的 tmux 会话上，
而不是要求任何东西为它重新启动。tmux 是唯一契约：服务端启动那一刻扫描主机上所有 tmux 会话，
存量 Agent CLI 自动纳管，零迁移成本。

## 三痛点（自研的全部理由）

1. **非侵入**：不接受"agent 必须跑在我的客户端里才受控"的产品（herdr 范式）。用户几十个 agent 已在 tmux 里活着，"再开一遍"不可接受。
2. **单一 App**：不接受"终端 App + Tailscale App + SSH 配置"三件套凑功能（moshi 范式）。联网能力内置于本产品 App 与服务端。
3. **舰队视角**：现有产品都是单 agent 陪伴式交互；用户面对的是大量 agent（多工程 × 每工程一队），需要聚合导航。

## 关键现实（通用产品必踩的坑）

用户的大量 agent 跑在 **team-agent 的私有 tmux socket** 上（不在默认 server）。
服务端必须枚举所有 tmux server（遍历 socket 目录），否则最大的 agent 群恰好不可见。

## 竞品定位一句话

herdr 的多 agent 感知 + moshi 的真实终端保真 + kittylitter 的扫码即连，
但以"不动用户的 tmux"为第一原则。产品面是投屏，技术面是 sidecar。
