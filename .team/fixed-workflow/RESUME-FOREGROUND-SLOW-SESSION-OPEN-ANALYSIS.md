# 前台恢复后会话打开迟钝：第一阶段只读路径分析

后续独立 A/B 红证、获准的最小修复与精确候选身份见 [修复结果](RESUME-FOREGROUND-SLOW-SESSION-OPEN-FIX-RESULT.md)。下文保留初始调研的基线与证据边界，不将独立复现冒认成原始 q 的完整时间线。

## 0. 结论边界

**这段日志不能定性此次迟钝发生在 Socket、会话切换还是首绘；不能据此宣称已经找到用户现场的唯一根因。** 日志停在 `%166` 的菜单取数行，`open_id=q` 只有 tap，没有后续 route/subscribe/frame/draw。更重要的是，`09:21:40.733` 没有 ON_START 证据，而 `41.052 visibility=8` 是窗口隐藏事件，不能直接把前者当作恢复边沿。

只读核验已确认：

1. `%0` 是仍被导航壳持有的旧会话；L2 推送/收藏更新可以让该路由重组。`enterSessionLive already=true` 只描述工作区 L2 订阅，**不是终端镜像订阅成功或首帧就绪**。
2. 当前黄金 Core 产物确实含 PR #41 的修复，不能再套用旧报告“前台完全没有响应期限”的结论：READY resume 安排 5,000ms 存活期限，由 2s 泵裁决；回前台后的失败可以立即重拨。
3. 仍存在一个明确的能力缺口：**L1 Listing 成功即结束前台探活，但新会话 Subscribe 没有对应首个 SNAPSHOT 的响应期限。** READY/send=true 不是会话已经可用；如果随后无首快照，UI 的“正在加载终端…”可持续等待。此缺口不等于此次用户现场已经证明了网络黑洞。
4. 正常 `%0 → %166` 接线已有按 ref 二进制监听、先注册后首订、旧 VM 身份守卫；源码不支持“正常切换必然吞掉新会话帧/被旧 uiConnector 抢占”的断言。
5. 服务端同步退订/订阅，以及渲染冷首帧仍有可导致等待的代码路径，必须用缺失的后续时间线排除；`reflowHardCap=800ms` **不是整个订阅处理的总超时上限**。

第一阶段仅新增分析文档、源码 hash 与已发布 JAR 的反汇编，未修改产品源码/构建安装 App/发送生产请求/改 9900。之后按更新目标和 leader 明确授权进入 Core 修复；当前结果以链接文档为准。

## 1. 基线与原始证据

- 调研 HEAD：`83ba5cc363ad7a27ce60e21f539d8a8bc1e89edf`，tree `e2ae4d36e21bcaa42b3a03fbcbc6aa494427fd9c`。
- 已实际核查：当前 `app/src`、App Gradle 与 settings 对黄金构建提交 `5530953e34d8cccb6411716bd7f3ca17c972bed9` 零差异；当前 `server/` 对生产构建提交 `d4181a3ed4491f3575087b79fdcafdf1f176efe8` 零差异。
- 本机黄金 APK SHA256：`c982bcec68e0aec9a090b7959031d80a057dac51316eb97798d8492e085295b1`；路径 `/Users/alauda/Downloads/corral-app-final-5530953.apk`。**未从用户手机核取已安装包，不能冒称现场包身份已证实。**
- App 的 `ConnectionManager` 来自 Maven `core-conn:20260915.close`（`app/build.gradle.kts:137`），不是未接入构建的 `app/core-conn/` 历史源码。
- 按黄金构建的 `core-aar-identity.txt` 核实当前发布件：
  - AAR `723b1f73c72f7530c63685d93b29b85772f4bc7c374f196ec8124dfba3ab0b82`。
  - JAR `0c2aab7c59ef67d2775074036070f05ef734dba258ed0343345e96d0ee15f66c`。
  - 实际路径 `.worktrees/workspace-create-agent-core-maven/.team/nodes/developer/session-longpress-close-core-maven/dev/agentmirror/core/core-conn/20260915.close/`。
