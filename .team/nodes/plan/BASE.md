# 知识基底 · ledger.theme.v1 / t.plan（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.plan · 终端主题库落位「思路格」（契约 085）

🔴🔴🔴 **你这一格只出方案，⛔ 一行产品代码都不许改。** 写范围只有 `.team/nodes/theme-plan/`。
碰 `app/` 就是越界——那是下一格实现席的事，而且 `app/` 现在被另一张账本占着施工。

用户原话（2026-08-20）：
> 「这个绿色不太行。https://github.com/ZingerLittleBee/Heeler 你去看一下这个开源仓库，
> 然后把它的主题全部都适配一下，录到设置里。同时的话，你必须有两个角色去做。
> 第一个角色，给出思路。第二个角色，根据这个思路去做。因为它的开源仓库是 AGPL。」

**你就是第一个角色。**

---

## 🔴 先读契约（这是需求真相源，不是参考资料）

`/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md`

**它的 §0 许可裁定已经定死了，⛔ 不许推翻、不许"重新评估"**，你只负责把它落实成可执行方案：
- ⛔ 一行 Heeler 的 Swift 都不许抄（AGPL-3.0 强传染，我们是 Apache-2.0，抄了整个 APP 被拖下水）
- ✅ 色值直接从上游 `mbadolato/iTerm2-Color-Schemes`（**MIT**）取，⛔ 不从 Heeler 转手
- ✅ Heeler 只当"做哪些主题 / 设置页怎么组织"的**想法**参考（想法不受版权保护，代码表达受）
- 🔴 产物必须含 NOTICE，写明来源 + MIT + `Copyright (c) 2011-present Mark Badolato`

⚠️ 已核事实，⛔ 不必重复核、也⛔ 不许当成"待验证"：
`Sources/Heeler/Resources/Notices/GhosttyTheme-MIT.txt` 原文就写着色值来自 iTerm2-Color-Schemes、MIT。

---

## 你要产出什么

**唯一产物**：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-plan/方案.md`

它要让实现席**照着做就行、不需要再做一次调研**。至少回答这八件事：

### 1. 主题清单核对（**这是本格最花时间也最有价值的一块**）
契约 §1 列了约 30 族。逐个到 `mbadolato/iTerm2-Color-Schemes` 里核**准确文件名**
（`gh api` 取 repo tree 即可，⛔ 不必 clone）。同名不同拼写很常见（`Rose Pine` vs `RosePine` vs `rose-pine`）。
输出一张表：`我们的展示名 | 上游文件名 | 深/浅 | 核到没有`。
🔴 **核不到的明写"核不到"，⛔ 严禁拿一个相近的顶上去** —— 顶上去 = 用户选了 A 拿到 B，且没人会发现。

### 2. 数据怎么进包
上游是 `.itermcolors`（plist XML）/ `schemes/*` 多格式。定一个方案并说清取舍：
生成期转换成 Kotlin 常量？还是随包一份 JSON/资源文件运行期解析？
判断依据写出来：包体积、启动耗时、能不能被单测断言到**具体色值**（判据要求断言常量值，不是"非空"）。
🔴 如果选"生成"，**生成脚本也要你设计**（放 `tools/`），并说明它是一次性跑还是纳入构建。

### 3. 与 083 §2 已落地的深浅两套怎么合流（⛔ 不得倒退）
083 §2 已裁定并落地：APP 自带深浅两套、跟随系统自动适配；浅色 = 白底 + **灰底**用户消息块。
主题库是在那之上**换终端正文的 16 色 + fg/bg**，⛔ 不是把 083 §2 推翻重做。
说清：`TerminalSpec.kt` / `TermPalette.kt` 现有的色值哪些被主题接管、哪些仍归 APP 外壳。
⚠️ **`TermPalette.colorFor` 这条路径同时正在被另一张账本的 `t.bg` 格改动**（显式背景色重映射），
你的方案要写明**合流点在哪、谁先谁后**，⛔ 不要设计一个和它打架的结构。

### 4. 设置页信息架构
从 Heeler 键名读出的模型：`follow-system` / `terminal-theme-dark` / `terminal-theme-light`
⇒ **跟随系统开关 + 深色用哪个 + 浅色用哪个**（不是单选一个）。
说清落在现有设置页的哪一节、长什么样（列表？带预览？）、持久化到哪。
🔴 约 30 族 × 2 = 60 项，**滚动性能和搜索/分组**要有说法，⛔ 不要设计成一个 60 行的裸列表。

### 5. 默认值
用户此刻的抱怨是「这个绿色不太行」。方案要给出**新的默认主题**建议 + 理由，
并说明升级时老用户怎么迁移（有没有存过偏好、没存过按什么走）。

### 6. 判据细化（把契约 §2 那七条变成可执行命令）
每条写成实现席能直接跑的 `argv`。特别是第 5 条「切换真的改变了终端渲染」：
🔴 判据要断言**世界变了**——切前切后取色采样，断言背景色值**从 A 变成 B 且 A≠B**，
⛔ 不许只断言"设置项被点了"，⛔ 不许用"截图 md5 互不相同"（077 §2 已裁死）。
🔴 渲染类判据必须在**两个不同 density**的 AVD 上各跑一遍，其中一个**非整数密度**（083 §0：模拟器在渲染层没有分辨力）。

### 7. 拆格建议
实现是不是一格干得完？如果建议拆成多格，给出格名、写范围（必须两两不相交）、依赖顺序。

### 8. 风险与不确定
你查不清的、拿不准的，**明写"查不清"**。⛔ 不许为了让方案完整而补一个说得通的因果。

---

## 纪律

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，
一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。所有判据 cwd 都是仓根。
⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
（2026-08-19 实撞：上一格席位进了 worktree，判据在仓根跑 ⇒ 测试类找不到 ⇒ 必红，整张账本停机。）
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
⛔ **本格不许改 `app/` 下任何文件，不许跑 gradlew 构建**（`app/` 正被另一张账本占用施工）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进 `方案.md`。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ 查 GitHub 用 `gh api`（只读），⛔ 不要 clone 大仓。

```

- write_paths: .team/nodes/theme-plan/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/ui/theme/, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff
- 判据: A-plan-doc, A-plan-sections, A-plan-table

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
