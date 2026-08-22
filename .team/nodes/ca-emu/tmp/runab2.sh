#!/usr/bin/env bash
# 只跑 big_scrollback，A/B 各 20 次交替。样本落 raw-capp2，⛔ 不写 raw-capp/ 与 raw/。
set -uo pipefail
T="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$T/../../../../" && pwd)"
APK_A="${APK_A:?}"
APK_B="${APK_B:?}"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
OUTA="$WT/.team/perf/raw-capp2/A"
OUTB="$WT/.team/perf/raw-capp2/B"
DIAG="$WT/.team/perf/raw-capp2/diag"
NAV="$DIAG/navigation-runlog.log"
mkdir -p "$OUTA" "$OUTB" "$DIAG"
: > "$NAV"
install_apk() {
  echo "INSTALL $2 $(date +%H:%M:%S)"
  "$ADB" install -r "$1" >/dev/null
}
fx=big_scrollback
for n in $(seq 1 20); do
  echo "===== $fx #$n A then B  $(date +%H:%M:%S) load=$(uptime | sed 's/.*averages: //')"
  install_apk "$APK_A" A
  if OUTDIR="$OUTA" bash "$T/coldopen.sh" "$fx" "$n"; then
    echo "OK A $fx #$n" | tee -a "$NAV"
  else
    echo "FAIL A $fx #$n rc=$?" | tee -a "$NAV"
  fi
  install_apk "$APK_B" B
  if OUTDIR="$OUTB" bash "$T/coldopen.sh" "$fx" "$n"; then
    echo "OK B $fx #$n" | tee -a "$NAV"
  else
    echo "FAIL B $fx #$n rc=$?" | tee -a "$NAV"
  fi
done
echo ALLDONE
