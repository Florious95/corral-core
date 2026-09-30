# 知识基底 · ledger.perf.v1 / t.perf（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.perf · 主题重着色落地后终端卡顿（性能回归）

🔴 **用户实测原话（2026-08-20，装的是 `agentmirror-20260820-1437.apk`）**：
> 「**比上一版本卡顿了非常多**。」

上一版本 = 085 终端主题库落地**之前**的包。⇒ 高度怀疑是本轮引入的回归。

---
## 🔴 第一动作：**先量，不要猜**

⛔ **不许直接照着下面的假设去改。** 先拿到「时间花在哪」的读数，把 profile 结果写进
`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/perf-remap/说明.md`。
**如果量出来根因不是下面这条，就修你量到的那条**，并在说明里写明「leader 的假设被证伪」——
⛔ 严禁为了迎合假设而改一个不是瓶颈的地方。

### leader 的首要假设（**带证据，但仍需你验证**）

`/Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/ui/theme/TermPalette.kt` 的 `colorFor` → `project()` 路径**每格每帧**执行，且**没有任何缓存**：

```kotlin
// TermSurfaceView.kt:589 —— 绘制路径，逐格调用
private fun colorFor(color: TerminalColor, background: Boolean): Int =
    TermPalette.colorFor(color, background, isNight())
```

`project()` 每次调用做的事：
1. `toOkLab(argb)` —— 3 次 cbrt + 矩阵乘
2. `chromaGate()` —— **`slots.filter { ... }` 每次分配一个新 List**
3. `nearest()` —— 遍历最多 17 个槽算距离
4. 前景还要 `contrastRepair()` —— **又两次 filter + `minWithOrNull`**，某些分支还调 `DiagLog.record`

一屏 51×40 ≈ 2000 格 ×（前景+背景）≈ **4000 次投影/帧**，每次都有 List 分配。
⇒ 预期表现是 **GC 抖动 + 掉帧**，与用户描述吻合。

⚠️ 思路席在 `投影规则.md` 里预判过这条并判它可忽略（「候选最多 17 个槽…格子绘制相比可忽略」）。
**那个估计只算了单次成本，没算频次和分配。** 你要用读数推翻或证实它，⛔ 不要沿用它的结论。

### 也要排查的第二条（⛔ 不要跳过）
本轮 `t.live` 格新增了「会话页在屏必须订当前工作区」的订阅。
**若它导致高频重组，也会表现为卡顿。** 量一下重组/推送频率，给出读数。
两条都不是 ⇒ 照实写你量到的第三条。

---
## 🔴 修法的硬约束

1. ⛔⛔ **不许靠关掉主题重着色来提速。** 契约 085 §1.5 是用户明确要的语义
   （CLI 发什么颜色都不作数，一律投影到主题色板）。**删功能不是优化。**
2. ⛔ **不许改判据让它变绿。**
3. 🔴 **加缓存不得改变任何一个颜色的输出。** 这是本格最硬的一条——优化必须是**纯粹的等价变换**。
   见判据 `A-pf-equiv`。
4. 🔴 **绘制热路径里不许有 `DiagLog.record`**（它是诊断仪表，不该按帧调用）。
   仪表要保留，但改成**只在结果变化时**记一次（`t.dedup` 格已做过同类改造，见
   `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vzfix-dedup/说明.md`）。⛔ 不许把仪表整个删掉。

### 可选实现方向（**给方向不给方案，你量完自己定**）
- `argb → 结果` 的 memo（注意 key 要含 background / dark / againstBg）
- 256 索引里那 240 个扩展色是**有限集合**，可开跑时一次性预计算成表
- `filter` 换成预先分好的两个不可变 List（有色槽 / 无色槽），消灭每次分配
- 主题切换 / 深浅切换时**整体失效缓存**（`TermPalette` 已有 `cachedKey` 失效点，接上去）

---
## 判据（⛔ 一个字不许改）

| id | 内容 |
|---|---|
| `A-pf-equiv` | **等价性**：穷举 256 个索引 × 前景/背景 × 浅色/深色 + 一批固定真彩样本，逐一断言 `colorFor` 返回值**与优化前完全相同**（把优化前的值作为金样常量钉进测试）。任何一个不等 ⇒ 红。测试类名须含 `RemapEquivalence` |
| `A-pf-bench` | **吞吐微基准**（单测，类名须含 `RemapThroughput`）：连续 200000 次 `colorFor`（含真彩与 256 扩展）耗时低于你在说明里声明的上限；**优化前后两个数都要写进说明.md** |
| `A-pf-jank` | **真机掉帧**：你写 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/perf-remap/perf-check.sh`（⛔ trap 收尾不留后台进程）。固定工作负载下取 `adb shell dumpsys gfxinfo <包名>` 的 **Janky frames %** 与 **95th percentile**，**d480 与 d440 各一遍**。🔴 **断言世界变了**：记录**优化前**与**优化后**两组读数，断言 janky% 明显下降，且 p95 低于你声明的绝对上限 |
| `A-pf-suite` | `./gradlew :app:testDebugUnitTest` 全量绿（含 `TermBgRemapTest` / `TermSchemeCatalogTest` / `TermThemeTest` 不倒退） |
| `A-pf-doc` | `说明.md` 非空 |

🔴 `A-pf-jank` 的「优化前」读数**必须在你动代码之前先采**（先验红）。采完再改。

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
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ ADB 一律 `$HOME/Library/Android/sdk/platform-tools/adb`；模拟器 `emulator-5554`；
先 `adb reverse tcp:9900 tcp:9900`；截图前 `input keyevent 111`；跑完 `wm density reset`。
🔴 渲染/性能类必须 **d480（整数 3.0）与 d440（非整数 2.75）各跑一遍**（083 §0）。

🟢 **⛔ 不得倒退**：073 / 075 / 076 / 077 / 078 §1 / 081 / 082 / land-v1 五格 /
vz-v1 五格 / vzfix-v1 三格 / theme-v1 五格。**085 的重着色语义一个字都不许弱化。**

## 上游材料
- 契约 085（§1.5 重着色语义是硬约束）：`/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md`
- 投影规则（本轮 OKLab 方案的出处）：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-remap/投影规则.md`
- 热路径本体：`/Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/ui/theme/TermPalette.kt`（470 行）
- 绘制调用点：`/Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt:589`

```

- write_paths: app/, .team/nodes/perf-remap/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/085-终端主题库落位.md, /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/theme-remap/投影规则.md, /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vzfix-dedup/说明.md
- 判据: A-pf-equiv, A-pf-bench, A-pf-jank, A-pf-suite, A-pf-doc

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
- 标题引用条目：requirement-base/entries/085*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
