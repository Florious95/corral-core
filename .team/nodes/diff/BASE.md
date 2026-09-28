# 知识基底 · ledger.vz.v1 / t.diff（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
实现 **输入框差分同步**。契约 requirement-base/entries/084-输入框差分同步.md（先读全文）。
用户原话：「前面有个字要修改，很显然的，它是不支持的，因为它是**永远的增量模式**……有没有好的解决办法？」
→ leader 给三方案 → **用户选 C（差分同步）并要求实时**：「我希望它是**比较实时的**，而不是我发送那一刻，它在同步。」

**算法**：`prefix = 公共前缀长度(已同步, 当前)`；发 `BackSpace × (len(已同步)-prefix)` + `当前[prefix:]`；再 `已同步 = 当前`。
- 🔴 **常见路径零代价**：纯追加时 prefix == len(已同步) ⇒ **退格数 0**，键序与今天逐键直通**完全一致**。
- 🔴 **补全菜单必须保住**：CLI 仍收到逐键输入，`/` `@` `Tab` `↑↓` 照常。⛔ 不许改成"发送时整行提交"（用户已否掉该方案）。
- 不需要知道 CLI 光标位置——**约定同步后光标永远在行尾**。
🔴 **实时是硬要求**：每次文本变化立刻算差分立刻发。唯一允许延迟的是**中文输入法组合期**
（`TextFieldValue.composition != null`，那段文本还不是用户要的）：**组合期攒着、上屏立即发**；英文/数字**一个字符都不许延迟**。
⚠️ **前置**：t.chrome 已把 `BasicTextField` 改成 `TextFieldValue` 重载。
⚠️ **先量再决定要不要合并，⛔ 不许拍脑袋加延迟**：改中间一字可能连发十几个键，CLI 对快速连发退格的吞吐未知。
先量 **单次编辑发出的按键数** 与 **CLI 侧重绘耗时**，两个数写进说明；若要加合并，**延迟上限 50ms**并写明取舍。
**判据**（先验红）：`A-df-append` 纯追加退格数==0 且键序与旧行为一致；
`A-df-edit` 光标移中间删一字再插一字 ⇒ **两边最终文本相等**（⛔ 不许只断言"发了退格"）；
`A-df-ime` 组合期零按键、上屏 ≤1 帧内发出；`A-df-latency` 英文逐字无额外延迟。
配套单测名须含 `DiffSync`。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-diff/说明.md。

---
🔴🔴 **判据形态**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做内容断言，
配可重跑探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-chrome/ui-check.sh`（**改前必须红、改后必须绿**，红证据写进说明）。
颜色/尺寸 UI 树读不到 ⇒ **单测断言常量值**。⛔ 不许用「截图 md5 互不相同」当判据。⛔ 脚本 trap 收尾不留后台进程。

🔴🔴🔴 **模拟器在渲染层没有分辨力（契约 083 §0，本轮最重要的一条）**
用户原话：「上面那个展示的问题，**在模拟器上是完全正常的**，但是我在**手机上就完全不正常**。」
⇒ 密度 / 字体 fallback / 亚像素取整都不同，**模拟器绿 ≠ 真机绿**。
⇒ 凡是渲染类判据，**必须在至少两个不同 density 的 AVD 上各跑一遍**，
   其中**必须有一个非整数密度**（2.75 或 3.5）——整数密度会把取整误差掩盖掉。
   建 AVD 用 `~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager` + `-prop hw.lcd.density=...`，
   或用 `adb shell wm density 420` 临时改密度再跑（后者更快，⛔ 跑完必须 `wm density reset`）。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。所有判据 cwd 都是仓根。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb；先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前关输入法。
🟢 **⛔ 不得倒退**：073 身份键含 socket / 075 一级转圈 / 076 三条 / 077 §1 会话页标题 / 078 §1 首列不被裁 / 081 cols 仪表 / 082 收藏页按各工作区取数 / land-v1 设计落位五格。

```

