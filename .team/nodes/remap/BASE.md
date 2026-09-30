# 知识基底 · ledger.theme.v1 / t.remap（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.remap · 「重新着色」投影规则（契约 085 §1.5 · 思路格）

🔴🔴🔴 **你这一格只出方案，⛔ 一行产品代码都不许改。** 写范围只有 `.team/nodes/theme-remap/`。
⛔ 不许碰 `app/`（它正被另一张账本占着施工），⛔ 不许跑 gradlew 构建。

## 起点：用户 2026-08-20 的澄清，它推翻了一个隐含前提

> 「那个主题就是**无论 Agent 的 CLI 它本身的主题是什么，它都可以按照那个方式去转换成目标的主题**。」

⇒ 终端是一层**重新着色**。CLI 自己发什么颜色都不作数——ANSI 索引、256 色、24 位真彩，
**最终画到屏幕上的都必须是选中主题的颜色**。用户选了 Dracula 就该看到 Dracula，
哪怕 CLI 死活在发别的。

先完整读契约 §1.5：`/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md`
再读思路席已产出的方案（本轮其余部分照它做，⛔ 不要重新设计）：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md`
再读**仓根现状**的唯一重着色漏斗：`/Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/ui/theme/TermPalette.kt`（158 行，`colorFor` 是入口）

## 现状（已核，⛔ 不必重核）

`TermPalette.colorFor(color, background, dark)` 已经是唯一漏斗，`t.bg` 格刚落地：
- `Indexed(254)` / 近白索引 → `userBlockBg`
- `Indexed(0/16)` → 纸色
- 其余 `Indexed` → `ansi16[i]`（**只覆盖 0..15，16..255 目前原样出**）
- `Rgb` 背景 → `guardRgbBg` 亮度守卫；🔴 **`Rgb` 前景直接 `raw` 原样画**

⇒ **256 色索引 16–255 和真彩前景，现在都漏出了主题。** 这正是本格要解决的。

## 你要产出什么

**唯一产物**：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-remap/投影规则.md`

要让实现席照着写就行。至少回答这七件事：

### 1. 投影规则本体
真彩 / 256 色 → 主题的 16+fg+bg，用什么距离？（sRGB 欧氏？加权？OKLab/CIE 感知距离？）
给出**选定方案 + 为什么不选另外两个**，并给出可手算验证的例子（列几组 输入RGB → 落到哪个槽位）。

### 2. 🔴 丢信息怎么办（本格最重要的一问）
语法高亮的十几种颜色投影到 16 色会塌成同一个。说清：
- 哪些塌是可接受的（同族深浅），哪些不可接受（两个语义不同的高亮变成同色）；
- 有没有办法在投影时**保持相对区分度**（例如先聚类再分配槽位、或前景保色相只归一亮度）；
- **代价**是什么，⛔ 不要假装没有代价。

### 3. 前景 vs 背景要不要用不同规则
背景已有亮度守卫（`guardRgbBg`）。前景现在是原样画。两者规则应当相同还是不同？给理由。
🔴 **可读性是硬约束**：投影后前景/背景对比度不能低到看不清（给一个可判定的下限，如 WCAG 对比度）。

### 4. 哪些情况**允许不投影**（例外清单）
例如：主题声明支持真彩透传？图片/sixel？用户显式关掉？
⛔ 例外必须**穷举并给判据**，不许留"视情况而定"。

### 5. 与 083 §2 / `t.bg` 已落地映射的合流
`t.bg` 那三条（索引 0/16→纸色、254/近白→userBlock、真彩背景亮度守卫）
在主题化之后**目标值换成主题色板**，机制不动。
说清：这三条特例在新规则下是**保留为特例**还是**被通用投影吸收**。⛔ 不得推翻 083 §2。

### 6. 判据设计（契约 §1.5 那条最硬的）
🔴 给定一段**固定输入字节流**（含默认色 + ANSI16 + 256 索引 + 24 位真彩），
主题 X 下渲染出的颜色集合 ⊆ X 的色板（容差内）；换 Y 后 ⊆ Y 的色板，且两集合不等。
你要给出：
- **这段字节流长什么样**（直接把可复制的转义序列写进方案，实现席照抄成测试夹具）；
- 集合怎么取（单测层面从 `colorFor` 取，还是像素采样？两者各给一条）；
- 容差取多少、为什么；
- 🔴 **先验红怎么红**：在今天的代码上这条判据必须红，说清红在哪一项。

### 7. 风险与查不清
⛔ 不许为了让方案完整而补一个说得通的因果。查不清就写查不清。

---
## 纪律

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，
一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件。
⛔ **本格不许改 `app/` 下任何文件，不许跑 gradlew**（`app/` 正被另一张账本占用施工）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进 `投影规则.md`。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。

```

- write_paths: .team/nodes/theme-remap/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md, /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/ui/theme/
- 判据: A-rm-doc, A-rm-sections, A-rm-fixture

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
- 标题引用条目：requirement-base/entries/085*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
