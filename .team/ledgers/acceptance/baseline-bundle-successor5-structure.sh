#!/bin/sh
# //! purpose: 锁 successor5 test/probe/impl required 与 mechanical 精确集合，拒绝 legacy/successor4 门回流。
# //! contract: 0=集合和四态绑定精确；1=结构被反证；2=候选账本或量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor5-structure: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
ledger="$repo_root/.team/ledgers/baseline-bundle-successor5-v1.json"
[ -e "$ledger" ] || unjudgeable "successor5 compiled ledger missing"
[ -r "$ledger" ] && [ -s "$ledger" ] || unjudgeable "successor5 compiled ledger unavailable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"

python3 - "$ledger" <<'PY'
import json
import sys

try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError) as exc:
    print(f"UNJUDGEABLE baseline-bundle-successor5-structure: invalid ledger: {type(exc).__name__}", file=sys.stderr)
    raise SystemExit(2)

expected = {
    "t.baseline-bundle.test": {
        "M.baseline-bundle.successor5-test": ".team/ledgers/acceptance/baseline-bundle-successor5-test.sh",
    },
    "t.baseline-bundle.probe": {
        "M.baseline-bundle.successor5-probe": ".team/ledgers/acceptance/baseline-bundle-successor5-probe.sh",
    },
    "t.baseline-bundle.impl": {
        "M.baseline-bundle.successor5-impl": ".team/ledgers/acceptance/baseline-bundle-successor5-impl.sh",
        "M.baseline-bundle.successor5-bypass": ".team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh",
    },
}
for task_id, wanted in expected.items():
    try:
        acceptance = data["tasks"][task_id]["acceptance"]
        required = acceptance["required"]
        mechanical = acceptance["mechanical"]
    except (KeyError, TypeError):
        print(f"FAIL baseline-bundle-successor5-structure: missing acceptance for {task_id}", file=sys.stderr)
        raise SystemExit(1)
    if required != list(wanted) or len(mechanical) != len(wanted):
        print(f"FAIL baseline-bundle-successor5-structure: required/mechanical mismatch for {task_id}", file=sys.stderr)
        raise SystemExit(1)
    actual = {}
    for item in mechanical:
        try:
            acceptance_id = item["acceptance_id"]
            argv = item["argv"]
        except (KeyError, TypeError):
            print(f"FAIL baseline-bundle-successor5-structure: malformed mechanical for {task_id}", file=sys.stderr)
            raise SystemExit(1)
        if len(argv) != 2 or argv[0] != "/bin/sh":
            print(f"FAIL baseline-bundle-successor5-structure: mechanical argv mismatch for {task_id}", file=sys.stderr)
            raise SystemExit(1)
        if item.get("cwd") != "${worktree}" or item.get("expected_exit_code") != 0 or item.get("unjudgeable_exit_codes") != [2]:
            print(f"FAIL baseline-bundle-successor5-structure: four-state binding mismatch for {task_id}", file=sys.stderr)
            raise SystemExit(1)
        actual[acceptance_id] = argv[1]
    if actual != wanted:
        print(f"FAIL baseline-bundle-successor5-structure: mechanical map mismatch for {task_id}", file=sys.stderr)
        raise SystemExit(1)

serialized = json.dumps(data, sort_keys=True)
for forbidden in ("M.baseline-bundle.impl-bypass", "M.baseline-bundle.probe", "M.baseline-bundle.successor4-impl", "M.baseline-bundle.successor4-probe"):
    if forbidden in serialized:
        print(f"FAIL baseline-bundle-successor5-structure: obsolete acceptance present: {forbidden}", file=sys.stderr)
        raise SystemExit(1)
raise SystemExit(0)
PY
rc=$?
case "$rc" in 0) exit 0 ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "structure checker rc=$rc" ;; esac
