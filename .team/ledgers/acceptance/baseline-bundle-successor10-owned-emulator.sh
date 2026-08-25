#!/bin/sh
# //! purpose: strict envcheck+successor9唯一SDK后，以安全AVD helper无输入创建fresh AVD，再复用已验ownership runner安装并只清owned设备。
# //! contract: 0=preflight/measurement/install/owned cleanup/recovery 全绿；1=固定身份或证据矛盾；2=环境、设备、超时或清理不可判。
# //! boundary: production无可替换root/runner/package/device；AVD原始日志只在node-local 0600后清理，fixture override仅限successor10 node-local temp。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
umask 077

fail() { printf '%s\n' "FAIL baseline-bundle-successor10-owned-emulator: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor10-owned-emulator: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve worktree root"
helper="$script_dir/baseline-bundle-successor7-apparatus.py"
avd_helper="$script_dir/baseline-bundle-successor10-avd.py"
continuity="$script_dir/baseline-bundle-successor7-continuity.sh"
for bootstrap_tool in env awk sed grep; do command -v "$bootstrap_tool" >/dev/null 2>&1 || unjudgeable "$bootstrap_tool unavailable"; done
test_override_count=$(env | awk -F= '/^SUCCESSOR10_TEST_[A-Za-z0-9_]*=/{count++} END{print count+0}')
fixture_selector=${SUCCESSOR10_FIXTURE_MODE:-}
case "$fixture_selector" in
'' ) mode=production ;;
1 ) mode=fixture ;;
* ) fail "invalid fixture mode selector" ;;
esac
if [ "$mode" = fixture ]; then
    [ "${SUCCESSOR10_TEST_HARNESS:-}" = baseline-bundle-successor10-emulator-regression ] || fail "explicit isolated test harness marker missing"
    test_override_names=$(env | sed -n 's/^\(SUCCESSOR10_TEST_[A-Za-z0-9_]*\)=.*/\1/p')
    for test_name in $test_override_names; do
        case "$test_name" in
        SUCCESSOR10_TEST_HARNESS|SUCCESSOR10_TEST_FIXTURE_ROOT|SUCCESSOR10_TEST_ENVCHECK|SUCCESSOR10_TEST_RUNNER|SUCCESSOR10_TEST_CONTINUITY|SUCCESSOR10_TEST_SDK_ROOT|SUCCESSOR10_TEST_GATE_TIMEOUT|SUCCESSOR10_TEST_AVD_TIMEOUT|SUCCESSOR10_TEST_READY_TIMEOUT|SUCCESSOR10_TEST_ADB_TIMEOUT|SUCCESSOR10_TEST_RECOVERY_TIMEOUT|SUCCESSOR10_TEST_TERM_GRACE|SUCCESSOR10_TEST_KILL_GRACE) ;;
        *) fail "unknown test override in isolated mode" ;;
        esac
    done
    fixture_root=${SUCCESSOR10_TEST_FIXTURE_ROOT:-}
    [ -n "$fixture_root" ] || unjudgeable "fixture root missing"
    case "$fixture_root" in "$repo_root/.team/nodes/spec-sol/baseline-bundle-successor10/tmp/"*) ;; *) fail "fixture root escapes node-local temp" ;; esac
    envcheck=${SUCCESSOR10_TEST_ENVCHECK:-}
    runner=${SUCCESSOR10_TEST_RUNNER:-}
    continuity=${SUCCESSOR10_TEST_CONTINUITY:-}
    sdk_root=${SUCCESSOR10_TEST_SDK_ROOT:-}
    evidence="$fixture_root/APPARATUS.json"
    run_root="$fixture_root/run"
    gate_timeout=${SUCCESSOR10_TEST_GATE_TIMEOUT:-3}
    avd_timeout=${SUCCESSOR10_TEST_AVD_TIMEOUT:-5}
    ready_timeout=${SUCCESSOR10_TEST_READY_TIMEOUT:-5}
    adb_timeout=${SUCCESSOR10_TEST_ADB_TIMEOUT:-3}
    recovery_timeout=${SUCCESSOR10_TEST_RECOVERY_TIMEOUT:-3}
    term_grace=${SUCCESSOR10_TEST_TERM_GRACE:-2}
    kill_grace=${SUCCESSOR10_TEST_KILL_GRACE:-2}
