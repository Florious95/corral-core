# 知识基底 · ledger.vzfix.v1 / t.live（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.live · vz-v1 验收打回返工（契约 083）

🔴 **本轮不是新功能，是独立验收席 `vz-v1-ver` 打回的红项。**
它的结论原话：**「单测几何公式绿 ≠ 屏幕墨迹绿」**。
⇒ **本格所有判据必须在模拟器上量出来，⛔ 不许用单测绿顶替。**

## 你这一格修什么：**顶栏指示灯不实时 + 「查看」菜单是空的（很可能同一条根因）**

🔴 **验收席已经指出这两条可能同根**：「overlay 空（`· 0`）很可能是同一条订阅断了」。
灯读的和查看菜单读的是**同一份 live 数据**。
⇒ **第一动作是查那条订阅，不是分别打两个补丁。**
⛔ 若查出来确实是两个根因，**在说明.md 里写清楚证据**再分别修；⛔ 不许没查就当两件事修。

### 红项 A：顶栏指示灯不跟随工作状态实时变色

验收席的实验（照抄，可复现）：**停在会话页、全程不切页**
```
终端正文写着该会话 working（「验收席 vz-v1-ver 正在干活」）
d480 灯 content-desc = Idle → 随后 Unknown
再停 10s：仍 Unknown（期望 Busy）
d420 灯 content-desc = Unknown
```
用户原话：「假如说你是空闲状态，我给你发了消息并且按了回车，这个指示灯它是不会变绿的…
**我希望它是根据它的工作状态实时去变化的**。」

🔴 **三态不得压成两态**（契约 062）：`Busy / Idle / Unknown` 三个都要在。
**`Unknown` 绝不能当 `Idle`** —— 那是把「不知道」染成「确定空闲」。
用户对灯色的裁定：工作=绿灯、空闲=灰灯、**未知=红灯**。

⚠️ 验收席记了一句关键的：单测 `VzVerifyLampFollowsLiveOverlayWithoutNavigation` **是绿的**，
「**模拟器世界没跟上**」。⇒ 单测在断言一个模拟器上不成立的世界，**修完要让单测和真机同时成立**。

### 红项 B：「查看」菜单没有当前显示名、没有会话行

验收席探针原始输出：
```
SHEET: ['切换会话', ' · 0']
FAIL 查看菜单没有当前显示名 '远程控制 app 开发'。屏上有: ['切换会话', ' · 0']
```
⇒ 期望：含**当前会话显示名** + 该工作区的**会话行**。实际只有标题和一个 ` · 0`（0 = 一个都没取到）。
这同时是 **076 §1 的回归**（「查看菜单按当前会话 ref 取数」曾判绿，现在退了）。

### 判据（⛔ 不许改，⛔ 不许只跑一个 density）
1. `A-lv-ui`：`.team/nodes/vzfix-live/ui-check.sh`（你写，⛔ trap 收尾不留后台进程），双 density 各跑一遍：
   - 不切页，让会话处于 working，断言灯 content-desc 变成 **Busy**（记下变化前后两个值 + 等待秒数）
   - 打开「查看」，断言 UI 树里**有当前显示名**，且会话行数 **> 0**
   🔴 **断言「世界变了」**：要记录**变化前**和**变化后**两个读数，⛔ 不许只断言「现在是 Busy」。
2. `A-lv-suite`：`./gradlew :app:testDebugUnitTest` 全量绿（不倒退）
3. `A-lv-verprobe`：**验收席的探针必须仍然能跑到 SHEET 那一步且不再 FAIL 这两条**
4. `A-lv-doc`：`说明.md` 非空

### 🔴 诊断日志纪律（本工程铁律，本格必做）
灯没变绿时，日志里只写「未触发」等于什么都没说。
**把参与比较的两边原始数值都记下来**，再记结论；并记**触发来源**（订阅推送 / 轮询 / 用户操作）。
判据是那句：**光看导出的日志，就能判断出是「该做而没做」还是「做了但做错了」。**


---
## 🔴 全格通用（违反任一条 = 本格红）

🔴🔴🔴 **开工第一件事**
```bash
cd /Volumes/nvme/Projects/远程Agent安卓 && pwd
```
`pwd` 必须输出仓根。**若输出里出现 `.worktrees/`，立刻 cd 回仓根**——
派单正文下方那段「## 工作目录」是框架自动附加的，**它是错的，以本条为准**。
（本工程已因此红过两次、返工两轮；改成这条自检后连续两格一次过。）
⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。

🔴 **先验红再改**：本格判据在改之前必须先红一次，红的原始输出贴进说明.md。⛔ 判据一个字不许改。
🔴 **一次修复一个提交**。⛔ 不要顺手改相邻代码。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进本格说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ ADB 一律 `$HOME/Library/Android/sdk/platform-tools/adb`。⛔ 截图前先关输入法 `adb shell input keyevent 111`。
✅ 模拟器 `emulator-5554`；先 `adb reverse tcp:9900 tcp:9900`；跑完 `adb shell wm density reset`。
🔴 渲染类必须 **d480（整数 3.0）与 d420（非整数 2.625）各跑一遍**（083 §0：模拟器在渲染层没有分辨力，整数密度会掩盖取整误差）。

🟢 **⛔ 不得倒退**：073 身份键含 socket / 075 一级转圈 / 076 / 077 / 078 §1 首列不被裁 / 081 cols 仪表 /
082 收藏页按各工作区取数 / land-v1 五格 / vz-v1 已判绿的 t.glyph·t.bg·t.chrome·t.diff。

## 上游材料（都在仓根，直接读）
- 独立验收报告（本轮红项的来源，**操作数都在里面**）：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-ver/验收报告.md`
- 验收探针（**判据脚本本体，⛔ 只读不改**）：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-ver/ui-check.sh`
- 契约：`/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md`

```

- write_paths: app/, .team/nodes/vzfix-live/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-ver/验收报告.md, /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-ver/ui-check.sh, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md
- 判据: A-lv-ui, A-lv-suite, A-lv-doc

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
- 标题引用条目：requirement-base/entries/083*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
