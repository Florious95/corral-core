# 知识基底 · ledger.hl1.v1 / t.srvperf（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.srvperf · 服务端卡顿对拍：今天 13:35 的新二进制 vs 上午的旧构建（measurement-only，不改代码）

用户反馈：用**旧 app** 比今天上午卡顿，怀疑服务端回退。生产 daemon 13:35 换成了含今天三条 server land
（573cfa866 e12-close / 01467a88c e13-new-pane / e943e9fe1 gofix-scrubbedenv）的新二进制。
⚠️ 混杂变量：本机现在跑着模拟器+构建负载，上午没有。你的对拍必须把这个变量隔离掉（同机同负载下 A/B）。

## 做法（照 perf-v1 的真基线对拍方法论）
1. 基线：`git archive dc9aab11b`（今天三条 server land 之前）导出到 .team/nodes/hl1-srvperf/tmp/base/，`go build` 出 base 二进制。
2. 对照：当前 main `go build` 出 new 二进制。
3. 同负载对拍：各起在**隔离端口+隔离 TMUX_TMPDIR**（⛔ 不碰 9900 生产 daemon、⛔ 不碰真实舰队 tmux）；
   隔离 tmux 里造持续高频输出的会话（yes/彩色滚屏），用 ws 客户端脚本（参考 e2e/ 里现成的）订阅终端流，
   量化：帧到达间隔 p50/p95、每秒帧数、daemon CPU（ps -o pcpu 采样 60s）。A/B 各跑两轮取稳。
4. 结论三选一：new 明显差于 base（给数字）/ 两者相同（卡顿另有原因，给数字）/ 不可判（说明为何）。
## 交付 .team/nodes/hl1-srvperf/对拍.md
必含：`status=done`、`结论=`、`base指标=`、`new指标=`、`量具身份=`（两个二进制的 md5+构建 sha）。
纪律：不改产品代码、不建分支；临时文件只写 .team/nodes/hl1-srvperf/tmp/；如实报不可判是合法出口。

```

- write_paths: .team/nodes/hl1-srvperf/
- read_paths: e2e/
- 判据: A-srvperf-doc

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
