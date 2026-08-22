# 知识基底 · ledger.hl1.v1 / t.verify（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.verify · 回炉④：模拟器亲眼看到修复（眼见为实铁律）

在你的 worktree checkout 分支 pr/hl-blank，`cd app && ./gradlew -q :app:assembleDebug`，
装到模拟器，按 t.repro 的同一复现步骤重走：
1. 点开隔离会话 → **对话界面出现**（终端内容可见），截图留证。
2. 不倒退抽查：返回列表再进一次；后台→前台回来一次；各截图。
3. 交付 .team/nodes/hl1-verify/复验.md（必含 `结果=`、`截图=`、步骤逐条）。
⚠️ 复验失败（还是白屏）⇒ 如实报 status=stillblank + 截图，⛔ 不许含糊。

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
## 🔴 本轮流程：PR 链（一格一分支，判据过了才并线）

**开工第一件事，跑这两条自检，把输出贴进说明.md：**
```
pwd                        # 你必须在自己的 worktree 里，不是仓根
git branch --show-current
```

1. **建你自己的分支**：`git checkout -b （本格不建分支，只读复验）`。
2. **只提交到本分支**。⛔ 不许并线、⛔ 不许碰 main、⛔ 不许 `git stash apply` 别人的改动。
3. **⛔ 你不要 push。** PR 由 leader 代开。你的交付＝分支名 + commit sha + 说明.md。
4. **⛔ 判据红了不许改判据让它变绿。** 判据本身写错 ⇒ 报 blocked 并指出错在哪。
5. **先验红**：改之前把判据/探针跑一遍，把红的原始输出贴进说明.md。没有先验红 ⇒ 评审直接 refutes。
6. 代码注释符合外骨骼标准（看基底里的规范引用；archwiki 棘轮会验）。
7. 临时文件只写 `.team/nodes/hl1-verify/tmp/`，⛔ 不写 /tmp、不写任何项目外路径。
8. 交付物全落盘后才 report_result；如实报不可判是合法出口，⛔ 不许造假。

```

- write_paths: .team/nodes/hl1-verify/
- read_paths: .team/nodes/hl1-repro/复现.md
- 判据: A-verify-doc

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
