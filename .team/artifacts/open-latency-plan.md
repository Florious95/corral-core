## 总判断

**2s 门能达成，但不是靠现有这批 app 侧条目——首帧 1794ms 里约 1.67s 花在「Subscribe 帧还没被服务端开始处理」上，必须先修 readLoop 串行 + 全量扫描，才轮得到几何/收帧口那些百毫秒级的项。**

算术（可证伪，来自 probe16 自己的操作数）：`snapshot#2 - snapshot#1 = 1873-1794 = 79ms`，而这段是一次完整 `handleResize` = `br.Size`(1) + `br.Resize`(windowID+set-option+resize-window+Size=4) + `br.Size`(1) + `snapshotWithCursor`(Snapshot+CursorPos=2) = **8 次串行 tmux exec** ⇒ 当时 ≈10ms/exec。DUT 的 `handleSubscribe`（pr/srv-first-frame）是 Size+preSnap2+pipe+Resize4+postSnap2 ≈ 11 次 exec ≈ **110ms**。而实测 subscribe 发出(10ms)→首帧(1794ms) = 1784ms。⇒ **约 1670ms 与 handleSubscribe 本体无关**，只能发生在它被调用之前，即 `ws_conn.go:366-370` 的 readLoop 里排在前面的那一帧还没处理完。前面那一帧几乎必然是进会话时发的 `Level2Subscribe`（`WorkspaceViewModel.kt:596 enterSessionLive`）或 L2 的 `List`，两者都走全量 `Discover` + `ps -axo`：本机 `/private/tmp/tmux-501` 有 **23 个 socket**，`discovery/scan.go:71-83` 逐 socket 串行 exec（死 socket 也各花一次，超时上限 5s/个），叠上 `proctree.go` 的全表 `ps` 且 `snapshot()` 全程持 `f.mu`。这同时解释了为什么裸客户端 108ms（sprobe2 只发 auth+subscribe，队列里没有扫描帧）。

**缺什么数据**：服务端没有任何「帧到达 → 开始处理 → 处理结束」的时间戳，上面 1670ms 的归因是算术推断而非实测。第 0 项就是补这个量具，否则后面所有服务端改动都无法证伪。TUI 一次 SIGWINCH 全屏重绘的真实成本也没量过（本轮禁 tmux），第 3 项的收益上限只能给区间。

---

## 工作项（按 收益/工作量 排序）

### P0-a｜服务端逐帧时间戳（量具，先于一切改动）
- **目标**：把「1794ms 里 1670ms 去哪了」从推断变成读数；它同时是后面每一项的验收量具。
- **落点**：`server/internal/api/ws_conn.go:134-149`（readLoop 读到帧的 wall）与 `:349-371` handleFrame 分派前后各打一条 `log.Info("frame", type, recv_ms, start_ms, done_ms, queue_ms)`；`discovery/scan.go:71-83` 每 socket 一条 `socket=… ms=… panes=…`；`proctree.go readProcTable` 一条 `ps_ms=`。
- **红判据**：新单测 `ws_frame_latency_test.go`：注入一个 `Discover` 睡 800ms 的 discoverer，背靠背发 `list` + `subscribe`，断言日志里 subscribe 的 `queue_ms > 700`。**先红**（今天必然红成立=缺陷存在），修完 P0-b 后同一测试断言 `queue_ms < 50` 转绿。
- **风险/回退**：纯日志，零行为改动；回退=删日志。⚠️ 日志不得打 pane 内容与 socket 路径以外的东西（凭据纪律）。

### P0-b｜扫描类帧全部移出 readLoop + Discover 并发化 + 短 TTL
- **目标**：吃掉首帧 1794ms 中的 ~1670ms 排队段 ⇒ 三世界冷进入各降 ~1.5s。**这是 2s 门的主项**，其余全部加起来也不到它一半。
- **落点**：
  - `ws_conn.go:366-370`：`List`（pr/listing-ps-storm 已改成 `go c.handleList(t)`，直接采用）**并补上 `Level2Subscribe` → `go`**（`level2.go:222 publishLevel2` 同样是全量 Discover，storm 分支没动它）；`AttachPreview`/`Scrollback` 同理评估。
  - `discovery/scan.go:71-83`：23 个 socket 串行改为 `errgroup` 并发（上限 8），单 socket 超时从 5s 压到 500ms（死 socket 是常态，不该按活服务器给预算）。
  - `proctree.go`：合入 pr/listing-ps-storm 的 (pid,starttime) ident 缓存 + `snapCovers`（消掉「一 pane 一次全表 ps」的 storm）；另把 `readProcTable` 移出 `f.mu` 临界区（现在一次 ps 全程堵住所有并发 identify）。
- **红判据**（server go test，`git archive` 到 `/private/tmp/claude-501/` 跑）：① 上面的 `queue_ms<50`；② `procTableReads` 计数：一次 listing tick ≤1（storm 分支已有该测试）；③ 新增：`DiscoverWithDirs` 面对 20 个假 socket（其中 15 个 stale）总耗时 < 1s。
- **风险/回退**：`go handleList` 让同连接的 listing 回复可能乱序于其他帧——协议里 Listing 带 `seq`/`req_id`，客户端按 seq 收敛，风险低但需一条并发两次 List 的测试锁住「不发倒退 seq」。回退=把 `go` 去掉，单行。
- **不触碰禁遮掩红线**：改的是「更早开始干活」，不是延迟展示。

