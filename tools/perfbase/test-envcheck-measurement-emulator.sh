#!/bin/sh
# Focused, repo-local test for the two environment-gate phases.
set -u

ROOT=${ENVCHECK_EMU_FIXTURE_ROOT:-"$(CDPATH= cd "$(dirname "$0")/../../.team/nodes/envcheck-emu/tmp" && pwd)"}/focused-$$
BIN="$ROOT/bin"
TOOLPATH="$ROOT/toolpath"
SOCKETS=${ENVCHECK_EMU_FIXTURE_ROOT:-"$(CDPATH= cd "$(dirname "$0")/../../.team/nodes/envcheck-emu/tmp" && pwd)"}
STATE="$ROOT/state"
ENV_CHECK="$(CDPATH= cd "$(dirname "$0")" && pwd)/envcheck.sh"
RUNNER="$(CDPATH= cd "$(dirname "$0")" && pwd)/run-input-ab.sh"
mkdir -p "$BIN" "$TOOLPATH" "$STATE" || exit 2
cleanup() {
  if [ -n "${RUNNER_PID:-}" ]; then kill "$RUNNER_PID" 2>/dev/null || true; wait "$RUNNER_PID" 2>/dev/null || true; fi
  rm -f "$SOCKETS"/s-$$-*
  rm -rf "$ROOT"
}
trap cleanup EXIT INT TERM HUP

cat > "$BIN/uptime" <<'EOF'
#!/bin/sh
state=${EMU_TEST_STATE:?}
load=$(sed -n 's/^load=//p' "$state" | head -1)
printf ' 12:00  up 1 day,  load averages: %s, 0.00, 0.00\n' "$load"
EOF
cat > "$BIN/ps" <<'EOF'
#!/bin/sh
state=${EMU_TEST_STATE:?}
printf '  PID COMMAND\n'
sed -n '/^qemu=/s/^qemu=//p' "$state" | while IFS=' ' read -r pid comm; do
  [ -n "$pid" ] && printf '%s %s\n' "$pid" "$comm"
done
sed -n '/^qemu_extra=/s/^qemu_extra=//p' "$state" | while IFS=' ' read -r pid comm; do
  [ -n "$pid" ] && printf '%s %s\n' "$pid" "$comm"
