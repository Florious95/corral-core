#!/bin/sh
# 判据：tmux `mouse` 开关对注入路径的影响被测死了——**两档都测了、样本够、三个问题都有答案**。
#
# ⛔ 不判结论方向：「有影响」和「没影响」都是合法结果。
#    判据若只在「契约那句话成立」时给绿，就是在奖励席位把实验做成想要的样子——
#    而这一格存在的理由恰恰是**怀疑那句话是错的**。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.tmuxmouse/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
F=$(ls .team/perf/tmuxmouse-*.json 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "FAIL 没有 .team/perf/tmuxmouse-*.json —— 两档读数是本格的交付物"; exit 1; }

# ⛔ 红线：不许碰用户真实 tmux
grep -qE '/private/tmp/tmux-501/default|tmux set-option -g mouse' "$S" && {
  echo "UNJUDGEABLE 说明里出现用户真实 socket 或全局 set-option —— 需 leader 人工看一眼"; exit 2; }

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

for k in ("on", "off"):
    c = d.get(k)
    if not isinstance(c, dict):
        bad.append("缺 mouse=%s 那一档" % k); continue
    n = c.get("n")
    if not isinstance(n, int) or n < 10:
        bad.append("mouse=%s 的 n=%s < 10" % (k, n))
    for f in ("received_count", "byte_exact_match_count"):
        if not isinstance(c.get(f), int):
            bad.append("mouse=%s 缺整数字段 %s" % (k, f))
    m = c.get("byte_exact_match_count")
    if isinstance(m, int) and isinstance(n, int) and m > n:
        bad.append("mouse=%s 的逐字节命中 %d > 样本 %d，算错了" % (k, m, n))

if not d.get("injected_bytes"):
    bad.append("缺 injected_bytes —— 注入了什么必须写清，否则复现不了")
if not d.get("sink_mode_flags"):
    bad.append("缺 sink_mode_flags —— 得证明 sink 真的开了鼠标模式，⛔ 不然测的是个聋子")
if not isinstance(d.get("load1"), (int, float)):
    bad.append("缺 load1")

# 说明里必须给出「契约那句话该改成什么」——leader 要据此改契约并给用户更正
if "契约" not in note or ("改成" not in note and "撤回" not in note and "仍成立" not in note):
    bad.append("说明里没写「契约 §1 那句话该改成什么 / 是否仍成立」—— leader 要据此更正用户")

if bad:
    print("FAIL 实验产物不合格：")
    for x in bad[:10]: print("  - " + x)
    sys.exit(1)

for k in ("on", "off"):
    c = d[k]
    print("  mouse=%-3s n=%-3d 收到=%-3d 逐字节命中=%-3d"
          % (k, c["n"], c["received_count"], c["byte_exact_match_count"]))
on, off = d["on"]["byte_exact_match_count"], d["off"]["byte_exact_match_count"]
print("PASS 两档齐、样本 >=10、注入串与 sink 模式标志有记录、说明给出了契约的处置")
print("     结论由 leader 读：on=%d off=%d —— 相等 ⇒ 契约 §1 那条推论对注入路径不成立。" % (on, off))
sys.exit(0)
PY
