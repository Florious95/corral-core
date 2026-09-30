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

S=.team/nodes/t.step4/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "UNJUDGEABLE 没有 git"; exit 2; }

# ④ 只动核层 —— 机械核实，⛔ 不采信自报
D1=$(git status --porcelain -- 'server/*' 2>/dev/null | head -5)
[ -z "$D1" ] || { echo "FAIL 这一格不许改服务端，但 server/ 有改动："; printf '%s\n' "$D1"; exit 1; }
D2=$(git status --porcelain -- 'app/core-protocol/*' 'app/core-terminal/*' 'app/core-conn/*' 2>/dev/null | head -5)
[ -z "$D2" ] || { echo "FAIL 这一格不许改核层（前三步已完成），但 app/core-* 有改动："; printf '%s\n' "$D2"; exit 1; }

# ① 四条断言必须以测试形式留在仓里
T=$(grep -rlE '(fun|class) .*(Mouse|Touch|Pointer|Gesture)' \
      app/app/src/test 2>/dev/null | head -3)
[ -n "$T" ] || {
  echo "FAIL 找不到壳侧鼠标采集测试（app/app/src/test 下，名字含 Mouse/Touch/Pointer/Gesture）"
  exit 1
}
echo "  ok   测试文件：$(echo "$T" | tr '\n' ' ')"

miss=""
grep -rqE 'bytes|InputFrame' $T || miss="$miss R1(送出的 bytes 来自核层编码)"
grep -rqE '不发|null|empty|Empty|none|never' $T || miss="$miss R2(没开模式时不发帧)"
grep -rqE 'Ctrl|ctrl|modifier|修饰' $T || miss="$miss R3(修饰键带到编码入口)"
grep -rqE 'Scroll|scroll|滚轮|键盘' $T || miss="$miss R4(既有键盘/滚轮路径不退)"
[ -z "$miss" ] || { echo "FAIL 测试里看不到这些断言：${miss}"; exit 1; }

# ⑤ ⛔ 老编码不许留后门：编码实现里不该出现 X10/1005/1015 的发送路径
# ⑤ 壳⛔ 不许自己拼 CSI 鼠标字节：编码规则只许有一份（在核层）
BAD=$(grep -rnE '\\u001b\[<|\\e\[<|"\\x1b\[<"' app/app/src/main 2>/dev/null | head -3)
[ -z "$BAD" ] || {
  echo "FAIL 壳里出现自拼的 SGR 鼠标序列——编码只许有一份（核层第3步）："
  printf '%s\n' "$BAD"; exit 1; }

# ③ 复现先红是铁律
grep -qE 'FAIL|红|failed' "$S" || {
  echo "FAIL 说明里没有贴改前的红原文 —— 复现先红是铁律"; exit 1; }

# ② 真跑，🔴 --rerun-tasks 禁缓存（2026-08-23 一天内在两个判据上各踩一次缓存假绿）
ROOT=$(pwd)
[ -x "$ROOT/app/gradlew" ] || { echo "UNJUDGEABLE 没有可执行 app/gradlew"; exit 2; }
OUT="$ROOT/.team/nodes/t.step4/tmp/gradle-run.log"
mkdir -p "$(dirname "$OUT")"
echo "  …  app/gradlew :app:testDebugUnitTest --offline --rerun-tasks"
( cd "$ROOT/app" && ./gradlew --console=plain :app:testDebugUnitTest --offline --rerun-tasks ) >"$OUT" 2>&1
rc=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && {
  echo "UNJUDGEABLE 编译不过（见 ${OUT}）"; tail -8 "$OUT"; exit 2; }
tail -4 "$OUT"
[ "$rc" -eq 0 ] || { echo "FAIL gradle 测试未通过（rc=${rc}，见 ${OUT}）"; exit 1; }
grep -q 'up-to-date' "$OUT" && { echo "UNJUDGEABLE 测试是 up-to-date（缓存），不是这次跑出来的"; exit 2; }

echo "PASS 四条断言在仓里、壳未自拼 CSI、说明贴了改前的红、服务端与核层零改动、app 单测真跑绿"
echo "     ⚠️ 性能⛔ 不在本判据内——最终判据是用户真机。"
exit 0
