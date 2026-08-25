#!/bin/sh
# //! purpose: 用隔离 fake avdmanager 锁定 successor10 AVD 创建的无输入、有限时、零泄露、零启动四态。
# //! contract: 0=控制组和全部破坏齿符合；1=错误放行、错误分流、泄露或启动；2=回归量具不可判。
# //! boundary: 只写本格 node-local tmp；不调用 production selector、真实 avdmanager、adb、emulator 或 qemu。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
umask 077

fail() { printf '%s\n' "FAIL baseline-bundle-successor10-avd-regression: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor10-avd-regression: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
helper="$script_dir/baseline-bundle-successor10-avd.py"
wrapper="$script_dir/baseline-bundle-successor10-owned-emulator.sh"
scratch="$repo_root/.team/nodes/spec-sol/baseline-bundle-successor10/tmp/avd-regression-$$"
secret_marker=SUCCESSOR10_SECRET_PATH_MUST_NOT_LEAK

for tool in git python3 sh sed grep chmod mkdir rm cp stat date kill sleep; do
    command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"
done
[ -r "$helper" ] && [ -s "$helper" ] || unjudgeable "AVD helper unavailable"
[ -r "$wrapper" ] && [ -s "$wrapper" ] || unjudgeable "owned wrapper unavailable"
mkdir -p "$scratch" || unjudgeable "cannot create node-local scratch"
trap 'rm -rf "$scratch"' EXIT INT TERM HUP

make_sdk() {
    case_root=$1
    sdk_root=$case_root/sdk
    mkdir -p "$sdk_root/cmdline-tools/latest/bin" \
        "$sdk_root/system-images/android-35/google_apis/arm64-v8a" \
        "$sdk_root/platform-tools" "$sdk_root/emulator" || unjudgeable "cannot create fake SDK"
    printf '%s\n' '<localPackage path="system-images;android-35;google_apis;arm64-v8a" />' \
        >"$sdk_root/system-images/android-35/google_apis/arm64-v8a/package.xml"
    cat >"$sdk_root/cmdline-tools/latest/bin/avdmanager" <<'SH'
#!/bin/sh
set -u
mode=$(sed -n '1p' "$ANDROID_SDK_ROOT/.fake-mode" 2>/dev/null) || exit 90
python3 -c 'import os,stat,sys; sys.exit(0 if all(stat.S_ISREG(os.fstat(fd).st_mode) and stat.S_IMODE(os.fstat(fd).st_mode) == 0o600 for fd in (1,2)) else 82)' || exit 82
if [ "$#" -eq 3 ] && [ "$1" = list ] && [ "$2" = device ] && [ "$3" = -c ]; then
    if [ "$mode" = device_missing ]; then
        printf '%s\n' another_profile
    else
        printf '%s\n' pixel_6
    fi
    exit 0
fi
[ "$#" -eq 9 ] || exit 89
[ "$1" = create ] && [ "$2" = avd ] && [ "$3" = --force ] && [ "$4" = --name ] || exit 88
[ "$6" = --package ] && [ "$7" = 'system-images;android-35;google_apis;arm64-v8a' ] || exit 87
[ "$8" = --device ] && [ "$9" = pixel_6 ] || exit 86
if IFS= read -r unexpected_input; then
    printf '%s\n' 'interactive input was supplied' >&2
    exit 85
fi
name=$5
case "$mode" in
license)
    printf '%s\n' 'license not accepted' >&2
    exit 7
    ;;
input)
    printf '%s\n' 'interactive input required yes/no' >&2
    exit 6
    ;;
timeout)
    printf '%s\n' "$$" >"$ANDROID_SDK_ROOT/.timeout-shell-pid"
    sleep 20 &
    timeout_child=$!
    printf '%s\n' "$timeout_child" >"$ANDROID_SDK_ROOT/.timeout-child-pid"
    wait "$timeout_child"
    exit 0
    ;;
