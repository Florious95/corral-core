#!/bin/sh
# //! purpose: 核 successor6 PROBE.md 的独立操作数、投影真实齿、SDK fallback 与 required 精确集合。
# //! contract: 0=交付结构与真实齿完整；1=交付/账本结构被反证；2=SDK/fixture/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor6-probe: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor6-probe: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
artifact="$repo_root/.team/nodes/baseline-bundle-probe/PROBE.md"
[ -e "$artifact" ] || fail "missing PROBE.md"
[ -r "$artifact" ] || unjudgeable "PROBE.md unreadable"
[ -s "$artifact" ] || fail "PROBE.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for token in SUCCESSOR6_LEGACY_PREFIX_REPRO SUCCESSOR6_CANONICAL_IDENTITY SUCCESSOR6_SLOT_PROJECTION SUCCESSOR6_ARCHIVE_PROJECTION 'projection_keys=source,runtime,artifact,build,equivalence,implementation' 'slot0=build-1' 'slot1=build-2' 'manifest_stable=true' bundle_id independent_builds apk_relpath; do
    grep -F -e "$token" "$artifact" >/dev/null 2>&1 || fail "PROBE.md missing $token"
done
for gate in baseline-bundle-successor5-sdk-regression.sh baseline-bundle-successor6-projection-regression.sh baseline-bundle-successor6-structure.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "required gate returned unsupported status" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor6-probe: independent projection operands and exact required-list verified"
