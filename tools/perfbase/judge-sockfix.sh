#!/bin/sh
# 判据：死 socket → 空闲 CPU 的因果曲线做出来了，而且修完之后**曲线被压平**。
#
# 判什么、不判什么：
#   判  ① before/after 两条曲线都在、N 序列覆盖到 140（生产实撞的量级）、每点样本 >=60；
#       ② after 的最坏点必须显著低于 before 的最坏点，且 after 不随 N 明显增长；
#       ③ 功能不许退：活 socket 发现数 before==after。
#   ⛔ 不判 before 曲线「必须涨」——如果它不涨，那是 hypothesis_refuted，
#      席位该走那条合法出口，⛔ 判据不该逼出一个想要的结论。
#      所以 before 不涨时本判据返回 **2（不可判）**，把裁定交回 leader。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.sockfix/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
F=$(ls .team/perf/sockfix-*.json 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "FAIL 没有 .team/perf/sockfix-*.json —— 两条曲线是本格的交付物"; exit 1; }

# ⛔ 红线：不许往用户真实 socket 目录里造死 socket
grep -q '/private/tmp/tmux-501' "$S" && grep -qiE 'mkdir|touch|造|放入|写入' "$S" && {
  echo "UNJUDGEABLE 说明里同时出现用户真实 socket 目录与造 socket 的动作——需 leader 人工看一眼"; exit 2; }

python3 - "$F" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是不可判，⛔ 不是不通过（未捕获异常退出码恰好 1，会与真红撞码）。
    traceback.print_exception(t, v, tb); print("UNJUDGEABLE 判据自己跑不起来"); _s.exit(2)
_s.excepthook = _hook
import json, sys
d = json.load(open(sys.argv[1]))

MIN_SAMPLES = 60
NEED_N = 140          # 生产实撞的死 socket 量级，必须覆盖到

bad = []
if not d.get("daemon_commit"): bad.append("缺 daemon_commit")
p = d.get("port")
if not isinstance(p, int): bad.append("缺 port")
elif p == 9900:          bad.append("port=9900 —— ⛔ 生产 daemon，红线")
if not d.get("dead_socket_recipe"): bad.append("缺 dead_socket_recipe —— 死 socket 怎么造的必须写清，否则复现不了")

def curve(name):
    c = d.get(name)
    if not isinstance(c, dict) or not c:
        bad.append("缺曲线 %s" % name); return None
    out = {}
    for k, v in c.items():
        try: n = int(k)
        except Exception: bad.append("%s 的键 %r 不是整数 N" % (name, k)); continue
        if not isinstance(v, dict): bad.append("%s[%s] 不是对象" % (name, k)); continue
        s = v.get("n_samples")
        if not isinstance(s, int) or s < MIN_SAMPLES:
            bad.append("%s[N=%d] 样本 %s < %d" % (name, n, s, MIN_SAMPLES)); continue
        m = v.get("mean")
        if not isinstance(m, (int, float)):
            bad.append("%s[N=%d] 缺数值 mean" % (name, n)); continue
        out[n] = m
    return out

b, a = curve("before"), curve("after")
if bad:
    print("FAIL 曲线产物不合格：")
    for x in bad[:10]: print("  - " + x)
    sys.exit(1)

for nm, c in (("before", b), ("after", a)):
    if max(c) < NEED_N:
        print("UNJUDGEABLE %s 曲线最大 N=%d，没覆盖到生产实撞的 %d —— 判不出" % (nm, max(c), NEED_N))
        sys.exit(2)

bw, aw = max(b.values()), max(a.values())
b0 = b.get(min(b))
print("  before 曲线：" + "  ".join("N=%d→%.1f%%" % (k, b[k]) for k in sorted(b)))
print("  after  曲线：" + "  ".join("N=%d→%.1f%%" % (k, a[k]) for k in sorted(a)))

# ⛔ before 不涨 = 机理没坐实 ⇒ 不可判，交回 leader，⛔ 不冒充通过也不冒充失败
if bw - (b0 or 0) < 3.0:
    print("UNJUDGEABLE before 曲线没有随死 socket 数明显上升（最坏 %.1f%% vs N 最小处 %.1f%%）" % (bw, b0 or 0))
    print("     ⇒ 机理未坐实。这不是失败，是 leader 的诊断可能不对，交回人工裁定。")
    sys.exit(2)

fail = []
if aw >= bw * 0.5:
    fail.append("after 最坏点 %.1f%% 未低到 before 最坏点 %.1f%% 的一半——没压平" % (aw, bw))
ag = aw - min(a.values())
if ag > 5.0:
    fail.append("after 仍随 N 增长 %.1f 个百分点（阈值 5）——开销还是跟死 socket 数挂钩" % ag)
fb, fa = d.get("live_discovered_before"), d.get("live_discovered_after")
if fb is None or fa is None:
    fail.append("缺 live_discovered_before/after —— 功能不退必须自证")
elif fb != fa:
    fail.append("活 socket 发现数变了：before=%r after=%r —— 功能退了" % (fb, fa))

if fail:
    print("FAIL 修复未达标：")
    for x in fail: print("  - " + x)
    sys.exit(1)
print("PASS before 最坏 %.1f%% → after 最坏 %.1f%%；after 随 N 增长 %.1f 个百分点；活 socket 发现数不变(%s)"
      % (bw, aw, ag, fa))
sys.exit(0)
PY