leak)
    printf '%s\n' "$ANDROID_SDK_ROOT/SUCCESSOR10_SECRET_PATH_MUST_NOT_LEAK" \
        "$ANDROID_AVD_HOME/SUCCESSOR10_SECRET_PATH_MUST_NOT_LEAK"
    printf '%s\n' "$ANDROID_SDK_ROOT/SUCCESSOR10_SECRET_PATH_MUST_NOT_LEAK" >&2
    ;;
success|config_device|config_package) ;;
*) exit 84 ;;
esac
mkdir -p "$ANDROID_AVD_HOME/$name.avd" || exit 83
device=pixel_6
image=system-images/android-35/google_apis/arm64-v8a
abi=arm64-v8a
tag=google_apis
[ "$mode" = config_device ] && device=pixel_5
[ "$mode" = config_package ] && image=system-images/android-34/google_apis/arm64-v8a
{
    printf 'hw.device.name=%s\n' "$device"
    printf 'image.sysdir.1=%s/\n' "$image"
    printf 'abi.type=%s\n' "$abi"
    printf 'tag.id=%s\n' "$tag"
} >"$ANDROID_AVD_HOME/$name.avd/config.ini"
exit 0
SH
    chmod 700 "$sdk_root/cmdline-tools/latest/bin/avdmanager" || unjudgeable "cannot make fake avdmanager executable"
    for executable in "$sdk_root/platform-tools/adb" "$sdk_root/emulator/emulator"; do
        cat >"$executable" <<'SH'
#!/bin/sh
: >"$ANDROID_SDK_ROOT/.FORBIDDEN_LAUNCH"
exit 91
SH
        chmod 700 "$executable" || unjudgeable "cannot make forbidden launcher executable"
    done
}

prepare_case() {
    case_name=$1
    fake_mode=$2
    case_root="$scratch/$case_name"
    mkdir -p "$case_root" || unjudgeable "cannot create case root"
    make_sdk "$case_root"
    printf '%s\n' "$fake_mode" >"$case_root/sdk/.fake-mode"
    mkdir "$case_root/avd-home" || unjudgeable "cannot create AVD home"
    chmod 700 "$case_root/avd-home" || unjudgeable "cannot enforce AVD home mode"
    evidence="$case_root/evidence.json"
    name_file="$case_root/avd.name"
    raw_dir="$case_root/raw"
    test_name="successor10_${case_name}_abcdefgh"
}

assert_no_leak_or_launch() {
    case_root=$1
    output=$2
    for forbidden in "$scratch" "$secret_marker" 'sdk.dir=' 'ANDROID_SDK_ROOT=' 'ANDROID_AVD_HOME='; do
        grep -F "$forbidden" "$output" >/dev/null 2>&1 && fail "protected path or value leaked"
    done
    [ ! -e "$case_root/sdk/.FORBIDDEN_LAUNCH" ] || fail "adb/emulator was launched"
    [ ! -e "$case_root/RUNNER_LAUNCHED" ] || fail "ownership runner was launched"
}

