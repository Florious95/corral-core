# 往返次数审计（roundtrip audit）

> 任务：fix-roundtrip-audit（leader 2026-08-13 派单，用户原话「测出来延迟高就得优化，说明某条路径有问题」）。
> 方法：**代码推导优先**（读 ws_handler.go / SessionViewModel.kt / conn 层请求-响应配对），
> 模拟器实测留给 w-base-v2（本席禁碰模拟器）。
> 定义：**往返 = 客户端发出一个请求 → 等到对应回复（或等到的数据到达）**。服务端内部对
> tmux 的同步调用不算客户端往返，但单独列出（它们在 TS 下同样耗时）。
> 目标：实际往返数 / 理论最少往返数，差值 = 优化空间。TS 单往返数百 ms，LAN 近 0——
> 往返数是 TS/LAN 体感差异的决定因素。

## 〇、协议往返模型（docs/protocol.md §5 请求-回复配对）

| 请求 | 回复 | 往返数 |
|---|---|---|
| `auth` | `auth_ack` | 1 |
| `list` | `listing` | 1 |
| `subscribe` | 二进制 `snapshot`（+ 后续 delta 流） | 1 |
| `input` | `input_ack` | 1 |
| `scrollback` | 二进制 `scrollback` | 1 |
| `resize` | 二进制 `snapshot`（真实变化时） | 1 |

每个交互 = 若干请求-回复配对，串行时往返数叠加。

## 一、冷启动 → 首屏可见

**实际往返序列**（从 SessionRoute / SessionViewModel / conn 接线推导）：

| 步 | 请求→回复 | 往返 | 载荷 |
|---|---|---|---|
| 1 | 配对/恢复 token → `auth` → `auth_ack` | 1 | 小（token） |
| 2 | `list` → `listing` | 1 | 全量工作区列表（会话名等） |
| 3 | `subscribe` → `snapshot` | 1 | **全屏快照**（capture-pane -e，含颜色，重量级） |
| 4 | 历史预取 `scrollback(-400,400)` → `scrollback` | 1 | 一页历史（ANSI 字节） |
| 合计 | | **4 往返** | 1 全屏快照 + 1 页历史 |

**理论最少**：2 往返（auth + subscribe→snapshot）。list 可与 subscribe 并行（选会话后立即订阅，
不等 listing 回来），历史预取可与 snapshot 并行。

**可省**：2 往返（list 并行化 + 历史预取并行化）= **TS 下省 ~600-800ms**（2×300-400ms）。
但注意：省掉 list 会失去「会话列表」，需 UI 保证默认进入会话。**历史预取不能省**（D-36 需要）。

## 二、发一条消息 → 看到自己的字

**实际序列**（SessionViewModel.sendInput → manager.input → handleInput → input_ack + 回显 delta）：

| 步 | 请求→回复 | 往返 | 载荷 |
|---|---|---|---|
| 1 | `input` → `input_ack` | 1 | 小（文本） |
| 2 | 回显：CLI 处理 → pipe-pane → `delta` 流回客户端 | 1（数据到达） | 增量字节 |
| 合计 | | **2 往返** | 小 + 增量 |

**理论最少**：1 往返（input → 回显 delta）。input_ack 是「必达回执」（003 契约），与回显
理论上可合并——但 ack 是**同步确认输入已进 pane**，回显是**异步 CLI 输出**，语义不同，
无法合并。

**结论**：2 往返已是理论最优（ack + 回显是两个不可合并的语义）。**无优化空间**，除非
ack 也能带「回显预测」（本地回显，003 已做本地输入条？——见 §七）。

## 三、捏合一次 → 画面稳定（重点）

**实际序列**（TermSurfaceView.onScale → presenter.onFontSizeChanged → onResizeRequest →
manager.resize → handleResize → snapshot 回传）：

- **每次 onScale 触发一次 resize**。一次捏合手势 = 十几个 onScale 事件（GestureDetector
  连续触发，TermSurfaceView.kt:110-118）。
- 每个 resize：`handleResize` 内 3 次串行 tmux 操作（Size before → Resize → Size after，
  真实变化再 + Snapshot 全屏 capture）。ws_handler.go:320-357。
- 若 tmux 真实 reflow（变化），客户端收到 1 个 snapshot（全屏重量级）。

**实际**：一次捏合 ≈ **10-20 个 resize 往返 + 10-20 次全屏快照传输**（每次变化都推 snapshot）。

**理论最少**：**1 个往返**（松手时发一次 resize → 一次 snapshot）。raw/041 已裁定
「捏合过程中只本地预览，松手时才发一次」——`fix-pinch-preview-commit` 已立案从未开工。

