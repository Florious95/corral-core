# P0：投递记 delivered、席位进程活着，但消息从未成为一个回合（grok 席位）

- 报告方：远程Agent安卓 team leader（使用方）
- 受理方：ledger-orchestration 维护 team
- 日期：2026-08-18
- 定级依据（用户 2026-08-18 令）：编排中断、又没有提醒使用方 ⇒ P0。

## 一、现象

账本 `ledger.l2-tristate.v1` 第三格 `t.srv` 于 13:29:16 派给席位 `dev-server`（provider=grok），
框架记 `delivered`。**8 分钟后该消息仍未在席位侧成为任何一个回合**，席位处于空闲。
驱动器在 `wait` 上空等（预算 1000s），账本停在 r3 不动。

同一账本里 `t.rule`（advisor）与 `t.app`（dev-app）**都正常完成了**，
所以不是全队死了，是这一条没被消费。

## 二、日志与证据（全部只读检查）

### 量具身份

- `team-agent`：`0.5.66+integrate.10137cda`，md5 前 12 位 `feb3e3487f6d`
- `ledger-run`：md5 前 12 位 `5e17f4ab1d6f`，mtime `8月 18 19:51`

### 驱动器日志（原样）

```
[ledger-run 2026-08-18T13:29:16Z] 派单 dispatch | task=t.srv 收件人=…::grok-l2/dev-server 席位=dev-server
[ledger-run 2026-08-18T13:29:16Z] 投递回执 send-ok | task=t.srv case_id=ledger_l2-tristate_v1__t.srv__r2 message_id=msg_df9371eab130
[ledger-run 2026-08-18T13:29:16Z] 派单落地 dispatch-landed | task=t.srv 席位 dev-server 的收件箱已多出这条派单
[ledger-run 2026-08-18T13:29:16Z] 等待 wait | task=t.srv 在等的 key（case_id）=ledger_l2-tristate_v1__t.srv__r2 team=grok-l2 预算=1000s
```

### 收件箱（`team-agent inbox dev-server --json` 最后一条）

```
created 13:29:16  delivered 13:29:18  status=delivered  delivery_attempts=1  error=None
content_len=1996   （正文完整，开头是 "[账本任务 t.srv] 服务端实现三态规则（契约 062）…"）
```

### 席位侧：消息从未出现

```
grok 会话根 ~/.grok/sessions/**/*.jsonl 中搜 msg_df9371eab130 → 命中文件数 0
```

### 席位进程活着（排除「席位死了」）

```
pane dev-server 的 shell 子进程：comm=node（grok CLI 是 node 程序），etime=02:05:19
pane_in_mode=0（**不是 copy-mode**，排除已闭合的那条 Enter 被吃根因）
```

### 席位处于空闲（用你们自己的判定口径）

pane 标题无工作态前导符号，且内容停在**上一张账本**的任务摘要
（`061 server L2 status push and tests - grok`）——即它最后一次真正干活是上一张账本那一格。

`team-agent status` 报 `status=running / worker_state=PROBABLY_IDLE`，
`last_output_at=12:35:30`（一小时前）。

## 三、复现

```bash
cd /Volumes/nvme/Projects/远程Agent安卓
ledger-run --drive --json .team/ledgers/l2-tristate-v1.json
# 观察 t.srv：dispatch-landed 之后 wait 永远等不到；
# 同时 inbox 记 delivered、grok 会话里搜不到该 message_id
```

⚠️ 我不确定它必现。同一账本前两格（advisor / dev-app）都正常消费了，
所以更像是**偶发**或与某个前置状态相关，而不是这条路径整体不通。

## 四、原因分析，以及我的判断边界

**查到这里为止是事实**：投递侧记 delivered；席位进程活着；不是 copy-mode；
消息在席位侧的会话记录里完全不存在；同队其他席位同期正常消费。

**从这里开始是推测，你们自己核**：从「框架记 delivered」到「成为席位的一个回合」之间
还有一段（注入 → CLI 收下 → 起一轮 → 写会话）。这段里有一步无声地丢了这条消息。
你们此前闭合过的两条同族根因（copy-mode 吃 Enter、尾部截断丢 token）在本例中都不成立：
前者 `pane_in_mode=0`，后者 `content_len=1996` 且正文完整。**所以这可能是第三条。**

⚠️ 我**没有**去做复现阶梯、没有保场、没有采集更多样本——按我方用户裁定，取证是你们的工作。
上面全部来自正常干活时的只读检查。

## 五、这条为什么是 P0

失败形态是**沉默的**：投递侧全绿（delivered / attempts=1 / error=None），
驱动器"在正常等待"，席位"活着"，**四个观测面没有一个报错**，
而实际上这一格永远不会完成。使用方只能靠自建心跳发现，
且发现之后也分不清是「席位在慢慢干」还是「这条根本没进去」——
我是靠 `grok 会话里搜 message_id 命中 0` 才分开的，这个量具不是框架提供的。

⇒ 建议方向（修法归你们定）：**送达凭据不能止于「写进收件箱」**。
真正的凭据是「它成为了席位的一个回合」。你们已经有 `token_landed` 这类判据的思路，
差的是把它接进投递路径本身，让「投了但没被消费」在**有限时间内变成一个响的失败**。

## 六、我方状态

**不停工**（按用户 2026-08-18 新裁定：投完报告就继续）。
我会重启该席位并让驱动器继续推进；如果重启后同一条恢复正常，我会在下一封里补一句。

**不需要我方配合取证。不用长回。**
