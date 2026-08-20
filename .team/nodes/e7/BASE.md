# 知识基底 · ledger.pr2.v1 / t.e7（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.e7 · E7 · 列表超一屏可滚 + 「下面还有」提示（契约 088）

方案 §3

🔴 **施工方案已定，⛔ 你不要自己另选**：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr1-idea-list/方案.md` 里对应小节写清了「改什么/外骨骼/判据」，**照它做**。
有异议报 `blocked` 说明，不要静默改方案。

---
## 🔴 流程（PR 链）
开工先跑并把输出贴进说明.md：`pwd` 与 `git branch --show-current`。
1. 建分支 `git checkout -b pr/e7-scroll-hint`，只改自己 worktree 里的文件。
2. ⛔ 不 commit、⛔ 不 push、⛔ 不并线 —— **封版由 leader 自动做**（判据 `A-e7-seal`
   在你报完后把改动提交到 `pr/e7-scroll-hint` 并断言分支非空）。⚠️ **报完别再改那棵 worktree**，改了就漂了。
3. ⛔ 不许写 `/tmp` 或任何项目外路径；临时文件写 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr2-e7/tmp/`（自己 mkdir -p）。
4. ⛔ 判据红了不许改判据让它变绿；判据本身写错 ⇒ 报 `blocked` 并指出错在哪。
5. **一次只改一个缺陷**，⛔ 顺手改相邻代码/重构/改格式一律禁止。

## 🔴 判据纪律
- 判据要断言「世界变了」；**先验红**（改之前跑，必须红）→ 改 → 验绿。
  **先验红的原始输出必须贴进说明.md**，⛔ 没有先验红的绿不算数。
- 断言「某物不应出现」必须先造出让它出现的条件，否则是恒真判据。
- 判据**查代码内容，⛔ 不查 commit 身份**。

## 🔴 两条常态判据：不许新增（⛔ 不是必须为 0）
main 上已有存量（app lint 16 条、archwiki T3 若干）。**不是你造成的，⛔ 不要去修** —— 修了 diff 就超范围。
两条判据都会**逐条点名新增了哪几条**（含文件行号）。⛔ 不许 `--freeze` 洗基线。T1 判据仍必须全绿。

## 说明.md 必须含
分支名 / `pwd` 与 `git branch --show-current` 的输出 / 改了哪些文件 /
**每条判据的先验红原始输出** / 验绿原始输出 / 查不清的明写「查不清」。

```

- write_paths: app/app/src/main/java/dev/agentmirror/app/workspace/L2SessionList.kt, app/app/src/main/java/dev/agentmirror/app/workspace/WorkspaceScreen.kt, .team/nodes/pr2-e7/
- read_paths: requirement-base/entries/088-会话列表与Agent生命周期.md, .team/nodes/pr1-idea-list/方案.md, .team/nodes/pr2-e7/说明.md
- 判据: A-e7-suite, A-e7-wiki, A-e7-smell, A-e7-doc, A-e7-seal

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app.workspace.L2SessionList.kt, dev.agentmirror.app.workspace.WorkspaceScreen.kt
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