- Core 行号下文指 **该 JAR 的 SourceLineNumberTable**，并附反汇编证据，不把可能已漂移的源码工作树当权威。参考 `.worktrees/issue40-core-fix-composite/app/core-conn/` 源码与 JAR 的关键行为一致，但 Listing 清除 recovery 标记等细节存在差异，故定性以 JAR 为准。
- 新证据目录：`.team/fixed-workflow/evidence/resume-foreground-slow-session-open-20261002-developer/`：
  `artifact-identity.txt`、`source-head.txt`、`source-sha256.txt`、`owned-source-diff.txt`（0 bytes）、`core-manager-bytecode.txt`、`core-listener-bytecode.txt`、`core-connection-bytecode.txt`、`core-policy-bytecode.txt`。

## 2. 日志事实：它证明什么，不证明什么

| 时间 | 可证事实 | 不可推出 |
|---|---|---|
| 40.733 | WorkspaceViewModel 消费 Listing seq75、15工作区 | 这不是 ON_START；也未记录 req_id/连接代/来源，不能证明它是本次新探活的响应 |
| 40.787 | 一次有字 onDraw 方法体末次耗时 4,027µs，环 p95 17,095µs | 不包含 Choreographer 中此前的 beginFrame 争锁；不是 q 的打开耗时 |
| 40.952 | L2 快照 src=level2-push 正常消费，8个会话 | L2 正常不证明 `%166` 镜像订阅/首帧成功 |
| 40.959–40.964 | 当前 route ref仍为 `%0`；工作区 L2 already=true | 不表示点击后路由仍错误选了 `%0`；这是 q tap **之前** |
| 41.052 | Term View 收到窗口 visibility=8（GONE），停止渲染唤醒 | 不等于 VM dispose，也不等于恢复前台；窗口事件与 Activity 生命周期不同 |
| 48.041 | 用户点击产生 `open_id=q ev=tap t=200727628` | 没有 q 的后续事件，不能算 tap→首绘耗时 |
| 48.048 | 菜单取数已使用 `%166`，距 tap 约7ms | 说明新的导航 ref 已进入组合数据路径，但不能证明 VM/几何/subscribe 已完成 |

`TermDrawMeter` 是进程级对象，非 per-open/per-ref（`termview/TermDrawMeter.kt:40–61,145–146`）。`t_emit - t_firstOnDraw = 43,660,876ms`，约12h7m40.876s；`n=256` 是环中的稳态采样数。这些首绘时间不是 q 的新会话首绘时间，也不能证明同一个旧 View 一直存活了12小时。

## 3. 完整事件链及精确落点

路径简写：`AP=app/src/main/java/dev/agentmirror/app`。

### 3.1 前后台恢复

1. `AP/MainActivity.kt:167–178`：onStart 检查真实 stop→start 边沿，调用 resumeConnection。
2. `AP/MainActivity.kt:186–196`：manager 丢失才按保存配置重建；否则转 ServiceWire。
3. `AP/MainActivity.kt:199–206`：onStop **保留 activeSession**，仅记边沿，未 dispose 会话 VM。
4. `AP/MainNavState.kt:60–77`：只有明确 openSession 操作才改 activeSession 并打 tap；后台停止本身不会选新 pane。
5. `AP/ServiceWire.kt:248–252`：依次调用 `m.onForegroundResume()`、`m.onNetworkAvailable()`。
6. 已发布 Core `ConnectionManager.kt:370–400`：READY 记录当前 Connection 与 `now+5000ms` 存活期限，发/合并 L1 和 L2 刷新；RECONNECTING 立即拨号。
7. Core `ConnectionManager.kt:306–320`：pump 超期且还是同一 READY Connection 时，closeForReconnect；Core listener `:945–979` 保留 resumeRecovery 决定立即重拨还是普通退避。
8. `AP/MirrorForegroundService.kt:153–167,215` 与 `AP/AppClockPump.kt:41,49–58,104–108`：期限靠 2,000ms 周期泵检查；前台服务泵在主 Looper，服务无泵才由 Compose fallback 接管。因此在正常调度下，5s预算大约在5–7s被处理，**主线程被阻塞时不构成硬实时上限**。
9. Core listener `:874–880`：任意 Listing 消费后，若 probe Connection 身份等于当前 Connection，就清存活期限，且置 `foregroundRecoveryArmed=false`。**没有核对 Listing.req_id 是否对应本次前台发送的挑战。** Level2/heartbeat 不等价。

