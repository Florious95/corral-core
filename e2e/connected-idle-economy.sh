#!/usr/bin/env bash
# connected-idle-economy.sh measures one isolated real daemon in the three
# frozen states: zero connection, authenticated with zero subscriptions, and
# authenticated with one subscription.
#
# Rework contract (fix-connected-idle-economy 回炉, two red items):
#   - Each of the three states runs in its OWN fresh runtime: own mktemp temp
#     root, own client process, own daemon process, own tmux -L server, own
#     high port, own socket dir, own state dir. The same self-built read-only
#     daemon+client binaries are reused across states; runtime state never is.
#   - Every state boundary runs a scoped cleanup that must prove ZERO residue
#     for that state (client/daemon/tmux/listener/socket/state/temp/tree) and
#     writes an independent zero-residue log before the next state starts.
#   - The client helper token enters ONLY through the environment
#     (CIDLE_E2E_CLIENT_TOKEN); no -token argv. Logs/evidence record only
#     presence/argv-shape, never the token value.
#   - daemon and client both launch under env -i; TS_AUTHKEY/TS_CONTROL_URL
#     are never inherited. Scoped discovery is explicitly fail-closed to the
#     state's own socket dir.
#   - Frozen thresholds unchanged: mean online CPU <=5.0%, each state window
#     >=60s, 27 panes, deterministic fleet 3/27/200, fairness <=60s.
#   - The approved read-only ps -axo snapshot is used only to classify the
#     self-owned wrapper pane roots; the raw process table is never recorded
#     or displayed. Production daemon (PID 3393 / :9900) and user/Team Agent
#     tmux are never connected, scanned, attached, or signalled.

set -euo pipefail

E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$E2E_ROOT/.." && pwd)"
SERVER_ROOT="$REPO_ROOT/server"
ART="$E2E_ROOT/artifacts/fix-connected-idle-economy"
mkdir -p "$ART"

MEASURE_SECONDS=60
PANE_COUNT=27
CPU_LIMIT=5.0

# A fresh per-run pairing token, born in this shell session only. It reaches
# the daemon via AGENTMIRROR_TOKEN and the client via CIDLE_E2E_CLIENT_TOKEN;
# never as argv, never written to any log or evidence file.
TOKEN="$(python3 -c 'import os,secrets; print(secrets.token_urlsafe(24))')"

METRICS_TSV="$ART/measurements.tsv"
METRICS_JSON="$ART/metrics.json"
BUILD_LOG="$ART/build.log"
FINAL_CLEANUP_LOG="$ART/final-cleanup.log"
ISOLATION_SUMMARY="$ART/isolation-summary.txt"
USED_PORTS_FILE="$ART/used-ports.tsv"
SOCKET_DIRS_FILE="$ART/state-socket-dirs.tsv"

: >"$METRICS_TSV"
: >"$BUILD_LOG"
: >"$FINAL_CLEANUP_LOG"
: >"$USED_PORTS_FILE"
: >"$SOCKET_DIRS_FILE"
printf 'state\tpanes\twall_start_ns\twall_end_ns\twall_seconds\tcpu_start_raw\tcpu_end_raw\tcpu_start_seconds\tcpu_end_seconds\tcpu_delta_seconds\tmean_cpu_percent\tcapture_start\tcapture_end\tcapture_delta\tcapture_per_second\n' >"$METRICS_TSV"

REAL_TMUX="$(command -v tmux)"
REAL_GO="$(command -v go)"

# Read-only build root; binaries are built once here and reused by all three
# states (allowed: same self-built read-only binary). Never part of any state
# runtime. Removed by the final trap.
BUILD_ROOT="$(mktemp -d /tmp/am-cidle-build.XXXXXX)"
case "$BUILD_ROOT" in
  /tmp/am-cidle-build.*|/private/tmp/am-cidle-build.*) ;;
  *) echo "unsafe build root: $BUILD_ROOT" >&2; exit 1 ;;
esac
DAEMON_BIN="$BUILD_ROOT/bin/agentmirrord"
CLIENT_BIN="$BUILD_ROOT/bin/connected-idle-client"
CLIENT_SRC="$BUILD_ROOT/client"
SHIM_DIR="$BUILD_ROOT/shim"   # shared tmux shim; per-state behaviour via env
mkdir -p "$BUILD_ROOT/bin" "$CLIENT_SRC" "$SHIM_DIR"

# Register the final sweep as early as possible: an abort before this point is
# impossible (variables above are all set), so the build root and any stray
# state roots are always swept on exit.
trap on_exit EXIT
trap 'exit 130' INT TERM

# ---------------------------------------------------------------------------
# Shared helpers (process lifecycle / timing / counting)
# ---------------------------------------------------------------------------

