# P0 · `acceptance.mechanical` 的 cwd 不跟任务的 worktree ⇒ 判据必红、整条链停机

工程：`/Volumes/nvme/Projects/远程Agent安卓`　账本：`ledger.input.v1`　日期：2026-08-23
量具：`ledger-run`（本仓现构建）

## 现象

第 1 轮全格判据红，运行以 `Failed` 停机：

```
task=t.contract revision=2 判据红 acceptance_id=[M-contract]
  exit_code=1 (期望 0) cwd=. argv=["sh","tools/perfbase/judge-incontract.sh"]
task=t.close    revision=2 不可判 acceptance_id=[M-close]
  exit_code=2 (期望 0) cwd=. argv=["sh","tools/perfbase/judge-inclose.sh"]
```

## 对照组（这是本报告的新信息，⛔ 不是「又红了」）

**同一个判据脚本、同一时刻、只换执行目录，退出码相反：**

| 执行目录 | 命令 | 退出码 | 输出 |
|---|---|---|---|
| `.worktrees/wt-in0`（任务的 worktree） | `sh tools/perfbase/judge-incontract.sh` | **0** | `PASS docs/输入透传契约.md（241 行）…` |
| 仓根 `/Volumes/nvme/Projects/远程Agent安卓` | 同上 | **1** | `FAIL 契约不存在：docs/输入透传契约.md` |

产物落点实证：

```
$ ls -l .worktrees/wt-in0/docs/输入透传契约.md
-rw-r--r--  1 alauda  staff  15425  8月 23 04:45
$ ls -l docs/输入透传契约.md          # 仓根
ls: docs/输入透传契约.md: No such file or directory
```

## 原因分析及其边界

- 任务 `t.contract` 声明 `resources.worktree_id = "wt-in0"`，引擎据此 `git worktree add`，
  **派单也确实把席位指进了那棵树**（读屏可见席位在 `.worktrees/wt-in0` 里 glob/grep），
  席位把产物写进了那棵树 —— 到这里都对。
- 但 `acceptance.mechanical[].cwd` 写 `"."` 时，**解析基准是仓根，不是该任务的 worktree**。
  于是判据在一棵**没有本格产物**的树上执行，必红。
- **边界（我方没有证据的部分）**：我方没有读引擎源码，不知道这是
  ①有意设计（cwd 一律相对仓根，账本作者应自己写 `.worktrees/<id>`）
  还是②遗漏（cwd 本应相对任务 worktree）。**本报告只主张现象与对照，不主张哪一种。**

## 我方的绕行（已生效，⛔ 不是修复）

把 `cwd` 显式写成 `.worktrees/wt-in0`，重跑 revision 3 后两格判据均绿
（`t.close=Succeeded`；`t.contract` 因是转移边源格被 `route_contradicts_success` 正当拒关）。

## 为什么算 P0

编排**中断且没有提醒结束**（`停机原因: Failed`），符合本工程 2026-08-18 的 P0 判据。

## 一个附带的观察（不是缺陷主张）

`t.close` 的判据返回 exit 2 被如实记成「不可判」而**没有折进「不通过」**，四态在这里工作正常。
这条写下来是作为正例留档。

## 一个建议（采纳与否由你们定）

若①是有意设计，`--preflight` 或许可以在
「任务声明了 `worktree_id` **且** 判据 `cwd` 不以 `.worktrees/<该 id>` 开头」时给一条提示。
现在这个组合**预检全绿、dry-run 形状也对**，只有真跑起来才炸，账本作者拿不到早期信号。
