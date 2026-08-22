#!/bin/sh
# 判据：t.capp——corral-app 独立工程只**引用**核的发布产物，⛔ 仓内无核源码，且能装出 APK。
# 这条是「禁止从 app 侧改核」的**物理保证**：没有源码就改不了。
# 四态：0=通过；1=不通过；2=不可判。
set -u
ROOT=$(pwd)
APPDIR="$ROOT/.team/staging/corral-app"
VER="20260822.0"
[ -d "$APPDIR" ] || { echo "FAIL 无 .team/staging/corral-app"; exit 1; }
S="$APPDIR/settings.gradle.kts"
[ -f "$S" ] || { echo "FAIL 缺 settings.gradle.kts"; exit 1; }

# ① ⛔ 不许再用源码 composite build 引核——那是可编辑的源码，挡不住任何人
grep -q "includeBuild" "$S" && { echo "FAIL settings 里还有 includeBuild（源码 composite）——核必须以发布产物消费"; exit 1; }
# ② ⛔ 工程内不许出现核的源码
if find "$APPDIR" -path "*/core-protocol/src" -o -path "*/core-terminal/src" -o -path "*/core-conn/src" 2>/dev/null | grep -q .; then
  echo "FAIL corral-app 里带了核源码目录 —— 必须只引用产物"; exit 1
fi
# ③ 依赖必须钉死版本，且 raw URL 仓排在本地仓前面（推了 maven 分支后自动走远端）
grep -rq "dev.agentmirror.core:core-protocol:$VER" "$APPDIR" || { echo "FAIL 没有钉死版本的 core-protocol:${VER} 依赖"; exit 1; }
grep -rq "raw.githubusercontent.com/Florious95/corral-core/maven" "$APPDIR" || { echo "FAIL 没声明 corral-core 的 maven 分支 raw URL 仓"; exit 1; }
grep -rq "staging/maven-repo" "$APPDIR" || { echo "FAIL 没声明本地 maven-repo 兜底仓（maven 分支推之前构建不出来）"; exit 1; }

cd "$APPDIR" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
[ -x ./gradlew ] || { echo "UNJUDGEABLE 没有可执行 gradlew"; exit 2; }
OUT="$ROOT/.team/nodes/ca-app/tmp/build.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :app:assembleRelease >"$OUT" 2>&1
RC=$?
grep -qE "Compilation error|Unresolved reference|Could not find dev.agentmirror.core" "$OUT" && {
  echo "UNJUDGEABLE 解析/编译不过（见 ${OUT}）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL 引用式构建失败 rc=${RC}（见 ${OUT}）"; exit 1; }
APK=$(find "$APPDIR" -name "*-release*.apk" | head -1)
[ -n "$APK" ] || { echo "FAIL 构建绿但没有 release APK 产物"; exit 1; }
# ④ 仪表与白屏修复必须还在（量具没了就没法自证不回退）
T=$(mktemp -d "$ROOT/.team/nodes/ca-app/tmp/dex.XXXX") || { echo "UNJUDGEABLE mktemp 失败"; exit 2; }
( cd "$T" && unzip -qo "$APK" 'classes*.dex' 2>/dev/null )
for k in PerfTrace addBinaryListener debug.agentmirror.perftrace; do
  n=$(strings "$T"/classes*.dex 2>/dev/null | grep -c -- "$k")
  [ "$n" -ge 1 ] || { echo "FAIL APK 里找不到 $k（仪表/修复被弄丢了）"; rm -rf "$T"; exit 1; }
done
rm -rf "$T"
echo "PASS corral-app 只引用产物、无核源码、装出 release APK 且仪表在位"
exit 0
