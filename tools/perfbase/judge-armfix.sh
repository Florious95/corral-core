#!/bin/sh
# 判据：按键回显打点已从 PerfTrace 总开关上摘下来——**PerfTrace 开着但按键开关关着时，挂钩必须是 null**。
#
# 为什么这么判：这次回炉要修的不是「慢」，而是「**开会话测量会误触发逐字符回调**」。
# 所以判据的核心不是跑分，是核**挂钩条件**：`onAsciiPrint` 的赋值不能只看 `PerfTrace.isEnabled()`。
# 跑分那一关由 leader 另立的 A/B 格负责，⛔ 这一格不冒充它。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.armfix/说明.md
VM=app/app/src/main/java/dev/agentmirror/app/session/SessionViewModel.kt
TE=app/core-terminal/src/main/kotlin/dev/agentmirror/terminal/TerminalEmulator.kt
KE=tools/perfbase/keyecho.sh
J=.team/perf/keyecho-baseline.json

for f in "$VM" "$TE" "$KE"; do
  [ -f "$f" ] || { echo "UNJUDGEABLE 源文件不在，核不了挂钩条件：${f}"; exit 2; }
done
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }

# ① 挂钩那一行不许只看总开关。取 onAsciiPrint 赋值处上下 3 行，里面必须出现第二个条件。
CTX=$(grep -n -B3 'onAsciiPrint *=' "$VM" | head -40)
[ -n "$CTX" ] || { echo "FAIL 在 ${VM} 里找不到 onAsciiPrint 的赋值处"; exit 1; }
printf '%s\n' "$CTX" | grep -qE 'keyEcho|KeyEcho|key_echo|KEY_ECHO' || {
  echo "FAIL 挂钩条件里没有独立的按键回显开关，仍然只受 PerfTrace 总开关控制："
  printf '%s\n' "$CTX"
  echo "     ⇒ 每次开会话性能测量（PerfTrace 必开）都会逐字符回调，基线可比性被污染"
  exit 1
}

# ② keyecho.sh 必须负责把新开关打开，否则按键量具量不到东西
grep -qE 'keyEcho|key_echo|KEY_ECHO|keyecho' "$KE" || {
  echo "FAIL ${KE} 里没有打开按键回显开关的动作——量具会量不到"; exit 1; }

# ③ 按键量具仍然能用：重跑过的结果必须还在，且配对没坏
[ -f "$J" ] || { echo "FAIL 没有重跑后的 ${J}——改完必须自证量具还能用"; exit 1; }
python3 - "$J" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是不可判，⛔ 不是不通过（未捕获异常退出码恰好是 1，会与真红撞码）。
    traceback.print_exception(t, v, tb)
    print("UNJUDGEABLE 判据自己跑不起来（见上方异常）"); _s.exit(2)
_s.excepthook = _hook
import json, sys
d = json.load(open(sys.argv[1]))
n, u = d.get("n"), d.get("unmatched")
if not isinstance(n, int) or not isinstance(u, int):
    print("UNJUDGEABLE keyecho json 里 n/unmatched 不是整数"); sys.exit(2)
if n < 30:
    print("FAIL 重跑的按键量具 n=%d < 30" % n); sys.exit(1)
if u > n * 0.2:
    print("FAIL 重跑后配对失败率 %d/%d > 20%%——这次修复把量具改坏了" % (u, n)); sys.exit(1)
print("  ok   按键量具重跑仍可用：n=%d unmatched=%d" % (n, u))
PY
rc=$?
[ "$rc" -eq 0 ] || exit "$rc"

echo "PASS 挂钩已加独立按键开关、keyecho.sh 会开它、量具重跑仍可用"
echo "     ⚠️ 本判据只核【挂钩条件】，⛔ 不核跑分——跑分由 leader 另立的 A/B 格判。"
exit 0
