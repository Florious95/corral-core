#!/usr/bin/env bash
set -euo pipefail

# G-SAFE harness: only a private synthetic tmux socket and structure-only
# wrappers are touched. No pane body, argv, credentials, or production socket.
ROOT="${GSAFE_ROOT:?set GSAFE_ROOT to a private test directory}"
SOCK="$ROOT/gsafe.sock"
TRACE="$ROOT/candidate-trace.log"
CONTROL_TRACE="$ROOT/control-trace.log"
BIN="${NODEPROBE_BIN:-$PWD/target/release/nodeprobe}"
TMUX_REAL="${TMUX_REAL:-$(command -v tmux)}"
WRAP="$ROOT/bin"
mkdir -p "$ROOT" "$WRAP"
rm -f "$SOCK" "$TRACE" "$CONTROL_TRACE"

cat >"$WRAP/tmux" <<'SH'
#!/bin/sh
set -eu
trace="${NODEPROBE_TRACE:?}"
sock=""
category=other
prev=""
for arg in "$@"; do
  if [ "$prev" = 1 ]; then sock="$arg"; prev=""; continue; fi
  if [ "$arg" = "-S" ]; then prev=1; continue; fi
  case "$arg" in
    list-panes) category=list-panes;;
    capture-pane) category=capture-pane;;
    attach-session|attach|send-keys|pipe-pane|display-message) category=forbidden;;
  esac
done
bound=0
[ "$sock" = "${NODEPROBE_SOCKET:?}" ] && bound=1
printf 'tool=tmux category=%s socket_bound=%s\n' "$category" "$bound" >>"$trace"
case "$category" in
  capture-pane|forbidden)
    printf 'blocked=%s\n' "$category" >>"$trace"
    exit 97
    ;;
esac
[ "$bound" = 1 ] || exit 98
exec "$TMUX_REAL" "$@"
SH
cat >"$WRAP/ps" <<'SH'
#!/bin/sh
set -eu
trace="${NODEPROBE_TRACE:?}"
safe=0
for arg in "$@"; do
  [ "$arg" = "pid=,ppid=,stat=,comm=" ] && safe=1
done
if [ "$safe" = 1 ]; then
  printf 'tool=ps category=ps-narrow socket_bound=not-applicable\n' >>"$trace"
  printf '%s 1 S+ /usr/bin/codex\n' "${GSAFE_PANE_PID:?}"
  exit 0
fi
printf 'tool=ps category=ps-dangerous socket_bound=not-applicable\nblocked=ps-dangerous\n' >>"$trace"
exit 97
SH
chmod +x "$WRAP/tmux" "$WRAP/ps"

cleanup() {
  "$TMUX_REAL" -S "$SOCK" -f /dev/null kill-server >/dev/null 2>&1 || true
  rm -f "$SOCK"
}
trap cleanup EXIT

"$TMUX_REAL" -S "$SOCK" -f /dev/null new-session -d -s gsafe -n main 'sleep 60'
PANE_PID="$($TMUX_REAL -S "$SOCK" -f /dev/null display-message -p '#{pane_pid}')"
case "$PANE_PID" in ''|*[!0-9]*) echo 'invalid pane pid' >&2; exit 2;; esac
export NODEPROBE_SOCKET="$SOCK" NODEPROBE_TRACE="$CONTROL_TRACE" PATH="$WRAP:$PATH"
set +e
"$WRAP/tmux" -S "$SOCK" capture-pane -p -t gsafe:0.0 >/dev/null 2>&1
capture_rc=$?
"$WRAP/ps" ax >/dev/null 2>&1
ps_rc=$?
set -e
[ "$capture_rc" = 97 ] && [ "$ps_rc" = 97 ]
: >"$TRACE"
export NODEPROBE_TRACE="$TRACE" GSAFE_PANE_PID="$PANE_PID" GSAFE_FAKE_PID=424242
NODEPROBE_FIXTURES="$PWD/tools/nodeprobe/fixtures/titles.tsv" \
NODEPROBE_PROVIDERS="$PWD/tools/nodeprobe/fixtures/providers.tsv" \
NODEPROBE_BIN="$BIN" "$BIN" -S "$SOCK" >"$ROOT/candidate.json"
python3 - "$ROOT/candidate.json" "$TRACE" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
assert report.get("error") is None, report
nodes = report.get("nodes", [])
assert nodes, report
assert any(n.get("provider") == "codex" and n.get("activity") == "idle" for n in nodes), nodes
trace = open(sys.argv[2]).read().splitlines()
assert trace and any("category=list-panes" in x and "socket_bound=1" in x for x in trace), trace
assert any("category=ps-narrow" in x for x in trace), trace
assert not any("blocked=" in x or "capture-pane" in x or "ps-dangerous" in x for x in trace), trace
print(json.dumps({"nodes": len(nodes), "positive_trace_lines": len(trace), "forbidden_rejections": 0}))
PY
printf 'capture_rc=%s\nps_dangerous_rc=%s\n' "$capture_rc" "$ps_rc" >"$ROOT/control-result.txt"
