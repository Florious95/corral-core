# 账本编排试用期 · 优化清单（非阻塞，攒批交用户会签后统一发）

判别式：**这个账本还能不能往前推一格？** 能 ⇒ 记这里，不投。

---

## F-01 grok 席位的 resume 检查查的是 claude 的转录目录

- 日期：2026-08-18
- 命令：`team-agent restart . --team grok-l2`
- 现象：`refused_resume_atomicity`，`checked_paths` 指向
  `.team/runtime/provider-config/<席位>/**claude**/projects/<uuid>.jsonl`
  —— 而这两个席位的 `provider` 是 **grok**。
- 影响：不阻塞（`--allow-fresh` 可过），但对**真有上下文**的 grok 席位，
  这条路径查不到就会误判"不可恢复"，从而逼人用 `--allow-fresh` 丢掉真实上下文。
  本次安全只是因为那两个席位从没跑过一个回合（jsonl 根本不存在）。
- 量具：team-agent `0.5.66+integrate.10137cda`（8月18 17:41）

## F-02 status 报 ready=False / workers_not_spawned，而四个席位实际在跑且能干活

- 日期：2026-08-18
- 现象：`status --json` → `ready:false`，`not_reasons:["workers_not_spawned"]`；
  同一份输出里四个席位全是 `running` + `PROBABLY_IDLE`。
  随后实测：派单送达、席位转 BUSY、命令执行、文件写盘全部正常。
- 影响：不阻塞。但 `ready` 这个字段在这种情况下**与世界不一致**，
  如果有人拿它做门禁（"ready 才开工"），会卡住一个健康的队伍。
- 量具：同上

## F-03 驱动器的停机通知用了外部 team key `annot`，在本工作区必然投不出去

- 日期：2026-08-18
- 现象：`ledger-run --drive` 正常停机（判据红），日志尾部：
  ```
  "通知未送达：team key `annot` is not a runtime key in target workspace
   `/Volumes/nvme/Projects/远程Agent安卓` (spec/display name may differ from runtime key)"
  ```
- 关键事实：**我的账本里 `annot` 出现 0 次**（`grep -c annot` = 0）。
  账本里所有 `roles[].seat.team` 都是 `grok-l2`。⇒ `annot` 不是我给的，是驱动器自己带的
  （`annot` 是编排队自己工作区的 team 名）。
- 影响：**不阻塞**（我有 30 分钟心跳，从日志里读到了停机原因），
  但它让「跑完/停机必须通知 leader」这条功能在外部工程里**必然失效**——
  而这条恰恰是我 2026-08-16 提的诉求、他们采纳并实现的那条。
  没有心跳的使用方会完全看不见停机。
- 建议方向（不替他们定）：通知的 team key 应从账本 `roles[<owner.role>].seat.team` 取，
  不要用任何默认值/活跃 team。这和 skill 里「`results --case` 必须显式传 `--team`，
  不要依赖当前活跃 team」是同一条。
- 量具：`ledger-run` md5 见下次报告时补；本次未记，属我方疏漏（量具身份纪律）。