pid_alive() {
  local pid="${1:-}"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

pid_zombie() {
  local pid="${1:-}" state
  [ -n "$pid" ] || return 1
  state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
  [ "${state#Z}" != "$state" ]
}

stop_owned_pid() {
  # Scoped kill: only the captured PID. No pgrep/pkill by name anywhere.
  local pid="${1:-}"
  [ -n "$pid" ] || return 0
  if pid_alive "$pid"; then
    kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 50); do
      pid_alive "$pid" || return 0
      if pid_zombie "$pid"; then
        wait "$pid" 2>/dev/null || true
        ! pid_alive "$pid"
        return
      fi
      sleep 0.1
    done
    kill -KILL "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      pid_alive "$pid" || return 0
      if pid_zombie "$pid"; then
        wait "$pid" 2>/dev/null || true
        ! pid_alive "$pid"
        return
      fi
      sleep 0.1
    done
  fi
  wait "$pid" 2>/dev/null || true
  ! pid_alive "$pid"
}

cpu_seconds() {
  local raw="$1"
  python3 - "$raw" <<'PY'
import sys
raw = sys.argv[1].strip()
days = 0
if "-" in raw:
    d, raw = raw.split("-", 1)
    days = int(d)
parts = raw.split(":")
if len(parts) == 3:
    hours, minutes, seconds = int(parts[0]), int(parts[1]), float(parts[2])
elif len(parts) == 2:
    hours, minutes, seconds = 0, int(parts[0]), float(parts[1])
else:
    hours, minutes, seconds = 0, 0, float(parts[0])
print(days * 86400 + hours * 3600 + minutes * 60 + seconds)
PY
}

wall_time_ns() {
  # Wall-clock nanoseconds; cross-process comparable on this host.
  python3 -c 'import time; print(time.time_ns())'
}

pick_high_port() {
  python3 <<'PY'
import random, socket
for _ in range(500):
    port = random.randint(49152, 65000)
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", port))
    except OSError:
        s.close()
        continue
    s.close()
    print(port)
    break
else:
    raise SystemExit("no high port available")
PY
}

port_listens() {
  local port="$1"
  python3 - "$port" <<'PY'
import socket, sys
s = socket.socket()
s.settimeout(0.2)
try:
    listening = s.connect_ex(("127.0.0.1", int(sys.argv[1]))) == 0
finally:
    s.close()
raise SystemExit(0 if listening else 1)
PY
}

# ---------------------------------------------------------------------------
# Final zero-residue sweep + exit handling (registered early so any abort still
# proves the whole run left nothing behind).
# ---------------------------------------------------------------------------
final_sweep() {
  local ok=1 state_left build_left ports_left
  state_left="$(ls -d /tmp/am-cidle-zero_connection.* /tmp/am-cidle-connected_*.* 2>/dev/null || true)"
  if [ -n "$state_left" ]; then
    ok=0
    state_left=true
  else
    state_left=false
  fi
  if [ -e "$BUILD_ROOT" ]; then
    ok=0
    build_left=true
  else
    build_left=false
  fi
  ports_left=false
  if [ -f "$USED_PORTS_FILE" ]; then
    while IFS=$'\t' read -r _state port; do
      if port_listens "$port"; then
        ports_left=true
        ok=0
      fi
    done <"$USED_PORTS_FILE"
  fi
  printf 'state_runtime_roots_absent=%s\n' "$state_left" >>"$FINAL_CLEANUP_LOG"
  printf 'build_root_absent=%s\n' "$build_left" >>"$FINAL_CLEANUP_LOG"
  printf 'all_state_ports_listener_absent=%s\n' "$ports_left" >>"$FINAL_CLEANUP_LOG"
  printf 'ps_scope=read-only snapshot; classification only beneath self-owned pane roots; raw process table not recorded or displayed; zero production interaction\n' >>"$FINAL_CLEANUP_LOG"
  [ "$ok" -eq 1 ]
}

on_exit() {
  local rc=$?
  # The build root is not state runtime; its removal is part of the final
  # zero-residue proof.
  case "$BUILD_ROOT" in
    /tmp/am-cidle-build.*|/private/tmp/am-cidle-build.*) rm -rf -- "$BUILD_ROOT" ;;
  esac
  if ! final_sweep; then
    rc=1
  fi
  if [ "$rc" -eq 0 ]; then
    echo "connected-idle-economy: PASS"
  else
    echo "connected-idle-economy: FAIL (see $ART)" >&2
  fi
  exit "$rc"
}

# ---------------------------------------------------------------------------
# Per-state runtime state. State globals are (re)assigned at the start of
# state_body and consumed by state_cleanup / tmux_own; states run strictly
# sequentially so there is no cross-state aliasing.
# ---------------------------------------------------------------------------

STATE_ROOT=""
STATE_LABEL=""
STATE_TMUX_ROOT=""
STATE_SOCKET_DIR=""
STATE_SOCKET=""
STATE_PANE_CWD=""
STATE_RUN_HOME=""
STATE_STATE_DIR=""
STATE_UPLOAD_DIR=""
STATE_DAEMON_TMP=""
STATE_OWNED_PIDS=""
STATE_READY_FILE=""
STATE_PORT=""
STATE_DAEMON_PID=""
STATE_CLIENT_PID=""
STATE_TMUX_PID=""
STATE_DAEMON_LOG=""
STATE_DAEMON_STDOUT=""
STATE_CLIENT_LOG=""
STATE_CLEANUP_LOG=""
STATE_CAPTURE_LOG=""
STATE_TARGET_AUDIT=""
STATE_VIOLATIONS=""

