# 生产 daemon PID 46081 生命周期审计

- 审计时间：2026-08-10 00:45–01:30 +0800；为解释二进制来源与恢复状态，仅把二进制元数据向前扩到 2026-08-09 21:43，把当前只读核验向后扩到 2026-08-10 02:33。
- 裁定：unknown
- 核心收窄窗：01:12:04–01:23:19（artifact 级）；严格按 PID 的最后存活证据是 00:58:19，因此严格 PID 边界仍为 00:58:19–01:23:19。
- 处置边界：未 signal、restart、attach、HTTP/WS 探测 PID 3393、TCP :9900 或任何真实 tmux；未运行生产 launcher；未读取 profile/provider env 或密钥原文。

## 1. 裁定

没有证据能证明 PID 46081 因产品内部错误退出，也没有证据能证明它被外部信号、Terminal 关闭、系统内存回收或宽 kill 终止。因此严格三选一为 unknown。

“旧实例没有 stdout/stderr 落盘”证明的是可观测性缺口，不证明退出原因。HANDOFF-leader-20260809.md:62 的宽 pkill 文本确实可能匹配生产路径，但没有实际执行证据，只能登记为潜在风险，不能升级成 environment。

## 2. 分钟级时间线

| 时间 +0800 | 可复核事实 | 证据与边界 |
|---|---|---|
| 2026-08-09 21:43:07 | server/agentmirrord 文件生成；构建元数据指向 fc00676c 且 modified=true。 | stat、go version -m。用于来源上下文；dirty build 使源码不能成为旧进程退出原因的精确证明。 |
| 00:45 | 冻结审计窗开始。 | FIELD.md:15-19。 |
| 00:58:19 | PID 46081 存活，PPID 46069，程序路径为工作区 server/agentmirrord，cwd=/Users/alauda，监听 :9900；同域清理只对 PID 19738 与 88840 发精确 SIGTERM。 | e2e/artifacts/feat-ts-wire-verify3/cleanup-followup.log:1-12、cleanup-proof.log:1-15。它是窗口内最后一条精确 PID 存活证据。 |
| 00:59:13 | feat-ts-wire 结构化 evidence 落盘，清理结论为 pass，生产 PID 46081 标为 present_untouched。 | .team/evidence/feat-ts-wire.json；artifact mtime。 |
| 01:12:04 | dogfood REPORT 定稿，清理反证记录“环境外两个 agentmirrord 仍运行、未收任何信号”。 | e2e/artifacts/dogfood/REPORT.md:703-728。该行不列 PID，故支持 01:12 核心收窄窗，但不能单独证明其中一个必是 46081。 |
| 01:18:24 | dogfood evidence/REPORT 提交。 | commit 54be8b9；提交只含 dogfood artifact/evidence。 |
| 01:18:54 | Team Agent 停止 w-dogfood2；结构化事件目标是 pane %29、old_pane_pid=20069。 | .team/logs/events.jsonl:5288-5291。未出现 PID 46081，也未给出任何宽 kill argv。 |
| 01:23:19 | 恢复实例 PID 3393 启动；prod log birth/mtime 同为 01:23:19。 | ps -p 3393；stat .team/logs/agentmirrord-prod.log。它给出旧实例已经消失的右边界，不给出旧实例的退出时刻。 |
| 02:27–02:33 | 隔离值守自测前后，PID 3393 启动指纹、:9900 listener、FD 1/2 对应日志 inode 全部不变。 | prod-guard-selftest.log；仅 ps/lsof/stat。 |

## 3. 正证据