run_helper_case() {
    case_name=$1
    fake_mode=$2
    expected_rc=$3
    expected_reason=$4
    prepare_case "$case_name" "$fake_mode"
    case "$case_name" in
    wrong_mode) chmod 755 "$case_root/avd-home" || unjudgeable "cannot prepare mode tooth" ;;
    already_exists) mkdir "$case_root/avd-home/$test_name.avd" || unjudgeable "cannot prepare existing-name tooth" ;;
    package_unavailable) rm "$case_root/sdk/system-images/android-35/google_apis/arm64-v8a/package.xml" ;;
    esac
    started=$(date +%s)
    python3 "$helper" create --repo-root "$repo_root" --sdk-root "$case_root/sdk" \
        --avdmanager "$case_root/sdk/cmdline-tools/latest/bin/avdmanager" \
        --avd-home "$case_root/avd-home" --evidence "$evidence" --name-file "$name_file" \
        --raw-dir "$raw_dir" --timeout 1 --mode fixture --test-name "$test_name" \
        >"$case_root/output" 2>&1
    actual_rc=$?
    elapsed=$(($(date +%s) - started))
    [ "$actual_rc" -eq "$expected_rc" ] || fail "$case_name exit mismatch"
    grep -F "\"reason_code\":\"$expected_reason\"" "$evidence" >/dev/null 2>&1 || fail "$case_name reason mismatch"
    python3 "$helper" verify --evidence "$evidence" --mode fixture >"$case_root/verify" 2>&1 || fail "$case_name evidence rejected"
    [ ! -e "$raw_dir" ] || fail "$case_name raw capture was not removed"
    [ ! -e "$case_root/avd-task-home" ] || fail "$case_name task home was not removed"
    assert_no_leak_or_launch "$case_root" "$case_root/output"
    if [ "$case_name" = timeout ]; then
        [ "$elapsed" -le 8 ] || fail "timeout exceeded finite upper bound"
        for pid_file in "$case_root/sdk/.timeout-shell-pid" "$case_root/sdk/.timeout-child-pid"; do
            timeout_pid=$(sed -n '1p' "$pid_file" 2>/dev/null)
            case "$timeout_pid" in ''|*[!0-9]*) fail "timeout PID tooth missing" ;; esac
            settle=0
            while kill -0 "$timeout_pid" 2>/dev/null && [ "$settle" -lt 3 ]; do
                sleep 1
                settle=$((settle + 1))
            done
            kill -0 "$timeout_pid" 2>/dev/null && fail "timeout process survived owned process-group cleanup"
        done
    fi
}

run_helper_case success success 0 created
success_evidence=$evidence
[ -r "$name_file" ] && [ -s "$name_file" ] || fail "success did not persist generated name"
[ "$(stat -f '%Lp' "$name_file" 2>/dev/null)" = 600 ] || fail "name file mode is not 0600"
[ "$(stat -f '%Lp' "$success_evidence" 2>/dev/null)" = 600 ] || fail "evidence mode is not 0600"
run_helper_case wrong_mode success 1 avd_home_mode_invalid
run_helper_case already_exists success 1 avd_name_exists
run_helper_case license license 2 license_unavailable
run_helper_case input input 2 interactive_input_required
run_helper_case device_missing device_missing 2 device_profile_unavailable
run_helper_case device_mismatch config_device 1 device_profile_mismatch
run_helper_case package_unavailable success 2 package_unavailable
run_helper_case package_mismatch config_package 1 package_mismatch
run_helper_case timeout timeout 2 timeout
run_helper_case no_leak leak 0 created

# Evidence validator teeth: unsafe mode, unknown fields, digest tamper and
# missing evidence must not be accepted as a valid created record.
mutation="$scratch/mutation"
mkdir "$mutation" || unjudgeable "cannot create mutation root"
cp "$success_evidence" "$mutation/mode.json"
chmod 644 "$mutation/mode.json"
python3 "$helper" verify --evidence "$mutation/mode.json" --mode fixture >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "0644 evidence was accepted"
cp "$success_evidence" "$mutation/unknown.json"
python3 - "$mutation/unknown.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); data=json.loads(p.read_text()); data["unknown"]=True
p.write_text(json.dumps(data,separators=(",",":"))+"\n"); p.chmod(0o600)
PY
python3 "$helper" verify --evidence "$mutation/unknown.json" --mode fixture >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "unknown evidence field was accepted"
cp "$success_evidence" "$mutation/digest.json"
python3 - "$mutation/digest.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); data=json.loads(p.read_text()); data["stderr_sha256"]="bad"
p.write_text(json.dumps(data,separators=(",",":"))+"\n"); p.chmod(0o600)
PY
python3 "$helper" verify --evidence "$mutation/digest.json" --mode fixture >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "invalid digest was accepted"
python3 "$helper" verify --evidence "$mutation/missing.json" --mode fixture >/dev/null 2>&1
[ "$?" -eq 2 ] || fail "missing evidence was not unjudgeable"

