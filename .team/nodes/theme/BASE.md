# 知识基底 · ledger.ux2.v1 / t.theme（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
让 **APP 的终端主题与主机 Agent CLI 完全一致**。契约 requirement-base/entries/078-终端左侧遮挡与主题跟随CLI.md **§2**（先读）。
🖼 **左右对照图（必看）**：/Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/07-左侧遮挡与主题不一致.png
用户原话：「我希望 APP 上看到的和 Agent 的 CLI 上看到的**主题是完全一致的**。包括它的背景，各个对话的颜色等等。」

现状：主机 CLI 是**浅底深字**，APP 是**深蓝黑底浅字**。
🔴 **关键观察（省你一轮）**：其余颜色（`查看` 的紫、`uiautomator dump` 的蓝、`Improvising…` 的粉、`远控 leader` 的青底标签）两边**色相一致**
⇒ **ANSI 前景色通道是通的，坏的只是「背景 / 默认前景」这一层**。别去大改 ANSI 渲染。

🔴 **要查清并写进说明（⛔ 不猜）**：
① 主机终端的背景/默认前景**从哪儿取**——tmux `default-terminal` / OSC 11 查询 / 用户终端 profile？
② 传输面——现有会话帧加字段，还是握手时一次性下发？
③ 取不到时的兜底——**必须响亮可见地退回某个明确默认**，⛔ 不许静默用深色假装成功。

**判据 A-th-bg**：同一会话，APP 渲染的背景与主机终端一致（先支持「浅底/深底」这一档即可，不要求逐 RGB 相等）。
🔴🔴 **判据必须断言「数据来源」，不能只断言「现在是浅色」** —— 写死成浅色同样能让后者变绿，**那是骗判据**。
配套测试名须含 `TermTheme`（app 与 server 两侧都要）。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/ux-theme/说明.md。

**这条的分量**：APP 是主机终端的镜像，**镜子和实物不一致，用户就得在脑子里做一次换算**——那正是本产品要消灭的东西。

---
🔴🔴 **判据形态已升级（契约 077 §2，用户令：不要频繁多模态读图）**
用户原话：「你们要考虑一个以 MCP 为基础去测试的办法，而不是要频繁实图、频繁去识别图片、使用多模态去识别图片。」
⇒ **你必须产出一个可重跑的 UI 检查脚本** `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/ux-theme/ui-check.sh`：
   里面用 `adb` 导航到目标页面，再用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做**内容断言**（has / absent / distinct）。
   `uiassert.py` 已落地并正反测过，用法见 `--help` 或契约 077 §2。
🔴 **这个脚本就是本格的根因探针**：**改之前跑它必须红，改之后跑它必须绿**。红证据写进说明。
🔴 脚本要**自足**（自己导航、自己断言、自己收尾），⛔ 不许依赖「模拟器恰好停在某页」。
🔴 取不到 UI 树时必须响亮失败（uiassert 已内置）——尺子坏了和被测正常在输出上同形。
⛔ **截图降级为给人看的旁证**，≥1 张关键现场即可，⛔ 不再靠「md5 互不相同」当判据——
   那个连图里画的是什么都没看（2026-08-19 实发：交过一张半透明过渡态截图照样绿）。
⛔ **脚本跑完不许留后台进程**（2026-08-19 实发：探针漏 daemon/node 孤儿，攒了 12 个、最老 6h10m，
   还把调用方的判据卡死到超时）。用 `trap` 收尾。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。理由：所有判据 cwd 都是仓根，你改在 worktree 里判据什么都看不到，代码对了也全红。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住也不发消息，写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb；先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前关输入法。
✅ **用户裁定：模拟器 1:1 还原他手机**——「你们能够在这个 APP 上看到的，就是我手机上看到的」⇒ 模拟器验收 = 用户验收的等价物。
🟢 **已经好了、⛔ 本轮别动**：073 身份键含 socket、075 一级菜单转圈、076 三格（横滑抢竖滑/查看菜单取数/收藏页显示名与状态）。

```

- write_paths: app/, server/, .team/nodes/ux-theme/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/077-会话页标题仍用旧名与判据改用UI树.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/078-终端左侧遮挡与主题跟随CLI.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/
- 判据: A-th-test, A-th-suite, A-th-ui, A-th-shot, A-th-doc, A-th-go

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app, internal/api
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_bridge, go_internal_discovery, go_internal_protocol, kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：go_cmd_agentmirrord, kt_dev_agentmirror_app_service

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

## 3. 需求基
- 标题引用条目：requirement-base/entries/078*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