- write_paths: app/, .team/nodes/vz-chrome/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/
- 判据: A-ch-test, A-ch-suite, A-ch-ui, A-ch-shot, A-ch-doc

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


---
以上是基底，以下是任务。

修 **两层间距 + 「已发送」+ 输入框光标**——契约 083 **§3 §4 §5**（先读）。
🖼 现场：/Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/12-已发送提示与卡片间距过大.jpg、/Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/13-输入框不能往前编辑.jpg

## §3 两层间距（用户：「这两个间距都要缩小」）
```kotlin
Surface(.padding(Dims.terminalCardMargin)   // 第一层：外 8dp
        color = currentTerminalPalette().background)
{ terminalContent() }                        // TerminalMetrics.paddingLeft/Right = 14dp ← 第二层
```
屏幕边到首字符 = 8 + 14 = **22dp**（density 3 时 66px），横向吃掉 3–4 个字符位。
**取值（leader 定）**：卡片外 **8→4dp**；终端内 **14→6dp**
（依据：`t.clip` 实测首列左溢 11–13px，6dp×3=18px 仍盖得住，⛔ 不要再往下调）。
🔴🔴 **联动必须一起做**：padding 减少 ⇒ 可用宽度增加 ⇒ **cols 变多** ⇒ CLI 可能切回宽布局
（用户真机上出现过的 Tips 双栏就是这么来的）。⇒ **必须同时加 cols 上限**，否则修一个坏一个。
上限取多少你来定，但要在说明里给依据（例如"保证 CJK 字符在 4.5–6.5 寸屏上不小于 X dp"）。

## §4 删「已发送」（用户：「很突兀，这个要删除」）
位置 `SessionScreen.kt:486` `is InputStatus.Sent -> "已发送"`，叠在终端内容上方。
🔴 **只删成功态，保留失败态**：那个状态机是「必达」语义的一部分
（`SessionViewModel.kt:104`：「ok 显示已发送 / fail+超时明确报错」）。
⇒ 默认无提示、**出错才出声**，符合 061「失败可见」红线。⛔ 不许把整个状态机删掉。

## §5 输入框不能往前编辑
**机理已定位**：`SessionShellScreen.kt:398` 用的是 `BasicTextField(value: String, onValueChange)` —— 
**String 重载不持有 `TextFieldValue`**，光标位置由 Compose 内部管理，
外部 `draft` 每次回写都把 selection 重置到末尾 ⇒ 光标移到中间后一重组就跳回去。
**修法**：改用 **`TextFieldValue` 重载**，由调用方持有含 `selection` 的状态。⛔ 不要只传 String。

**判据 A-ch-***（先验红）：
- `A-ch-cursor`：把光标移到文本中间 → 触发一次外部状态更新 → **selection 不变**（这条是本格核心）。
- `A-ch-gap`：断言屏幕边到首字符的总 padding == 10dp（4+6），且 cols 未超上限。
- `A-ch-sent`：发送成功后 UI 树里**读不到「已发送」**；而**模拟一次发送失败仍能读到错误提示**（🔴 这条防的是把整个状态机删掉）。
配套单测名须含 `ConsoleChrome`。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-chrome/说明.md。