else
    [ "$test_override_count" -eq 0 ] || fail "test override present in production count=$test_override_count"
    [ "$(basename "$repo_root")" = wt-maple-core ] || unjudgeable "production apparatus must run in retained wt-maple-core"
    envcheck="$repo_root/tools/perfbase/envcheck.sh"
    runner="$repo_root/tools/perfbase/run-input-ab.sh"
    evidence="$repo_root/.team/nodes/baseline-bundle-apparatus/APPARATUS.json"
    run_root="$repo_root/.team/nodes/baseline-bundle-apparatus/tmp/run-$$"
    sdk_root=
    gate_timeout=30
    avd_timeout=60
    ready_timeout=120
    adb_timeout=60
    recovery_timeout=30
    term_grace=10
    kill_grace=5
fi

for limit in "$gate_timeout" "$avd_timeout" "$ready_timeout" "$adb_timeout" "$recovery_timeout" "$term_grace" "$kill_grace"; do
    case "$limit" in ''|*[!0-9]*|0) fail "timeout must be a positive integer" ;; esac
    [ "$limit" -le 300 ] || fail "timeout exceeds fixed upper bound"
done

for tool in git python3 sh bash awk sed grep kill date ps; do command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"; done
for item in "$helper" "$avd_helper" "$continuity" "$envcheck" "$runner"; do [ -r "$item" ] && [ -s "$item" ] || unjudgeable "required apparatus input unavailable"; done

wait_pid_gone() {
    watched_pid=$1
    wait_limit=$2
    waited=0
    while kill -0 "$watched_pid" 2>/dev/null; do
        [ "$waited" -lt "$wait_limit" ] || return 1
        sleep 1
        waited=$((waited + 1))
    done
    return 0
}

run_bounded() {
    bound=$1
    output=$2
    shift 2
    "$@" >"$output" 2>&1 &
    bounded_pid=$!
    elapsed=0
    while kill -0 "$bounded_pid" 2>/dev/null; do
        if [ "$elapsed" -ge "$bound" ]; then
            kill -TERM "$bounded_pid" 2>/dev/null || true
            if ! wait_pid_gone "$bounded_pid" "$term_grace"; then
                kill -KILL "$bounded_pid" 2>/dev/null || true
                wait_pid_gone "$bounded_pid" "$kill_grace" || return 125
            fi
            wait "$bounded_pid" 2>/dev/null || true
            return 124
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    wait "$bounded_pid"
}

# The strict host gate is the first external action.  Nothing before it creates
# an AVD, starts adb, launches qemu, reads an APK, or mutates the retained WT.
run_bounded "$gate_timeout" /dev/null sh "$envcheck" --gate
preflight_rc=$?
[ "$preflight_rc" -eq 0 ] || unjudgeable "strict envcheck preflight rc=$preflight_rc"

mkdir -p "$run_root" || unjudgeable "cannot create task-local run directory"
event_log="$run_root/runner-events.log"
runner_log="$run_root/runner.log"
apk_path_file="$run_root/apk.path"
avd_home="$run_root/avd-home"
avd_name_file="$run_root/avd.name"
avd_raw="$run_root/avd-raw"
avd_safe_log="$run_root/avd-create.safe"
if [ "$mode" = production ]; then
    avd_evidence="$repo_root/.team/nodes/baseline-bundle-apparatus/AVD-CREATE.json"
else
    avd_evidence="$fixture_root/AVD-CREATE.json"
fi
install_log="$run_root/install.log"
pm_log="$run_root/pm.log"
runner_pid=
owned_qemu_pid=
owned_qemu_start=
bound_serial=

