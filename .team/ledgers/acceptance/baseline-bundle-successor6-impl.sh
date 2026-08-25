#!/bin/sh
# //! purpose: 组合 successor5 SDK fallback、successor6 身份/槽位门、完整深门与既有防伪 fixture。
# //! contract: 0=真实 bundle 全绿；1=实现/身份/槽位/摘要/防伪被反证；2=SDK/fixture/资产/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor6-impl: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor6-impl: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
manifest="$repo_root/.team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json"
projection="$script_dir/baseline-bundle-successor6-projection.py"
contract="$script_dir/fixtures/baseline-bundle-successor6/projection-contract.json"

command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v shasum >/dev/null 2>&1 || unjudgeable "shasum unavailable"
for fixed_input in "$manifest" "$projection" "$contract"; do
    [ -r "$fixed_input" ] && [ -s "$fixed_input" ] || unjudgeable "required projection input unavailable"
done

for gate in baseline-bundle-successor5-sdk.sh baseline-bundle-successor5-sdk-regression.sh baseline-bundle-successor6-projection-regression.sh; do
    [ -r "$script_dir/$gate" ] && [ -s "$script_dir/$gate" ] || unjudgeable "required bootstrap gate unavailable"
    sh "$script_dir/$gate"
    gate_rc=$?
    case "$gate_rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "bootstrap gate returned unsupported status" ;; esac
done

manifest_before=$(shasum -a 256 "$manifest" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash manifest before projection"
assert_manifest_stable() {
    manifest_current=$(shasum -a 256 "$manifest" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash manifest during gates"
    [ "$manifest_before" = "$manifest_current" ] || fail "manifest changed while gates were running"
}
projection_output=$(python3 "$projection" --manifest "$manifest" --contract "$contract" 2>&1)
projection_rc=$?
printf '%s\n' "$projection_output"
case "$projection_rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "projection gate returned unsupported status" ;; esac
printf '%s\n' "$projection_output" | grep -F 'SUCCESSOR6_CANONICAL_IDENTITY equal=true' >/dev/null 2>&1 || fail "canonical identity evidence missing"
printf '%s\n' "$projection_output" | grep -F 'SUCCESSOR6_SLOT_PROJECTION schema=true non_circular=true stable=true no_traversal=true independent=true' >/dev/null 2>&1 || fail "independent slot evidence missing"
assert_manifest_stable

for gate in baseline-bundle-successor6-deep.sh baseline-bundle-successor3-canonical.sh baseline-bundle-successor3-bypass.sh; do
    [ -r "$script_dir/$gate" ] && [ -s "$script_dir/$gate" ] || unjudgeable "required deep or antiforgery gate unavailable"
    sh "$script_dir/$gate"
    gate_rc=$?
    case "$gate_rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "deep or antiforgery gate returned unsupported status" ;; esac
    assert_manifest_stable
done

printf '%s\n' "PASS baseline-bundle-successor6-impl: content identity, independent slots, deep digests and antiforgery verified"
