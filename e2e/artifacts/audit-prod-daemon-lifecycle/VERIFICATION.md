# 终审裁定 · audit-prod-daemon-lifecycle

- 终审员：v-audit-prod-daemon（一次性独立终审员）
- 终审时间：2026-08-10（隔离复跑取证）
- 结论：**FAIL**（红项：告警去重状态机压掉「故障→健康→同故障复发」的复发告警）

---

## 1. 审计对象与方法

完整阅读：
- 根 `CLAUDE.md`（工程编排约定/红线）
- `.team/nodes/audit-prod-daemon-lifecycle/{CLAUDE.md,FIELD.md,LIBRARIAN.md}`（任务信封、现场基、撞库回执）
- `e2e/artifacts/audit-prod-daemon-lifecycle/REPORT.md`、`prod-guard-selftest.sh`、`prod-guard-selftest.log`
- `.team/evidence/audit-prod-daemon-lifecycle.json`
- 当前窄 diff（`git diff HEAD -- .team/watchdog.py`；`.team/prod-daemon-launch.sh` 为新增未跟踪文件）

红线遵守：未修改产品代码、`.team/watchdog.py`、launcher、原 REPORT/evidence/台账；
唯一写入 `VERIFICATION.md`。PID 3393、`:9900`、真实 tmux 仅 `ps/lsof/stat` 前后只读指纹，
未发任何 signal / restart / attach / HTTP / WS。测试全部使用隔离高端口假进程 + 临时目录。

## 2. 独立复跑三条 acceptance（全部 exit 0）

| # | 验收命令（独立复跑） | exit | log_path |
|---|---|---|---|
| A1 | `bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/REPORT.md && python3 -m json.tool .team/evidence/audit-prod-daemon-lifecycle.json >/dev/null'` | 0 | REPORT.md / evidence json |
| A2 | `bash -lc 'python3 -m py_compile .team/watchdog.py && bash -n .team/prod-daemon-launch.sh'` | 0 | .team/watchdog.py, .team/prod-daemon-launch.sh |
| A3 | `bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.log'` | 0 | prod-guard-selftest.log |

三条 acceptance 均通过。但见 §4：验收并不覆盖「复发须再告警」这一契约，selftest 只测了
连续同故障去重，未测健康间隔后的复发。

## 3. 专项红测：告警去重状态机（隔离高端口假进程，端口 60000，临时目录）

契约：同一端口依次「故障→健康→同故障复发」，**复发必须再次追加告警**；同一故障连续两次才应去重。
若当前实现压掉复发，终审判 fail，不修。

隔离复跑（`--prod-guard-once --port 60000 --log <tmp>/prod.log --escalation-log <tmp>/esc.log`）：

| 步 | 状态 | 预期 | 实测 `escalation_written` | exit |
|---|---|---|---|---|
| S1 | 故障 missing_listener | true（首报追加） | **true** | 1 |
| S2 | 健康（假 daemon 监听 + 日志接管） | false（健康不写） | **false** | 0 |
| S3 | 同故障复发 missing_listener | **true（复发必须再追加）** | **false（被压掉）** | 1 |

实时输出：
```
STEP1 fault rc=1 write=True faults=['missing_listener']
STEP2 healthy rc=0 write=False pid=66144
STEP3 recurrence rc=1 write=False faults=['missing_listener']
escalation_records=1
esc_log_verbatim:
PROD_GUARD {"at": "2026-08-09T18:44:14.886038+00:00", "component": "prod_daemon_guard", "faults": ["missing_listener"], "fingerprint": "60000:missing_listener", "pid": null, "port": 60000, "state": "fault"}
```

两次故障（S1、S3 同 fingerprint）后隔离 esc.log 只有 **1 条**告警记录——S3 的复发被去重逻辑误吞。
补充对照：连续同故障确实去重（repeat 轮 escalation_written=false，符合契约）；不同 fault
组合（missing_listener→missing_listener,missing_log）会写新记录（escalation_written=true）。

### 红项定位

