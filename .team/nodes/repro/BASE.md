# 知识基底 · ledger.hl1.v1 / t.repro（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.repro · 回炉①：模拟器亲眼复现「打开会话白屏」并定位引入 PR（契约 092 §1）

用户实测：从会话列表点开一个会话，页面空白，对话（终端）界面不出现。**回归**——上一版正常。

## 你要交付（全部进 .team/nodes/hl1-repro/复现.md + 截图）
1. **复现**：在你的 worktree（main 同源）`cd app && ./gradlew -q :app:assembleDebug` 装到模拟器，
   按下方隔离环境流程配对，点开你隔离造的会话，截图留证（白屏即复现成功）。
   ⚠️ 若 main 上复现不出：如实写「复现不出＋你试过的完整步骤」，报 status=norepro，⛔ 不许硬编。
2. **定位引入 commit**：本轮 land 了 14 条 PR（git log 里 `land pr/` 的 merge）。高嫌疑：
   e12-close / e13-new-pane / e1-resync / e2-composer / recon-foreground / geom-resume。
   先读这几条的 diff 缩小范围，再用 git bisect（在你 worktree 里 checkout 候选 commit → assembleDebug → 装 → 点开会话）钉死第一个坏 commit。
3. **初步根因分析**：坏 diff 里哪一处最可能导致白屏，写清推理与证据边界（哪步开始是推测）。
4. 复现.md 必含字段行：`引入commit=<sha>`、`复现步骤=`、`截图=`（相对路径列表）。

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

1. **建你自己的分支**：`git checkout -b （本格不建分支，只读定位）`。
2. **只提交到本分支**。⛔ 不许并线、⛔ 不许碰 main、⛔ 不许 `git stash apply` 别人的改动。
3. **⛔ 你不要 push。** PR 由 leader 代开。你的交付＝分支名 + commit sha + 说明.md。
4. **⛔ 判据红了不许改判据让它变绿。** 判据本身写错 ⇒ 报 blocked 并指出错在哪。
5. **先验红**：改之前把判据/探针跑一遍，把红的原始输出贴进说明.md。没有先验红 ⇒ 评审直接 refutes。
6. 代码注释符合外骨骼标准（看基底里的规范引用；archwiki 棘轮会验）。
7. 临时文件只写 `.team/nodes/hl1-repro/tmp/`，⛔ 不写 /tmp、不写任何项目外路径。
8. 交付物全落盘后才 report_result；如实报不可判是合法出口，⛔ 不许造假。

```

- write_paths: .team/nodes/hl1-repro/
- read_paths: requirement-base/entries/092-会话页白屏回归与两处简陋UI.md, e2e/layer2.sh
- 判据: A-repro-doc

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