---
🔴🔴 **判据形态**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做内容断言，
配可重跑探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-chrome/ui-check.sh`（**改前必须红、改后必须绿**，红证据写进说明）。
颜色/尺寸 UI 树读不到 ⇒ **单测断言常量值**。⛔ 不许用「截图 md5 互不相同」当判据。⛔ 脚本 trap 收尾不留后台进程。

🔴🔴🔴 **模拟器在渲染层没有分辨力（契约 083 §0，本轮最重要的一条）**
用户原话：「上面那个展示的问题，**在模拟器上是完全正常的**，但是我在**手机上就完全不正常**。」
⇒ 密度 / 字体 fallback / 亚像素取整都不同，**模拟器绿 ≠ 真机绿**。
⇒ 凡是渲染类判据，**必须在至少两个不同 density 的 AVD 上各跑一遍**，
   其中**必须有一个非整数密度**（2.75 或 3.5）——整数密度会把取整误差掩盖掉。
   建 AVD 用 `~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager` + `-prop hw.lcd.density=...`，
   或用 `adb shell wm density 420` 临时改密度再跑（后者更快，⛔ 跑完必须 `wm density reset`）。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。所有判据 cwd 都是仓根。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb；先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前关输入法。
🟢 **⛔ 不得倒退**：073 身份键含 socket / 075 一级转圈 / 076 三条 / 077 §1 会话页标题 / 078 §1 首列不被裁 / 081 cols 仪表 / 082 收藏页按各工作区取数 / land-v1 设计落位五格。


---
🔴 **本格追加两条（用户 2026-08-19 连报）**

## §6 `LanPill` 写死了 "LAN"，实际走的是 tailnet
用户原话：「我 APP 上右上角显示的是 LAN，但**实际上我是通过 TS 的网络去访问它的**，这可能是适配方面的疏忽。」
🖼 /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/16-重连提示位置错.png（右上角那颗 `LAN` 胶囊）

**根因已定位**：`design/compose-handoff/.../CommonUi.kt:188`
```kotlin
fun LanPill(modifier: Modifier = Modifier) {
    MicroPill("LAN", ...)      // ← 文案写死
}
```
⇒ **设计落位时把原本动态的连接类型文案照搬成了常量**。旧包显示 `tailnet` 是对的，是这次落位引入的回归。
**修法**：`LanPill` 接受连接类型参数，按真实连接通道显示（LAN / tailnet / …），
🔴 值必须来自**连接层的真实状态**，⛔ 不许按"能不能连通"猜。
**判据 `A-ch-net`**：走 tailnet 连接时 UI 树里读到的**不是** `LAN`；走局域网时才是 `LAN`。先验红。

## §7 重连/重试提示出现在屏幕顶端（标题上方），应在列表上部
用户原话：「它的重试、重连，它都是在我画红线的那块区域，**预期应该是在会话列表的上部**，而不是屏幕外面。」
🖼 同上图，红圈在**状态栏与标题之间**——提示被放到了内容区之外，被状态栏压着、几乎看不见。
**修法**：把重连/重试提示挪进**内容区顶部**（列表上方、标题下方），随内容一起受 inset 约束。
🔴 ⛔ 不得倒退 076 §2a（顶部空隙已经收紧），也不得让它把列表推下去造成跳动——用不占位的方式（overlay 在列表顶部）或固定高度条。
**判据 `A-ch-banner`**：断言提示的**顶边 ≥ 标题栏底边**（即在标题下方），且出现/消失时列表首行的 y 坐标**不变**（无跳动）。

## §8 顶栏「‹」返回图标与标题未光学对齐（用户 2026-08-19）
🖼 /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/17-顶栏返回图标与标题未光学对齐.png
用户原话：「顶部的字体和返回的那个图标，它**没有中心对齐**。」

**机理**：`‹` 是**文本字符**，它在 em box 里的墨迹偏上；而中文 `远控 leader` 的视觉重心偏下。
`Row(verticalAlignment = CenterVertically)` 对齐的是**排版盒**，不是**视觉重心** ⇒ 盒子对齐了，看着没对齐。
⇒ 这是**光学对齐**问题，⛔ 不是 `Alignment` 参数写错了，改 Alignment 修不好。

**修法二选一**（你定，写进说明）：
① 用矢量 Icon（`Icons.Rounded.ChevronLeft`）替代字符——图标的几何中心就是视觉中心；
② 保留字符但加一个**经实测的 baseline offset**（⛔ 不许拍脑袋填数，要在模拟器上量出偏差再定值）。
🔴 倾向 ①：字符方案在换字体/换语言时会重新失准，图标不会。

**判据 `A-ch-align`**：断言 `‹` 的**墨迹包围盒中心 y** 与标题文本的**墨迹包围盒中心 y** 之差 ≤ 1dp。
🔴 断言墨迹盒（实际画出来的像素范围），⛔ 不许断言布局盒——布局盒本来就是"对齐"的，那条判据恒绿。

## §9 系统弹出组件没跟随设计（拍照/相册菜单）—— **一处修、全部跟上**
🖼 /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/18-拍照菜单未跟随设计.png
用户原话：「拍照和上传文件，它似乎是最基本的组件，**没有经过一致性的设计**，和整体 APP 的设计不一致，使得它有点突兀。」

**根因已定位，⛔ 不要去单独给这个菜单套皮**：
`design/compose-handoff/.../Theme.kt:19` 的 `lightColorScheme(...)` **只覆盖了 15 个槽位**
（primary / background / surface / surfaceVariant / outline / scrim …），
**缺了 `surfaceContainer` / `surfaceContainerLow` / `surfaceContainerHigh` / `surfaceContainerHighest`**，
而 `DropdownMenu` / `Dialog` / `Snackbar` / `BottomSheet` / `ModalBottomSheet` 用的正是这几个
⇒ 落到 Material3 默认值（默认基色是紫）⇒ 你看到的淡紫方角菜单。

**修法**：把缺失槽位补进 `LightScheme` / `DarkScheme`，值取自 `AppPalette`。
🔴 **顺带扫一遍还有哪些槽位没覆盖**（`error` / `onError` / `errorContainer` / `inverseSurface` / `tertiary*` …），
一次补全，⛔ 不要等用户一个个报出来。补不了的（设计没给对应色）在说明里列出来，别静默留默认值。

**判据 `A-ch-scheme`**：单测断言 `LightScheme`/`DarkScheme` 中**每一个 Material3 槽位都不等于框架默认值**
（或显式列入"有意保留默认"的白名单）。🔴 这条能挡住"以后又冒出一个紫色弹窗"。
另在模拟器上打开 `+` 菜单截一张图作旁证。

## §10 顶栏状态指示灯不实时（用户 2026-08-19）
🖼 /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/19-顶栏指示灯不实时.png（红线指的就是那颗灯）
用户原话：「假如说你是空闲状态，我给你发了消息并且按了回车，这个指示灯它**是不会变绿的**，
我必须**切出去再切进来**，它才会变绿。我希望它是**根据它的工作状态实时去变化的**。」

🔴 **「切出去再切进来才对」= 典型的「只在进入时取一次快照，之后不再订阅」**——
和 076 §1（查看菜单读单例）、082（收藏页只对账一次）是同一族病根。
⇒ 会话页顶栏的 `RunningDot` 拿的是**进入会话那一刻的状态**，之后 CLI 从空闲转工作它不知道。

**修法**：顶栏状态改为**订阅**该会话的实时状态流（与二级菜单列表同一个数据源，
062/076 §3b 已定「状态来源必须和会话列表同一套」）。⛔ 不许在会话页另算一套。
🔴 ⛔ **不得靠轮询**（061 静默经济）：走既有的推送/事件通道。

**判据 `A-ch-lamp`**（先验红）：停在会话页不做任何切换，
让该会话从空闲转为工作 ⇒ **10 秒内**顶栏灯必须变绿；再转回空闲 ⇒ 变灰；未知 ⇒ 红。
🔴 **判据必须禁止"切出再切入"**——那正是现在能蒙混过关的路径。断言里不许有任何页面切换动作。
```

- write_paths: app/, .team/nodes/vz-diff/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/, .team/nodes/chrome/BASE.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/084-输入框差分同步.md
- 判据: A-df-test, A-df-suite, A-df-ui, A-df-shot, A-df-doc

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
- 标题引用条目：requirement-base/entries/083*, requirement-base/entries/084*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
