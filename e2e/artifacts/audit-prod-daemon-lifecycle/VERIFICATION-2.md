# 二次终审裁定 · audit-prod-daemon-lifecycle（回炉后第二次独立终审）

- 终审员：v-audit-prod-daemon2（一次性独立终审员，交件即退役）
- 终审时间：2026-08-10（独立复跑 + 隔离状态机重做）
- 结论：**PASS**（首审唯一红项——「fault→healthy→同 fault 复发」复发告警被吞——回炉后已修复并独立实证）

---

## 1. 审计对象与方法

完整阅读：根 `CLAUDE.md`、`.team/nodes/audit-prod-daemon-lifecycle/{FIELD.md,LIBRARIAN.md,CLAUDE.md}`、
`e2e/artifacts/audit-prod-daemon-lifecycle/{REPORT.md,VERIFICATION.md,prod-guard-selftest.sh,prod-guard-selftest.log}`、
`.team/evidence/audit-prod-daemon-lifecycle.json`、`.team/watchdog.py`、`.team/prod-daemon-launch.sh`。

红线遵守：未修改产品代码、`.team/watchdog.py`、launcher、原 REPORT/evidence/台账和第一次红证
`VERIFICATION.md`；唯一写入 `VERIFICATION-2.md`。PID 3393、`:9900`、真实 tmux 仅 `ps/lsof/stat`
前后只读指纹，未发任何 signal / restart / attach / HTTP / WS；未读 profile/env 密钥原文，
未输出 token/authkey/argv。自测进程只自然退出或精确 wait 自身捕获 PID，零残留。

## 2. 独立复跑三条 acceptance（全部 exit 0）

| # | 验收命令（独立复跑） | exit |
|---|---|---|
| A1 | `bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/REPORT.md && python3 -m json.tool .team/evidence/audit-prod-daemon-lifecycle.json >/dev/null'` | 0 |
| A2 | `bash -lc 'python3 -m py_compile .team/watchdog.py && bash -n .team/prod-daemon-launch.sh'` | 0 |
| A3 | `bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.log'` | 0 |

## 3. 独立状态机序列重做（隔离高端口 60010/60011 + 临时日志 + 假 agentmirrord，与 shipped selftest 端口/时序独立）

契约六性逐一独立复跑（每步用 `--prod-guard-once`，读回 `escalation_written` 与 esc.log 记录数）：

| 步 | 状态 | 预期 `escalation_written` | 实测 | 契约 |
|---|---|---|---|---|
| S0 | 基线 healthy（空日志，无前序记录） | false | **false** | 健康不写 |
| S1 | fault 首报 missing_listener | true | **true** | 首报追加 |
| S2 | 连续同 fault | false | **false** | 连续去重 |
| S3 | healthy 恢复转换（假 daemon 监听+日志接管） | true | **true** | 恢复落一条记忆 |
| S4 | 恢复后再 healthy | false | **false** | 健康不刷屏 |
| S5 | 等假 daemon 自然退出后同 fault 复发 | **true** | **true** | **复发必须再告警（首审红项回归）** |
| S6 | 复发后连续同故障 | false | **false** | 复发后再去重 |
| S7 | 不同 fault missing_log | true | **true** | 不同故障不吞 |

esc.log 断言：fault 记录 3 条、healthy 恢复记录 1 条；exit 码 healthy=0/fault=1 全对；
S5 复发 `escalation_written=true` 实证红项修复成立（首审红证 S3 为 false 被吞）。

补充独立边界（同端口不同 fault，端口 62000）：missing_listener → missing_log(+fd 未接管) →
missing_listener 依次全部写入（fingerprint 各异），esc_records=3——不同故障即使同端口也不吞。

交叉对照：原 shipped `prod-guard-selftest.sh` 重跑（输出重定向到临时文件，未触碰原证据日志）
exit 0，`result=PASS`，`dedupe_fault_records=3`、`recovery_healthy_records=1`、
`signal_api_scan_exit=1`、`direct_team_agent_scan_exit=1`、`production_*` 前后一致、
`fake_process_exit=natural`、`fixture_process_residue=0`。

## 4. 首审红项修复核验（§3 红证 → 现实现）

