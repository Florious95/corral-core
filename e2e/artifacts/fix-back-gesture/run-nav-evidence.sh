#!/usr/bin/env bash
set -euo pipefail

ROOT=/Volumes/nvme/Projects/远程Agent安卓
ADB=/Users/alauda/Library/Android/sdk/platform-tools/adb
SERIAL=emulator-5556
PKG=dev.agentmirror.app
APK="$ROOT/app/app/build/outputs/apk/debug/app-debug.apk"
DAEMON="$ROOT/e2e/bin/agentmirrord"
OUT="$ROOT/e2e/artifacts/fix-back-gesture"
RUN_TMP=$(mktemp -d /tmp/fix-back-gesture.XXXXXX)
# macOS 的 /tmp 指向 /private/tmp；daemon 返回物理 cwd，UI 语义定位也必须用同一口径。
RUN_TMP=$(cd "$RUN_TMP" && pwd -P)
DAEMON_PID=
mkdir -p "$OUT" "$RUN_TMP/state" "$RUN_TMP/uploads" "$RUN_TMP/cwd" "$RUN_TMP/tmux"

cleanup() {
  if [[ -n "$DAEMON_PID" ]]; then
    kill "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
  fi
  TMUX='' TMUX_TMPDIR="$RUN_TMP/tmux" tmux -f /dev/null kill-server 2>/dev/null || true
  rm -rf "$RUN_TMP"
}
trap cleanup EXIT

dump() {
  local name=$1
  "$ADB" -s "$SERIAL" shell uiautomator dump "/sdcard/$name.xml" >/dev/null
  "$ADB" -s "$SERIAL" pull "/sdcard/$name.xml" "$OUT/$name.xml" >/dev/null
}

capture() {
  local name=$1
  dump "$name"
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/$name.png"
  "$ADB" -s "$SERIAL" shell dumpsys window | rg -m2 'mCurrentFocus|mFocusedApp' > "$OUT/$name-focus.txt"
}

