#!/usr/bin/env bash
# Strict input A/B/A/B sampler.  0=pass, 1=measured regression, 2=unjudgeable.
set -uo pipefail
T="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT="$(cd "$T/../.." && pwd)"
PARSER="$T/parse-input-ab.py"
BASELINE_TAG="baseline-20260822-release"
REFERENCE_MD5="0907d6881bb1e034ef33a49f89afaa44"
FIXTURES=(big_scrollback real_claude_idle redraw_tui)

md5_file() {
  if command -v md5 >/dev/null 2>&1; then md5 -q "$1"
  elif command -v md5sum >/dev/null 2>&1; then md5sum "$1" | awk '{print $1}'
  else return 2
  fi
}
parser_call() { python3 "$PARSER" "$@"; }
expect_rc() {
  local wanted="$1" label="$2"; shift 2
  set +e; "$@" >/dev/null 2>&1; local got=$?; set -e
  if [ "$got" -ne "$wanted" ]; then
    echo "SELF-TEST FAIL $label: expected rc=$wanted got rc=$got" >&2
    return 1
  fi
}

self_test() {
  local tmp="$WT/.team/nodes/input-full-auto/sampler-impl/tmp/self-test"
  rm -rf "$tmp"; mkdir -p "$tmp/A" "$tmp/B"
  local order="$tmp/order.tsv"; : > "$order"
  python3 - "$tmp" "$order" <<'PY'
import pathlib, sys
root, order = map(pathlib.Path, sys.argv[1:])
for fx in ("big_scrollback", "real_claude_idle", "redraw_tui"):
    for n in range(1, 11):
        for package in ("A", "B"):
            base = 1000 + n * 100
            path = root / package / f"{fx}-{n:02d}.log"
            path.write_text("\n".join([
                f"D PerfTrace: open_id={package}-{n} ev=tap t={base}",
                f"D PerfTrace: open_id={package}-{n} ev=route_enter t={base+10}",
                f"D PerfTrace: open_id={package}-{n} ev=first_frame_recv t={base+30}",
                f"D PerfTrace: open_id={package}-{n} ev=first_draw t={base+70}",
            ]) + "\n", encoding="utf-8")
            with order.open("a", encoding="utf-8") as stream:
                stream.write(f"{fx}\t{n}\t{package}\n")
PY
  local out="$tmp/good.json"
  local -a common=(--a "$tmp/A" --b "$tmp/B" --order "$order" --out "$out"
    --baseline-tag "$BASELINE_TAG" --baseline-reference-md5 "$REFERENCE_MD5"
    --a-md5 "$REFERENCE_MD5" --b-md5 "11111111111111111111111111111111"
    --a-revision "$BASELINE_TAG" --b-revision candidate
    --load1 1.0 --free 1024 --inactive 4096)
  expect_rc 0 positive parser_call "${common[@]}" || return 1
  cp "$order" "$tmp/order-bad.tsv"
  python3 - "$tmp/order-bad.tsv" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1]); rows = p.read_text().splitlines()
rows[0] = rows[0].replace("\tA", "\tB")
p.write_text("\n".join(rows) + "\n")
PY
  local -a bad_order=("${common[@]}"); bad_order[5]="$tmp/order-bad.tsv"
  expect_rc 2 order parser_call "${bad_order[@]}" || return 1
  expect_rc 2 same-md5 parser_call "${common[@]/11111111111111111111111111111111/$REFERENCE_MD5}" || return 1
  local -a bad_tag=("${common[@]}"); bad_tag[9]=not-a-release
  expect_rc 2 wrong-tag parser_call "${bad_tag[@]}" || return 1
  cp -R "$tmp/A" "$tmp/A-missing"; rm -f "$tmp/A-missing/redraw_tui-01.log"
  local -a missing=("${common[@]}"); missing[1]="$tmp/A-missing"
  expect_rc 2 missing-sample parser_call "${missing[@]}" || return 1
  cp -R "$tmp/A" "$tmp/A-fixture-missing"
  rm -f "$tmp/A-fixture-missing/redraw_tui-"*.log
  local -a missing_fixture=("${common[@]}"); missing_fixture[1]="$tmp/A-fixture-missing"
  expect_rc 2 missing-fixture parser_call "${missing_fixture[@]}" || return 1
  cp -R "$tmp/A" "$tmp/A-event"
  python3 - "$tmp/A-event/real_claude_idle-01.log" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
