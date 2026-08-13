# 远程滚动投送协议设计方案

> 作者：协议设计席 w-scroll-design  
> 日期：2026-08-14  
> 状态：**leader 已裁定（2026-08-14），等待用户点头后进入实现阶段**  
> 范围：`docs/` only，`app/` 与 `server/` 零改动

---

## TL;DR（推荐方案摘要）

| 层 | 推荐做法 |
|---|---|
| **新协议帧** | 新增 `TypeScrollWheel`（C→S），不复用 Input |
| **mouse-tracking 判定 + 动作** | `tmux if-shell -F '#{mouse_any_flag}' '<注入字节>' '<copy-mode>'`（原子，消除竞态） |
| **有 mouse tracking** | `send-keys -H` 注入 SGR 或 X10 格式字节，不追加 Enter |
| **无 mouse tracking** | `copy-mode -e` + `send-keys -X scroll-up/down`（tmux 负责降级） |
| **切换判据** | `mouse_any_flag`（0/1），已在 tmux 3.6a 上实测，可被测试 |
| **成功回执** | **不发 ScrollWheelAck**（屏幕内容变化即是反馈，加往返会让滑动变粘） |
| **失败通知** | 失败发错误帧（pane 消失 / tmux 命令失败 / 目标不可达） |
| **copy-mode 模式通知** | 服务端进/出 copy-mode 时推 `TypePaneModeChanged`（S→C）帧，App 显示最小指示 |
| **输入安全兜底** | `handleInput` 前若 `pane_in_mode=1`，先 `send-keys -X cancel` 退出再送键 |

---

## 实测基底（2026-08-14 隔离验证）

> socket：`/tmp/tmux-scroll-test-design`（已销毁）  
> tmux 版本：3.6a  
> 绝对不碰生产 daemon（pid 70317）

**以下两条是 2026-08-14 leader 裁定后的补充实测（第二轮，socket `/tmp/tmux-scroll-test-design2`）：**

### 实测 0：`if-shell -F` 作为原子判定+动作

```bash
# Case A: bare shell (mouse_any_flag=0) → 应进 copy-mode
tmux if-shell -F -t test '#{mouse_any_flag}' \
  "send-keys -H -t test '1b5b4d602121'" \
  "copy-mode -e -t test"
# pane_in_mode=1 ✓  (进了 copy-mode，未注入字节)

# Case B: vim+mouse=a (mouse_any_flag=1) → 应注入字节不进 copy-mode
tmux if-shell -F -t test '#{mouse_any_flag}' \
  "send-keys -H -t test '1b5b4d602121'" \
  "copy-mode -e -t test"
# pane_in_mode=0 ✓  (未进 copy-mode，字节注入走正确分支)
```

**结论**：`if-shell -F '#{mouse_any_flag}' '<转发>' '<copy-mode>'` 两个分支均正确，
单条命令内完成判定+执行（见竞态分析节）。

### 实测 00：`send-keys -X cancel` 退出 copy-mode

```bash
# 先 copy-mode -e，pane_in_mode=1
tmux send-keys -X -t test cancel
# pane_in_mode=0 ✓  (已退出 copy-mode)
```

**结论**：`send-keys -X cancel` 可作为输入安全兜底（文字输入前自动脱困）。

---

### 实测 1：`#{mouse_any_flag}` 可用性

```bash
# 裸 shell
tmux display-message -p -t test '#{mouse_any_flag}'
# 输出：0 ✓

# vim -c 'set mouse=a'（显式启用鼠标）
tmux display-message -p -t test '#{mouse_any_flag}'
# 输出：1 ✓

# 同时观察格式区分
tmux display-message -p -t test 'any=#{mouse_any_flag} std=#{mouse_standard_flag} sgr=#{mouse_sgr_flag}'
# vim with mouse=a → any=1 std=1 sgr=0
```

**结论**：`#{mouse_any_flag}` 在 tmux 3.6a 存在，语义正确（TUI 开鼠标则 1，裸 shell 则 0）。
`#{mouse_standard_flag}` 与 `#{mouse_sgr_flag}` 可进一步区分字节格式。