cleanup_tmp() {
    case "$run_root" in
        "$repo_root/.team/nodes/baseline-bundle-apparatus/tmp/"*|"$repo_root/.team/nodes/spec-sol/baseline-bundle-successor10/tmp/"*) rm -rf "$run_root" ;;
        *) return 2 ;;
    esac
}

stop_runner() {
    stop_rc=0
    stop_forced=0
    if [ -n "$runner_pid" ] && kill -0 "$runner_pid" 2>/dev/null; then
        kill -TERM "$runner_pid" 2>/dev/null || return 2
        if ! wait_pid_gone "$runner_pid" "$term_grace"; then
            stop_forced=1
            kill -KILL "$runner_pid" 2>/dev/null || true
            wait_pid_gone "$runner_pid" "$kill_grace" || return 2
        fi
    fi
    if [ -n "$runner_pid" ]; then
        wait "$runner_pid" 2>/dev/null
        stop_rc=$?
        runner_pid=
    fi
    if [ -n "$owned_qemu_pid" ] && kill -0 "$owned_qemu_pid" 2>/dev/null; then
        qemu_start_now=$(ps -p "$owned_qemu_pid" -o lstart= 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        [ -n "$owned_qemu_start" ] && [ "$qemu_start_now" = "$owned_qemu_start" ] || return 2
        stop_forced=1
        kill -TERM "$owned_qemu_pid" 2>/dev/null || true
        if ! wait_pid_gone "$owned_qemu_pid" "$term_grace"; then
            kill -KILL "$owned_qemu_pid" 2>/dev/null || true
            wait_pid_gone "$owned_qemu_pid" "$kill_grace" || return 2
        fi
    fi
    [ -z "$owned_qemu_pid" ] || ! kill -0 "$owned_qemu_pid" 2>/dev/null || return 2
    if [ -n "$bound_serial" ] && [ -n "${adb:-}" ] && [ -x "$adb" ]; then
        serial_deadline=$(($(date +%s) + recovery_timeout))
        serial_log="$run_root/serial-cleanup.log"
        while :; do
            serial_remaining=$((serial_deadline - $(date +%s)))
            [ "$serial_remaining" -gt 0 ] || return 2
            serial_probe_timeout=$adb_timeout
            [ "$serial_probe_timeout" -le "$serial_remaining" ] || serial_probe_timeout=$serial_remaining
            run_bounded "$serial_probe_timeout" "$serial_log" "$adb" -s "$bound_serial" get-state
            serial_rc=$?
            if [ "$serial_rc" -ne 0 ] || ! grep -Fqx device "$serial_log" 2>/dev/null; then
                break
            fi
            sleep 1
        done
    fi
    [ "$stop_rc" -eq 143 ] || stop_forced=1
    [ "$stop_forced" -eq 0 ] || return 2
    return 0
}

recover_host() {
    recover_deadline=$(($(date +%s) + recovery_timeout))
    while :; do
        recover_remaining=$((recover_deadline - $(date +%s)))
        [ "$recover_remaining" -gt 0 ] || return 2
        recover_probe_timeout=$gate_timeout
        [ "$recover_probe_timeout" -le "$recover_remaining" ] || recover_probe_timeout=$recover_remaining
        run_bounded "$recover_probe_timeout" /dev/null sh "$envcheck" --gate
        recovery_rc=$?
        [ "$recovery_rc" -eq 0 ] && return 0
        sleep 1
    done
}

abort_run() {
    reason=$1
    stop_runner >/dev/null 2>&1 || true
    recover_host >/dev/null 2>&1 || true
    cleanup_tmp >/dev/null 2>&1 || true
    unjudgeable "$reason"
}

fail_run() {
    reason=$1
    stop_runner >/dev/null 2>&1 || true
    recover_host >/dev/null 2>&1 || true
    cleanup_tmp >/dev/null 2>&1 || true
    fail "$reason"
}

signal_run() {
    trap - EXIT INT TERM HUP
    stop_runner >/dev/null 2>&1 || true
    recover_host >/dev/null 2>&1 || true
    cleanup_tmp >/dev/null 2>&1 || true
    exit 143
}
trap signal_run INT TERM HUP

run_bounded 30 /dev/null sh "$continuity"
continuity_rc=$?
[ "$continuity_rc" -eq 0 ] || abort_run "retained four-task/bundle continuity rc=$continuity_rc"

if [ "$mode" = production ]; then
    sdk_gate="$script_dir/baseline-bundle-successor9-sdk-selector.sh"
    [ -r "$sdk_gate" ] && [ -s "$sdk_gate" ] || abort_run "successor9 SDK selector unavailable"
    run_bounded 60 /dev/null sh "$sdk_gate"
    sdk_gate_rc=$?
    case "$sdk_gate_rc" in
        0) ;;
        1) fail_run "successor9 SDK selector refuted target policy" ;;
        2) abort_run "successor9 SDK selector unavailable" ;;
        *) abort_run "successor9 SDK selector unsupported rc" ;;
    esac
    if ! python3 - "$repo_root/app/local.properties" "$run_root/sdk.path" <<'PY'
