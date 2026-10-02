# 02 · 桌面端滚轮监控与裸字节输入透传契约（desktop_wheel_and_raw_input_v1）

> **状态**：**权威契约与跨端保护红线**。
> **适用范围**：`CorralNative`（桌面原生 macOS 客户端）与 `agentmirrord`（服务端）交互；明确滚轮事件的全链路监控、编码与注入规范。
> **背景与直接诱因**：服务端在 `feat/issue42-agent-task-notifications`（提交 `881ffa0d8`，部署 `deployment-9900-issue42-881f`）版本上线后，导致桌面端滚轮完全失效；在回退至黄金基线 `0b8e83a`（`deployment-9900-whoami-0b8e83a`）后滚轮即刻恢复。为防止后续服务端演进、重构与新功能开发再次引发同类回退，特制定本文档作为硬性边界。

---

## 1. 桌面端（CorralNative）滚轮监控与输入模型

桌面端在 macOS 原生 AppKit 架构下，终端滚轮的处理链路如下：

```
[用户物理鼠标滚轮滚动]
       │ (macOS 系统事件)
       ▼
[AppKit: CorralNativeTerminalView.scrollWheel(with: NSEvent)]
       │ (SwiftTerm 内部判断)
       ├─► ① 处于普通主缓冲区（Normal Buffer）且 reportsMouse == false：
       │     └─► SwiftTerm 本地处理历史缓冲区行滚动（不经过网络）
       │
       └─► ② 处于鼠标跟踪（Mouse Mode SGR 1006，如 pi / claude / vim）
           或全屏备用缓冲区（Alternate Screen Buffer，如 less / man）：
             │
             ├─► 生成 SGR 1006 滚轮序列: ESC[<64;col;rowM (上滚) / ESC[<65;col;rowM (下滚)
             │   或生成方向键序列: ESC[A (上) / ESC[B (下)
             │
             ▼
[CorralApplicationCoordinator.send(source:data:)]
       │ (16ms 窗口防抖合并，最多合批 64 个滚轮报告，防止洪峰淹没网络)
       ▼
[WebSocket JSON V1 数据帧]
       │ {"type": "input", "payload": {"ref": "<ref>", "bytes": "<Base64 encoded SGR/ESC bytes>"}}
       ▼
[服务端 agentmirrord]
```

### 关键客户端事实与约束
1. **悬停即滚（Hover-to-Scroll）**：
   在 macOS 交互体系中，鼠标光标悬停在任何可见终端视口上滚动滚轮，AppKit 会直接将 `scrollWheel` 事件分发至光标下的 View。**滚轮滚动绝不会改变全局 `firstResponder`（焦点）**。客户端已在 `PR 9 (85232e7)` 落实悬停滚轮放行。
2. **两路滚轮协议的并存现状**：
   - **移动端（Android / Web）**：发送独立的 `{"type": "scroll_wheel", "payload": {"ref": "...", "delta": N}}` 协议帧，由服务端 `handleScrollWheel` $\to$ `InjectScroll` 自行决定走 copy-mode 还是 SGR；
   - **原生桌面端（CorralNative）**：由 SwiftTerm 直接生成标准的 **SGR 1006 滚轮字节（如 `\x1b[<64;...;...M`）或方向键字节**，通过标准的 `{"type": "input", "payload": {"bytes": "..."}}` 原始字节通道上行。

---

## 2. 服务端回退根因深度复盘（881ffa0d8 破坏点剖析）

经比对黄金基线 `0b8e83a` 与故障版本 `881ffa0d8`，服务端直接导致桌面滚轮失效的核心改动如下：

### 2.1 致命死因：丢弃 `InjectRawAtomic`，导致转义序列被碎割拆分

在黄金基线 `0b8e83a`（`internal/api/ws_handler.go:365-373`）：
```go
injectRaw := br.InjectRaw
// Escape-prefixed VT/SGR packets must stay in one PTY write; otherwise
// an interactive CLI can consume the lone ESC as a standalone key.
if bytes.IndexByte(i.Bytes, 0x1b) >= 0 {
    injectRaw = br.InjectRawAtomic
}
if err := injectRaw(c.ctx, i.Bytes); err != nil { ... }
```
其中 `InjectRawAtomic`（`internal/bridge/inject_raw.go:16-32`）：
```go
// 将整串字节通过一次性的 `tmux send-keys -H` 整体原子注入
args = append(args, "send-keys", "-t", p.target, "-H", "--")
for _, b := range raw {
    args = append(args, fmt.Sprintf("%02x", b))
}
```

