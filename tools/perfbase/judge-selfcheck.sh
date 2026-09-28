#!/bin/sh
# 判据：两条「我没跑」被补成了数——**E1 的延迟表在、E2 的测试与真 fixture 在仓里**。
#
# ⛔ 不判结论方向：断言成立或不成立**都是合法结果**。
#    若判据只在「断言成立」时给绿，就等于奖励席位把我方说成没问题——
#    而这一格存在的理由恰恰是「我方可能有同样的病」。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.selfcheck/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
F=$(ls .team/perf/reflow-sync-*.json 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "FAIL 没有 E1 的表 .team/perf/reflow-sync-*.json"; exit 1; }

# ⛔ fixture 必须是真从 tmux 取的，且留在仓里——否则这测试以后不可复跑
FX=$(find app/core-terminal/src/test -type f \( -name '*235*' -o -name '*wide*' -o -name '*capture*' \) 2>/dev/null | head -3)
[ -n "$FX" ] || {
  echo "FAIL 找不到 E2 的 fixture（app/core-terminal/src/test 下应有 235/wide/capture 相关文件）"
  echo "     ⛔ 没有 fixture 的测试以后不可复跑，等于没测"
  exit 1
}
echo "  ok   fixture：$(echo "$FX" | tr '\n' ' ')"

T=$(grep -rlE 'func .*(Wide|235|Reflow)|fun .*(Wide|235|Reflow)' app/core-terminal/src/test 2>/dev/null | head -3)
[ -n "$T" ] || { echo "FAIL 找不到 E2 的测试函数（名字里应含 Wide/235/Reflow）"; exit 1; }
echo "  ok   测试：$(echo "$T" | tr '\n' ' ')"

python3 - "$F" "$S" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是不可判，⛔ 不是不通过（未捕获异常退出码恰好 1，会与真红撞码）。
    traceback.print_exception(t, v, tb); print("UNJUDGEABLE 判据自己跑不起来"); _s.exit(2)
_s.excepthook = _hook
import json, sys
d = json.load(open(sys.argv[1]))
note = open(sys.argv[2], encoding="utf-8").read()
bad = []

# E1：两个方向 × 至少 4 个 delay 档，每档 n>=20，且必须有 delay=0
dirs = [k for k in d if isinstance(d[k], dict) and k in ("widen", "narrow", "变宽", "变窄")]
if len(dirs) < 2:
    bad.append("E1 缺方向：变宽/变窄两个方向都要测（拿到 %r）" % dirs)
for dn in dirs:
    tbl = d[dn]
    delays = sorted(int(k) for k in tbl if str(k).lstrip('-').isdigit())
    if 0 not in delays:
        bad.append("%s 缺 delay=0 档 —— 那正是我方实际的截取时刻" % dn)
    if len(delays) < 4:
        bad.append("%s 只有 %d 个 delay 档（要求 >=4）" % (dn, len(delays)))
    for k in delays:
        cell = tbl[str(k)] if str(k) in tbl else tbl[k]
        n = cell.get("n")
        if not isinstance(n, int) or n < 20:
            bad.append("%s delay=%s 的 n=%s < 20" % (dn, k, n))
        if not isinstance(cell.get("mismatch_rate"), (int, float)):
            bad.append("%s delay=%s 缺数值 mismatch_rate" % (dn, k))
if not isinstance(d.get("load1"), (int, float)):
    bad.append("缺 load1")

# 说明里必须交代「这两条实测把发出去的哪句话改成了什么」——这一格的产出要能直接用于追加信
if "追加" not in note and "改成" not in note and "修正" not in note:
    bad.append("说明里没写「把发给下游的哪句话改成了什么」—— leader 要据此发追加信")

if bad:
    print("FAIL 自查产物不合格：")
    for x in bad[:10]: print("  - " + x)
    sys.exit(1)

for dn in dirs:
    tbl = d[dn]
    ks = sorted(int(k) for k in tbl if str(k).lstrip('-').isdigit())
    line = "  ".join("d=%sms→%.1f%%" % (k, (tbl.get(str(k)) or tbl.get(k))["mismatch_rate"] * 100
                     if (tbl.get(str(k)) or tbl.get(k))["mismatch_rate"] <= 1 else
                     (tbl.get(str(k)) or tbl.get(k))["mismatch_rate"]) for k in ks)
    print("  %-8s %s" % (dn, line))
print("PASS E1 两方向 × >=4 档 × n>=20 齐、含 delay=0；E2 测试与真 fixture 在仓里；说明交代了改哪句话")
print("     ⚠️ 本判据⛔ 不判断言成立与否——不成立就是我方缺陷，那也是合法且重要的结果。")
sys.exit(0)
PY
