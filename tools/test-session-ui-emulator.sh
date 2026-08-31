#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../app" && pwd)
TEST_SRC="$ROOT/app/src/androidTest/java/dev/agentmirror/app/session/SessionUiSmokeTest.kt"
PLANNED=(defaultDock_rendersExactlyThreeControlsInOrder sessionsMode_replacesSameRowWithoutOverlay sessionsMode_boundsMultipleLongChipsAndKeepsBackReachable sessionsMode_backRestoresDefaultDockWithoutBusinessMutation favoritesPageEntry_injectsReconciledFavoritesAndPreservesOrder ordinarySessionListEntry_injectsSameReconciledFavoritesAndPreservesOrder sessionsEmpty_rendersNoFavoriteMessageAndBackWithoutFallback sessionChips_onlineNavigatesOnceOfflineNeverNavigatesAndLongNameEllipsizes hotkeys_emitExactSevenTokensAndBackPreservesSession viewDock_opensExistingSheetWithCurrentWorkspaceSource viewSheet_dismissesAndSelectionUsesExistingNavigation input_focusControlsLinesAndPreservesControlledCallbacks input_imeAfterFirstViewportDoesNotAddResizeFrame terminalHost_transitionsPreserveIdentityAndBindings theme_twoSchemesUpdateAllChromeValuesAndMeetContrastFloor revertedEighteen_uiSemanticSurfacesRemainAbsent revertedEighteen_protocolAndCallbackSurfacesNeverEmit persistentAcceptanceFixture_rendersDeterministicNavigableStates)
discovered=($(grep -E '^ *@Test fun [A-Za-z0-9_]+' "$TEST_SRC" | sed -E 's/.*@Test fun ([A-Za-z0-9_]+).*/\1/' | sort))
planned_count=${#PLANNED[@]}; discovered_count=${#discovered[@]}; planned_names=$(printf '%s ' "${PLANNED[@]}"); discovered_names=$(printf '%s ' "${discovered[@]}")
echo "planned_count=$planned_count planned_names=[$planned_names]"; echo "discovered_count=$discovered_count discovered_names=[$discovered_names]"
[[ "$planned_count" -eq 18 && "$discovered_count" -eq 18 ]] || { echo 'inventory mismatch'; exit 3; }
for n in "${PLANNED[@]}"; do printf '%s\n' "${discovered[@]}" | grep -Fxq "$n" || { echo "missing=$n"; exit 3; }; done
if [[ "${1:-}" == "--list-tests" ]]; then exit 0; fi
if [[ "${1:-}" == "--static-contract" ]]; then
  hits=0
  for f in SessionScreenIntegrationFixture.kt SessionUiTestTransport.kt SessionImeIntegrationTest.kt SessionTermSurfaceIdentityTest.kt SessionThemePersistenceTest.kt SessionNegativeBoundaryTest.kt; do
    p="$ROOT/app/src/androidTest/java/dev/agentmirror/app/session/$f"; echo "constant_adapter file=$f"; h=$(grep -nE 'const val|object .*\{|record\(' "$p" || true); printf '%s\n' "$h"; [[ -z "$h" ]] || hits=$((hits+1))
  done
  echo "empty_callback scan=SessionUiSmokeTest.kt"; h=$(grep -nE 'on[A-Za-z]+ *= *\{\}' "$TEST_SRC" || true); printf '%s\n' "$h"; [[ -z "$h" ]] || hits=$((hits+1))
  echo "static_contract total_hits=$hits"; exit "$([[ $hits -eq 0 ]] && echo 0 || echo 3)"
fi
if [[ "${1:-}" == "--functional-gate" ]]; then
  load=$(sysctl -n vm.loadavg 2>/dev/null | awk '{print $2}' || true); load=${load:-unknown}; free=$(df -Pk "$ROOT" 2>/dev/null | awk 'NR==2 {print $4}' || true); free=${free:-unknown}; qemu_pids=$(pgrep -f qemu-system 2>/dev/null || true); qemu_count=$(printf '%s\n' "$qemu_pids" | awk 'NF {n++} END {print n+0}')
  echo "functional_gate=prestart cwd=$(pwd) branch=$(git -C "$ROOT/.." branch --show-current) head=$(git -C "$ROOT/.." rev-parse HEAD) sdk=${ANDROID_HOME:-unknown}"; echo "raw_inventory=planned_count=$planned_count discovered_count=$discovered_count"; echo "raw_load1=$load raw_disk_free_kb=$free raw_qemu_pids=[$qemu_pids] qemu_count=$qemu_count dead_tmux_sockets=informational"; awk -v x="$load" 'BEGIN {exit !(x != "unknown" && x <= 12.0)}' || exit 2; [[ "$qemu_count" == 0 ]] || exit 2; exit 0
fi
ADB=${ADB:-adb}; command -v "$ADB" >/dev/null 2>&1 || { echo 'executed_count=0 execution_state=NOT_RUN_NO_DEVICE'; exit 2; }; echo 'executed_count=0 execution_state=NOT_RUN_NO_DEVICE'; exit 2