### 实测 2：`send -M` 不可用

```bash
tmux -S "$SOCK" send -M
# 输出：no mouse target（退出码非0）
```

**结论**：`send -M` 只在鼠标按键绑定的回调上下文（`bind -n WheelUpPane ...`）里有效，
服务端从 Go 的 `exec.Command` 调用时失败。**方案 (b) 不成立，不采用。**

### 实测 3：`send-keys -H` 可注入原始字节

```bash
# SGR 格式的滚轮上滚（\033[<64;40;12M）
tmux send-keys -t test -H '1b5b3c36343b34303b31324d'
# 退出码：0 ✓

# X10 格式的滚轮上滚（\033[M\x60\x21\x21，位置 1,1）
tmux send-keys -t test -H '1b5b4d602121'
# 退出码：0 ✓
```

**结论**：`send-keys -H` 是注入原始鼠标字节的可行路径，且不追加 Enter（区别于 `send-keys -l --`）。

### 实测 4：copy-mode 降级路径可用

```bash
# 进入 copy-mode（-e = 到底部自动退出）
tmux copy-mode -e -t test    # pane_in_mode → 1 ✓
tmux send-keys -X -t test scroll-up   # scroll_position → 1 ✓

# 再次 scroll-down 到底部时自动退出 copy-mode
tmux send-keys -X -t test scroll-down   # pane_in_mode → 0 ✓（-e 生效）
```

**结论**：`copy-mode -e` + `send-keys -X scroll-up/down` 完整可用。
`-e` 标志保证了用户滚到底部时自动回到普通输入状态，无需 App 主动退出。

---

## Q1：走哪条通道（推荐：新增 wire kind）

### 候选对比

| 候选 | 描述 | 代价 |
|---|---|---|
| **复用 `Input.Text`** 发原始字节 | 把 `\033[<64;1;1M` 作为 Text 传入 | 致命缺陷：`Inject()` 追加 Enter，会触发 shell 命令行；`SendKeys` 只接受闭集 key name |
| **复用 `Input.Keys`** | 把 scroll 加入 `namedKeys` 闭集 | `SendKeys` 走 `send-keys --` 路径，不支持 raw bytes；且 mouse-tracking / copy-mode 分支判断必须在服务端，不适合放进 key name |
| **新增 `TypeScrollWheel`** | 新帧类型，服务端独立处理 | 协议版本号语义变化（加帧不破坏旧客户端，旧客户端收到未知 type 按协议返回 error frame）；服务端改动局限于 `ws_handler.go` + `bridge.go` |

**推荐：新增 `TypeScrollWheel`**。理由：
1. `Inject` 的 "inject then Enter" 合约是协议红线（requirement 003），不能绕过
2. 服务端需要做 mouse-tracking 状态判定 + 字节格式选择 + copy-mode 降级，这些逻辑放在帧类型层而不是在 Text 内容里编码，职责清晰
3. 此工程无外部发布版本，协议加帧代价为零

### 新帧定义（草案）

```go
// ScrollWheel 向远端 pane 投送一次滚轮事件（C→S）。
// Delta < 0 表示向上滚（朝历史方向），> 0 表示向下滚。
// |Delta| 表示一次手势的"档位数"（通常为 1–5）；服务端按档位数重复动作。
//
// 回执策略：**成功不回 ack**（屏幕内容变化即是反馈；加往返会让滑动变粘）；
// 失败（pane 消失 / tmux 命令失败）发 TypeError 帧。
//
// @contract
// @pre Ref 非空、Delta != 0（无 ReqID，成功不回执）
// @post 成功：tmux 命令已执行，无 ack 帧；失败：TypeError 帧
// @err Validate 对空 Ref、Delta 0 返回 ErrInvalidField
type ScrollWheel struct {
    Ref   string `json:"ref"`
    Delta int32  `json:"delta"` // <0=up, >0=down
}

// PaneModeChanged 通知客户端 pane 进入/退出 copy-mode（S→C）。
// App 收到后显示/隐藏最小指示（一行字或角标），防止用户在 copy-mode 里
// 输入被 tmux 吃掉而不知情。
//
// @contract
// @post 客户端更新 UI 指示；无需 ack
type PaneModeChanged struct {
    Ref        string `json:"ref"`
    InCopyMode bool   `json:"in_copy_mode"`
}
```