**可省**：~10-19 个往返 + ~10-19 次全屏快照 = **TS 下省数秒**（每次 resize 3 个 tmux 调用 ×
10-20 次 = 30-60 个 tmux 调用，每个 TS 下数百 ms）。**这是最大优化点。**

## 四、切后台 → 回前台 → 画面稳定

**实际序列**（TermSurfaceView.onWindowVisibilityChanged → presenter.onRealViewportChanged
→ onResizeRequest）：

- 回前台：`onRealViewportChanged` 重算几何，尺寸变化则 emit 一次 resize → `handleResize`。
- 若几何与内核一致 → **不 emit**（D-38 已修：几何一致不重复 resize）。
- 无重新订阅/重新取快照（保持原 subscription 的 delta 流）。

**实际**：0-1 个往返（仅几何变化时 1 个 resize → snapshot）。

**理论最少**：0（后台期间 subscription 保持，回前台 delta 流直接续上）。

**结论**：**已接近最优**（D-38 修复后）。无重新订阅浪费。

## 五、上滑翻一页历史

**实际序列**（SessionViewModel syncFromPresenter → requestOlderHistoryPage →
manager.scrollback → handleScrollback → scrollbackRange + Scrollback）：

| 步 | 请求→回复 | 往返 | 载荷 |
|---|---|---|---|
| 1 | `scrollback(-400,400)` → `scrollback` | 1 | 一页历史 |
| 服务端内部 | `scrollbackRange` 内 **2 次 tmux capture**：先全量 `MinInt32,-1` 测历史量，再取页 | （服务端内） | 全量 + 页 |

**实际**：1 往返（客户端视角），但服务端内部 2 次 capture，其中 1 次是**全量**（每次翻页
都从最老 capture 到底，docs/web-vs-android 模型点 4 已标注）。

**理论最少**：1 往返 + 服务端内部 1 次 capture（直接按请求 range capture，不先全量测历史量；
或把 historySize 缓存）。

**可省**：服务端内部 1 次全量 capture/请求。**数据量可减**：全量历史 capture 在长会话下
是重量级（可能数十 KB-数百 KB），而实际只需一页。TS 下这次全量 capture 是翻页延迟的
主要来源。

## 六、传输量估算

| 交互 | 载荷 | 重量级？ |
|---|---|---|
| 冷启动 snapshot | 全屏（~24×80 格 × 颜色字节） | 是（约几 KB-几十 KB ANSI） |
| 历史预取 | 400 行 ANSI | 中（几百行） |
| 发送回显 | 增量（几行） | 否 |
| **捏合每次 snapshot** | 全屏 | **是（重复 N 次）** |
| **历史翻页服务端全量 capture** | 全历史 | **是（每次翻页）** |

**两个重量级载荷浪费**：捏合 N 次全屏快照、历史翻页每次全量 capture。

## 七、优化点排序（按 TS 下省时）

| 优先级 | 优化 | 省往返 | 省载荷 | TS 下省时 | 关联任务 |
|---|---|---|---|---|---|
| **P0** | **捏合松手才发一次 resize** | 10-19 | 10-19 次全屏 | 数秒 | fix-pinch-preview-commit（已立案未开工） |
| **P1** | **历史翻页服务端免全量测高**（直接按 range capture 或缓存 historySize） | 0（客户端） | 每次翻页省全量 capture | 数百 ms/页 | docs 模型点 4 修法 c |
| P2 | 冷启动 list 与 subscribe 并行 | 1 | 0 | ~300-400ms | 协议层顺序 |
| P3 | 发送本地回显预测（可选，003 语义） | 0 | 0 | 感知延迟 | 需裁定 |

## 八、结论

- **最大浪费 = 捏合**（P0）：一次捏合 10-20 个 resize 往返 + 10-20 次全屏快照，TS 下数秒。
  `fix-pinch-preview-commit` 的失败态基准：**量一次真实捏合发了几次 resize、传了多少字节**
  （需 w-base-v2 模拟器实测，本席只做代码推导——本审计确认 raw/041 的判定方向）。
- **次大 = 历史翻页**（P1）：每次翻页服务端全量 capture 一次，TS 下数百 ms。
- 发送（2 往返理论最优）与回前台（已接近最优）无空间。

**代码推导已完成**。若要上模拟器实测（量真实捏合 resize 次数/字节数、真实翻页数据量），
需 w-base-v2 在隔离 daemon + 注延迟条件下跑 S2 捏合场景 / S4 上滑场景，采集指标如下：
- 捏合：logcat/协议层数一次捏合的 `resize` 帧数 + 累计 `snapshot` 字节数。
- 翻页：一次 `scrollback` 请求的服务端处理耗时 + 传输字节（对比理论最少）。
