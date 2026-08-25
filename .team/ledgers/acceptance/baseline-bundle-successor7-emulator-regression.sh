#!/bin/sh
# //! purpose: 用仓内假 envcheck/AVD/runner/adb 验 successor7 有限时安装与 only-owned cleanup；另复跑 emu-own 真实 runner 所有权齿。
# //! contract: 0=阳性、preflight2、install timeout、证据伪造1/缺失2与 ambient 存活全符合；1=行为回归；2=夹具不可判。
# //! boundary: 不启动真实 adb/qemu/emulator，不访问生产 daemon/tmux。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor7-emulator-regression: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-emulator-regression: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
main_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve main root"
scratch="$main_root/.team/nodes/spec-sol/baseline-bundle-successor7/tmp/emulator-regression-$$"
fake_repo="$scratch/wt-maple-core"
fake_sdk="$fake_repo/fake-sdk"
events="$scratch/events.log"
state="$scratch/state"
fixture_root="$fake_repo/.team/nodes/spec-sol/baseline-bundle-successor7/tmp/case"
runner="$fake_repo/tools/perfbase/run-input-ab.sh"
wrapper="$script_dir/baseline-bundle-successor7-owned-emulator.sh"
helper="$script_dir/baseline-bundle-successor7-apparatus.py"

for tool in git python3 shasum chmod mkdir rm sleep date; do command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"; done
case "$scratch" in "$main_root/.team/nodes/spec-sol/baseline-bundle-successor7/tmp/"*) ;; *) unjudgeable "unsafe scratch" ;; esac
rm -rf "$scratch"
mkdir -p "$fake_repo/tools/perfbase" "$fake_repo/.team/nodes/baseline-bundle-impl" \
    "$fake_repo/.team/private/baseline-vault/fake" "$fake_sdk/emulator" "$fake_sdk/platform-tools" \
    "$fake_sdk/cmdline-tools/latest/bin" "$fixture_root" || unjudgeable "cannot create fake apparatus"
git -C "$fake_repo" init -q || unjudgeable "cannot initialize fake worktree"

cleanup() {
    if [ -n "${AMBIENT_PID:-}" ]; then kill "$AMBIENT_PID" 2>/dev/null || true; wait "$AMBIENT_PID" 2>/dev/null || true; fi
    if [ -f "$state" ]; then
        fake_qemu=$(sed -n 's/^qemu_pid=//p' "$state" | tail -1)
        [ -n "$fake_qemu" ] && kill "$fake_qemu" 2>/dev/null || true
    fi
    rm -rf "$scratch"
}
trap cleanup EXIT INT TERM HUP

printf '%s' fake-apk >"$fake_repo/.team/private/baseline-vault/fake/baseline.apk"
python3 - "$fake_repo" <<'PY'
import hashlib,json,pathlib,sys
r=pathlib.Path(sys.argv[1]); apk=r/".team/private/baseline-vault/fake/baseline.apk"
sha=hashlib.sha256(apk.read_bytes()).hexdigest()
m={"bundle_id":"a"*64,"artifact":{"apk_sha256":sha},"archive":{"primary_relpath":".team/private/baseline-vault/fake/baseline.apk"}}
(r/".team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json").write_text(json.dumps(m)+"\n",encoding="utf-8")
PY

envcheck="$scratch/envcheck.sh"
continuity="$scratch/continuity.sh"
cat >"$envcheck" <<'SH'
#!/bin/sh
printf '%s\n' "envcheck:${1:-}" >> "${S7_EVENTS:?}"
if [ "${S7_ENVCHECK_HANG:-0}" = 1 ]; then trap '' TERM; exec sleep 60; fi
case "${1:-}" in --gate) exit "${S7_PREFLIGHT_RC:-0}" ;; --measurement) exit 0 ;; *) exit 2 ;; esac
SH
cat >"$continuity" <<'SH'
#!/bin/sh
printf '%s\n' continuity >> "${S7_EVENTS:?}"
exit 0
SH
cat >"$fake_sdk/cmdline-tools/latest/bin/avdmanager" <<'SH'
#!/bin/sh
printf '%s\n' avdmanager >> "${S7_EVENTS:?}"
mkdir -p "${ANDROID_AVD_HOME:?}/successor7_verify_owned.avd"
exit 0
SH
cat >"$fake_sdk/emulator/emulator" <<'SH'
#!/bin/sh
exit 99
SH
cat >"$fake_sdk/platform-tools/adb" <<'SH'
#!/bin/sh
printf '%s\n' "adb:$*" >> "${S7_EVENTS:?}"
if [ "${S7_ADB_SLEEP:-0}" -gt 0 ]; then sleep "$S7_ADB_SLEEP"; fi
case "$*" in *" install -r "*) exit 0 ;; *" shell pm path dev.agentmirror.app") printf '%s\n' package:/data/app/base.apk; exit 0 ;; *" get-state") exit 1 ;; *) exit 1 ;; esac
SH
cat >"$runner" <<'SH'
#!/usr/bin/env bash
set -u
printf '%s\n' runner >> "${S7_EVENTS:?}"
sleep 60 & qemu=$!
printf 'qemu_pid=%s\n' "$qemu" >> "${S7_STATE:?}"
printf 'qemu_bound pid=%s comm=/fake/qemu-system-aarch64\n' "$qemu" >> "${RUNNER_EVENT_LOG:?}"
printf 'adb_bound serial=emulator-5554 state=device boot=1 rc=0\n' >> "$RUNNER_EVENT_LOG"
printf 'measurement_rc=0 pid=%s serial=emulator-5554\n' "$qemu" >> "$RUNNER_EVENT_LOG"
if [ "${S7_RUNNER_IGNORE_TERM:-0}" = 1 ]; then
    trap '' TERM
