#!/usr/bin/env bash
# 069 静默经济：没人在菜单里空跑 60s，量 CPU 与 tmux 调用次数。
# 隔离 socket，不碰 default tmux。ps 只取 pid/comm/%cpu。
set -eu
BASE=/tmp/rf-ver
REAL_TMUX="$(command -v tmux)"
ROOT="/Volumes/nvme/Projects/远程Agent安卓"
unset TMUX
unset TMUX_TMPDIR
rm -rf "$BASE"
mkdir -p "$BASE/bin" "$BASE/tmux" "$BASE/state" "$BASE/wrap"

COUNT="$BASE/tmux.calls"
: >"$COUNT"
cat > "$BASE/wrap/tmux" <<EOF
#!/bin/sh
echo 1 >> '$COUNT'
exec '$REAL_TMUX' "\$@"
EOF
chmod +x "$BASE/wrap/tmux"

printf '%s\n' '#!/bin/sh' 'exec sleep 600' > "$BASE/bin/grok"
chmod +x "$BASE/bin/grok"

SOCK="$BASE/tmux/sock"
"$REAL_TMUX" -S "$SOCK" new-session -d -s rfidle -n rf-idle "exec -a $BASE/bin/grok sleep 600"
SESS="$("$REAL_TMUX" -S "$SOCK" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS" | grep -qx rfidle || { echo "FAIL 自检：会话不在隔离 socket got='$SESS'"; exit 1; }
"$REAL_TMUX" -S "$SOCK" select-pane -t rfidle:0.0 -T "idle keep - grok"

DAEMON="$BASE/agentmirrord"
if [ -x /tmp/rf-advisor/agentmirrord ]; then
  cp /tmp/rf-advisor/agentmirrord "$DAEMON"
else
  ( cd "$ROOT/server" && go build -o "$DAEMON" ./cmd/agentmirrord )
fi

PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
[ "$PORT" != "9900" ] || { echo "FAIL 拒绝 9900"; exit 1; }

export AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$BASE/tmux"
export AGENTMIRROR_STATE_DIR="$BASE/state"
export AGENTMIRROR_TOKEN="rf-ver-token"
export AGENTMIRROR_LISTEN="127.0.0.1:${PORT}"
export AGENTMIRROR_LIST_INTERVAL="2s"
unset TS_AUTHKEY
export PATH="$BASE/wrap:$PATH"

DAEMON_PID=""
cleanup() {
  if [ -n "${DAEMON_PID:-}" ]; then
    kill "$DAEMON_PID" 2>/dev/null || true
    sleep 0.3
    kill -9 "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
  fi
  "$REAL_TMUX" -S "$SOCK" kill-server 2>/dev/null || true
}
trap cleanup EXIT

: >"$COUNT"
"$DAEMON" >"$BASE/daemon.log" 2>&1 &
DAEMON_PID=$!
sleep 0.2
kill -0 "$DAEMON_PID" 2>/dev/null || { echo "FAIL daemon 立刻退出"; exit 1; }
READY=0
for _ in $(seq 1 40); do
  if python3 -c "import socket;s=socket.socket();s.settimeout(0.2);s.connect(('127.0.0.1',int('$PORT')));s.close()" 2>/dev/null; then
    READY=1; break
  fi
  sleep 0.2
done
[ "$READY" = 1 ] || { echo "FAIL daemon 未监听"; exit 1; }

# 没人在菜单：不连 WS、不 list、不 level2_subscribe。listingLoop/level2Loop 都应 park。
sleep 0.5
T0=$(wc -l < "$COUNT" | tr -d ' ')
echo "idle_start pid=$DAEMON_PID tmux_calls_t0=$T0 (want 0 after start; no client)"

CPU_SUM=0
CPU_MAX=0
CPU_MIN=999
N=0
SAMPLES=""
for i in $(seq 1 60); do
  LINE=$(ps -p "$DAEMON_PID" -o pid=,comm=,%cpu=)
  CPU=$(echo "$LINE" | awk '{print $NF}')
  COMM=$(echo "$LINE" | awk '{print $2}')
  # 只记 comm，不取 argv
  SAMPLES="${SAMPLES}${i} comm=${COMM} cpu=${CPU}\n"
  CPU_SUM=$(python3 -c "print($CPU_SUM + float('$CPU'))")
  CPU_MAX=$(python3 -c "print(max($CPU_MAX, float('$CPU')))")
  CPU_MIN=$(python3 -c "print(min($CPU_MIN, float('$CPU')))")
  N=$((N+1))
  sleep 1
done
T1=$(wc -l < "$COUNT" | tr -d ' ')
DELTA=$((T1 - T0))
CPU_MEAN=$(python3 -c "print(round($CPU_SUM / $N, 4))")

echo "CPU_mean=$CPU_MEAN CPU_max=$CPU_MAX CPU_min=$CPU_MIN n=$N"
echo "tmux_calls_t0=$T0 tmux_calls_t1=$T1 tmux_delta=$DELTA"
if [ "$DELTA" -eq 0 ]; then
  echo "tmux_verdict=GREEN zero polling (delta=0)"
else
  echo "tmux_verdict=RED polling tmux_delta=$DELTA"
fi
python3 - <<PY
mean=float("$CPU_MEAN"); mx=float("$CPU_MAX")
print("CPU_verdict=GREEN near-idle" if mean < 1.0 and mx < 5.0 else "CPU_verdict=RED mean=%.4f max=%.4f" % (mean, mx))
PY
echo "--- samples (comm/%cpu only) ---"
printf '%b' "$SAMPLES"
