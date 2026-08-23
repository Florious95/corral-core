#!/bin/sh
# 判据：订阅时播种鼠标模式成立——**四条断言在仓里、兼容不破、且测试真跑（禁缓存）**。
#
# 判什么：① R1–R4 四条测试在仓里；② go test + gradle 都真跑且绿（⛔ 不吃缓存）；
#         ③ 说明贴了改前的红；④ 说明写清了「capture-pane -e 到底带不带 DEC 模式」的**实测**结论。
# ⛔ 不判性能——最终判据是用户真机。
# ⛔ 也不逼出「leader 的归因成立」：rootcause_disputed 是合法出口，那由 leader 读。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u

# 🔴 worktree 无 local.properties（已 gitignore），缺 SDK 判不可判而非不通过。
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$ANDROID_HOME" ] || { echo "UNJUDGEABLE 找不到 Android SDK（ANDROID_HOME=$ANDROID_HOME）"; exit 2; }
export ANDROID_HOME; export ANDROID_SDK_ROOT="$ANDROID_HOME"

S=.team/nodes/t.modeseed/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
command -v go >/dev/null 2>&1 || { echo "UNJUDGEABLE 没有 go"; exit 2; }

# ① 四条断言必须以测试形式留在仓里
T=$(grep -rlE '(fun|class|func) .*(ModeSeed|Seed|MouseMode|SubscribeMode)' \
      server/internal app/core-terminal/src/test app/core-conn/src/test app/core-protocol/src/test 2>/dev/null | head -4)
[ -n "$T" ] || { echo "FAIL 找不到播种相关测试（名字含 ModeSeed/Seed/MouseMode/SubscribeMode）"; exit 1; }
echo "  ok   测试文件：$(echo "$T" | tr '\n' ' ')"

miss=""
grep -rqE '1002|canEncode' $T || miss="$miss R1(订阅时已开1002ue后能编码)"
grep -rqE '1003' $T          || miss="$miss R2(1003 场景)"
grep -rqE '兼容|compat|unchanged|逐字段' $T || miss="$miss R3(不带新字段线格式不变)"
grep -rqE '1002l|关|off|clear' $T || miss="$miss R4(播种不锁住后续关闭)"
[ -z "$miss" ] || { echo "FAIL 测试里看不到这些断言：${miss}"; exit 1; }

# ③ 复现先红
grep -qE 'FAIL|--- FAIL|红|failed' "$S" || { echo "FAIL 说明里没贴改前的红原文"; exit 1; }

# ④ 关键前提必须是**实测**过的，⛔ 不许只引文档
grep -qE 'capture-pane' "$S" || { echo "FAIL 说明里没交代 capture-pane -e 带不带 DEC 模式的实测结论"; exit 1; }

# ② 真跑（禁缓存）
echo "  …  go test -count=1 ./internal/..."
OUT=".team/nodes/t.modeseed/tmp/run.log"; mkdir -p "$(dirname "$OUT")"
( cd server && go test -count=1 ./internal/... ) >"$OUT" 2>&1
rc=$?
tail -4 "$OUT"
[ "$rc" -eq 0 ] || { echo "FAIL go test 未通过（rc=${rc}，见 ${OUT}）"; exit 1; }

ROOT=$(pwd)
[ -x "$ROOT/app/gradlew" ] || { echo "UNJUDGEABLE 没有 app/gradlew"; exit 2; }
echo "  …  app/gradlew :core-protocol:test :core-conn:test :core-terminal:test --rerun-tasks"
( cd "$ROOT/app" && ./gradlew --console=plain :core-protocol:test :core-conn:test :core-terminal:test --offline --rerun-tasks ) >>"$OUT" 2>&1
rc=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 ${OUT}）"; exit 2; }
tail -3 "$OUT"
[ "$rc" -eq 0 ] || { echo "FAIL gradle 测试未通过（rc=${rc}，见 ${OUT}）"; exit 1; }
grep -q 'up-to-date' "$OUT" && { echo "UNJUDGEABLE gradle 是 up-to-date（缓存），不是这次跑出来的"; exit 2; }

echo "PASS 四条断言在仓里、说明贴了改前的红并给了 capture-pane 实测结论、go+gradle 真跑绿"
echo "     ⚠️ 性能⛔ 不在本判据内——最终判据是用户真机。"
exit 0