新增 FrameType 常量：
```go
TypeScrollWheel    FrameType = "scroll_wheel"
TypePaneModeChanged FrameType = "pane_mode_changed"
```

**无 TypeScrollWheelAck**（leader 裁定：成功不发 ack，失败复用 TypeError）。

---

## Q2：谁判断 mouse-tracking 模式，状态从哪读

**结论：由服务端判断，通过 `tmux display-message -p '#{mouse_any_flag}'` 实时查询。**

### 为什么不走客户端追踪

- 客户端不看 ANSI 字节流（protocol 把 raw bytes 隐藏在 binary 帧里，客户端只得渲染后的格子），无法解析 DECSET 序列
- 客户端若追踪，需要额外往返通知服务端当前状态，增加一个协议往返

### 为什么不在输出流里解析 DECSET

- 服务端 `stream.go` 的 `pipe-pane` FIFO 中有所有原始字节
- 但在那里解析 DECSET 1000/1002/1003/1006 需要嵌入一个 VT 状态机，工程量大且与 tmux 内置的解析产生重复
- tmux 自己已经维护这个状态，通过格式变量暴露，直接复用即可

### 判定流程（服务端，每次 handleScrollWheel）— 已更新为原子版

**不用两步查询（先 display-message、再 send-keys -H）**，改用单条 `if-shell -F`
将判定与动作合并在同一次 tmux 命令派发内（见竞态分析节）：

```
tmux if-shell -F -t <pane> '#{mouse_any_flag}' \
  'send-keys -H -t <pane> <scroll_bytes>' \
  'copy-mode -e -t <pane> ; send-keys -X -t <pane> scroll-up/down'
```

字节格式（实现阶段按 `#{mouse_sgr_flag}` 再分支）：
- `mouse_sgr_flag=1` → SGR：`\033[<64;1;1M`（up）/ `\033[<65;1;1M`（down）
- `mouse_sgr_flag=0` → X10：`\033[M\x60\x21\x21`（up）/ `\033[M\x61\x21\x21`（down）

**字节格式选择可在 Go 侧先查一次 `mouse_sgr_flag`（格式不会在一次手势内变）**，
然后把对应的 hex 字符串拼入 if-shell 命令。

### 为什么用 `mouse_any_flag` 而不是 `pane_in_mode`

`pane_in_mode` 只告知 tmux copy-mode 是否激活，不告知应用是否启用鼠标追踪。
`mouse_any_flag` 直接反映应用是否通过 DECSET 1000/1002/1003 开启了鼠标上报。
两者含义不同，需要分开处理（见分层责任表）。

### 格式选择细节

| 标志 | 格式 | 滚轮上滚字节（位置 1,1） |
|---|---|---|
| `mouse_sgr_flag=1` | SGR (DECSET 1006) | `\033[<64;1;1M` |
| `mouse_standard_flag=1` (非 SGR) | X10 (DECSET 1000) | `\033[M\x60\x21\x21` |

> **说明**：如果同时检测到 sgr_flag=1，优先用 SGR（无坐标上限，普适性更好）。
> Claude Code 等 Node.js TUI 通常会启用 SGR 模式（实测待补充，需在真机上确认）。

---

### copy-mode 模式通知与输入安全兜底（leader 裁定，必须做）

**问题**：进入 copy-mode 后用户打字，按键被 tmux 吃掉（走 copy-mode 命令）而不是进 shell，
用户看到「我在敲命令但什么都没发生」。这比看不到历史严重。

**两层保护**（均必须实现，不能只做其中一层）：

**层 1 — 服务端推模式通知帧**

服务端主动 enter copy-mode 时，推 `TypePaneModeChanged{Ref, InCopyMode: true}`；
退出时（handleInput 兜底 / -e 自动退出后的下一次 handleScrollWheel 检测到 `pane_in_mode=0`）
推 `TypePaneModeChanged{Ref, InCopyMode: false}`。

