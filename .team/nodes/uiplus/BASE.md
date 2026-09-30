# 知识基底 · ledger.hl1.v1 / t.uiplus（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.uiplus · 「+ Agent」弹窗重做（契约 092 §2，分支 pr/ui-agent-dialog）

用户：「+agent 的 ui 过于简陋」。指工作区右上角 + 号开 Agent 的界面（选 Provider、勾 bypass，088 §4）。

## 要求
- 与全 app 视觉互洽（Material3、既有配色/间距/圆角语言），参考成熟 Agent APP 的选择器样式。
- Provider 选择用带卡通图标的卡片/列表（图标资源已在 app 里，088 §3 那套），不是裸 RadioButton。
- bypass 勾选带一句说明文案；确认按钮主色、置底。
- 功能不回退：创建会话的 argv 组装逻辑不变（088 §7 契约：服务端原样执行）。
## 交付
- 代码 + 必要单测；说明.md 必含 `截图前=`、`截图后=`（模拟器实拍，装你分支的 debug 包）、`改点=`。

## 模拟器与隔离环境（契约 092 §4，照抄，别自创）
- 模拟器 emulator-5554 已在跑（leader 起的，共享基础设施，⛔ 不要自己再起/杀模拟器）。
  adb = ~/Library/Android/sdk/platform-tools/adb。
- 隔离 tmux + 测试 daemon 照 e2e/layer2.sh 的做法；socket 必须自检落在自己 TMPD：
  `TMUX_TMPDIR=<你的tmp> tmux -f /dev/null list-sessions` 能看到自己的会话才算。
- 假 CLI：`ln -s /bin/bash <dir>/claude`（⛔ cp 出的 bash 跑不起来；shebang 脚本 comm=bash 不命中白名单）。
- app 冷启前 `pm clear dev.agentmirror.app`（模拟器里有指向已死端口的旧配对档案）；
  手填配对 ws://10.0.2.2:<你的端口>/ws + 你自己的 token。
- 🔴 测试 daemon 会扫到真实舰队的 tmux —— ⛔⛔ 绝不许在 app 里点开任何真实会话，
  只许点你隔离造出来的那个（cwd 是你自己的 tmp 目录）。
- 截图先关输入法：`adb shell input keyevent 111`。


---
## 🔴 本轮流程：PR 链（一格一分支，判据过了才并线）

**开工第一件事，跑这两条自检，把输出贴进说明.md：**
```
pwd                        # 你必须在自己的 worktree 里，不是仓根
git branch --show-current
```

1. **建你自己的分支**：`git checkout -b pr/ui-agent-dialog`。
2. **只提交到本分支**。⛔ 不许并线、⛔ 不许碰 main、⛔ 不许 `git stash apply` 别人的改动。
3. **⛔ 你不要 push。** PR 由 leader 代开。你的交付＝分支名 + commit sha + 说明.md。
4. **⛔ 判据红了不许改判据让它变绿。** 判据本身写错 ⇒ 报 blocked 并指出错在哪。
5. **先验红**：改之前把判据/探针跑一遍，把红的原始输出贴进说明.md。没有先验红 ⇒ 评审直接 refutes。
6. 代码注释符合外骨骼标准（看基底里的规范引用；archwiki 棘轮会验）。
7. 临时文件只写 `.team/nodes/hl1-uiplus/tmp/`，⛔ 不写 /tmp、不写任何项目外路径。
8. 交付物全落盘后才 report_result；如实报不可判是合法出口，⛔ 不许造假。

```

- write_paths: app/
- read_paths: requirement-base/entries/092-会话页白屏回归与两处简陋UI.md, requirement-base/entries/088-会话列表与Agent生命周期.md
- 判据: A-uiplus-suite, A-uiplus-wiki, A-uiplus-smell, A-uiplus-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app_service

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
