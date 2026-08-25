#!/bin/sh
# //! purpose: 核 successor6 RED.md、投影红绿齿、SDK fallback 与 required 精确集合。
# //! contract: 0=交付结构与真实齿完整；1=交付/账本结构被反证；2=SDK/fixture/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor6-test: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor6-test: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
artifact="$repo_root/.team/nodes/baseline-bundle-test/RED.md"
[ -e "$artifact" ] || fail "missing RED.md"
[ -r "$artifact" ] || unjudgeable "RED.md unreadable"
[ -s "$artifact" ] || fail "RED.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for token in SUCCESSOR6_LEGACY_PREFIX_REPRO SUCCESSOR6_CANONICAL_IDENTITY SUCCESSOR6_SLOT_PROJECTION SUCCESSOR6_ARCHIVE_PROJECTION 'legacy_constraint=1' 'legal_projection=0' 'bundle_id_tamper=1' 'path_traversal=1' 'slot_tamper=1' 'slot_swap=1' 'legacy_scoped=1' 'missing=2' '--rerun-tasks' '--no-build-cache' '-count=1'; do
    grep -F -e "$token" "$artifact" >/dev/null 2>&1 || fail "RED.md missing $token"
done
for gate in baseline-bundle-successor5-sdk-regression.sh baseline-bundle-successor6-projection-regression.sh baseline-bundle-successor6-structure.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "required gate returned unsupported status" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor6-test: projection, SDK and exact required-list teeth present"
