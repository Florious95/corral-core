#!/usr/bin/env bash
# ui-check.sh — t.chrome 083 §3–§10（退出码即判据）
# 改前必须红、改后必须绿。trap 收尾。不启动模拟器、不碰用户 tmux、不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
# 脚本可在仓根 .team/nodes/vz-chrome 或 worktree 同路径
if [ -d "$NODE/../../../app/app/src" ]; then
  ROOT="$(cd "$NODE/../../.." && pwd)"
elif [ -d "$NODE/../../../../.worktrees/wt20.chrome/app/app/src" ]; then
  ROOT="$(cd "$NODE/../../../.." && pwd)"
else
  ROOT="$(cd "$NODE/../../.." && pwd)"
fi
if [ -d "$ROOT/.worktrees/wt20.chrome/app/app/src" ]; then
  APP="$ROOT/.worktrees/wt20.chrome/app"
else
  APP="$ROOT/app"
fi
SRC="$APP/app/src/main/java/dev/agentmirror/app"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -f "$SRC/ui/theme/DesignTokens.kt" ] || fail "缺 DesignTokens.kt"
[ -f "$SRC/ui/theme/TerminalSpec.kt" ] || fail "缺 TerminalSpec.kt"
[ -f "$SRC/ui/theme/AppTheme.kt" ] || fail "缺 AppTheme.kt"
[ -f "$SRC/ui/screens/SessionShellScreen.kt" ] || fail "缺 SessionShellScreen.kt"

grep -q 'val terminalCardMargin: Dp = 4.dp' "$SRC/ui/theme/DesignTokens.kt" \
  || fail "卡片外间距不是 4dp"
grep -q 'val paddingLeft: Dp = 6.dp' "$SRC/ui/theme/TerminalSpec.kt" \
  || fail "终端内 padding 不是 6dp"
grep -q 'const val maxCols: Int = 112' "$SRC/ui/theme/TerminalSpec.kt" \
  || fail "缺 cols 上限 112"
grep -q 'is InputStatus.Sent -> null' "$SRC/session/SessionScreen.kt" \
  || fail "成功态「已发送」还在"
if grep -q '"已发送"' "$SRC/session/SessionScreen.kt"; then
  fail "SessionScreen 仍有「已发送」文案"
fi
grep -q 'InputStatus.Failed' "$SRC/session/SessionScreen.kt" \
  || fail "失败态被删了"
grep -q 'BasicTextField' "$SRC/ui/screens/SessionShellScreen.kt" || fail "缺输入框"
grep -q 'value = draft' "$SRC/ui/screens/SessionShellScreen.kt" || fail "输入框未绑 draft"
grep -q 'draft: TextFieldValue' "$SRC/ui/screens/SessionShellScreen.kt" \
  || fail "输入框仍是 String 重载"
grep -q 'path.label' "$SRC/ui/components/CommonUi.kt" \
  || fail "LanPill 仍写死 LAN"
grep -q 'surfaceContainer =' "$SRC/ui/theme/AppTheme.kt" \
  || fail "Light/Dark scheme 缺 surfaceContainer"
grep -q 'listConnector' "$SRC/service/ServiceWire.kt" \
  || fail "缺 listConnector 扇出"
grep -q 'fun BackChevron' "$SRC/ui/components/CommonUi.kt" \
  || fail "缺 BackChevron 光学对齐"

# 单测名含 ConsoleChrome
(cd "$APP" && ./gradlew -q :app:testDebugUnitTest --tests 'dev.agentmirror.app.ui.ConsoleChromeTest') \
  || fail "ConsoleChromeTest 未绿"

[ -x "$ADB" ] || fail "adb 不在 $ADB"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

run_ui() {
  local tag="$1"
  "$ADB" shell am start -n dev.agentmirror.app/.MainActivity >/dev/null 2>&1 || true
  sleep 0.8
  python3 "$ROOT/tools/uiassert.py" save "$NODE/ui-tree-$tag.xml" >/dev/null \
    || fail "uiassert 没写出 $tag"
  [ -s "$NODE/ui-tree-$tag.xml" ] || fail "uiassert 空树 $tag"
  python3 "$ROOT/tools/uiassert.py" absent "已发送" || fail "屏上出现「已发送」 density=$tag"
}

run_ui "default"

# 整数密度 3.0 = 480dpi
"$ADB" shell wm density 480 >/dev/null
sleep 1
run_ui "d480"
# 非整数密度 2.75 = 440dpi
"$ADB" shell wm density 440 >/dev/null
sleep 1
run_ui "d440"
"$ADB" shell wm density reset >/dev/null

echo "PASS t.chrome vz-chrome"
