#!/bin/sh
# 判据：同机同时段 A/B 对拍——B（引用 maven 产物构建）不许比同批 A（includeBuild 源码构建）
# 慢超过 10%。⛔ 不跟历史基线比：跨包比历史地板会得出假的结论（2026-08-22 实撞，判者已打回一次）。
#
# 为什么地板是「同批 A 组」而不是基线文件：本链要回答的只有一个问题——
# **换依赖形态有没有让性能倒退**。同一台机器、同一时段、A,B 交替测出来的 A 组，
# 是唯一能隔离掉「机器状态漂移」「包内容差异」两个混杂因素的对照。
#
# 四态：0=通过；1=不通过（B 真的慢了）；2=不可判（数据不齐/形状不对，判据自己跑不起来）。
set -u
F=$(ls .team/perf/recheck-*-capp-ab.json 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "UNJUDGEABLE 没有 A/B 对拍文件 .team/perf/recheck-*-capp-ab.json"; exit 2; }

python3 - "$F" <<'PY'
import json, sys
f = sys.argv[1]
try:
    d = json.load(open(f))
except Exception as e:
    print("UNJUDGEABLE %s 读不出来：%s" % (f, e)); sys.exit(2)

THRESH = 10.0            # 判据门：B 比 A 慢超过这个百分比即红
MIN_N  = 10              # 每组每夹具最少样本数
SEGS   = ("first_draw", "layout_settled")

cmp_ = d.get("comparison")
if not isinstance(cmp_, dict) or not cmp_:
    print("UNJUDGEABLE 文件里没有 comparison 段"); sys.exit(2)

# 两个包必须真的是两个包：md5 相同说明构建错了，比出来的差异毫无意义。
amd5 = (d.get("A") or {}).get("apk_md5"); bmd5 = (d.get("B") or {}).get("apk_md5")
if not amd5 or not bmd5:
    print("UNJUDGEABLE A/B 组缺 apk_md5，认不出被测物"); sys.exit(2)
if amd5 == bmd5:
    print("UNJUDGEABLE A 与 B 的 apk_md5 相同（%s）——这不是对拍，是同一个包测了两遍" % amd5[:12]); sys.exit(2)

red, ok, unj = [], [], []
for fx in sorted(cmp_):
    for seg in SEGS:
        node = (cmp_[fx] or {}).get(seg)
        if not isinstance(node, dict):
            unj.append("%s.%s 缺这一段" % (fx, seg)); continue
        A, B = node.get("A") or {}, node.get("B") or {}
        for metric in ("p50", "p95"):
            av, bv = A.get(metric), B.get(metric)
            na, nb = A.get("n"), B.get("n")
            if av is None or bv is None:
                unj.append("%s.%s.%s 取不到值" % (fx, seg, metric)); continue
            if not isinstance(na, int) or not isinstance(nb, int) or na < MIN_N or nb < MIN_N:
                unj.append("%s.%s 样本不足（A=%s B=%s，要求各 >=%d）" % (fx, seg, na, nb, MIN_N)); continue
            pct = (bv - av) / av * 100.0 if av else 0.0
            line = "%-15s %-14s %-3s A=%7.1fms B=%7.1fms (%+.1f%%)" % (fx, seg, metric, av, bv, pct)
            (red if pct > THRESH else ok).append(line)

# ⛔ 不可判不许折进通过或失败：数据不齐就是判不出，不是「B 没问题」。
if unj:
    print("UNJUDGEABLE 数据不齐，判不了：")
    for u in unj[:8]:
        print("  " + u)
    sys.exit(2)

for l in ok:
    print("  ok   " + l)
for l in red:
    print("  RED  " + l)

if red:
    print("FAIL B（引用 maven 产物）比同批 A（源码 composite）慢超过 %.0f%%，共 %d 段" % (THRESH, len(red)))
    print("     A=%s  B=%s" % (amd5[:12], bmd5[:12]))
    sys.exit(1)
print("PASS B 相对同批 A 全段未超 %.0f%%（%d 段）" % (THRESH, len(ok)))
sys.exit(0)
PY
