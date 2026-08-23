#!/bin/sh
# 判据：t.pub——三核模块能发布成 maven 产物，产物落进本地 maven 仓目录（将来推成 corral-core 的 maven 分支）。
# 目的（用户 2026-08-22）：core 以**发布产物**被 app 消费，app 仓里没有 core 源码 ⇒ 物理上改不了。
# ⛔ 席位不许 push；推 maven 分支是 leader 的动作。本判据只判「产物齐、坐标对、核仍是纯 JVM」。
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
REPO="$ROOT/.team/staging/maven-repo"
GROUP_PATH="dev/agentmirror/core"
VER="20260822.0"
[ -d "$REPO" ] || { echo "FAIL 无 maven 产物目录 .team/staging/maven-repo"; exit 1; }

for a in core-protocol core-terminal core-conn; do
  D="$REPO/$GROUP_PATH/$a/$VER"
  [ -d "$D" ] || { echo "FAIL 缺产物目录 ${D#"$ROOT"/}"; exit 1; }
  ls "$D"/$a-$VER.jar "$D"/$a-$VER.aar >/dev/null 2>&1 || {
    echo "FAIL $a 没有 jar/aar 产物"; exit 1; }
  [ -f "$D/$a-$VER.pom" ] || { echo "FAIL $a 缺 pom"; exit 1; }
  # 核必须仍是纯 JVM：⛔ 不许上 Android 插件、⛔ 源码不许 import android
  B="$ROOT/app/$a/build.gradle.kts"
  [ -f "$B" ] || { echo "FAIL 缺 app/$a/build.gradle.kts"; exit 1; }
  grep -qE 'id\("com\.android\.|kotlin\("android"\)' "$B" && {
    echo "FAIL app/$a 用了 Android 插件——核必须零 Android 依赖"; exit 1; }
  if grep -rqE '^import (android|androidx)\.' "$ROOT/app/$a/src" 2>/dev/null; then
    echo "FAIL app/$a 源码 import 了 android/androidx："
    grep -rnE '^import (android|androidx)\.' "$ROOT/app/$a/src" | head -3; exit 1
  fi
done

cd "$ROOT/app" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
OUT="$ROOT/.team/nodes/ca-pub/tmp/pub.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :core-protocol:test :core-terminal:test :core-conn:test --offline --rerun-tasks >"$OUT" 2>&1
RC=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 ${OUT}）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL 三核单测红 rc=${RC}（见 ${OUT}）"; exit 1; }
echo "PASS 三核已发布为 maven 产物且仍是纯 JVM"
exit 0
