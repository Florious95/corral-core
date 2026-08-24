#!/bin/sh
# //! purpose: 核 successor5 PROBE.md、SDK fallback 回归与 successor5-only probe 门。
# //! contract: 0=交付结构完整；1=交付/账本结构被反证；2=SDK/目录/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor5-probe: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor5-probe: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
artifact="$repo_root/.team/nodes/baseline-bundle-probe/PROBE.md"
[ -e "$artifact" ] || fail "missing PROBE.md"
[ -r "$artifact" ] || unjudgeable "PROBE.md unreadable"
[ -s "$artifact" ] || fail "PROBE.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for token in SUCCESSOR5_REQUIRED_EXACT SUCCESSOR5_LEGACY_NEGATIVE SUCCESSOR5_SDK_SOURCE_WHITELIST SUCCESSOR5_SDK_FALLBACK_NO_OUTPUT SUCCESSOR5_SDK_EXTRA_KEY_REJECTED SUCCESSOR5_SDK_NOT_TRACKED SUCCESSOR5_CANONICAL_REAL SUCCESSOR5_FIXED_FIXTURE 'probe_required=successor5_probe' 'legacy_probe=absent' 'source_tree_sha256=not_required' bundle_id independent_builds apk_relpath control-contract.json; do
    grep -F "$token" "$artifact" >/dev/null 2>&1 || fail "PROBE.md missing $token"
done
for gate in baseline-bundle-successor5-sdk.sh baseline-bundle-successor5-sdk-regression.sh baseline-bundle-successor5-structure.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "required gate returned unsupported status" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor5-probe: SDK fallback and successor5 probe contract verified"