| 证据 | 观察 | 能证明什么 |
|---|---|---|
| cleanup-followup.log:1-12 | 00:58:19 精确列出 46081/46069、程序路径、cwd、:9900。 | 46081 当时仍活；00:58 的精确 PID 清理没有误杀它。 |
| dogfood REPORT.md:703-728 | 隔离 daemon PID 9064 按 /tmp/dg1/dogfood-daemon 精确清理，两个外部 agentmirrord 仍活。 | dogfood 收尾的执行目标被隔离；不能由源码 grep 推断其杀了生产实例。 |
| events.jsonl:5288-5291 | 01:18:54 stop_agent 记录目标 pane PID 20069。 | 团队结构化事件没有把 46081 列作 stop 目标；不能反向证明底层绝无未记录副作用。 |
| 当前 ps/lsof/stat | PID 3393 为 agentmirrord、监听 *:9900；FD 1 与 2 都指向 prod log inode 652421996。 | 恢复实例当前确有日志接管；不解释旧实例退出。 |

## 4. 负证据

以下均是“未找到”，只能降低某些解释的证据强度，不能证明没有发生：

| 查询 | 有效结果 | 限制 |
|---|---|---|
| log show，processIdentifier == 46081，00:45–01:30，筛 error/fault/exit/signal/kill/crash/panic/fatal | exit 0，仅表头。 | 普通进程退出与外部 SIGKILL 未必进入 unified log。首次带空格时区的查询格式失败，已丢弃并用本机时区合法格式重跑；失败查询没有被当成空结果。 |
| log show，process == agentmirrord，同窗同类谓词 | exit 0，仅表头。 | daemon 的旧 stdout/stderr 在 Terminal，未进入此日志。 |
| DiagnosticReports 两目录窗口内文件元数据 | 0 个。 | Go panic/正常 os.Exit 未必生成 DiagnosticReport。 |
| pmset -g log 筛实际 Sleep/Wake/Shutdown/Restart | 0 条；kern.boottime 为 2026-08-09 15:25:05。 | 排除整机重启，不能排除 Terminal 窗口或 shell 生命周期。 |
| kernel/runningboardd 内存回收查询，限定 agentmirrord 或精确 PID | 0 条。 | 没有 memory-kill 正证据；日志覆盖不是完备证明。 |
| events.jsonl 精确搜索 46081 | 0 条。 | Team Agent 事件不记录任意宿主 shell 命令。 |
| Diagnostic/artifact/system log 中宽 pkill 实际 argv | 0 条。 | HANDOFF 文本仍是风险，但没有执行事实。 |

## 5. kill 路径逐项审计

| file:line / 事件 | 匹配面 | 实际执行证据 | 裁定 |
|---|---|---|---|
| HANDOFF-leader-20260809.md:62 | pkill -f server/agentmirrord 会匹配生产程序路径。 | 无；events、窗口内 artifact/system log 均无该 argv。 | 潜在高风险文本；无执行证据，不归 environment。 |
| e2e/run.sh:75-78 | 只匹配 $E2E_ROOT/bin/agentmirrord；生产路径为 server/agentmirrord。 | 窗口 artifact 无 e2e/run.sh 执行记录。 | 即使执行也不匹配已知生产路径。 |
| .team/nodes/test-app-dogfood/walkthrough-reference.sh:12-17 | pkill -f wk-daemon，仅匹配隔离二进制名。 | 无该脚本执行记录；dogfood 实际 artifact 记录的是 /tmp/dg1/dogfood-daemon 精确 PID 清理。 | 不匹配 agentmirrord。 |
| e2e/layer2.sh:52-63,136-149 | kill 的值来自刚启动的 $CLEANUP_PID，兜底 -9 仍是同一 PID。 | 窗口 artifact 无 layer2 执行记录。 | PID scoped，不是 basename 清理。 |
| server/internal/api/state_wiring_test.go:75-84 | 只 kill 自建隔离 pane 根的 descendantPIDs。 | 窗口无该测试执行记录。 | 不含宽 pkill。 |
| .team/nodes/fix-daemon-idle-cpu/CLAUDE.md:7-11 | 记载过手工 pkill 四个孤儿。 | evidence 提交时间为 2026-08-09 15:38:37，早于生产二进制 21:43 与 PID 46081 生命周期。 | 真实历史 kill，但时间上不能造成本事件。 |
| cleanup-proof.log:3-15 | 精确 SIGTERM PID 19738 与 88840。 | 已执行；00:58:19 随后仍证明 46081 present_untouched。 | 直接排除该清理为原因。 |
| dogfood REPORT.md:696-728 | 精确 PID 9064 / 绝对隔离路径。 | 已执行；报告随后仍记录两个外部 agentmirrord。 | 没有生产触碰证据。 |
| events.jsonl:5215-5216、5288-5289 | stop_agent 目标分别是 pane PID 87512、20069。 | 已执行的结构化 stop 事件。 | 没有 46081 目标或宽 kill argv。 |

