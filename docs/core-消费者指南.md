# corral-core 消费者指南

给没参与过本工程、只拿得到 https://github.com/Florious95/corral-core 的人：怎么用三核造另一个客户端（桌面端 / 另一个安卓壳 / CLI）。符号与路径均来自源码，不是印象。

三核源码：`app/core-protocol/src`、`app/core-terminal/src`、`app/core-conn/src`。线上契约正文：`docs/protocol.md`。

包名：协议与连接都在 `dev.agentmirror.app.conn`；终端仿真在 `dev.agentmirror.terminal`。

---

## 怎么引

仓库 URL（maven 分支的 raw 布局）：

`https://raw.githubusercontent.com/Florious95/corral-core/maven/`

坐标以本地产物目录名为准：`.team/staging/maven-repo/dev/agentmirror/core/core-protocol/` 下是 `20260822.0`。三条一起钉死：

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://raw.githubusercontent.com/Florious95/corral-core/maven/") }
        // 克隆了本仓、maven 分支还没拉到时的本地兜底：
        // maven { url = uri(rootDir.resolve("<本仓>/.team/staging/maven-repo")) }
        mavenCentral()
    }
}

dependencies {
    implementation("dev.agentmirror.core:core-protocol:20260822.0")
    implementation("dev.agentmirror.core:core-terminal:20260822.0")
    implementation("dev.agentmirror.core:core-conn:20260822.0")
}
```

`core-conn` 的 POM 已经依赖 `core-protocol` 同版本；`core-protocol` 会带上 `kotlinx-serialization-json-jvm`。`core-terminal` 只依赖 Kotlin stdlib。三核都是 **纯 JVM**，没有 Android 插件、源码没有 `import android.` / `androidx.`，桌面端可以直接用。

传输层不在核里：你必须自己实现 `WebSocketTransport`（安卓可用 OkHttp，桌面可用任意 JVM WebSocket），再交给 `TransportFactory`。

---

## core-protocol —— 线上协议 / 消息类型

它定义控制面 JSON 与数据面二进制两种帧，编解码入口是 `FrameCodec` 和 `BinaryFrameCodec`。版本钉在 `ProtocolVersion`：`VALUE = 1`（JSON 信封字段 `"v"`）、`BINARY = 1`（二进制第 3 字节）、magic `"RA"`、ref 最长 255 字节、编码侧载荷上限 1 MiB。

### 控制帧

一条 WebSocket text = 一个信封 `{"v":1,"type":...,"payload":{...}}`。`FrameCodec.encode` / `FrameCodec.decode` 进出的是 `FramePayload`（密封接口），不是裸 JSON。编码前、解码后都 `validate()`，无效帧不跨线；失败抛 `FrameEncodeException` / `FrameDecodeException`，分类码是 `FrameError`（缺版本、版本不支持、未知 type、坏帧、缺字段、坏 magic、未知 kind、截断、空 ref、ref 过长等）。

未知信封字段被忽略（前向兼容）；未知 `"type"` 是错误。协议级错误帧是 `ErrorFrame`，code 为 `ErrorCode` 闭集；未识别 code 字符串解码回落 `ErrorCode.UNKNOWN`，该值**永不上行**。

客户端会发出去的帧（C→S）包括：`AuthFrame`（token 只上行一次，toString 已打码）、`ListFrame`、`SubscribeFrame`、`UnsubscribeFrame`、`InputFrame`、`ScrollbackFrame`、`ResizeFrame`、`ScrollWheelFrame`、`AttachPreviewFrame`、`Level2SubscribeFrame`、`Level2UnsubscribeFrame`、`OverlaySubscribeFrame`、`OverlayUnsubscribeFrame`。

服务端会推过来的帧（S→C，客户端编码路径会拒）包括：`AuthAckFrame`、`ListingFrame`、`ListDeltaFrame`、`InputAckFrame`、`ErrorFrame`、`PaneModeChangedFrame`、`Level2Frame`、`Level2HeartbeatFrame`、`OverlayFrame`。

会话寻址键是 `Session.ref`（展示名可重名）。一级列表按 cwd 聚成 `Workspace`。`InputFrame` 里 text/attachmentPath 与 keys 互斥；命名键闭集是 `InputKey`（esc / ctrl_c / tab / 方向 / backspace）。`InputAckFrame` 失败原因闭集是 `InputFailReason`。

### 二进制流帧

一条 WebSocket binary = 一帧。布局：magic `"RA"` + version + kind + reflen + ref + payload。`BinaryFrameCodec.decode` 得到 `BinaryFrame`：`kind` / `ref` / `data`（原始 ANSI 字节，不经 JSON 转义）。kind 闭集 `BinaryKind`：

- SNAPSHOT(1) —— 订阅生效后首帧全屏快照（capture-pane -e）
- DELTA(2) —— pipe-pane 增量
- SCROLLBACK(3) —— 回答 `ScrollbackFrame`；payload 前有 12 字节元数据头，填进 `BinaryFrame.reqId` / `fromLine` / `lineCount`

未知 kind 报 `FrameError.UNKNOWN_KIND`。畸形镜像流必须浮出，不得静默写进终端网格。

---

## core-terminal —— 终端仿真

门面是 `TerminalEmulator(cols, rows, scrollbackCapacity = 5000)`。它不做绘制：字节流进，网格状态出。

喂什么进去：

- `replaySnapshot(bytes, cols, rows)` —— 订阅首帧 / 重连 / resize 后的 SNAPSHOT。按新尺寸清屏重建，scrollback 保留；capture-pane 的裸 LF 会补隐式 CR，并剥掉恰好一个尾随 LF。
- `feed(bytes)` —— DELTA。可在任意字节处切断，半截转义序列留到下一段。
- `prependHistory(bytes)` —— SCROLLBACK 历史分页，头插进 `ScrollbackBuffer`。alternate screen 期间调用被忽略。
- `resize(cols, rows)` —— 只换尺寸不 reflow，内容以随后到达的服务端快照为准。

拿什么出来：

- `snapshot()` 返回不可变 `ScreenSnapshot`：`cols` / `rows` / `cursorX` / `cursorY` / `cursorVisible` / `altScreen` / `lines`（`List<List<Cell>>`）。
- `Cell` 是一格：`text`、`style`（`TextStyle`）、`width`（CJK/emoji 宽字符首格 width=2，后一格 width=0，渲染时跳过）。
- `TextStyle` 的颜色是 `TerminalColor`（Default / Indexed / Rgb），外加 bold/dim/italic/underline/inverse/strikethrough。
- 脏区回调 `DamageListener.onDamage(rows)`：一次 feed/重放后需要重绘的屏幕行区间。
- `historyAvailable`：alternate screen（全屏 TUI）期间为 false。
- 五个公开入口（feed / replaySnapshot / resize / prependHistory / snapshot）以实例锁串行，避免收件线程与渲染线程撕裂网格。

核里的网格与解析器是内部实现，客户端不要去碰；只走 `TerminalEmulator`。

---

## core-conn —— 连接管理

对外状态机是 `ConnectionState`：CONNECTING → AUTHENTICATING → READY → RECONNECTING / STOPPED。配置是 `ConnectionConfig(url, token)`（token 的 toString 已打码）。

`Connection` 管**一条** WebSocket：传输建立后立刻发 `AuthFrame`；`AuthAckFrame.ok == true` 才 READY，否则永久关闭。控制帧走 `Connection.send`（内部 `FrameCodec.encode`），二进制走 `Connection.sendBinary`。你通常不直接 new `Connection`，而是用 `ConnectionManager`。

`ConnectionManager` 负责：指数退避重连（`ReconnectPolicy`，默认 1s 起、上限 30s、±20% 抖动）、订阅簿记、READY 后自动 `list()` 并重放全部活跃 subscribe。时间源是 `Clock`（生产用 `Clock.Real`）。宿主必须周期性调用 `pump(nowMs)` 驱动重连、调用 `resolveExpiredInputs(nowMs)` 裁决输入超时（默认 10s）。网络恢复时可调 `onNetworkAvailable()`，在 RECONNECTING 时不等退避到点立刻重试。

业务入口（均要求非 STOPPED；未 READY 时 subscribe 只记簿、重连后重放）：

| 方法 | 发出的帧 |
|---|---|
| `list()` | `ListFrame` |
| `subscribe(ref, rows, cols)` | `SubscribeFrame` |
| `unsubscribe(ref)` | `UnsubscribeFrame` |
| `resize(ref, rows, cols)` | `ResizeFrame`（成功后更新簿记，重连重放最新行列） |
| `sendInput` / `sendKeystroke` / `sendInputKeys` / `sendBackspace` | `InputFrame`；结果必达 `onInputResult` |
| `scrollback(ref, fromLine, count)` | `ScrollbackFrame` |
| `sendScrollWheel` / `sendAttachPreview` | 无 ack，失败看 `ErrorFrame` |
| `subscribeLevel2` / `unsubscribeLevel2` | `Level2SubscribeFrame` / `Level2UnsubscribeFrame` |
| `subscribeOverlay` / `unsubscribeOverlay` | `OverlaySubscribeFrame` / `OverlayUnsubscribeFrame` |

上层只见回调：`setListener` 挂全局 `ConnectionManager.Listener`（`onStateChanged` / `onFrame` / `onBinary` / `onLocalDecodeError` / `onInputResult` / `onReconnect`）。本层不持久会话状态；链路的唯一事实源是主机 tmux。

### 必须按 ref 分发二进制帧（白屏根因）

`ConnectionManager` **原来**只有一个全局 listener 槽（`setListener`）。第二个订阅者把第一个挤掉之后，第一个会话的 SNAPSHOT/DELTA 没人收 ⇒ **白屏**。

现在二进制按会话 ref 分发。源码真实签名（`app/core-conn/src/main/java/dev/agentmirror/app/conn/ConnectionManager.kt`）：

```kotlin
fun addBinaryListener(ref: String, listener: Listener)
fun removeBinaryListener(ref: String, listener: Listener)
```

到达时：该 ref 下若有登记，只派给这些人；否则才回落到全局 `setListener`。造新客户端的人如果继续只用一个全局槽、或者打开第二个会话时覆盖同一 listener，会重踩这个坑。正确做法：每个打开的会话 `addBinaryListener(ref, sessionListener)`，关掉时 `removeBinaryListener`。控制帧（listing 等）仍走全局槽，那是列表页的事。

仪表出口 `ConnPerf` / `ConnDiag` 由壳注入，核模块默认空操作。

---

## 边界与纪律

- 三核是纯 JVM。Android UI、OkHttp 实现、绘制层（Surface/Compose）都在壳，不在核。壳里没有核源码，物理上改不了协议与仿真。
- 性能相关的东西钉在核里：打开链路上 `SubscribeFrame` 的发出、二进制帧按 ref 分发、`TerminalEmulator` 的 snapshot/feed 路径。如无必要不要改这些。改核必须过性能门 `tools/perfbase/judge-perf-ab.sh`（同机 A/B，门槛 +10%）。
- 不要拆按 ref 分发，不要把 `BinaryKind.SNAPSHOT` 当 DELTA 去 `feed`（必须 `replaySnapshot`），不要自己解析信封绕过 `FrameCodec`。
- 未知 `ErrorCode` 要容忍；未知控制帧 type 是错误。listing 的 seq 不连续时 `ConnectionManager` 会自动重新 list，客户端不要自己发明恢复协议。

---

## 一个最小可跑的例子

连上 → 订阅一个会话 → 拿到第一帧 → 喂给终端仿真 → 取出屏幕内容。传输请换成你的 `WebSocketTransport` 实现；下面假设 `transportFactory` 已经能拨到 sidecar。

```kotlin
val config = ConnectionConfig(url = "wss://host:port/ws", token = pairingToken)
val mgr = ConnectionManager(config, transportFactory)
val term = TerminalEmulator(cols = 80, rows = 24)
val sessionRef = "the-tmux-pane-ref" // 来自 ListingFrame.workspaces[].sessions[].ref

