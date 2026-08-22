#!/bin/bash
# Isolated tmux + isolated daemon + WS dump of first KindSnapshot.
# Never touches default tmux socket. Never binds/kills 9900.
set -euo pipefail
ROOT="/Volumes/nvme/Projects/远程Agent安卓"
TMP="$ROOT/.team/nodes/hl1-sprobe2/tmp"
PORT=22421
TOKEN="HL1SP2$(date +%s)"
SHA="f116dd8d16c7bdd9f3836bf2e22f66a53c133603"
SOCK="$TMP/tmux/tmux-501/default"
CWD="$TMP/cwd-static"
DUMP="$TMP/out"

mkdir -p "$TMP/tmux" "$TMP/bin" "$TMP/state" "$TMP/uploads" "$CWD" "$DUMP"
ln -sfn /bin/bash "$TMP/bin/claude"

# --- never inherit outer TMUX ---
unset TMUX || true
export -n TMUX_TMPDIR 2>/dev/null || true

cleanup() {
  if [ -f "$TMP/daemon.pid" ]; then
    old=$(cat "$TMP/daemon.pid" || true)
    if [ -n "${old:-}" ]; then
      comm=$(ps -o comm= -p "$old" 2>/dev/null | tr -d ' ' || true)
      if [ "$comm" = "agentmirrord" ]; then
        kill "$old" 2>/dev/null || true
        sleep 0.3
        kill -9 "$old" 2>/dev/null || true
      fi
    fi
  fi
  unset TMUX || true
  if [ -S "$SOCK" ] || [ -d "$TMP/tmux/tmux-501" ]; then
    TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null kill-server 2>/dev/null || true
  fi
}
# cleanup on EXIT except we keep dumps; always stop our daemon+tmux
trap cleanup EXIT

# tear down leftover of ours
cleanup

# refuse 9900
if lsof -nP -iTCP:9900 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "NOTE: 9900 is listening (production). will not touch it."
fi
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "FAIL: port $PORT already in use"
  exit 1
fi

# extract sealed server tree
rm -rf "$TMP/server"
mkdir -p "$TMP/server"
git -C "$ROOT" archive "$SHA" server | tar -x -C "$TMP"
# now $TMP/server is the sealed tree
if ! grep -q "Capture BEFORE Resize" "$TMP/server/internal/api/ws_handler.go"; then
  echo "FAIL: extracted tree missing Capture BEFORE Resize"
  exit 1
fi
echo "EXTRACT_OK sha=$SHA"

# build daemon into tmp (do not write into product tree)
(
  cd "$TMP/server"
  go build -o "$TMP/agentmirrord" ./cmd/agentmirrord
)
echo "DAEMON_BUILD_OK"
# dump client lives in extracted tree so it can import internal/protocol
# (outside-module import of internal/ is rejected by go).
mkdir -p "$TMP/server/cmd/sprobe2dump"
cp "$TMP/dump/main.go" "$TMP/server/cmd/sprobe2dump/main.go"
(
  cd "$TMP/server"
  go build -o "$TMP/dump-snap" ./cmd/sprobe2dump
)
echo "DUMP_BUILD_OK"

# isolated tmux
export PATH="$TMP/bin:$PATH"
unset TMUX
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null new-session -d -s hl1sp2-static \
  -c "$CWD" "export PATH=\"$TMP/bin:\$PATH\"; exec claude -i"
sleep 0.5

# self-check: session lives on OUR socket, not default
SESS=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null list-sessions)
echo "LIST_SESSIONS=$SESS"
echo "SOCKET=$SOCK len=${#SOCK}"
test -S "$SOCK"
echo "$SESS" | grep -q hl1sp2-static
# must not be the fleet socket
if [ "$SOCK" = "/tmp/tmux-501/default" ] || [ "$SOCK" = "/private/tmp/tmux-501/default" ]; then
  echo "FAIL: fell back to default tmux socket"
  exit 1
fi
# default socket if present must be a different path
if [ -S /private/tmp/tmux-501/default ]; then
  echo "NOTE: real fleet socket exists (expected). will not send keys there."
fi

# freeze alt-screen
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null send-keys -t hl1sp2-static \
  "source ./frozen-alt.sh" Enter

pre_ok=0
for i in $(seq 1 40); do
  alt=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t hl1sp2-static '#{alternate_on}' || true)
  cap=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null capture-pane -e -p -t hl1sp2-static || true)
  echo "wait i=$i alt=$alt"
  if [ "$alt" = "1" ] && echo "$cap" | grep -q STATIC_ALT_MARKER_092; then
    pre_ok=1
    break
  fi
  sleep 0.25
done
if [ "$pre_ok" != "1" ]; then
  echo "FAIL: pre-subscribe capture missing marker"
  TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null capture-pane -e -p -t hl1sp2-static | head
  exit 1
fi
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null capture-pane -e -p -t hl1sp2-static > "$DUMP/pre-capture.txt"
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t hl1sp2-static \
  'alt=#{alternate_on} cursor=#{cursor_x},#{cursor_y} size=#{pane_width}x#{pane_height} pid=#{pane_pid}' \
  > "$DUMP/pre-pane.txt"
pid=$(TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t hl1sp2-static '#{pane_pid}')
ps -o comm= -p "$pid" | tr -d ' ' > "$DUMP/pre-comm.txt"
echo "PRE_CAPTURE_OK alt=1 marker=yes comm=$(cat "$DUMP/pre-comm.txt")"

printf '%s' "$TOKEN" > "$TMP/token"
printf '%s' "$PORT" > "$TMP/port"

: > "$TMP/daemon.log"
# TMUX_TMPDIR so discovery sees our socket first; still also scans /tmp (fleet) — we only subscribe ours.
unset TMUX
AGENTMIRROR_TOKEN="$TOKEN" AGENTMIRROR_STATE_DIR="$TMP/state" \
  TMUX_TMPDIR="$TMP/tmux" \
  "$TMP/agentmirrord" -listen "127.0.0.1:$PORT" -upload-dir "$TMP/uploads" \
  -log-level debug -list-interval 2s >>"$TMP/daemon.log" 2>&1 &
echo $! > "$TMP/daemon.pid"
echo "DAEMON_PID=$(cat "$TMP/daemon.pid")"

ok=0
for i in $(seq 1 50); do
  if (echo >/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null; then ok=1; break; fi
  sleep 0.1
done
test "$ok" = "1"
echo "LISTEN_OK port=$PORT (not 9900)"

# confirm 9900 still the original listener
echo "9900_AFTER=$(lsof -nP -iTCP:9900 -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print $1,$2}' || true)"

"$TMP/dump-snap" \
  -url "ws://127.0.0.1:$PORT/ws" \
  -token "$TOKEN" \
  -cwd "hl1-sprobe2/tmp/cwd-static" \
  -out "$DUMP" \
  -rows 96 -cols 108 \
  -timeout 8s | tee "$DUMP/result.stdout.json"

# post-subscribe pane (WINCH may have cleared live cells)
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null capture-pane -e -p -t hl1sp2-static > "$DUMP/post-capture.txt" || true
TMUX_TMPDIR="$TMP/tmux" tmux -f /dev/null display-message -p -t hl1sp2-static \
  'alt=#{alternate_on} cursor=#{cursor_x},#{cursor_y} size=#{pane_width}x#{pane_height}' \
  > "$DUMP/post-pane.txt" || true

echo "RUN_OK"
cat "$DUMP/result.json"
