#!/bin/sh
# 判据：第 0 步的打点本身没让「打开会话」变慢——B（带 key_send/key_echo 打点）对同批 A（打点之前）
# 不许慢超过 10%。
#
# 为什么必须做这一轮：第 0 步只加仪表、不改行为，但**加在热路径上的仪表也是代码**。
# 用户的硬要求是「每一个步骤都要有性能基线的回测」，第 0 步是一步。
#
# ⚠️ 一个必须说清的量具局限：`coldopen.sh` 从 `adb logcat -s PerfTrace` 取数，
# **所以测的时候 PerfTrace 必然是开的**。⇒ 这一轮量的是「打点开着时的代价」，
# ⛔ 量不到「打点关着时的代价」。开着都不退，关着更不会退（关路径只多一次空引用判断），
# 所以这个方向是安全的；但**结论只能说到「开着不退」**，⛔ 不许写成「零成本已验证」。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
F=$(ls .team/perf/instr-ab-*.json 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "UNJUDGEABLE 没有 A/B 对拍文件 .team/perf/instr-ab-*.json"; exit 2; }

python3 - "$F" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是**不可判**，⛔ 不是不通过（python 未捕获异常退出码恰好是 1，会与真红撞码）。
    traceback.print_exception(t, v, tb)
    print("UNJUDGEABLE 判据自己跑不起来（见上方异常）——⛔ 这不是失败，是判不出")
    _s.exit(2)
_s.excepthook = _hook
import json, sys
d = json.load(open(sys.argv[1]))

THRESH, MIN_N = 10.0, 20
SEGS = ("first_draw", "layout_settled")

# 🔴 方法学闸（2026-08-23 用户裁定走"提高量测质量"这条路之后加的，⛔ 这是收紧不是放宽）
# 第一轮实测失败的教训：debug 包 p50 在 1-2s 量级，而 release 地板是 ~150ms —— **差一个数量级**；
# 同一夹具量出 p50 +34.7% 而 p95 -44.8%，**方向都对不上** ⇒ 噪声压过了效应。
# 在那种条件下判据即使给出 0 或 1，那个数也是噪声，不是结论。所以：条件不达标 = 判不出。
bt = d.get("build_type")
if bt != "release":
    print("UNJUDGEABLE build_type=%r —— 必须用 release 包对拍。" % (bt,))
    print("     debug 包 p50 在 1-2s 量级、release 地板 ~150ms，不是同一量程，测了也不能比。")
    sys.exit(2)
l1 = d.get("load1")
if not isinstance(l1, (int, float)):
    print("UNJUDGEABLE json 里没有数值 load1，认不出测的时候机器多吵"); sys.exit(2)
if l1 > 12.0:
    print("UNJUDGEABLE load1=%.2f > 12 —— 机器太吵，判不出。" % l1)
    print("     20260822 地板的负载区间是 6.87-10.49；load 上到 13-24 时，机器漂移大于要检测的效应。")
    sys.exit(2)

a5, b5 = (d.get("A") or {}).get("apk_md5"), (d.get("B") or {}).get("apk_md5")
if not a5 or not b5:
    print("UNJUDGEABLE A/B 缺 apk_md5，认不出被测物"); sys.exit(2)
if a5 == b5:
    print("UNJUDGEABLE A 与 B 的 apk_md5 相同（%s）——这不是对拍，是同一个包测了两遍" % a5[:12]); sys.exit(2)

# 🔴 只取**单批**，⛔ 不取跨批合并：2026-08-22 实测同一个包两批之间 p50 自己漂了 16.5%，
# 机器漂移量大于要检测的效应；跨批合并量到的是漂移，不是差异。
cmp_all = {k: v for k, v in (d.get("comparison") or {}).items()
           if isinstance(v, dict) and "combined" not in k}
if not cmp_all:
    print("UNJUDGEABLE comparison 里没有单批数据"); sys.exit(2)

def nof(b):
    for fx in b.values():
        if isinstance(fx, dict):
            for seg in fx.values():
                if isinstance(seg, dict) and isinstance(seg.get("A"), dict):
                    return seg["A"].get("n") or 0
    return 0

CH = max(cmp_all, key=lambda k: nof(cmp_all[k]))
print("判决依据批次 = %s（单批 n=%d）" % (CH, nof(cmp_all[CH])))
cmp_ = cmp_all[CH]

red, ok, unj = [], [], []
for fx in sorted(cmp_):
    for seg in SEGS:
        node = (cmp_[fx] or {}).get(seg)
        if not isinstance(node, dict):
            unj.append("%s.%s 缺这一段" % (fx, seg)); continue
        A, B = node.get("A") or {}, node.get("B") or {}
        for m in ("p50", "p95"):
            av, bv, na, nb = A.get(m), B.get(m), A.get("n"), B.get("n")
            if av is None or bv is None:
                unj.append("%s.%s.%s 取不到值" % (fx, seg, m)); continue
            if not isinstance(na, int) or not isinstance(nb, int) or na < MIN_N or nb < MIN_N:
                unj.append("%s.%s 样本不足（A=%s B=%s，各需 >=%d）" % (fx, seg, na, nb, MIN_N)); continue
            pct = (bv - av) / av * 100.0 if av else 0.0
            line = "%-15s %-14s %-3s A=%7.1fms B=%7.1fms (%+.1f%%)" % (fx, seg, m, av, bv, pct)
            (red if pct > THRESH else ok).append(line)

# ⛔ 不可判不许折进通过或失败：数据不齐就是判不出，不是「打点没问题」。
if unj:
    print("UNJUDGEABLE 数据不齐，判不了：")
    for u in unj[:8]: print("  " + u)
    sys.exit(2)
for l in ok:  print("  ok   " + l)
for l in red: print("  RED  " + l)
if red:
    print("FAIL 打点让打开会话慢了超过 %.0f%%，共 %d 段（A=%s B=%s）" % (THRESH, len(red), a5[:12], b5[:12]))
    sys.exit(1)
print("PASS 打点开着时相对同批 A 全段未超 %.0f%%（%d 段）" % (THRESH, len(ok)))
print("     ⚠️ 本轮只证明「打点开着不退」，⛔ 未测「打点关着」——量具本身要靠 PerfTrace 取数。")
sys.exit(0)
PY