App 收到后显示/隐藏一个最小指示（角标、状态行一行字即可）。

**层 2 — handleInput 前的兜底检查（安全保障，就算 App 指示出了差错也能脱困）**

```go
// handleInput 的安全前置（新增）
if paneInMode(ctx, br) {
    // send-keys -X cancel 退出 copy-mode（已实测：pane_in_mode 1→0 ✓）
    runTmux(ctx, socket, timeout, "send-keys", "-X", "-t", target, "cancel")
    // 推模式通知：InCopyMode=false
    c.send(&protocol.PaneModeChanged{Ref: i.Ref, InCopyMode: false})
}
// 然后正常 Inject / SendKeys
```

`paneInMode` 实现：`tmux display-message -p '#{pane_in_mode}'`，返回 "1" 则真。

**为什么两层都要做**：
- 层 1 让 App 视觉可见（用户知道自己在 copy-mode）
- 层 2 是安全兜底（App 指示 delay、用户没注意、或手动 q 退出后服务端状态未及时同步 —— 任何情况下打字都能脱困）

---

## Q3：非 TUI 场景怎么降级

**推荐：`copy-mode -e` + `send-keys -X scroll-up`**（已实测）

### 判据（可测）

```
mouse_any_flag=0  AND  pane_in_mode=0  →  入 copy-mode 再滚
pane_in_mode=1                          →  直接发 send-keys -X scroll-up/down
                                            （已经在 copy-mode，不再 copy-mode 一次）
mouse_any_flag=1                         →  注入鼠标字节，不进 copy-mode
```

这三个条件都可以通过 `tmux display-message` 读取并在单元测试里 mock，**不需要靠猜**。

### 降级行为对用户的含义

- 用户在裸 shell 上滑 → 进入 tmux copy-mode，可以看历史 ✓
- copy-mode 里上滑到底部 → `-e` 自动退出，恢复输入 ✓
- 用户在 Claude Code / vim 等 TUI 里上滑 → 等价于鼠标滚轮，TUI 自己处理 ✓

### 不处理的边缘情况（显式说明不是漏掉）

- **copy-mode 里键盘操作冲突**：用户在 copy-mode 时打字会被 tmux 拦截为 copy-mode 命令。
  这是 tmux copy-mode 的固有行为，本方案不改变它，接受这个 UX 缺陷作为初版。
- **多窗格场景**：本协议只处理一个 Ref 指定的 pane，不涉及跨 pane。

---

## Q4：手势语义

### 一次上滑 = 几行

**推荐：每次 `onScroll` 回调（Android GestureDetector）发一个 `ScrollWheel` 帧，`delta = -(lines)` 其中 lines ≈ 3**

- 当前 `TermSurfaceView.onScroll` 已把 `distanceY` 转成 `deltaLines`（见 `TermSurfaceView.kt:99–102`）
- 直接复用这个映射：`delta = -deltaLines`（GestureDetector 给的 deltaLines 已是正整数表示向上的行数）
- 服务端收到 `|delta|` 档位后，**对 mouse tracking 路径**：发 `|delta|` 次鼠标滚轮事件；
  **对 copy-mode 路径**：发一次 `send-keys -X scroll-up`（tmux 默认 3 行/次，足够）

### 惯性滑动（fling）

目前 `TermSurfaceView` 没有 `onFling` 处理。建议：
- 初版不实现惯性：fling 仅触发多次 `onScroll` 事件（Android 系统行为），自然产生连续 `ScrollWheel` 帧
- App 侧可加节流（比如 50ms 内合并多帧为一帧），但这是 App 实现细节，不影响协议

### 与本地缓冲滚动的共存

当前行为：`onScrollBy` → 本地 buffer 滚动（`presenter.onScrollBy`）。

**推荐分层**：
- 订阅中（subscribed = true）且 delta != 0 → **发 ScrollWheel 到服务端**；同时**阻止本地 buffer 滚动**
  - 理由：TUI 场景下本地 buffer 只有一屏内容，本地滚无用；发到远端才能看历史
  - 非 TUI 场景下服务端会进 copy-mode，deltas 会回来更新屏幕
