#!/bin/sh
# 判据：碰了性能关键路径的改动，必须带复测数据。
# 用法：judge-perf-touched.sh <基线ref>..<待判ref>    例：judge-perf-touched.sh main..HEAD
# 四态：0=没碰(不适用)或碰了且复测绿；1=碰了但没带复测/复测红；2=不可判(没传范围/清单缺失/git 不可用)。
set -u
RANGE="${1:-}"
[ -n "$RANGE" ] || { echo "UNJUDGEABLE 未传 <base>..<head> 范围"; exit 2; }
ROOT=$(pwd)
L="$ROOT/docs/性能关键路径.md"
[ -f "$L" ] || { echo "UNJUDGEABLE 缺清单 docs/性能关键路径.md"; exit 2; }
git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1 || { echo "UNJUDGEABLE 不是 git 仓"; exit 2; }
CHANGED=$(git -C "$ROOT" diff --name-only "$RANGE" 2>/dev/null) || { echo "UNJUDGEABLE 取不到 diff：$RANGE"; exit 2; }
[ -n "$CHANGED" ] || { echo "NOT_APPLICABLE 该范围无改动"; exit 0; }

WATCH=$(grep -oE '(app|server)/[A-Za-z0-9_./-]+\.(kt|go)' "$L" | sort -u)
[ -n "$WATCH" ] || { echo "UNJUDGEABLE 清单里没有可监视的文件路径"; exit 2; }
HIT=""
for f in $CHANGED; do
  for w in $WATCH; do [ "$f" = "$w" ] && HIT="$HIT $f"; done
done
if [ -z "$HIT" ]; then
  echo "NOT_APPLICABLE 未碰性能关键路径（$(echo "$CHANGED" | wc -l | tr -d ' ') 个改动文件）"
  exit 0
fi
echo "碰到的性能关键文件：$HIT"
# shellcheck disable=SC2012
R=$(ls "$ROOT"/.team/perf/recheck-*.json 2>/dev/null | tail -1)
[ -n "$R" ] || { echo "FAIL 碰了性能关键路径却没有复测数据（.team/perf/recheck-*.json）"; exit 1; }
sh "$ROOT/tools/perfbase/judge-perf-nonregress.sh"
exit $?
