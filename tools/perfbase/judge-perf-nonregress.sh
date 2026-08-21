#!/bin/sh
# 判据：改动后复测不许低于基线（用户令：此后任何改动不允许低于基线，优化必须高于基线）。
# 比的是同流程同环境下的复测文件 .team/perf/recheck-*.json 对基线 .team/perf/baseline-*.json：
#   逐夹具逐段比 p50 与 p95，任一项超出基线 +10% 即红（10% = 量具噪声带，超出算真回退）。
# ⛔ 基线缺失/复测缺失/夹具对不上 = 不可判(2)，⛔ 不许当通过。
set -u
ROOT=$(pwd)
# shellcheck disable=SC2012
B=$(ls "$ROOT"/.team/perf/baseline-*.json 2>/dev/null | tail -1)
# shellcheck disable=SC2012
R=$(ls "$ROOT"/.team/perf/recheck-*.json 2>/dev/null | tail -1)
[ -n "$B" ] || { echo "UNJUDGEABLE 无基线文件"; exit 2; }
[ -n "$R" ] || { echo "UNJUDGEABLE 无复测文件 .team/perf/recheck-*.json"; exit 2; }
exec /usr/bin/python3 - "$B" "$R" <<'PY'
import json,sys
b=json.load(open(sys.argv[1])); r=json.load(open(sys.argv[2]))
TOL=1.10
bf=b.get("fixtures") or {}; rf=r.get("fixtures") or {}
if set(bf)!=set(rf):
    print("UNJUDGEABLE 夹具集合对不上 基线=%s 复测=%s"%(sorted(bf),sorted(rf))); sys.exit(2)
bad=[];ok=[]
for name in sorted(bf):
    for seg in ("first_draw","layout_settled"):
        for m in ("p50","p95"):
            try:
                x=float(bf[name]["stats"][seg][m]); y=float(rf[name]["stats"][seg][m])
            except Exception as e:
                print("UNJUDGEABLE %s.%s.%s 取不到：%s"%(name,seg,m,e)); sys.exit(2)
            line="%s %s %s 基线=%.1fms 复测=%.1fms (%+.1f%%)"%(name,seg,m,x,y,(y/x-1)*100 if x else 0)
            (bad if x and y> x*TOL else ok).append(line)
for l in ok: print("  ok  ",l)
if bad:
    print("FAIL 相对基线回退（阈值 +10%）：")
    for l in bad: print("  RED ",l)
    sys.exit(1)
print("PASS 全段不低于基线")
PY
