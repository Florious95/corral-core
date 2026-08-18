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

## F-05 我方自伤三连：心跳的量具连坏三次（记在这里是为了不再犯，不是给对方的）

同一天内，判活这件事我用错了三把尺。三次都不是世界错了，是量具错了：

1. **`worker_state` / `last_output_at` 对 grok 席位失灵**（见 F-04）——席位在干活，字段冻住报 idle。
2. **`find -newermt '-30 minutes'` 在 macOS BSD find 上永远返回 0**。
   实测：同一时刻 `ls` 显示会话文件正在被写、python 量出「最近写入距今 1 秒、近 600 秒 39 个文件」，
   而 `find -newermt` 报 0。**这条会稳定地把健康运行判成卡死，并按心跳规则自动发 P0。**
3. **`pgrep -x ledger-run | head -1` 是全机匹配**。本机同时有三个驱动器在跑：
   `远程Agent安卓`（我的）、`agent前沿探索/多agent协作`、`无等编排`。
   `head -1` 取到的常是别人的进程，于是 etime 读数完全无意义（一度读到 24 秒，
   而我自己的那个已经跑了 21 分钟）。

⇒ 收紧：量具固化进 `.team/artifacts/heartbeat-check.py`，心跳只调它，**不许在心跳文案里手写 ps/pgrep/find**。
   驱动器按 **cwd 认领**，活动信号用 **python 比 mtime**，不用 find 的相对时间。

⇒ 这条的通用形状值得记：**「判据自己坏了」和「被测对象坏了」在输出上完全同形**，
   都是一个不好看的读数。区别只有一个办法能看出来——**用第二种独立方法量同一件事**。
   本次三条全是靠交叉验证（ls vs find、python vs find、lsof cwd vs pgrep）抓出来的。

## F-06 `worktree_id` 这个名字会让席位真的去建 git worktree，然后把活干在判据看不见的地方

- 日期：2026-08-18
- 规范里 `worktree_id` 的语义是**并发互斥标签**（相同 id 的任务不得并行）。
  但派单正文里出现这个词，席位读到的是「给我一棵工作树」。
- 实测：本账本六格全部被席位建出了 `.worktrees/<worktree_id>`
  （wt.app / wt.design / wt.package / wt.rollback / wt.server / wt.verify，全停在派单时的 HEAD）。
  - `dev-server` 建了树但仍在仓根干活 ⇒ 判据全过
  - `dev-app` 进树里干活 ⇒ 仓根零改动，判据 `A-ap-push` 报 `No tests found` 判红
  - `advisor` 第一轮更保守，直接声明「不碰仓根共享树」，只归档不回退 ⇒ 判据红
  **同一句话，三个席位三种解法。**
- 二次危害（这条更隐蔽）：席位建树时用的是**派单那一刻的 HEAD**。
  `wt.app` 停在 `1d3f60602`，早于回炉提交 `b89c09373`，
  ⇒ 那棵树里**还带着刚被回退掉的 v1 实现**。若把它整棵合回仓根，等于把刚拔掉的东西装回去，
  而且看起来像「席位干完了」。
- 我方收紧：所有任务标题显式写「`worktree_id` 只是互斥标签，必须在仓根干活，⛔ 不要 git worktree add」。
- 建议方向（不替他们定）：要么换个不误导的字段名（如 `mutex_group`），
  要么在派单正文自动附一句语义说明。**一个字段名如果会让执行者做出与语义相反的动作，
  那不是执行者理解力的问题。**

## F-07 我方纪律：改账本前必须先停驱动器（不是缺陷，是我踩的坑）

- 日期：2026-08-18
- 我为了不重派在途任务，用过一个做法：**只改「尚未派单」的任务标题、不 bump revision**，
  靠驱动器每轮重读账本让新文案生效。改**纯文字**时它侥幸成立。
- 这次我加了一个任务 `t.accuracy` 和一条依赖 —— **改的是结构**。
  驱动器当场发现内存里那份与盘上那份不一致，停机，日志原话：
  > 不合并、不覆盖、不猜。有人在我手上改了账本，这件事只能由人处理
- **这是对的行为，记在这里不是抱怨。** 它没有合并、没有覆盖、没有猜；
  如果它自作主张合并，我会拿到一张来路不明的账本，而且不会知道。
- 代价：那一轮 `t.research` 的成功**没有被写回**，任务回到 planned，要重跑一遍。
- ⇒ 纪律：**改账本 = 先停驱动器 → 改 → bump revision → 重启**。
  「不 bump 直接改」只在改纯文案且能接受不生效时才可考虑，不要当成常规手段。