因此 PR41 并未缺席。后续调查的重点是“探活何时开始、哪一个 Listing 清掉它、之后的新订阅是否有结果”，不是重复实现一套 onStart 重连。

### 3.2 tap→route→首订

1. `AP/MainNavState.kt:71–77`：openSession(ref,name) 先 `PerfTrace.onUserOpen(ref)`，再设置 activeSession。
2. `AP/AgentMirrorApp.kt:177–228`：activeSession 决定 Session 路由。Session→Session 的 `contentKey="agent-cli-session"` 复用 UI composition；进入 Session 没有300ms Push动画。`enterSessionLive` 的 effect 同时以收藏/实时列表版本为 key，数据变化也会重新执行，故 `%0` 的重复日志属于允许的重组路径。
3. `AP/workspace/WorkspaceViewModel.kt:626–650`：enterSessionLive 的 already 判断是 cwd、wireWorkspace、L2取数账本；already 时不订 L2，只推进 generation。没有检查 Connection.isReady、mirror subscribe 或首快照。
4. `AP/session/SessionRoute.kt:94–104`：remember(ref) 先打 route_enter，再同步 createSessionViewModel。
5. `SessionRoute.kt:148–216`：复用进程级 manager；start 幂等，`ServiceWire.reconnectNow` **只跨 RECONNECTING 的退避，READY无操作**；读取几何缓存，cache hit 才 warm subscribe，miss 等实测几何。
6. `AP/session/SessionViewModel.kt:227–249`：**先 `addBinaryListener(ref,this)`，再 warm subscribe**。即便 UI 槽尚未挂新 VM，二进制也能按 ref 投递。
7. `AP/termview/TermSurfaceView.kt:82–99`：AndroidView 换 presenter 时即 seed 字格，并 `doOnLayout` 向新 presenter 重放现有尺寸；不是必须等尺寸改变才能首订。
8. `AP/termview/TermViewPresenter.kt:568–586` → `SessionViewModel.kt:834–858`：首次有效几何回调，先完成本地尺寸，后 subscribe；disposed 会拒绝迟到布局回调。
9. Core `ConnectionManager.kt:527–553`：非 STOPPED 先记订阅簿；没 Connection/未 READY 时记簿等待重放；READY 时调用 send(SubscribeFrame)，记录 PerfTrace。**没有创建 per-ref 首SNAPSHOT的待响应记录/期限。**
10. `AP/perf/PerfTrace.kt:622–674`：`subscribe_sent emitted=1 ok=1` 表示 send 返回真。`SessionViewModel` 自己的 `sent=true` 在未READY记簿时也可能是真，必须用该 trace 的 emitted/ready/reason 判断，不能混称已上网。

### 3.3 首帧→应用快照→首绘

1. 已发布 `Connection.kt:191–207`：二进制解码之后先 `ConnPerf.emitWsBinaryRecv`，再传 listener。
2. Core manager listener `:919–940`：优先按 frame.ref 找 BinaryListener；存在针对监听时不依赖全局 UI 槽。无针对监听才走包装 listener。
3. `SessionViewModel.kt:327–350`：ref匹配后打 `first_frame_recv`。随后 SNAPSHOT replay 完成才 `hasSnapshot=true`（`:367,410`）及 `snapshot_applied`（`:415`）；第一种二进制若是DELTA，不能代替首SNAPSHOT。
4. `AP/session/SessionScreen.kt:357–411`：AndroidView持续渲染；`!hasSnapshot` 时加载占位始终盖在上面，READY 时文案“正在加载终端…”。此占位没有自身超时。
5. `AP/termview/TermSurfaceView.kt:324–339`：Choreographer 先 `takeDamage`、`beginFrame`，然后 invalidate。`:665–768` 的 onDraw 计时在之后才开始，glyphs>0时打 first_draw。
6. `AP/termview/TermViewPresenter.kt:659–689,726–789`：已有 prepared frame 的流式路径无同步争 emulator 锁；但 **prepared==null 的冷首帧仍同步 captureFrame**，后者 `synchronized(emulator)`。另所有 presenter 共用 single-thread capture executor（`:902–909`），watchdog 1,800ms。这些是首快照已到而首绘晚时的检查点，当前片段没有命中证据。

