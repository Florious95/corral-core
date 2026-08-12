# D-37 特殊键条连按调研 — MVP 方案

- 席位：w-research-keybar（一次性调研席，只出方案不改工程代码）
- 日期：2026-08-12
- 状态：调研完成，方案待 leader/裁定席验收
- 禁止 git commit/push

## 0. 问题定义

特殊键条（Esc/Ctrl-C/Tab/↑↓←→）按下后 UI 显示"发送中…"，等待 `input_ack` 才允许
下一次按键，导致无法连按（如 Esc 连按两次打断 agent）。

目标：键条改为**非阻塞连按**（fire-and-forget，按一下发一个 `input.keys` 帧，不等 ack
即可按下一个），同时保住"发送必达"（003）的失败可见性。草稿发送保持现状（文本清框
语义依赖回执）。

## 1. 现状链路与阻塞点（已读代码实锤）

| 层 | 位置 | 行为 |
|---|---|---|
| UI | `SessionScreen.kt:236` | `KeyBar(enabled = viewModel.inputStatus !is InputStatus.Sending)` 发送中键条整体置灰 |
| UI | `SessionScreen.kt:486-490` | `Surface(onClick=…, enabled=enabled)` enabled=false 时点击被忽略 |
| VM | `SessionViewModel.kt:288-300` `sendKey()` | `if (inputStatus is InputStatus.Sending) return` **发送闸**，在途不回发 |
| VM | `SessionViewModel.kt:218-234` `onInputResult()` | 守卫 `if (inputStatus !is InputStatus.Sending) return`；ack 到才 Sending→Sent/Failed |
| conn | `ConnectionManager.kt:254-262` `sendInputKeys()` | 已支持多次调用，每 req_id 独立登记 `pendingInputs`（10s 超时） |

**根因**：VM 用单一 `InputStatus.Sending` 闸合并了所有发送（草稿+键），而 `sendInputKeys`
返回 `Boolean`（**不返回 req_id**），VM 无法把 ack 路由到具体那次发送——只能用"在途
最多一个"来规避歧义。键条连按被这个闸卡死。

## 2. 服务端顺序保证（零改动前提，已验证）

`server/internal/api/ws_conn.go:92-106`：

- `readLoop()` 是**单协程串行**读帧：每帧 → `handleFrame` → 同步 `handleInput`（同步调
  tmux send-keys，阻塞直到注入完成）→ 读下一帧。
- WebSocket over TCP 本身保序；同一条连接上客户端按发送顺序发出的帧必然按序到达。

**结论**：客户端连发 N 个 `input.keys` 帧（Esc Esc / Esc Ctrl-C / …），服务端必然
**按到达顺序逐个注入 tmux**。不存在并发竞态、不存在乱序重排。Esc 连按两次 =
tmux 收到 `Escape Escape`，顺序正确。**服务端无需任何改动**。

客户端侧同理：`OkHttpWebSocketTransport.sendText` 走 OkHttp 异步 send 队列，FIFO 保序；
返回 true 仅表示已入队，不是已写出（对连发无碍，队列内顺序即发送顺序）。

## 3. ack 失败/超时/掉线处理（保持必达语义）

键是瞬时动作，无重发价值——失败可见即可，用户重按即重发。conn 层现有簿记已覆盖全部
失败类：

- `input_ack ok:false` → `resolveInput` 按 req_id 独立判失败（`ConnectionManager.kt:431-435`）
- 10s 超时无回执 → `resolveExpiredInputs` 判 `timeout`（`ConnectionManager.kt:190-200`）
- 掉线/stop → `failAllPending` 逐个判失败（`ConnectionManager.kt:437-447`）

`pendingInputs` 是 `LinkedHashMap<Long, PendingInput>`，**天然支持多键在途 + ack 乱序**
（每 req_id 独立 remove + resolve）。关键：VM 需要拿到 req_id 才能把 ack 路由到
"这是键的回执 / 这是草稿的回执"——见 §5。

## 4. 是否需要客户端排队？（结论：不需要）

连按的按键节奏（人按键盘 ~100–300ms/次，最极端快速连按也远低于 10 键/秒）远达不到
服务端吞吐压力：`handleInput` 单个 keys 帧是 1 次 tmux 调用（亚毫秒级），服务端
`sendCh` 缓冲 256。排队会引入额外延迟与复杂度，fire-and-forget 即发即走是正确选择。

防御性兜底（可选，MVP 建议加一行）：VM 侧按键节流上限（如 200ms 内最多 20 键 / 连续
按键快速去重——Esc 按住时移动端不会自动连发，但触摸狂点有界）。仅作护栏，不影响
正常连按。

## 5. MVP 方案（分层，改动最小、一次做对）

### 5.1 前提改动：conn 层返回 req_id（消除歧义的关键）

`ConnectionManager.sendInput` / `sendInputKeys` 当前返回 `Boolean`，改为返回
`Long?`（`null` = 不可发送；非 null = req_id，与 `pendingInputs` 的 key 对齐）。