可执行源码中没有 killall 命中。

## 6. 仍不可判的退出路径

- environment 候选：00:58 时 PID 46081 的 PPID 是 Terminal shell 46069；父 shell/窗口关闭或外部 signal 都可能终止它，但没有窗口关闭、signal 发送者或 wait status。
- product 候选：server/cmd/agentmirrord/main.go:206-221 可因 SIGINT/SIGTERM 或 Serve 返回退出；panic/runtime fatal 也可能退出。旧 stderr 未落盘，且二进制是 dirty build，不能从当前源码把候选升级为事实。
- 外部误杀候选：HANDOFF 的宽 pkill 能匹配，但没有执行证据。
- 系统回收候选：没有 memory pressure/jetsam/crash/reboot 正证据，但系统日志不是完备退出审计。

缺少决定性证据：旧进程 wait status、signal sender、旧 stderr、Terminal/shell 生命周期事件或可重复复现。故保持 unknown，不立产品 fix 案，followup_task=null。

## 7. 值守加固

- .team/prod-daemon-launch.sh:1-17：固定 exec 现有 server/agentmirrord，stdout 与 stderr 以追加方式接管到 .team/logs/agentmirrord-prod.log；不 kill、不 takeover、不循环重启、不输出 argv；本任务未执行该脚本。
- .team/watchdog.py:122-283：只用 lsof/ps/stat 检查端口 listener、agentmirrord 身份、进程存在、日志存在及 FD 1/2 path+inode 接管。
- .team/watchdog.py:244-265：异常以 PROD_GUARD JSON 行追加到 watchdog-escalation.log；上一条同 fingerprint 时不重复写；健康状态不写 escalation。
- .team/watchdog.py:295-304：既有 Team Agent send/status subprocess 统一走绝对工作区 .team/ta。
- .team/watchdog.py:316-319：每轮生产探针仅记录异常，不 restart、不 takeover、不 signal。

## 8. 自测与验收

| argv | exit | 证据 |
|---|---:|---|
| bash e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.sh > e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.log 2>&1 | 0 | prod-guard-selftest.log |
| bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/REPORT.md && python3 -m json.tool .team/evidence/audit-prod-daemon-lifecycle.json >/dev/null' | 0 | REPORT.md、audit-prod-daemon-lifecycle.json |
| bash -lc 'python3 -m py_compile .team/watchdog.py && bash -n .team/prod-daemon-launch.sh' | 0 | 本报告与最终验收回执 |
| bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.log' | 0 | prod-guard-selftest.log |

自测覆盖：

- healthy：高端口 55000 的假 agentmirrord，FD 1/2 都接临时日志，exit 0。
- missing listener：未占用高端口 55001，exit 1；同一故障第二轮 escalation_written=false。
- missing log：假进程仍在，但目标临时日志不存在，exit 1，明确列 missing_log 与 FD 1/2 未接管。
- 假进程 6 秒自然退出；看门狗/launcher 源码无 signal/kill API；没有 HTTP/WS 请求。
- PID 3393 的启动时刻、:9900 listener、FD 1/2 inode 前后完全一致；临时进程与目录零残留。

开发中首轮同形隔离自测 exit 1，原因是系统 Python 3.9 会在导入时求值 str | None 注解；加入 future annotations 后重跑 exit 0。失败未触碰生产面，也未发送清理信号。

---

## 9. 终审红项回炉记录（2026-08-10）

