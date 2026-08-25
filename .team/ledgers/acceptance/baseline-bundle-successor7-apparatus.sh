#!/bin/sh
# //! purpose: 验证 command 格留下的 fresh owned-emulator 证据、3528 四格连续性与 permanent impl-bypass。
# //! contract: 0=三者合取；1=证据/夹具被反证；2=WT、设备证据或量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor7-apparatus: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-apparatus: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve worktree"
evidence="$repo_root/.team/nodes/baseline-bundle-apparatus/APPARATUS.json"

for gate in baseline-bundle-successor7-continuity.sh baseline-bundle-successor7-impl-bypass.sh; do
    output=$(sh "$script_dir/$gate" 2>&1)
    rc=$?
    printf '%s\n' "$output"
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "$gate unsupported rc=$rc" ;; esac
done
python3 "$script_dir/baseline-bundle-successor7-apparatus.py" verify --repo-root "$repo_root" \
    --evidence "$evidence" --mode production --max-age 7200
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "apparatus evidence unsupported rc=$rc" ;; esac

printf '%s\n' "PASS baseline-bundle-successor7-apparatus: retained bundle installed on fresh owned emulator, cleaned, and permanent bypass rejected"
