#!/bin/sh
set -eu
f=.team/nodes/input-full-auto/sampler-test/RED.md
j=.team/nodes/input-full-auto/sampler-test/cases.json
[ -s "$f" ] && [ -s "$j" ] || exit 1
for x in 'A/B/A/B' 'md5' 'n<10' '1.10' 'exit 2' '阳性对照'; do grep -q "$x" "$f" || exit 1; done
python3 -m json.tool "$j" >/dev/null || exit 1
