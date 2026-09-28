#!/usr/bin/env bash
# t.probe16 isolation + cold-open static alt-screen. tmp only under this dir.
set -euo pipefail
ROOT="/Volumes/nvme/Projects/远程Agent安卓"
TMPD="$ROOT/.team/nodes/hl1-probe16/tmp"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
APK="$ROOT/.worktrees/hl1.probe16/app/app/build/outputs/apk/debug/app-debug.apk"
PKG="dev.agentmirror.app"
PORT=22461
UIDN="$(id -u)"
SOCK="$TMPD/tmux/tmux-${UIDN}/default"

mkdir -p "$TMPD/state" "$TMPD/uploads" "$TMPD/cwd-static" "$TMPD/bin" "$TMPD/tmux" "$TMPD/out"
cp -f "$ROOT/.team/nodes/hl1-verify2/tmp/agentmirrord" "$TMPD/agentmirrord"
chmod +x "$TMPD/agentmirrord"
ln -sfn /bin/bash "$TMPD/bin/claude"
cat > "$TMPD/cwd-static/frozen-alt.sh" <<'EOF'
# sourced inside exec claude (symlink to bash) so comm stays claude
printf '\033[?1049h\033[H\033[2JSTATIC_ALT_MARKER_092\n'
trap 'printf "\033[2J\033[H"' WINCH
while :; do read -r -t 3600 || true; done
EOF
cat > "$TMPD/tmux.conf" <<'EOF'
set -g history-limit 50000
set -g mouse off
set -g status off
EOF
printf 'P16TOK%s\n' "$(date +%s | tail -c 7 | tr -d '\n')" | tr -d '\n' > "$TMPD/token"
TOKEN="$(tr -d '\n\r' < "$TMPD/token")"
echo "$PORT" > "$TMPD/port"

# --- kill only our isolated tmux, never default socket ---
unset TMUX || true
export -n TMUX_TMPDIR 2>/dev/null || true
if [ -S "$SOCK" ]; then
  TMUX='' TMUX_TMPDIR="$TMPD/tmux" tmux -f /dev/null kill-server 2>/dev/null || true
  sleep 0.3
fi
if [ -f "$TMPD/daemon.pid" ]; then
  old="$(cat "$TMPD/daemon.pid" || true)"
  if [ -n "${old:-}" ] && kill -0 "$old" 2>/dev/null; then
    kill "$old" 2>/dev/null || true
    sleep 0.3
  fi
fi

TMUX='' TMUX_TMPDIR="$TMPD/tmux" tmux -f "$TMPD/tmux.conf" new-session -d \
  -s hl1p16-static -c "$TMPD/cwd-static" "$TMPD/bin/claude"
sleep 0.5
# self-check: session must be on our socket
SESS="$(TMUX='' TMUX_TMPDIR="$TMPD/tmux" tmux -f /dev/null list-sessions)"
echo "$SESS" > "$TMPD/out/tmux-sessions.txt"
echo "$SESS" | grep -q 'hl1p16-static' || { echo "FAIL: session not on own TMPD"; exit 1; }
echo "$SESS" | grep -v hl1p16-static && { echo "FAIL: extra sessions on isolation socket"; exit 1; } || true
python3 - <<PY
import os
p=os.environ.get("SOCK_PATH","")
PY
SOCK_LEN=$(python3 -c "import os; print(len(os.path.abspath('$SOCK')))")
echo "socket=$SOCK len=$SOCK_LEN" > "$TMPD/out/socket.txt"

TMUX='' TMUX_TMPDIR="$TMPD/tmux" tmux -f /dev/null send-keys -t hl1p16-static "source ./frozen-alt.sh" C-m
sleep 1
# pre-subscribe capture (own socket only)
TMUX='' TMUX_TMPDIR="$TMPD/tmux" tmux -f /dev/null capture-pane -p -t hl1p16-static > "$TMPD/out/pre-capture.txt"
TMUX='' TMUX_TMPDIR="$TMPD/tmux" tmux -f /dev/null display-message -p -t hl1p16-static \
  'alt=#{alternate_on} #{pane_width}x#{pane_height} cwd=#{pane_current_path}' > "$TMPD/out/pre-pane.txt"
