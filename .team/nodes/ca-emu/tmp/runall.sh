#!/usr/bin/env bash
set -uo pipefail
T="$(cd "$(dirname "$0")" && pwd)"
export OUTDIR="${OUTDIR:-$T/../../../../.team/perf/raw}"
mkdir -p "$OUTDIR"
NAVLOG="$OUTDIR/diag/navigation-runlog.log"
mkdir -p "$OUTDIR/diag"
: > "$NAVLOG"
for fx in real_claude_idle redraw_tui big_scrollback; do
  for n in $(seq 1 10); do
    echo "--- $fx #$n $(date +%H:%M:%S) load=$(uptime | sed 's/.*averages: //')"
    if bash "$T/coldopen.sh" "$fx" "$n"; then
      echo "OK $fx #$n" | tee -a "$NAVLOG"
    else
      echo "FAIL $fx #$n rc=$?" | tee -a "$NAVLOG"
    fi
  done
done
echo ALLDONE
