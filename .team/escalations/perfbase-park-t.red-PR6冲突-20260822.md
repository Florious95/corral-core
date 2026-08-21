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
