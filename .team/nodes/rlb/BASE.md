# 知识基底 · ledger.hl1.v1 / t.rlb（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.rlb · 回炉对拍二分：打开会话回归的引入 land（契约 092 §10，measurement-only）

用户纪律：回退作分析，⛔ 不在嫌疑代码上向前修。你不修任何东西，只回退与测量。

## 步骤
1. **基线真相**：git archive 4120c0884 双端（app assembleDebug + server go build），模拟器隔离环境
   （真 claude 夹具 + 大滚回夹具，量具照 hl1-verify2 复验.md），实测冷点开→字形秒数。
   预期显著快于 main；若基线同样慢 ⇒ 如实报（回归假设被证伪，写明），status=done + 结论=基线同慢。
2. **对照**：main 双端同测。
3. **二分**：窗口 4120c0884..1f47c099 的 land 序列（git log --first-parent --oneline 取 land 提交），
   app+server 成对同 commit 构建，按冷点开秒数二分，钉**第一个变慢的 land**。
4. **回退分析**：拿引入 land 的 diff 写根因分析（哪几行让进入路径变慢/变黑；与 open-latency-plan.md
   的 readLoop 排队机理对照——一致则互证，不一致以你的实测为准）。
## 交付 .team/nodes/hl1-rlb/说明.md
必含：status=done、基线秒数=、main秒数=、引入land=、回退diff分析=、每步计秒表。
## 模拟器与隔离环境（契约 092 §4，照抄，别自创）
- 模拟器 emulator-5554 已在跑（leader 起的，共享基础设施，⛔ 不要自己再起/杀模拟器）。
  adb = ~/Library/Android/sdk/platform-tools/adb。
- 隔离 tmux + 测试 daemon 照 e2e/layer2.sh 的做法；socket 必须自检落在自己 TMPD：
  `TMUX_TMPDIR=<你的tmp> tmux -f /dev/null list-sessions` 能看到自己的会话才算。
- 假 CLI：`ln -s /bin/bash <dir>/claude`（⛔ cp 出的 bash 跑不起来；shebang 脚本 comm=bash 不命中白名单）。
- app 冷启前 `pm clear dev.agentmirror.app`（模拟器里有指向已死端口的旧配对档案）；
  手填配对 ws://10.0.2.2:<你的端口>/ws + 你自己的 token。
- 🔴 测试 daemon 会扫到真实舰队的 tmux —— ⛔⛔ 绝不许在 app 里点开任何真实会话，
  只许点你隔离造出来的那个（cwd 是你自己的 tmp 目录）。
- 截图先关输入法：`adb shell input keyevent 111`。


---
纪律：全部构建在 .team/nodes/hl1-rlb/tmp/ 下的 git archive 导出树做，⛔ 不碰仓根工作区、不建分支、不 revert 仓上任何 commit；如实报不可判是合法出口。

```

- write_paths: .team/nodes/hl1-rlb/
- read_paths: .team/nodes/hl1-verify2/复验.md, .team/artifacts/open-latency-plan.md, requirement-base/entries/092-会话页白屏回归与两处简陋UI.md
- 判据: A-rlb-doc

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
