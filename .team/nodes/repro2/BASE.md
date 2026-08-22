# 知识基底 · ledger.hl1.v1 / t.repro2（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.repro2 · 高保真复现「打开会话白屏」（契约 092 §1 · 第二梯度）

第一轮 t.repro 已证：main 现编 **debug** 包 + 假 CLI（bash symlink，主屏）点开会话**正常**
（.team/nodes/hl1-repro/复现.md，norepro）。你的任务是把保真度补齐到用户路径，逐级尝试：

## 保真度阶梯（逐级做，每级截图留证，复现即停）
1. **用户那份 release 包**：`~/Desktop/agentmirror-20260821-1324.apk`（md5 e6cbe8d9e34769c4f3e3c58f2d069af4）
   直接安装（pm clear 后装），同隔离步骤点开会话。debug 复现不出而 release 复现出 ⇒ 立刻写明
   「构建类型相关（R8/minify 嫌疑）」。
2. **alt-screen 真内容**：假 CLI 升级——进入备用屏（printf '\033[?1049h'），持续重绘彩色 TUI、
   含 CJK 与长行（模拟 Claude Code 界面），再点开会话。
3. **真 claude CLI**：隔离 tmux 里直接起真的 `claude`（PATH 里有），alt=1 真实界面，再点开会话。
4. 每级都做三个动作：冷点开、退出重进、后台→前台。
⚠️ 你的隔离 daemon 会扫到真实舰队 —— ⛔⛔ 只许点你自己造的会话。

## 交付 .team/nodes/hl1-repro2/复现.md
- 必含：`status=done`、`复现=成功` 或 `复现=norepro`、`触发条件=`（哪一级/哪个动作触发）、
  `截图=`、每级的结果表。
- 复现成功后**才**做定位：先对触发条件做变量隔离（构建类型/alt-screen/真CLI 哪个是必要条件），
  再视情况 bisect 本轮 14 条 land（复现.md 里给 `引入commit=` 或 `引入commit=非本轮提交（构建类型相关）`）。
- 全梯走完仍不复现 ⇒ 如实写 `复现=norepro` + 完整证据，report status=blocked。
  判据会红——**这不是你的错**，leader 收链后去找用户要诊断日志。⛔ 不许硬编复现。
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
纪律：不建分支（只读复现）；临时文件只写 .team/nodes/hl1-repro2/tmp/；交付落盘后才 report_result。

```

- write_paths: .team/nodes/hl1-repro2/
- read_paths: .team/nodes/hl1-repro/复现.md, requirement-base/entries/092-会话页白屏回归与两处简陋UI.md
- 判据: A-repro2-doc

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
