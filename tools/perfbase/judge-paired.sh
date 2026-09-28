#!/bin/sh
# 判据：配对重算成立——**两轮都算了、配对数够、置信区间在、丢弃的样本有交代、
# 并且给出了这台量具的可分辨效应**。
#
# 为什么这么判：这一格要回答的不是「B 快还是慢」，而是「**这台量具能不能回答那个问题**」。
# 所以判据核的是**方法学产物是否齐全且自洽**，⛔ 不核 median 的正负——
# 结论是正是负都是合法结果，判据不该有偏好。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.paired/说明.md
P=tools/perfbase/paired.py
R2=.team/perf/paired-r2.json
R3=.team/perf/paired-r3.json

[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
[ -f "$P" ] || { echo "FAIL 重算脚本不存在：${P}（没有脚本＝结论不可复跑）"; exit 1; }
for f in "$R2" "$R3"; do
  [ -f "$f" ] || { echo "FAIL 缺 ${f}——两轮都要重算，只算一轮比不出修复有没有效"; exit 1; }
done

python3 - "$R2" "$R3" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是不可判，⛔ 不是不通过（未捕获异常退出码恰好是 1，会与真红撞码）。
    traceback.print_exception(t, v, tb)
    print("UNJUDGEABLE 判据自己跑不起来（见上方异常）"); _s.exit(2)
_s.excepthook = _hook
import json, sys

bad = []
for path in sys.argv[1:]:
    try:
        d = json.load(open(path))
    except Exception as e:
        print("UNJUDGEABLE %s 读不出来：%s" % (path, e)); sys.exit(2)

    mde = d.get("min_detectable_effect_pct")
    if not isinstance(mde, (int, float)):
        bad.append("%s 没有数值 min_detectable_effect_pct —— 这一格的核心问题没回答" % path)
    elif mde <= 0:
        bad.append("%s 的 min_detectable_effect_pct=%r 不是正数" % (path, mde))

    segs = 0
    def walk(node, p=""):
        global segs
        if isinstance(node, dict):
            if "median_rel_diff" in node:
                segs += 1
                for k in ("n_pairs", "ci95_low", "ci95_high", "dropped"):
                    if k not in node:
                        bad.append("%s %s 缺字段 %s" % (path, p, k))
                n = node.get("n_pairs")
                if isinstance(n, int) and n < 8:
                    bad.append("%s %s n_pairs=%d 太少，配对统计撑不住" % (path, p, n))
                lo, hi = node.get("ci95_low"), node.get("ci95_high")
                m = node.get("median_rel_diff")
                if all(isinstance(x, (int, float)) for x in (lo, hi, m)):
                    if not (lo <= m <= hi):
                        bad.append("%s %s 中位数 %.3f 不在 CI [%.3f, %.3f] 内——算错了"
                                   % (path, p, m, lo, hi))
                # ⛔ 丢弃的样本必须有交代：dropped 缺省或非整数都不行
                dr = node.get("dropped")
                if dr is not None and not isinstance(dr, int):
                    bad.append("%s %s dropped 不是整数：%r" % (path, p, dr))
            for k, v in node.items():
                walk(v, p + "." + k if p else k)
    walk(d)
    if segs == 0:
        bad.append("%s 里没有任何 median_rel_diff —— 等于没重算" % path)
    else:
        print("  ok   %-28s 段数=%d  可分辨效应=%.1f%%" % (path, segs, mde if isinstance(mde,(int,float)) else -1))

if bad:
    print("FAIL 配对重算产物不合格：")
    for b in bad[:10]:
        print("  - " + b)
    sys.exit(1)
print("PASS 两轮都已配对重算：段齐、n_pairs 够、CI 自洽、丢弃样本有计数、可分辨效应已给出")
print("     ⚠️ 本判据⛔ 不核 median 的正负——B 更快或更慢都是合法结论。")
sys.exit(0)
PY
