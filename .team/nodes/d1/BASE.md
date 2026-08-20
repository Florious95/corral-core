# 知识基底 · ledger.pr1.v1 / t.d1（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.d1 · userBlockBg 写死成 APP 常量，永不跟主题（契约 089 §1）

`TermPalette.assembleSlot()`：`userBlockBg = app.userBlockBackground.toArgb()` —— 只认外壳深浅，不跟主题。
`TerminalSpec.kt` 深色外壳的该常量是 `0xFF10241F`。

**leader 像素实测（非推测）**：Grok 整块面板底 `#0f241f`、Claude Code 用户消息块底 `#0f241f`、
同屏普通终端底 `#1f1f27`。`#0f241f` 就是 `0xFF10241F`（JPEG 差 1）。
⇒ `defaultBg/defaultFg/ansi16` 这条链是通的，**唯独 userBlockBg 不换**，
它是**唯一一条逃出主题色板的路径**，违反契约 085 §1.5「任何颜色都必须投影到当前主题色板」。

## 方案（契约已定，⛔ 不要自己另选）
`userBlockBg` 从当前主题现算：
1. **优先用该主题自带的 `selection` 色**（`TermSchemeCatalog` 里有，语义＝被强调文本的底）；
2. **缺失、或与 `defaultFg` 对比度不达标时**，回退「从 `defaultBg` 朝 `defaultFg` 抬一档」。

## 判据
- `A-d1-theme`：至少三个差异明显的主题，断言 `colorFor(Indexed(254), background=true)` 输出**互不相同**，
  且每个输出可由该主题 scheme 派生。🔴 先验红：当前恒为 `0xFF10241F`。
- `A-d1-esc`：**穷举 256 索引 + 抽样真彩**，断言不存在任何输入使 `colorFor` 返回不属于当前主题派生集合的颜色。
  这条断言的是「逃逸路径已封死」，⛔ 不是「这一条改好了」。

## ⚠️ 明确告诉你，免得你以为没修好
**修完 D1 不会让 Grok 变回黑底。**绿色会变成「主题派生的用户块色」——颜色变了，
但「整屏被染色」是另一条缺陷（D2），由 `t.meter` 先补仪表再定位。⛔ 不要顺手去动 D2。

---
## 🔴 本轮流程：PR 链（一格一分支，判据过了才并线）

**开工第一件事，跑这两条自检，把输出贴进说明.md：**
```
pwd                        # 你必须在自己的 worktree 里，不是仓根
git branch --show-current
```

1. **建你自己的分支**：`git checkout -b pr/d1-userblock-theme`。
2. **只提交到本分支**。⛔ 不许并线、⛔ 不许碰 main、⛔ 不许 `git stash apply` 别人的改动。
3. **⛔ 你不要 push。** 本仓本地没配 remote，远端是 `tools/mirror-push.sh` 过滤后推的镜像仓，
   **PR 由 leader 代开**。你的交付＝分支名 + commit sha + 说明.md。
4. **⛔ 判据红了不许改判据让它变绿。** 判据本身写错 ⇒ 报 `blocked` 并指出错在哪，不要自己改。
5. **必须写合规的外骨骼注释** —— 架构维基从注释现算，注释不合规 ⇒ 维基缺节点缺边 ⇒
   下一个席位的知识基底是残的。你的机械判据含 `archwiki --check --strict-t3`，会红给你看。
6. **一次只修一个缺陷。** ⛔ 顺手改相邻代码 / 顺手重构 / 顺手改格式，全部禁止。
   每一行改动都要能追溯到本格需求。

## 🔴 判据纪律（三铁律）
- 判据要断言「世界变了」；**写完先验红**（改之前跑，必须红），再改，再验绿。
- **先验红的原始输出必须贴进 `说明.md`**，⛔ 没有先验红的绿不算数。
- 断言「某物不应出现」时**必须先制造出让它出现的条件**，否则是恒真判据。
- 判据**查代码内容，⛔ 不查 commit 身份**（revert/cherry-pick 会让「commit 在不在」说谎）。

## 说明.md 必须包含
分支名 / commit sha / `pwd` 与 `git branch --show-current` 的输出 / 改了哪些文件 /
**每条判据的先验红原始输出** / 每条判据的验绿原始输出 / 查不清的地方明写「查不清」。

```

- write_paths: app/app/src/main/java/dev/agentmirror/app/ui/theme/, .team/nodes/pr1-d1/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/089-重着色漏斗与瞬时提示成功态.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md
- 判据: A-d1-suite, A-d1-wiki, A-d1-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app.ui.theme
- 正向依赖（你消费的契约，只读）：无
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app, kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_workspace

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app.ui.theme

- **职责**：历史品牌深蓝（`0xFF1B2A4A`）。
- **导出面**：AgentMirrorTheme, DarkStateTones, LightStateTones, LocalStateTones, MonoFontFamily, Spacing, StateTone, StateTones
- **依赖边**：（无）
- **doc 全文**：历史品牌深蓝（`0xFF1B2A4A`）。 ui-redesign（018）后 M3 深浅两套 primary 均已改用独立取值（深 `0xFF9DBDFF`、 浅 `0xFF2F5DA8`），本 token 自彼时起无任何消费点——保留仅为存档（原 colors.xml 的 `brand_primary` 资源随 stage3 #19 删除，本 token 不再有资源对照）。

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.pairing

- **职责**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
- **导出面**：Failed, Pairing, PairingConfig, PairingConfigStore, PairingFailCause, PairingRoute, PairingScreen, PairingViewModel, QrParseException, QrPayload, QrPayloadParser, SharedPreferencesPairingConfigStore
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。 负责相机扫码、地址解析与配对握手、配置持久化与常驻连接装配；替代 "终端 App + Tailscale App + SSH 配置"三件套（需求 001 单一 App 原则）。 配对成功与冷启动重连共用 [startPersistentConnection] 作为唯一装配入口。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app.workspace

- **职责**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
- **导出面**：ConnectionUi, SessionUi, StateBadge, StateBadgeStyle, WorkspaceScreen, WorkspaceUi, WorkspaceUiState, WorkspaceViewModel
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。 - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。 - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。 二级导航经 [WorkspaceScreen] 的 onOpenSession 回调把 (ref, name) 交给根路由 [AgentMirrorApp]，由其挂载 [SessionRoute] 进入会话页。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
