# park · t.red（corral-core PR #6）land 时 add/add 冲突，未解，留给用户裁

- 时间：2026-08-22 03:56（UTC 19:56）
- 分支：`pr/perfbase-red`（head `d50085b06`，3 个封版提交）
- 冲突：`CONFLICT (add/add): app/app/src/main/java/dev/agentmirror/app/perf/PerfTrace.kt`
- 处置：**已 park，⛔ 未解冲突**（铁律⑩ leader 不亲写产品码含解冲突；autopr 也不自动解）。
  其余格照常推进，链子没停。

## 为什么会撞

我立格时给 t.red（先红格）和 t.app（转绿格）**各自一个 worktree**，两条分支都从 main 起。
t.red 建了 `PerfTrace.kt` 的骨架 + 三条红测；t.app 在自己的树里**又建了一遍同名文件**的完整实现。
判者 pass 之后 autopr 先 land 了 t.app（PR #7，已 MERGED），main 里已经有这些文件，
再 land t.red 就是 add/add。

**这是我的账本设计错**，不是席位错也不是引擎错：先红格与转绿格改的是同一批文件，
本该让 t.app 的分支**从 t.red 的分支起**（或共用同一 worktree 串行），而不是各起一棵。

## 现在的事实（别被 PR #6 OPEN 误导）

- t.red 的交付物**已经在 main 里**了 —— 它的骨架与三条红测随 PR #7 一起进的，
  且进去的是**判者复审过的那一版**（r8 pass，另含新增的 `PerfTraceWiringTest`）。
- t.red 的「先红」证据没有丢：账本里 `c.red` 判据通过、attempt 落档；PR #6 的 diff 本身就是那份先红快照。

## 给用户的三个选项（我不替你选）

1. **关掉 #6 并注明 superseded by #7**（我的建议）：内容已进 main，强行 merge 只是制造一个假的合并点。
   代价：#6 显示 closed 而不是 merged，「一事一闭」在这一格上是「一事一 PR 一说明」。
2. **让席位去解冲突**：派 pb-impl 在 `pr/perfbase-red` 上 rebase 到 main 并保留先红快照语义。
   代价：解出来的东西没有判者复审，且它解的是一份已被取代的旧版。
3. **改账本形状重跑**：先红格与转绿格串到同一 worktree。代价：整格重来一轮。

⛔ 我没有替你 merge、没有 force-push、没有改 #6 的任何状态。

---

## 追加 park：t.instr4（corral-core PR #10），同一类原因

- 时间：2026-08-22 00:40（UTC）
- 冲突：`CONFLICT (content): app/.../conn/ConnectionManager.kt`
- 处置：**已 park，⛔ 未解冲突**。

原因与 #6 同类、也同样是**我的账本形状错**：`t.fixblank` 的分支是**基于 instr4** 做的，
它 land 进 main（e8343236e）时把 instr4 的留痕一并带进去了；再 land instr4 自己就成了 content 冲突。
⇒ **t.instr4 的内容已经在 main 里**，PR #10 只是同一份东西的第二个入口。

给用户的选项同 #6：①关掉 #10 注明 superseded by #11（建议）②让席位 rebase ③改账本形状。
⛔ 我没替你 merge、没 force-push、没改 PR 状态。

## 另记一条排序错（已自修，不需要用户处置，但要知道发生过）

`t.core`（三核模块切分）的 worktree 是在仪表与白屏修复 land 进 main **之前**建的（基点 ed79f179c），
切出来的包里 PerfTrace 符号 0 处、白屏修复 0 处 ⇒ 复测格 `t.cperf` 拿到 0 行日志、判**不可判(2)**。
判据没有把它折成通过或失败，席位也没有手工合并去凑一个能测的构建——两边都守住了。
处置：rev24 把 t.core 换新 worktree（wt-pb-core2）在当前 main 上重做，t.cperf 换钥匙复位。
教训：**依赖 land 结果的格，不能与 land 竞速**——账本里要么显式等 land 完成，要么让该格自己从最新 main 起树。