### P1｜视口几何 seed 一次到位（合并原 #2/#3/#4 三条，同一个根）
- **目标**：省掉每次进入的第二次 `handleResize`（8 exec ≈ 80ms）+ **两次** SIGWINCH 全屏重绘中的一次。对「会重绘的 TUI」（verify2 5.27s，比纯 claude 多 2.1s）是唯一能咬到重绘成本的一项，预期 150–800ms，Ink 类 TUI 偏上限。
- **落点**（三处一起，缺一条洞就还在）：
  1. `TermSurfaceView.kt:735-748 persistViewportGeom`：别用 `height/cellH` 现算，直接落 `presenter.emulator.rows/cols`（= 上一次真正告诉服务端的值）。IME 挤压（`MainActivity.kt:79` ADJUST_RESIZE + `SessionScreen.kt:253-259` imePadding → `TermSurfaceView.kt:287-291 onSizeChanged`）**结构性免疫**，不需要「是否挤压」判据，也不需要 max 分桶。
  2. `SessionRoute.kt:144-151 cacheHit`：加窗口度量维度（save 时一并持久化 window width，命中要求相等），否则横屏用过、竖屏再进必然命中错值（`AndroidManifest.xml:69-73` 无 configChanges，旋转必重建）。⚠️ 只加「不匹配即 miss」是**假修**——miss 会退到 `SessionRoute.kt:206-207` 的 40×120，一样错，必须做成按窗口宽分键的 2–3 条目小字典。
  3. `SessionRoute.kt:206-207 INITIAL_COLS`：120 → 手机量级（46/50）。一行，拿掉「把宿主 pane 撑到 120 列再缩回」的整屏 reflow。
- **红判据**：instrumentation/单测各一条 —— ① 构造「挤压后的 onSizeChanged」，断言 prefs 里存的 rows == emulator.rows（先红）；② 横屏存、竖屏读，断言 `cacheHit=false` 或命中的是竖屏条目；③ 端到端：进→弹键盘→返回→再进，日志里 `term-geom source=subscribe cache=hit` 的 rows 必须等于其后 `resize sent` 的 rows（今天必不等，修完必等；两条日志已存在于 `SessionRoute.kt:160-167` 与 `TermSurfaceView.kt:750-758`）。
- **风险/回退**：用户长期分屏小窗口时首帧订阅可能偏大，随后仍由一次 resize 收敛 = 退回今天行为，不更差。三处彼此独立，可单条回退。

### P2｜收帧口前移（消除白屏竞态，非提速项）
- **目标**：**当前收益 0ms**（probe16 首帧比 uiConnector 挂载晚 1418ms，本轮没丢帧），但 P0-b 一旦把服务端压到 100ms 量级，`subscribe(8ms) → uiConnector attach(377ms)` 这 369ms 空窗立刻变成真丢帧。更重的后果在缓存命中世界：`TermViewPresenter.kt:472-482` 几何一致 ⇒ 不发 resize ⇒ 没有第二张快照来兜底 ⇒ **永久白屏**，不是迟 369ms。
- **落点**：`SessionViewModel.kt:213-214` 把 `manager.subscribe(...)` 从 init 挪成显式 `start()`；`SessionRoute.kt:100-107` 的 `DisposableEffect` 里先赋 `ServiceWire.uiConnector = vm`（不可放进 `:88-91` 的 remember lambda——组合期副作用可被丢弃），紧接着 `vm.start()`。
- **不推荐的替代**：在 ConnectionManager 里缓存最近一张 SNAPSHOT 由 `ServiceWire.kt:106-109 replayTo` 补播——快照与 attach 之间的 delta 同样被三路丢（`SessionRoute.kt:195-202` NoopUiListener、`MirrorForegroundService.kt:244`、`ServiceWire.kt:326-331`），只补快照会把屏回退到旧态；要做对得连 delta 尾巴一起缓冲，比 start() 复杂得多，且属于「先画缓存旧帧」——须交用户裁。
- **红判据**：单测「构造 VM 后不调 start() 则 manager 收不到 subscribe」+「attach 之后才 subscribe」的顺序断言；端到端：连续两次进同一会话（第二次缓存命中）配合被打快的服务端，第二次必须出字（今天预期白屏）。
- **风险/回退**：跨越 VM 生命周期契约，所有构造点（含测试）漏调 `start()` = 静默不订阅，比现状更坏 ⇒ 必须有那条断言测试兜底。回退=把 subscribe 挪回 init。

