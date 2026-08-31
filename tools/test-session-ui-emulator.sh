#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../app" && pwd)
ADB=${ADB:-adb}
mapfile -t devices < <(python3 - "$ADB" <<'PY'
import subprocess,sys
p=subprocess.run([sys.argv[1],"devices"],capture_output=True,text=True,timeout=20,check=True)
for line in p.stdout.splitlines()[1:]:
    parts=line.split()
    if len(parts)==2 and parts[1]=="device": print(parts[0])
PY
)
serials=()
for serial in "${devices[@]}"; do
  [[ "$(python3 - "$ADB" "$serial" <<'PY'
import subprocess,sys
print(subprocess.run([sys.argv[1],"-s",sys.argv[2],"shell","getprop","ro.kernel.qemu"],capture_output=True,text=True,timeout=20,check=True).stdout.strip())
PY
)" == "1" ]] && serials+=("$serial")
done
[[ ${#serials[@]} -eq 1 ]] || { echo "expected exactly one Android emulator, found ${#serials[@]}" >&2; exit 2; }
export ANDROID_SERIAL=${serials[0]}
api=$(python3 - "$ADB" "$ANDROID_SERIAL" <<'PY'
import subprocess,sys
print(subprocess.run([sys.argv[1],"-s",sys.argv[2],"shell","getprop","ro.build.version.sdk"],capture_output=True,text=True,timeout=20,check=True).stdout.strip())
PY
)
log="$ROOT/build/session-ui-emulator.log"
mkdir -p "$(dirname "$log")"
printf 'serial=%s api=%s\ncommand=./gradlew :app:connectedDebugAndroidTest :app:assembleRelease --rerun-tasks --no-build-cache\n' "$ANDROID_SERIAL" "$api" | tee "$log"
cd "$ROOT"
start=$(date +%s)
python3 - "$log" <<'PY'
import subprocess,sys
with open(sys.argv[1],"a") as log:
    p=subprocess.run(["./gradlew",":app:connectedDebugAndroidTest",":app:assembleRelease","--rerun-tasks","--no-build-cache"],stdout=log,stderr=subprocess.STDOUT,text=True,timeout=900)
    raise SystemExit(p.returncode)
PY
cat "$log"
count=$(grep -Eo '[0-9]+ tests? completed' "$log" | tail -1 | awk '{print $1}')
[[ "${count:-0}" -gt 0 ]] || { echo 'no executed instrumentation tests' >&2; exit 3; }
printf 'duration_seconds=%s executed_tests=%s\n' "$(( $(date +%s) - start ))" "$count" | tee -a "$log"