p.write_text("\n".join(x for x in p.read_text().splitlines() if "first_frame_recv" not in x) + "\n")
PY
  local -a missing_event=("${common[@]}"); missing_event[1]="$tmp/A-event"
  expect_rc 2 missing-event parser_call "${missing_event[@]}" || return 1
  cp -R "$tmp/A" "$tmp/A-nonmonotonic"
  python3 - "$tmp/A-nonmonotonic/real_claude_idle-01.log" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1]); p.write_text(p.read_text().replace("t=1110", "t=1100"))
PY
  local -a nonmonotonic=("${common[@]}"); nonmonotonic[1]="$tmp/A-nonmonotonic"
  expect_rc 2 nonmonotonic parser_call "${nonmonotonic[@]}" || return 1
  cp -R "$tmp/A" "$tmp/A-n"; rm -f "$tmp/A-n/big_scrollback-10.log"
  cp "$order" "$tmp/order-n.tsv"
  sed -i.bak '/big_scrollback\t10\tA/d' "$tmp/order-n.tsv"; rm -f "$tmp/order-n.tsv.bak"
  local -a short=("${common[@]}"); short[1]="$tmp/A-n"; short[5]="$tmp/order-n.tsv"
  expect_rc 2 short-group parser_call "${short[@]}" || return 1
  cp -R "$tmp/B" "$tmp/B-slow"
  python3 - "$tmp/B-slow/real_claude_idle-01.log" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1]); p.write_text(p.read_text().replace("t=1170", "t=1300"))
PY
  local -a slow=("${common[@]}"); slow[3]="$tmp/B-slow"
  expect_rc 1 regression parser_call "${slow[@]}" || return 1
  local -a missing_env=("${common[@]}"); missing_env+=(--load1 "")
  expect_rc 2 missing-env parser_call "${missing_env[@]}" || return 1
  expect_rc 2 dirty-env parser_call "${common[@]}" --envcheck-exit 2 || return 1
  rm -rf "$tmp"; rmdir "${tmp%/*}" 2>/dev/null || true
  echo "SELF-TEST PASS order identity fixtures segments samples ratio env"
}

if [ "${1:-}" = "--self-test" ]; then
  set -e; self_test; exit $?
fi
[ -x "$PARSER" ] || { echo "UNJUDGEABLE parser missing or not executable: $PARSER" >&2; exit 2; }
ENV_CHECK="${ENV_CHECK:-$T/envcheck.sh}"
[ -f "$ENV_CHECK" ] || { echo "UNJUDGEABLE envcheck missing: $ENV_CHECK" >&2; exit 2; }
set +e; sh "$ENV_CHECK" --gate; env_rc=$?; set -e
[ "$env_rc" -eq 0 ] || { echo "UNJUDGEABLE envcheck exit=$env_rc" >&2; exit 2; }

# Capture the comparable host readings only after the environment gate passes.
# Values are MiB for memory, and load1 is the one-minute host load.
collect_memory() {
  if command -v vm_stat >/dev/null 2>&1; then
    local vm page free inactive
    vm="$(vm_stat 2>/dev/null || true)"
    page="$(printf '%s\n' "$vm" | sed -n 's/.*page size of \([0-9][0-9]*\) bytes.*/\1/p' | head -1)"
    free="$(printf '%s\n' "$vm" | awk -F: '/Pages free:/{gsub(/[^0-9]/, "", $2); print $2; exit}')"
    inactive="$(printf '%s\n' "$vm" | awk -F: '/Pages inactive:/{gsub(/[^0-9]/, "", $2); print $2; exit}')"
    [ -n "$page" ] && [ -n "$free" ] && [ -n "$inactive" ] || return 1
    printf '%s %s\n' "$((free * page / 1024 / 1024))" "$((inactive * page / 1024 / 1024))"
    return 0
  fi
  if [ -r /proc/meminfo ]; then
    awk '/^MemFree:/{free=$2} /^Inactive:/{inactive=$2} END {if (free != "" && inactive != "") printf "%d %d\n", free/1024, inactive/1024}' /proc/meminfo
    return 0
  fi
  return 1
}

