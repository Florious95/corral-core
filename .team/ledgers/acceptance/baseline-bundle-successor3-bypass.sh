#!/bin/sh
# //! purpose: 用真实实现/measure 入口做绿色控制与单变量 provenance 伪造齿，拒绝 root/empty-raw 旁路红。
# //! contract: 0=绿色控制均0且固定伪造均由指定原因拒绝1/2；1=放行或拒绝形状漂移；2=入口/夹具/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor3-bypass: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor3-bypass: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
helper="$script_dir/baseline-bundle-successor3-real-fixture.py"
contract="$script_dir/fixtures/baseline-bundle-successor3/control-contract.json"
scratch="$repo_root/.team/nodes/spec-sol/baseline-bundle-successor3/tmp/bypass-controlled"

command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v shasum >/dev/null 2>&1 || unjudgeable "shasum unavailable"
command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
for f in "$repo_root/tools/perfbase/baseline_bundle.py" "$script_dir/baseline-bundle-measure.sh" "$script_dir/baseline-bundle-successor3-measure.sh" "$helper" "$contract"; do
    [ -e "$f" ] || unjudgeable "fixed real-tooth input missing"
    [ -r "$f" ] && [ -s "$f" ] || unjudgeable "fixed real-tooth input unreadable or empty"
done
contract_sha=$(shasum -a 256 "$contract" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash fixed control contract"
[ "$contract_sha" = "ffcea3d0d3282618ad91f9db44c7a99616868b6610c88516e022385e59bd3fd9" ] || unjudgeable "fixed control contract digest drift"

output=$(python3 "$helper" --mode bypass --implementation-root "$repo_root" --source-root "$repo_root" --contract "$contract" --scratch "$scratch" 2>&1)
rc=$?
printf '%s\n' "$output"
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "controlled real bypass fixture unsupported rc=$rc" ;; esac
printf '%s\n' "$output" | grep -F 'SUCCESSOR3_CANONICAL_REAL' >/dev/null 2>&1 || fail "canonical control evidence missing"
printf '%s\n' "$output" | grep -F 'forged_base_rc=2 forged_hardened_rc=1 forged_reason=manifest_bundle_id_mismatch' >/dev/null 2>&1 || fail "canonical hardened classification evidence missing"
printf '%s\n' "$output" | grep -F 'SUCCESSOR3_MEASURE_CONTROL green_rc=0 forged_rc=1 reason=runner_provenance_mismatch' >/dev/null 2>&1 || fail "measure control evidence missing"
if printf '%s\n' "$output" | grep -E 'repository root mismatch|empty raw log' >/dev/null 2>&1; then
    fail "bypass tooth used forbidden side-path rejection"
fi
printf '%s\n' "PASS baseline-bundle-successor3-bypass: controlled real provenance forgeries rejected"