grep -q STATIC_ALT_MARKER_092 "$TMPD/out/pre-capture.txt" || { echo "FAIL: marker missing before subscribe"; cat "$TMPD/out/pre-capture.txt"; exit 1; }
PPID_PANE=$(TMUX='' TMUX_TMPDIR="$TMPD/tmux" tmux -f /dev/null display-message -p -t hl1p16-static '#{pane_pid}')
COMM=$(ps -c -o comm= -p "$PPID_PANE" | tr -d ' ')
echo "pane_pid=$PPID_PANE comm=$COMM" > "$TMPD/out/pane-comm.txt"
BASECOMM=$(basename "$COMM")
[ "$BASECOMM" = "claude" ] || { echo "FAIL: comm=$COMM basename=$BASECOMM want claude"; exit 1; }

# daemon — do not bind 9900
TMUX='' TMUX_TMPDIR="$TMPD/tmux" AGENTMIRROR_TOKEN="$TOKEN" AGENTMIRROR_STATE_DIR="$TMPD/state" \
  "$TMPD/agentmirrord" -listen "0.0.0.0:$PORT" -upload-dir "$TMPD/uploads" \
  -log-level debug -list-interval 500ms >"$TMPD/daemon.log" 2>&1 &
echo $! > "$TMPD/daemon.pid"
for i in $(seq 1 40); do
  (echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break
  sleep 0.25
done
(echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null || { echo "FAIL: daemon not listening $PORT"; tail -20 "$TMPD/daemon.log"; exit 1; }
# confirm 9900 still the production pid
echo "daemon_ok port=$PORT pid=$(cat $TMPD/daemon.pid)" 

export ADB
"$ADB" reverse tcp:$PORT tcp:$PORT >/dev/null
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell am force-stop "$PKG"
"$ADB" shell pm clear "$PKG" >/dev/null
sleep 0.5

dumpui() { "$ADB" shell "uiautomator dump /sdcard/p16.xml >/dev/null 2>&1; cat /sdcard/p16.xml" 2>/dev/null; }

tap_text() {
  python3 - "$1" "$2" <<'PY'
import re, sys
xml=open(sys.argv[1],encoding='utf-8',errors='replace').read()
want=sys.argv[2]
for m in re.finditer(r'<node[^>]*/?>', xml):
    n=m.group(0)
    t=re.search(r'text="([^"]*)"', n)
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1)==want:
        x1,y1,x2,y2=map(int,b.groups())
        print((x1+x2)//2,(y1+y2)//2)
        sys.exit(0)
sys.exit(1)
PY
}

tap_contains() {
  python3 - "$1" "$2" <<'PY'
import re, sys
xml=open(sys.argv[1],encoding='utf-8',errors='replace').read()
want=sys.argv[2]
for m in re.finditer(r'<node[^>]*/?>', xml):
    n=m.group(0)
    t=re.search(r'text="([^"]*)"', n)
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and want in t.group(1):
        x1,y1,x2,y2=map(int,b.groups())
        print((x1+x2)//2,(y1+y2)//2)
        sys.exit(0)
sys.exit(1)
PY
}

edit_center() {
  python3 - "$1" "$2" <<'PY'
import re, sys
xml=open(sys.argv[1],encoding='utf-8',errors='replace').read()
idx=int(sys.argv[2]); n=0
for m in re.finditer(r'<node[^>]*/?>', xml):
    node=m.group(0)
    cls=re.search(r'class="([^"]*)"', node)
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if cls and 'EditText' in cls.group(1) and b:
        if n==idx:
            x1,y1,x2,y2=map(int,b.groups())
            print((x1+x2)//2,(y1+y2)//2)
            sys.exit(0)
        n+=1
sys.exit(1)
PY
}

"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
dumpui > "$TMPD/out/ui-01-pairing.xml"
if grep -q '手填连接' "$TMPD/out/ui-01-pairing.xml"; then
  read cx cy < <(tap_text "$TMPD/out/ui-01-pairing.xml" "手填连接" || true)
  if [ -n "${cx:-}" ]; then "$ADB" shell input tap "$cx" "$cy"; sleep 1; fi
fi
dumpui > "$TMPD/out/ui-02-form.xml"
read cx cy < <(edit_center "$TMPD/out/ui-02-form.xml" 0)
"$ADB" shell input tap "$cx" "$cy"; sleep 0.4
"$ADB" shell input text "ws://10.0.2.2:${PORT}/ws"
sleep 0.4
"$ADB" shell input keyevent 111
dumpui > "$TMPD/out/ui-03-url.xml"
read cx cy < <(edit_center "$TMPD/out/ui-03-url.xml" 1)
"$ADB" shell input tap "$cx" "$cy"; sleep 0.4
"$ADB" shell input text "$TOKEN"
sleep 0.4
"$ADB" shell input keyevent 111
dumpui > "$TMPD/out/ui-04-filled.xml"
read cx cy < <(tap_text "$TMPD/out/ui-04-filled.xml" "连接")
"$ADB" shell input tap "$cx" "$cy"
for i in $(seq 1 30); do
  if grep -q "listing: first snapshot" "$TMPD/daemon.log"; then echo "paired i=$i"; break; fi
  sleep 1
done
sleep 2
dumpui > "$TMPD/out/ui-05-l1.xml"
"$ADB" exec-out screencap -p > "$TMPD/out/shot-03-l1.png"

# cold start like verify2
"$ADB" shell am force-stop "$PKG"
sleep 1
"$ADB" logcat -c || true
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 4

# find cwd-static on L1; swipe if needed; NEVER tap fleet names
FOUND=0
for i in $(seq 1 12); do
  dumpui > "$TMPD/out/ui-l1-$i.xml"
  if grep -q 'text="cwd-static"' "$TMPD/out/ui-l1-$i.xml"; then FOUND=1; break; fi
  "$ADB" shell input swipe 540 1800 540 700 300
  sleep 0.6
done
[ "$FOUND" = 1 ] || { echo "FAIL: cwd-static not in L1"; exit 1; }
read cx cy < <(tap_text "$TMPD/out/ui-l1-$i.xml" "cwd-static")
"$ADB" exec-out screencap -p > "$TMPD/out/shot-l1-static.png"
"$ADB" shell input tap "$cx" "$cy"
sleep 2
dumpui > "$TMPD/out/ui-l2.xml"
grep -q 'cwd-static' "$TMPD/out/ui-l2.xml" || { echo "FAIL: not on cwd-static L2"; exit 1; }
"$ADB" exec-out screencap -p > "$TMPD/out/shot-l2-static.png"
read cx cy < <(tap_text "$TMPD/out/ui-l2.xml" "bash")
# start logcat then tap
"$ADB" logcat -v epoch AMPROBE16:I '*:S' > "$TMPD/out/logcat-amp.txt" 2>&1 &
echo $! > "$TMPD/out/logcat.pid"
sleep 0.3
TAP_MS=$(python3 -c 'import time; print(int(time.time()*1000))')
echo "$TAP_MS" > "$TMPD/out/tap-wall-ms.txt"
"$ADB" shell input tap "$cx" "$cy"
# sample 20s
for n in $(seq 0 20); do
  sleep 1
  "$ADB" exec-out screencap -p > "$TMPD/out/shot-t${n}s.png" || true
done
"$ADB" shell input keyevent 111 || true
sleep 0.3
"$ADB" exec-out screencap -p > "$TMPD/out/shot-end.png"
if [ -f "$TMPD/out/logcat.pid" ]; then
  kill "$(cat "$TMPD/out/logcat.pid")" 2>/dev/null || true
fi
# extra dump of logcat buffer
"$ADB" logcat -d -v epoch AMPROBE16:I '*:S' > "$TMPD/out/logcat-amp-dump.txt" || true
grep -E 'subscribe|snapshot|sendq|KindSnapshot|binary|error' "$TMPD/daemon.log" | tail -80 > "$TMPD/out/daemon-sub.txt" || true
echo "DONE tap_wall_ms=$TAP_MS"
ls -l "$TMPD/out" | tail