### 3.4 销毁与监听槽是否冲突

- `SessionRoute.kt:124–130`：退出时只有 `ServiceWire.uiConnector === oldVm` 才清槽；新 VM 已占槽则旧dispose不清它。
- `SessionViewModel.kt:823–830`：dispose只封闭自己的生命周期、移除本ref监听并unsubscribe本ref。不同 ref 的新 VM 不被移除。
- `ServiceWire.kt:89–119`：UI槽与列表槽并行且同对象去重；挂载补播 state、AuthAck、lastListing，不补播二进制。这也解释了 applyListing 日志可能来自本地补播，不能仅凭它证明网络往返刚刚完成。
- 新 VM 有独立 emulator/presenter/lifecycleLock，没有“必须等旧 VM释放共享镜像槽”这种代码条件。

**结论：正常接线路径有保护，不能直接定性 B。若 q 出现 frame_ref_mismatch/no_listener、route_enter→subscribe_sent 长停顿或 snapshot_applied→first_draw 长停顿，再定位具体线程/锁。**

## 4. 对三类疑点的判断

### A：Socket 假活

**可发生，但现场未证实；旧版无探活结论已不适用于黄金 AAR。**

- `OkHttpWebSocketTransport.kt:78–85` 的 send 直接入 OkHttp 队列；`:105–136` 只在open/close/failure更新isOpen，半开期间 READY/send=true不足以证明往返。
- `:147–164`：Ping15s；升级后的 WS 普通readTimeout3s不负责探测无流量半开。
- 现版foreground预算是5s+泵，不是等Ping。但一次Listing就清期限/armed后，**tap新ref并不重新验证会话首帧**；随后黑洞仍可能等正常网络失败后重放。
- 另一个需要独立举证的探活细节：延迟的旧Listing也能清新probe，因为代码只比较 Connection对象，没有挑战req_id匹配。它是潜在假阳性机制，不能从片段推断已发生。

### B：旧会话 `%0` 与新 `%166` 的客户端槽/锁

**没有发现可仅由正常不同ref切换必然触发的抢槽/丢订阅机制。** 上述先按ref登记、身份守卫和presenter换绑都在。剩余需验证的是冷首帧同步争锁/捕获队列，或真实 main-thread长任务；不能从4ms末次onDraw和p95推翻它们，因为仪表边界没包含之前的beginFrame。

### C：服务端等待

**无法排除，也不是“800ms reflow cap就排除了”。**

- `server/internal/api/ws_conn.go:221–239,706–722`：一条WS的readLoop同步handleSubscribe/Unsubscribe/Resize/Scrollback。
- `ws_handler.go:135–263`：subscribe依次做pane解析、几何acquire、Size、pipe attach、可能Resize、capture/publish。
- `reflow_gate.go:14–15` 和 `ws_handler.go:678–767`：800ms是reflow等待的截止点；Size/attach/CaptureState等tmux操作仍在该函数外或使用原ctx。
- `server/internal/bridge/tmux.go:48,54–95`：**每条tmux命令10s**，不是整条订阅总期限。
- `ws_conn.go:851–882` → `bridge/stream.go:227–317,397–429`：旧pane退订可以同步等待pipe detach（tmux调用）及fanout退出最多3s；`pane_geometry.go:93–118` 还可能同步恢复旧尺寸。
- cache miss时旧 VM可先dispose/unsubscribe，待新View几何再发subscribe，因此服务端旧退订的同步耗时有机会头阻塞新订阅；warm hit通常在构造新VM时先发新subscribe，顺序不同。**必须读取q的cache hit/miss和实际帧顺序，不能把一种顺序当全部场景。**
- 现有 `perf_subscribe` (`ws_conn.go:705–762`) 在Read返回后才记recv，再在handler返回后打整行；`queue_ms≈0` 不能排除该帧此前堵在TCP/WS读取队列，甚至handler挂起时还没有这条日志。