- 未订阅 → 保持现有本地 buffer 滚动（不影响非会话页）

实现位置：在 `TermSurfaceView.onScroll` 的回调处，由 `SessionViewModel` 判断当前是否订阅，
若是则发 ScrollWheel 事件并返回 `true`（consumed），不再走 `presenter.onScrollBy`。

---

## 竞态分析：`#{mouse_any_flag}` 值在查完后立刻变

### 问题描述

若用两条命令：
1. `tmux display-message -p '#{mouse_any_flag}'` → Go 拿到 "1"
2. 之间 tmux 处理来自 pane 的 `\033[?1000l`（应用关闭鼠标追踪）
3. `tmux send-keys -H '1b5b4d602121'` → **原始字节打进已经变成裸 shell 的命令行**

这正是「鼠标字节污染命令行」风险，后果严重。

### `if-shell -F` 为何能大幅收窄窗口（实测确认）

```bash
# 实测：两个分支均在单次 if-shell 命令内正确完成（2026-08-14 隔离实测）
tmux if-shell -F -t test '#{mouse_any_flag}' \
  "send-keys -H -t test '1b5b4d602121'" \
  "copy-mode -e -t test"
# bare shell → pane_in_mode=1 ✓
# vim+mouse  → pane_in_mode=0，字节注入 ✓
```

**tmux 是单线程事件循环**。`if-shell -F '#{flag}'` 中 `-F` 表示「把条件当 format 求值，
不起 shell 子进程」，整个评估+分支选择+命令执行在 **tmux 同一次命令派发**中完成。
来自 pane 的 pty 字节（`\033[?1000l`）只能在 tmux 返回事件循环后才被处理。
因此条件与动作之间不存在事件循环间隙——竞态窗口从「两次 IPC 之间的任意时间」
缩窄到「tmux 单次调度内部的操作序列」（对外部信号近似原子）。

### 残余风险与接受理由

`if-shell` 不是 OS 级原子操作。极端情况：tmux 在执行 `send-keys -H` 的 tmux 命令解析阶段
触发信号（很理论化）仍可能有间隙。但：
1. 这个窗口比两次 IPC 小 2–3 个数量级
2. 真正的 OS 级原子在现有 tmux 接口中不可能实现
3. **这个残余风险被 handleInput 的 `pane_in_mode` 兜底覆盖**：就算字节已进入 shell，
   用户下一次打字时兜底会 `send-keys -X cancel` 恢复，损失至多是一次乱码字符

### 结论

推荐方案：使用 `if-shell -F '#{mouse_any_flag}' '<注入字节>' '<copy-mode>'`，
**不做两次分开的 query+action**。这是在现有 tmux 接口下能达到的最小竞态窗口。

---

## Q5：不倒退红线

以下行为**绝对不能**被新链路改变：

| 红线 | 验证方式 |
|---|---|
| 文本输入（Input.Text / Input.Keys）零副作用 | 现有 InputAck 红测保持绿；新代码不触碰 handleInput 核心路径 |
| 滚轮字节不追加 Enter | `send-keys -H` 独立于 `Inject`；新增 `InjectScroll` bridge 方法，不调用 Inject |
| **mouse_any_flag=0 时零原始字节进 pane** | 红测：裸 shell 发 ScrollWheel，验证无字节注入，只有 copy-mode 动作（pane_in_mode=1）|
| **copy-mode 中打字能自动脱困** | 红测：进 copy-mode → 发 TypeInput → 验证 pane_in_mode 变 0 且字符进入 shell |
| **copy-mode 模式变更推通知帧** | 红测：进/出 copy-mode 各触发一条 TypePaneModeChanged 帧 |
| 已有 Scrollback 分页（requirement 006）不受影响 | handleScrollback 路径不改，现有红测保持绿 |
| 协议旧帧不变（auth/input/resize/scrollback） | frametype.go / frames.go 只增不改，validate 规则不变 |
| 生产 daemon 不重启 | 本轮不部署；实现阶段才改 server/ |
| App 旋转/重连后状态恢复 | ScrollWheel 无状态；重连后服务端重新查 flag，无旧状态泄漏 |

