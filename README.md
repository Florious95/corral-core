# corral-core — Android 核心模块与接受参考

> **产品品牌：corral**。本仓库为 `Florious95/corral-core`，默认从远端 `main` 开始，提供 `app/core-*` 核心模块与 `app/app/` 的已接受参考源码；`app/app/` 不是日常 App 入口。
>
> 三仓职责：`Florious95/corral-core` 维护 Android 核心；`Florious95/corral-app` 是日常 Android App（默认从 `main` 构建，固定使用 `dev.agentmirror.core` 的 `20260913.52fc4bd` 发布依赖，发布源为本仓库 `maven` 提交 `7bdd02c1e1914a99df77ab8f1d8c2aab1652fbd5`）；`Florious95/corral-serve` 维护服务端当前实现与构建入口。接受核心/App基线为 `52fc4bd72e5d60fcf24cfa1ae43811ad23e4cfb7`，服务端基线为 `ca2bef1f47049760fd0748802a42decb29690646`。

**corral 是一面手机镜子，映出你主机上所有 tmux 里的 Agent 舰队。**

主机是唯一运行时，手机只是显示器 + 键盘。服务端是一个 **sidecar**——attach 到用户**已经在跑**的
tmux 会话上，不要求任何东西为它重启：启动那一刻扫描主机上所有 tmux server（含 team-agent 私有
socket），存量 Agent CLI 自动纳管，零迁移成本（需求 001）。

一句话定位：**herdr 的多 agent 感知 + moshi 的真实终端保真 + kittylitter 的扫码即连，但以"不动
用户的 tmux"为第一原则。** 产品面是投屏，技术面是 sidecar。

## 为什么自研

1. **非侵入**：不接受"agent 必须跑在我的客户端里才受控"（herdr 范式）。用户几十个 agent 已在
   tmux 里活着，"再开一遍"不可接受。
2. **单一 App**：不接受"终端 App + Tailscale App + SSH 配置"三件套凑功能（moshi 范式）。联网能力
   内置于本产品 App 与服务端。
3. **舰队视角**：现有产品都是单 agent 陪伴式交互；用户面对的是大量 agent（多工程 × 每工程一队），
   需要聚合导航。

## 架构总览

架构维基从源码现算（`tools/archwiki/build_wiki.py`，勿手改）：见 [docs/wiki/README.md](docs/wiki/README.md)
——依赖图、判据结果、每包架构卡。

```
┌────────────┐        WebSocket         ┌───────────────────────────────┐
│  Android    │ ◄──────────────────────► │  agentmirrord  (主机 sidecar)  │
│  App        │    JSON 控制帧 + 终端流    │  ├─ tsnetd   LAN + tailnet     │
│  com.agent… │                          │  ├─ api       WS + upload      │
└────────────┘                          │  ├─ bridge    单 pane 终端桥    │
                                        │  ├─ discovery tmux 多 socket 扫描│
                                        │  ├─ agentstate  per-agent 状态   │
                                        │  ├─ pairing    token + QR       │
                                        │  └─ config     flag + env       │
                                        └───────────────────────────────┘
                                            ▲ attach（不重启、不重构）
                                            ▼
                                        tmux server（含 team-agent 私有 socket）
```

- **镜像层零适配**：终端镜像任何 CLI 天然支持，永不因 CLI 升级而坏。
- **状态层严格隔离**：状态判不出降级 `unknown`，绝不影响镜像与输入的可用性（需求 008）。

## 快速开始

### 服务端：入口在 corral-serve

服务端不在本仓库；从 `Florious95/corral-serve` 的 `main` 开始，构建与运行入口为：

```bash
cd corral-serve
go build ./...
go run ./cmd/agentmirrord -listen 0.0.0.0:9900
```

### App：日常入口在 corral-app

日常 Android App 不从本仓库的 `app/app/` 构建；从 `Florious95/corral-app` 的 `main` 开始，固定依赖 `20260913.52fc4bd`：

```bash
cd corral-app
./gradlew --no-daemon --console=plain :app:assembleDebug
```

App 首次连接流程：服务端 stdout 打印配对二维码，Android 端扫码后进入舰队视图。

> 服务端无配置文件：全部配置来自 flag + 环境变量（sidecar 单二进制哲学）。

## 界面语言：当期锁定中文

产品界面（Android App 与终端交互文案）**当期锁定中文**。这是需求基的显式裁定，不是遗漏：
`requirement-base/entries/017-场景审计八项裁定.md` 的 **R-6** 裁定「当期锁中文并在 README 明示；
抽取翻译后置」。

- **本期界面只有中文一种语言**。App 界面、提示文案、终端内注入文案均为中文，不包含英文或其他语言。
- **国际化（i18n）与翻译抽取是已裁定的后置项，不是待办疏漏**。需求基已把这项工作安排在后续阶段，
  不在本期交付范围内——因此界面没有其他语言**不是 bug，也不是欢迎 PR 的开放缺口**。
- **对外部贡献者**：当前**不接受翻译类 PR**（把界面文案抽成资源文件、新增语言包、翻译现有文案等）。
  这不是不欢迎多语言，而是界面语言政策由需求基裁定，翻译抽取属后置工作，其启动时机与验收标准
  **尚未确定**——现在开工只会与后续既定安排冲突。将来开放以需求基就翻译抽取正式立项为条件，
  立项后本说明会同步更新。如果你对国际化有想法，欢迎在 issue/讨论区交流；翻译实现的 PR 请等立项后再提。

## 许可

Apache-2.0，见 [LICENSE](LICENSE)。
