# Web vs Android 终端模型对照（study-web-terminal-model）

> 对照标准：`web/` 客户端（xterm.js，MIT）vs `app/` 自研终端内核（Apache-2.0，避 GPL）。
> 同协议、同 daemon，四条缺陷全部出在安卓端。本文件是**读码对照**，所有结论附代码位置（文件:行）证据。
> 任务边界：不改产品代码，只产出本对照文档。

对照的四个模型点与用户现象对应：

| 模型点 | 对应用户现象 |
|---|---|
| 1. buffer 与 viewport 分离 | 「向上滑完全失效，只有一屏」 |
| 2. reflow 锚定 | 「发消息从上往下刷新，最新消息看不到」+「捏合后闪烁重绘」 |
| 3. cell 宽度求法（floor vs round） | 「最右侧文字被截断」 |
| 4. scrollback 分页拉取 | 「看不到打开会话前的历史」 |

**总览结论先行**：

- **三条**差异（模型点 1、3、4）直接由安卓自研内核的模型缺陷造成；**一条**（模型点 2）是协议级
  交互放大（捏合 resize 无合并 + 本地不 reflow），Web 端靠 xterm 内置能力 + 120ms 防抖规避。
- 最硬的根因是**模型点 3**：安卓把「上报给服务端的 cols」用**名义字格宽（默认 10px）**计算，
  而「画布的列推进」用**实测字形宽**计算——两个独立栅格从不收敛，导致最右列被裁。
- Web 端唯一比安卓更弱的是**历史不进本地滚动空间**（独立只读面板），但它用显式按钮 + xterm
  自身 buffer，用户现象（向上滑失效/看不到打开前历史）在 Web 上不出现。

---

## 模型点 1：buffer 与 viewport 分离

### Web 怎么做

xterm.js 内部持有完整 scrollback buffer，viewport 只是带偏移的窗口：

- 构造即开 buffer：`new TerminalCtor({ scrollback: 5000, ... })` — `web/js/terminal.js:37-43`
- 会话内**所有写**都进 buffer，两种路径同源：
  - `writeSnapshot(u8) { this.term.reset(); this.term.write(u8); }` — `web/js/terminal.js:74-77`
  - `writeDelta(u8) { this.term.write(u8); }` — `web/js/terminal.js:80-82`
- 滚动是 xterm 原生 viewport，**零网络**：wrapper 只挂 `onScroll` 回调（滚到 buffer 顶触发
  `onHistoryBoundary`）与 `scrollToBottom` — `web/js/terminal.js:52-55, 88`
- 滚到缓冲顶：`if (line <= 0 && this._lastScrollLine > 0) this.onHistoryBoundary();` —
  `web/js/terminal.js:53`，接线到 `app.js:78` 的 `loadHistory`

### Android 怎么做

buffer 与 viewport 在**架构上也是分离的**，但 buffer 的累积路径不同：

- 本地 buffer：`val scrollback = ScrollbackBuffer(scrollbackCapacity)`（默认 5000）—
  `app/terminal/.../TerminalEmulator.kt:54, 62`
- **只有屏幕顶行滚出时进 buffer**：`main.onLineScrolledOut = { line -> scrollback.appendTail(line) }` —
  `TerminalEmulator.kt:94-96`；滚出发生在 `TerminalGrid.scrollUp` — `TerminalGrid.kt:139-147`
- viewport = `TermViewPresenter.topLine / window`：跟随/锁定、只改偏移零网络 —
  `TermViewPresenter.kt:44, 115, 129-136, 150-158`
- 滚动 `onScrollBy` 的钳制逻辑（缺陷核心）：
  ```
  val maxTop = (logicalCount - height).coerceAtLeast(0)
  val current = topLine ?: maxTop
  val next = (current - deltaLines).coerceIn(0, maxTop)
  topLine = if (next >= maxTop) null else next
  ```
  — `TermViewPresenter.kt:150-155`

### 差异

