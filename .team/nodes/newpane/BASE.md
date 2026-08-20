# 知识基底 · ledger.pr2.v1 / t.newpane（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.newpane · E13 · 工作区页「+」新建 pane（选 Provider + 勾 Bypass）（契约 088）

方案 §1 第6条 + 契约 088 §4

⚠️ Bypass 参数以契约 088 §4 的实测表为准，**Grok 必须显式传 `--always-approve`**（用户本机 config 已开不代表别人开）。

🔴 **施工方案已定，⛔ 你不要自己另选**：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr1-idea-list/方案.md` 里对应小节写清了「改什么/外骨骼/判据」，**照它做**。
有异议报 `blocked` 说明，不要静默改方案。

---
## 🔴 流程（PR 链）
开工先跑并把输出贴进说明.md：`pwd` 与 `git branch --show-current`。
1. 建分支 `git checkout -b pr/e13-new-pane`，只改自己 worktree 里的文件。
2. ⛔ 不 commit、⛔ 不 push、⛔ 不并线 —— **封版由 leader 自动做**（判据 `A-newpane-seal`
   在你报完后把改动提交到 `pr/e13-new-pane` 并断言分支非空）。⚠️ **报完别再改那棵 worktree**，改了就漂了。
3. ⛔ 不许写 `/tmp` 或任何项目外路径；临时文件写 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr2-newpane/tmp/`（自己 mkdir -p）。
4. ⛔ 判据红了不许改判据让它变绿；判据本身写错 ⇒ 报 `blocked` 并指出错在哪。
5. **一次只改一个缺陷**，⛔ 顺手改相邻代码/重构/改格式一律禁止。

## 🔴 判据纪律
- 判据要断言「世界变了」；**先验红**（改之前跑，必须红）→ 改 → 验绿。
  **先验红的原始输出必须贴进说明.md**，⛔ 没有先验红的绿不算数。
- 断言「某物不应出现」必须先造出让它出现的条件，否则是恒真判据。
- 判据**查代码内容，⛔ 不查 commit 身份**。

## 🔴 两条常态判据：不许新增（⛔ 不是必须为 0）
main 上已有存量（app lint 16 条、archwiki T3 若干）。**不是你造成的，⛔ 不要去修** —— 修了 diff 就超范围。
两条判据都会**逐条点名新增了哪几条**（含文件行号）。⛔ 不许 `--freeze` 洗基线。T1 判据仍必须全绿。

## 说明.md 必须含
分支名 / `pwd` 与 `git branch --show-current` 的输出 / 改了哪些文件 /
**每条判据的先验红原始输出** / 验绿原始输出 / 查不清的明写「查不清」。

```

- write_paths: server/internal/api/, app/app/src/main/java/dev/agentmirror/app/workspace/, .team/nodes/pr2-newpane/
- read_paths: requirement-base/entries/088-会话列表与Agent生命周期.md, .team/nodes/pr1-idea-list/方案.md, .team/nodes/pr2-newpane/说明.md
- 判据: A-newpane-suite, A-newpane-wiki, A-newpane-smell, A-newpane-doc, A-newpane-seal

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app.workspace, internal/api
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_bridge, go_internal_discovery, go_internal_protocol, kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_tsnet, kt_dev_agentmirror_app_ui_theme
- **反向依赖（波及面 = 回归自查范围）**：go_cmd_agentmirrord, kt_dev_agentmirror_app

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app.workspace

- **职责**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
- **导出面**：ConnectionUi, SessionUi, StateBadge, StateBadgeStyle, WorkspaceScreen, WorkspaceUi, WorkspaceUiState, WorkspaceViewModel
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。 - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。 - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。 二级导航经 [WorkspaceScreen] 的 onOpenSession 回调把 (ref, name) 交给根路由 [AgentMirrorApp]，由其挂载 [SessionRoute] 进入会话页。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
