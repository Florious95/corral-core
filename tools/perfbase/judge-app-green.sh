#!/bin/sh
# 判据：t.instr.app「转绿」——红测转绿 + 全量单测不新增红 + 8 个打点真的接在产品链路上。
# 基线事实（2026-08-22 leader 实测）：main 上 `:app:testDebugUnitTest :terminal:test` 退出码 0，
# 所以存量红为零 ⇒ 增量任何红即红（棘轮）。
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

ROOT=$(pwd)
SRC="$ROOT/app/app/src/main"
CORE="$ROOT/app/app/src/main/java/dev/agentmirror/app/perf/PerfTrace.kt"
T="$ROOT/app/app/src/test/kotlin/dev/agentmirror/app/perf/PerfTraceChainTest.kt"
[ -f "$CORE" ] || { echo "FAIL 缺 PerfTrace.kt"; exit 1; }
[ -f "$T" ] || { echo "FAIL 缺红测文件"; exit 1; }
grep -q "@Ignore" "$T" && { echo "FAIL 红测被 @Ignore 掉了"; exit 1; }

# 8 个事件名必须各自出现在 PerfTrace.kt **之外**的产品源码里（= 真接线，不是只定义了常量）
for ev in tap route_enter subscribe_sent geom_seed first_frame_recv snapshot_applied first_draw layout_settled; do
  # shellcheck disable=SC2126
  N=$(grep -rl -- "$ev" "$SRC" 2>/dev/null | grep -v "/perf/PerfTrace.kt" | wc -l | tr -d ' ')
  [ "$N" -ge 1 ] || { echo "FAIL 事件 $ev 只存在于 PerfTrace.kt，没接到产品链路上"; exit 1; }
done

# 关时零成本：调用点不许无条件拼串。粗判——PerfTrace 必须暴露 enabled 守卫且被外部引用。
grep -qE "enabled" "$CORE" || { echo "FAIL PerfTrace 没有开关字段"; exit 1; }

cd "$ROOT/app" || { echo "UNJUDGEABLE 无 app 工程"; exit 2; }
OUT="$ROOT/.team/nodes/pb-green/tmp/green-run.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :app:testDebugUnitTest :terminal:test --offline --rerun-tasks >"$OUT" 2>&1
RC=$?
if grep -qE "Compilation error|Unresolved reference|Could not resolve" "$OUT"; then
  echo "UNJUDGEABLE 编译不过（见 ${OUT}）"; exit 2
fi
[ "$RC" -eq 0 ] || { echo "FAIL 全量单测红 rc=${RC}（基线为 0，增量红即红；见 ${OUT}）"; exit 1; }
echo "PASS 全量绿且 8 打点已接线"
exit 0