---

## 分层责任表

| 层 | 组件 | 职责 |
|---|---|---|
| **手势层** | `TermSurfaceView.onScroll` (Android) | 将 `distanceY` 转成 `deltaLines`，构造 `ScrollWheel` 帧；订阅中时不再调 `presenter.onScrollBy` |
| **事件层** | `SessionViewModel` (Android) | 发送 `ScrollWheel` 帧；持有 subscribed 状态供手势层查询 |
| **协议层** | `protocol/frames.go`, `frametype.go` | 定义 `ScrollWheel` / `ScrollWheelAck` 帧及 Validate |
| **服务端 handler** | `api/ws_handler.go: handleScrollWheel` | 查询 tmux 格式变量；按 mouse_any_flag 选分支；返回 `ScrollWheelAck` |
| **服务端 bridge** | `bridge/bridge.go: InjectScroll` | 新方法：`send-keys -H` 注入字节 OR `copy-mode -e` + `send-keys -X scroll-up/down` |
| **tmux** | 隔离 socket（生产用 bridge 的 socket） | 维护 mouse-tracking 状态机；响应 copy-mode 命令；转发鼠标字节给运行的应用 |

---

## 红测草案

### T1：mouse_any_flag=0 路径（裸 shell → copy-mode）

```go
func TestScrollWheelFallbackCopyMode(t *testing.T) {
    // 隔离 tmux，裸 sh，mouse_any_flag=0
    pane := newTestPane(t, "sh") // helper: 起隔离 socket，返回 Pane
    
    err := pane.InjectScroll(ctx, -3) // delta=-3，向上3档
    require.NoError(t, err)
    
    // 验证进入 copy-mode
    inMode := queryFlag(t, pane, "#{pane_in_mode}")
    assert.Equal(t, "1", inMode, "should enter copy-mode for non-mouse-tracking pane")
    
    // 验证零原始字节进 pane 输入 —— 检查 pane 的输入缓冲未收到字节序列
    // （通过观察 sh 命令行未乱码）
}
```

### T2：mouse_any_flag=1 路径（vim+mouse → SGR/X10 字节注入）

```go
func TestScrollWheelInjectsMouseBytes(t *testing.T) {
    // 隔离 tmux，vim -c 'set mouse=a'，mouse_any_flag=1
    pane := newTestPane(t, "vim -c 'set mouse=a' /dev/null")
    
    err := pane.InjectScroll(ctx, -1) // 向上1档
    require.NoError(t, err)
    
    // 验证未进入 copy-mode
    inMode := queryFlag(t, pane, "#{pane_in_mode}")
    assert.Equal(t, "0", inMode, "should NOT enter copy-mode when mouse tracking active")
    
    // 验证字节格式正确（mock send-keys -H 拦截，检查 hex 内容）
    // mock 实现见 bridge_test.go 的 mockTmux 方式
}
```

### T3：delta=0 → 协议错误（Validate 层拒绝）

```go
func TestScrollWheelDeltaZeroInvalid(t *testing.T) {
    frame := protocol.ScrollWheel{ReqID: 1, Ref: "ref1", Delta: 0}
    err := frame.Validate()
    require.Error(t, err)
    assert.ErrorIs(t, err, protocol.ErrInvalidField)
}
```

### T4：字节格式 · SGR scroll-up

```go
func TestScrollByteSGRUp(t *testing.T) {
    b := scrollMouseBytes(1, 1, -1, true /*sgr*/)
    // \033[<64;1;1M
    assert.Equal(t, "\x1b[<64;1;1M", string(b))
}
```

### T5：字节格式 · X10 scroll-up

```go
func TestScrollByteX10Up(t *testing.T) {
    b := scrollMouseBytes(1, 1, -1, false /*x10*/)
    // \033[M\x60\x21\x21  (button=64, col=1+32, row=1+32)
    assert.Equal(t, "\x1b[M\x60\x21\x21", string(b))
}
```