- 这是 ack 精确路由的**必要条件**：VM 收到 `onInputResult(reqId, ok, reason)` 后，
  必须知道该 req_id 是键还是草稿，才能正确分支（键回执不动草稿 / 草稿回执清框）。
- 改动向后兼容：现有调用方只多一个可用返回值；`Boolean` 语义被 `null` 判定取代。
- 影响面：`SessionViewModel` 两处调用 + 测试（`SessionViewModelTest` 用假 transport
  断言帧，conn 层测试断言 req_id 递增——均不破坏）。

### 5.2 VM 层：键去闸、草稿保闸

`SessionViewModel` 增加 `pendingKeys: MutableSet<Long>`（或 `pendingDraftReqId: Long?`）：

- `sendKey(key)`：
  - 保留 READY 检查（未就绪明确报错 `InputStatus.Failed`，不发帧）。
  - **去掉 Sending 闸**：`manager.sendInputKeys(ref, key)` 非 null → 记 `pendingKeys.add(reqId)`，
    不置 Sending（键条永不因发送置灰，可连按）。
  - `onInputResult(reqId, ok, reason)`：
    - `reqId in pendingKeys` → **键回执**：`pendingKeys.remove(reqId)`；ok → 短暂
      `InputStatus.Sent`（复用现成 "已发送" 瞬态 + TRANSIENT_MS 自动收起，不动草稿）；
      !ok → `InputStatus.Failed(mapInputReason(reason))`（失败可见）。**删除旧的
      `if (inputStatus !is InputStatus.Sending) return` 守卫**——改为按 req_id 查表路由。
    - 否则 → **草稿回执**：走既有逻辑（ok 清框 / !ok 保留 + 报错）。
  - `pendingSendIsKey` 标志退役（其职责被 req_id 查表取代）。
- `sendDraft()`：**保留 Sending 闸**（草稿回执语义依赖：ok 清输入框 + 清附件标记，
  必须等回执；草稿与草稿不得并发）。登记 `pendingDraftReqId` 供 5.2 路由。

### 5.3 UI 层

- `SessionScreen.kt:236`：`KeyBar(enabled = …)` 改为**始终 enabled**（或仅按
  `connectionState == READY` 置灰，键条不再因发送在途置灰）。
- `SessionScreen.kt:486-490`：键帽 `Surface enabled` 随之放开（连按生效）。
- `InputBar` 的发送按钮 Sending 置灰保留（只针对草稿）。
- 状态区：键回执显示"已发送"瞬态；草稿仍显示"发送中…"。（现有 StatusArea 已区分
  Sent/Sending，无需结构性改动。）

### 5.4 节流护栏（可选）

`sendKey` 内加 200ms 窗口 ≤ N（如 20）键的计数护栏，超限丢弃并短暂提示——防极端
狂点把队列打满；正常连按不受影响。

## 6. 风险清单

| 风险 | 判定 | 处置 |
|---|---|---|
| 连发多帧服务端乱序 | 无——单 readLoop 串行 + WS 保序 | 零风险，无需服务端改动 |
| ack 乱序到达 | 低——conn 层按 req_id 独立 resolve，天然安全 | VM 按 req_id 查表路由，乱序也正确 |
| 键回执误动草稿 | 消除——req_id 查表把键/草稿严格分开 | §5.2 |
| 掉线瞬间连按 | 低——failAllPending 逐个判失败，用户重按即可 | 失败可见，瞬时动作无重发价值 |
| 狂点堆积队列 | 低——人按节奏远低于服务端吞吐；OkHttp 队列 FIFO 保序不丢 | §5.4 节流护栏兜底 |
| 超时误报 | 现有 10s 超时不变；连发时各 req 独立超时独立报 | 不变 |

## 7. 影响面与测试

- 改动文件：`ConnectionManager.kt`（返回类型）、`SessionViewModel.kt`（sendKey/
  onInputResult/状态）、`SessionScreen.kt`（KeyBar enabled）。
- 现有测试：`SessionViewModelTest` 中 `sendKeyAckOkKeepsDraft` / `sendKeyFailureKeepsDraft…`
  / `sendWhileDisconnected…` 断言需按新 req_id 语义微调（断言目标不变：草稿保留、
  失败可见、无帧不发）；`ConnManagerTest` req_id 递增断言不受影响。
- 新增测试建议：连按两键（Esc、Esc）→ 两个 `input.keys` 帧按序发出且 VM 状态不被
  第一键的 Sending 卡住；两键 ack 乱序到达 → 各自正确路由；草稿在途时连按键 →
  键回执不动草稿。

## 8. 一句话结论

服务端**保证多帧顺序**（单 readLoop 串行），客户端 conn 层**已支持多键在途**
（每 req_id 独立簿记、乱序安全），唯一卡点就是 VM 层用单一 Sending 闸合并键/草稿。
MVP = conn 层返回 req_id + VM 键去闸按 req_id 路由 + UI 键条放开，服务端零改动，
约 3 个文件、一处返回类型变更。
