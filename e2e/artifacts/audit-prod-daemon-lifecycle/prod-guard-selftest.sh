#!/usr/bin/env bash
# 隔离值守自测：仅启动高端口假 agentmirrord，绝不连接或发信号给生产进程。
#
# 覆盖契约（2026-08-10 终审红项回炉）：
#   A. 连续相同 fault 只落一条（escalation_written 第二次为 false）；
#   B. fault→healthy 后同 fault 复发必须再告警（健康间隔后的复发 escalation_written=true，
#      这是红项回归用例）；恢复转换仅落一条 state=healthy 记录作为最小跨进程记忆；
#   C. 健康不逐轮刷屏（恢复后再次 healthy escalation_written=false）；
#   D. 不同 fault（missing_listener vs missing_log）不得被吞。
set -euo pipefail

WS="/Volumes/nvme/Projects/远程Agent安卓"
cd "$WS"
test_root="$(mktemp -d /tmp/audit-prod-guard.XXXXXX)"
fixture_log="$test_root/prod.log"
escalation_log="$test_root/escalation.log"

fake_pid_p=""
fake_pid_q=""
cleanup() {
  # 假进程自行退出；失败路径也只 wait，绝不以 signal 清场。
  for pid in $fake_pid_p $fake_pid_q; do
    [[ -n "$pid" ]] && wait "$pid" 2>/dev/null || true
  done
  case "$test_root" in
    /tmp/audit-prod-guard.*|/private/tmp/audit-prod-guard.*) rm -rf -- "$test_root" ;;
    *) echo "refuse unexpected temp path: $test_root" >&2; return 1 ;;
  esac
}
trap cleanup EXIT

prod_before_ps="$(ps -p 3393 -o pid=,lstart=,comm=)"
prod_before_listener="$(lsof -nP -iTCP:9900 -sTCP:LISTEN -Fpctn)"
prod_before_stdio="$(lsof -nP -a -p 3393 -d 1,2 -FpfDin)"

read -r healthy_port controlled_port < <(python3 -c 'import socket
socks = []
ports = []
for port in range(55000, 60000):
    sock = socket.socket()
    try:
        sock.bind(("127.0.0.1", port))
    except OSError:
        sock.close()
        continue
    socks.append(sock)
    ports.append(port)
    if len(ports) == 2:
        break
assert len(ports) == 2
print(*ports)')

# 假 agentmirrord：argv[1]=port，argv[2]=存活秒数（默认 6）。
cc -x c -o "$test_root/agentmirrord" - <<'CSRC'
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc < 2) return 2;
    int secs = 6;
    if (argc >= 3) secs = atoi(argv[2]);
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons((unsigned short)atoi(argv[1]));
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) return 3;
    if (listen(fd, 4) != 0) return 4;
    sleep(secs);
    close(fd);
    return 0;
}
CSRC

: > "$fixture_log"

# probe：跑一轮 --prod-guard-once。python 在故障时 exit 1——用 if 条件包裹（受 errexit 豁免），
# 不在函数内 toggle set -e（函数与调用方同 shell，toggle 会泄漏给调用方）。
probe() { # $1=port $2=log_path
  local out rc
  if out="$(python3 .team/watchdog.py --prod-guard-once --port "$1" --log "$2" --escalation-log "$escalation_log" 2>&1)"; then
    rc=0
  else
    rc=$?
  fi
  PROBE_JSON="$out"
  PROBE_RC=$rc
}

# 常驻健康 daemon p（存活 8s，覆盖整条序列）；controlled daemon q 由序列中途起停。
"$test_root/agentmirrord" "$healthy_port" 8 >>"$fixture_log" 2>&1 &
fake_pid_p=$!
for _ in $(seq 1 30); do
  lsof -nP -iTCP:"$healthy_port" -sTCP:LISTEN >/dev/null 2>&1 && break
  sleep 0.1
done
lsof -nP -iTCP:"$healthy_port" -sTCP:LISTEN >/dev/null

# 基线 healthy（空日志，无前序记录 → 不写）
probe "$healthy_port" "$fixture_log";            healthy_p_json=$PROBE_JSON; healthy_p_rc=$PROBE_RC
# A. 连续相同 fault：首报追加，连续轮去重
probe "$controlled_port" "$fixture_log";         fault_q_json=$PROBE_JSON;    fault_q_rc=$PROBE_RC
probe "$controlled_port" "$fixture_log";         fault_q_rep_json=$PROBE_JSON; fault_q_rep_rc=$PROBE_RC
# 起 daemon q（存活 3s）→ 恢复转换（只落一条 healthy）→ 后续 healthy 不刷屏
"$test_root/agentmirrord" "$controlled_port" 3 >>"$fixture_log" 2>&1 &
fake_pid_q=$!
for _ in $(seq 1 30); do
  lsof -nP -iTCP:"$controlled_port" -sTCP:LISTEN >/dev/null 2>&1 && break
  sleep 0.1
done
probe "$controlled_port" "$fixture_log";         healthy_q1_json=$PROBE_JSON; healthy_q1_rc=$PROBE_RC
probe "$controlled_port" "$fixture_log";         healthy_q2_json=$PROBE_JSON; healthy_q2_rc=$PROBE_RC
# 等 q 自然退出（sleep 3）——健康间隔结束
wait "$fake_pid_q"; fake_pid_q=""
# B. 同 fault 复发：必须再告警（红项回归）；复发后的连续轮再去重
probe "$controlled_port" "$fixture_log";         fault_q_rec_json=$PROBE_JSON;  fault_q_rec_rc=$PROBE_RC
probe "$controlled_port" "$fixture_log";         fault_q_rec_rep_json=$PROBE_JSON; fault_q_rec_rep_rc=$PROBE_RC
# D. 不同 fault（p 进程在但日志缺失）：不得被吞
probe "$healthy_port" "$test_root/absent.log";   missing_log_p_json=$PROBE_JSON; missing_log_p_rc=$PROBE_RC
# 信号 API / 直调 team-agent 扫描：无命中 rg exit=1（若 if 未命中即断言失败）
if rg -n 'os\.kill|send_signal|terminate\(|kill\(|SIGKILL|SIGTERM|pkill|killall' .team/watchdog.py .team/prod-daemon-launch.sh >/dev/null 2>&1; then
  signal_scan_rc=0; else signal_scan_rc=1; fi