- file: `.team/watchdog.py`
- 行号：`record_prod_guard()`（第 244–265 行）；根因在 248–252 行——健康快照直接
  `return False`（248 行）不写任何状态位，去重只读**上一条 PROD_GUARD 记录**（246 行
  `_last_prod_guard_record`），`previous.state=="fault" && fingerprint 相同 → return False`（250–252 行）。
  因健康不重置指纹，S2 之后上一条仍是 S1 的 fault，S3 同指纹即被误去重。
- argv：`python3 .team/watchdog.py --prod-guard-once --port 60000 --log <tmp>/prod.log --escalation-log <tmp>/esc.log`（S1→S2→S3 依次，隔离端口+临时目录）
- exit：S1=1、S2=0、S3=1（进程面 exit 正常，但 S3 该写未写）
- 日志路径：隔离 `<tmp>/esc.log`（`escalation_records=1`；生产 `.team/logs/watchdog-escalation.log` 全程未触碰）

## 4. 为什么 selftest 绿了仍属契约违反

`prod-guard-selftest.sh` 的序列是 fault(missing)→repeat 同 fault（连续），断言
`repeat["escalation_written"] is False`——对「连续同故障去重」这是**正确断言**，它从不构造
「健康间隔后复发」。契约恰恰要求健康间隔后的复发**必须再报**，而实现没有健康位。所以
acceptance A1–A3 全绿不构成对该契约的验证，红测直接命中实现缺陷。

## 5. launcher / watchdog 干净性核验（通过）

- **launcher 未执行**：`prod-daemon-launch.sh` mtime 2026-08-10 02:23:10，晚于 PID 3393
  start（01:23:19）；PID 3393 PPID=1（非 launcher 子进程）。
- **stdout/stderr 追加接管**：launcher `exec nohup "$DAEMON" "$@" </dev/null >>"$PROD_LOG" 2>&1`
  ——追加、双流接管、无 kill/takeover/restart loop。
- **无 token/authkey/argv 输出**：launcher 全文仅一条 `echo "prod launcher: daemon binary is not
  executable"`；无 profile/env 读取，无密钥原文。
- **watchdog 无 kill/signal API**：`rg os\.kill|send_signal|terminate\(|SIGKILL|SIGTERM|pkill|killall`
  零命中；生产探针只用 lsof/ps/stat 只读。
- **Team Agent 调用统一走 `.team/ta`**：`subprocess.run([TA, ...])`（第 299、302 行），
  无直接 `["team-agent", ...]` 调用。
- **PID 3393 / :9900 前后指纹完全一致**：start `Mon Aug 10 01:23:19 2026 ./server/agentmirrord`、
  listener `p3393 cagentmirrord *:9900`、FD 1/2 → prod log inode 652421996 全部前后相同；
  `stat` inode 652421996 一致。生产 `.team/logs/watchdog-escalation.log` 未被我方测试改动。

## 6. classification=unknown 相称性复核（通过）

- REPORT 以 cleanup-followup.log（00:58:19 `pid_46081=present_untouched`）为最后 PID 存活
  证据，01:23:19 恢复实例出生为右边界；无 product 内部退出证据（dirty build、旧 stderr 未落盘），
  无 environment 信号/系统回收正证据，HANDOFF 宽 pkill 仅有文本无执行证据。
- evidence JSON 的 `classification=unknown`、`followup_task=null`、`causal_gaps` 逐项与
  REPORT 一致；缺失证据未升级成 product/environment，符合「没有证据就写 unknown」。
- 复核通过，未发现 evidence 与证据面失衡。

## 7. 终审结论

**FAIL**。唯一红项为 §3/§4 所述告警去重状态机缺陷：
`.team/watchdog.py:244-265`（根因 248–252 行）在「故障→健康→同故障复发」下压掉复发告警，
违反契约「复发必须再次追加告警；同一故障连续两次才应去重」。按纪律不顺手修，仅记录。
其余交付面（launcher、只读探针、干净性、classification=unknown 相称性）核验通过。