val sessionListener = object : ConnectionManager.Listener {
    override fun onStateChanged(state: ConnectionState) {
        if (state == ConnectionState.READY) {
            mgr.addBinaryListener(sessionRef, this)          // 先按 ref 挂上，再订
            mgr.subscribe(sessionRef, rows = 24, cols = 80)  // 发 SubscribeFrame
        }
    }
    override fun onFrame(frame: FramePayload) { /* listing / error / pane_mode … */ }
    override fun onBinary(frame: BinaryFrame) {
        if (frame.ref != sessionRef) return
        when (frame.kind) {
            BinaryKind.SNAPSHOT -> term.replaySnapshot(frame.data, term.cols, term.rows)
            BinaryKind.DELTA -> term.feed(frame.data)
            BinaryKind.SCROLLBACK -> term.prependHistory(frame.data)
        }
        val screen: ScreenSnapshot = term.snapshot()
        // screen.lines[y][x].text 就是该格字符；width==0 的格子跳过
        println("cursor=${screen.cursorX},${screen.cursorY} alt=${screen.altScreen}")
    }
    override fun onLocalDecodeError(code: FrameError, message: String) { /* 不要吞 */ }
    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) {}
    override fun onReconnect(attempt: Int, delayMs: Long) {}
}

mgr.setListener(sessionListener)
mgr.start()                          // Connection 内部发 AuthFrame，等 AuthAckFrame
// 宿主时钟泵（生产用定时器）：
// mgr.pump(System.currentTimeMillis())
// mgr.resolveExpiredInputs(System.currentTimeMillis())
```

顺序不能反：先 `addBinaryListener` 再 `subscribe`。成功订阅后服务端先发 SNAPSHOT 再流 DELTA；SNAPSHOT 必须走 `replaySnapshot`，DELTA 才走 `feed`。关掉会话时 `unsubscribe(sessionRef)` 并 `removeBinaryListener(sessionRef, sessionListener)`。