而在 `881ffa0d8` 中：
**`InjectRawAtomic` 被彻底删除，所有带有转义字符的原始输入被强行退化回普通的 `InjectRaw`！**

普通的 `InjectRaw` 逻辑如下：
```go
// 按 isControlByte 切割输入，C0/DEL 走 -H，其余走 -l
if ctrl {
    runTmux("send-keys", "-t", p.target, "-H", "--", "1b") // ！！！第 1 次调用：单独发 ESC
} else {
    runTmux("send-keys", "-t", p.target, "-l", "--", "[<64;1;1M") // ！！！第 2 次调用：发后续文字
}
```

#### 破坏链条：
- 桌面端发出的滚轮序列是 `\x1b[<64;10;20M`；
- `0x1b` 被判定为 Control Byte（走 `send-keys -H 1b`）；
- `[` 及后续字符被判定为 Printable Run（走 `send-keys -l "[<64;10;20M"`）；
- **两次独立的 tmux 进程调用在时间线上被割裂**！终端内的 TUI（如 `pi` / `claude` / `vim`）首先收到一个**孤立的 ESC 键**，判定为“用户按下了退出键”；紧接着收到纯文本 `[<64;10;20M`！
- **整个滚轮转义序列被彻底肢解破坏，滚轮当场彻底失效！**

### 2.2 附加破坏：`sendScrollSnapshot` 在 `handleScrollWheel` 中被删除

在 `0b8e83a` 中，当执行 copy-mode 滚动后，服务端会主动调用 `sendScrollSnapshot` 将滚动后的屏幕快照立即下发给客户端；而在 `881ffa0d8` 中该方法被直接移除，导致依赖 copy-mode 滚动的场景失去了即时快照同步。

---

## 3. 服务端改进与演进硬性铁律（服务端开发必读守则）

服务端后续在进行架构重构、Issue #42 通知系统演进或输入优化时，**必须无条件遵守以下 4 条铁律**：

### 铁律 1：凡包含 `0x1b`（ESC）的输入，必须保证单次原子写入（MUST Inject Atomically）
- **禁止拆分**：严禁将包含 `0x1b` 的字节序列按可打印/控制字符拆成多次 `tmux send-keys` 调用；
- **强制原子化**：遇到带有 `0x1b` 的 `Input.Bytes`，**必须使用单次 `send-keys -H`（即 `InjectRawAtomic`）将整个转义序列原子送入 tmux PTY**！

### 铁律 2：双模态滚轮通道必须完全对等保障（MUST Support Both Modalities）
服务端必须同时稳定支持两种客户端输入模态，不得偏废任一：
1. **模态 A（桌面端原生模式）**：
   - 客户端通过 `{"type": "input", "payload": {"bytes": "..."}}` 传输裸 SGR-1006 / VT 字节；
   - 服务端必须通过 `InjectRawAtomic` 原样原子注入目标 pane。
2. **模态 B（移动端 / 抽象模式）**：
   - 客户端通过 `{"type": "scroll_wheel", "payload": {"ref": "...", "delta": N}}` 传输抽象滚动格数；
   - 服务端根据 `#{mouse_any_flag}` 判定：为 1 时注入 SGR 滚轮字节，为 0 时进入/维持 copy-mode 并滚动。

### 铁律 3：严禁在未经过真实桌面端联调的情况下上线新二进制
- 在每次向生产端口（9900 等）部署新构建前，必须在完全隔离的端口（如 9920）上，使用真实的 `CorralNative` 客户端进行回显与滚轮核验；
- 严禁仅凭单测覆盖或安卓端可用，就盲目推定桌面端功能未受损。

### 铁律 4：服务端 CI 必须常驻防碎割红测守卫
服务端测试套件中必须永久包含针对 SGR 滚轮注入原子性的测试用例：
- 测试断言：当服务端接收到 `\x1b[<64;1;1M` 时，`tmux` 调用的 argv **必须是一次包含全部十六进制字节的 `send-keys -H`**，严禁出现分段的 `-H` 与 `-l`！
