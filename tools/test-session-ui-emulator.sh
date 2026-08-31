#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../app" && pwd)
TEST_SRC="$ROOT/app/src/androidTest/java/dev/agentmirror/app/session/SessionUiSmokeTest.kt"
PLANNED=(
 defaultDock_rendersExactlyThreeControlsInOrder sessionsMode_replacesSameRowWithoutOverlay sessionsMode_boundsMultipleLongChipsAndKeepsBackReachable sessionsMode_backRestoresDefaultDockWithoutBusinessMutation
 favoritesPageEntry_injectsReconciledFavoritesAndPreservesOrder ordinarySessionListEntry_injectsSameReconciledFavoritesAndPreservesOrder sessionsEmpty_rendersNoFavoriteMessageAndBackWithoutFallback sessionChips_onlineNavigatesOnceOfflineNeverNavigatesAndLongNameEllipsizes
 hotkeys_emitExactSevenTokensAndBackPreservesSession viewDock_opensExistingSheetWithCurrentWorkspaceSource viewSheet_dismissesAndSelectionUsesExistingNavigation input_focusControlsLinesAndPreservesControlledCallbacks input_imeAfterFirstViewportDoesNotAddResizeFrame
 terminalHost_transitionsPreserveIdentityAndBindings theme_twoSchemesUpdateAllChromeValuesAndMeetContrastFloor revertedEighteen_uiSemanticSurfacesRemainAbsent revertedEighteen_protocolAndCallbackSurfacesNeverEmit persistentAcceptanceFixture_rendersDeterministicNavigableStates
)
discovered=($(grep -E '^ *@Test fun [A-Za-z0-9_]+' "$TEST_SRC" | sed -E 's/.*@Test fun ([A-Za-z0-9_]+).*/\1/' | sort))
planned_count=${#PLANNED[@]}; discovered_count=${#discovered[@]}
planned_names=$(printf '%s ' "${PLANNED[@]}"); discovered_names=$(printf '%s ' "${discovered[@]}")
echo "planned_count=$planned_count planned_names=[$planned_names]"
echo "discovered_count=$discovered_count discovered_names=[$discovered_names]"
[[ "$planned_count" -eq 18 && "$discovered_count" -eq 18 ]] || { echo 'inventory count mismatch' >&2; exit 3; }
for n in "${PLANNED[@]}"; do printf '%s\n' "${discovered[@]}" | grep -Fxq "$n" || { echo "missing test $n" >&2; exit 3; }; done
[[ "${#PLANNED[@]}" -eq "${#discovered[@]}" ]] || exit 3
if [[ "${1:-}" == "--list-tests" ]]; then exit 0; fi
if [[ "${1:-}" == "--functional-gate" ]]; then
  mode=${2:-}; [[ "$mode" == prestart ]] || { echo 'functional gate requires prestart'; exit 2; }
  load=$(sysctl -n vm.loadavg 2>/dev/null | awk '{print $2}' || true); load=${load:-unknown}
  free=$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')
  qemu=$(pgrep -f 'qemu-system' | wc -l | tr -d ' ')
  echo "functional_gate=prestart load1=$load free_kb=$free qemu_count=$qemu dead_sockets=informational"
  awk -v x="$load" 'BEGIN {exit !(x != "unknown" && x <= 12.0)}' || exit 2
  [[ "$qemu" == 0 ]] || exit 2
  exit 0
fi
ADB=${ADB:-adb}
if ! command -v "$ADB" >/dev/null 2>&1; then
  echo 'executed_count=0 execution_state=NOT_RUN_NO_DEVICE failed_names=UNAVAILABLE_NOT_RUN rc=2'
  exit 2
fi
serials=()
while IFS= read -r serial; do
  [[ -n "$serial" ]] || continue
  qemu=$("$ADB" -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r' || true)
  [[ "$qemu" == 1 ]] && serials+=("$serial")
done < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ ${#serials[@]} -ne 1 ]]; then
  echo 'executed_count=0 execution_state=NOT_RUN_NO_DEVICE failed_names=UNAVAILABLE_NOT_RUN rc=2'
  exit 2
fi
export ANDROID_SERIAL=${serials[0]}
api=$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')
log="$ROOT/build/session-ui-emulator.log"; mkdir -p "$(dirname "$log")"; start=$(date +%s)
printf 'serial=%s api=%s\n' "$ANDROID_SERIAL" "$api" | tee "$log"
cd "$ROOT"
set +e
./gradlew :app:connectedDebugAndroidTest :app:assembleRelease --rerun-tasks --no-build-cache 2>&1 | tee -a "$log"
gradle_rc=${PIPESTATUS[0]}
set -e
xml_dir="$ROOT/build/outputs/androidTest-results/connected/debug"
executed=$(find "$xml_dir" -name 'TEST-*.xml' -print0 2>/dev/null | xargs -0 grep -h -Eo 'tests="[0-9]+"' | awk -F'"' '{s+=$2} END {print s+0}')
failed=$(find "$xml_dir" -name 'TEST-*.xml' -print0 2>/dev/null | xargs -0 grep -h -c -E '<failure|<error' | awk '{s+=$1} END {print s+0}')
printf 'planned_count=18 discovered_count=18 executed_count=%s failed_names_count=%s gradle_rc=%s duration_seconds=%s\n' "$executed" "$failed" "$gradle_rc" "$(( $(date +%s)-start ))" | tee -a "$log"
[[ "$gradle_rc" -eq 0 && "$executed" -eq 18 && "$failed" -eq 0 ]] || exit 4
