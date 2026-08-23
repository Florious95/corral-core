#!/bin/sh
set -eu
sh .team/ledgers/acceptance/input-perf-measure.sh
f=.team/nodes/input-full-auto/perf-verify/VERDICT.md
[ -s "$f" ] || { echo 'FAIL missing VERDICT.md' >&2; exit 1; }
for x in '破坏齿' 'baseline-20260822-release' 'A/B/A/B' 'p50' 'p95'; do grep -q "$x" "$f" || { echo "FAIL verdict missing: $x" >&2; exit 1; }; done
grep -q '^verdict: pass$' "$f" || { echo 'FAIL independent verdict not pass' >&2; exit 1; }
echo PASS
