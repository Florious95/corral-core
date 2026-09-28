#!/bin/bash
set -eu

ADB=/Users/alauda/Library/Android/sdk/platform-tools/adb
SERIAL=emulator-5554
PKG=dev.agentmirror.app
ROOT=/Volumes/nvme/Projects/远程Agent安卓/e2e/artifacts/abc-regression
APK=$1
GROUP=$2
OUT="$ROOT/$GROUP"
PAIR_FILE=/tmp/agentmirror-ab-redraw/pair.secret

mkdir -p "$OUT/frames"

dump_ui() {
  local name=$1
  "$ADB" -s "$SERIAL" shell uiautomator dump "/sdcard/$name.xml" >/dev/null
  "$ADB" -s "$SERIAL" pull "/sdcard/$name.xml" "$OUT/$name.xml" >/dev/null
}

tap_text() {
  local xml=$1 want=$2 xy
  xy=$(python3 - "$xml" "$want" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
for node in re.findall(r'<node[^>]*/?>', xml):
    text = re.search(r'text="([^"]*)"', node)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if text and bounds and text.group(1) == sys.argv[2]:
        x1,y1,x2,y2 = map(int, bounds.groups())
        print((x1+x2)//2, (y1+y2)//2)
        break
PY
)
  test -n "$xy"
  "$ADB" -s "$SERIAL" shell input tap $xy
  sleep 2
}

focus_log() {
  "$ADB" -s "$SERIAL" shell dumpsys window | rg -m2 'mCurrentFocus|mFocusedApp'
}

enter_level3() {
  "$ADB" -s "$SERIAL" shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 3
  dump_ui nav
  if rg -q 'text="输入指令…"|class="android.widget.EditText".*bounds="\[158,' "$OUT/nav.xml"; then return; fi
  if rg -q 'text="claude.exe"' "$OUT/nav.xml" && rg -q 'text="‹ 工作区"' "$OUT/nav.xml"; then
    tap_text "$OUT/nav.xml" claude.exe
  else
    tap_text "$OUT/nav.xml" /Volumes/nvme/Projects/远程Agent安卓
    dump_ui nav-sessions
    tap_text "$OUT/nav-sessions.xml" claude.exe
  fi
  dump_ui nav-level3
  rg -q 'text="输入指令…"|class="android.widget.EditText".*bounds="\[158,' "$OUT/nav-level3.xml"
  focus_log | rg -q 'dev.agentmirror.app'
}

"$ADB" -s "$SERIAL" uninstall "$PKG" > "$OUT/install.log" 2>&1 || true
"$ADB" -s "$SERIAL" install "$APK" >> "$OUT/install.log" 2>&1
"$ADB" -s "$SERIAL" shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
dump_ui pair

read url_x url_y token_x token_y < <(python3 - "$OUT/pair.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
centers=[]
for node in re.findall(r'<node[^>]*/?>', xml):
    if 'class="android.widget.EditText"' not in node: continue
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    x1,y1,x2,y2=map(int,b.groups()); centers += [(x1+x2)//2,(y1+y2)//2]
print(*centers[:4])
PY
)
"$ADB" -s "$SERIAL" shell input tap "$url_x" "$url_y"
"$ADB" -s "$SERIAL" shell input text 'ws://10.0.2.2:19988/ws'
"$ADB" -s "$SERIAL" shell input keyevent 4
"$ADB" -s "$SERIAL" shell input tap "$token_x" "$token_y"
PAIR_SECRET=$(<"$PAIR_FILE")
"$ADB" -s "$SERIAL" shell input text "$PAIR_SECRET"
unset PAIR_SECRET
"$ADB" -s "$SERIAL" shell input keyevent 4
dump_ui pair-filled
tap_text "$OUT/pair-filled.xml" 连接
sleep 5
enter_level3

focus_log > "$OUT/redraw-focus-before.txt"
dump_ui redraw-before
"$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/redraw-before.png"
"$ADB" -s "$SERIAL" shell screenrecord --bit-rate 12000000 --size 1080x2400 --time-limit 25 "/sdcard/$GROUP-redraw.mp4" >/dev/null 2>&1 &
sleep 1
"$ADB" -s "$SERIAL" shell input tap 540 2245
sleep 3
dump_ui redraw-focus
"$ADB" -s "$SERIAL" shell input text AAAAAAAAAAAAAAAAAAAA
sleep 2
dump_ui redraw-stage1
focus_log | rg -q 'dev.agentmirror.app'
"$ADB" -s "$SERIAL" shell input text BBBBBBBBBBBBBBBBBBBB
sleep 2
dump_ui redraw-stage2
focus_log | rg -q 'dev.agentmirror.app'
"$ADB" -s "$SERIAL" shell input text CCCCCCCCCCCCCCCCCCCC
sleep 2
dump_ui redraw-stage3
focus_log > "$OUT/redraw-focus-after.txt"
"$ADB" -s "$SERIAL" shell input keyevent 4
sleep 14
"$ADB" -s "$SERIAL" pull "/sdcard/$GROUP-redraw.mp4" "$OUT/redraw.mp4" >/dev/null
ffmpeg -loglevel error -y -i "$OUT/redraw.mp4" -vf fps=10 "$OUT/frames/frame-%04d.png"
ffmpeg -loglevel error -y -i "$OUT/redraw.mp4" -vf 'fps=2,scale=270:-1,tile=4x4' -frames:v 1 "$OUT/contact.png"

enter_level3
focus_log > "$OUT/left-before-focus.txt"
dump_ui left-before
"$ADB" -s "$SERIAL" shell input swipe 5 1200 500 1200 300
sleep 2
focus_log > "$OUT/left-after-focus.txt"
dump_ui left-after

"$ADB" -s "$SERIAL" shell am force-stop "$PKG"
enter_level3
focus_log > "$OUT/right-before-focus.txt"
dump_ui right-before
"$ADB" -s "$SERIAL" shell input swipe 1075 1200 580 1200 300
sleep 2
focus_log > "$OUT/right-after-focus.txt"
dump_ui right-after

for stage in before focus stage1 stage2 stage3; do
  printf '%s ' "$stage"
  sed 's/></>\n</g' "$OUT/redraw-$stage.xml" | rg -m1 ViewFactoryHolder | sed -E 's/.*bounds="([^"]+)".*/terminal=\1/'
  sed 's/></>\n</g' "$OUT/redraw-$stage.xml" | rg -m1 'class="android.widget.EditText"' | sed -E 's/.*bounds="([^"]+)".*/input=\1/'
done > "$OUT/bounds.txt"

printf 'group=%s\n' "$GROUP"
cat "$OUT/bounds.txt"
printf 'left-after '; head -1 "$OUT/left-after-focus.txt"
printf 'right-after '; head -1 "$OUT/right-after-focus.txt"