首审红证定位：`.team/watchdog.py` 原 `record_prod_guard()` 健康快照直接 `return False` 不落状态位，
去重只读上一条 fault 记录，健康间隔后的同指纹复发被误去重。当前工作树实现（v4.4 增量 + 状态机）：

- 连续同 fault：上一条为 fault 且 fingerprint 相同 → 去重；
- fault→healthy 真实恢复转换：仅当上一条同端口为 fault 时落一条 `state=healthy`
  （fingerprint=`<port>:`、`faults=[]`）作为最小跨进程恢复记忆；后续 healthy 轮 no-op；
- 复发：healthy 记录使去重条件（上一条 fault 且同指纹）不成立 → 再追加 fault；
- 不同 fault：指纹不同即追加。

独立序列 S3/S5 逐条复现了该语义（恢复落一条、复发再告警），红项不成立，修复成立。

## 5. launcher / watchdog 干净性核验（通过）

- **launcher 未执行**：`prod-daemon-launch.sh` 为未跟踪新增；PID 3393 非其子进程（本审未运行它）。
- **stdout/stderr 追加接管**：`exec nohup "$DAEMON" "$@" </dev/null >>"$PROD_LOG" 2>&1` ——追加、双流接管、
  无 kill/takeover/restart loop（`rg` 确认仅注释提及）。
- **无 token/authkey/argv 输出**：launcher 全文仅一条非可执行报错 echo；无 profile/env 读取。
- **watchdog 无 kill/signal API**：`rg os\.kill|send_signal|terminate\(|kill\(|SIGKILL|SIGTERM|pkill|killall`
  零命中；生产探针只用 lsof/ps/stat 只读。
- **Team Agent subprocess 全走绝对 `.team/ta`**：`TA = os.path.join(WS, ".team", "ta")`（WS 为绝对
  `/Volumes/nvme/Projects/远程Agent安卓`），send/status 均 `subprocess.run([TA, ...])`；无直接 `["team-agent", ...]`。
- **PID 3393 / :9900 前后指纹一致**：start `Mon Aug 10 01:23:19 2026 ./server/agentmirrord`、
  listener `p3393 cagentmirrord *:9900`、FD 1/2 → prod log inode 652421996，前后全部相同；
  生产 prod log（inode 652421996 / size 5107 / 01:23:19）与 escalation log
  （inode 652067937 / size 327 / 01:58:42）全程未变。
- **零残留**：本审三套隔离 harness（60010/60011、62000、shipped selftest）临时目录与假进程全部清理；
  现场仅存他席既有进程（w-fix-tsstatedir2 codex worker 及其隔离 fixture、am-cidle 隔离 fixture），非本审产物。

## 6. classification=unknown / followup_task=null 相称性复核（通过）

- evidence JSON `classification=unknown`、`followup_task=null`、`causal_gaps`（wait status 缺失、
  signal sender 缺失、旧 stderr 未落盘、无 Terminal/shell 关闭事件、宽 pkill 仅文本无执行证据、
  dirty build）与 REPORT §1/§6 逐项一致；缺失证据未升级成 product/environment，符合「没有证据就写 unknown」。
- evidence JSON 的 `rework` 记录（before_fix 红证复现 S1 true/S2 false/S3 复发被吞 → after_fix
  S1 true/S2 healthy 恢复 true/S3 复发 true）与 REPORT §9 及本审独立复跑一致。
- 第一次红证 `VERIFICATION.md` mtime 2026-08-10 02:44:54 早于 REPORT（02:54）与 selftest log（02:55），
  原文保留未篡改。

## 7. 终审结论

**PASS**。首审唯一红项（`record_prod_guard` 在「fault→healthy→同 fault 复发」下压掉复发告警）
回炉后已修复，六性契约（连续去重 / 健康不刷屏 / 复发再告警 / 复发后再去重 / 不同 fault 不吞 /
恢复仅落一条）经独立序列与 shipped selftest 双重实证全部成立。三条 acceptance 独立复跑 exit 0。
launcher 追加接管、watchdog 只读探针、无 kill/takeover/restart、Team Agent 全走绝对 `.team/ta`、
classification=unknown 相称性、生产零触碰与零残留均核验通过。
