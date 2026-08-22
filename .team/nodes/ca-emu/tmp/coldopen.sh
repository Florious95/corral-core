#!/usr/bin/env bash
# coldopen.sh <fixture> <n>  —— 一次冷点开：force-stop → 冷启 → 进夹具工作区 → 点开会话 → 收 PerfTrace
# ⛔ 只点 pb-emu 自造夹具（工作区 cwd 精确匹配），⛔ 不点真实舰队；⛔ 不取屏不识图。
set -uo pipefail
T="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$T/uilib.sh"
FX="$1"; N="$2"
CWD="$T/cwd"
CWD_LABEL="$(basename "$CWD")"
RAW="$T/../../../../.team/perf/raw-capp"  # 复测另开目录，⛔ 不再覆盖基线 raw/
OUT="${OUTDIR:?OUTDIR required}"
mkdir -p "$OUT"
LOG="$OUT/${FX}-$(printf '%02d' "$N").log"

printf "%s\t%s\t%s\t%s\n" "$(date +%H:%M:%S)" "$FX" "$N" "$(uptime | sed 's/.*averages: //')" >> "$OUT/host-load.tsv"
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
sleep 2
"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1

# 等列表页出现夹具工作区行（最多 30s）。UI 可能只露出末段目录名。
ok=0
for i in $(seq 1 45); do
  dumpui > "$T/cur.xml"
  if grep -q "$CWD" "$T/cur.xml" || grep -q "text=\"$CWD_LABEL\"" "$T/cur.xml"; then ok=1; break; fi
  sleep 1
done
[ "$ok" = 1 ] || { echo "FAIL($FX#$N): 列表页未出现夹具工作区" | tee -a "$LOG"; exit 2; }

c=$(node_center "$T/cur.xml" "$CWD")
[ -z "$c" ] && c=$(node_center "$T/cur.xml" "$CWD_LABEL")
[ -n "$c" ] || { echo "FAIL($FX#$N): 工作区行无坐标" | tee -a "$LOG"; exit 2; }
"$ADB" shell input tap ${c% *} ${c#* } >/dev/null 2>&1

# 等二级会话页出现该夹具行
ok=0
for i in $(seq 1 30); do
  sleep 1
  dumpui > "$T/cur.xml"
  if grep -q "\"$FX\"" "$T/cur.xml"; then ok=1; break; fi
done
[ "$ok" = 1 ] || { echo "FAIL($FX#$N): 二级页未出现会话行" | tee -a "$LOG"; exit 2; }

c=$(node_center "$T/cur.xml" "$FX")
[ -n "$c" ] || { echo "FAIL($FX#$N): 会话行无坐标" | tee -a "$LOG"; exit 2; }
# —— 这一 tap 就是被测的「点开会话」——
"$ADB" shell input tap ${c% *} ${c#* } >/dev/null 2>&1

sleep 10   # 等排布静默（layout_settled 静默窗口 500ms，留足余量）
"$ADB" logcat -d -s PerfTrace > "$LOG" 2>&1
echo "$LOG"