### T6：copy-mode 中打字自动脱困（leader 裁定 Q2 兜底）

```go
func TestInputEscapesCopyModeBeforeInject(t *testing.T) {
    pane := newTestPane(t, "sh")
    
    // 强制进入 copy-mode
    runTmux(t, pane, "copy-mode", "-e", "-t", pane.Target())
    require.Equal(t, "1", queryFlag(t, pane, "#{pane_in_mode}"))
    
    // 发 TypeInput → handleInput 应先 cancel 再 inject
    // 模拟：直接调 bridge 层的 Inject（含前置 cancel）
    err := pane.InjectWithCopyModeEscape(ctx, "hello")
    require.NoError(t, err)
    
    // 验证：已退出 copy-mode
    assert.Equal(t, "0", queryFlag(t, pane, "#{pane_in_mode}"))
}
```

### T7：进/出 copy-mode 各推一条 TypePaneModeChanged

```go
func TestPaneModeChangedNotification(t *testing.T) {
    // 验证 handleScrollWheel（mouse_any_flag=0）推 PaneModeChanged{true}
    // 验证 handleInput 前兜底推 PaneModeChanged{false}
    // 具体实现依赖 ws_handler 的测试桩，实现阶段补全
}
```

---

## 已知局限（实现阶段补录，待后续任务处理）

| 局限 | 影响 | 计划处置 |
|---|---|---|
| **SGR/X10 坐标写死 `1;1`** | 对 neovim 分屏、自实现坐标路由的多面板 TUI，滚轮会被路由到左上角 split 而非用户当前窗格。主流 TUI（vim 单窗、less、htop、claude-code）只看 button code（64/65），不受影响。用户核心痛点是「零投送」，1;1 先解决从 0 到 1。 | App 侧上报手势起点坐标后，服务端改用实际坐标替换 1;1，留作后续 |
| **`sendError` 正常路径不打服务端日志** | 客户端能看到 `ErrorFrame`，但服务端侧对旧客户端发未知类型、死亡 pane 等失败路径是「瞎的」，无可观测指标。与「失败可见」红线有张力：客户端侧可见，服务端侧不可见。 | 等 `feat-diagnostic-log-export` 统一处理日志分层时一并解决，届时 `sendError` 可按错误等级决定是否写结构化日志 |
| **远端投送引入至少一个 RTT 的延迟** | 改前：上滑→本地缓冲立刻动，零延迟。改后：手势→发帧→服务端查 tmux→注入→远端重绘→mirror delta 回来→屏幕才动，至少一个完整往返（当前链路约 123ms，广州 DERP 节点）。用户对滑动跟手极其敏感，体感上比本地缓冲钝。**为什么不做乐观本地滚动**：乐观滚动（先滚本地，再等远端权威结果）会导致双重滚动（本地滚一次、远端 mirror delta 回来又滚一次），体验更差；且 copy-mode 路径下「本地在本地缓冲，远端在 copy-mode 里」两套坐标系无法对齐。接受单向延迟。 | 如用户真机试用后觉得体感太钝，考虑：① 降低 DERP 延迟（接入更近节点）；② 换 Tailscale 直连；③ 专项评估乐观滚动代价。到时要有 RTT 数据，不能靠感觉猜 |

---

## leader 裁定记录（2026-08-14，已全部决定）

| 事项 | 裁定 |
|---|---|
| ScrollWheelAck | **成功不发 ack**；失败发 TypeError 帧（屏幕内容变化即是反馈，加往返会让滑动变粘） |
| copy-mode 提示 | **必须做**：`TypePaneModeChanged` 帧 + `handleInput` 前 `send-keys -X cancel` 兜底（见 Q2 节） |
| delta 语义 | **档位数**（服务端决定每档动作次数，App 不感知远端 TUI 滚速） |
| 惯性节流 | **App 层，协议不涉及**（50ms 节流建议，实现阶段决定） |
| 竞态处理 | **使用 `if-shell -F`** 而非两步 query+action（已实测，见竞态分析节） |

**等待用户点头后进入实现阶段。**
