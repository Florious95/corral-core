#!/bin/bash
set -eu
ADB=/Users/alauda/Library/Android/sdk/platform-tools/adb
PKG=dev.agentmirror.app
OUT=$1
SIDE=$2

dump() {
  "$ADB" -s emulator-5554 shell uiautomator dump "/sdcard/swipe-$SIDE.xml" >/dev/null
  "$ADB" -s emulator-5554 pull "/sdcard/swipe-$SIDE.xml" "$OUT/$SIDE-current.xml" >/dev/null
}

tap_text() {
  local xml=$1 want=$2 xy
  xy=$(python3 - "$xml" "$want" <<'PY'
import re,sys
x=open(sys.argv[1]).read()
for n in re.findall(r'<node[^>]*/?>',x):
 t=re.search(r'text="([^"]*)"',n); b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
 if t and b and t.group(1)==sys.argv[2]:
  x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
)
  test -n "$xy"; "$ADB" -s emulator-5554 shell input tap $xy; sleep 2
}

"$ADB" -s emulator-5554 shell am force-stop "$PKG"
"$ADB" -s emulator-5554 shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
dump
if ! rg -q 'text="输入指令…"|class="android.widget.EditText".*bounds="\[158,' "$OUT/$SIDE-current.xml"; then
  if rg -q 'text="‹ 工作区"' "$OUT/$SIDE-current.xml"; then
    tap_text "$OUT/$SIDE-current.xml" claude.exe
  else
    tap_text "$OUT/$SIDE-current.xml" /Volumes/nvme/Projects/远程Agent安卓
    dump
    tap_text "$OUT/$SIDE-current.xml" claude.exe
  fi
  dump
fi
rg -q 'text="输入指令…"|class="android.widget.EditText".*bounds="\[158,' "$OUT/$SIDE-current.xml"
"$ADB" -s emulator-5554 shell dumpsys window | rg -m2 'mCurrentFocus|mFocusedApp' > "$OUT/$SIDE-before-focus.txt"
cp "$OUT/$SIDE-current.xml" "$OUT/$SIDE-before.xml"
if [ "$SIDE" = left ]; then
  "$ADB" -s emulator-5554 shell input swipe 5 1200 500 1200 300
else
  "$ADB" -s emulator-5554 shell input swipe 1075 1200 580 1200 300
fi
sleep 2
"$ADB" -s emulator-5554 shell dumpsys window | rg -m2 'mCurrentFocus|mFocusedApp' > "$OUT/$SIDE-after-focus.txt"
dump
cp "$OUT/$SIDE-current.xml" "$OUT/$SIDE-after.xml"
