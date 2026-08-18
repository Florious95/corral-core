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
