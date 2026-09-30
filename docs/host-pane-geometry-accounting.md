# 主机 tmux pane 几何记账缺陷（host-pane-geometry-accounting）

> 状态：**已修复，待与 w-base-v2 实测对表**（2026-08-13）。本文档从只读根因链演进为修复记录。
>
> 关联：D-38「后台返回视口不恢复」客户端部分已判定通过；本缺陷是 D-38 分析过程中挖出的**契约级**问题（用户实证：Mac 上同一个 CLI 底部也被截断，pane 尺寸被改且不恢复）。**注意：修复不标为「D-38 修复」**——用户报的「回前台对话框跑到中间」无证据由本缺陷造成；本修复覆盖的是异常断连与多订阅两条路径。

## 一句话根因

App 通过 ResizeFrame 把主机 tmux pane 改小，但**只有显式退出会话才恢复原尺寸**；连接中途断开 / 服务端关闭 / 多客户端先后订阅时，pane 停在改小尺寸甚至恢复成**错误的叠加基线**。

## 完整链路（App → 主机 tmux pane）

```
TermViewPresenter 几何事件
  → recomputeGeometry() → onResizeRequest(rows, cols)   [仅 rows/cols ≠ 内核时]
    → SessionViewModel presenter 回调（app/.../session/SessionViewModel.kt:64-68）
      → ConnectionManager.resize(ref, rows, cols)        [app/.../conn/ConnectionManager.kt:336]
        → conn.send(ResizeFrame)                          [Frames.kt:369]
          → 服务端 handleResize → bridge.Pane.Resize      [server/internal/bridge/bridge.go:238]
            → tmux set-option window-size latest + resize-window → 主机 pane 实际改尺寸
```

## 三条 pane 改小路径

`recomputeGeometry` 的 `rows = viewportHeightPx / cellHeight`：

| # | 触发 | 入口 | 说明 |
|---|---|---|---|
| 1 | **首帧 seed 抓到被挤压的小几何**（进会话时 IME/输入框在屏） | `onViewportSizeChanged` 首帧（TermViewPresenter.kt:259） | 首帧 seed 一次 emit 小 rows，此后 IME 收起增长不再重算（fix-ime-no-resize 语义） |
| 2 | **捏合放大字号** | `onFontSizeChanged`（TermViewPresenter.kt:287） | 字格变大 → rows = 视口高/字格高 变小 |
| 3 | **真实视口变小**（旋转/分屏/窗口变更） | `onRealViewportChanged`（TermViewPresenter.kt:228） | View 变小 → rows 变小 |

App 侧 **ResizeFrame 唯一出口**：`SessionViewModel.kt:64-68`（presenter 回调）。订阅初始尺寸来自 `SessionRoute.kt:131` 的 `INITIAL_ROWS=40 / INITIAL_COLS=120`（SubscribeFrame，仅初始建议，进会话首帧 seed 后被实际像素覆盖）。

## 恢复缺失：只有一条路径会恢复

服务端 subscribe 时记录原几何并设恢复闭包（`ws_handler.go:111` 记 `origCols/origRows`，`ws_handler.go:158` 设 `sub.restoreSize = func(){ Resize(origCols, origRows) }`）。

**`restoreSize` 只在 `subscribeCancel` 被调用**（`ws_conn.go:360`，`ws_conn.go:370-371`）：

| 场景 | 路径 | restoreSize 是否调用 | pane 是否恢复 |
|---|---|---|---|
| **显式退出会话** | App `dispose()` → `manager.unsubscribe` → `handleUnsubscribe`（ws_handler.go:171）→ `subscribeCancel` | ✅ | 恢复到订阅前几何 |
| **连接中途断开**（切后台久/杀进程/网络断） | `readLoop` 退出 → `teardown()`（ws_conn.go:187）只 `cancel()`+`detach()` | ❌ **不调** | 停在改小尺寸 |
| **服务端优雅关闭** | `Server.Close` → `closeSubscriptions()`（ws_conn.go:346）只 `cancel()`+`detach()` | ❌ **不调** | 停在改小尺寸 |
| **relay 流关闭**（pane 死/detach） | relay defer（ws_conn.go:383）只 `detach()`+移除 | ❌ **不调** | 停在改小尺寸 |

