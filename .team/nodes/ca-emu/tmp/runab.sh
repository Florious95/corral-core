#!/usr/bin/env bash
set -uo pipefail
T="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$T/../../../../" && pwd)"
APK_A="${APK_A:?}"
APK_B="${APK_B:?}"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
NAV="$WT/.team/perf/raw-capp/diag/navigation-runlog.log"
mkdir -p "$WT/.team/perf/raw-capp/A" "$WT/.team/perf/raw-capp/B" "$WT/.team/perf/raw-capp/diag"
: > "$NAV"
install_apk() {
  echo "INSTALL $2 $(date +%H:%M:%S)"
  "$ADB" install -r "$1" >/dev/null
}
for fx in real_claude_idle redraw_tui big_scrollback; do
  for n in $(seq 1 10); do
    echo "===== $fx #$n A then B  $(date +%H:%M:%S) load=$(uptime | sed 's/.*averages: //')"
    install_apk "$APK_A" A
    if OUTDIR="$WT/.team/perf/raw-capp/A" bash "$T/coldopen.sh" "$fx" "$n"; then
      echo "OK A $fx #$n" | tee -a "$NAV"
    else
      echo "FAIL A $fx #$n rc=$?" | tee -a "$NAV"
    fi
    install_apk "$APK_B" B
    if OUTDIR="$WT/.team/perf/raw-capp/B" bash "$T/coldopen.sh" "$fx" "$n"; then
      echo "OK B $fx #$n" | tee -a "$NAV"
    else
      echo "FAIL B $fx #$n rc=$?" | tee -a "$NAV"
    fi
  done
done
echo ALLDONE
