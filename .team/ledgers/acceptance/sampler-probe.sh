#!/bin/sh
set -eu
f=.team/nodes/input-full-auto/sampler-probe/PROBE.md
[ -s "$f" ] || exit 1
for x in 'runab.sh' 'paired.py' 'judge-perf-ab.sh' '破坏齿' 'exit 2' '四段' '隔离'; do grep -q "$x" "$f" || exit 1; done
