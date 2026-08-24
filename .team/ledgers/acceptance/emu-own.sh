#!/bin/sh
# //! purpose: 机械验证 runner 实际启动、唯一绑定并安全收尾本次测量 qemu。
# //! contract: 0=所有权链与清理/恢复全绿；1=产品/测试契约未满足；2=量具不可判。
# //! boundary: 只运行 repo-local 假 launcher/ps/adb/gate，不启动真实模拟器或生产资源。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u

fail() {
    printf '%s\n' "FAIL emu-own: $*" >&2
    exit 1
}

unjudgeable() {
    printf '%s\n' "UNJUDGEABLE emu-own: $*" >&2
    exit 2
}

script_dir=$(CDPATH= cd "$(dirname "$0")" 2>/dev/null && pwd) \
    || unjudgeable "cannot resolve acceptance script directory"
repo_root=$(CDPATH= cd "$script_dir/../../.." 2>/dev/null && pwd) \
    || unjudgeable "cannot resolve repository root"
runner="$repo_root/tools/perfbase/run-input-ab.sh"
envcheck="$repo_root/tools/perfbase/envcheck.sh"
focused_test="$repo_root/tools/perfbase/test-run-input-ab-emulator-ownership.sh"
fixture_root="$repo_root/.team/nodes/emu-own/tmp"

[ -f "$focused_test" ] || fail "missing focused test: tools/perfbase/test-run-input-ab-emulator-ownership.sh"
[ -s "$focused_test" ] || fail "focused test is empty"
[ -f "$runner" ] && [ -f "$envcheck" ] || fail "required perfbase scripts are missing"
command -v sh >/dev/null 2>&1 || unjudgeable "POSIX sh is unavailable"
command -v bash >/dev/null 2>&1 || unjudgeable "bash is unavailable"
command -v grep >/dev/null 2>&1 || unjudgeable "grep is unavailable"

sh -n "$focused_test" >/dev/null 2>&1 || fail "focused test is not valid POSIX sh"
bash -n "$runner" >/dev/null 2>&1 || fail "run-input-ab.sh has invalid bash syntax"
sh -n "$envcheck" >/dev/null 2>&1 || fail "envcheck.sh has invalid sh syntax"

grep -F 'run-input-ab.sh' "$focused_test" >/dev/null 2>&1 \
    || fail "focused test does not execute run-input-ab.sh"
grep -F '/qemu-system-aarch64' "$focused_test" >/dev/null 2>&1 \
    || fail "focused test lacks full-path qemu comm prior-red arm"
grep -F 'emulator-5554' "$focused_test" >/dev/null 2>&1 \
    || fail "focused test lacks bound adb serial"
grep -F '27.43' "$focused_test" >/dev/null 2>&1 \
    || fail "focused test lacks dirty-load recovery arm"
grep -F '7.74' "$focused_test" >/dev/null 2>&1 \
    || fail "focused test lacks recovered-load positive control"

mkdir -p "$fixture_root" 2>/dev/null || unjudgeable "cannot create repo-local fixture root"
write_probe="$fixture_root/.acceptance-write-$$"
(umask 077 && : > "$write_probe") 2>/dev/null \
    || unjudgeable "repo-local fixture root is not writable"
rm -f "$write_probe" 2>/dev/null || unjudgeable "cannot clean fixture write probe"

test_output=$(EMU_OWN_FIXTURE_ROOT="$fixture_root" sh "$focused_test" 2>&1)
test_rc=$?
printf '%s\n' "$test_output"

case "$test_rc" in
    0) ;;
    1) fail "focused emulator ownership test failed" ;;
    2) unjudgeable "focused emulator ownership test could not judge" ;;
    *) unjudgeable "focused test returned unsupported exit $test_rc" ;;
esac

evidence='EMU_OWN_EVIDENCE fullpath_qemu_bound=true bound_pid_passed=true serial_passed=true order_preflight_launch_bind_adb_measurement=true zero_count_exit=2 zero_count_no_install=true zero_count_no_orphan=true zero_count_recovered=true no_adb_exit=2 no_adb_no_orphan=true measurement_reject_exit=2 success_cleanup=true failure_cleanup=true signal_cleanup=true only_owned_pid_killed=true'
printf '%s\n' "$test_output" | grep -F "$evidence" >/dev/null 2>&1 \
    || fail "missing exact ownership, cleanup, and recovery evidence"

printf '%s\n' "PASS emu-own: runner binds full-path qemu PID and cleans only this measurement emulator"
exit 0
