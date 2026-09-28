#!/bin/sh
# //! purpose: 机械验证 r13 性能红已有因果聚焦修复，且新候选的 fresh 三夹具四段 A/B 门全绿。
# //! contract: 0=因果聚焦门与新候选性能门通过；1=产品/测试/交付不满足；2=环境或证据不可判。
# //! boundary: 聚焦门只跑仓内假夹具；本脚本不启动真实 emulator/tmux/daemon，只读取已落盘 fresh 测量。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u

fail() {
    printf '%s\n' "FAIL perf-regress: $*" >&2
    exit 1
}

unjudgeable() {
    printf '%s\n' "UNJUDGEABLE perf-regress: $*" >&2
    exit 2
}

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) \
    || unjudgeable "cannot resolve acceptance script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) \
    || unjudgeable "cannot resolve repository root"
focused="$repo_root/tools/perfbase/test-perf-regress-attribution.sh"
node="$repo_root/.team/nodes/perf-regress"
attribution="$node/ATTRIBUTION.md"
impl="$node/IMPL.md"
measure="$node/FIXED-MEASURE.md"
result_json="$node/perf-ab-fixed.json"
raw_a="$node/raw/A"
raw_b="$node/raw/B"
fixture_root="$node/tmp"
gradlew="$repo_root/app/gradlew"
gradle_wrapper="$repo_root/app/gradle/wrapper/gradle-wrapper.jar"
local_properties="$repo_root/app/local.properties"

[ -f "$focused" ] || fail "missing focused test: tools/perfbase/test-perf-regress-attribution.sh"
[ -r "$focused" ] || unjudgeable "focused test is unreadable"
[ -s "$focused" ] || fail "focused test is empty"
command -v sh >/dev/null 2>&1 || unjudgeable "POSIX sh is unavailable"
command -v grep >/dev/null 2>&1 || unjudgeable "grep is unavailable"
command -v find >/dev/null 2>&1 || unjudgeable "find is unavailable"
command -v wc >/dev/null 2>&1 || unjudgeable "wc is unavailable"
command -v tr >/dev/null 2>&1 || unjudgeable "tr is unavailable"
command -v sed >/dev/null 2>&1 || unjudgeable "sed is unavailable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 is unavailable"

sh -n "$focused" >/dev/null 2>&1 || fail "focused test is not valid POSIX sh"
if grep -E '\[\[|\]\]|(^|[[:space:]])(local|declare|typeset|function)[[:space:]]|[<>]\(' "$focused" >/dev/null 2>&1; then
    fail "focused test contains a forbidden bashism"
fi
grep -F 'PerfRegressionTapRouteBoundaryTest' "$focused" >/dev/null 2>&1 \
    || fail "focused test does not run tap-to-route real-entry test"
grep -F 'PerfRegressionFirstFrameDrawBoundaryTest' "$focused" >/dev/null 2>&1 \
    || fail "focused test does not run first-frame-to-draw real-entry test"
grep -F 'PerfRegressionBigScrollbackControlTest' "$focused" >/dev/null 2>&1 \
    || fail "focused test lacks big_scrollback positive control"
grep -F -e '--rerun-tasks' "$focused" >/dev/null 2>&1 \
    || fail "focused Gradle test can reuse cache"

[ -f "$gradlew" ] || unjudgeable "Gradle wrapper script is missing"
[ -r "$gradlew" ] && [ -x "$gradlew" ] \
    || unjudgeable "Gradle wrapper script is not runnable"
[ -f "$gradle_wrapper" ] && [ -r "$gradle_wrapper" ] \
    || unjudgeable "Gradle wrapper runtime is missing or unreadable"
command -v java >/dev/null 2>&1 \
    || unjudgeable "Java runtime for Gradle is unavailable"

sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk_dir" ]; then
    [ -f "$local_properties" ] && [ -r "$local_properties" ] \
        || unjudgeable "Android SDK location is unavailable"
    sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$local_properties" 2>/dev/null | sed -n '1p') \
        || unjudgeable "cannot read Android SDK location"
fi
[ -n "$sdk_dir" ] && [ -d "$sdk_dir" ] \
    || unjudgeable "Android SDK directory is unavailable"