else
    trap 'kill "$qemu" 2>/dev/null || true; wait "$qemu" 2>/dev/null || true; printf "%s\n" runner_cleanup >> "${S7_EVENTS:?}"; exit 143' TERM INT HUP
fi
while :; do sleep 1; done
SH
chmod +x "$envcheck" "$continuity" "$fake_sdk/cmdline-tools/latest/bin/avdmanager" \
    "$fake_sdk/emulator/emulator" "$fake_sdk/platform-tools/adb" "$runner" || unjudgeable "cannot make fake tools executable"

sh "$script_dir/emu-own.sh" >/dev/null 2>&1
case "$?" in 0) ;; 1) fail "frozen emu-own runner regression failed" ;; 2) unjudgeable "frozen emu-own runner regression unavailable" ;; *) unjudgeable "emu-own unsupported rc" ;; esac

sleep 60 &
AMBIENT_PID=$!
: >"$events"; : >"$state"

(
    cd "$fake_repo" || exit 2
    SUCCESSOR7_FIXTURE_MODE=not-one sh "$wrapper"
) >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "nonempty invalid fixture selector was not rc1"
[ ! -s "$events" ] || fail "invalid fixture selector reached apparatus"
(
    cd "$fake_repo" || exit 2
    SUCCESSOR7_TEST_ARBITRARY=1 sh "$wrapper"
) >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "production test override was not rc1"
[ ! -s "$events" ] || fail "production test override reached apparatus"
(
    cd "$fake_repo" || exit 2
    env SUCCESSOR7_TEST_EMPTY= sh "$wrapper"
) >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "empty production test override was not rc1"
[ ! -s "$events" ] || fail "empty production test override reached apparatus"
(
    cd "$fake_repo" || exit 2
    SUCCESSOR7_FIXTURE_MODE=1 SUCCESSOR7_TEST_FIXTURE_ROOT="$fixture_root" sh "$wrapper"
) >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "test mode without isolated harness marker was not rc1"
(
    cd "$fake_repo" || exit 2
    SUCCESSOR7_FIXTURE_MODE=1 SUCCESSOR7_TEST_HARNESS=baseline-bundle-successor7-emulator-regression \
      SUCCESSOR7_TEST_FIXTURE_ROOT="$fixture_root" SUCCESSOR7_TEST_UNKNOWN=1 sh "$wrapper"
) >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "unknown isolated test override was not rc1"

derived_path="$fixture_root/derived.path"
python3 "$helper" resolve --repo-root "$fake_repo" --path-file "$derived_path" >/dev/null 2>&1 || fail "cannot create derived path handoff"
if ! python3 - "$derived_path" <<'PY'
import os,stat,sys
raise SystemExit(0 if stat.S_IMODE(os.lstat(sys.argv[1]).st_mode)==0o600 else 1)
PY
then
    fail "derived path handoff mode is not 0600"
fi
rm "$derived_path" || unjudgeable "cannot remove derived mode probe"