**不对称确认**：正常退出有恢复、异常断连无恢复。用户的场景（手机上切后台、连接大概率被断）走的就是 `teardown` 这条不恢复的路——这精确对上「Mac 上 CLI 底部被截断」的症状。

## 多客户端记账缺陷（契约级）

`bridge.Pane` 是**跨连接共享的同一 tmux pane**（`resolvePane` → `c.s.catalog.entry(ref)` 返回共享实例；`Resize` 直接 `resize-window` 作用于 pane）。每个订阅者 `handleSubscribe` 独立走：

```
resolvePane(ref) → subscribeCancel(ref) [仅本连接同 ref] → br.Size() 记 origCols/origRows → br.Resize(客户端尺寸)
```

`br.Size()` 是**实时读 tmux pane 当前尺寸**（`bridge.go:279`，fresh read 从不缓存）。因此：

1. 客户端 A 订阅：origA = Mac 原尺寸（如 120×40），pane → A 的手机尺寸（如 108×96）；
2. 客户端 B 订阅：`br.Size()` 读到 pane 当前 = 108×96，**记 origB = 108×96**；
3. A 断连（teardown 不恢复）→ pane 停在 108×96；
4. B 正常退出 → `restoreSize` 恢复 pane 到 origB = **108×96**（不是 Mac 原 120×40）。

**结果：pane 永远回不到原始几何，且每轮订阅把「当前已变形尺寸」当新 orig 基线 → 恢复成错尺寸、逐轮叠加。**

## leader 裁定（契约级，2026-08-13）

1. **原始几何必须是 pane 级单例，不是订阅者级快照**——首个订阅者记录，最后一个退订者恢复；中间订阅者一律不记不改基线。
2. **异常断连（teardown/closeSubscriptions）与正常退出走同一条恢复路径**。现在只有正常退出有恢复，断连直接漏，不对称。
3. **客户端 `dispose()` 要触发恢复**，不能只 unsubscribe。

## 两个前置（均已解除）

1. **实测确认**：w-base-v2 五点实测（进会话前/进会话后/回前台后/退出会话后/再次进入）正常退出+单客户端路径确实良好（④=①、⑤=②），与代码分析一致。本修复覆盖的是**异常断连与多订阅**两条路径，这两条实测尚未验证（w-base-v2 重测中，填满终端后做捏合/IME切后台/双客户端）。
2. **产品裁定已解除**：用户明确「改 pane 是必须的，不是缺陷」「实现只要和我第一次进对话的实现一样就行」——争议点不是「该不该改」，而是「重复进入会话时算出的几何 ≠ 第一次进入时算出的几何」。

## 修复实现（2026-08-13）

生产改动全部在服务端 Go（客户端 `dispose→unsubscribe→恢复` 经验证已通、无需改）：

| 文件 | 改动 |
|---|---|
| `server/internal/api/pane_geometry.go`（新） | pane 级原始几何单例：首个订阅者 `acquire` 记基线、中间订阅者只增计数不覆盖、最后一个 `release` 归零恢复——契约 1 |
| `server/internal/api/server.go` | Server 挂 `paneGeoms map[string]*paneGeometry`（跨连接共享） |
| `server/internal/api/ws_conn.go` | `subscription.restoreOnce`；新增 `teardownSubscription`（cancel+detach+release），`subscribeCancel`/`teardown`/`closeSubscriptions`/relay 退出全部走它——契约 2 |
| `server/internal/api/ws_handler.go` | `handleSubscribe` 用 `geom.acquire`，`sub.restoreSize` 闭包调 `geom.release` |