## 5. 最小可行动路径（尚未授权改代码）

### 首先定位丢失的耗时区间

请补现场 `09:21:35` 至 `%166` CLI真正出现后的完整日志，并核安装APK身份。至少包含：

- lifecycle ON_STOP/ON_START/ON_RESUME、windowVisibility=0/8；
- foreground_resume、conn state、ws failure/closed、重拨/认证；
- q 的 route_enter、term-geom cache reason、geometry_ready、subscribe_sent（含emitted/ready/replay/reason）；
- ws_binary_recv、frame_ref_mismatch/no_listener、first_frame_recv、snapshot_applied、first_draw/layout_settled；
- 对应服务器同ref的 perf_subscribe/perf_reflow/连接关闭事件。

按客户端同一 monotonic 时钟分段：

| 延迟段 | 最小调查/修复方向 |
|---|---|
| tap→route_enter | 根组合/导航/主线程，先trace长任务，不改Socket策略 |
| route_enter→subscribe_sent | cache miss与几何等待、同步构造/锁，核geometry_ready和实际View可见性 |
| emitted=1 subscribe_sent→ws_binary_recv | transport及server同步handler；用服务端到达/处理/发出证据区分，不从客户端孤证断言NAT |
| ws_binary_recv→first_frame_recv | Core ref listener分发/解码，核mismatch/no_listener |
| first_frame_recv→snapshot_applied | 快照解析与emulator锁；确认首帧kind，不以DELTA冒充SNAPSHOT |
| snapshot_applied→first_draw | prepared frame捕获、Main Choreographer与visible恢复；不重连已经健康的Socket |

服务器/手机的monotonic时钟来源不同，不直接互减；可先各自计算段内耗时，再用带ref/连接代的事件对应。

### 现有代码缺口的最小候选（须先红测与裁定）

1. **若同源红测证实 READY新订阅无首SNAPSHOT可无限等：**在既有 ConnectionManager 的订阅簿中给 `ref + connection generation + latest subscription` 增加一次性的首SNAPSHOT等待裁决，匹配SNAPSHOT清除，unsubscribe/替代订阅/代切换取消；超期有明确UI失败/有限恢复，不无条件打断健康Socket。预算与恢复动作由验收场景裁定，不随手写死“秒开”数字。避免另建并行连接/第二套状态机。
2. **若证实旧Listing误解除新探活：**只接受对应当前连接代与probe请求req_id的挑战响应；不要用任意Listing/缓存补播证明存活。这属于修正已有PR41分支，而非重写生命周期。
3. **若首帧已到但冷捕获阻塞：**改精确冷首帧同步争锁/捕获队列点，保持既有prepared-frame模型；不能用任意延时、预绘旧pane或隐藏占位凑绿。
4. **若证实server旧退订头阻塞：**对确认命中的清理/订阅阶段施加整体预算或使慢清理不阻塞WS reader，保留pipe所有权和尺寸归还语义；没有证据前不广泛重构server队列。

### 测试交接

已直接交tester精确AAR/JAR身份与两场景：
- foreground得到Listing后，新ref subscribe本地接受但没有binary，泵是否继续无期限READY；
- 延迟旧Listing清掉probe后真实黑洞，是否还能限期恢复。

也交付了server旧退订/新首订顺序与每命令超时的检查点。未把历史红/其他包的绿当作当前包的结论；独立结果尚未收到。

## 6. 当前断点

源码机制与黄金产物身份已核；尚欠完整q链、用户实际APK身份以及同源复现。

可以确认的定性是：**应用把连接READY/L1恢复与“当前会话已收到首快照”分开处理，但后者没有等待期限；正常客户端切换有防丢帧保护。** 不能进一步把此次长等待锁定为半开、界面状态冲突或server锁竞争中的某一项。下一步优先补现有日志与独立红测，获准后才在被证实的断点做最小改动。
