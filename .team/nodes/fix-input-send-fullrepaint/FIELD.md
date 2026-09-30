# 现场基 · fix-input-send-fullrepaint（发消息后整屏重刷）

## 用户报告（第 4 次，历史上从未真正解决）

> 「我给你发了消息有概率，**整个消息从上往下刷新，一行一行的刷新**，
>  然后底部的那些信息它就没刷新，导致我**看不到底部最新的消息**。」

## ⭐ 决定性差分（用户自带的对照组，本任务最重要的线索）

> 「并且这个问题**永远只出现在我给你发消息的时候**。
>  然后你的 teammate 给你发消息时候，**本质上和我发消息是一回事，但是他给你发消息就没有这个问题**，
>  所以说**这个问题是可以根除的**。」

两条路径产生的终端内容变化本质相同：

| 来源 | 现象 |
|---|---|
| 用户从 **App 发送 input** | 整屏自上而下逐行重刷，底部最新消息看不到 |
| teammate 从**其他进程**写入同一 tmux 会话 | **正常增量刷新，无此问题** |

**这意味着渲染层有能力正确增量绘制。** 问题不在「怎么画」，
而在「App 发送 input」这条路径上**额外做了什么**。

**排查必须从发送路径入手**，而不是从渲染层入手：
`SessionViewModel` 的发送流程 → `ConnectionManager.input` → 回显 delta 的处理
→ 是否顺带触发了 resize / 重新订阅 / 快照重放 / 强制滚到底 / 整帧失效。

**在解释清楚「为何只有自己发消息才触发」之前，不要动渲染层。**
否则就是又一次治标——本缺陷已被这样修过四轮。

## 调研已给的根治方向（docs/oss-terminal-solutions.md）

- 内核**早已有行级脏区** `takeDirty`，但 `TermSurfaceView` 仍**整帧全窗口重绘**
  （`onDraw` + `frameCallback`）
- herdr 内嵌 **ghostty（MIT）** 的 `collect_dirty_patch` 逐行 patch 是直接模板
- 许可证：ghostty MIT，**可引用**

**但注意顺序**：整帧重绘是「放大器」，不是「触发器」。
teammate 写入时同样走整帧重绘却不出问题，说明还有一个只在发送路径上出现的触发条件。
先找触发器，再谈用脏行 patch 消除放大效应。

## 同族但独立的任务，不要混做

- `fix-cols-grid-convergence`：最右列被截（渲染/栅格）
- `fix-scrollback-history-d36`：向上滑看历史（buffer 累积）
- `fix-ime-no-resize`：输入框变高致 resize（已修复，待实测）
- 捏合闪烁 + 字号持久化：待立案（`raw/041` 预览+松手生效）

## 不得破坏

- **D-27 已修**：服务端 no-op resize skip
- **D-20 已修**：IME 弹起时末行可见
- **IME resize 抑制**（今晚锚点 738b503c3）：首帧后挤压不再 emit resize
- 强制回归门 `TermSurfaceSessionBindingRegressionTest`、`TermSurfacePinchGestureTest` 保持绿

## 收工门

红测必须**同时模拟两条路径**（本端发送后的回显 delta / 纯外部 delta），
断言两者产生的重绘范围一致且均为脏行级。
这个断言直接对应用户给出的对照组——**能通过它，就说明真的根除了，而不是碰巧不复现。**
