#!/bin/sh
# //! purpose: 锁 permanent impl-bypass 的固定 provenance、绿控0、伪造1与缺失2。
# //! contract: 0=四态与摘要齿精确；1=回归；2=夹具/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor7-bypass-regression: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-bypass-regression: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
output=$(sh "$script_dir/baseline-bundle-successor7-impl-bypass.sh" 2>&1)
rc=$?
printf '%s\n' "$output"
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "permanent gate returned unsupported rc=$rc" ;; esac
printf '%s\n' "$output" | grep -F 'permanent_green=0 permanent_forged=1 permanent_missing=2' >/dev/null 2>&1 || fail "four-state evidence missing"
printf '%s\n' "PASS baseline-bundle-successor7-bypass-regression: stable fixture rejects forgery and reports missing as unjudgeable"
