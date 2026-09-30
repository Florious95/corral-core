#!/bin/sh
# 判据：第 3 步成立——**模式跟踪与 SGR1006 编码都有测试、1003 降级留痕在、且测试真跑（禁缓存）**。
#
# 判什么：① R1–R4 四条测试在仓里；② core-terminal 测试真跑且绿（--rerun-tasks，⛔ 不吃缓存）；
#         ③ 说明贴了改前的红；④ 服务端与 APP 壳零改动（这一格只动核层）；
#         ⑤ 编码里⛔ 不许出现 X10/1005/1015 的老编码路径。
# ⛔ 不判性能——最终判据是用户真机。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u

# 🔴 worktree 里没有 local.properties（它按机器路径生成、已 gitignore），
# gradle 会报 "SDK location not found"。⛔ 别往仓里塞 local.properties——
# 那是机器相关路径。这里用环境变量供给，缺了就判**不可判**（不是判红：
# 那是本机环境不具备，不是被测物有问题）。2026-08-23 实撞。
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$ANDROID_HOME" ] || { echo "UNJUDGEABLE 找不到 Android SDK（ANDROID_HOME=$ANDROID_HOME），跑不了 gradle"; exit 2; }
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

S=.team/nodes/t.step3/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "UNJUDGEABLE 没有 git"; exit 2; }

# ④ 只动核层 —— 机械核实，⛔ 不采信自报
D1=$(git status --porcelain -- 'server/*' 2>/dev/null | head -5)
[ -z "$D1" ] || { echo "FAIL 这一格不许改服务端，但 server/ 有改动："; printf '%s\n' "$D1"; exit 1; }
D2=$(git status --porcelain -- 'app/app/*' 2>/dev/null | head -5)
[ -z "$D2" ] || { echo "FAIL 这一格不许改 APP 壳（那是第4步），但 app/app/ 有改动："; printf '%s\n' "$D2"; exit 1; }

# ① 四条断言必须以测试形式留在仓里
T=$(grep -rlE '(fun|class) .*(Mouse|Sgr|SGR|TrackingMode|MouseEncode)' \
      app/core-terminal/src/test 2>/dev/null | head -3)
[ -n "$T" ] || {
  echo "FAIL 找不到鼠标模式/编码测试（app/core-terminal/src/test 下，名字含 Mouse/SGR/TrackingMode）"
  exit 1
}
echo "  ok   测试文件：$(echo "$T" | tr '\n' ' ')"

miss=""
grep -rqE '1002' $T || miss="$miss R1(1002 模式跟踪)"
grep -rqE '1006|\\u001b\[<|\\e\[<|CSI <' $T || miss="$miss R2(SGR1006 编码)"
grep -rqE '1003' $T || miss="$miss R3(1003 降级)"
grep -rqE '不发|null|empty|Empty|none' $T || miss="$miss R4(没开模式时不发)"
[ -z "$miss" ] || { echo "FAIL 测试里看不到这些断言：${miss}"; exit 1; }

# ⑤ ⛔ 老编码不许留后门：编码实现里不该出现 X10/1005/1015 的发送路径
BAD=$(grep -rnE '1005|1015' app/core-terminal/src/main 2>/dev/null | grep -viE '不用|禁|⛔|not use|never' | head -3)
[ -z "$BAD" ] || {
  echo "FAIL 核层出现 1005/1015 且不是「明确排除」的注释——契约只许 SGR 1006："
  printf '%s\n' "$BAD"; exit 1; }

# ③ 复现先红是铁律
grep -qE 'FAIL|红|failed' "$S" || {
  echo "FAIL 说明里没有贴改前的红原文 —— 复现先红是铁律"; exit 1; }

# ② 真跑，🔴 --rerun-tasks 禁缓存（2026-08-23 一天内在两个判据上各踩一次缓存假绿）
ROOT=$(pwd)
[ -x "$ROOT/app/gradlew" ] || { echo "UNJUDGEABLE 没有可执行 app/gradlew"; exit 2; }
OUT="$ROOT/.team/nodes/t.step3/tmp/gradle-run.log"
mkdir -p "$(dirname "$OUT")"
echo "  …  app/gradlew :core-terminal:test --offline --rerun-tasks"
( cd "$ROOT/app" && ./gradlew --console=plain :core-terminal:test --offline --rerun-tasks ) >"$OUT" 2>&1
rc=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && {
  echo "UNJUDGEABLE 编译不过（见 ${OUT}）"; tail -8 "$OUT"; exit 2; }
tail -4 "$OUT"
[ "$rc" -eq 0 ] || { echo "FAIL gradle 测试未通过（rc=${rc}，见 ${OUT}）"; exit 1; }
grep -q 'up-to-date' "$OUT" && { echo "UNJUDGEABLE 测试是 up-to-date（缓存），不是这次跑出来的"; exit 2; }

echo "PASS 四条断言在仓里、无 1005/1015 后门、说明贴了改前的红、服务端与壳零改动、core-terminal 测试真跑绿"
echo "     ⚠️ 性能⛔ 不在本判据内——最终判据是用户真机。"
exit 0
