#!/bin/sh
# //! purpose: 机械验证性能采样的严格 preflight 与 owned-emulator measurement 两阶段环境闸。
# //! contract: 0=聚焦测试八臂和三条清理路径全绿；1=产品/测试契约未满足；2=量具不可判。
# //! boundary: 只运行 repo-local 假命令夹具，不接触真实 tmux、qemu、adb 或生产 daemon。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u

fail() {
    printf '%s\n' "FAIL envcheck-emu: $*" >&2
    exit 1
}

unjudgeable() {
    printf '%s\n' "UNJUDGEABLE envcheck-emu: $*" >&2
    exit 2
}

script_dir=$(CDPATH= cd "$(dirname "$0")" 2>/dev/null && pwd) \
    || unjudgeable "cannot resolve acceptance script directory"
repo_root=$(CDPATH= cd "$script_dir/../../.." 2>/dev/null && pwd) \
    || unjudgeable "cannot resolve repository root"
envcheck="$repo_root/tools/perfbase/envcheck.sh"
runner="$repo_root/tools/perfbase/run-input-ab.sh"
focused_test="$repo_root/tools/perfbase/test-envcheck-measurement-emulator.sh"
fixture_root="$repo_root/.team/nodes/envcheck-emu/tmp"

[ -f "$focused_test" ] || fail "missing focused test: tools/perfbase/test-envcheck-measurement-emulator.sh"
[ -s "$focused_test" ] || fail "focused test is empty"
[ -f "$envcheck" ] && [ -f "$runner" ] || fail "required perfbase scripts are missing"
command -v sh >/dev/null 2>&1 || unjudgeable "POSIX sh is unavailable"
command -v bash >/dev/null 2>&1 || unjudgeable "bash is unavailable for run-input-ab.sh"
command -v grep >/dev/null 2>&1 || unjudgeable "grep is unavailable"

sh -n "$focused_test" >/dev/null 2>&1 || fail "focused test is not valid POSIX sh"
sh -n "$envcheck" >/dev/null 2>&1 || fail "envcheck.sh has invalid sh syntax"
bash -n "$runner" >/dev/null 2>&1 || fail "run-input-ab.sh has invalid bash syntax"

mkdir -p "$fixture_root" 2>/dev/null || unjudgeable "cannot create repo-local fixture root"
write_probe="$fixture_root/.acceptance-write-$$"
(umask 077 && : > "$write_probe") 2>/dev/null \
    || unjudgeable "repo-local fixture root is not writable"
rm -f "$write_probe" 2>/dev/null || unjudgeable "cannot clean fixture write probe"

test_output=$(ENVCHECK_EMU_FIXTURE_ROOT="$fixture_root" sh "$focused_test" 2>&1)
test_rc=$?
printf '%s\n' "$test_output"

case "$test_rc" in
    0) ;;
    1) fail "focused two-phase gate test failed" ;;
    2) unjudgeable "focused two-phase gate test could not judge" ;;
    *) unjudgeable "focused test returned unsupported exit $test_rc" ;;
esac

evidence='ENVCHECK_EMU_EVIDENCE preflight_clean=0 preflight_unrelated_qemu=2 owned_high_load=0 extra_qemu=2 dead_socket=2 daemon_cpu=2 no_adb=2 unowned_high_load=2 cleanup_success=true cleanup_failure=true cleanup_signal=true'
printf '%s\n' "$test_output" | grep -F "$evidence" >/dev/null 2>&1 \
    || fail "missing exact two-phase and cleanup evidence"

printf '%s\n' "PASS envcheck-emu: strict preflight and owned-emulator measurement gate are mechanically distinguished"
exit 0
