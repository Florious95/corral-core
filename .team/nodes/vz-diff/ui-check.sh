#!/usr/bin/env bash
# ui-check.sh — t.diff 084 输入框差分同步（退出码即判据）
# 改前必须红、改后必须绿。trap 收尾。不启动模拟器、不碰用户 tmux、不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="/Volumes/nvme/Projects/远程Agent安卓"
APP="$ROOT/app"
SRC="$APP/app/src/main/java/dev/agentmirror/app"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -f "$SRC/session/DiffSync.kt" ] || fail "缺 DiffSync.kt"
[ -f "$SRC/session/SessionViewModel.kt" ] || fail "缺 SessionViewModel.kt"
grep -q 'fun plan(synced: String, current: String)' "$SRC/session/DiffSync.kt" \
  || fail "DiffSync.plan 不存在"
grep -q 'DiffSync.plan' "$SRC/session/SessionViewModel.kt" \
  || fail "SessionViewModel 未调用 DiffSync.plan"
grep -q 'if (isComposing)' "$SRC/session/SessionViewModel.kt" \
  || fail "组合期未守卫"
# 旧算法用公共后缀，会在行尾 CLI 上改错中间字
if grep -q 'sealed interface TextDelta' "$SRC/session/SessionViewModel.kt"; then
  fail "TextDelta 后缀算法还在 SessionViewModel"
fi
# 实时：onPassthroughInput 路径不得 delay
if grep -n 'delay(' "$SRC/session/SessionViewModel.kt" | grep -q -i 'passthrough\|diffsync\|applyDiff'; then
  fail "差分路径引入了 delay"
fi

# 单测名含 DiffSync
(cd "$APP" && ./gradlew -q :app:testDebugUnitTest --tests '*DiffSync*') \
  || fail "DiffSync 单测未绿"

[ -x "$ADB" ] || fail "adb 不在 $ADB"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

run_ui() {
  local tag="$1"
  "$ADB" shell am start -n dev.agentmirror.app/.MainActivity >/dev/null 2>&1 || true
  sleep 0.8
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  python3 "$ROOT/tools/uiassert.py" save "$NODE/ui-tree-$tag.xml" >/dev/null \
    || fail "uiassert 没写出 $tag"
  [ -s "$NODE/ui-tree-$tag.xml" ] || fail "uiassert 空树 $tag"
  "$ADB" exec-out screencap -p > "$NODE/shot-$tag.png" || fail "截图失败 $tag"
  [ -s "$NODE/shot-$tag.png" ] || fail "空截图 $tag"
}

run_ui "default"

"$ADB" shell wm density 480 >/dev/null
sleep 1
run_ui "d480"
"$ADB" shell wm density 440 >/dev/null
sleep 1
run_ui "d440"
"$ADB" shell wm density reset >/dev/null

echo "PASS t.diff vz-diff"
