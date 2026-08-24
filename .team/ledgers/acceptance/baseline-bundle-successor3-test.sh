#!/bin/sh
# //! purpose: 便宜核对 successor3 测试席已交付三项返修与 unjudgeable 拒绝设计；不冒充事实执行。
# //! contract: 0=RED.md 结构完整；1=交付失败；2=目录/工具不可读。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor3-test: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor3-test: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
f="$repo_root/.team/nodes/baseline-bundle-test/RED.md"
[ -e "$f" ] || fail "missing RED.md"
[ -r "$f" ] || unjudgeable "RED.md unreadable"
[ -s "$f" ] || fail "RED.md empty"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
for token in SUCCESSOR3_CANONICAL_PROJECTION_RED_GREEN SUCCESSOR3_SDK_PREFLIGHT_NO_VALUE_DISCLOSURE SUCCESSOR3_BYPASS_FIXED_PROVENANCE SUCCESSOR3_IMPL_UNJUDGEABLE_REJECTED 'missing_local_properties=2' 'missing_fixture=2' 'forged_fixture=1' 'unjudgeable_report=1' '--rerun-tasks' '--no-build-cache' '-count=1'; do
    grep -F -e "$token" "$f" >/dev/null 2>&1 || fail "RED.md missing $token"
done
printf '%s\n' "PASS baseline-bundle-successor3-test: independent red/green matrix present"
