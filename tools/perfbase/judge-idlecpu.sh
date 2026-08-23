#!/bin/sh
# 判据：空闲 CPU 复现实验成立——**三态都测了、有原始秒级序列、结论与数一致**。
#
# ⚠️ 这把判据**不判「CPU 高不高」**，只判「这次实验做没做扎实」。
# 因为「复现不出」是合法且重要的结果（说明烧 CPU 来自生产环境特有的东西），
# 判据不该逼席位交出一个「复现成功」。⛔ 判据不许对结论有偏好。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.idlecpu/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
F=$(ls .team/perf/idlecpu-*.json 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "FAIL 没有 .team/perf/idlecpu-*.json——三态读数是本格的交付物"; exit 1; }

# ⛔ 生产 daemon 不许被碰：说明里若出现动生产的痕迹，直接红。
grep -qE 'kill .*16330|:9900 .*(kill|restart|停|重启)' "$S" && {
  echo "FAIL 说明里出现动生产 daemon（pid 16330 / :9900）的痕迹——红线"; exit 1; }

python3 - "$F" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是不可判，⛔ 不是不通过（未捕获异常退出码恰好 1，会与真红撞码）。
    traceback.print_exception(t, v, tb); print("UNJUDGEABLE 判据自己跑不起来"); _s.exit(2)
_s.excepthook = _hook
import json, sys
d = json.load(open(sys.argv[1]))
NEED = ("N0", "N1-idle", "N1-busy")
bad = []
for k in NEED:
    st = d.get(k)
    if not isinstance(st, dict):
        bad.append("缺态 %s —— 三态缺一就比不出「空闲 vs 有活」" % k); continue
    s = st.get("samples")
    if not isinstance(s, list) or len(s) < 30:
        bad.append("%s 的 samples 不是 >=30 个的秒级序列（拿到 %s）"
                   % (k, len(s) if isinstance(s, list) else type(s).__name__)); continue
    for f in ("mean", "p95", "max"):
        if not isinstance(st.get(f), (int, float)):
            bad.append("%s 缺数值字段 %s" % (k, f))
    if not isinstance(st.get("load1"), (int, float)):
        bad.append("%s 缺 load1 —— 换负载区间的数不能直接比较" % k)
if not d.get("daemon_commit"):
    bad.append("缺 daemon_commit，认不出被测的是哪份服务端")
port = d.get("port")
if not isinstance(port, int):
    bad.append("缺 port，认不出是不是隔离 daemon")
elif port == 9900:
    bad.append("port=9900 —— ⛔ 这是生产 daemon，红线")

if bad:
    print("FAIL 复现实验不合格：")
    for b in bad[:10]: print("  - " + b)
    sys.exit(1)

for k in NEED:
    st = d[k]
    print("  ok   %-9s n=%-4d mean=%5.1f%%  p95=%5.1f%%  max=%5.1f%%  load1=%s"
          % (k, len(st["samples"]), st["mean"], st["p95"], st["max"], st["load1"]))
i0, ii = d["N0"]["mean"], d["N1-idle"]["mean"]
print("PASS 三态齐、序列够长、隔离端口 %d、commit %s" % (port, str(d["daemon_commit"])[:12]))
print("     结论由 leader 读：N0=%.1f%% N1-idle=%.1f%%（趋近 0 = 复现不出，也是合法结果）" % (i0, ii))
sys.exit(0)
PY
