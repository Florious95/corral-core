#!/bin/sh
# 判据：t.base——模拟器性能基线文件形式与充分性。
# 判的是「这份基线够不够格当地板」，不是「性能好不好」：
#   三个夹具各 ≥10 次冷点开、每次都有 open_id 与 8 段时间戳、统计四项齐、outliers 节显式存在
#   （极端值不许剔除，只许列出来）。
# 四态：0=通过；1=不通过；2=不可判。
set -u
ROOT=$(pwd)
# shellcheck disable=SC2012
F=$(ls "$ROOT"/.team/perf/baseline-*.json 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "FAIL 没有 .team/perf/baseline-*.json"; exit 1; }
exec /usr/bin/python3 - "$F" <<'PY'
import json,sys
p=sys.argv[1]
try: d=json.load(open(p))
except Exception as e:
    print("UNJUDGEABLE 基线文件不是合法 JSON: %s"%e); sys.exit(2)
bad=[]
for k in ("baseline_date","app_sha","server_sha","apk_md5","emulator","fixtures"):
    if k not in d: bad.append("缺顶层字段 %s"%k)
EV=["tap","route_enter","subscribe_sent","geom_seed","first_frame_recv",
    "snapshot_applied","first_draw","layout_settled"]
want={"real_claude_idle","redraw_tui","big_scrollback"}
fx=d.get("fixtures") or {}
miss=want-set(fx)
if miss: bad.append("缺夹具 %s"%sorted(miss))
for name,v in fx.items():
    s=v.get("samples") or []
    if len(s)<10: bad.append("%s 样本仅 %d 次（要求 ≥10 次冷点开）"%(name,len(s)))
    if "outliers" not in v: bad.append("%s 缺 outliers 节（极端值不许剔除，必须列出）"%name)
    st=v.get("stats") or {}
    for seg in ("first_draw","layout_settled"):
        g=st.get(seg) or {}
        for m in ("mean","p50","p95","max"):
            if m not in g: bad.append("%s.stats.%s 缺 %s"%(name,seg,m))
    for i,smp in enumerate(s[:200]):
        if not smp.get("open_id"): bad.append("%s 第%d个样本缺 open_id"%(name,i)); break
        t=smp.get("t") or {}
        lack=[e for e in EV if e not in t]
        if lack: bad.append("%s 第%d个样本缺事件 %s（8 段必须齐，缺了就算不出分段）"%(name,i,lack)); break
        vals=[t[e] for e in EV]
        if any(b<a for a,b in zip(vals,vals[1:])):
            bad.append("%s 第%d个样本时间非单调：%s"%(name,i,vals)); break
if bad:
    print("FAIL 基线不合格：")
    for b in bad: print("  -",b)
    sys.exit(1)
print("PASS 基线合格 %s"%p)
PY