import pathlib,sys
src,out=map(pathlib.Path,sys.argv[1:])
try: lines=src.read_text(encoding="utf-8").splitlines()
except (OSError,UnicodeError): raise SystemExit(2)
if len(lines)!=1 or not lines[0].startswith("sdk.dir=") or not lines[0][8:]: raise SystemExit(2)
sdk=pathlib.Path(lines[0][8:]).resolve()
if not sdk.is_dir(): raise SystemExit(2)
out.write_text(str(sdk)+"\n",encoding="utf-8")
out.chmod(0o600)
PY
    then
        abort_run "cannot resolve validated SDK"
    fi
    sdk_root=$(sed -n '1p' "$run_root/sdk.path" 2>/dev/null)
fi

[ -n "$sdk_root" ] && [ -d "$sdk_root" ] || abort_run "SDK root unavailable"
emulator="$sdk_root/emulator/emulator"
adb="$sdk_root/platform-tools/adb"
avdmanager="$sdk_root/cmdline-tools/latest/bin/avdmanager"
for item in "$emulator" "$adb" "$avdmanager"; do [ -x "$item" ] || abort_run "required Android tool unavailable"; done
if [ "$mode" = production ]; then
    [ -r "$sdk_root/system-images/android-35/google_apis/arm64-v8a/package.xml" ] || abort_run "fixed system image unavailable"
fi

(umask 077; mkdir "$avd_home") || fail_run "task-owned AVD home already exists or is unavailable"
chmod 0700 "$avd_home" || fail_run "cannot enforce task-owned AVD home mode"
python3 "$avd_helper" create --repo-root "$repo_root" --sdk-root "$sdk_root" --avdmanager "$avdmanager" \
    --avd-home "$avd_home" --evidence "$avd_evidence" --name-file "$avd_name_file" --raw-dir "$avd_raw" \
    --timeout "$avd_timeout" --mode "$mode" >"$avd_safe_log" 2>&1
avd_rc=$?
[ -r "$avd_safe_log" ] && [ -s "$avd_safe_log" ] && sed -n '1p' "$avd_safe_log" >&2
case "$avd_rc" in
    0) ;;
    1) fail_run "fresh AVD creation contract refuted" ;;
    2) abort_run "fresh AVD creation unavailable" ;;
    *) abort_run "fresh AVD helper unsupported rc" ;;
esac
python3 "$avd_helper" verify --evidence "$avd_evidence" --mode "$mode" >/dev/null 2>&1 || abort_run "AVD create evidence invalid"
avd_name=$(sed -n '1p' "$avd_name_file" 2>/dev/null)
case "$avd_name" in successor10_[a-z0-9_]*) ;; *) fail_run "fresh AVD name identity invalid" ;; esac
[ -d "$avd_home/$avd_name.avd" ] && [ ! -L "$avd_home/$avd_name.avd" ] || abort_run "fresh AVD directory missing"

