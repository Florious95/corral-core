#!/usr/bin/env bash
# fix-l2-8.sh - L2-8 criterion fix patch (found by control seat, apply at leader/advisor's discretion)
#
# Background: during t.verify the control seat found a bug in probe.sh L2-8:
#   probe.sh line 105: `grep -q 'Level2ViewModel\|Level2Entry' "$APP_MAIN/.../workspace/"`
#   greps a DIRECTORY without -r. Inside the script grep resolves to /usr/bin/grep
#   (BSD grep 2.6.0) which does not recurse into a directory arg and always
#   returns "Is a directory" exit 2 -> L2-8 permanently FAIL.
#   The implementation is complete (Level2ViewModel.kt exists); the criterion
#   false-reds on a correct implementation.
#
# Evidence:
#   - probe.sh baseline: L2-1..L2-7 PASS / L2-8 FAIL / L2-9..L2-11 PASS
#   - /usr/bin/grep -rq on workspace/ dir returns 0 (Level2ViewModel present)
#   - /usr/bin/grep -q on Level2ViewModel.kt file returns 0
#   - After changing L2-8 to grep -rq, a probe.sh copy is 11/11 PASS (exit 0)
#
# Fix: add -r to the L2-8 grep (directory recursion). One-line change, only
# affects the L2-8 verdict.
#
# Usage:
#   1. Preview the diff:  bash .team/nodes/level2-verify/fix-l2-8.sh --dry-run
#   2. Apply the fix:     bash .team/nodes/level2-verify/fix-l2-8.sh --apply
#      (script runs probe.sh after to confirm 11/11 green)
#
# Note: this patch is NOT applied by default (the control seat does not modify
# anything). Apply only after leader/advisor decides.
set -u

PROBE=".team/nodes/level2-livestream/probe.sh"
FIX_LINE='if grep -q '\''Level2ViewModel\|Level2Entry'\'' "$APP_MAIN/dev/agentmirror/app/workspace/" 2>/dev/null; then'
FIXED_LINE='if grep -rq '\''Level2ViewModel\|Level2Entry'\'' "$APP_MAIN/dev/agentmirror/app/workspace/" 2>/dev/null; then'

if [ ! -f "$PROBE" ]; then
  echo "ERROR: probe.sh not found: $PROBE" >&2
  exit 2
fi

case "${1:-}" in
  --dry-run)
    echo "Patch to apply to: $PROBE"
    echo "  - ${FIX_LINE}"
    echo "  + ${FIXED_LINE}"
    echo "Effect: L2-8 now greps the workspace/ directory recursively; BSD grep no longer returns exit 2 for a directory arg."
    echo "Verify: after apply, probe.sh goes from L2-8 FAIL to 11/11 PASS (exit 0)."
    exit 0
    ;;
  --apply)
    if grep -Fq "$FIXED_LINE" "$PROBE"; then
      echo "Already applied (idempotent), skipping."
    elif grep -Fq "$FIX_LINE" "$PROBE"; then
      python3 - "$PROBE" "$FIX_LINE" "$FIXED_LINE" <<'PYEOF'
import sys
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding='utf-8').read()
if old not in s:
    sys.exit("anchor not found in " + p)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
      echo "Fix applied: L2-8 grep now has -r."
    else
      echo "ERROR: L2-8 anchor line not found; probe.sh may have changed." >&2
      exit 3
    fi
    ;;
  *)
    echo "Usage: $0 --dry-run | --apply" >&2
    exit 2
    ;;
esac

# After apply, verify probe.sh is 11/11 green
if [ "${1:-}" = "--apply" ]; then
  echo "=== verify probe.sh after fix ==="
  bash "$PROBE"
  echo "=== probe.sh exit: $? (0=all green) ==="
fi