1. **buffer 填充来源不同**。Web 的 xterm 从会话全部输出累积（快照 + delta 都写进 buffer）；
   Android 只从「屏幕滚出行」累积。打开会话时首帧走 `replaySnapshot` → `main.resize()` +
   `main.reset()` 重建网格（`TerminalEmulator.kt:139-140`），而 capture-pane 快照恰好只有可见屏
   `rows` 行、`replaySnapshot` 剥掉尾部 LF 后正好填满网格（`TerminalEmulator.kt:144-151`），
   **没有任何行滚出 → 本地 buffer 保持空** → `logicalCount == emulator.rows` →
   `maxTop == 0` → 上滑 `next` 被 `coerceIn(0, 0)` 钳到 0、`next >= maxTop` 恒真 → `topLine`
   恒保持 `null`（跟随态）→ **滑动完全无效**。「只有一屏」由此而来。
2. **历史拉取的触发依赖「先能锁定」**（详见模型点 4）：`syncFromPresenter` 只有在
   `locked && window.first == 0` 时才补页（`SessionViewModel.kt:303`）；而 buffer 空时用户
   **根本锁不住**（上一条），于是补页永不触发，向上滑 → 无历史 → 现象闭环。
3. ED3（`ESC[3J`）清空本地 scrollback：`if (mode == 3 && !altActive) scrollback.clear()` —
   `TerminalEmulator.kt:274`。CLI 清屏即把本地缓冲连根拔掉，再叠加差异 2，向上滑彻底无内容。

### 修法建议

- **a)** 让「补页」不依赖先锁定：把 `syncFromPresenter` 的 `atHistoryTop` 从
  `locked && window.first == 0`（`SessionViewModel.kt:303`）放宽为 `window.first == 0`
  （跟随态贴底时 `window.first == logicalCount - visibleRows`，需同时允许窗口顶触底即补页），
  或提供显式「加载更早历史」入口（对齐 Web 的「历史」按钮 `web/js/app.js:62`）。
- **b)** `onScrollBy` 在 `maxTop == 0` 时也应能置位锁定态：把 `topLine = if (next >= maxTop)
  null else next`（`TermViewPresenter.kt:155`）改为「`deltaLines` 为正且 `maxTop == 0` 时
  `topLine = 0`（并立即触发补页）」，使空 buffer 也能进入可补页的锁定态。
- **c)** ED3 是否该清本地 scrollback 需裁定：若保留「本地历史与远端一致」，清空后应
  **重置分页锚点并重新预取**（对齐 `SessionViewModel.kt:165-168` 的预取逻辑），而不是让 buffer
  空置。参照 Web：xterm 的 `term.reset()` 在 `writeSnapshot` 里同样清 buffer（`terminal.js:75`），
  但 xterm 随后的 `write` 重新累积 + 独立历史按钮兜底，Web 不会出现「无法滚动」。

---

## 模型点 2：reflow 锚定

### Web 怎么做

- resize 由 xterm 自身处理，`term.resize(cols, rows)` 触发 xterm **本地 reflow**（buffer 重排、
  保持内容）— `web/js/terminal.js:68`
- resize 上报**带 120ms 合并**：
  ```
  this._resizeTimer = setTimeout(() => this.onResize(this.term.rows, this.term.cols), 120);
  ```
  — `web/js/terminal.js:99-108`（注释明言 D-29：合并布局抖动、避免缩放期间闪烁重绘）
- resize 生效后服务端补发全屏 snapshot（协议 §6.2），客户端 `writeSnapshot` = `reset` + `write`
  单次清屏重建 — `terminal.js:74-77`

### Android 怎么做

- 捏合 → `ScaleGestureDetector.onScale` **在捏合全程连续触发** → `onFontSizeChanged(newW, newH)` —
  `TermSurfaceView.kt:108-117` → `recomputeGeometry` → `onResizeRequest`（`TermViewPresenter.kt:219-227, 230-237`）
  → `SessionViewModel.onResizeRequest` → `manager.resize` + `emulator.resize`（`SessionViewModel.kt:64-69`）。
  **无防抖**：捏合每秒几十次 `onScale`，就发几十次 resize 帧。