### P3｜「再进」路径：先压 exec 次数，linger 单独交用户裁
- **目标**：verify2 的「再进」2.54/3.62/4.83s 与冷进入同量级。一次「返回+再进」= teardown 的 restore Resize(4 exec) + 新 handleSubscribe(11 exec) ≈ 16 次串行 exec + 两次 SIGWINCH。
- **落点（先做这一半，无契约风险）**：`bridge/bridge.go:377-392` —— `windowID` 按 pane 缓存（window id 不随 resize 变）、`Resize` 末尾的 `Size` 与 `handleResize` 里紧随的 `br.Size`（`ws_handler.go` 前后各一次）去重、用 tmux 的 `cmd1 ';' cmd2` 单次派生合并 set-option+resize-window+display-message。11→3~4 次可达，**冷进入也受益**。
- **落点（交用户裁的那一半）**：`SessionViewModel.kt:631-634 dispose()` 的 linger。⚠️ 单加 linger 收益≈0：`SessionRoute.kt:96 remember(ref)` 会重建 VM、VM 无条件重订阅，而 `handleSubscribe` 第一件事就是 `subscribeCancel`（`ws_handler.go:144`）把你 linger 的订阅拆掉。要有收益必须把 VM+TerminalEmulator 提到路由之外并跳过重订阅。该变体**不算遮掩**（linger 期间 delta 持续喂 emulator，再进看到的是实时画面）；但**桌面端用户的 pane 会一直停在手机几何**，这是 `pane_geometry.go:78-101` 那套 restore 契约当初就是为防它而立的 ⇒ 必须交用户裁。原条目里的「按 ref 缓存内容、再进先画上次的」属于展示旧帧，红线内，须单独标注。
- **红判据**：`bridge` 层单测统计 exec 次数（注入 fake tmux bin 计数），断言一次 Resize ≤2 次派生、一次 subscribe ≤5 次；端到端「再进」计时对拍。
- **风险/回退**：exec 合并要小心 tmux 分号在 args 里的转义与部分失败的错误归类（`tmux.go:99+ classifyTmuxError` 只认单命令 stderr）；逐条回退。

### P4｜渲染热路径卫生（两行守卫 + 摘一处每帧读盘）
- **目标**：不解释首帧，也不解释 janky 93%；真实价值是**让后续对拍的计时窗口与被测物一致**，以及每次按键省 0.3–2ms 主线程。
- **落点**：① `TermSurfaceView.kt:66-75` presenter setter 加 `if (field === value) return`（同文件 `:110-116` nightOverride 已有此守卫，是遗漏）——`SessionScreen.kt:310-314` 的 update lambda 因 `SessionViewModel` 有十余个裸 var 而必判 unstable，每次重组都会重跑 `applyFontMetrics`（measureText + glyph 缓存 clear + 一次 prefs 落盘）。**不要删 `glyphAdvanceCache.clear()`**（键是 `textSize.toRawBits() xor cp`，存在跨字号碰撞面），加守卫后它本就只在真字号变更时跑。② 摘掉 `TermSurfaceView.kt:365`（onDraw）与 `:223`（doFrame）里的 `refreshBurst()`，保留 `:284`/`:343`/`:356`（`:356` 的 tap 通路是 `perf-check.sh:336-339` 无 restart 切换 opt 的唯一生效路径，必须留）。③ `DiagLog.kt:196-212` 的 coalesce 分支每次都跑 3 个 Regex + 全扫最多 4096 条且持锁，而 `TermSurfaceView.kt:399 recordLeftEdgeOnce` 在 onDraw 每帧调用——JVM 复刻实测未命中全扫 73µs，ART 折算每帧 0.3–0.7ms 且随会话变长单调增大，**这条比重组那条更像用户报的「滑动掉帧」**。
- **红判据**：① update lambda 计数器 + `applyFontMetrics` 打点，敲一行字断言调用次数 == 字号变更次数（今天必红）；② `recordLeftEdgeOnce` 加一个「已记录」布尔短路后，onDraw 里 DiagLog 调用数每帧 0。
- **风险/回退**：回归面已核（回前台走 `:347 forcePresenter`、尺寸走 `:289`、字号走 fontSizeSp setter，均不经该 setter）；每条一行，独立回退。
- **诚实标注**：**摘掉这些数字不会让 janky 判决变化**，前后两包都带着这笔开销、A/B 对称；这是量具卫生修复，不是卡顿修复。

---

## 排期建议
P0-a 与 P0-b 同一格（量具+修复必须一起才有先红后绿）；P1 可与 P0 并行（app 侧，无写冲突）；P2 必须排在 P0-b **之后**验收（之前它的红判据在今天的世界里恒假）；P3 前半、P4 独立并行。

**关键文件**：`/Volumes/nvme/Projects/远程Agent安卓/server/internal/api/ws_conn.go`、`/Volumes/nvme/Projects/远程Agent安卓/server/internal/api/level2.go`、`/Volumes/nvme/Projects/远程Agent安卓/server/internal/discovery/scan.go`、`/Volumes/nvme/Projects/远程Agent安卓/server/internal/api/proctree.go`、`/Volumes/nvme/Projects/远程Agent安卓/server/internal/bridge/bridge.go`、`/Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/session/SessionRoute.kt`、`/Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/session/SessionViewModel.kt`、`/Volumes/nvme/Projects/远程Agent安卓/app/app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt`。