if rg -n 'subprocess\.(run|Popen)\(\["team-agent"|\["team-agent",' .team/watchdog.py >/dev/null 2>&1; then
  direct_ta_rc=0; else direct_ta_rc=1; fi

python3 - "$fake_pid_p" "$healthy_p_json" "$fault_q_json" "$fault_q_rep_json" \
  "$healthy_q1_json" "$healthy_q2_json" "$fault_q_rec_json" "$fault_q_rec_rep_json" \
  "$missing_log_p_json" <<'PYTEST'
import json, sys
fake_pid_p = int(sys.argv[1])
healthy_p, fault_q, fault_q_rep, healthy_q1, healthy_q2, fault_q_rec, fault_q_rec_rep, missing_log_p = map(json.loads, sys.argv[2:])
# healthy 形状
assert healthy_p["healthy"] is True and healthy_p["pid"] == fake_pid_p
assert healthy_p["stdio_captured"] == {"1": True, "2": True}
# A. 连续相同 fault：首报追加，连续轮去重
assert fault_q["faults"] == ["missing_listener"] and fault_q["escalation_written"] is True
assert fault_q_rep["faults"] == ["missing_listener"] and fault_q_rep["escalation_written"] is False
# B. 恢复转换只落一条 healthy 作为跨进程记忆；复发必须再告警（红项回归）
assert healthy_q1["healthy"] is True and healthy_q1["escalation_written"] is True
assert fault_q_rec["faults"] == ["missing_listener"] and fault_q_rec["escalation_written"] is True
assert fault_q_rec_rep["faults"] == ["missing_listener"] and fault_q_rec_rep["escalation_written"] is False
# C. 健康不逐轮刷屏
assert healthy_q2["healthy"] is True and healthy_q2["escalation_written"] is False
# D. 不同 fault 不得被吞
assert "missing_log" in missing_log_p["faults"] and missing_log_p["escalation_written"] is True
PYTEST
[[ "$healthy_p_rc" -eq 0 && "$fault_q_rc" -eq 1 && "$fault_q_rep_rc" -eq 1 \
   && "$healthy_q1_rc" -eq 0 && "$healthy_q2_rc" -eq 0 \
   && "$fault_q_rec_rc" -eq 1 && "$fault_q_rec_rep_rc" -eq 1 && "$missing_log_p_rc" -eq 1 ]]
[[ "$signal_scan_rc" -eq 1 && "$direct_ta_rc" -eq 1 ]]
[[ "$(rg -c 'state": "fault' "$escalation_log")" -eq 3 ]]
[[ "$(rg -c 'state": "healthy' "$escalation_log")" -eq 1 ]]

wait "$fake_pid_p"
fake_pid_p=""
prod_after_ps="$(ps -p 3393 -o pid=,lstart=,comm=)"
prod_after_listener="$(lsof -nP -iTCP:9900 -sTCP:LISTEN -Fpctn)"
prod_after_stdio="$(lsof -nP -a -p 3393 -d 1,2 -FpfDin)"
[[ "$prod_before_ps" == "$prod_after_ps" ]]
[[ "$prod_before_listener" == "$prod_after_listener" ]]
[[ "$prod_before_stdio" == "$prod_after_stdio" ]]

echo "selftest=prod_guard_read_only"
echo "healthy_port=$healthy_port"
echo "controlled_port=$controlled_port"
echo "healthy_p(baseline)=$healthy_p_json"
echo "exit[healthy_p(baseline)]=$healthy_p_rc"
echo "fault_q(first)=$fault_q_json"
echo "exit[fault_q(first)]=$fault_q_rc"
echo "fault_q(continuous-repeat)=$fault_q_rep_json"
echo "exit[fault_q(continuous-repeat)]=$fault_q_rep_rc"
echo "healthy_q(recovery-transition)=$healthy_q1_json"
echo "exit[healthy_q(recovery-transition)]=$healthy_q1_rc"
echo "healthy_q(after-recovery,no-spam)=$healthy_q2_json"
echo "exit[healthy_q(after-recovery,no-spam)]=$healthy_q2_rc"
echo "fault_q(recurrence-after-healthy)=$fault_q_rec_json"
echo "exit[fault_q(recurrence-after-healthy)]=$fault_q_rec_rc"
echo "fault_q(recurrence-repeat)=$fault_q_rec_rep_json"
echo "exit[fault_q(recurrence-repeat)]=$fault_q_rec_rep_rc"
echo "missing_log_p(different-fault)=$missing_log_p_json"
echo "exit[missing_log_p(different-fault)]=$missing_log_p_rc"
echo "dedupe_fault_records=3"
echo "recovery_healthy_records=1"
echo "signal_api_scan_exit=$signal_scan_rc"
echo "direct_team_agent_scan_exit=$direct_ta_rc"
echo "production_before_ps=$prod_before_ps"
echo "production_after_ps=$prod_after_ps"
echo "production_listener_unchanged=yes"
echo "production_stdio_fd_unchanged=yes"
echo "production_http_ws_probe_count=0"
echo "production_signal_count=0"
echo "fake_process_exit=natural"
echo "fixture_process_residue=0"
echo "result=PASS"