- 内核 resize **明确不 reflow**：
  ```
  fun resize(cols, rows) { main.resize(cols, rows); ... }
  ```
  KDoc：「只换尺寸不 reflow，内容以随后到达的服务端快照为准」— `TerminalEmulator.kt:159-172`；
  `TerminalGrid.resize` 保留左上角重叠区、其余 `Cell.BLANK` — `TerminalGrid.kt:263-280`
- 服务端每次 resize：`handleResize` → `br.Resize`（`resize-window`）+ 补发 snapshot —
  `server/internal/api/ws_handler.go:267-309` → 客户端 `replaySnapshot` 清屏重建（`SessionViewModel.kt:162-168`）
- 锚定策略：reflow 锚点 = 服务端快照；快照到达前，画面上是**截断的旧栅格**（左上角保留、
  其余空白，`TerminalGrid.kt:263-279`）

### 差异

1. **捏合 resize 无合并**：Web 有 120ms 定时器合并（`terminal.js:106`）；Android 每个
   `onScale` 事件都触发 `onResizeRequest`（`TermSurfaceView.kt:108-117`），造成 resize 帧风暴 →
   每次都是一次「tmux resize-window + 全屏快照重放 + 整帧重绘」→ 现象 4 的「捏合后整体重绘
   闪烁一段时间才好」。
2. **本地不 reflow**：Web 的 xterm resize 本地重排保持内容，快照到达只是收敛；Android
   resize 直接把旧栅格截断成左上角（`TerminalGrid.kt:263-280`），在服务端快照到达前用户看到
   的是残缺画面。
3. **现象 2（发消息从上往下刷新、最新消息看不到）**：发送后 CLI（recap）清屏重绘。Android
   跟随态窗口贴底（`window` 底 = 末逻辑行，`TermViewPresenter.kt:134`），清屏若走 ED3 会
   先清空 scrollback（`TerminalEmulator.kt:274`）→ `logicalCount` 收缩 → 跟随窗口落到重建后
   「短内容 + 底部空白」区；而「最新消息」在重绘内容的中上部 → 贴底窗口看不到它。
   （此条的精确触发需模拟器复现确认；代码能确认的是 ED3 清空本地缓冲 + 重绘高度不定时，
   跟随窗口只钉底部。）

### 修法建议

- **a)** 捏合 resize 合并，对齐 Web `terminal.js:106`：`onScale` 只更新目标字格，用
  `postDelayed(120ms)` 或 `onScaleEnd`（GestureDetector 的 `onScaleEnd`，`TermSurfaceView.kt:108`
  所在监听器里）合并为**一次** `onFontSizeChanged`/`onResizeRequest`。
- **b)** resize 期间本地先 reflow 保内容（仿 xterm Buffer.resize）：`TerminalGrid.resize`
  （`TerminalGrid.kt:263-280`）改为按新列宽重新换行打包，而不是左上角截断；至少保住
  「锚点行」位置不跳（对应 FIELD 里 reflow 锚定的定义）。
- **c)** ED3 清空 scrollback（`TerminalEmulator.kt:274`）改为清空后**立即重预取历史**并保持
  跟随窗口钉底，杜绝「清屏后最新消息不可见」。

---

## 模型点 3：cell 宽度求法（floor vs round）—— 现象 1 最硬根因

### Web 怎么做

- cols 求法 = **floor**：
  ```
  const cols = Math.max(2, Math.floor(this.container.clientWidth / this._charWidth()));
  const rows = Math.max(2, Math.floor(this.container.clientHeight / this._lineHeight()));
  ```
  — `web/js/terminal.js:65-66`。floor 保证 `cols × charWidth ≤ 容器宽`，**最后一列整格可见**。
- `_charWidth()` 用探针 span 实测 'M' 的 `getBoundingClientRect().width` — 真实字形推进宽 —
  `web/js/terminal.js:111-122`；`_lineHeight` 实测行高 — `terminal.js:125-130`
- 首次 `fit()` 在 `open()` 即执行（`terminal.js:56`），ResizeObserver 每次容器变化再 fit
  （`app.js:89-91, 121-123`）；`subscribe` 上报的就是实测 dims（`app.js:95`）——**cols 与绘制
  同一字形来源**。

### Android 怎么做