(
    cd "$fake_repo" || exit 2
    S7_EVENTS="$events" S7_STATE="$state" SUCCESSOR7_FIXTURE_MODE=1 \
      SUCCESSOR7_TEST_HARNESS=baseline-bundle-successor7-emulator-regression SUCCESSOR7_TEST_FIXTURE_ROOT="$fixture_root" \
      SUCCESSOR7_TEST_ENVCHECK="$envcheck" SUCCESSOR7_TEST_CONTINUITY="$continuity" \
      SUCCESSOR7_TEST_RUNNER="$runner" SUCCESSOR7_TEST_SDK_ROOT="$fake_sdk" \
      SUCCESSOR7_TEST_READY_TIMEOUT=5 SUCCESSOR7_TEST_ADB_TIMEOUT=3 SUCCESSOR7_TEST_RECOVERY_TIMEOUT=3 \
      SUCCESSOR7_TEST_TERM_GRACE=2 SUCCESSOR7_TEST_KILL_GRACE=2 \
      sh "$wrapper"
) >/dev/null 2>&1
positive_rc=$?
[ "$positive_rc" -eq 0 ] || fail "positive fixture rc=$positive_rc"
kill -0 "$AMBIENT_PID" 2>/dev/null || fail "ambient process was touched"
fake_qemu=$(sed -n 's/^qemu_pid=//p' "$state" | tail -1)
if [ -z "$fake_qemu" ] || kill -0 "$fake_qemu" 2>/dev/null; then
    fail "owned fake qemu survived"
fi
[ ! -d "$fixture_root/run" ] || fail "task-owned AVD directory survived"
first=$(sed -n '1p' "$events")
[ "$first" = envcheck:--gate ] || fail "strict envcheck was not first action"
for token in continuity avdmanager runner 'adb:-s emulator-5554 install -r ' runner_cleanup; do grep -F "$token" "$events" >/dev/null 2>&1 || fail "event missing $token"; done
evidence="$fixture_root/APPARATUS.json"
[ -s "$evidence" ] || fail "fixture evidence missing"
if ! python3 - "$evidence" <<'PY'
import os,stat,sys
raise SystemExit(0 if stat.S_IMODE(os.lstat(sys.argv[1]).st_mode)==0o600 else 1)
PY
then
    fail "apparatus evidence mode is not 0600"
fi
chmod 0644 "$evidence" || unjudgeable "cannot install 0644 evidence tooth"
python3 "$helper" verify --repo-root "$fake_repo" --evidence "$evidence" --mode fixture --max-age 300 >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "0644 evidence was not rc1"
chmod 0600 "$evidence" || unjudgeable "cannot restore evidence mode"

python3 - "$evidence" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p)); d["owned_qemu_cleanup"]=False
open(p,"w").write(json.dumps(d)+"\n")
PY
python3 "$helper" verify --repo-root "$fake_repo" --evidence "$evidence" --mode fixture --max-age 300 >/dev/null 2>&1
[ "$?" -eq 1 ] || fail "forged cleanup evidence was not rc1"
mv "$evidence" "$evidence.saved"
python3 "$helper" verify --repo-root "$fake_repo" --evidence "$evidence" --mode fixture --max-age 300 >/dev/null 2>&1
[ "$?" -eq 2 ] || fail "missing evidence was not rc2"
mv "$evidence.saved" "$evidence"

: >"$events"; : >"$state"
(
    cd "$fake_repo" || exit 2
    S7_EVENTS="$events" S7_STATE="$state" S7_PREFLIGHT_RC=2 SUCCESSOR7_FIXTURE_MODE=1 \
      SUCCESSOR7_TEST_HARNESS=baseline-bundle-successor7-emulator-regression SUCCESSOR7_TEST_FIXTURE_ROOT="$fixture_root" \
      SUCCESSOR7_TEST_ENVCHECK="$envcheck" SUCCESSOR7_TEST_CONTINUITY="$continuity" \
      SUCCESSOR7_TEST_RUNNER="$runner" SUCCESSOR7_TEST_SDK_ROOT="$fake_sdk" sh "$wrapper"
) >/dev/null 2>&1
[ "$?" -eq 2 ] || fail "dirty preflight was not rc2"
[ "$(sed -n '$=' "$events")" = 1 ] && [ "$(sed -n '1p' "$events")" = envcheck:--gate ] || fail "dirty preflight launched apparatus"

: >"$events"; : >"$state"
(
    cd "$fake_repo" || exit 2
    S7_EVENTS="$events" S7_STATE="$state" S7_ADB_SLEEP=3 SUCCESSOR7_FIXTURE_MODE=1 \
      SUCCESSOR7_TEST_HARNESS=baseline-bundle-successor7-emulator-regression SUCCESSOR7_TEST_FIXTURE_ROOT="$fixture_root" \
      SUCCESSOR7_TEST_ENVCHECK="$envcheck" SUCCESSOR7_TEST_CONTINUITY="$continuity" \
      SUCCESSOR7_TEST_RUNNER="$runner" SUCCESSOR7_TEST_SDK_ROOT="$fake_sdk" \
      SUCCESSOR7_TEST_READY_TIMEOUT=5 SUCCESSOR7_TEST_ADB_TIMEOUT=1 SUCCESSOR7_TEST_RECOVERY_TIMEOUT=3 \
      SUCCESSOR7_TEST_TERM_GRACE=1 SUCCESSOR7_TEST_KILL_GRACE=1 sh "$wrapper"
) >/dev/null 2>&1
[ "$?" -eq 2 ] || fail "adb timeout was not rc2"
timeout_qemu=$(sed -n 's/^qemu_pid=//p' "$state" | tail -1)
if [ -z "$timeout_qemu" ] || kill -0 "$timeout_qemu" 2>/dev/null; then
    fail "timeout left owned qemu"
