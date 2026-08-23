#!/bin/sh
# 判据：t.core.split——corral-core 模块内切分（:core-protocol / :core-terminal / :core-conn）落地。
# 判三件事：模块真存在且被 include、核模块⛔零 Android 依赖、全量单测仍绿（棘轮：基线为 0 红）。
# 四态：0=通过；1=不通过；2=不可判。
set -u
ROOT=$(pwd)
SET="$ROOT/app/settings.gradle.kts"
[ -f "$SET" ] || { echo "UNJUDGEABLE 无 settings.gradle.kts"; exit 2; }
for m in core-protocol core-terminal core-conn; do
  grep -q "\":$m\"" "$SET" || { echo "FAIL settings.gradle.kts 没 include :$m"; exit 1; }
  [ -d "$ROOT/app/$m" ] || { echo "FAIL 模块目录 app/$m 不存在"; exit 1; }
  B="$ROOT/app/$m/build.gradle.kts"
  [ -f "$B" ] || { echo "FAIL 缺 app/$m/build.gradle.kts"; exit 1; }
  # 核模块必须是纯 JVM/KMP：⛔ 不许上 android 插件
  grep -qE 'id\("com\.android\.|kotlin\("android"\)' "$B" && {
    echo "FAIL app/$m 用了 Android 插件——核模块必须零 Android 依赖（任务三红线）"; exit 1; }
  # 核模块源码里不许 import android.* / androidx.*
  if grep -rqE '^import (android|androidx)\.' "$ROOT/app/$m/src" 2>/dev/null; then
    echo "FAIL app/$m 源码 import 了 android/androidx："
    grep -rnE '^import (android|androidx)\.' "$ROOT/app/$m/src" | head -5
    exit 1
  fi
done

cd "$ROOT/app" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
OUT="$ROOT/.team/nodes/pb-core/tmp/split-run.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :app:testDebugUnitTest :terminal:test :core-protocol:test :core-terminal:test :core-conn:test --offline --rerun-tasks >"$OUT" 2>&1
RC=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 ${OUT}）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL 切分后单测红 rc=${RC}（见 ${OUT}）"; exit 1; }
echo "PASS 三核模块切分成立且全量绿"
exit 0
