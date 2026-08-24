#!/bin/sh
# //! purpose: 核 successor5 RED.md、SDK fallback 回归与 required 精确集合。
# //! contract: 0=交付结构完整；1=交付/账本结构被反证；2=SDK/目录/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor5-test: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor5-test: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
artifact="$repo_root/.team/nodes/baseline-bundle-test/RED.md"
[ -e "$artifact" ] || fail "missing RED.md"
[ -r "$artifact" ] || unjudgeable "RED.md unreadable"
[ -s "$artifact" ] || fail "RED.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for token in SUCCESSOR5_REQUIRED_EXACT SUCCESSOR5_LEGACY_NEGATIVE SUCCESSOR5_SDK_SOURCE_WHITELIST SUCCESSOR5_SDK_FALLBACK_NO_OUTPUT SUCCESSOR5_SDK_EXTRA_KEY_REJECTED SUCCESSOR5_SDK_NOT_TRACKED 'impl_required=successor5_impl,successor5_bypass' 'probe_required=successor5_probe' 'legacy_impl_bypass=absent' 'legacy_probe=absent' 'extra_key=2' 'duplicate_sdk_dir=2' 'invalid_sdk_dir=2' 'tracked_target=1' '--rerun-tasks' '--no-build-cache' '-count=1'; do
    grep -F -e "$token" "$artifact" >/dev/null 2>&1 || fail "RED.md missing $token"
done
for gate in baseline-bundle-successor5-sdk.sh baseline-bundle-successor5-sdk-regression.sh baseline-bundle-successor5-structure.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "required gate returned unsupported status" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor5-test: SDK fallback and exact required-list teeth present"