- 回炉席：w-fix-prodguard-dedupe（一次性，交件即退役）
- 回炉目标：修复 `VERIFICATION.md` §3/§4 唯一红项——`record_prod_guard()` 在
  「故障→健康→同故障复发」下压掉复发告警（根因：健康快照直接 `return False` 不落任何状态位，
  去重只读上一条 fault 记录，健康间隔后的同指纹复发被误判为连续故障而去重）。
- 红测先行（修复前，端口 60000 临时日志）：S1 fault 写、S2 healthy 不写、S3 同 fault 复发
  `escalation_written=false` 被吞，esc.log 仅 1 条 fault——红证复现成立，与 VERIFICATION §3 一致。

### 9.1 最小修复（`.team/watchdog.py` `record_prod_guard()`）

状态机改为带**健康恢复位**的去重：

1. **连续相同 fault 只落一条**：上一条为 fault 且 fingerprint 相同 → 去重，不写；
2. **fault→healthy 真实恢复转换落一条 `state=healthy` 记录**（fingerprint=`<port>:`，`faults=[]`）
   作为允许的最小跨进程恢复记忆——仅当上一条同端口记录为 fault 时落，后续 healthy 轮 no-op；
3. **健康不逐轮刷屏**：恢复转换只落一条，再健康不写；
4. **不同 fault 不得被吞**：指纹不同即追加。

`healthy` 恢复转换同样返回 `escalation_written=true`，与 CLI 既有字段语义一致。

### 9.2 契约级回归自测（`prod-guard-selftest.sh` 重写并复跑）

隔离高端口（55000/55001）+ 临时日志 + 假 agentmirrord（自然退出，零 signal），完整覆盖：

| 用例 | 端口 | 期望 `escalation_written` | 实测 | 契约 |
|---|---|---|---|---|
| healthy 基线（空日志） | 55000 | false | false | 健康不写 |
| fault 首报 missing_listener | 55001 | true | **true** | 首报追加 |
| fault 连续同故障 | 55001 | false | **false** | 连续去重 |
| healthy 恢复转换 | 55001 | true | **true** | 恢复落一条记忆 |
| healthy 恢复后再健康 | 55001 | false | **false** | 不刷屏 |
| **同 fault 复发（红项回归）** | 55001 | **true** | **true** | 复发必须再告警 |
| 复发后连续同故障 | 55001 | false | **false** | 复发后再去重 |
| 不同 fault missing_log | 55000 | true | **true** | 不同故障不吞 |

esc.log 断言：fault 记录 3 条、healthy 恢复记录 1 条；exit 码 healthy=0/fault=1 全对；
`rg` 扫描无 signal API、无直调 team-agent；PID 3393/:9900/FD 1/2 前后指纹一致。

### 9.3 生产只读核验（回炉全程）

- PID 3393 `start Mon Aug 10 01:23:19 2026 ./server/agentmirrord`、`:9900` listener
  `p3393 cagentmirrord *:9900`、FD 1/2 → prod log inode 652421996 前后完全一致；
- `.team/logs/watchdog-escalation.log` inode 652067937 / size 327 / mtime 2026-08-10 01:58:42
  回炉前后未变（我方测试全部指向临时日志）；
- 未运行 prod launcher、未 signal/restart/attach/HTTP/WS、未读 profile/env 密钥原文；
- `VERIFICATION.md` 红证原文保留未篡改。

### 9.4 验收复跑

| argv | exit |
|---|---:|
| `python3 -m py_compile .team/watchdog.py && bash -n .team/prod-daemon-launch.sh` | 0 |
| `bash e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.sh > e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.log 2>&1` | 0 |
| `bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/REPORT.md && python3 -m json.tool .team/evidence/audit-prod-daemon-lifecycle.json >/dev/null'` | 0 |
| `bash -lc 'test -s e2e/artifacts/audit-prod-daemon-lifecycle/prod-guard-selftest.log'` | 0 |

回炉结论：红项已修复，契约四性（连续去重 / 复发再告警 / 健康不刷屏 / 不同故障不吞）全部实证。
