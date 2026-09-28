# 知识基底 · ledger.pr1.v1 / t.idea-list（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.idea-list · 会话列表交互与 Provider 图标的方案（契约 088）⛔ 只出思路，一行代码都不许改

## 要给出可直接施工的方案
- **E10** 首列由星标改为 **Provider 图标**（收藏页首列同样）；**E11** 收藏改为**长按**弹出。
- **E12 关闭**：三件事同时成立的**原子动作** —— 关 CLI 进程 ＋ 关它在 tmux 下的条目 ＋
  若是收藏项则一并取消收藏。⛔ 不许出现半截状态。要二次确认。
  **作用域（用户裁定 b）**：收藏页⛔ 不给「关闭」；**会话页长按已收藏项照给**。
- **E13 新建 Agent**：工作区页右上角「+」，选 Provider ＋ 勾 Bypass ⇒ 新开 tmux pane 跑启动命令。
- **E14**：设置里可改**每个 Provider 的完整启动命令**（用户自建 `claude-local` 走本地第三方模型）。
- **E7** 列表展示不全且无下拉提示；**E8** 侧滑退出了会话而不是关列表。

## Bypass 参数（leader 本机 `--help` 实测，⛔ 不要自己去猜或改）
claude → `--dangerously-skip-permissions` / codex → `--dangerously-bypass-approvals-and-sandbox` /
copilot → `--yolo` / cursor → `--force` / **grok → `--always-approve`** / pi → **无**。
⚠️ Grok 必须**显式传** `--always-approve`：用户截图里的 always-approve 是他本地 config 已开，
产品⛔ 不许依赖用户 config。

## Provider 图标的硬指标（⛔ 不是形容词）
1. 不透明底必须去掉（原生资产直接摆上会出现方块底，这是用户点名的问题）。
2. 与界面互洽：统一光学尺寸、统一内边距、统一圆角容器；**深浅两主题各出一版对比图**。
3. 观感（可爱、耐看、不突兀）走 user gate。
4. **许可**：优先用各家官方发布的资产并归一化；**凡许可不允许再分发的，在同一视觉语言下自绘**。
   🔴 **逐个 Provider 查清许可并写进文档**（资产地址 + 许可名 + 是否允许再分发）。查不清就明写「查不清」，
   ⛔ 不许因为"大家都这么用"就当成可以。

## 产物
唯一产物：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr1-idea-list/方案.md`，给到能直接派实现席位的粒度
（改哪个文件、改什么、判据怎么写）。⛔ 写范围只有 `.team/nodes/pr1-idea-list/`。

---
## 🔴 本轮流程：PR 链（一格一分支，判据过了才并线）

**开工第一件事，跑这两条自检，把输出贴进说明.md：**
```
pwd                        # 你必须在自己的 worktree 里，不是仓根
git branch --show-current
```

1. **建你自己的分支**：`git checkout -b pr/idea-list`。
2. **只提交到本分支**。⛔ 不许并线、⛔ 不许碰 main、⛔ 不许 `git stash apply` 别人的改动。
3. **⛔ 你不要 push。** 本仓本地没配 remote，远端是 `tools/mirror-push.sh` 过滤后推的镜像仓，
   **PR 由 leader 代开**。你的交付＝分支名 + commit sha + 说明.md。
4. **⛔ 判据红了不许改判据让它变绿。** 判据本身写错 ⇒ 报 `blocked` 并指出错在哪，不要自己改。
5. **必须写合规的外骨骼注释** —— 架构维基从注释现算，注释不合规 ⇒ 维基缺节点缺边 ⇒
   下一个席位的知识基底是残的。你的机械判据含 `archwiki --check --strict-t3`，会红给你看。
6. **一次只修一个缺陷。** ⛔ 顺手改相邻代码 / 顺手重构 / 顺手改格式，全部禁止。
   每一行改动都要能追溯到本格需求。

## 🔴 判据纪律（三铁律）
- 判据要断言「世界变了」；**写完先验红**（改之前跑，必须红），再改，再验绿。
- **先验红的原始输出必须贴进 `说明.md`**，⛔ 没有先验红的绿不算数。
- 断言「某物不应出现」时**必须先制造出让它出现的条件**，否则是恒真判据。
- 判据**查代码内容，⛔ 不查 commit 身份**（revert/cherry-pick 会让「commit 在不在」说谎）。

## 说明.md 必须包含
分支名 / commit sha / `pwd` 与 `git branch --show-current` 的输出 / 改了哪些文件 /
**每条判据的先验红原始输出** / 每条判据的验绿原始输出 / 查不清的地方明写「查不清」。

```

- write_paths: .team/nodes/pr1-idea-list/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/088-会话列表与Agent生命周期.md
- 判据: A-il-doc

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
