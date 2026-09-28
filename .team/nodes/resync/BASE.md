# 知识基底 · ledger.pr2.v1 / t.resync（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.resync · E1 · 控制键改写远端输入行后，用仿真器光标锚定回读校正 syncedText（契约 087）

🔴 **方案已由思路席位裁定，⛔ 你不要自己再选**：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr1-idea-input/思路.md` §0 明写
**选定方案 1「本地缓冲 + 光标锚定回读」，否决方案 2「镜像 + 按键捕获」，也否决「发送才同步」**。
改成别的 ⇒ E2–E5 与 087 验收全部作废。

**必须照它的 §2 落实的细节**：`resyncPending` 期间 `onPassthroughInput` 只更新本地框不发键；
回读完成后用 `DiffSync.plan(新synced, 本地当前)` 一次补齐；⛔ 回读不得在 `composition != null` 时覆盖；
用**仿真器光标**定位输入行，⛔ 不许写死行号；超时 400ms 且**失败必须可见，不静默**。
日志三个字段一个不能少：`resync_wait_ms`、`snapshot_gen`、`trigger=Tab|Esc|Up|Down|Ctrl-C`。

⛔ **084 §5 的四条判据（A-df-append / A-df-edit / A-df-ime / A-df-latency）必须仍然全绿**——
那是 F-087-3，破坏任一条即为失败。
---
## 🔴 流程（PR 链）
开工先跑并把输出贴进说明.md：`pwd` 与 `git branch --show-current`。
1. 建分支 `git checkout -b pr/e1-resync`，只改自己 worktree 里的文件。
2. ⛔ 不 commit、⛔ 不 push、⛔ 不并线 —— **封版由 leader 自动做**（判据 `A-resync-seal`
   在你报完后把改动提交到 `pr/e1-resync` 并断言分支非空）。⚠️ **报完别再改那棵 worktree**，改了就漂了。
3. ⛔ 不许写 `/tmp` 或任何项目外路径；临时文件写 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr2-resync/tmp/`（自己 mkdir -p）。
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

- write_paths: app/app/src/main/java/dev/agentmirror/app/session/, app/app/src/main/java/dev/agentmirror/app/termview/, .team/nodes/pr2-resync/
- read_paths: requirement-base/entries/087-输入行发散与输入区重做.md, requirement-base/entries/084-输入框差分同步.md, .team/nodes/pr1-idea-input/思路.md, .team/nodes/pr2-resync/说明.md
- 判据: A-resync-suite, A-resync-wiki, A-resync-smell, A-resync-doc, A-resync-seal

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app.session, dev.agentmirror.app.termview
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_tsnet, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_terminal
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app.termview

- **职责**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。
- **导出面**：ANSI_COLORS, DRAWABLE_EQUIVALENTS, GlyphFallbackPolicy, GlyphFontProvider, GlyphRunBuilder, GlyphSegment, GlyphSlot, TermSurfaceView, TermViewPresenter, XTERM_256
- **依赖边**：dev.agentmirror.terminal
- **doc 全文**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。 [TermViewPresenter] 纯 JVM 视口状态机（跟随/锁定历史、可见行窗口、捏合行列数换算、 脏区合并），单测全部打在它上；[TermSurfaceView] 薄 Android 层（Canvas 画格、拖动/捏合 手势、Choreographer 帧调度）。内核为 :terminal 模块；resize 协议帧由上层接线（conn/session）。 @consumes dev.agentmirror.terminal

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
