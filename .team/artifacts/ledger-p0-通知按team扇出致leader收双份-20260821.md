# P0 · 驱动器通知按 team 扇出、未按物理 leader 去重 ⇒ 同一事件 leader 必收两份

## 1. 现象
用户视角：每条驱动器通知（停机 Failed / parked / 解挂 unparked）都在 leader 屏上出现**两遍**，
message_id 不同、正文逐字相同、相隔约 120ms。用户以为消息系统坏了。

## 2. 日志（量具身份 + 原始行）
- ledger-run：本仓现构建（lease 持有 pid=99410，cwd=/Volumes/nvme/Projects/远程Agent安卓，单驱动器，
  .team/ledgers/hl1-v1.json.lease 佐证）；team-agent runtime 0.5.66。
- .team/logs/events.jsonl（同一事件的两封）：
  - `deliver_to_leader.submit message_id=msg_4b56f3573558 owner_team_id=grok-l2 leader_id=leader ts=14:31:41.260`
  - `deliver_to_leader.submit message_id=msg_4b76faa9b470 owner_team_id=remote-agent-android leader_id=leader ts=14:31:41.380`
- 历史对（同形）：msg_0ece13542fe0(grok-l2) / msg_0f041b985640(remote-agent-android)——解挂通知；
  当日全部停机通知均成对出现。

## 3. 复现
一个 workspace 里两个存活 team（实现席在 grok-l2、评审席在 remote-agent-android），同一物理 leader。
起 `ledger-run --drive --resident` 驱动跨两 team 的账本 → 任一停机/解挂事件 ⇒ leader 收两封同文异 id 消息。

## 4. 原因分析（盲测，未读源码；已排除项在后）
- 事实：单驱动器、两 submit、owner_team_id 恰好一边一个 ⇒ 通知路径按「账本涉及的每个 team」各发一份，
  收件人解析为各 team 的 leader，而两个 team 的 leader 是同一 pane。
- 已排除：①双驱动器（pgrep+lease 单实例）②team-agent 投递层自我复制（两封各有独立 submit，
  上游就是两次调用）③我方脚本重发（时间窗内 leader 无人工 send）。
- 从这里开始是推测：扇出发生在 ledger-run 的 notify 组装处（对 roles 涉及的 team 集合逐一发送）。

## 5. 正确行为建议（选项，你们定）
① 通知按**物理收件人**去重（解析后同一 leader 只发一份）；
② 或通知只发**账本声明的升报收件人/主 team** 一份；
③ 至低限度：正文带 dedup key（event_id），让接收端可判重。

## 6. 我方责任
一个工作区开两个 team（异源评审需要不同 provider 配置）是我方结构选择；若你们的模型期望
「一 workspace 一 team」，请明说，我们可以改为跨 workspace 的评审席寻址。