done
launcher=$(sed -n 's/^launcher_pid=//p' "$state" | head -1)
if [ -n "$launcher" ]; then printf '%s qemu-system-x86_64\n' "$launcher"; fi
EOF
cat > "$BIN/tmux" <<'EOF'
#!/bin/sh
exit 1
EOF
cat > "$BIN/lsof" <<'EOF'
#!/bin/sh
state=${EMU_TEST_STATE:?}
if [ "$(sed -n 's/^daemon=//p' "$state" | head -1)" = 1 ]; then printf '4242\n'; fi
EOF
cat > "$BIN/top" <<'EOF'
#!/bin/sh
state=${EMU_TEST_STATE:?}
cpu=$(sed -n 's/^cpu=//p' "$state" | head -1)
printf '4242 %s\n' "${cpu:-0}"
EOF
cat > "$BIN/adb" <<'EOF'
#!/bin/sh
state=${EMU_TEST_STATE:?}
serial=${EMULATOR_SERIAL:-emulator-5554}
if [ "${1:-}" = devices ]; then
  adb_state=$(sed -n 's/^adb=//p' "$state" | head -1)
  printf 'List of devices attached\n%s\t%s\n' "$serial" "${adb_state:-offline}"
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
state=${EMU_TEST_STATE:?}
printf 'launcher_pid=%s\n' "$$" >> "$state"
trap 'printf "cleanup_pid=%s\n" "$$" >> "$state"; exit 0' TERM INT HUP
while :; do sleep 1; done
EOF
cat > "$BIN/fail-cleanup.bashenv" <<'EOF'
kill() {
  if [ "${1:-}" = -0 ]; then builtin kill "$@"; else return 1; fi
}
wait() { return 1; }
EOF
chmod +x "$BIN"/*
for dependency in awk sed head tr tail; do
  ln -s "$(command -v "$dependency")" "$TOOLPATH/$dependency"
done

write_state() {
  cat > "$STATE/current" <<EOF
load=$1
qemu=$2
daemon=${3:-0}
cpu=${4:-0}
adb=${5:-device}
boot=${6:-1}
EOF
  rm -f "$STATE/current.tmp"
}
run_gate() {
  (cd "$SOCKETS" && ENVCHECK_SOCKET_DIR=. EMU_TEST_STATE="$STATE/current" PATH="$BIN:$PATH" \
    /bin/sh "$ENV_CHECK" --gate >/dev/null 2>&1)
}
run_measure() {
  (cd "$SOCKETS" && ENVCHECK_SOCKET_DIR=. EMU_TEST_STATE="$STATE/current" ADB="$BIN/adb" PATH="$BIN:$PATH" \
    EMULATOR_SERIAL=emulator-5554 /bin/sh "$ENV_CHECK" --measurement 111 emulator-5554 >/dev/null 2>&1)
}
expect() {
  want=$1; shift
  set +e; "$@"; got=$?; set -e
  [ "$got" -eq "$want" ] || { echo "focused failure expected=$want got=$got" >&2; exit 1; }
}

set -e
write_state 8.50 ''
expect 0 run_gate
for missing_tool in ps uptime lsof top tmux; do
  rm -f "$TOOLPATH"/*
  for fake_tool in ps uptime lsof top tmux; do
    [ "$fake_tool" = "$missing_tool" ] || ln -s "$BIN/$fake_tool" "$TOOLPATH/$fake_tool"
  done
  set +e
  ENVCHECK_SOCKET_DIR="$SOCKETS" EMU_TEST_STATE="$STATE/current" PATH="$TOOLPATH" \
    /bin/sh "$ENV_CHECK" --gate >/dev/null 2>&1
  missing_rc=$?
  set -e
  [ "$missing_rc" -eq 2 ] || { echo "missing tool $missing_tool rc=$missing_rc" >&2; exit 1; }
done
rm -f "$TOOLPATH"/*
write_state 8.50 '111 qemu-system-x86_64'
expect 2 run_gate
write_state 16.55 '111 qemu-system-x86_64'
expect 0 run_measure
write_state 16.55 '111 qemu-system-x86_64'
printf '%s\n' 'qemu_extra=222 qemu-system-aarch64' >> "$STATE/current"
expect 2 run_measure
(
cd "$SOCKETS"
python3 - . "$$" <<'PY'
import pathlib, socket, sys
root = pathlib.Path(sys.argv[1])
prefix = sys.argv[2]
for n in range(11):
    path = root / f"s-{prefix}-{n}"
    sock = socket.socket(socket.AF_UNIX)
    sock.bind(str(path))
    sock.close()
PY
)
expect 2 run_measure
rm -f "$SOCKETS"/s-$$-*
write_state 16.55 '111 qemu-system-x86_64' 1 6
expect 2 run_measure
write_state 16.55 '111 qemu-system-x86_64' 0 0 offline 0
expect 2 run_measure
write_state 16.55 '' 0 0 device 1
expect 2 run_measure

run_runner() {
  label=$1; expected=$2
  rm -f "$STATE/current" "$STATE/cleanup_pid" "$STATE/launcher_pid" "$STATE/marker"
  write_state 8.50 '' 0 0 device 1
  set +e
  EMU_TEST_STATE="$STATE/current" EMULATOR_CLEANUP_MARKER="$STATE/marker" ENVCHECK_SOCKET_DIR="$SOCKETS" ADB="$BIN/adb" \
    EMULATOR_SERIAL=emulator-5554 EMULATOR_LAUNCHER="$BIN/launcher" EMULATOR_READY_TIMEOUT=5 \
    RUNNER_EMULATOR_ONLY=1 RUNNER_EMULATOR_TEST_EXIT="$expected" PATH="$BIN:$PATH" \
    bash "$RUNNER" --emulator-self-test >/dev/null 2>&1
  got=$?
  set -e
  [ "$got" -eq "$expected" ] || { echo "runner $label rc=$got" >&2; exit 1; }
  bound_pid=$(sed -n 's/^launcher_pid=//p' "$STATE/current" | head -1)
  [ -n "$bound_pid" ] && ! kill -0 "$bound_pid" 2>/dev/null || { echo "runner $label did not clean bound pid" >&2; exit 1; }
  [ -s "$STATE/marker" ] || { echo "runner $label cleanup trap was not observed" >&2; exit 1; }
}
run_runner success 0
run_runner failure 2

rm -f "$STATE/current" "$STATE/cleanup_pid" "$STATE/launcher_pid" "$STATE/marker"
write_state 8.50 '' 0 0 device 1
set +e
EMU_TEST_STATE="$STATE/current" EMULATOR_CLEANUP_MARKER="$STATE/marker" BASH_ENV="$BIN/fail-cleanup.bashenv" \
  ENVCHECK_SOCKET_DIR="$SOCKETS" ADB="$BIN/adb" EMULATOR_SERIAL=emulator-5554 \
  EMULATOR_LAUNCHER="$BIN/launcher" EMULATOR_READY_TIMEOUT=5 RUNNER_EMULATOR_ONLY=1 PATH="$BIN:$PATH" \
  bash "$RUNNER" --emulator-self-test >/dev/null 2>&1
cleanup_fail_rc=$?
set -e
[ "$cleanup_fail_rc" -eq 2 ] || { echo "runner cleanup failure rc=$cleanup_fail_rc" >&2; exit 1; }
grep '^cleanup qemu=' "$STATE/marker" >/dev/null 2>&1 || { echo "runner cleanup failure was not observed" >&2; exit 1; }
cleanup_pid=$(sed -n 's/^launcher_pid=//p' "$STATE/current" | head -1)
[ -n "$cleanup_pid" ] && kill "$cleanup_pid" 2>/dev/null || true

rm -f "$STATE/current" "$STATE/cleanup_pid" "$STATE/launcher_pid" "$STATE/marker"
write_state 8.50 '' 0 0 device 1
set +e
EMU_TEST_STATE="$STATE/current" EMULATOR_CLEANUP_MARKER="$STATE/marker" ENVCHECK_SOCKET_DIR="$SOCKETS" ADB="$BIN/adb" \
  EMULATOR_SERIAL=emulator-5554 EMULATOR_LAUNCHER="$BIN/launcher" EMULATOR_READY_TIMEOUT=5 \
  RUNNER_EMULATOR_ONLY=1 RUNNER_EMULATOR_TEST_WAIT=1 PATH="$BIN:$PATH" bash "$RUNNER" --emulator-self-test >/dev/null 2>&1 &
RUNNER_PID=$!
set -e
for n in 1 2 3 4 5; do [ -f "$STATE/current" ] && grep '^launcher_pid=' "$STATE/current" >/dev/null 2>&1 && break; sleep 1; done
kill -TERM "$RUNNER_PID"
set +e; wait "$RUNNER_PID"; signal_rc=$?; set -e
[ "$signal_rc" -eq 143 ] || { echo "runner signal rc=$signal_rc" >&2; exit 1; }
bound_pid=$(sed -n 's/^launcher_pid=//p' "$STATE/current" | head -1)
[ -n "$bound_pid" ] && ! kill -0 "$bound_pid" 2>/dev/null || { echo "runner signal did not clean bound pid" >&2; exit 1; }
[ -s "$STATE/marker" ] || { echo "runner signal cleanup trap was not observed" >&2; exit 1; }

printf '%s\n' 'ENVCHECK_EMU_EVIDENCE preflight_clean=0 preflight_unrelated_qemu=2 owned_high_load=0 extra_qemu=2 dead_socket=2 daemon_cpu=2 no_adb=2 unowned_high_load=2 cleanup_success=true cleanup_failure=true cleanup_signal=true'
