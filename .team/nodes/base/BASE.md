# 知识基底 · ledger.land.v1 / t.base（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
**整包落位 + 三态扩展**，⛔ 本格**不改任何现有屏**。契约 requirement-base/entries/080-Compose设计包落位.md（先读）。

① 把 `/Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff/dev/agentmirror/app/ui/` 整个目录拷进 `app/app/src/main/java/dev/agentmirror/app/ui/`，与现有代码**并存**。
② **三态扩展**：`ui/model/Models.kt` 的 `SessionStatus` 从 `{ Busy, Idle }` 扩成 `{ Busy, Idle, Unknown }`；
   `ui/components/CommonUi.kt` 的 `StatusChip` 与 `ui/screens/SessionShellScreen.kt` 的 `RunningDot`（现签名 `running: Boolean`）都改成认三态。
   **灯色（用户直接指定）**：进行中=绿灯亮（**保留设计的脉冲动画**）/ 空闲=灰灯 / **未知=红灯**。
   红色请从 `DesignTokens.kt` 的调色板里取或新增一个 token，⛔ 不许在组件里写死色值。
③ 新增 token 时**放进 `AppPalette`**（浅/深各一份），⛔ 不要散落。
④ 确认整包**能编译**且不引入任何新依赖（`app/build.gradle.kts` ⛔ 一行不许动）。
⑤ 写一个映射函数把本工程现有的三态模型转成 `SessionStatus`，**⛔ 不许把 unknown 映射成 Idle**。

**判据**：`ui-check.sh` 此格断言编译产物存在即可（本格不改 UI，UI 树不会变）；
重点在单测 `LandBase`：断言 `SessionStatus` 有三个值、`StatusChip`/`RunningDot` 三态各自取到不同颜色、
且 unknown 的颜色 ≠ idle 的颜色（🔴 这条防的就是「把不知道染成确定空闲」）。
说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/land-base/说明.md。

---
🔴 **本轮总纲：这是「落位」，不是参考、不是重写。**
设计包已落库 `/Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff/`（13 个 .kt，leader 已审：零新依赖、纯展示、目录直接对位）。
规格书 `/Volumes/nvme/Projects/远程Agent安卓/design/终端会话页.dc.html`（1299 行，每个数值写死在 inline style 里）。
用户原话：「**这个样式我是完全看了，并且把关了的，能够还原就行了。**」
⇒ 🔴 **视觉一律照还原，⛔ 不许"优化"、不许换色、不许调间距。** 任何视觉偏差算没做完，不算改进。
⇒ ⛔ **不要打开 html 截图看**——数值在源码里，读源码比看渲染准（设计方 README 也这么要求）。

🔴 **三态按本工程，⛔ 不按设计的两态**：设计 `Models.kt` 是 `{ Busy, Idle }` 且注释说「映射由你那边做」——
**那句话在本工程是陷阱**。062 铁律是三态，`unknown` 绝不能当 `idle`（那是把「不知道」染成「确定空闲」）。
⇒ 扩成 `{ Busy, Idle, Unknown }`。**灯色由用户直接指定**：进行中=**绿灯亮**（保留脉冲）/ 空闲=**灰灯** / 未知=**红灯**。

🔴🔴 **判据形态（077 §2）**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做**内容断言**，
配一个可重跑的探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/land-base/ui-check.sh`（**改前跑必须红、改后跑必须绿**，红证据写进说明）。
颜色/尺寸 UI 树读不到 ⇒ **用单测断言 token 常量值**（比对 `/Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff/` 里的源文件）。
⛔ 不许用「截图 md5 互不相同」当判据——那连图里画的是什么都没看。截图留 ≥1 张给人看即可。
⛔ 脚本跑完不许留后台进程（用 trap 收尾）。

🔴 **⛔ 不得倒退这些已完成的**：073 身份键含 socket；075 一级菜单转圈；
076 §1 查看菜单按**当前会话 ref** 取数（不是收藏簿最后写入的）、§2 横滑不抢竖滑/顶部空隙/删右上角设置、§3 显示名与状态标；077 §1 会话页标题用显示名。
🔴 会话显示名**可能是中文**（`远控 leader`），⛔ 不得按 ASCII 宽度排版、不得截断。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。所有判据 cwd 都是仓根，你改在 worktree 里判据什么都看不到。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住也不发消息，写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb；先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前关输入法。
✅ 用户裁定：模拟器 1:1 还原他手机 ⇒ 模拟器验收 = 用户验收的等价物。

```

- write_paths: app/, .team/nodes/land-base/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/080-Compose设计包落位.md, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/design/终端会话页.dc.html, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/
- 判据: A-lb-test, A-lb-suite, A-lb-ui, A-lb-shot, A-lb-doc

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
- 标题引用条目：requirement-base/entries/080*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
