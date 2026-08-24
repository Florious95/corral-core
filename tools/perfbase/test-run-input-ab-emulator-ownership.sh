#!/bin/sh
# Repo-local ownership test: no real qemu, adb, tmux, APK, or daemon.
set -u
BASE=${EMU_OWN_FIXTURE_ROOT:-"$(CDPATH= cd "$(dirname "$0")/../../.team/nodes/emu-own/tmp" && pwd)"}
ROOT="$BASE/focused-$$"
BIN="$ROOT/bin"; STATE="$ROOT/state"; EVENTS="$ROOT/events"
RUNNER_EVENTS="$ROOT/runner-events"
ENV_CHECK="$ROOT/envcheck"; RUNNER="$(CDPATH= cd "$(dirname "$0")" && pwd)/run-input-ab.sh"
mkdir -p "$BIN" "$STATE" || exit 2
cleanup() {
  if [ -n "${RUNNER_PID:-}" ]; then kill "$RUNNER_PID" 2>/dev/null || true; wait "$RUNNER_PID" 2>/dev/null || true; fi
  launcher=$(sed -n 's/^launcher_pid=//p' "$STATE/current" 2>/dev/null | head -1)
  [ -n "$launcher" ] && kill "$launcher" 2>/dev/null || true
  qemu=$(sed -n 's/^qemu_pid=//p' "$STATE/current" 2>/dev/null | head -1)
  [ -n "$qemu" ] && kill "$qemu" 2>/dev/null || true
  [ -n "${AMBIENT_PID:-}" ] && kill "$AMBIENT_PID" 2>/dev/null || true
  rm -rf "$ROOT"
}
trap cleanup EXIT INT TERM HUP

cat > "$BIN/ps" <<'EOF'
#!/bin/sh
state=${EMU_OWN_STATE:?}
printf ' PID COMMAND\n'
mode=$(sed -n 's/^qemu_mode=//p' "$state" | head -1)
pid=$(sed -n 's/^qemu_pid=//p' "$state" | head -1)
case "$mode" in
  full) [ -n "$pid" ] && printf '%s /fake/sdk/qemu-system-aarch64\n' "$pid" ;;
  ambient) printf '999 /fake/sdk/qemu-system-aarch64\n' ;;
esac
ambient_pid=$(sed -n 's/^ambient_pid=//p' "$state" | head -1)
[ -n "$ambient_pid" ] && printf '%s /fake/sdk/ambient-helper\n' "$ambient_pid"
EOF
cat > "$BIN/adb" <<'EOF'
#!/bin/sh
state=${EMU_OWN_STATE:?}
serial=${EMULATOR_SERIAL:-emulator-5554}
printf 'adb %s\n' "$*" >> "${EMU_OWN_EVENTS:?}"
if [ "${1:-}" = devices ]; then
  st=$(sed -n 's/^adb_state=//p' "$state" | head -1)
  printf 'List of devices attached\n%s\t%s\n' "$serial" "${st:-offline}"
  exit 0
fi
if [ "${1:-}" = -s ]; then
  printf '%s\n' "$(sed -n 's/^boot=//p' "$state" | head -1)"
  exit 0
