#!/bin/sh
# 判据：t.instr.red「先红」——红测必须存在、必须编译得过、必须失败。
# 四态：0=通过（确实先红了）；1=不通过；2=不可判（环境跑不起来）。
# ⛔ 编译错误不算「测试红」（棘轮方法论），编译不过一律 2。
set -u
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
  echo "UNJUDGEABLE 编译不过——红测必须编译得过才算红（见 $OUT）"; exit 2
fi

if [ "$RC" -eq 0 ]; then
  echo "FAIL 红测竟然全绿：实现还没写，绿=测试没验到东西（见 $OUT）"; exit 1
fi
grep -qE "PerfTraceChainTest > .* FAILED|tests completed, .* failed" "$OUT" || {
  echo "UNJUDGEABLE 非零退出但看不到测试 FAILED 行——分不清是没跑到还是真红（见 $OUT）"; exit 2; }
echo "PASS 先红成立 rc=$RC"
exit 0
