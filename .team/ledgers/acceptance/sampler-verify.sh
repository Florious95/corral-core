#!/bin/sh
set -eu
sh .team/ledgers/acceptance/sampler-impl.sh
f=.team/nodes/input-full-auto/sampler-verify/VERDICT.md
[ -s "$f" ] || exit 1
for x in '破坏齿' 'exit 2' 'A/B/A/B'; do grep -q "$x" "$f" || exit 1; done
grep -q '^verdict: pass$' "$f" || exit 1
