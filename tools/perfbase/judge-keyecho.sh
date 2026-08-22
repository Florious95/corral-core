#!/bin/sh
# 判据：按键回显量具成立——**它真的量到了「按下 → 出字」，而且没有靠丢样本把数字做好看**。
#
# 为什么这把判据要这么啰嗦：这一格最容易交付一个「跑得通但量错东西」的量具。
# 现有 PerfTrace 量的是打开会话（first_draw），输入透传基本不碰它 ⇒ 拿它当回测会一路绿灯，
# 而它根本没量你改的东西。所以判据必须核**配对**与**样本量**，⛔ 不只核「有没有文件」。
#
# 四态：0=通过；1=不通过；2=不可判（环境不具备/协议挡住，且席位如实报了）。
set -u
S=.team/nodes/t.instr/说明.md
K=tools/perfbase/keyecho.sh
J=.team/perf/keyecho-baseline.json

[ -f "$S" ] || { echo "FAIL 说明不存在：${S}（连'我做了什么/卡在哪'都没交）"; exit 1; }

# 环境/协议挡住是**合法终态**，但必须带上判据能核的读数，⛔ 不许一句"跑不了"了事。
if [ ! -f "$J" ]; then
  if grep -qE 'blocked_env|blocked_proto' "$S"; then
    if grep -qE 'load|内存|MB|free' "$S"; then
      echo "UNJUDGEABLE 席位如实报了阻塞（见 ${S}），且带了环境读数 —— ⛔ 这不是失败，是判不出"
      echo "     判不出的是「量具量得准不准」，因为它还没跑过一次真实测量。"
      exit 2
    fi
    echo "FAIL ${S} 声称阻塞却没给任何环境读数（load / 可用内存 / 挡住的那一行代码）"
    echo "     ——没有读数的'跑不了'无法复核，等于没报"
    exit 1
  fi
  echo "FAIL 没有实测结果 ${J}，${S} 里也没声明 blocked_env/blocked_proto"
  exit 1
fi

[ -f "$K" ] || { echo "FAIL 量具脚本不存在：${K}（有结果没量具 = 结果不可复跑）"; exit 1; }

python3 - "$J" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是**不可判**，⛔ 不是不通过。python 未捕获异常退出码恰好是 1，
    # 与「量具真的不合格」撞码 —— 2026-08-22 已被这个撞码坑过一次。
    traceback.print_exception(t, v, tb)
    print("UNJUDGEABLE 判据自己跑不起来（见上方异常）——⛔ 这不是失败，是判不出")
    _s.exit(2)
_s.excepthook = _hook
import json, sys
f = sys.argv[1]
try:
    d = json.load(open(f))
except Exception as e:
    print("UNJUDGEABLE %s 读不出来：%s" % (f, e)); sys.exit(2)

MIN_N = 30
bad = []

n = d.get("n")
if not isinstance(n, int):
    print("UNJUDGEABLE json 里没有整数 n，认不出样本量"); sys.exit(2)
if n < MIN_N:
    bad.append("样本量 n=%s < %d —— ⛔ 不许拿几个样本报 p95" % (n, MIN_N))

# 🔴 配不上对的样本必须**单独计数**。丢弃它们会让 p95 好看，这是本判据最要防的一件事。
if "unmatched" not in d:
    bad.append("json 里没有 unmatched 字段 —— 配不上对的样本去哪了？静默丢弃会让 p95 好看")
else:
    u = d["unmatched"]
    if not isinstance(u, int):
        bad.append("unmatched 不是整数：%r" % (u,))
    elif n > 0 and u > n * 0.2:
        bad.append("配对失败率 %d/%d > 20%% —— 配不上对就算不出真实延迟，这个数不可信" % (u, n))

# 换负载区间不可直接比较 ⇒ 必须留负载读数
if "load1" not in d:
    bad.append("json 里没有 load1 —— ⚠️ 换负载区间的数不能直接比较，没有它这份基线以后没法对拍")
if not d.get("apk_md5"):
    bad.append("json 里没有 apk_md5 —— 认不出被测物是哪个包")

# p50/p95 必须真有数，且 p95 >= p50（数算错了会在这里露馅）
found = 0
def walk(node, path):
    global found
    if isinstance(node, dict):
        if "p50" in node and "p95" in node:
            a, b = node.get("p50"), node.get("p95")
            if not isinstance(a, (int, float)) or not isinstance(b, (int, float)):
                bad.append("%s 的 p50/p95 不是数字：%r %r" % (path, a, b))
            elif b < a:
                bad.append("%s 的 p95(%s) < p50(%s) —— 分位数算错了" % (path, b, a))
            elif a <= 0:
                bad.append("%s 的 p50=%s —— 非正的延迟不可能，多半是时间戳配对配错了" % (path, a))
            else:
                found += 1
                print("  ok   %-24s p50=%7.1fms p95=%7.1fms" % (path, a, b))
        for k, v in node.items():
            walk(v, path + "." + k if path else k)
walk(d, "")
if found == 0:
    bad.append("整份 json 里找不到任何一组 p50/p95")

if bad:
    print("FAIL 量具不合格：")
    for b in bad:
        print("  - " + b)
    sys.exit(1)
print("PASS 按键回显量具：n=%d 未配对=%s load1=%s apk=%s，%d 组分位数自洽"
      % (n, d.get("unmatched"), d.get("load1"), str(d.get("apk_md5"))[:12], found))
sys.exit(0)
PY