fi
exit 1
EOF
cat > "$BIN/launcher" <<'EOF'
#!/bin/sh
state=${EMU_OWN_STATE:?}; events=${EMU_OWN_EVENTS:?}
printf 'launcher_pid=%s\n' "$$" >> "$state"
printf 'launcher\n' >> "$events"
sleep 30 &
qemu=$!
printf 'qemu_pid=%s\nqemu_mode=full\n' "$qemu" >> "$state"
trap 'kill "$qemu" 2>/dev/null || true; printf "launcher_cleanup\n" >> "$events"; exit 143' TERM INT HUP
while :; do sleep 1; done
EOF
chmod +x "$BIN"/*

cat > "$ENV_CHECK" <<'EOF'
#!/bin/sh
state=${EMU_OWN_STATE:?}; events=${EMU_OWN_EVENTS:?}
case "${1:-}" in
  --gate)
    n=$(sed -n 's/^gate_calls=//p' "$state" | head -1); n=$((${n:-0}+1))
    sed -i.bak '/^gate_calls=/d' "$state"; rm -f "$state.bak"
    printf 'gate_calls=%s\n' "$n" >> "$state"
    scenario=$(sed -n 's/^scenario=//p' "$state" | head -1)
    if [ "$scenario" = recovery ] && [ "$n" -eq 2 ]; then
      printf 'recovery_dirty load=27.43\n' >> "$events"; exit 2
    fi
    if [ "$scenario" = recovery ] && [ "$n" -ge 3 ]; then
      printf 'recovery_pass load=7.74\n' >> "$events"; exit 0
    fi
    printf 'preflight\n' >> "$events"; exit 0
    ;;
  --measurement)
    printf 'measurement pid=%s serial=%s\n' "${2:-}" "${3:-}" >> "$events"
    expected=$(sed -n 's/^qemu_pid=//p' "$state" | head -1)
    [ "${2:-}" = "$expected" ] || exit 2
    [ "${3:-}" = emulator-5554 ] || exit 2
    rc=$(sed -n 's/^measurement_rc=//p' "$state" | head -1)
    exit "${rc:-0}"
    ;;
esac
exit 2
EOF
chmod +x "$ENV_CHECK"

write_state() {
  cat > "$STATE/current" <<EOF
scenario=$1
qemu_mode=$2
adb_state=${3:-device}
boot=${4:-1}
measurement_rc=${5:-0}
gate_calls=0
EOF
  [ -n "${AMBIENT_PID:-}" ] && printf 'ambient_pid=%s\n' "$AMBIENT_PID" >> "$STATE/current"
  : > "$EVENTS"
  : > "$RUNNER_EVENTS"
}
run_runner() {
  EMU_OWN_STATE="$STATE/current" EMU_OWN_EVENTS="$EVENTS" RUNNER_EVENT_LOG="$RUNNER_EVENTS" ENV_CHECK="$ENV_CHECK" \
    ADB="$BIN/adb" EMULATOR_SERIAL=emulator-5554 EMULATOR_LAUNCHER="$BIN/launcher" \
    EMULATOR_READY_TIMEOUT=2 RUNNER_EMULATOR_ONLY=1 PATH="$BIN:$PATH" \
    bash "$RUNNER" --emulator-self-test >/dev/null 2>&1
  rc=$?
  return "$rc"
}
expect_rc() {
  want=$1; shift
  set +e; "$@"; got=$?; set -e
  [ "$got" -eq "$want" ] || { echo "expected rc=$want got=$got" >&2; exit 1; }
}

set -e
sleep 30 &
AMBIENT_PID=$!
write_state normal full
expect_rc 0 run_runner
grep -F 'measurement pid=' "$EVENTS" >/dev/null || { echo 'measurement spy missing' >&2; exit 1; }
actual=$(sed -n 's/^qemu_pid=//p' "$STATE/current" | head -1)
passed=$(sed -n 's/^measurement pid=//p' "$EVENTS" | awk '{print $1}')
[ "$passed" = "$actual" ] || { echo 'bound PID mismatch' >&2; exit 1; }
grep -F 'launcher_cleanup' "$EVENTS" >/dev/null || { echo 'success cleanup missing' >&2; exit 1; }
order=$(awk '$1 == "preflight" || $1 == "launcher" || $1 == "measurement" {print $1}' "$RUNNER_EVENTS" | tr '\n' ' ')
[ "$order" = 'preflight launcher measurement ' ] || { echo "order=$order" >&2; exit 1; }

assert_ambient_alive() {
  kill -0 "$AMBIENT_PID" 2>/dev/null || { echo 'ambient PID was killed' >&2; exit 1; }
}
assert_ambient_alive

write_state recovery none
expect_rc 2 run_runner
grep -F 'recovery_pass load=7.74' "$EVENTS" >/dev/null || { echo 'zero count recovery missing' >&2; exit 1; }
! grep -F 'measurement' "$EVENTS" >/dev/null || { echo 'zero count reached measurement' >&2; exit 1; }
zero_launcher=$(sed -n 's/^launcher_pid=//p' "$STATE/current" | head -1)
[ -n "$zero_launcher" ] && ! kill -0 "$zero_launcher" 2>/dev/null || { echo 'zero count orphan' >&2; exit 1; }

write_state no-adb full offline 0
expect_rc 2 run_runner
noadb_qemu=$(sed -n 's/^qemu_pid=//p' "$STATE/current" | head -1)
[ -n "$noadb_qemu" ] && ! kill -0 "$noadb_qemu" 2>/dev/null || { echo 'no adb orphan' >&2; exit 1; }

write_state reject full device 1 2
expect_rc 2 run_runner
reject_qemu=$(sed -n 's/^qemu_pid=//p' "$STATE/current" | head -1)
[ -n "$reject_qemu" ] && ! kill -0 "$reject_qemu" 2>/dev/null || { echo 'measurement reject orphan' >&2; exit 1; }

write_state recovery none device 1
expect_rc 2 run_runner
grep -F 'recovery_dirty load=27.43' "$EVENTS" >/dev/null || { echo 'recovery dirty missing' >&2; exit 1; }
grep -F 'recovery_pass load=7.74' "$EVENTS" >/dev/null || { echo 'recovery pass missing' >&2; exit 1; }

write_state signal full
set +e
EMU_OWN_STATE="$STATE/current" EMU_OWN_EVENTS="$EVENTS" RUNNER_EVENT_LOG="$RUNNER_EVENTS" ENV_CHECK="$ENV_CHECK" ADB="$BIN/adb" \
  EMULATOR_SERIAL=emulator-5554 EMULATOR_LAUNCHER="$BIN/launcher" EMULATOR_READY_TIMEOUT=2 \
  RUNNER_EMULATOR_ONLY=1 RUNNER_EMULATOR_TEST_WAIT=1 PATH="$BIN:$PATH" \
  bash "$RUNNER" --emulator-self-test >/dev/null 2>&1 &
RUNNER_PID=$!
set -e
for n in 1 2 3 4 5; do grep '^qemu_pid=' "$STATE/current" >/dev/null 2>&1 && break; sleep 1; done
kill -TERM "$RUNNER_PID"
set +e; wait "$RUNNER_PID"; signal_rc=$?; set -e
[ "$signal_rc" -eq 143 ] || { echo "signal rc=$signal_rc" >&2; exit 1; }
signal_qemu=$(sed -n 's/^qemu_pid=//p' "$STATE/current" | head -1)
[ -n "$signal_qemu" ] && ! kill -0 "$signal_qemu" 2>/dev/null || { echo 'signal orphan' >&2; exit 1; }
assert_ambient_alive

printf '%s\n' 'EMU_OWN_EVIDENCE fullpath_qemu_bound=true bound_pid_passed=true serial_passed=true order_preflight_launch_bind_adb_measurement=true zero_count_exit=2 zero_count_no_install=true zero_count_no_orphan=true zero_count_recovered=true no_adb_exit=2 no_adb_no_orphan=true measurement_reject_exit=2 success_cleanup=true failure_cleanup=true signal_cleanup=true only_owned_pid_killed=true'