- cols 求法也是整数除法（等价 floor）：
  ```
  val rows = viewportHeightPx / cellHeight
  val cols = viewportWidthPx / cellWidth
  ```
  — `TermViewPresenter.kt:232-233`。方向对。
- **但 `cellWidth` 是名义值，不是实测字形宽**：
  - 默认 `DEFAULT_CELL_WIDTH = 10` — `TermViewPresenter.kt:334-337`
  - 仅捏合时被改：`onFontSizeChanged` 写 `cellWidth/cellHeight` — `TermViewPresenter.kt:219-221`
- **实测字形宽从不回写 presenter**：`TermSurfaceView.measureCells` 用
  `fgPaint.measureText("W")` 算 `cellW`、`roundToInt()` 取整 — `TermSurfaceView.kt:341-354`；
  该 `cellW` 只是 View 的**绘制定位**局部变量，与 `presenter.cellWidth` 全链路无任何赋值关联
  （grep：`cellWidth` 仅出现在 `TermViewPresenter` 定义/默认/`onFontSizeChanged`，以及
  `TermSurfaceView.kt:112-113` 读它做捏合缩放）。
- **两个独立栅格从不收敛**：上报给服务端、决定 pane 列数的是 `viewportWidth / cellWidth(名义 10)`；
  画布上每列推进的是 `cellW(实测，如 11px)`。若实测字形宽 > 名义 10px，则 `cols` 偏大 →
  一行 `cols` 个单元格 × 实测 `cellW` 超出视口右缘 → Canvas 越界被裁剪 → **最右侧文字被截断**
  （现象 1，第 4 次报告）。示例：视口 1080px、实测字格 11px、名义 10px →
  `cols = 1080/10 = 108`，但视口只放得下 `1080/11 = 98` 列 → 右缘 ~10 列被裁。
- 且 `measureCells` 用 `roundToInt()`（`TermSurfaceView.kt:352`）而非 floor：实测 10.6px →
  11px，**放大**绘制步进，比 floor 更容易越界。

### 差异

Web：cols 与绘制共用**同一实测字形宽**，且 floor 求列数（`terminal.js:65`），cols 计算出就
保证装得下。
Android：cols 用**名义字格**、绘制用**实测字格**，两条来源从未同步（`TermViewPresenter.kt:232-233`
vs `TermSurfaceView.kt:351-352`）；这是现象 1 的根因，且捏合放大时 `cellWidth` 短暂接近实测
（`textSize = cellHeight×0.85` 连带放大字形），所以「捏大字体反而正常」也与本条吻合。

### 修法建议

- **a)** 把实测字形宽回写 presenter：在 `measureCells()`（`TermSurfaceView.kt:341-354`）算出
  `cellW/cellH` 后调用 presenter 新设的方法
  `setMeasuredCellSize(cellW, cellH)`，使 `recomputeGeometry` 的
  `cols = viewportWidth / cellWidth`（`TermViewPresenter.kt:233`）与绘制用同一来源 →
  右侧自然不再裁。
- **b)** `cellW` 求法改 **floor**（`TermSurfaceView.kt:352` 的 `roundToInt()` → `floor`），宁可少
  一列，与 Web `terminal.js:65` 对齐。
- **c)** 初始几何不猜：`createSessionViewModel` 用 `INITIAL_ROWS=40 / INITIAL_COLS=120`
  （`SessionRoute.kt:131-132, 163-164`）构造订阅，是猜测值；应先测量 View 与字形再上报
  （Web 是 `open()` 即 `fit()` 用实测值 `app.js:95`）。至少把默认 `DEFAULT_CELL_WIDTH`
  （`TermViewPresenter.kt:336`）改为首次实测值，避免首帧 cols 偏大。

---

## 模型点 4：scrollback 分页拉取

### Web 怎么做

- 显式「历史」按钮 → `loadHistory`（`web/js/app.js:62, 105, 125-131`）→ `fetchOlder`
  （`web/js/scrollback.js:22-36`）：
  - `rows = g.term.rows`、`historyLines = Math.max(50, rows * 2)`、`fromLine = nextScrollbackLine`
    （首 `-historyLines`）— `scrollback.js:25-28`
  - `client.scrollback(ref, fromLine, count)` — `scrollback.js:30`（client 实现 `client.js:169-174`）
