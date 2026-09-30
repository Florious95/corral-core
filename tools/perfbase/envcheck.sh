#!/bin/sh
# Strict environment gate for performance measurements.
# --gate is the preflight phase. --measurement relaxes load1 only for the
# qemu PID and adb serial bound by run-input-ab.sh.
set -u
MODE=${1:-}
MAX_DEAD=10
MAX_LOAD=12
MAX_DAEMON_CPU=5
SOCKET_DIR=${ENVCHECK_SOCKET_DIR:-/private/tmp/tmux-501}
ADB_BIN=${ADB:-adb}
OWNED_PID=${2:-}
SERIAL=${3:-}
case "$MODE" in
  --gate|--clean) PHASE=preflight ;;
  --measurement)
    PHASE=measurement
    case "$OWNED_PID" in ''|*[!0-9]*) echo "UNJUDGEABLE measurement owned_pid missing or invalid" >&2; exit 2;; esac
    [ -n "$SERIAL" ] || { echo "UNJUDGEABLE measurement adb serial missing" >&2; exit 2; }
    ;;
  *) echo "usage: $0 --gate|--clean|--measurement PID SERIAL" >&2; exit 2;;
esac
dirty=0
dead=0
live=0
for required_tool in ps uptime lsof top tmux; do
  if ! command -v "$required_tool" >/dev/null 2>&1; then
    echo "UNJUDGEABLE required tool unavailable: $required_tool" >&2
    dirty=1
  fi
done
if [ -d "$SOCKET_DIR" ]; then
  for socket in "$SOCKET_DIR"/*; do
    [ -e "$socket" ] || continue
    [ -S "$socket" ] || continue
    tmux -S "$socket" list-sessions >/dev/null 2>&1
    tmux_rc=$?
    if [ "$tmux_rc" -eq 0 ]; then
      live=$((live + 1))
    else
      dead=$((dead + 1))
      [ "$tmux_rc" -eq 1 ] || dirty=1
      if [ "$MODE" = --clean ]; then rm -f "$socket"; fi
    fi
  done
fi
[ "$MODE" = --clean ] && [ "$dead" -gt "$MAX_DEAD" ] && dead=0
uptime_output=$(uptime 2>/dev/null); uptime_rc=$?
if [ "$uptime_rc" -ne 0 ]; then
  dirty=1
  uptime_output=''
fi
load1=$(printf '%s\n' "$uptime_output" | sed -E 's/.*load averages?: *([0-9.]+).*/\1/')
case "$load1" in ''|*[!0-9.]*|.*.*) dirty=1; load1='?';; esac
if [ "$load1" != '?' ] && [ "$PHASE" != measurement ]; then
  [ "$(awk -v value="$load1" -v limit="$MAX_LOAD" 'BEGIN {print (value > limit) ? 1 : 0}')" -eq 0 ] || dirty=1
fi
ps_output=$(ps -eo pid=,comm= 2>/dev/null); ps_rc=$?
if [ "$ps_rc" -ne 0 ]; then
  dirty=1
  ps_output=''
fi
qemu_pids=$(printf '%s\n' "$ps_output" | awk '{name=$2; sub(".*/", "", name); if (name ~ /^qemu-system/) print $1}')
qemu_count=$(printf '%s\n' "$qemu_pids" | awk 'NF {n++} END {print n+0}')
if [ "$PHASE" = preflight ]; then
  [ "$qemu_count" -eq 0 ] || dirty=1
else
  owned_count=$(printf '%s\n' "$qemu_pids" | awk -v wanted="$OWNED_PID" '$1 == wanted {n++} END {print n+0}')
  [ "$qemu_count" -eq 1 ] && [ "$owned_count" -eq 1 ] || dirty=1
fi
lsof_output=$(lsof -nP -iTCP:9900 -sTCP:LISTEN -t 2>/dev/null); lsof_rc=$?
if [ "$lsof_rc" -gt 1 ]; then dirty=1; lsof_output=''; fi
daemon_pid=$(printf '%s\n' "$lsof_output" | head -1)
daemon_cpu=none
if [ -n "$daemon_pid" ]; then
  top_output=$(top -l 5 -pid "$daemon_pid" -stats pid,cpu -n 1 2>/dev/null); top_rc=$?
  if [ "$top_rc" -ne 0 ]; then dirty=1; top_output=''; fi
  daemon_cpu=$(printf '%s\n' "$top_output" |
    awk -v wanted="$daemon_pid" '$1 == wanted {sum += $2; n++} END {if (n) printf "%.1f", sum/n; else print "?"}')
  [ -n "$daemon_cpu" ] || daemon_cpu='?'
  case "$daemon_cpu" in
    '?'|*[!0-9.]*|.*.*) dirty=1;;
    *) [ "$(awk -v value="$daemon_cpu" -v limit="$MAX_DAEMON_CPU" 'BEGIN {print (value > limit) ? 1 : 0}')" -eq 0 ] || dirty=1;;
  esac
fi
adb_state=not-checked
boot=not-checked
if [ "$PHASE" = measurement ]; then
  if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
    adb_state=missing; dirty=1
  else
    adb_state=$("$ADB_BIN" devices 2>/dev/null | awk -v serial="$SERIAL" '$1 == serial {print $2; exit}')
    [ "$adb_state" = device ] || dirty=1
    if [ "$adb_state" = device ]; then
      boot=$("$ADB_BIN" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | tail -1)
      [ "$boot" = 1 ] || dirty=1
    fi
  fi
fi
printf 'ENVCHECK phase=%s load1=%s/%s dead=%s/%s daemon_cpu=%s/%s observed_qemu=%s owned_pid=%s serial=%s state=%s boot=%s\n' \
  "$PHASE" "$load1" "$MAX_LOAD" "$dead" "$MAX_DEAD" "$daemon_cpu" "$MAX_DAEMON_CPU" \
  "${qemu_pids:-none}" "${OWNED_PID:-none}" "${SERIAL:-none}" "$adb_state" "$boot"
if [ "$dirty" -ne 0 ]; then
  echo "UNJUDGEABLE envcheck phase=$PHASE" >&2
  exit 2
fi
echo "PASS envcheck phase=$PHASE"
exit 0