红测（`host_pane_geometry_red_test.go`，先红后绿已验证）：
- 守卫 `TestFirstEntryResizesPane`：首次进会话正常 resize（80x24→108x96），恒绿，防「永不 resize」糊弄；
- `TestAbnormalTeardownRestoresPaneGeometry`：异常断连后 pane **108x96→80x24** 恢复；
- `TestSecondSubscriberDoesNotRebase`：A(108x96)+B(60x40)，A 断连+B 退订后 pane **60x40→80x24** 恢复（B 不再把 A 的变形尺寸当基线）。
- 假绿 `TestReentryGeometryReproduced` 已删（单客户端两次同尺寸天然假绿，无法从 pane 数字区分「真恢复」与「停在变形」）。

## ⚠️ 为什么恢复逻辑写了也可能不生效（留给后来人的账）

修复过程中实测抓到一个单看代码永远发现不了的坑：

**`release` 恢复 Resize 必须用 `context.Background()`，不能用发起恢复操作的连接的 ctx。**

根因链：`runTmux(ctx, ...)` 用传入 ctx 派生子 ctx，父 ctx 一旦取消，`exec.Command` 会**立即 kill 正在跑的 tmux 命令**。而 teardown/断连路径下，连接已经关闭、`c.ctx` 已 `cancel()` —— 此时调用 `sub.restoreSize()` → `geom.release(c.ctx, ...)` → `br.Resize(c.ctx)` 会因 ctx 取消而**静默失败**（只留一条 debug 日志）。

表现：代码看起来「恢复了」，单测里显式退订也绿（那时连接还活着、ctx 未取消），但**切后台断连那一路恢复根本没执行成功**——pane 停在变形尺寸，用户永远看不到被改小的恢复。

验证：`TestAbnormalTeardownRestoresPaneGeometry` 用 `c.ctx` 时红（108x96 不恢复）、改 `context.Background()` 后绿（回到 80x24）。

**教训**：凡是「清理收尾」动作（恢复尺寸、发 ack、关资源），都不能复用发起操作的那个 ctx——它可能已经死了。用独立生命周期（Background / 服务级 ctx）。

## 同形风险扫描（2026-08-13，全 server/ 排查）

按「在 teardown/cancel/退出路径上调用需要 ctx 的外部命令，而那个 ctx 恰已取消」的形状，扫描了 server/ 全部对 `br.*` / `runTmux` / `exec.Command*` 的调用。结论：**服务端没有第二个「清理路径必然用已取消 ctx」的同形坑**，但有三类邻近风险列在下面供裁。

### 已确认安全（正例，无需改）

| 位置 | ctx | 说明 |
|---|---|---|
| `bridge/stream.go:140,193`（detach 拆 pipe） | `context.Background()` | 清理路径已正确规避 |
| `bridge/stream.go:147`（attach 前置 detach） | `context.Background()` | 同上 |
| `pane_geometry.go:98`（release 恢复 Resize） | `context.Background()` | 本修复 |
| `ws_handler.go:129,139`（subscribe 错误路径 detach） | bridge 内部 Background | 安全 |

### 低危竞态（请求路径 × 断连瞬间，建议观察不修）

| 位置 | ctx | 风险 | 后果 |
|---|---|---|---|
| `ws_handler.go:115,121,202,219,257,320,327,335`（handleSubscribe/Input/Scrollback/Resize 全部 `br.*(c.ctx)`） | `c.ctx` | readLoop 串行处理帧，`teardown()` 在其退出后执行（`serveConn` 顺序保证），竞态窗口极窄；仅当一帧正在执行 `br.*` 的瞬间连接被 CloseNow | tmux 命令被 exec kill → 该帧静默失败。**自愈**：快照/增量流下次对账恢复，无资源泄漏 |
| `ws_handler.go:387`（Scrollback 用 ctx） | `c.ctx` | 同上 | 同上 |