- 回复渲染到**独立只读面板**，与 live grid 分开、**绝不并入 live buffer**：
  `acceptScrollback → showScrollbackPanel`（`scrollback.js:46-53`；`app.js:196-206`）；
  注释明言「history is a separate, non-invasive view」「live grid keeps following the bottom」
  — `scrollback.js:8-10`
- 服务端收敛：`scrollbackRange` 钳制并报告实际区间 — `server/internal/api/ws_handler.go:318-370`
- 第二个触发：滚到 xterm 自身 buffer 顶 → `onHistoryBoundary` → `loadHistory` —
  `web/js/terminal.js:52-55, app.js:78`

### Android 怎么做

- 服务端同款协议：`handleScrollback`（`ws_handler.go:220-260`）、`scrollbackRange` 收敛
  （`ws_handler.go:318-370`）、`bridge.Pane.Scrollback` → `capture-pane -S/-E`（`bridge.go:96-100`）
- 客户端触发**两条**：
  - ① 首帧 snapshot 预取一页：`if (!hasPrefetchedHistory) { requestOlderHistoryPage() }` —
    `SessionViewModel.kt:165-168`；`HISTORY_PAGE = 400`（`SessionViewModel.kt:361`）
  - ② 轮询 `syncFromPresenter`：`atHistoryTop = locked && window.first == 0 && hasMoreHistory`
    → `requestOlderHistoryPage` — `SessionViewModel.kt:300-307`（轮询节拍 `SessionScreen.kt:161-166`
    `TICK_MS = 100`）
- 回复 → `emulator.prependHistory(frame.data)`（`SessionViewModel.kt:173-175`）→
  `parseHistoryLines`/`HistoryBuilder` 解析（`TerminalEmulator.kt:376-426`）→
  `ScrollbackBuffer.prependHead` **头插进本地 buffer**（`ScrollbackBuffer.kt:77-86`）
- 判顶：`if (frame.fromLine > historyRequestedFromLine) hasMoreHistory = false` —
  `SessionViewModel.kt:177-179`（服务端收敛后报告实际区间，见协议 §6.3 `docs/protocol.md:311-313`）

### 差异

1. **历史进不进滚动空间**：Web 把「打开前历史」放独立面板、live 空间只有会话内 buffer
   （`scrollback.js:8-10, 46-53`）；Android 把历史**头插进 scrollback buffer**，与 live 区构成
   同一连续滚动空间（`ScrollbackBuffer.prependHead`，`ScrollbackBuffer.kt:77-86`）。连续滚动体验
   更优，但触发与失败耦合了：
   - 补页**必须先锁定再滚到 `window.first == 0`**（`SessionViewModel.kt:303`）；buffer 空时
     锁不住（模型点 1 差异 1）→ 补页永不触发 → 「看不到打开会话前的历史」。
   - ED3 清空 buffer（`TerminalEmulator.kt:274`）直接摧毁已并入的历史。
2. **服务端测 historySize 是整段全量 capture**：`br.Scrollback(ctx, math.MinInt32, -1)` —
   `ws_handler.go:324` 每次补页都从最老拉到底再减屏高，远端（tailnet）下延迟放大，预取慢 →
   「只加载了一屏」的感知窗口变长。
3. **分页锚点恒按整页推进**：`historyNextFromLine = historyRequestedFromLine - HISTORY_PAGE`
   （`SessionViewModel.kt:335`），服务端收敛返回的钳制区间（如只剩 50 行）不会回写修正锚点，
   只是靠 `frame.fromLine > requestedFromLine` 判顶兜底（`SessionViewModel.kt:177-179`）——功能
   正确但多一次往返。Web 同样按 `nextScrollbackLine` 推进（`scrollback.js:49-50`），无本质差异。

### 修法建议

- **a)** 补页触发放宽为「窗口顶触底即可」：`SessionViewModel.kt:303` 的 `atHistoryTop` 去掉
  `locked` 前置（或增加显式「加载更早历史」按钮，对齐 Web `app.js:62`）。
