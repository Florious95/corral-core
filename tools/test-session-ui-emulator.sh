#!/usr/bin/env bash
set -euo pipefail

WORKTREE=$(cd "$(dirname "$0")/.." && pwd)
APP="$WORKTREE/app"
TEST_SRC="$APP/app/src/androidTest/java/dev/agentmirror/app/session/SessionUiSmokeTest.kt"
NAMES=(defaultDockAndHotkeys sessionsRowFiltersNavigatesAndReturns sessionsSourceIsEntryIndependentAndEmptyNeverFallsBack viewUsesExistingCurrentWorkspaceSheet controlledInputAndIme terminalHostStaysSameAcrossDockModes terminalThemeChangesSessionChrome)
actual=$(grep -E '^ *fun (defaultDockAndHotkeys|sessionsRowFiltersNavigatesAndReturns|sessionsSourceIsEntryIndependentAndEmptyNeverFallsBack|viewUsesExistingCurrentWorkspaceSheet|controlledInputAndIme|terminalHostStaysSameAcrossDockModes|terminalThemeChangesSessionChrome)\(' "$TEST_SRC" | sed -E 's/^ *fun ([^(]+).*/\1/' | sort)
expected=$(printf '%s\n' "${NAMES[@]}" | sort)
found_count=$(printf '%s\n' "$actual" | grep -c .)
[[ $found_count -eq 7 && "$actual" == "$expected" ]] || { echo "test_inventory_mismatch" >&2; exit 3; }
echo "planned_count=7 discovered_count=7 names=${NAMES[*]}"
[[ "${1:-}" == "--list-tests" ]] && exit 0

cd "$WORKTREE"
[[ $(git branch --show-current) == pr/session-ui-shell ]] || { echo "wrong_branch" >&2; exit 4; }
git diff --quiet && git diff --cached --quiet || { echo "tracked_worktree_not_clean" >&2; exit 4; }
source_sha=$(git rev-parse HEAD)
[[ $source_sha =~ ^[0-9a-f]{40}$ ]] || exit 4

ADB=${ADB:-adb}
command -v "$ADB" >/dev/null || { echo "execution_state=NOT_RUN_NO_ADB" >&2; exit 2; }
emulators=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | while read -r candidate; do
    [[ $("$ADB" -s "$candidate" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r') == 1 ]] && printf '%s\n' "$candidate"
done)
emulator_count=$(printf '%s\n' "$emulators" | grep -c . || true)
[[ $emulator_count -eq 1 ]] || { echo "execution_state=NOT_RUN_REQUIRE_UNIQUE_EMULATOR count=$emulator_count" >&2; exit 2; }
serial=${ANDROID_SERIAL:-$emulators}
[[ $serial == "$emulators" ]] || { echo "selected_serial_mismatch" >&2; exit 2; }
[[ $("$ADB" -s "$serial" get-state) == device ]] || exit 2
[[ $("$ADB" -s "$serial" shell getprop sys.boot_completed | tr -d '\r') == 1 ]] || { echo "execution_state=NOT_RUN_NOT_BOOTED" >&2; exit 2; }
api=$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
export ANDROID_SERIAL="$serial"

cd "$APP"
./gradlew :app:connectedDebugAndroidTest :app:assembleDebug :app:assembleRelease --rerun-tasks --no-build-cache

xml_root="$APP/app/build/outputs/androidTest-results/connected/debug"
parsed=$(python3 - "$xml_root" "${NAMES[@]}" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1]); expected = sorted(sys.argv[2:])
files = list(root.rglob("*.xml")) if root.is_dir() else []
if not files: raise SystemExit("missing_junit_xml")
seen=[]; failed=[]; skipped=[]; duration=0.0
for path in files:
    try: tree=ET.parse(path)
    except ET.ParseError: raise SystemExit(f"bad_junit_xml={path}")
    for case in tree.iter("testcase"):
        name=case.attrib.get("name", "").split("[")[0]
        if name in expected:
            seen.append(name); duration += float(case.attrib.get("time", "0") or 0)
            if case.find("failure") is not None or case.find("error") is not None: failed.append(name)
            if case.find("skipped") is not None: skipped.append(name)
if sorted(seen) != expected or len(seen) != 7: raise SystemExit(f"executed_name_mismatch={seen}")
if failed or skipped: raise SystemExit(f"failed={failed} skipped={skipped}")
print(f"executed_count=7 failed_names=[] skipped_names=[] duration_seconds={duration:.3f} executed_names={','.join(seen)}")
PY
)
echo "$parsed"

