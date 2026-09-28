# 缺陷④ 回炉第 7 轮 · 根因探针报告

**结论：leader 的架构性嫌疑 —— 命中。copy-mode 滚动确实不经过 pipe-pane，客户端唯一
画面来源在滚动时收到 0 字节。这就是用户第三次报"上滑失败"的根因，且当前生产代码
（pid 81134 正在跑的那份实现）就带着这个缺陷。**

## 探针打在哪

`server/internal/bridge/pipepane_scroll_probe_test.go`
（`//go:build scrollprobe`，不进默认测试集，`go test ./...` 看不到它）。

判据只有一个：**产品自己的 `Pane.Subscribe`（`pipe-pane -o` → FIFO）通道收到多少字节**。
`capture-pane` 只在报告里打印，从不参与 pass/fail —— 这正是前两轮翻车的通道，本轮明确
排除在判据之外。

流程：

1. 隔离 tmux server（`newTestTMUX`，独立 socket，产品/用户 tmux 零接触）起一个跑
   `seq 1 400; exec sh` 的 pane。
2. `Subscribe` 拿到 FIFO 字节流，drain 到静默，记 baseline（证明 FIFO 接线本身是通的，
   不是 0 字节假阳性）。
3. 执行 `InjectScroll` 内部实际发的那两条 tmux 命令：`copy-mode -e` 、
   `send-keys -X -N 10 scroll-up`。
4. 再 drain 一次 Subscribe 通道，记 post-scroll 字节数。
5. 旁证：滚动前后 `capture-pane -p` 是否变化、`#{pane_in_mode}` 是否为 1（证明 tmux 侧
   确实进了 copy-mode 且滚了）——只打印，不判据。

## 回退后跑的实际输出（原样贴）

在隔离 worktree 里回退 `1511b50c7` / `be214a375` / `67b06f4f8`（`git revert`，三个提交
干净应用，`go build ./...` 通过，`grep InjectScroll` 确认符号已不存在）后运行：

```
=== RUN   TestPipePaneReceivesZeroBytesOnCopyModeScroll
    pipepane_scroll_probe_test.go:79: baseline: 8 bytes in 1 chunks (seq 1..400 draining through pipe-pane)
    pipepane_scroll_probe_test.go:103: post-scroll (product Subscribe channel): 0 bytes in 0 chunks
    pipepane_scroll_probe_test.go:104: pane_in_mode after scroll: 1
         (tmux confirms copy-mode was entered)
    pipepane_scroll_probe_test.go:105: capture-pane changed (corroborating only, NOT the verdict): false
PROBE HIT: product Subscribe channel received 0 bytes for a copy-mode scroll that tmux itself confirms happened (pane_in_mode=1
, capture-pane changed=false)
--- PASS: TestPipePaneReceivesZeroBytesOnCopyModeScroll (1.09s)
PASS
ok  	github.com/agentmirror/agentmirror/internal/bridge	1.612s
```

跑了 3 次（含 `-count=1` 强制重跑），结果稳定一致：post-scroll 恒为 0 字节 0 chunk，
`pane_in_mode` 恒为 1（tmux 确认真的进了 copy-mode 且真的滚了）。

**额外验证（不在回炉流程要求内，但信息量大）**：同一探针在**当前主线 HEAD**（未回退，
`InjectScroll` 就是现在生产在跑的那份代码）上跑，结果完全一致 —— 0 字节 0 chunk。
探针本身只调用裸 tmux 命令（`copy-mode -e` + `send-keys -X scroll-up`），不经过
`InjectScroll` 这个 Go 符号是否存在，所以这个事实与"改没改"无关，是 tmux 本身的机制：
**copy-mode 是 tmux client 端换视角渲染，从不写回 pane 的 pty**，`pipe-pane` 只镜像
pane 程序自己写到 pty 的字节，两者本就是不相交的两条通路。

## 命中还是不命中

**命中。** leader 的推断成立：

- `Subscribe`＝客户端画面的唯一来源（`stream.go` 契约 `@inv none — 纯镜像，只读 pane
  输出流` 属实，它确实只读 pane 输出，不读 tmux 的 copy-mode 渲染层）。
- `InjectScroll` 的 `copy-mode -e` + `send-keys -X scroll-up -N n` 让 tmux **自己**
  确认滚动发生了（`pane_in_mode=1`），但这个滚动只改变了 tmux 对该 pane 的**渲染视角**，
  从未有一个字节流回 pane 的 pty。
- 结果：`pipe-pane` 的 FIFO 在滚动动作前后是静默的，客户端一个字节都收不到 ⇒ 用户手机
  屏幕原地不动 ⇒ "上滑失败"。

## 结论 / 对下一轮的含义

当前"copy-mode 路线"（`1511b50c7`→`be214a375`→`67b06f4f8` 三轮迭代的落点）在**架构层面
不可能满足验收标准**："用户在 Claude Code 会话里上滑，能看到该 TUI 自己的上文"——因为
`pipe-pane` 根本看不见 copy-mode 里显示的历史。这不是一个可以靠调参数（scroll 行数、
延迟、字节格式）修好的 bug，是通道选错了：

- copy-mode 滚出来的内容只活在 tmux 的渲染状态里，要让客户端看到，必须**主动查询**这个
  状态（如 `capture-pane -e` 抓 copy-mode 视口内容）并**推给客户端**，而不能指望
  `pipe-pane` 被动镜像出来。
- 也就是说：`InjectScroll` 触发滚动之后，还缺一步「把 copy-mode 视口内容读出来送给
  客户端」，这一步在三轮实现里完全没有——服务端只发了 `TypePaneModeChanged`（进没进
  copy-mode 的状态帧），从未发过 copy-mode 视口的画面帧。

具体怎么补这一步（新增一个基于 `capture-pane -e -t <target>` 的画面帧、走什么协议类型、
频率如何）不在本探针职责内——那是契约层的决定，回炉流程第 4 步交给 leader 定夺后由开发
席落地。**本探针只回答"根因是不是这个"，答案是：是。**

## 回炉流程状态

1. ✅ 隔离 worktree 回退三个提交
2. ✅ 从回退 diff 反推根因，产出探针
3. ✅ 回退后跑探针 → **命中**
4. ⏳ 等修复落地后重跑本探针，预期从"HIT"翻转为"不再命中"（即 post-scroll 字节数 >0
   且反映真实滚动后的画面）——这一步不归我，留给开发席修完后验收用。
