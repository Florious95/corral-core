#!/bin/sh
# 判据：t.instr.red「先红」——红测必须存在、必须编译得过、必须失败。
# 四态：0=通过（确实先红了）；1=不通过；2=不可判（环境跑不起来）。
# ⛔ 编译错误不算「测试红」（棘轮方法论），编译不过一律 2。
set -u

# 🔴 worktree 里没有 local.properties（它按机器路径生成、已 gitignore），
# gradle 会报 "SDK location not found"。⛔ 别往仓里塞 local.properties——
# 那是机器相关路径。这里用环境变量供给，缺了就判**不可判**（不是判红：
# 那是本机环境不具备，不是被测物有问题）。2026-08-23 实撞。
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$ANDROID_HOME" ] || { echo "UNJUDGEABLE 找不到 Android SDK（ANDROID_HOME=$ANDROID_HOME），跑不了 gradle"; exit 2; }
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

ROOT=$(pwd)
T="$ROOT/app/app/src/test/kotlin/dev/agentmirror/app/perf/PerfTraceChainTest.kt"
S="$ROOT/app/app/src/main/java/dev/agentmirror/app/perf/PerfTrace.kt"

for f in "$T" "$S"; do
  [ -f "$f" ] || { echo "FAIL 缺文件 $f"; exit 1; }
done

# 三条红测方法名必须在场（名字即契约，改名等于换判据）
for m in perfTrace_关闭时零行 perfTrace_一次打开产出八事件且open_id一致且时间单调 perfTrace_两次并发打开open_id不串; do
  grep -q "$m" "$T" || { echo "FAIL 红测缺方法 $m"; exit 1; }
done
grep -q "@Ignore" "$T" && { echo "FAIL 红测被 @Ignore"; exit 1; }

cd "$ROOT/app" || { echo "UNJUDGEABLE 无 app 工程"; exit 2; }
OUT="$ROOT/.team/nodes/pb-red/tmp/red-run.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :app:testDebugUnitTest --offline --tests 'dev.agentmirror.app.perf.PerfTraceChainTest' --rerun-tasks >"$OUT" 2>&1
RC=$?

# 编译失败 ⇒ 不可判（不是「红」）
if grep -qE "Compilation error|e: file:///|Could not resolve|Unresolved reference" "$OUT"; then
  echo "UNJUDGEABLE 编译不过——红测必须编译得过才算红（见 ${OUT}）"; exit 2
fi

if [ "$RC" -eq 0 ]; then
  echo "FAIL 红测竟然全绿：实现还没写，绿=测试没验到东西（见 ${OUT}）"; exit 1
fi
grep -qE "PerfTraceChainTest > .* FAILED|tests completed, .* failed" "$OUT" || {
  echo "UNJUDGEABLE 非零退出但看不到测试 FAILED 行——分不清是没跑到还是真红（见 ${OUT}）"; exit 2; }
echo "PASS 先红成立 rc=$RC"
exit 0