mkdir -p "$fixture_root" 2>/dev/null \
    || unjudgeable "cannot create repo-local fixture root"
write_probe="$fixture_root/.acceptance-write-$$"
(umask 077 && : > "$write_probe") 2>/dev/null \
    || unjudgeable "repo-local fixture root is not writable"
rm -f "$write_probe" 2>/dev/null \
    || unjudgeable "cannot clean fixture write probe"

focused_output=$(PERF_REGRESS_FIXTURE_ROOT="$fixture_root" sh "$focused" 2>&1)
focused_rc=$?
printf '%s\n' "$focused_output"
case "$focused_rc" in
    0) ;;
    1)
        if printf '%s\n' "$focused_output" | grep -E 'SDK location not found|Android SDK.*(not found|missing)|Failed to find Build Tools revision|Installed Build Tools revision.*corrupt|failed to find target with hash string|License for package.*not accepted|JAVA_HOME is not set|Unable to locate a Java Runtime|GradleWrapperMain|Could not install Gradle distribution|Could not resolve host|UnknownHostException|Connection timed out|Read timed out' >/dev/null 2>&1; then
            unjudgeable "SDK/Gradle runtime could not execute focused test"
        fi
        fail "focused causal regression test failed"
        ;;
    2) unjudgeable "focused causal regression test could not judge" ;;
    *) unjudgeable "focused test returned unsupported exit $focused_rc" ;;
esac

evidence='PERF_REGRESS_EVIDENCE tap_route_real_entry=true frame_draw_real_entry=true selected_cause_assertion=true big_scrollback_control=true'
printf '%s\n' "$focused_output" | grep -F "$evidence" >/dev/null 2>&1 \
    || fail "missing exact causal and positive-control evidence"

for artifact in "$attribution" "$impl" "$measure"; do
    [ -e "$artifact" ] || fail "missing required artifact: $artifact"
    [ -r "$artifact" ] || unjudgeable "required artifact is unreadable: $artifact"
    [ -s "$artifact" ] || fail "required artifact is empty: $artifact"
done

grep -F 'redraw_tui.tap_to_route_enter' "$attribution" >/dev/null 2>&1 \
    || fail "attribution omits redraw_tui tap-to-route prior red"
grep -F 'real_claude_idle.first_frame_to_first_draw' "$attribution" >/dev/null 2>&1 \
    || fail "attribution omits real_claude_idle frame-to-draw prior red"
grep -F 'big_scrollback' "$attribution" >/dev/null 2>&1 \
    || fail "attribution omits big_scrollback positive control"
grep -F '破坏齿' "$impl" >/dev/null 2>&1 \
    || fail "implementation report omits causal mutation tooth"
measure_last_line=$(sed -n '$p' "$measure" 2>/dev/null) \
    || unjudgeable "cannot read fixed measurement verdict"
case "$measure_last_line" in
    'measurement: pass') ;;
    'measurement: fail') fail "fixed measurement reports product regression" ;;
    'measurement: unjudgeable') unjudgeable "fixed measurement could not judge" ;;
    *) unjudgeable "fixed measurement verdict is missing or unsupported" ;;
esac

[ -e "$result_json" ] || fail "missing required artifact: $result_json"
[ -r "$result_json" ] || unjudgeable "required artifact is unreadable: $result_json"
[ -s "$result_json" ] || fail "required artifact is empty: $result_json"

[ -d "$raw_a" ] && [ -d "$raw_b" ] \
    || fail "fresh raw A/B directories are missing"
[ -r "$raw_a" ] && [ -r "$raw_b" ] \
    || unjudgeable "fresh raw A/B directories are unreadable"
raw_a_count=$(find "$raw_a" -type f -name '*.log' -print 2>/dev/null | wc -l | tr -d ' ')
raw_b_count=$(find "$raw_b" -type f -name '*.log' -print 2>/dev/null | wc -l | tr -d ' ')
case "$raw_a_count:$raw_b_count" in
    *[!0-9:]*) unjudgeable "cannot count fresh raw logs" ;;
esac
[ -n "$raw_a_count" ] && [ -n "$raw_b_count" ] \
    || unjudgeable "cannot count fresh raw logs"
