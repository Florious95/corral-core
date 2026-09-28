# P0 · ledger-run 因 send 进程超时停机，但派单实际已落收件箱

## ① 现象

用户视角：全自动账本刚启动就在第一格挂起，尽管目标 Codex 席位真实存在且随后可见派单正文。

机器视角：`ledger.input-full-auto.v1` revision 1 在派 `t.perf.design` 时，`ledger-run` 报 `Timeout { timeout: 30s }`，将运行置为 `AwaitingHuman/parked`；随后一次只读 `team-agent inbox pi-codex-bridge` 明确显示同一账本任务正文，状态 `target_resolved`。即“send 子进程超时”与“派单未落地”不等价，本次实际落地但驱动器已停机。

## ② 日志与量具身份

原始日志：`.team/nodes/_driver/input-full-auto-v1.out`。

关键原文：

```text
[ledger-run 2026-08-23T14:42:23Z] 派单 dispatch | task=t.perf.design 收件人=/Volumes/nvme/Projects/远程Agent安卓::remote-agent-android/pi-codex-bridge 席位=pi-codex-bridge attempt=att-t.perf.design-seq1-t1787496143537 delivery=del-t.perf.design-seq1-t1787496143537 等席位预算=1800s 来源=任务 seat_wait_seconds
[ledger-run 2026-08-23T14:42:53Z] 停机 halt | 投递失败: t.perf.design → /Volumes/nvme/Projects/远程Agent安卓::remote-agent-android/pi-codex-bridge（Timeout { timeout: 30s }）
[ledger-run 2026-08-23T14:42:53Z] 挂起 park | token=parked ledger_id=ledger.input-full-auto.v1 revision=1
```

落地对照（同一席位 inbox）：

```text
- leader: [账本任务 t.perf.design] ... [status=target_resolved attempts=1 error=-]
case_id=ledger_input-full-auto_v1__t.perf.design__r1
```

量具：
- `ledger-run`：`/Users/alauda/.cargo/bin/ledger-run`
- md5：`8c1c850bec4c86d230480b99fd6cd671`
- mtime：`2026-08-20T15:06:13+0800`
- size：`13725520`
- team-agent 观测入口：安装 CLI 的 `status` / `inbox`，未读私有 runtime 或 worker socket。

## ③ 最小复现

cwd：`/Volumes/nvme/Projects/远程Agent安卓`

```sh
ledger-run --drive --resident --json .team/ledgers/input-full-auto-v1.json
team-agent inbox pi-codex-bridge --workspace . --team remote-agent-android
```

期望：若 send 外层超时但消息最终落地，应进入“投递不确定/对账”状态，或在有 `target_resolved` 后继续等待同一 case；至少不应把同一已落地任务当成确定投递失败。

实际：30 秒 timeout 后账本 park；inbox 已有同一任务正文。

## ④ 原因分析、已排除与边界

已排除：
- 收件席不存在：`team-agent status` 显示 `pi-codex-bridge` running，前两次直接派单均成功并产生 durable result。
- 派单正文未生成：inbox 中正文完整，含正确 worktree 与 case_id。
- 产品判据红：停机发生在任何 acceptance 之前。
- leader 手工重投：未重投该 case。

可确定到：`ledger-run` 将 send 子进程的 30 秒超时直接映射为投递失败并 park，但外部效果后来/同时实际落地，因此是外部动作结果不确定的归类问题。

判断边界：未读框架源码，不判断 timeout 发生在 team-agent CLI、coordinator 回执还是 ledger-run 等待层；也不替框架指定实现。正确行为可选为：超时后按同一 delivery/case 对账，或结构化进入 `needs_reconcile`，但不得自动生成第二次外部投递。

绕行与代价：我方不重投；保留当前席位继续完成已落地任务，结果到达后提升 ledger revision 解挂并消费。这会增加一次人工 revision 手术，但避免重复执行。

## 新对照：fresh seats + revision 2 仍精确 30 秒超时

用户禁止人肉补投后，我方移除了收到人工救援消息的旧三席，新增三个从未收过任务的 Codex fresh seats，并把 `ledger.perf-sampler.v1` 提升到 revision 2。preflight、dry-run 均绿，前沿为 impl/probe/test 三格并行。新驱动器仍在第一条自动派单上精确 30 秒超时并 park：

```text
[ledger-run 2026-08-23T15:21:36Z] 派单 dispatch | task=t.sampler.impl ... 席位=sampler-dev-luna2 ...
[ledger-run 2026-08-23T15:22:06Z] 停机 halt | 投递失败: t.sampler.impl ...（Timeout { timeout: 30s }）
[ledger-run 2026-08-23T15:22:07Z] 挂起 park | token=parked ledger_id=ledger.perf-sampler.v1 revision=2
```

独立 nodeprobe 随后显示全部 Codex 席位 idle。这个对照排除了旧席位 inbox 积压、旧 case、旧 revision 和单一角色损坏；失败族收敛为：**ledger-run 自动 send 调用系统性在固定 30 秒超时，而同 workspace 的直接 Team Agent 通信此前可完成 durable round-trip**。按用户要求，我方不再用直接 send 顶替全自动编排，因此产品任务当前诚实阻塞。
