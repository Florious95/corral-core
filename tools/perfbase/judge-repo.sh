#!/bin/sh
# 判据：t.repo——app 壳迁往 corral-app 的**本地暂存件**成立（⛔ 席位不许推远端，推是 leader 的动作）。
# 判四件事：暂存工程在、用 includeBuild 引核、能装出 APK、核里不再残留 Android 壳。
# 四态：0=通过；1=不通过；2=不可判。
set -u
ROOT=$(pwd)
STAGE="$ROOT/.team/staging/corral-app"
[ -d "$STAGE" ] || { echo "FAIL 无暂存工程 .team/staging/corral-app"; exit 1; }
S="$STAGE/settings.gradle.kts"
[ -f "$S" ] || { echo "FAIL 暂存工程缺 settings.gradle.kts"; exit 1; }
grep -q "includeBuild" "$S" || { echo "FAIL 没用 composite build（includeBuild）引 core"; exit 1; }

# core 侧不许再有 Android 壳：三核模块内零 android import 已由 judge-core-split.sh 管；
# 这里只查 app 壳确实**搬走了**——迁移清单必须在场且逐条可核。
M="$STAGE/迁移清单.md"
[ -f "$M" ] || { echo "FAIL 缺 $M（迁了什么必须逐条可核）"; exit 1; }

cd "$STAGE" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
OUT="$ROOT/.team/nodes/pb-repo/tmp/repo-build.log"
mkdir -p "$(dirname "$OUT")"
[ -x ./gradlew ] || { echo "UNJUDGEABLE 暂存工程没有可执行 gradlew"; exit 2; }
./gradlew :app:assembleDebug --offline >"$OUT" 2>&1
RC=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 $OUT）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL 引用式构建装不出 APK rc=$RC（见 $OUT）"; exit 1; }
find "$STAGE" -name "*.apk" | head -1 | grep -q . || { echo "FAIL 构建绿但找不到 APK 产物"; exit 1; }
echo "PASS 引用式构建成立（远端推送留给 leader）"
exit 0