LOAD1="${LOAD1:-}"
FREE="${FREE:-}"
INACTIVE="${INACTIVE:-}"
[ -n "$LOAD1" ] || LOAD1="$(uptime | sed -E 's/.*load averages?: *([0-9.]+).*/\1/')"
if [ -z "$FREE" ] || [ -z "$INACTIVE" ]; then
  MEMORY="$(collect_memory 2>/dev/null || true)"
  [ -n "$FREE" ] || FREE="$(printf '%s\n' "$MEMORY" | awk '{print $1}')"
  [ -n "$INACTIVE" ] || INACTIVE="$(printf '%s\n' "$MEMORY" | awk '{print $2}')"
fi
[ -n "$LOAD1" ] && [ -n "$FREE" ] && [ -n "$INACTIVE" ] || {
  echo "UNJUDGEABLE host load/free/inactive readings unavailable" >&2
  exit 2
}
APK_A="${APK_A:-}"; APK_B="${APK_B:-}"
[ -f "$APK_A" ] && [ -f "$APK_B" ] || { echo "UNJUDGEABLE APK_A/APK_B must name two APK files" >&2; exit 2; }
A_MD5="$(md5_file "$APK_A")" || { echo "UNJUDGEABLE no md5 utility" >&2; exit 2; }
B_MD5="$(md5_file "$APK_B")" || { echo "UNJUDGEABLE no md5 utility" >&2; exit 2; }
[ "$A_MD5" = "$REFERENCE_MD5" ] || { echo "UNJUDGEABLE A md5=$A_MD5 is not frozen reference $REFERENCE_MD5" >&2; exit 2; }
[ "$A_MD5" != "$B_MD5" ] || { echo "UNJUDGEABLE A/B md5 are identical" >&2; exit 2; }
N="${N:-10}"
case "$N" in ''|*[!0-9]*) echo "UNJUDGEABLE N must be an integer >= 10" >&2; exit 2;; esac
[ "$N" -ge 10 ] || { echo "UNJUDGEABLE N=$N < 10" >&2; exit 2; }
RAW_ROOT="${RAW_ROOT:-$WT/.team/nodes/input-full-auto/sampler-impl/tmp/raw-$(date +%Y%m%d-%H%M%S)}"; OUT="${OUT:-$RAW_ROOT/perf-ab.json}"
ORDER="$RAW_ROOT/order.tsv"
SETUP_FIXTURES="${SETUP_FIXTURES:-$WT/.team/nodes/ca-emu/tmp/setup-fixtures.sh}"
COLDOPEN="${COLDOPEN:-$WT/.team/nodes/ca-emu/tmp/coldopen.sh}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
[ -x "$SETUP_FIXTURES" ] && [ -x "$COLDOPEN" ] || { echo "UNJUDGEABLE isolated fixture scripts missing" >&2; exit 2; }
mkdir -p "$RAW_ROOT/A" "$RAW_ROOT/B"; : > "$ORDER"
set +e; "$SETUP_FIXTURES"; setup_rc=$?; set -e
[ "$setup_rc" -eq 0 ] || { echo "UNJUDGEABLE isolated fixture setup failed rc=$setup_rc" >&2; exit 2; }
install_apk() { "$ADB" install -r "$1" >/dev/null 2>&1; }
for fx in "${FIXTURES[@]}"; do
  for n in $(seq 1 "$N"); do
    for package in A B; do
      apk="$APK_A"; [ "$package" = B ] && apk="$APK_B"
      install_apk "$apk" || { echo "UNJUDGEABLE APK install failed package=$package fixture=$fx n=$n" >&2; exit 2; }
      printf '%s\t%s\t%s\n' "$fx" "$n" "$package" >> "$ORDER"
      OUTDIR="$RAW_ROOT/$package" bash "$COLDOPEN" "$fx" "$n"
      rc=$?; [ "$rc" -eq 0 ] || { echo "UNJUDGEABLE coldopen failed package=$package fixture=$fx n=$n rc=$rc" >&2; exit 2; }
    done
  done
done
set -e
parser_call --a "$RAW_ROOT/A" --b "$RAW_ROOT/B" --order "$ORDER" --out "$OUT" \
  --baseline-tag "$BASELINE_TAG" --baseline-reference-md5 "$REFERENCE_MD5" \
  --a-md5 "$A_MD5" --b-md5 "$B_MD5" --a-revision "$BASELINE_TAG" \
  --b-revision "${B_REVISION:-candidate}" --envcheck-exit "$env_rc" \
  --load1 "$LOAD1" --free "$FREE" --inactive "$INACTIVE"