fi
kill -0 "$AMBIENT_PID" 2>/dev/null || fail "timeout touched ambient process"

: >"$events"; : >"$state"
gate_started=$(date +%s)
(
    cd "$fake_repo" || exit 2
    S7_EVENTS="$events" S7_STATE="$state" S7_ENVCHECK_HANG=1 SUCCESSOR7_FIXTURE_MODE=1 \
      SUCCESSOR7_TEST_HARNESS=baseline-bundle-successor7-emulator-regression SUCCESSOR7_TEST_FIXTURE_ROOT="$fixture_root" \
      SUCCESSOR7_TEST_ENVCHECK="$envcheck" SUCCESSOR7_TEST_CONTINUITY="$continuity" \
      SUCCESSOR7_TEST_RUNNER="$runner" SUCCESSOR7_TEST_SDK_ROOT="$fake_sdk" \
      SUCCESSOR7_TEST_GATE_TIMEOUT=1 SUCCESSOR7_TEST_TERM_GRACE=1 SUCCESSOR7_TEST_KILL_GRACE=1 sh "$wrapper"
) >/dev/null 2>&1
gate_timeout_rc=$?
gate_elapsed=$(($(date +%s) - gate_started))
[ "$gate_timeout_rc" -eq 2 ] || fail "TERM-ignoring bounded command was not rc2"
[ "$gate_elapsed" -le 6 ] || fail "bounded command exceeded deadline elapsed=$gate_elapsed"
[ "$(sed -n '$=' "$events")" = 1 ] || fail "bounded preflight timeout launched later apparatus"

: >"$events"; : >"$state"
runner_started=$(date +%s)
(
    cd "$fake_repo" || exit 2
    S7_EVENTS="$events" S7_STATE="$state" S7_RUNNER_IGNORE_TERM=1 SUCCESSOR7_FIXTURE_MODE=1 \
      SUCCESSOR7_TEST_HARNESS=baseline-bundle-successor7-emulator-regression SUCCESSOR7_TEST_FIXTURE_ROOT="$fixture_root" \
      SUCCESSOR7_TEST_ENVCHECK="$envcheck" SUCCESSOR7_TEST_CONTINUITY="$continuity" \
      SUCCESSOR7_TEST_RUNNER="$runner" SUCCESSOR7_TEST_SDK_ROOT="$fake_sdk" \
      SUCCESSOR7_TEST_READY_TIMEOUT=5 SUCCESSOR7_TEST_ADB_TIMEOUT=1 SUCCESSOR7_TEST_RECOVERY_TIMEOUT=3 \
      SUCCESSOR7_TEST_TERM_GRACE=1 SUCCESSOR7_TEST_KILL_GRACE=1 sh "$wrapper"
) >/dev/null 2>&1
runner_timeout_rc=$?
runner_elapsed=$(($(date +%s) - runner_started))
[ "$runner_timeout_rc" -eq 2 ] || fail "TERM-ignoring runner was not rc2"
[ "$runner_elapsed" -le 15 ] || fail "runner cleanup exceeded deadline elapsed=$runner_elapsed"
forced_qemu=$(sed -n 's/^qemu_pid=//p' "$state" | tail -1)
if [ -z "$forced_qemu" ] || kill -0 "$forced_qemu" 2>/dev/null; then
    fail "forced cleanup left owned qemu"
fi
kill -0 "$AMBIENT_PID" 2>/dev/null || fail "forced cleanup touched ambient process"

printf '%s\n' "SUCCESSOR7_EMULATOR_EVIDENCE preflight_first=true fresh_task_avd=true owned_pid_serial=true install=true success_cleanup=true invalid_mode_exit=1 production_test_override_exit=1 production_empty_test_override_exit=1 unknown_test_override_exit=1 explicit_test_mode=true evidence_mode_0600=true derived_mode_0600=true evidence_0644_exit=1 dirty_preflight_exit=2 dirty_preflight_no_launch=true adb_timeout_exit=2 timeout_cleanup=true bounded_term_exit=2 bounded_term_seconds=$gate_elapsed forced_runner_exit=2 forced_runner_seconds=$runner_elapsed forced_runner_cleanup=true ambient_untouched=true foreign_qemu_touched=false forged_evidence_exit=1 missing_evidence_exit=2 emu_own_regression=true"
