# 知识基底 · ledger.hl1.v1 / t.sprobe2（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.sprobe2 · 字节级甄别：静态 alt-screen 的快照里到底有没有字形（measurement-only）

背景（三份材料先读）：
- t.verify 复验红（.team/nodes/hl1-verify/复验.md）：**装了 pr/srv-first-frame 修复的 daemon**，
  静态 alt-screen 冷点开仍 >16s 全黑；大滚回 2.7s 有字（只差 2s 门）。
- t.sprobe 机理（.team/nodes/hl1-sprobe/说明.md）：修复前 Resize 的 SIGWINCH 清屏 → 空快照（世界 B）。
- t.srvperf 对拍2（.team/nodes/hl1-srvperf/对拍2.md）：WS 层静态 alt-screen 63ms 收到 KindSnapshot 89B。

## 你要回答的唯一问题
**装了修复（分支 pr/srv-first-frame，已封版 f116dd8d1）的 daemon**，对「订阅前就冻结的静态 alt-screen pane」
发出的首个 KindSnapshot，其**字节里有没有该 TUI 的字形内容**（如 STATIC_ALT_MARKER 类标记字符）？
- **有字形** ⇒ 服务端已修好，白屏在 **app 渲染层**（app 收到 alt-screen 快照没画出来）——写清证据。
- **无字形（还是 CUP/清屏序列）** ⇒ 服务端修复不完整，写清快照原始字节（hexdump 前 200B）与
  你对 capture 路径的进一步机理推断（sfix 是先 snapshot 再 Resize 了，为什么还空？
  例如：pane 在**订阅之前**就已经历过一次 Resize/清屏？冻结方式的问题？逐项排除）。
用 e2e/repro2/srvperf 现成的 WS 脚本改造（auth→list→subscribe→dump 帧原始字节）。隔离 tmux+隔离端口，⛔ 不碰 9900。
静态 alt-screen 的造法照 t.verify 的 frozen-alt.sh（先冻结、订阅前 capture 自证含 marker）。
## 交付 .team/nodes/hl1-sprobe2/甄别.md
必含：status=done、世界=server仍空|app渲染层、快照hex=（前200B）、快照含marker=yes|no、依据=。
纪律：不改产品代码、不建分支；临时文件只写 .team/nodes/hl1-sprobe2/tmp/；如实报不可判是合法出口。

```

- write_paths: .team/nodes/hl1-sprobe2/
- read_paths: .team/nodes/hl1-verify/复验.md, .team/nodes/hl1-sprobe/说明.md, .team/nodes/hl1-srvperf/对拍2.md
- 判据: A-sprobe2-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：（未命中已知包，报 leader）
- 正向依赖（你消费的契约，只读）：无
- **反向依赖（波及面 = 回归自查范围）**：无

### 闭包架构卡内联

（无卡命中——报 leader，不要猜）

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
