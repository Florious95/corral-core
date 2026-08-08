#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# tools/gate/run.sh —— 全量回归门入口（薄壳）。
#
# 三面并行后台跑（wall-clock = 最慢一面），收齐后交 gate.py finalize 做棘轮校验与报告。
# 门只读产品代码，绝不修改 server/ 与 app/（知识基底红线）。
#
# 用法：
#   bash tools/gate/run.sh                      # 全量门：并行三面 + 报告 + 棘轮
#   bash tools/gate/run.sh --self-test          # 用手工 fixture 验证门本身（验收命令）
#   bash tools/gate/run.sh --accept-baseline=<理由>   # 显式接受用例数下行（棘轮下行必须此旗标）
#
# 退出码：0=过，1=红，2=参数错误。
# ---------------------------------------------------------------------------
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="python3"

SELF_TEST=0
ACCEPT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --self-test) SELF_TEST=1 ;;
    --accept-baseline=*) ACCEPT="${1#*=}" ;;
    *)
      echo "unknown arg: $1" >&2
      echo "usage: run.sh [--self-test] [--accept-baseline=<reason>]" >&2
      exit 2
      ;;
  esac
  shift
done

if [ "$SELF_TEST" = "1" ]; then
  # 自测不触碰真实报告/基线（fixture 走临时目录）。
  exec "$PY" "$DIR/gate.py" selftest
fi

# 运行目录 last-run/ 保留三面原始 JSON 与日志供排查；每次运行前清空。
RUNDIR="$DIR/last-run"
mkdir -p "$RUNDIR"
rm -f "$RUNDIR"/*.json "$RUNDIR"/*.log

# 三面并行：各自后台跑，各写一份原始结果 JSON（server/app/archwiki）。
for suite in server app archwiki; do
  "$PY" "$DIR/gate.py" run "$suite" --out "$RUNDIR/$suite.json" >"$RUNDIR/$suite.log" 2>&1 &
done
wait

# 汇总：棘轮校验 + 报告落盘；退出码即门结论。
ARGS=(finalize "$RUNDIR" --report "$DIR/gate-report.json" --baseline "$DIR/baseline.json")
if [ -n "$ACCEPT" ]; then
  ARGS+=(--accept-baseline "$ACCEPT")
fi
"$PY" "$DIR/gate.py" "${ARGS[@]}"
exit $?
