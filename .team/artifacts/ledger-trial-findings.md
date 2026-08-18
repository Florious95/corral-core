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

## F-04 🔴 grok 席位的 worker_state / last_output_at 失灵，会诱发错误的 P0

- 日期：2026-08-18
- 量具：team-agent `0.5.66+integrate.10137cda`，md5 前 12 位 `feb3e3487f6d`
- 现象：`t.design` 于 11:58:56 派给 advisor，`inbox` 记 `status=delivered / delivered_at=11:59:00.350`。
  此后 4 分钟：
  - `status --json` 持续报 `advisor PROBABLY_IDLE`，`last_output_at` **冻在 11:58:59**（早于送达 1.35 秒，
    那是上一格 t.rollback 的收尾输出）
  - 而 grok 自己的会话文件 `~/.grok/sessions/**/chat_history.jsonl`、`updates.jsonl`
    **在 12:03 仍在被写**，且该 `message_id` 在其中命中 3 处
  ⇒ **席位一直在干活，框架的状态字段说它 idle。**
- 危害：这正是「活着但没在干活」的**假阳性**。按试用期纪律，
  「席位全 idle + 驱动器活着 + 无写回」要按 P0 上报——我差一点据此给框架队发了一份
  针对健康运行的 P0，白烧对方上下文。
- 我方已做的收紧：心跳的判据从 `worker_state` 改为 **grok 会话文件 mtime**
  （`~/.grok/sessions` 下最近写入时间），因为它反映的是世界，不是框架的推断。
- 附带教训（我方的，不是他们的）：我第一次查转录查的是
  `.team/runtime/provider-config/advisor/claude/**.jsonl` —— 那 5 个文件全是 8/15–16 的、
  属于**老 claude team 的同名席位**。拿老 team 的转录去量新 grok 席位，必然得到「命中 0」。
  **没有量具身份的读数不作为裁定依据**，这次是自己撞上的。
