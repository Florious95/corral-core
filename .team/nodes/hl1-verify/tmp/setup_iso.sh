#!/bin/bash
# Isolated tmux + test daemon for t.verify. Never touches default tmux socket.
set -euo pipefail
ROOT="/Volumes/nvme/Projects/远程Agent安卓"
TMP="$ROOT/.team/nodes/hl1-verify/tmp"
PORT=22351
TOKEN="HL1VRF$(date +%s)"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"

mkdir -p "$TMP/tmux" "$TMP/bin" "$TMP/state" "$TMP/uploads" \
  "$TMP/cwd-static" "$TMP/cwd-hist"
ln -sfn /bin/bash "$TMP/bin/claude"

# Tear down only THIS isolation server.
unset TMUX || true
if [ -S "$TMP/tmux/tmux-501/default" ] || [ -d "$TMP/tmux/tmux-501" ]; then
  TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null kill-server 2>/dev/null || true
  sleep 0.3
fi
# stale daemon of ours
if [ -f "$TMP/daemon.pid" ]; then
  old=$(cat "$TMP/daemon.pid" || true)
  if [ -n "${old:-}" ] && kill -0 "$old" 2>/dev/null; then
    comm=$(ps -o comm= -p "$old" | tr -d ' ')
    if [ "$comm" = "agentmirrord" ]; then
      kill "$old" 2>/dev/null || true
      sleep 0.4
      kill -9 "$old" 2>/dev/null || true
    fi
  fi
fi

# Do not inherit outer TMUX.
export -n TMUX_TMPDIR 2>/dev/null || true
unset TMUX
export PATH="$TMP/bin:$PATH"

TMUX_TMPDIR="$TMP/tmux" tmux -f "$TMP/tmux.conf" new-session -d -s hl1v-static \
  -c "$TMP/cwd-static" "export PATH=\"$TMP/bin:\$PATH\"; exec claude -i"
TMUX_TMPDIR="$TMP/tmux" tmux -f "$TMP/tmux.conf" new-session -d -s hl1v-hist \
  -c "$TMP/cwd-hist" "export PATH=\"$TMP/bin:\$PATH\"; exec claude -i"
sleep 0.8

# Self-check: sessions live on OUR TMPD socket.
SESS=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null list-sessions)
echo "LIST_SESSIONS=$SESS"
SOCK="$TMP/tmux/tmux-501/default"
echo "SOCKET=$SOCK len=${#SOCK}"
test -S "$SOCK"
echo "$SESS" | grep -q hl1v-static
echo "$SESS" | grep -q hl1v-hist

# Confirm default socket is NOT this one.
if [ -S /tmp/tmux-501/default ] || [ -S /private/tmp/tmux-501/default ]; then
  echo "NOTE: real fleet socket exists (expected). will not send keys there."
fi

# Frozen alt-screen in static session (same process → comm=claude).
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null send-keys -t hl1v-static \
  "source ./frozen-alt.sh" Enter
# Large scrollback then idle (stay in claude/bash).
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null send-keys -t hl1v-hist \
  "seq 1 40000 | sed 's/^/HISTLINE /'; echo HIST_DONE_092" Enter

# Wait hist dump + alt marker.
for i in $(seq 1 40); do
  alt=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t hl1v-static '#{alternate_on}' || true)
  cap=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null capture-pane -p -t hl1v-static || true)
  hs=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t hl1v-hist '#{history_size}' || true)
  hcap=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null capture-pane -p -t hl1v-hist || true)
  echo "wait i=$i alt=$alt hist=$hs"
  if [ "$alt" = "1" ] && echo "$cap" | grep -q STATIC_ALT_MARKER_092 && [ "${hs:-0}" -ge 30000 ] && echo "$hcap" | grep -q HIST_DONE_092; then
    break
  fi
  sleep 0.5
done

echo "=== pane comm (ps -o comm= only) ==="
for sess in hl1v-static hl1v-hist; do
  pid=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t "$sess" '#{pane_pid}')
  comm=$(ps -o comm= -p "$pid" | tr -d ' ')
  echo "$sess pid=$pid comm=$comm"
  test "$comm" = "claude"
done

echo "=== final pane state ==="
echo -n "static alt="; TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t hl1v-static '#{alternate_on}'
echo -n "hist size="; TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t hl1v-hist '#{history_size}'
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null capture-pane -p -t hl1v-static | tail -5
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null capture-pane -p -t hl1v-hist | tail -3

printf '%s' "$TOKEN" > "$TMP/token"
printf '%s' "$PORT" > "$TMP/port"

# Start isolated daemon (own state dir → not the 9900 pidfile).
: > "$TMP/daemon.log"
AGENTMIRROR_TOKEN="$TOKEN" AGENTMIRROR_STATE_DIR="$TMP/state" \
  "$TMP/agentmirrord" -listen "0.0.0.0:$PORT" -upload-dir "$TMP/uploads" \
  -log-level debug -list-interval 500ms >>"$TMP/daemon.log" 2>&1 &
echo $! > "$TMP/daemon.pid"
echo "DAEMON_PID=$(cat "$TMP/daemon.pid")"

ok=0
for i in $(seq 1 30); do
  if (echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then ok=1; break; fi
  sleep 0.2
done
test "$ok" = "1"
echo "LISTEN_OK port=$PORT"
"$ADB" -s emulator-5554 reverse tcp:$PORT tcp:$PORT
echo "ADB_REVERSE tcp:$PORT"
echo "SETUP_OK"
