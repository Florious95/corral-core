VERDICT: supports

# t.recon.rv · 审 pr/recon-foreground（契约 090：回前台 / 收藏页重连）

审的对象说明：分支 `pr/recon-foreground` 现指向 `822a4ee1b`（leader 代提交的单个封版 commit，
9 文件 +420/−2：7 个产品文件 + 说明.md + 测试）。r18 审时该分支尚零提交、改动在
`.worktrees/pr1.recon` 工作区；r19 已逐文件核对：**封版 commit 的全部 9 个文件与 r18
审过的工作区内容逐字节一致**（git show 对 diff -q，9/9 SAME），故以下按提交后的
`git diff main...pr/recon-foreground` 论述，行号不变。

## 1. diff 与说明逐条核对 —— 一致

- `conn/Connection.kt:43-44`：新增 `isTransportOpen = !closed && transport.isOpen`，与说明 L33 一致。
- `conn/ConnectionManager.kt:240-292`：新增 `onUiVisible(source)`。STOPPED→`start()`；
  RECONNECTING→清 `pendingReconnectAt` + 立即 `attemptConnect()`（与既有网络钩子 L229-236 同形，
  不会双拨：退避触发路径 L200-202 以 `pendingReconnectAt` 为闸，已被置 null）；
  READY 且传输已关→`failAllPending` + 置 `readyIsReconnect` + 立即重拨；
  CONNECTING/AUTHENTICATING→不动。与说明 L34 一致。
- `service/ServiceWire.kt:225-260`：共同入口 `onUiVisible(source)`——manager 空且有 config 则重建并
  `start()`（真拨号）；无 config 记日志返回 false（不猜地址）；否则转交 manager。与说明 L35 一致。
- `MainActivity.kt:135-136`：`onStart` 调 `ServiceWire.onUiVisible("lifecycle:ON_START")`（E17）。
- `workspace/WorkspaceViewModel.kt:491-492`：`enterFavorites` 调同一个
  `ServiceWire.onUiVisible("visibility:favorites")`（E18），体内无自己的 start/订阅——满足 F-090-3
  「共同入口」，正是 090 条目 §3 点名的修法。
- 两个 PackageDoc 补外骨骼说明。**无任何超范围改动**：没动 termview/SessionRoute（比例错归 t.geom），
  没顺手重构，没动既有判据文件。

## 2. 先验红 —— 有原始输出，且结构上必然为红

说明.md L46-58 给出改前原始输出：3 tests 3 failed，含逐条断言消息
（fg：`N=20 次里有 20 次回前台未发起新拨号 trials=[0..19]`；fav 同形；
once：`共同入口必须落在 ServiceWire.onUiVisible：`）。
交叉验证：① 测试文件对 `ServiceWire.onUiVisible` **零静态引用**（fg 走 `controller.start()` 触发
Activity 生命周期，fav 走 `vm.enterFavorites()`，once 是读源码文本），而它用到的
`managerOrNull/manager(...)/resetConfigForTest/servicePumpActive` 全部在 HEAD（改前）的
ServiceWire.kt L138/238/256/340 已存在——即该测试在改前**可编译可运行**，先验红可能且可信；
② 改前 `MainActivity.onStart` 只有 `DiagLog.record`（见 diff 上下文），无任何拨号路径，
20/20 全漏是结构必然，与引用的红输出吻合。

## 3. 判据非恒真 —— 断言的是 transport.create 计数，不是状态变量

`UiVisibleReconnectTest.kt:55-62` 用 `RecordingTransportFactory` 记录每次真实
`transport.create`；fg（L117-121）与 fav（L157-164）都断言 `created.size > before`，
循环 N=20（L52），一次都不许漏（L124-127/166-169）。fav 交替两种断法
（偶数 `peerClose`→RECONNECTING、奇数 `releaseManager`→manager 空，L152-156），
覆盖 ServiceWire 层「重建并 start」与 Manager 层「立即重拨」两条分支。
判据在改前必红、改后才可能绿——**能区分修没修**，非恒真。
验绿有据：worktree 内 `app/app/build/test-results/testDebugUnitTest/TEST-dev.agentmirror.app.service.UiVisibleReconnectTest.xml`
（timestamp 2026-08-20T16:56Z = 本地 08-21 00:56）tests=3 failures=0 errors=0。

## 4. 090 条目其余要求

- E17 回归考古：说明 L21-25 用 `git log -S` 指名 `4324624aa`（feat-fg-service-wiring，已核实存在）
  给出机理（泵入服务后回前台不再重建 SessionRoute），更早的明写「查不清，不编因果」——符合
  090 §1 的「找不到就明写查不清」。
- 诊断日志纪律：ConnectionManager.kt L254-261/285-291 与 ServiceWire.kt L240-246 都记了
  判据两侧操作数（state/pendingReconnectAt/transportOpen/manager/config）+ 触发来源 source
  + 结论 kicked，满足 090 §5。
- 棘轮：`.team/acclogs/A-recon-wiki.log`、`A-recon-smell.log` 存在且 exit 0（新增=0）。

## 5. 弱点（不构成否决，如实记录）

1. **A-recon-once 的负断言未演示过红**（L187-193）：「enterFavorites 不得自己 .start()」从未制造过
   命中条件；且其正则 `\{.*?\}` 非贪婪截断到函数体**第一个 `}`**（for 循环闭括号），此后若有人补
   `.start()` 该断言看不见。但 once 的第一断言（wire 无 `fun onUiVisible(`）在先验红里真实红过，
   且本条只是防未来回归的护栏——判「修没修好」的主判据是 fg/fav 的拨号计数，那两条红绿俱全。
   建议后续把正则改为括号配平或改用结构化检查。
2. `L161-162` `val touchedSession = false; if (touchedSession) ...` 是死代码（表意「明示不走会话装配」），无害。
3. 说明 L65-70 引用的绿 XML（time=2.776）与盘上现存 XML（time=0.174）是两次运行——两次都绿，
   盘上的是更晚一次，非篡改。
4. READY-且传输已关分支把旧 `connection` 直接置 null 未显式 `close()`（ConnectionManager.kt:277）——
   传输已死，泄漏面限于监听器引用，不影响判据。
5. `A-recon-suite.log` 为 0 字节（wiki/smell 两个 log 有内容）——回归抽测的证据在说明 L74 只有一句话，
   原始输出未归档。因 UiVisibleReconnectTest 的 XML 与同目录同时刻的 ColdStartReconnectTest 等
   XML 俱在（同一次 00:56 构建产出，failures=0），不改判。

## 结论

需求（E17 回前台重连、E18 收藏页重连、同一入口）在 diff 里有真实实现；先验红有原始输出且结构必然；
判据计的是真实拨号非状态变量、非恒真；无超范围改动、判据未被弱化以变绿。supports。

（审计路径备注：账本「只准写」列的是 `.worktrees/pr1.rv.conn/.team/nodes/pr1-recon-rv/`，
「必须交付」列的是仓根 `.team/nodes/pr1-recon-rv/verdict.md`，两者不是同一目录；
本文件按交付物条款写仓根，另在 worktree 路径留同内容副本。）