[ "$raw_a_count" -ge 30 ] && [ "$raw_b_count" -ge 30 ] \
    || unjudgeable "fresh raw logs are incomplete A=$raw_a_count B=$raw_b_count"

python3 - "$result_json" <<'PY'
import json
import math
import sys

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
except Exception as exc:
    print("UNJUDGEABLE perf-regress: bad json:", exc, file=sys.stderr)
    sys.exit(2)

def product_fail(message):
    print("FAIL perf-regress:", message, file=sys.stderr)
    sys.exit(1)

def cannot_judge(message):
    print("UNJUDGEABLE perf-regress:", message, file=sys.stderr)
    sys.exit(2)

if data.get("schema") != "perf-ab.v1":
    product_fail("schema is not perf-ab.v1")
if data.get("baseline_source") != "baseline-20260822-release":
    product_fail("baseline source changed")
if data.get("baseline_reference_md5") != "0907d6881bb1e034ef33a49f89afaa44":
    product_fail("baseline reference md5 changed")

a_md5 = data.get("baseline_measured_md5")
b_md5 = data.get("candidate_md5")
b_revision = str(data.get("candidate_revision") or "")
if not a_md5 or not b_md5 or not b_revision:
    cannot_judge("A/B identity is incomplete")
if a_md5 == b_md5:
    cannot_judge("A/B packages have identical md5")
if a_md5 != "0907d6881bb1e034ef33a49f89afaa44":
    cannot_judge("measured A does not match the reference apk")
if b_md5 == "3ebc9c55703c780c842a2f410b85034e" or b_revision.startswith("565542972"):
    product_fail("old failing B was rerun instead of a changed candidate")
if (data.get("env") or {}).get("gate_exit") != 0:
    cannot_judge("environment gate did not pass")
if (data.get("order") or {}).get("valid") is not True:
    cannot_judge("A/B/A/B order is missing or invalid")

fixtures = ("big_scrollback", "real_claude_idle", "redraw_tui")
segments = (
    "tap_to_route_enter",
    "route_enter_to_first_frame",
    "first_frame_to_first_draw",
    "tap_to_first_draw",
)

def nearest_rank(values, fraction):
    try:
        ordered = sorted(float(value) for value in values)
    except Exception as exc:
        cannot_judge("non-numeric sample: %s" % exc)
    if len(ordered) < 10:
        cannot_judge("sample count below 10")
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]

regressions = []
for fixture in fixtures:
    fixture_data = (data.get("fixtures") or {}).get(fixture)
    if not isinstance(fixture_data, dict):
        cannot_judge("missing fixture %s" % fixture)
    for segment in segments:
        segment_data = fixture_data.get(segment)
        if not isinstance(segment_data, dict):
            cannot_judge("missing segment %s.%s" % (fixture, segment))
        a_values = segment_data.get("A") or []
        b_values = segment_data.get("B") or []
        if len(a_values) < 10 or len(b_values) < 10:
            cannot_judge("samples %s.%s A=%d B=%d" % (
                fixture, segment, len(a_values), len(b_values)
            ))
        for label, fraction in (("p50", 0.50), ("p95", 0.95)):
            a_value = nearest_rank(a_values, fraction)
            b_value = nearest_rank(b_values, fraction)
            if a_value <= 0:
                cannot_judge("nonpositive A %s.%s.%s" % (fixture, segment, label))
            ratio = b_value / a_value
            print("%s %s %s A=%.3f B=%.3f ratio=%.4f" % (
                fixture, segment, label, a_value, b_value, ratio
            ))
            if ratio > 1.10:
                regressions.append("%s.%s.%s=%.4f" % (
                    fixture, segment, label, ratio
                ))

if regressions:
    product_fail("regression above 1.10: " + ", ".join(regressions))
if data.get("measurement") != "pass" or data.get("verdict") != "pass":
    product_fail("recomputed data pass but artifact verdict is not pass")
print("PASS perf-regress: causal focused gate and fresh changed-candidate A/B pass")
sys.exit(0)
PY
json_rc=$?

case "$json_rc" in
    0) ;;
    1) exit 1 ;;
    2) exit 2 ;;
    *) unjudgeable "json judge returned unsupported exit $json_rc" ;;
esac

exit 0
