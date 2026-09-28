# 知识基底 · ledger.ux4.v1 / t.sent（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.sent · 「已发送」蓝色悬浮堆叠回归（契约 083 §12）

🔴 **用户原话（2026-08-20）**：
> 「有回归，那就是我发送消息，**键盘上面有已发送几个蓝色悬浮堆叠字**」

**这是回归，不是新缺陷**：083 原本就有「发送成功后无『已发送』」这一条，
`vz-v1` `t.ver` r19 独立验收判过**绿**（`两密度 uiassert absent 已发送` + 单测
`VzVerifySentHidesSuccessKeepsFailure`）。**判过绿，真机又出现了，而且变成"几个堆叠"。**

---
## 🔴 上一轮那个绿是怎么漏的（本格必须避免重蹈）

验收报告自己写着「**本场未在模拟器上注入一次真实发送失败**」，成功态也是看的**静态屏**。
⇒ **`absent 已发送` 在「根本没发生过发送」的屏上恒真。**
「发过了但没显示」和「压根没发过」，在那个断言下**完全同形**。

⇒ **本格铁律：断言「某物不应出现」之前，必须先制造出让它有机会出现的条件，
并在同一脚本里证明那个条件真的发生了。**

---
## 你要做什么

### ① 先复现（⛔ 复现不出来不许开工）
在模拟器上**真发一条消息**，然后：
- `uiassert has 已发送` 必须**先红**（= 屏上确实看得见），截图 + UI 树留证
- 🔴 同时证明**消息真的发出去了**（断言终端正文里出现了你发的那串内容），
  否则你复现的可能是另一个世界

### ② 定位
r19 判绿之后动过会话页/输入区的有：`vzfix-v1` 的 `t.live`/`t.align`、
`theme-v1` 的 `t.wire`/`t.settings`、`perf-v1` 的 `t.perf`。
⛔ **不许凭「最可能是谁」直接改**，先复现再定位，把定位证据写进说明.md。

### ③ 修
🔴 **「堆叠」是关键线索**：多个同时存在，说明很可能是**每次发送新建一个节点且从不移除**。
⇒ 修法要保证**节点被移除**，不是把它藏起来。

---
## 判据（⛔ 一个字不许改）
- `A-st-ui`：你写 `.team/nodes/ux4-sent/ui-check.sh`（⛔ trap 收尾不留后台进程），双 density：
  1. 先断言**消息真的发出去了**（终端正文里能找到你发的内容）
  2. 再断言同一时刻屏上「已发送」节点数 **== 0**（🔴 **要数个数，不只是 absent**——
     「堆叠」说明只测有无会漏掉有几个）
  3. **失败态仍在**：构造一次发送失败，断言「发送失败」提示**仍然显示**
     （⛔ 不许为了让成功态消失把失败提示一起删）
  4. 每步把**改前/改后两个读数**都打进日志
- `A-st-suite`：`./gradlew :app:testDebugUnitTest` 全量绿
- `A-st-doc`：`说明.md` 非空

⚠️ 本条被测对象**就在键盘上方**，关输入法可能让它不可见 ⇒ **允许在开着输入法的状态下取 UI 树**，
但⛔ 截图注意不要把输入法候选栏里的敏感内容拍进去（凭据红线）。

✅ ADB 一律 `$HOME/Library/Android/sdk/platform-tools/adb`；模拟器 `emulator-5554`；
先 `adb reverse tcp:9900 tcp:9900`；跑完 `adb shell wm density reset`。
🔴 渲染类必须 **d480（整数 3.0）与 d440（非整数 2.75）各跑一遍**（083 §0：模拟器在渲染层没有分辨力）。
🟢 **⛔ 不得倒退**：073 / 075 / 076 / 077 / 078 §1 / 081 / 082 / land-v1 五格 / vz-v1 五格 /
vzfix-v1 三格 / theme-v1 五格 / perf-v1（`colorFor` 的等价投影与 10× 优化）。
**085 §1.5 的重着色语义一个字都不许弱化。**

---
## 全格通用（违反任一条 = 本格红）

🔴🔴🔴 **开工第一件事**
```
cd /Volumes/nvme/Projects/远程Agent安卓 && pwd
```
`pwd` 必须输出仓根。**若输出里出现 `.worktrees/`，立刻 cd 回仓根**——
派单正文下方那段「## 工作目录」是框架自动附加的，**它是错的，以本条为准**。
⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
🔴 **一次修复一个提交**。⛔ 不要顺手改相邻代码。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。⛔ 禁读 `.team/current/profiles/*.env`。

```

- write_paths: app/, .team/nodes/ux4-sent/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/perf-remap/说明.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md
- 判据: A-st-ui, A-st-suite, A-st-doc

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