- **b)** 补页后保持视口不动：`prependHead` 头插后若 `topLine` 冻结在历史区，窗口顶应仍指向
  原 `window.first`（`prependHead` 使 `logicalCount` 增长，`topLine` 是逻辑行号、随增长
  平移——需确认 `TermViewPresenter.window` 的 clamp 在头插后不把用户顶离当前位置，
  `TermViewPresenter.kt:129-136`）。
- **c)** 服务端 `scrollbackRange` 的 `historySize` 测量改按页：请求直接带 `-S <start> -E <end>`
  （`bridge.go:96-100` 已支持），避免每次 `MinInt32` 全量 capture（`ws_handler.go:324`）；
  若无法全量，至少把 `countLines` 结果缓存/幂等。
- **d)** ED3 清空后重置分页锚点并重新预取（见模型点 1 修法 c），使「打开前的历史」在清屏后仍
  可经补页取回。

---

## 附加：现象 4「字号不持久化」

- Web 侧：字号是构造常量 `fontSize: 14`（`web/js/terminal.js:39`），不持久化但每次加载一致；
  `preferences.js` 只持久化配对与主题（`web/js/preferences.js:18-76`），无字号键。
- Android 侧：`presenter.cellWidth/cellHeight` 仅内存态（`TermViewPresenter.kt:58-61, 219-221`）；
  `SessionViewModel`/presenter/emulator 每次进入会话重建（`SessionRoute.kt:78-80`），默认回到
  名义 `10/20`（`TermViewPresenter.kt:334-337`）——捏合字号随重建丢失；且默认名义值与实测字形
  不符（模型点 3），重建后 cols 又回到偏大。
- 修法：仿 `preferences.js` 加本地持久化（SharedPreferences/DataStore），重进入时用持久值
  初始化 `cellWidth/cellHeight`；默认值改为首次实测字形宽（与模型点 3 修法 a/c 合并）。

---

## 许可证红线核对

- xterm.js 为 MIT（`web/package.json:17` `@xterm/xterm: 6.0.0`，vendor 包 `web/vendor/xterm/`），
  可参考其模型与算法；本文件全部修法建议均为**行为级修改**（合并 resize、回写实测字格、
  放宽补页触发、按页取历史），不引入 GPL 代码（Termux 那套 GPLv3 保持隔离）。

## 证据索引

| 模型点 | Web 端证据 | Android 端证据 | 服务端证据 |
|---|---|---|---|
| 1 buffer/viewport | terminal.js:37-43,52-55,74-82 | TerminalEmulator.kt:54-62,94-96,139-151；TermViewPresenter.kt:150-158 | — |
| 2 reflow 锚定 | terminal.js:68,74-77,99-108 | TermSurfaceView.kt:108-117；TermViewPresenter.kt:219-237；TerminalEmulator.kt:159-172；TerminalGrid.kt:263-280 | ws_handler.go:267-309 |
| 3 cell 宽度 | terminal.js:65-66,111-122 | TermViewPresenter.kt:232-233,334-337；TermSurfaceView.kt:341-354 | — |
| 4 scrollback 分页 | scrollback.js:22-36,46-53；app.js:62,78 | SessionViewModel.kt:165-168,300-307,330-337,173-179；ScrollbackBuffer.kt:77-86 | ws_handler.go:220-260,318-370；bridge.go:96-100 |

## 未决项（需模拟器/真机实测确认，非读码可定）

1. 现象 2「发消息从上往下刷新」的确切触发序列（ED3 清屏 vs 增量重绘）——代码能确认的是 ED3
   清空本地 buffer（`TerminalEmulator.kt:274`）+ 重绘高度不定时跟随窗口只钉底部。
2. 冷启动 edge case：连接 READY 晚于首帧布局时，`viewportSeeded` 已置位（`TermViewPresenter.kt:191-207`）
   → `recomputeGeometry` 不再被调 → 首帧 `INITIAL_ROWS/COLS = 40×120`（`SessionRoute.kt:163-164`）
   的 corrective resize 丢失，整个会话卡在小窗口——需确认实际进入会话时连接是否已 READY。
3. 服务端 `scrollbackRange` 全量 capture 的耗时在真机上的体感（`ws_handler.go:324`）。
