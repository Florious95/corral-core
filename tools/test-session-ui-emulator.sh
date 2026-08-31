#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../app" && pwd)
ADB=${ADB:-adb}
if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "adb unavailable: set ANDROID_HOME or ADB" >&2
  exit 2
fi
serials=()
while IFS= read -r serial; do
  [[ -n "$serial" ]] || continue
  [[ "$(python3 - "$ADB" "$serial" <<'PY'
import subprocess,sys
print(subprocess.run([sys.argv[1],"-s",sys.argv[2],"shell","getprop","ro.kernel.qemu"],capture_output=True,text=True,timeout=20,check=True).stdout.strip())
PY
)" == "1" ]] && serials+=("$serial")
done < <(python3 - "$ADB" <<'PY'
import subprocess,sys
p=subprocess.run([sys.argv[1],"devices"],capture_output=True,text=True,timeout=20,check=True)
for line in p.stdout.splitlines()[1:]:
    parts=line.split()
    if len(parts)==2 and parts[1]=="device": print(parts[0])
PY
)
[[ ${#serials[@]} -eq 1 ]] || { echo "expected exactly one Android emulator, found ${#serials[@]}" >&2; exit 2; }
export ANDROID_SERIAL=${serials[0]}
api=$(python3 - "$ADB" "$ANDROID_SERIAL" <<'PY'
import subprocess,sys
print(subprocess.run([sys.argv[1],"-s",sys.argv[2],"shell","getprop","ro.build.version.sdk"],capture_output=True,text=True,timeout=20,check=True).stdout.strip())
PY
)
log="$ROOT/build/session-ui-emulator.log"
mkdir -p "$(dirname "$log")"
planned='SessionUiSmokeTest#realComposeDockAndFavoritesSemantics SessionUiSmokeTest#favoriteSourceExcludesCurrentForBothEntryFixturesAndEmptyState'
printf 'serial=%s api=%s planned=%s\ncommand=./gradlew :app:connectedDebugAndroidTest :app:assembleRelease --rerun-tasks --no-build-cache\n' "$ANDROID_SERIAL" "$api" "$planned" | tee "$log"
cd "$ROOT"
start=$(date +%s)
python3 - "$log" <<'PY'
import subprocess,sys
with open(sys.argv[1],"a") as log:
    p=subprocess.run(["./gradlew",":app:connectedDebugAndroidTest",":app:assembleRelease","--rerun-tasks","--no-build-cache"],stdout=log,stderr=subprocess.STDOUT,text=True,timeout=900)
    raise SystemExit(p.returncode)
PY
cat "$log"
xml_count=$(find "$ROOT/build/outputs/androidTest-results" -name 'TEST-*.xml' -print0 2>/dev/null | xargs -0 grep -h -Eo 'tests="[0-9]+"' | awk -F'"' '{s+=$2} END {print s+0}')
failed_count=$(find "$ROOT/build/outputs/androidTest-results" -name 'TEST-*.xml' -print0 2>/dev/null | xargs -0 grep -h -c -E '<failure|<error' | awk '{s+=$1} END {print s+0}')
[[ "$xml_count" -gt 0 ]] || { echo 'no executed instrumentation tests' >&2; exit 3; }
[[ "$xml_count" -eq 2 ]] || { echo "planned/discovered mismatch: expected 2 got $xml_count" >&2; exit 3; }
printf 'duration_seconds=%s planned_count=2 discovered_count=%s executed_count=%s failed_names=%s\n' "$(( $(date +%s) - start ))" "$xml_count" "$xml_count" "count=$failed_count" | tee -a "$log"
[[ "$failed_count" -eq 0 ]] || { echo 'failed instrumentation tests' >&2; exit 4; }
