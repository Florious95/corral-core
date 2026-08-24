#!/bin/sh
# //! purpose: successor3 独立锁定 canonical bundle identity、SDK 前置与真实 Baseline Bundle 交付。
# //! contract: 0=新红绿齿与真实 bundle 全绿；1=实现/交付被反证；2=SDK/量具/事实不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor3-impl: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor3-impl: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
node="$repo_root/.team/nodes/baseline-bundle-impl"
impl_report="$node/IMPL.md"
base_gate="$script_dir/baseline-bundle-impl.sh"
canonical_gate="$script_dir/baseline-bundle-successor3-canonical.sh"

command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v sed >/dev/null 2>&1 || unjudgeable "sed unavailable"
command -v find >/dev/null 2>&1 || unjudgeable "find unavailable"
git_root=$(git -C "$repo_root" rev-parse --show-toplevel 2>/dev/null) || fail "acceptance copy is outside a git worktree"
[ "$git_root" = "$repo_root" ] || fail "repository root mismatch"
head=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null) || unjudgeable "cannot resolve worktree HEAD"
git -C "$repo_root" merge-base --is-ancestor 6575cefc084871738f9817236220eb91e3173a6e "$head" 2>/dev/null || unjudgeable "worktree does not descend from successor3 provenance base"

[ -e "$impl_report" ] || fail "missing IMPL.md"
[ -r "$impl_report" ] || unjudgeable "IMPL.md unreadable"
[ -s "$impl_report" ] || fail "IMPL.md empty"
[ "$(sed -n '$p' "$impl_report" 2>/dev/null)" = 'implementation: pass' ] || fail "IMPL.md does not end in implementation: pass"

local_properties="$repo_root/app/local.properties"
[ -e "$local_properties" ] || unjudgeable "app/local.properties missing"
[ -r "$local_properties" ] || unjudgeable "app/local.properties unreadable"
[ -s "$local_properties" ] || unjudgeable "app/local.properties empty"
sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$local_properties" 2>/dev/null | sed -n '1p')
[ -n "$sdk_dir" ] || unjudgeable "sdk.dir missing from app/local.properties"
[ -d "$sdk_dir" ] && [ -r "$sdk_dir" ] || unjudgeable "declared Android SDK unavailable"
apksigner=$(find "$sdk_dir/build-tools" -type f -name apksigner -perm -111 -print 2>/dev/null | sort | tail -n 1)
aapt=$(find "$sdk_dir/build-tools" -type f -name aapt -perm -111 -print 2>/dev/null | sort | tail -n 1)
[ -n "$apksigner" ] && [ -x "$apksigner" ] || unjudgeable "executable apksigner unavailable"
[ -n "$aapt" ] && [ -x "$aapt" ] || unjudgeable "executable aapt unavailable"

[ -r "$canonical_gate" ] && [ -s "$canonical_gate" ] || unjudgeable "real canonical gate unavailable"
canonical_output=$(sh "$canonical_gate" 2>&1)
canonical_rc=$?
printf '%s\n' "$canonical_output"
case "$canonical_rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "real canonical gate rc=$canonical_rc" ;; esac
printf '%s\n' "$canonical_output" | grep -F 'SUCCESSOR3_CANONICAL_REAL' >/dev/null 2>&1 || fail "real canonical red/green evidence missing"

[ -r "$base_gate" ] && [ -s "$base_gate" ] || unjudgeable "base independent gate unavailable"
base_output=$(sh "$base_gate" 2>&1)
base_rc=$?
printf '%s\n' "$base_output"
case "$base_rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "base independent gate rc=$base_rc" ;; esac
printf '%s\n' "$base_output" | grep -F 'BASELINE_BUNDLE_SUCCESSOR3_EVIDENCE canonical_projection_red_green=true' >/dev/null 2>&1 || fail "focused canonical red/green evidence missing"
printf '%s\n' "PASS baseline-bundle-successor3-impl: canonical projection, SDK preflight and real bundle verified"