make_wrapper_inputs() {
    case_root=$1
    cat >"$case_root/envcheck.sh" <<'SH'
#!/bin/sh
exit 0
SH
    cat >"$case_root/continuity.sh" <<'SH'
#!/bin/sh
exit 0
SH
    cat >"$case_root/runner.sh" <<'SH'
#!/bin/sh
: >"$(dirname "$0")/RUNNER_LAUNCHED"
exit 92
SH
    chmod 700 "$case_root/envcheck.sh" "$case_root/continuity.sh" "$case_root/runner.sh" || unjudgeable "cannot prepare wrapper fixtures"
}

run_wrapper_failure() {
    case_name=$1
    fake_mode=$2
    expected_rc=$3
    prepare_case "wrapper_$case_name" "$fake_mode"
    rm -rf "$case_root/avd-home"
    make_wrapper_inputs "$case_root"
    started=$(date +%s)
    env SUCCESSOR10_FIXTURE_MODE=1 \
        SUCCESSOR10_TEST_HARNESS=baseline-bundle-successor10-emulator-regression \
        SUCCESSOR10_TEST_FIXTURE_ROOT="$case_root" \
        SUCCESSOR10_TEST_ENVCHECK="$case_root/envcheck.sh" \
        SUCCESSOR10_TEST_RUNNER="$case_root/runner.sh" \
        SUCCESSOR10_TEST_CONTINUITY="$case_root/continuity.sh" \
        SUCCESSOR10_TEST_SDK_ROOT="$case_root/sdk" \
        SUCCESSOR10_TEST_GATE_TIMEOUT=2 SUCCESSOR10_TEST_AVD_TIMEOUT=1 \
        SUCCESSOR10_TEST_READY_TIMEOUT=2 SUCCESSOR10_TEST_ADB_TIMEOUT=2 \
        SUCCESSOR10_TEST_RECOVERY_TIMEOUT=2 SUCCESSOR10_TEST_TERM_GRACE=1 \
        SUCCESSOR10_TEST_KILL_GRACE=1 sh "$wrapper" >"$case_root/wrapper.output" 2>&1
    actual_rc=$?
    elapsed=$(($(date +%s) - started))
    [ "$actual_rc" -eq "$expected_rc" ] || fail "wrapper $case_name exit mismatch"
    assert_no_leak_or_launch "$case_root" "$case_root/wrapper.output"
    [ "$elapsed" -le 12 ] || fail "wrapper $case_name exceeded finite upper bound"
}

run_wrapper_failure license license 2
run_wrapper_failure input input 2
run_wrapper_failure device device_missing 2
run_wrapper_failure package config_package 1
run_wrapper_failure timeout timeout 2

# Production/test isolation and ordering are part of the permanent apparatus
# contract.  The fake teeth above never cross this line into a real tool.
# The next pattern is intentionally literal source code.
# shellcheck disable=SC2016
grep -F 'case "$fixture_selector" in' "$wrapper" >/dev/null 2>&1 || fail "fixture selector is not fail-closed"
grep -F 'test override present in production' "$wrapper" >/dev/null 2>&1 || fail "production test-variable rejection missing"
# The next pattern is intentionally literal source code.
# shellcheck disable=SC2016
helper_line=$(grep -n 'python3 "$avd_helper" create' "$wrapper" | sed -n '1s/:.*//p')
launch_line=$(grep -n 'EMULATOR_LAUNCHER=' "$wrapper" | sed -n '1s/:.*//p')
case "$helper_line:$launch_line" in *[!0-9:]*|:*) fail "cannot prove helper-before-launch ordering" ;; esac
[ "$helper_line" -lt "$launch_line" ] || fail "emulator launch is not gated by AVD creation"
grep -F 'printf no' "$wrapper" >/dev/null 2>&1 && fail "interactive avdmanager pipe returned"

printf '%s\n' "PASS baseline-bundle-successor10-avd-regression: success mode existing license input device package timeout no-leak zero-launch evidence-teeth"
