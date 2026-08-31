#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../app" && pwd)
SRC="$ROOT/app/src/androidTest/java/dev/agentmirror/app/session/SessionUiSmokeTest.kt"
NAMES=(defaultDockAndHotkeys sessionsRowFiltersNavigatesAndReturns sessionsSourceIsEntryIndependentAndEmptyNeverFallsBack viewUsesExistingCurrentWorkspaceSheet controlledInputAndIme terminalHostStaysSameAcrossDockModes terminalThemeChangesSessionChrome)
found=($(grep -E '^ *@Test fun [A-Za-z0-9_]+' "$SRC" | sed -E 's/.*@Test fun ([A-Za-z0-9_]+).*/\1/' | sort))
echo "planned_count=7 discovered_count=${#found[@]} planned_names=${NAMES[*]} discovered_names=${found[*]}"
[[ ${#found[@]} -eq 7 ]] || exit 3
for n in "${NAMES[@]}"; do printf '%s\n' "${found[@]}" | grep -Fxq "$n" || exit 3; done
[[ "${1:-}" == --list-tests ]] && exit 0
ADB=${ADB:-adb}; command -v "$ADB" >/dev/null 2>&1 || { echo 'execution_state=NOT_RUN_NO_DEVICE executed_count=0'; exit 2; }
serials=(); while read -r s state _; do [[ "$state" == device ]] || continue; [[ "$($ADB -s "$s" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" == 1 ]] && serials+=("$s"); done < <("$ADB" devices)
[[ ${#serials[@]} -eq 1 ]] || { echo 'execution_state=NOT_RUN_NO_DEVICE executed_count=0'; exit 2; }
export ANDROID_SERIAL=${serials[0]}; cd "$ROOT"; ./gradlew :app:connectedDebugAndroidTest :app:assembleRelease --rerun-tasks --no-build-cache
