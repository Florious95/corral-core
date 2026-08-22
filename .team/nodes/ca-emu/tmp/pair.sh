#!/usr/bin/env bash
# 手填配对到隔离 daemon。语义定位，⛔ 不识图、⛔ 不点真实舰队。
set -uo pipefail
T="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$T/uilib.sh"
PORT="${PORT:?}"
TOKEN="${TOKEN:?}"
APK="${APK:?}"

"$ADB" reverse tcp:"$PORT" tcp:"$PORT" >/dev/null 2>&1 || true
"$ADB" install -r "$APK"
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell pm clear "$PKG" >/dev/null 2>&1
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 4

ok=0
for i in $(seq 1 20); do
  dumpui > "$T/p0.xml"
  if grep -q '手填连接' "$T/p0.xml"; then ok=1; break; fi
  sleep 1
done
[ "$ok" = 1 ] || { echo "PAIR FAIL: 配对页未出现"; exit 2; }

CX0=$(edittext_center "$T/p0.xml" 0) || { echo "PAIR FAIL: url field"; exit 2; }
"$ADB" shell input tap ${CX0% *} ${CX0#* } >/dev/null 2>&1; sleep 1
"$ADB" shell input text "ws://127.0.0.1:${PORT}/ws" >/dev/null 2>&1; sleep 1
"$ADB" shell input keyevent 111 >/dev/null 2>&1; sleep 1

dumpui > "$T/p1.xml"
CX1=$(edittext_center "$T/p1.xml" 1) || { echo "PAIR FAIL: token field"; exit 2; }
"$ADB" shell input tap ${CX1% *} ${CX1#* } >/dev/null 2>&1; sleep 1
"$ADB" shell input text "$TOKEN" >/dev/null 2>&1; sleep 1
"$ADB" shell input keyevent 111 >/dev/null 2>&1; sleep 1

dumpui > "$T/p2.xml"
tap_text "$T/p2.xml" "连接" || { echo "PAIR FAIL: 连接按钮"; exit 2; }

ok=0
for i in $(seq 1 30); do
  sleep 1
  dumpui > "$T/p3.xml"
  # 列表行可能只渲染末段目录名（cwd），不全量打印绝对路径
  if grep -q "$T/cwd" "$T/p3.xml" || grep -q 'text="cwd"' "$T/p3.xml"; then ok=1; break; fi
  if grep -q '手填连接' "$T/p3.xml"; then
    echo "PAIR still on form at t=${i}s"
  fi
done
[ "$ok" = 1 ] || { echo "PAIR FAIL: 列表页未出现夹具工作区"; exit 2; }
echo "PAIR OK"
