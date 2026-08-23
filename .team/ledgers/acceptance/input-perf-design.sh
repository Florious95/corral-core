#!/bin/sh
set -eu
f=.team/nodes/input-full-auto/perf-design/CONTRACT.md
[ -s "$f" ] || { echo 'FAIL missing CONTRACT.md' >&2; exit 1; }
grep -q 'baseline-20260822-release' "$f" || { echo 'FAIL baseline tag missing' >&2; exit 1; }
grep -q '0907d6881bb1e034ef33a49f89afaa44' "$f" || { echo 'FAIL reference md5 missing' >&2; exit 1; }
for x in big_scrollback real_claude_idle redraw_tui tap_to_route_enter route_enter_to_first_frame first_frame_to_first_draw tap_to_first_draw 'A/B/A/B' '1.10'; do
  grep -q "$x" "$f" || { echo "FAIL missing contract token: $x" >&2; exit 1; }
done
grep -q '^contract: ready$' "$f" || { echo 'FAIL contract is not executable-ready' >&2; exit 1; }
echo PASS
