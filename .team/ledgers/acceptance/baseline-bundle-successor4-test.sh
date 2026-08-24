#!/bin/sh
# //! purpose: 核 successor4 RED.md、SDK 前置和 required 精确集合，防 legacy 门回流。
# //! contract: 0=交付结构完整；1=交付/账本结构被反证；2=SDK/目录/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor4-test: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor4-test: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
f="$repo_root/.team/nodes/baseline-bundle-test/RED.md"
[ -e "$f" ] || fail "missing RED.md"
[ -r "$f" ] || unjudgeable "RED.md unreadable"
[ -s "$f" ] || fail "RED.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for token in SUCCESSOR4_REQUIRED_EXACT SUCCESSOR4_LEGACY_NEGATIVE SUCCESSOR4_SDK_PREFLIGHT_NO_VALUE_DISCLOSURE 'impl_required=successor4_impl,successor4_bypass' 'probe_required=successor4_probe' 'legacy_impl_bypass=absent' 'legacy_probe=absent' 'missing_local_properties=2' '--rerun-tasks' '--no-build-cache' '-count=1'; do
    grep -F -e "$token" "$f" >/dev/null 2>&1 || fail "RED.md missing $token"
done
for gate in baseline-bundle-successor4-sdk.sh baseline-bundle-successor4-structure.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "required gate rc=$rc" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor4-test: required-list teeth and SDK contract present"
