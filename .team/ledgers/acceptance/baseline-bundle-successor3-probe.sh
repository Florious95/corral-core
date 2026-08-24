#!/bin/sh
# //! purpose: 便宜核对 successor3 审查席已交付 canonical、SDK 与固定夹具的独立设计操作数；不冒充事实执行。
# //! contract: 0=PROBE.md 结构完整；1=交付失败；2=目录/工具不可读。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor3-probe: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor3-probe: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
f="$repo_root/.team/nodes/baseline-bundle-probe/PROBE.md"
[ -e "$f" ] || fail "missing PROBE.md"
[ -r "$f" ] || unjudgeable "PROBE.md unreadable"
[ -s "$f" ] || fail "PROBE.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for token in SUCCESSOR3_CANONICAL_PROJECTION_RED_GREEN SUCCESSOR3_SDK_PREFLIGHT_NO_VALUE_DISCLOSURE SUCCESSOR3_BYPASS_FIXED_PROVENANCE SUCCESSOR3_IMPL_UNJUDGEABLE_REJECTED bundle_id independent_builds apk_relpath app/local.properties apksigner aapt control-contract.json 'missing_fixture=2' 'forged_fixture=1'; do
    grep -F "$token" "$f" >/dev/null 2>&1 || fail "PROBE.md missing $token"
done
printf '%s\n' "PASS baseline-bundle-successor3-probe: independent operands and mutation teeth present"