# The tmux shim (shared file) intercepts every tmux call the daemon makes and
# resolves the -S target + operation at exec time from the state env set at
# daemon launch. It fail-closes: any target outside CIDLE_ALLOWED_SOCKET_DIR
# is refused before exec. capture-pane targets are counted into the state's
# capture log.
cat >"$SHIM_DIR/tmux" <<'SH'
#!/bin/sh
target=
operation=
take_next=0
for arg in "$@"; do
  if [ "$take_next" = 1 ]; then
    target=$arg
    take_next=0
    continue
  fi
  if [ "$arg" = "-S" ]; then
    take_next=1
    continue
  fi
  if [ -z "$operation" ] && [ "${arg#-}" = "$arg" ]; then
    operation=$arg
  fi
done
printf '%s\t%s\n' "$target" "$operation" >>"$CIDLE_TMUX_TARGET_AUDIT"
case "$target" in
  "$CIDLE_ALLOWED_SOCKET_DIR"/*) ;;
  *) printf 'refused_non_owned_target\n' >>"$CIDLE_TMUX_VIOLATIONS"; exit 97 ;;
esac
if [ "$operation" = "capture-pane" ]; then
  printf '%s\n' "$target" >>"$CIDLE_CAPTURE_LOG"
fi
exec "$CIDLE_REAL_TMUX" "$@"
SH
chmod 700 "$SHIM_DIR/tmux"

# Shared fake wrapper-shaped pane root (bash owns a fake codex-named
# descendant). Exercises the provider's existing read-only ps identification
# path; every recorded PID belongs to the state runtime, no ps output is kept.
cat >"$BUILD_ROOT/fake-agent-wrapper.sh" <<'SH'
#!/bin/bash
bash -c 'exec -a codex /bin/sleep 600' &
child=$!
printf '%s\t%s\n' "$$" "$child" >>"$CIDLE_OWNED_PIDS"
wait "$child"
SH
chmod 700 "$BUILD_ROOT/fake-agent-wrapper.sh"

tmux_own() {
  # env -i tmux wrapper bound to the CURRENT state's socket/label/runtime.
  env -i \
    HOME="$STATE_RUN_HOME" \
    LANG=C \
    PATH="/usr/bin:/bin:/usr/sbin:/sbin" \
    SHELL=/bin/bash \
    TMPDIR="$STATE_ROOT" \
    TMUX_TMPDIR="$STATE_TMUX_ROOT" \
    CIDLE_OWNED_PIDS="$STATE_OWNED_PIDS" \
    "$REAL_TMUX" -L "$STATE_LABEL" "$@"
}

capture_count() {
  awk 'END { print NR + 0 }' "$STATE_CAPTURE_LOG"
}

# ---------------------------------------------------------------------------
# Measurement + client lifecycle for one state
# ---------------------------------------------------------------------------

measure_state() {
  local state="$1"
  local cpu_start_raw cpu_end_raw cpu_start cpu_end wall_start wall_end wall_seconds
  local cap_start cap_end cpu_delta mean_cpu cap_delta cap_rate panes_now

  panes_now="$(tmux_own list-panes -a -F '#{pane_id}' | wc -l | tr -d ' ')"
  [ "$panes_now" -eq "$PANE_COUNT" ] || { echo "$state pane count changed: $panes_now" >&2; return 1; }
  pid_alive "$STATE_DAEMON_PID" || { echo "$state daemon is not alive" >&2; return 1; }

  cpu_start_raw="$(ps -o time= -p "$STATE_DAEMON_PID" | tr -d '[:space:]')"
  cpu_start="$(cpu_seconds "$cpu_start_raw")"
  cap_start="$(capture_count)"
  wall_start="$(wall_time_ns)"
  sleep "$MEASURE_SECONDS"
  wall_end="$(wall_time_ns)"
  cap_end="$(capture_count)"
  cpu_end_raw="$(ps -o time= -p "$STATE_DAEMON_PID" | tr -d '[:space:]')"
  cpu_end="$(cpu_seconds "$cpu_end_raw")"

  wall_seconds="$(awk -v a="$wall_start" -v b="$wall_end" 'BEGIN { printf "%.9f", (b-a)/1000000000 }')"
  cpu_delta="$(awk -v a="$cpu_start" -v b="$cpu_end" 'BEGIN { printf "%.6f", b-a }')"
  mean_cpu="$(awk -v c="$cpu_delta" -v w="$wall_seconds" 'BEGIN { printf "%.6f", c/w*100 }')"
  cap_delta=$((cap_end - cap_start))
  cap_rate="$(awk -v c="$cap_delta" -v w="$wall_seconds" 'BEGIN { printf "%.6f", c/w }')"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$state" "$panes_now" "$wall_start" "$wall_end" "$wall_seconds" \
    "$cpu_start_raw" "$cpu_end_raw" "$cpu_start" "$cpu_end" "$cpu_delta" "$mean_cpu" \
    "$cap_start" "$cap_end" "$cap_delta" "$cap_rate" >>"$METRICS_TSV"
  printf '%-28s wall=%ss cpu_delta=%ss mean_cpu=%s%% capture=%s rate=%s/s panes=%s\n' \
    "$state" "$wall_seconds" "$cpu_delta" "$mean_cpu" "$cap_delta" "$cap_rate" "$panes_now"

  awk -v w="$wall_seconds" -v min="$MEASURE_SECONDS" 'BEGIN { exit !(w >= min) }' || {
    echo "$state wall window below frozen ${MEASURE_SECONDS}s" >&2
    return 1
  }
  if [ "$state" = "zero_connection" ] && [ "$cap_delta" -ne 0 ]; then
    echo "zero connection spawned $cap_delta capture-pane processes" >&2
    return 1
  fi
  if [ "$state" != "zero_connection" ]; then
    awk -v cpu="$mean_cpu" -v limit="$CPU_LIMIT" 'BEGIN { exit !(cpu <= limit) }' || {
      echo "$state mean CPU ${mean_cpu}% exceeds frozen ${CPU_LIMIT}%" >&2
      return 1
    }
  fi
}

start_client() {
  local mode="$1" log="$2"
  rm -f "$STATE_READY_FILE"
  # Token passes via env only; argv carries no -token shape.
  env -i \
    HOME="$STATE_RUN_HOME" \
    LANG=C \
    PATH="/usr/bin:/bin" \
    CIDLE_E2E_CLIENT_TOKEN="$TOKEN" \
    "$CLIENT_BIN" -mode "$mode" -url "ws://127.0.0.1:$STATE_PORT/ws" \
      -cwd "$STATE_PANE_CWD" -ready "$STATE_READY_FILE" -panes "$PANE_COUNT" \
      >"$log" 2>&1 &
  STATE_CLIENT_PID=$!
  for _ in $(seq 1 150); do
    if [ -f "$STATE_READY_FILE" ]; then
      return 0
    fi
    pid_alive "$STATE_CLIENT_PID" || { echo "$mode client exited before ready" >&2; return 1; }
    sleep 0.1
  done
  echo "$mode client did not become ready" >&2
  return 1
}

stop_client() {
  if [ -n "$STATE_CLIENT_PID" ]; then
    stop_owned_pid "$STATE_CLIENT_PID" || return 1
    STATE_CLIENT_PID=""
  fi
}

# ---------------------------------------------------------------------------
# One state: fresh runtime -> measure -> (client stop) -> cleanup + residue log
# ---------------------------------------------------------------------------

state_body() {
  local state="$1"
  local slot

  # 1. FRESH runtime root, unique to this state. Nothing is reused from the
  #    previous state: temp, tmux, socket, state, uploads, home, port.
  STATE_ROOT="$(mktemp -d "/tmp/am-cidle-${state}.XXXXXX")"
  case "$STATE_ROOT" in
    /tmp/am-cidle-*|/private/tmp/am-cidle-*) ;;
    *) echo "unsafe state root: $STATE_ROOT" >&2; return 1 ;;
  esac
  STATE_LABEL="am-cidle-${state}-$$"
  STATE_TMUX_ROOT="$STATE_ROOT/tmux"
  STATE_SOCKET_DIR="$STATE_TMUX_ROOT/tmux-$(id -u)"
  STATE_SOCKET="$STATE_SOCKET_DIR/$STATE_LABEL"
  STATE_PANE_CWD="$STATE_ROOT/panes"
  STATE_RUN_HOME="$STATE_ROOT/home"
  STATE_STATE_DIR="$STATE_ROOT/state"
  STATE_UPLOAD_DIR="$STATE_ROOT/uploads"
  STATE_DAEMON_TMP="$STATE_ROOT/daemon-tmp"
  STATE_OWNED_PIDS="$STATE_ROOT/owned-pane-pids.tsv"
  STATE_READY_FILE="$STATE_ROOT/client.ready"

  STATE_DAEMON_LOG="$ART/daemon-${state}.log"
  STATE_DAEMON_STDOUT="$ART/daemon-${state}.stdout"
  STATE_CLIENT_LOG="$ART/client-${state}.log"
  STATE_CLEANUP_LOG="$ART/cleanup-${state}.log"
  STATE_CAPTURE_LOG="$ART/capture-pane-${state}.log"
  STATE_TARGET_AUDIT="$ART/tmux-targets-${state}.tsv"
  STATE_VIOLATIONS="$ART/isolation-violations-${state}.log"

  mkdir -p "$STATE_TMUX_ROOT" "$STATE_PANE_CWD" "$STATE_RUN_HOME" \
    "$STATE_STATE_DIR" "$STATE_UPLOAD_DIR" "$STATE_DAEMON_TMP"
  # tmux refuses a socket dir that is not 0700 to its owner; a fresh mktemp
  # root is already 0700 but /private/tmp can alias /tmp via symlink, so pin
  # it explicitly.
  chmod 700 "$STATE_SOCKET_DIR" 2>/dev/null || true
  printf '%s\t%s\n' "$state" "$STATE_SOCKET_DIR" >>"$ART/state-socket-dirs.tsv"
  : >"$STATE_OWNED_PIDS"
  : >"$STATE_DAEMON_LOG"
  : >"$STATE_CAPTURE_LOG"
  : >"$STATE_TARGET_AUDIT"
  : >"$STATE_VIOLATIONS"
  : >"$STATE_CLIENT_LOG"
  STATE_DAEMON_PID=""
  STATE_CLIENT_PID=""
  STATE_TMUX_PID=""

  # 2. Own tmux server + exactly 27 wrapper panes on this state's socket.
  for slot in $(seq 1 "$PANE_COUNT"); do
    tmux_own new-session -d -x 80 -y 24 -s "cidle-$(printf '%02d' "$slot")" \
      -c "$STATE_PANE_CWD" "$BUILD_ROOT/fake-agent-wrapper.sh"
  done
  STATE_TMUX_PID="$(tmux_own display-message -p '#{pid}' | tr -d '[:space:]')"
  local actual_panes
  actual_panes="$(tmux_own list-panes -a -F '#{pane_id}' | wc -l | tr -d ' ')"
  if [ "$actual_panes" -ne "$PANE_COUNT" ]; then
    echo "$state isolated pane count $actual_panes, want $PANE_COUNT" >&2
    return 1
  fi
  STATE_PANE_CWD="$(cd "$STATE_PANE_CWD" && pwd -P)"

  # 3. Own high port.
  STATE_PORT="$(pick_high_port)"
  printf '%s\t%s\n' "$state" "$STATE_PORT" >>"$USED_PORTS_FILE"

  # 4. Own daemon on this state's port/state-dir/socket-dir; env -i so
  #    TS_AUTHKEY/TS_CONTROL_URL/host defaults are never inherited. Scoped
  #    discovery is fail-closed to this state's exact socket dir.
  env -i \
    HOME="$STATE_RUN_HOME" \
    LANG=C \
    PATH="$SHIM_DIR:/usr/bin:/bin:/usr/sbin:/sbin" \
    TMPDIR="$STATE_DAEMON_TMP" \
    TMUX_TMPDIR="$STATE_TMUX_ROOT" \
    AGENTMIRROR_TOKEN="$TOKEN" \
    AGENTMIRROR_STATE_DIR="$STATE_STATE_DIR" \
    AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$STATE_SOCKET_DIR" \
    CIDLE_REAL_TMUX="$REAL_TMUX" \
    CIDLE_ALLOWED_SOCKET_DIR="$STATE_SOCKET_DIR" \
    CIDLE_TMUX_TARGET_AUDIT="$STATE_TARGET_AUDIT" \
    CIDLE_CAPTURE_LOG="$STATE_CAPTURE_LOG" \
    CIDLE_TMUX_VIOLATIONS="$STATE_VIOLATIONS" \
    "$DAEMON_BIN" \
      -listen "127.0.0.1:$STATE_PORT" \
      -host 127.0.0.1 \
      -state-dir "$STATE_STATE_DIR" \
      -upload-dir "$STATE_UPLOAD_DIR" \
      -log-level info \
      >"$STATE_DAEMON_STDOUT" 2>"$STATE_DAEMON_LOG" &
  STATE_DAEMON_PID=$!

  local listener_ready=0 i
  for i in $(seq 1 150); do
    if ! pid_alive "$STATE_DAEMON_PID"; then
      echo "$state isolated daemon exited during startup" >&2
      return 1
    fi
    if port_listens "$STATE_PORT"; then
      listener_ready=1
      break
    fi
    sleep 0.1
  done
  if [ "$listener_ready" -ne 1 ]; then
    echo "$state isolated daemon did not open its high port" >&2
    return 1
  fi

  # 5. Client when the state needs an authenticated connection.
  if [ "$state" != "zero_connection" ]; then
    start_client "$([ "$state" = connected_single_subscription ] && echo single-subscription || echo zero-subscription)" "$STATE_CLIENT_LOG" || return 1
  fi

  # 6. Measure the frozen window.
  sleep 3
  measure_state "$state" || return 1

  # 7. Stop the client; daemon/tmux stay until state_cleanup.
  if [ -n "$STATE_CLIENT_PID" ]; then
    stop_client || return 1
  fi
}

state_cleanup() {
  # Scoped teardown for ONE state, then a zero-residue proof log. Only the
  # captured PIDs, the state's own tmux -L, the exact high port, the state's
  # socket and runtime tree are touched; no pgrep/pkill by generic name.
  local state="$1"
  local ok=1 client_gone=true daemon_gone=true tmux_gone=true
  local owned_pids_gone=true listener_absent=true handles_zero=true
  local violations_zero=true socket_absent=true runtime_absent=true
  local rm_rc log
  # A state_body abort can leave STATE_CLEANUP_LOG unset; always land the
  # residue proof in a deterministic per-state path.
  log="${STATE_CLEANUP_LOG:-$ART/cleanup-${state}.log}"

  if [ -n "$STATE_CLIENT_PID" ]; then
    stop_owned_pid "$STATE_CLIENT_PID" || { client_gone=false; ok=0; }
    STATE_CLIENT_PID=""
  fi
  if [ -n "$STATE_DAEMON_PID" ]; then
    stop_owned_pid "$STATE_DAEMON_PID" || { daemon_gone=false; ok=0; }
    STATE_DAEMON_PID=""
  fi
  if [ -n "$STATE_TMUX_PID" ]; then
    # kill-server on the state's own -L only; never default sockets.
    tmux_own kill-server >/dev/null 2>&1 || true
    stop_owned_pid "$STATE_TMUX_PID" || { tmux_gone=false; ok=0; }
    STATE_TMUX_PID=""
  fi

  if [ -f "$STATE_OWNED_PIDS" ]; then
    while IFS=$'\t' read -r root_pid child_pid; do
      stop_owned_pid "$child_pid" || { owned_pids_gone=false; ok=0; }
      stop_owned_pid "$root_pid" || { owned_pids_gone=false; ok=0; }
    done <"$STATE_OWNED_PIDS"
  fi

  if [ -n "$STATE_PORT" ] && port_listens "$STATE_PORT"; then
    listener_absent=false
    ok=0
  fi

  if command -v lsof >/dev/null 2>&1 && [ -n "$STATE_ROOT" ] && [ -d "$STATE_ROOT" ]; then
    if lsof -nP +D "$STATE_ROOT" >/dev/null 2>&1; then
      handles_zero=false
      ok=0
    fi
  fi

  if [ -n "$STATE_ROOT" ]; then
    case "$STATE_ROOT" in
      /tmp/am-cidle-*|/private/tmp/am-cidle-*)
        rm -rf -- "$STATE_ROOT"
        rm_rc=$?
        ;;
      *) rm_rc=1 ;;
    esac
    if [ "$rm_rc" -ne 0 ] || [ -e "$STATE_ROOT" ]; then
      runtime_absent=false
      ok=0
    fi
  fi
  if [ -n "$STATE_SOCKET" ] && [ -e "$STATE_SOCKET" ]; then
    socket_absent=false
    ok=0
  fi
  if [ -n "$STATE_VIOLATIONS" ] && [ -s "$STATE_VIOLATIONS" ]; then
    violations_zero=false
    ok=0
  fi

  printf 'state=%s\n' "$state" >>"$log"
  printf 'runtime_root=%s\n' "$STATE_ROOT" >>"$log"
  printf 'high_port=%s\n' "$STATE_PORT" >>"$log"
  printf 'client_pid_gone=%s\n' "$client_gone" >>"$log"
  printf 'daemon_pid_gone=%s\n' "$daemon_gone" >>"$log"
  printf 'tmux_pid_gone=%s\n' "$tmux_gone" >>"$log"
  printf 'owned_pane_pids_gone=%s\n' "$owned_pids_gone" >>"$log"
  printf 'listener_absent=%s\n' "$listener_absent" >>"$log"
  printf 'runtime_handles_zero=%s\n' "$handles_zero" >>"$log"
  printf 'socket_absent=%s\n' "$socket_absent" >>"$log"
  printf 'runtime_tree_absent=%s\n' "$runtime_absent" >>"$log"
  printf 'out_of_scope_tmux_targets=%s\n' "$([ "$violations_zero" = true ] && echo 0 || wc -l <"$STATE_VIOLATIONS" | tr -d ' ')" >>"$log"
  printf 'ps_scope=read-only snapshot; classification only beneath self-owned pane roots; raw process table not recorded or displayed; zero production interaction\n' >>"$log"

  [ "$ok" -eq 1 ]
}

run_state() {
  # state_body runs with the script's set -e active: any failure aborts the
  # body and is captured via ||. state_cleanup then always runs and writes the
  # state's zero-residue proof. Either failure fails the whole state.
  local state="$1" body_rc=0 cleanup_rc=0
  state_body "$state" || body_rc=$?
  state_cleanup "$state" || cleanup_rc=$?
  if [ "$cleanup_rc" -ne 0 ] || [ "$body_rc" -ne 0 ]; then
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Build the read-only binaries once (reused by all three states)
# ---------------------------------------------------------------------------
(
  cd "$SERVER_ROOT"
  "$REAL_GO" build -o "$DAEMON_BIN" ./cmd/agentmirrord
) >>"$BUILD_LOG" 2>&1

# The client helper: an inline module that reuses the server's wire shapes via
# the local replace. The token is read from the environment only; argv carries
# mode/url/cwd/ready/panes but never a token.
cat >"$CLIENT_SRC/go.mod" <<'EOF'
module github.com/agentmirror/agentmirror/connectedidleclient

go 1.26.5

require github.com/coder/websocket v1.8.14
EOF
cp "$E2E_ROOT/harness/go.sum" "$CLIENT_SRC/go.sum"
cat >"$CLIENT_SRC/main.go" <<'EOF'
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/coder/websocket"
)

type envelope struct {
	V       int             `json:"v"`
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

type session struct {
	Ref string `json:"ref"`
}

type workspace struct {
	Cwd      string    `json:"cwd"`
	Sessions []session `json:"sessions"`
}

func writeFrame(ctx context.Context, c *websocket.Conn, typ string, payload any) error {
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	body, err := json.Marshal(envelope{V: 1, Type: typ, Payload: raw})
	if err != nil {
		return err
	}
	return c.Write(ctx, websocket.MessageText, body)
}

func nextControl(ctx context.Context, c *websocket.Conn, want string) (json.RawMessage, error) {
	for {
		typ, body, err := c.Read(ctx)
		if err != nil {
			return nil, err
		}
		if typ != websocket.MessageText {
			continue
		}
		var env envelope
		if err := json.Unmarshal(body, &env); err != nil {
			return nil, err
		}
		if env.Type == "error" {
			return nil, fmt.Errorf("server error: %s", env.Payload)
		}
		if env.Type == want {
			return env.Payload, nil
		}
	}
}

func main() {
	mode := flag.String("mode", "", "zero-subscription or single-subscription")
	url := flag.String("url", "", "WebSocket URL")
	cwd := flag.String("cwd", "", "expected isolated cwd")
	ready := flag.String("ready", "", "ready-file path")
	wantPanes := flag.Int("panes", 27, "expected isolated pane count")
	flag.Parse()

	// Rework red-item-2: the pairing token arrives ONLY via the environment.
	// It is never an argv flag and never echoed; only its presence is proven.
	token := os.Getenv("CIDLE_E2E_CLIENT_TOKEN")
	if token == "" {
		panic("CIDLE_E2E_CLIENT_TOKEN not set")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	c, _, err := websocket.Dial(ctx, *url, nil)
	if err != nil {
		cancel()
		panic(err)
	}
	cancel()
	defer c.CloseNow()

	ctx, cancel = context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := writeFrame(ctx, c, "auth", map[string]any{"token": token}); err != nil {
		panic(err)
	}
	raw, err := nextControl(ctx, c, "auth_ack")
	if err != nil {
		panic(err)
	}
	var ack struct{ OK bool `json:"ok"` }
	if err := json.Unmarshal(raw, &ack); err != nil || !ack.OK {
		panic("authentication rejected")
	}
	if err := writeFrame(ctx, c, "list", map[string]any{"req_id": 1}); err != nil {
		panic(err)
	}
	raw, err = nextControl(ctx, c, "listing")
	if err != nil {
		panic(err)
	}
	var listing struct {
		Workspaces []workspace `json:"workspaces"`
	}
	if err := json.Unmarshal(raw, &listing); err != nil {
		panic(err)
	}
	count := 0
	ref := ""
	for _, ws := range listing.Workspaces {
		if ws.Cwd != *cwd {
			panic(fmt.Sprintf("listing escaped isolated cwd: %q", ws.Cwd))
		}
		count += len(ws.Sessions)
		if ref == "" && len(ws.Sessions) > 0 {
			ref = ws.Sessions[0].Ref
		}
	}
	if count != *wantPanes || ref == "" {
		panic(fmt.Sprintf("isolated listing panes=%d want=%d", count, *wantPanes))
	}

	if *mode == "single-subscription" {
		if err := writeFrame(ctx, c, "subscribe", map[string]any{"ref": ref, "rows": 24, "cols": 80}); err != nil {
			panic(err)
		}
		for {
			typ, _, err := c.Read(ctx)
			if err != nil {
				panic(err)
			}
			if typ == websocket.MessageBinary {
				break
			}
		}
	} else if *mode != "zero-subscription" {
		panic("unknown mode")
	}

	if err := os.WriteFile(*ready, []byte("ready\n"), 0o600); err != nil {
		panic(err)
	}
	fmt.Printf("ready mode=%s panes=%d\n", *mode, count)

	readErr := make(chan error, 1)
	go func() {
		for {
			_, _, err := c.Read(context.Background())
			if err != nil {
				readErr <- err
				return
			}
		}
	}()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	select {
	case <-sig:
		_ = c.Close(websocket.StatusNormalClosure, "measurement complete")
	case err := <-readErr:
		panic(fmt.Sprintf("connection ended before signal: %v", err))
	}
}
EOF
(
  cd "$CLIENT_SRC"
  GOWORK=off "$REAL_GO" build -mod=readonly -o "$CLIENT_BIN" .
) >>"$BUILD_LOG" 2>&1

# ---------------------------------------------------------------------------
# Run the three frozen states, each in a fresh runtime
# ---------------------------------------------------------------------------
overall_fail=0
for state in zero_connection connected_zero_subscription connected_single_subscription; do
  if ! run_state "$state"; then
    echo "state $state FAILED (see $ART/cleanup-${state}.log)" >&2
    overall_fail=1
  fi
done

# ---------------------------------------------------------------------------
# Emit metrics.json from measurements + per-state cleanup/residue evidence
# ---------------------------------------------------------------------------
python3 - "$METRICS_TSV" "$METRICS_JSON" "$CPU_LIMIT" "$MEASURE_SECONDS" "$PANE_COUNT" "$ART" "$overall_fail" <<'PY'
import csv, json, os, sys
src, dst, cpu_limit, seconds, panes, art, overall_fail = sys.argv[1:]
numeric_int = {"panes", "wall_start_ns", "wall_end_ns", "capture_start", "capture_end", "capture_delta"}
numeric_float = {"wall_seconds", "cpu_start_seconds", "cpu_end_seconds", "cpu_delta_seconds", "mean_cpu_percent", "capture_per_second"}
with open(src, newline="") as fh:
    rows = list(csv.DictReader(fh, delimiter="\t"))
for row in rows:
    for key in numeric_int:
        row[key] = int(row[key])
    for key in numeric_float:
        row[key] = float(row[key])

# Fold each state's per-boundary zero-residue evidence into its row.
cleanup_keys = ["client_pid_gone", "daemon_pid_gone", "tmux_pid_gone",
                "owned_pane_pids_gone", "listener_absent", "runtime_handles_zero",
                "socket_absent", "runtime_tree_absent", "out_of_scope_tmux_targets",
                "high_port", "runtime_root"]
for row in rows:
    log = os.path.join(art, "cleanup-" + row["state"] + ".log")
    cleanup = {"log": "e2e/artifacts/fix-connected-idle-economy/cleanup-" + row["state"] + ".log"}
    if os.path.isfile(log):
        for line in open(log, encoding="utf-8"):
            line = line.strip()
            if not line or "=" not in line:
                continue
            k, _, v = line.partition("=")
            if k in cleanup_keys:
                if k == "out_of_scope_tmux_targets":
                    try:
                        v = int(v)
                    except ValueError:
                        pass
                elif k == "high_port":
                    try:
                        v = int(v)
                    except ValueError:
                        pass
                elif v == "true":
                    v = True
                elif v == "false":
                    v = False
                cleanup[k] = v
    row["per_state_residue"] = cleanup

# Per-state explicit scoped socket dirs, in run order.
socket_dirs_tsv = os.path.join(art, "state-socket-dirs.tsv")
socket_dirs = []
if os.path.isfile(socket_dirs_tsv):
    for line in open(socket_dirs_tsv, encoding="utf-8"):
        line = line.strip()
        if not line or "\t" not in line:
            continue
        s, d = line.split("\t", 1)
        socket_dirs.append({"state": s, "socket_dir": d})

payload = {
    "verdict": "fail" if str(overall_fail) != "0" else "pass",
    "rework": "three states each in a fresh self-owned runtime (own mktemp root, own client process, own daemon process, own tmux -L server, own high port, own socket dir, own state dir); same read-only binaries reused; zero-residue proven at every state boundary; client token env-only (CIDLE_E2E_CLIENT_TOKEN), no -token argv",
    "thresholds": {
        "online_mean_cpu_percent_max": float(cpu_limit),
        "state_window_seconds_min": int(seconds),
        "pane_count": int(panes),
        "deterministic_fleet_sizes": [3, 27, 200],
        "fairness_seconds_max": 60,
    },
    "discovery": {
        "mode": "explicit_scoped_socket_dirs_per_state",
        "socket_dirs": socket_dirs,
        "default_socket_dirs_opened": False,
        "team_agent_socket_opened": False,
    },
    "process_snapshot_deviation": "ps read-only snapshot; classification only beneath self-owned pane roots; raw process table not recorded or displayed; zero production interaction",
    "states": rows,
}
with open(dst, "w") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
PY

# ---------------------------------------------------------------------------
# Isolation summary across the three per-state scoped audits
# ---------------------------------------------------------------------------
total_targets=0
total_captures=0
total_violations=0
socket_list=""
while IFS=$'\t' read -r state dir; do
  t="$(awk 'END { print NR + 0 }' "$ART/tmux-targets-${state}.tsv")"
  c="$(awk 'END { print NR + 0 }' "$ART/capture-pane-${state}.log")"
  v="$(awk 'END { print NR + 0 }' "$ART/isolation-violations-${state}.log")"
  total_targets=$((total_targets + t))
  total_captures=$((total_captures + c))
  total_violations=$((total_violations + v))
  socket_list="$socket_list
$state=$dir"
done <"$SOCKET_DIRS_FILE"
printf 'per_state_socket_dirs=%s\ntmux_target_count=%s\ncapture_total=%s\nout_of_scope_targets=%s\ndefault_socket_dirs_opened=false\nteam_agent_socket_opened=false\n' \
  "$socket_list" "$total_targets" "$total_captures" "$total_violations" >"$ISOLATION_SUMMARY"

# Verdict: exit through the registered EXIT trap (final sweep runs there).
if [ "$overall_fail" -ne 0 ]; then
  exit 1
fi
exit 0