debug_apk="$APP/app/build/outputs/apk/debug/app-debug.apk"
release_apk="$APP/app/build/outputs/apk/release/app-release.apk"
[[ -f $debug_apk && -f $release_apk ]] || { echo "missing_apk" >&2; exit 5; }
debug_sha=$(shasum -a 256 "$debug_apk" | awk '{print $1}')
"$ADB" -s "$serial" install -r "$debug_apk" >/dev/null
package=dev.agentmirror.app
component=dev.agentmirror.app/.SessionUiAcceptanceActivity
"$ADB" -s "$serial" shell pm path "$package" | grep -q '^package:' || { echo "package_not_installed" >&2; exit 5; }
resolved=$("$ADB" -s "$serial" shell cmd package resolve-activity --brief -c android.intent.category.DEFAULT "$component" | tr -d '\r')
[[ $resolved == *SessionUiAcceptanceActivity* ]] || { echo "component_not_resolvable=$resolved" >&2; exit 5; }
start_out=$("$ADB" -s "$serial" shell am start -W -n "$component" --es session_ui_fixture full --es entry favorites --es source_sha "$source_sha")
printf '%s\n' "$start_out"
grep -Eq 'Status: ok|Complete' <<<"$start_out" || { echo "activity_start_failed" >&2; exit 5; }
resumed=""
for _ in $(seq 1 40); do
    resumed=$("$ADB" -s "$serial" shell dumpsys activity activities | grep -m1 'mResumedActivity' || true)
    [[ $resumed == *"dev.agentmirror.app/.SessionUiAcceptanceActivity"* ]] && break
done
[[ $resumed == *"dev.agentmirror.app/.SessionUiAcceptanceActivity"* ]] || { echo "foreground_component_mismatch=$resumed" >&2; exit 5; }

remote_xml=/sdcard/session-ui.xml
"$ADB" -s "$serial" shell uiautomator dump "$remote_xml" >/dev/null
ui_xml=$("$ADB" -s "$serial" exec-out cat "$remote_xml")
state=$(grep -o 'content-desc="session-ui-state[^"<]*"' <<<"$ui_xml" | head -1 || true)
[[ $state == *"source_sha=$source_sha"* && $state == *"source_valid=true"* && $state == *"fixture=full"* ]] || { echo "fixture_state_binding_failed=$state" >&2; exit 5; }
[[ $state == *"package=$package"* && $state == *"component=$component"* && $state == *"current_ref=current"* && $state == *"terminal_count=1"* ]] || { echo "fixture_state_incomplete=$state" >&2; exit 5; }

release_manifest="$APP/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
[[ -f $release_manifest ]] || { echo "missing_release_manifest" >&2; exit 5; }
dex_dump="$APP/app/build/session-ui-release-dex.bin"
unzip -p "$release_apk" '*.dex' > "$dex_dump"
for symbol in SessionUiAcceptanceActivity LocalSessionTransport DebugSessionFixture DebugSessionSnapshot; do
    ! grep -aFq "$symbol" "$release_manifest" || { echo "release_manifest_contains=$symbol" >&2; exit 5; }
    ! grep -aFq "$symbol" "$dex_dump" || { echo "release_apk_contains=$symbol" >&2; exit 5; }
done
rm -f "$dex_dump"

out="$HOME/Downloads/agentmirror-session-ui-${source_sha:0:12}-release.apk"
cp "$release_apk" "$out"
release_sha=$(shasum -a 256 "$out" | awk '{print $1}')
size=$(stat -f %z "$out")
echo "head=$source_sha serial=$serial api=$api package=$package component=$component debug_apk_sha256=$debug_sha"
echo "release_apk=$out size=$size release_apk_sha256=$release_sha"
echo "fixture_state=$state"
