#!/bin/sh
# 判据：端到端实测做扎实了——**三个断点各有结论、有截图、有 logcat 证据**。
#
# ⛔ 不判「展开了没有」：B3 不展开而 B1/B2 通，是合法且重要的结果
#    （那说明不是我方缺陷）。判据若只在「展开了」时给绿，就是在逼席位把没看清写成看清了。
# 判的是：三条都给了结论、截图在、B1 有 logcat 原文、B2 有坐标对照、内层是什么写清了。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.e2emouse/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
F=$(ls .team/perf/e2emouse-*.json 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "FAIL 没有 .team/perf/e2emouse-*.json"; exit 1; }

# ⛔ 红线：不许碰生产与用户真实 tmux
grep -qE ':9900|/private/tmp/tmux-501/default' "$S" && {
  echo "UNJUDGEABLE 说明里出现生产 daemon 或用户真实 socket —— 需 leader 人工看一眼"; exit 2; }

# 截图必须真的在（⛔ 「我看到了」不算）
SHOTS=$(ls .team/nodes/t.e2emouse/tmp/*.png 2>/dev/null | wc -l | tr -d ' ')
[ "$SHOTS" -ge 2 ] || {
  echo "FAIL 截图只有 ${SHOTS} 张（要求 >=2：点击前 / 点击后）"
  echo "     ⛔ 眼见为实铁律：没有实测截图不算数"
  exit 1; }
echo "  ok   截图 ${SHOTS} 张"

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

for k in ("b1_bytes_sent", "b2_coord_match"):
    if not isinstance(d.get(k), bool):
        bad.append("%s 必须是 true/false（拿到 %r）—— 含糊不算结论" % (k, d.get(k)))
b3 = d.get("b3_expanded")
if not (isinstance(b3, bool) or b3 == "unknown"):
    bad.append("b3_expanded 必须是 true/false/\"unknown\"（拿到 %r）" % (b3,))

app = d.get("inner_app")
if app not in ("claude-code", "custom-tui"):
    bad.append("inner_app 必须写清是 claude-code 还是 custom-tui（拿到 %r）"
               "—— ⛔ 不许把自制 TUI 的结果说成 Claude Code 的" % (app,))
if not d.get("injected_bytes"):
    bad.append("缺 injected_bytes")
if not d.get("apk_md5"):
    bad.append("缺 apk_md5，认不出被测包")
if not isinstance(d.get("load1"), (int, float)):
    bad.append("缺 load1")

# B1 说 true 就必须有 logcat 原文；B2 说 true 就必须有坐标对照
if d.get("b1_bytes_sent") is True and not any(x in note for x in ("logcat", "InputFrame", "bytes=")):
    bad.append("B1 声称 true 却没有 logcat 原文 —— ⛔ 不采信自报")
if d.get("b2_coord_match") is True and not any(x in note for x in ("行", "列", "row", "col")):
    bad.append("B2 声称 true 却没有坐标对照")

if bad:
    print("FAIL 端到端产物不合格：")
    for x in bad[:10]: print("  - " + x)
    sys.exit(1)

print("  b1 发出字节  = %s" % d["b1_bytes_sent"])
print("  b2 坐标对上  = %s" % d["b2_coord_match"])
print("  b3 展开了吗  = %s   （内层=%s）" % (d["b3_expanded"], d["inner_app"]))
print("PASS 三条都有结论、截图在、B1/B2 的主张有证据、内层身份写清")
print("     ⚠️ 本判据⛔ 不判「展开了没有」——B3 为 false/unknown 而 B1/B2 通，是合法结果。")
sys.exit(0)
PY