tap_text() {
  local xml=$1 want=$2 xy
  xy=$(python3 - "$xml" "$want" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for node in re.findall(r"<node[^>]*/?>", xml):
    text = re.search(r'text="([^"]*)"', node)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if text and bounds and text.group(1) == sys.argv[2]:
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [[ -n "$xy" ]]
  "$ADB" -s "$SERIAL" shell input tap $xy
  sleep 2
}

edit_center() {
  local xml=$1 index=$2
  python3 - "$xml" "$index" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
nodes = []
for node in re.findall(r"<node[^>]*/?>", xml):
    if 'class="android.widget.EditText"' not in node:
        continue
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if bounds:
        x1, y1, x2, y2 = map(int, bounds.groups())
        nodes.append(((x1 + x2) // 2, (y1 + y2) // 2))
print(*nodes[int(sys.argv[2])])
PY
}

enter_level3() {
  "$ADB" -s "$SERIAL" shell am force-stop "$PKG"
  "$ADB" -s "$SERIAL" shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 3
  dump nav-current
  if rg -q 'text="输入指令…"|class="android.widget.EditText".*bounds="\[158,' "$OUT/nav-current.xml"; then
    return
  fi
  if rg -q 'text="‹ 工作区"' "$OUT/nav-current.xml"; then
    tap_text "$OUT/nav-current.xml" claude.exe
  else
    tap_text "$OUT/nav-current.xml" "$RUN_TMP/cwd"
    dump nav-sessions
    tap_text "$OUT/nav-sessions.xml" claude.exe
  fi
  dump nav-level3
  rg -q 'text="输入指令…"|class="android.widget.EditText".*bounds="\[158,' "$OUT/nav-level3.xml"
  "$ADB" -s "$SERIAL" shell dumpsys window | rg -m2 'mCurrentFocus|mFocusedApp' | rg -q "$PKG"
}

assert_level2() {
  local xml=$1
  rg -q 'text="‹ 工作区"' "$xml"
  rg -q 'text="claude.exe"' "$xml"
  ! rg -q 'text="输入指令…"|class="android.widget.EditText".*bounds="\[158,' "$xml"
}

assert_level1() {
  local xml=$1
  rg -q 'text="工作区"' "$xml"
  rg -q "text=\"$RUN_TMP/cwd\"" "$xml"
  ! rg -q 'text="‹ 工作区"' "$xml"
}

assert_pairing_root() {
  local xml=$1
  rg -q 'text="连接主机"' "$xml"
  rg -q 'text="手填连接"' "$xml"
}

PORT=$((22000 + RANDOM % 1000))
TOKEN="NAV$(date +%s)${RANDOM}"
TMUX='' TMUX_TMPDIR="$RUN_TMP/tmux" tmux -f /dev/null new-session -d -s claude.exe \
  -c "$RUN_TMP/cwd" "exec env -i HOME='$HOME' USER='$USER' PATH='$PATH' TERM=xterm-256color LANG='${LANG:-en_US.UTF-8}' claude --dangerously-skip-permissions"
sleep 3
TMUX_TMPDIR="$RUN_TMP/tmux" \
  AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$RUN_TMP/tmux/tmux-$(id -u)" \
  AGENTMIRROR_TOKEN="$TOKEN" AGENTMIRROR_STATE_DIR="$RUN_TMP/state" \
  "$DAEMON" -listen "0.0.0.0:$PORT" -upload-dir "$RUN_TMP/uploads" -list-interval 500ms \
  > "$RUN_TMP/daemon.log" 2>&1 &
DAEMON_PID=$!
for _ in $(seq 1 30); do
  if lsof -nP -a -p "$DAEMON_PID" -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then break; fi
  sleep 0.25
done
kill -0 "$DAEMON_PID"

"$ADB" -s "$SERIAL" install -r "$APK" > "$OUT/install.log"
"$ADB" -s "$SERIAL" shell pm clear "$PKG" >> "$OUT/install.log"
"$ADB" -s "$SERIAL" shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
dump pairing
read -r url_x url_y < <(edit_center "$OUT/pairing.xml" 0)
read -r token_x token_y < <(edit_center "$OUT/pairing.xml" 1)
"$ADB" -s "$SERIAL" shell input tap "$url_x" "$url_y"
"$ADB" -s "$SERIAL" shell input text "ws://10.0.2.2:$PORT/ws"
"$ADB" -s "$SERIAL" shell input tap "$token_x" "$token_y"
"$ADB" -s "$SERIAL" shell input text "$TOKEN"
dump pairing-filled
tap_text "$OUT/pairing-filled.xml" 连接
sleep 6
enter_level3

capture left-before
"$ADB" -s "$SERIAL" shell input swipe 5 1200 500 1200 300
sleep 2
capture left-after
assert_level2 "$OUT/left-after.xml"
rg -q "$PKG" "$OUT/left-after-focus.txt"

enter_level3
capture right-before
"$ADB" -s "$SERIAL" shell input swipe 1075 1200 580 1200 300
sleep 2
capture right-after
assert_level2 "$OUT/right-after.xml"
rg -q "$PKG" "$OUT/right-after-focus.txt"

enter_level3
capture d32-level3
"$ADB" -s "$SERIAL" shell input swipe 5 1200 500 1200 300
sleep 2
capture d32-level2
assert_level2 "$OUT/d32-level2.xml"
"$ADB" -s "$SERIAL" shell input swipe 5 1200 500 1200 300
sleep 2
capture d32-level1
assert_level1 "$OUT/d32-level1.xml"
"$ADB" -s "$SERIAL" shell input swipe 5 1200 500 1200 300
sleep 2
capture d32-pairing-root
assert_pairing_root "$OUT/d32-pairing-root.xml"
rg -q "$PKG" "$OUT/d32-pairing-root-focus.txt"

shasum -a 256 "$APK" > "$OUT/apk.sha256"
printf 'left=level2\nright=level2\nd32=level3_to_level2_to_level1_to_pairing_root\n' > "$OUT/result.txt"