**为什么不修**：这是「请求路径」而非「清理路径」——请求本来就依赖 ctx 活着。断连瞬间的竞态是任何 ctx 模式都有的窗口，且失败可自愈（不产生「写了没生效」的永久状态）。若担心，可在 `br.*` 外层加「断连时错误吞掉」的防御，但收益低。

### 设计预期（Close 后降级，非坑）

| 位置 | ctx | 行为 | 判定 |
|---|---|---|---|
| `state_wiring.go` `refresh` → `sample`/`capturePaneOutput`（:295,366）、`identify` → `ps`（agentstate/identify.go:151） | `p.ctx`（`Close()` 时取消） | Close 后在途刷新用已取消 ctx → exec kill → **降级 StateUnknown** | **设计预期**：`state_wiring.go:39` 注释明确「Every failure — sample error, **cancelled**/budget-exceeded Identify — degrades to unknown」。有明确降级契约，不是「写了没生效」 |
| `discovery/scan.go:152`（list-panes） | listing loop ctx（`loopStop()` 取消） | `Server.Close` 先 `loopStop()`；loop 在 `ctx.Done()` return，不再发起新 scan；在途 scan 检查 `ctx.Err()` 快速返回 | 安全，Close 后不调用 |

### 结论

坑的形状是「清理/收尾路径上的外部命令 + 已取消 ctx」。服务端唯一真正命中此形的是 `pane_geometry.release`（已修）。其余要么用 Background（正例）、要么是请求路径低危竞态（自愈）、要么是 Close 后明确降级（设计预期）。**建议：不修。** 若要加一道保险，可考虑给 `handleResize`/`handleInput` 的 `br.*(c.ctx)` 包一层「连接已关闭即跳过」，但当前无证据表明它在真实环境造成问题。

## 涉及文件与行号（证据索引）

| 文件 | 行号 | 作用 |
|---|---|---|
| `app/.../session/SessionViewModel.kt` | 64-68 | presenter 回调 → `manager.resize`（ResizeFrame 唯一出口） |
| `app/.../session/SessionViewModel.kt` | 322 | `dispose()` 只 `unsubscribe`（客户端侧恢复经此触发服务端，已通） |
| `app/.../session/SessionRoute.kt` | 131,163-164 | `INITIAL_ROWS=40 / INITIAL_COLS=120`（SubscribeFrame 初始建议） |
| `app/.../termview/TermViewPresenter.kt` | 228,259,287 | `onRealViewportChanged`/`onViewportSizeChanged`/`onFontSizeChanged`（改 pane 路径） |
| `app/.../conn/ConnectionManager.kt` | 336-342 | `resize()` → `conn.send(ResizeFrame)` |
| `server/internal/api/pane_geometry.go`（新） | 全部 | pane 级几何单例：acquire 记基线/release 归零恢复 |
| `server/internal/api/server.go` | 50-58,107 | Server 挂 `paneGeoms` map |
| `server/internal/api/ws_conn.go` | 60-78 | `subscription.restoreOnce`（幂等防双调） |
| `server/internal/api/ws_conn.go` | 360-375 | `teardownSubscription`：统一 teardown 路径（cancel+detach+release） |
| `server/internal/api/ws_conn.go` | ~195-208 | `teardown()` 走统一路径（修复前不调 restoreSize） |
| `server/internal/api/ws_conn.go` | ~349-358 | `closeSubscriptions()` 走统一路径（修复前不调） |
| `server/internal/api/ws_conn.go` | ~397-410 | relay 退出 defer 走统一路径（修复前不调） |
| `server/internal/api/ws_handler.go` | 103-118 | `handleSubscribe` 用 `geom.acquire`（替代 per-conn 记 orig） |
| `server/internal/api/ws_handler.go` | ~148-158 | `sub.restoreSize` 闭包调 `geom.release` |
| `server/internal/bridge/bridge.go` | 238-254,279 | `Resize`（window-size latest + resize-window）/`Size`（fresh read） |