if ! python3 "$helper" resolve --repo-root "$repo_root" --path-file "$apk_path_file" >/dev/null 2>&1; then
    abort_run "retained bundle APK unavailable"
fi
apk_path=$(sed -n '1p' "$apk_path_file" 2>/dev/null)
[ -n "$apk_path" ] && [ -f "$apk_path" ] || abort_run "resolved APK unavailable"

: >"$event_log"
env ANDROID_AVD_HOME="$avd_home" RUNNER_EVENT_LOG="$event_log" ENV_CHECK="$envcheck" ADB="$adb" \
    EMULATOR_LAUNCHER="$emulator" EMULATOR_AVD="$avd_name" EMULATOR_SERIAL=emulator-5554 \
    EMULATOR_READY_TIMEOUT="$ready_timeout" RUNNER_EMULATOR_ONLY=1 RUNNER_EMULATOR_TEST_WAIT=1 \
    bash "$runner" --emulator-self-test >"$runner_log" 2>&1 &
runner_pid=$!

ready_elapsed=0
ready_limit=$ready_timeout
while [ "$ready_elapsed" -lt "$ready_limit" ]; do
    if grep -F 'measurement_rc=0 ' "$event_log" >/dev/null 2>&1; then break; fi
    kill -0 "$runner_pid" 2>/dev/null || abort_run "ownership runner exited before measurement readiness"
    sleep 1
    ready_elapsed=$((ready_elapsed + 1))
done
grep -F 'measurement_rc=0 ' "$event_log" >/dev/null 2>&1 || abort_run "owned emulator readiness timeout"
owned_qemu_pid=$(sed -n 's/.*qemu_bound pid=\([0-9][0-9]*\).*/\1/p' "$event_log" | tail -1)
bound_serial=$(sed -n 's/.*adb_bound serial=\([^ ]*\).*/\1/p' "$event_log" | tail -1)
case "$owned_qemu_pid" in ''|*[!0-9]*) abort_run "owned qemu PID evidence missing" ;; esac
[ "$bound_serial" = emulator-5554 ] || abort_run "bound serial mismatch"
owned_qemu_start=$(ps -p "$owned_qemu_pid" -o lstart= 2>/dev/null | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
[ -n "$owned_qemu_start" ] || abort_run "owned qemu start identity unavailable"

run_bounded "$adb_timeout" "$install_log" "$adb" -s "$bound_serial" install -r "$apk_path"
install_rc=$?
[ "$install_rc" -eq 0 ] || abort_run "adb install rc=$install_rc"
run_bounded "$adb_timeout" "$pm_log" "$adb" -s "$bound_serial" shell pm path dev.agentmirror.app
pm_rc=$?
if [ "$pm_rc" -ne 0 ] || ! grep -F 'package:' "$pm_log" >/dev/null 2>&1; then
    abort_run "installed package identity unavailable"
fi

stop_runner || abort_run "owned runner/qemu cleanup failed"
recover_host || abort_run "host did not recover to strict envcheck gate"
recovery_rc=0

if ! python3 "$helper" write --repo-root "$repo_root" --evidence "$evidence" --mode "$mode" \
    --qemu-pid "$owned_qemu_pid" --serial "$bound_serial" --preflight-exit 0 --measurement-exit 0 \
    --install-exit 0 --runner-exit 143 --recovery-exit "$recovery_rc" --fresh-avd --owned-qemu --cleanup >/dev/null 2>&1; then
    abort_run "cannot write apparatus evidence"
fi
python3 "$helper" verify --repo-root "$repo_root" --evidence "$evidence" --mode "$mode" --max-age 300
verify_rc=$?
[ "$verify_rc" -eq 0 ] || abort_run "apparatus evidence verification rc=$verify_rc"

trap - INT TERM HUP
cleanup_tmp || unjudgeable "cannot remove task-owned AVD directory"
printf '%s\n' "PASS baseline-bundle-successor10-owned-emulator: fresh owned emulator installed retained bundle and was fully reaped"
