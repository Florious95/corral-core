# 知识基底 · ledger.ux4.v1 / t.draw（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.draw · 按 `t.idea` 的思路做真机可定罪的绘制性能改进

🔴 **本格照 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/ux4-idea/思路.md` 做，⛔ 不要自己重新设计。**
它写「查不清」的地方才轮到你判断。

## 背景（perf-v1 已经排除的东西，⛔ 不要重复走）
- **不是 085 主题投影**：`colorFor` 已优化 10×（857.6ns→87.4ns，等价变换），屏幕表现没变
- 基线包（085 之前）与当前包**一样卡**：janky% ~93、p95 32–34ms
- 大头在 `TermSurfaceView.onDraw` 铺格/`drawText`/GPU + 首帧 snapshot 到达
- 读数原件：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/perf-remap/说明.md`

## 🔴 硬约束
1. ⛔⛔ **不许用遮掩手段**（用户明令原话「不考虑特殊手段，比如重排好了才展示」）：
   延迟展示 / 等排好再画 / 占位 / 动画遮挡 / 把白屏换成主题底色 / 节流输入压 janky% ——**一个都不许**。
2. ⛔ **不许弱化 085 §1.5 重着色语义**，⛔ 不许倒退 083 已判绿的几何与 CJK 对齐。
3. 🔴 **必须留下真机可用的量具**：把 `onDraw` 耗时（以及你优化的那一段的耗时）
   打进诊断日志，**用户在自己手机上导出日志就能定罪**。
   ⛔ 不许只做一个模拟器上才能跑的验证——模拟器在渲染层没有分辨力（083 §0）。
4. 🔴 **诊断日志纪律**：有守卫/阈值/比较的地方，**参与比较的两边原始数值都要记**，再记结论；
   并记触发来源。⛔ 去重不是删仪表（参考 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vzfix-dedup/说明.md` 的做法）。

## 判据（⛔ 一个字不许改）
- `A-dw-ui`：你写 `.team/nodes/ux4-draw/perf-check.sh`（⛔ trap 收尾），双 density，
  **改前/改后两组读数**：`onDraw` 平均与 p95 耗时（来自你新加的仪表，不是 gfxinfo），
  断言 **p95 明显下降**；同时报 `gfxinfo` 的 janky%/p95 作参考（⛔ 不作为唯一判据，它含模拟器伪影）
- `A-dw-diag`：诊断日志里能读到 `onDraw` 耗时记录（判据 grep 你声明的 tag），
  且**同操作数不重复刷屏**（10s 窗口该 tag ≤ 你在说明里声明的上限）
- `A-dw-suite`：`./gradlew :app:testDebugUnitTest` 全量绿
- `A-dw-doc`：`说明.md` 非空，含改前/改后两组数与**风险**

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

- write_paths: app/, .team/nodes/ux4-draw/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/perf-remap/说明.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md
- 判据: A-dw-ui, A-dw-diag, A-dw-suite, A-dw-doc

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
