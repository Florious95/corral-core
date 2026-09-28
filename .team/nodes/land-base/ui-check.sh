#!/usr/bin/env bash
# ui-check.sh — t.base 落位探针（退出码即判据）
#
# 本格不改现有屏，UI 树不会因本格变。断言：
#   1. 设计包已落进 app/ui（编译产物 / 源文件）
#   2. SessionStatus 三态、unknown 色 token、映射不把 unknown 写成 Idle
#   3. uiassert.py 能取到当前屏（回归：现有 UI 仍在）
#
# 改前必须红、改后必须绿。trap 收尾。不启动模拟器、不起 daemon、不碰用户 tmux、不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$NODE/../../.." && pwd)"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
UI="$ROOT/app/app/src/main/java/dev/agentmirror/app/ui"
CLS="$ROOT/app/app/build/tmp/kotlin-classes/debugUnitTest/dev/agentmirror/app/ui/model/SessionStatus.class"
# debug 编译产物路径因 AGP 版本可能不同，下面用 find 兜底

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -f "$UI/model/Models.kt" ] || fail "未落位 Models.kt"
[ -f "$UI/components/CommonUi.kt" ] || fail "未落位 CommonUi.kt"
[ -f "$UI/screens/SessionShellScreen.kt" ] || fail "未落位 SessionShellScreen.kt"
[ -f "$UI/theme/DesignTokens.kt" ] || fail "未落位 DesignTokens.kt"
[ -f "$UI/theme/AppTheme.kt" ] || fail "未落位 AppTheme.kt（设计 Theme.kt 为与现有 Theme.kt 并存）"

grep -q 'enum class SessionStatus { Busy, Idle, Unknown }' "$UI/model/Models.kt" \
  || fail "SessionStatus 不是三态 Busy/Idle/Unknown"
grep -q 'L2Status.UNKNOWN -> SessionStatus.Unknown' "$UI/model/Models.kt" \
  || fail "映射未把 L2 UNKNOWN 接到 SessionStatus.Unknown"
if grep -E 'UNKNOWN -> SessionStatus\.Idle|"unknown" -> SessionStatus\.Idle' "$UI/model/Models.kt"; then
  fail "映射把 unknown 写成了 Idle"
fi
grep -q 'val unknownDot: Color' "$UI/theme/DesignTokens.kt" || fail "AppPalette 缺 unknownDot"
grep -q 'unknownDot = Color(0xFFC03A62)' "$UI/theme/DesignTokens.kt" || fail "浅色 unknownDot 未进 LightPalette"
grep -q 'unknownDot = Color(0xFFF0879F)' "$UI/theme/DesignTokens.kt" || fail "深色 unknownDot 未进 DarkPalette"
grep -q 'status: SessionStatus' "$UI/screens/SessionShellScreen.kt" || fail "RunningDot/顶栏仍是 Boolean running"
grep -q 'fun statusVisuals' "$UI/components/CommonUi.kt" || fail "StatusChip 未走三态 statusVisuals"

# 编译产物：本格不改现有屏，但必须能编过。已编过则直接核 class；否则编 debug Kotlin。
if ! find "$ROOT/app/app/build" -name 'SessionStatus.class' 2>/dev/null | grep -q .; then
  (cd "$ROOT/app" && ./gradlew -q :app:compileDebugKotlin :app:compileDebugUnitTestKotlin)
fi
find "$ROOT/app/app/build" -name 'SessionStatus.class' | grep -q . \
  || fail "编译产物 SessionStatus.class 不存在"

# uiassert：本格 UI 树不变，只核能读到树（尺子可用），并落一份 XML 证据。
[ -x "$ADB" ] || fail "adb 不在 $ADB"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"
python3 "$ROOT/tools/uiassert.py" save "$NODE/ui-tree.xml" >/dev/null
[ -s "$NODE/ui-tree.xml" ] || fail "uiassert.py save 没写出树"

echo "PASS t.base land-base"
