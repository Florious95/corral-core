#!/usr/bin/env bash
# fix-ts-state-dir-e2e: isolated headscale + daemon + Android API proof.
# The Android leg is a generated, task-owned Instrumentation. It calls the
# pairing, native tsnet, and ConnectionManager APIs directly; no Activity,
# camera, input event, UI dump, screenshot, or window operation participates.

set -euo pipefail
set +x

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RUN_ID="run-$(date '+%Y%m%dT%H%M%S')-$$"
ARTIFACT_DIR="$ROOT/e2e/artifacts/fix-ts-state-dir-e2e/$RUN_ID"
mkdir -p "$ARTIFACT_DIR"

ADB=/Users/alauda/Library/Android/sdk/platform-tools/adb
HEADSCALE=/private/tmp/headscale
TMUX_BIN=/opt/homebrew/bin/tmux
SANDBOX_EXEC=/usr/bin/sandbox-exec
LSOF=/usr/sbin/lsof
GRADLEW="$ROOT/app/gradlew"
PACKAGE=dev.agentmirror.app
TEST_PACKAGE=dev.agentmirror.app.fixstatee2e
TEST_RUNNER=dev.agentmirror.app.e2e.ApiE2ERunner
DEBUG_APK="$ROOT/app/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

HS_PORT=43910
HS_METRICS_PORT=43911
HS_GRPC_PORT=43912
HS_STUN_PORT=43913
DERP_PORT=43914
DAEMON_PORT=43920

TASK_TMP=""
HEADSCALE_PID=""
DERP_PID=""
DAEMON_PID=""
TASK_TMUX_PID=""
TASK_TMUX_PANE_PID=""
ANDROID_USER=""
ANDROID_USER_NAME="fix-ts-state-e2e-$$"
ANDROID_USER_CREATE_ATTEMPTED=no
ANDROID_DATA_FORCED_CLEANUP=no
ANDROID_ORIGINAL_USER=""
APP_UID=""
APP_PID=""
INSTRUMENT_HOST_PID=""
SECRET_FEED_PID=""
ADB_REVERSE_ADDED=no
ADB_DERP_REVERSE_ADDED=no
TMUX_ROOT=""
TMUX_LABEL="fix-ts-state-dir-e2e-$$"
PRODUCTION_SNAPSHOT=""
PRODUCTION_BASELINE_COUNT=0
AUTH_KEY=""
PAIR_TOKEN=""
DAEMON_TAILNET_IP=""
PAIRING_URL=""

DEVICE_PREFIX="/data/local/tmp/fix-ts-state-dir-e2e-$$"
DEVICE_TEST_APK="$DEVICE_PREFIX-test.apk"
DEVICE_WRAP_SCRIPT=""
DEVICE_SECRET_FIFO=""
WRAP_COMMAND=""
WRAP_PROPERTY="wrap.$PACKAGE"
WRAP_PROPERTY_SET=no
TEST_PACKAGE_INSTALLED=no

RESULT_STATUS=fail
FAILURE_REASON=not_completed
NODE_COUNT=0
APP_REGISTRATION_OBSERVATION=not_observed
STATE_DIR_PROOF=no
APP_TSNET_STATE_PROOF=unknown
PAIRING_ENTRY_PROOF=unknown
CONNECTION_MANAGER_PROOF=unknown
WORKSPACE_VISIBLE=no
SESSION_VISIBLE=no
SOCKS_LOOPBACK_PROOF=unknown
DERP_LISTENER_PROOF=unknown
DERP_ROUTE_PROOF=unknown
DERP_ACCEPTS=0
DERP_PACKETS_RECEIVED=0
DERP_PACKETS_SENT=0
ARGV_AUTHKEY_FLAG=unknown
ARGV_AUTHKEY_VALUE=unknown
ARGV_AUTHKEY_SHAPE=unknown
ARGV_PAIR_TOKEN_VALUE=unknown
APP_ARGV_AUTHKEY_VALUE=unknown
APP_ARGV_AUTHKEY_SHAPE=unknown
APP_ARGV_PAIR_TOKEN_VALUE=unknown
APP_LOG_SECRET_VALUE=unknown
ARTIFACT_SECRET_LEAK=unknown
BASELINE_APK_FILE=""
BASELINE_APK_HASH=""
APP_APK_REPLACE_ATTEMPTED=no
APP_APK_RESTORED=yes

die() {
  FAILURE_REASON=$1
  exit 1
}

port_count() {
  local proto=$1 port=$2
  if [ "$proto" = tcp ]; then
    "$LSOF" -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | tail -n +2 | wc -l | tr -d ' '
  else
    "$LSOF" -nP -iUDP:"$port" 2>/dev/null | tail -n +2 | wc -l | tr -d ' '
  fi
}

stop_owned_pid() {
  local pid=$1 marker=$2 cmd="" i
  [ -n "$pid" ] || return 0
  case "$pid" in *[!0-9]*) return 0 ;; esac
  cmd=$(ps -p "$pid" -o command= 2>/dev/null || true)
  [ -n "$cmd" ] || return 0
  case "$cmd" in *"$marker"*) kill -TERM "$pid" 2>/dev/null || true ;; *) return 0 ;; esac
  for i in 1 2 3 4 5; do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" 2>/dev/null || true
      return 0
    fi
    sleep 1
  done
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

cleanup() {
  local incoming_rc=$? final_rc cleanup_ok=yes open_files=0 tmp_absent=no
  local daemon_absent=yes headscale_absent=yes derper_absent=yes tmux_absent=yes tmux_pane_absent=yes
  local android_user_absent=yes android_data_absent=yes reverse_absent=yes
  local wrap_property_absent=yes device_files_absent=yes test_package_absent=yes
  local original_user_unchanged=yes production_untouched=yes derp_private_keys_absent=yes
  local restored_apk_path="" restored_apk_hash="" current_user="" user_list=""
  local reverse_list="" current_wrap="" test_path="" data_exists=no
  local p tcp udp expected_identity current_identity logcat_rc
  trap - EXIT INT TERM ERR
  set +e

  if [ "$WRAP_PROPERTY_SET" = yes ] && [ -x "$ADB" ]; then
    "$ADB" -s emulator-5554 shell \
      "su 0 sh -c 'setprop $WRAP_PROPERTY \"\"'" >/dev/null 2>&1 || true
    WRAP_PROPERTY_SET=no
  fi
  if [ -n "$SECRET_FEED_PID" ]; then
    kill -TERM "$SECRET_FEED_PID" 2>/dev/null || true
    wait "$SECRET_FEED_PID" 2>/dev/null || true
  fi
  stop_owned_pid "$INSTRUMENT_HOST_PID" "$TEST_PACKAGE/$TEST_RUNNER"

  if [ -x "$ADB" ]; then
    if [ -z "$ANDROID_USER" ] && [ "$ANDROID_USER_CREATE_ATTEMPTED" = yes ]; then
      user_list=$("$ADB" -s emulator-5554 shell pm list users 2>/dev/null) || user_list=""
      ANDROID_USER=$(printf '%s\n' "$user_list" | sed -n "s/.*UserInfo{\([0-9][0-9]*\):$ANDROID_USER_NAME:.*/\1/p" | tail -1)
    fi
    if [ -n "$ANDROID_USER" ]; then
      "$ADB" -s emulator-5554 shell am force-stop --user "$ANDROID_USER" "$PACKAGE" >/dev/null 2>&1 || true
      "$ADB" -s emulator-5554 shell am force-stop --user "$ANDROID_USER" "$TEST_PACKAGE" >/dev/null 2>&1 || true
    fi

    # Persist only a bounded, in-stream-redacted log for the exact task PID.
    if [ -n "$APP_PID" ] && [ -n "$TASK_TMP" ] && [ -d "$TASK_TMP" ]; then
      APP_LOG_SECRET_VALUE=$("$ADB" -s emulator-5554 logcat -d --pid "$APP_PID" 2>/dev/null | \
        TS_AUTHKEY="$AUTH_KEY" PAIR_TOKEN_SECRET="$PAIR_TOKEN" /opt/homebrew/bin/python3 -c '
import os, pathlib, sys
data = sys.stdin.buffer.read()
secrets = [v.encode() for v in (os.environ.get("TS_AUTHKEY", ""), os.environ.get("PAIR_TOKEN_SECRET", "")) if v]
found = any(v in data for v in secrets)
for v in secrets:
    data = data.replace(v, b"[REDACTED]")
pathlib.Path(sys.argv[1]).write_bytes(data[-32000:])
print("yes" if found else "no")
' "$TASK_TMP/app-logcat-redacted.log")
      logcat_rc=$?
      if [ "$logcat_rc" -ne 0 ]; then
        APP_LOG_SECRET_VALUE=unknown
        cleanup_ok=no
        FAILURE_REASON=app_logcat_probe_failed
      elif [ "$APP_LOG_SECRET_VALUE" = yes ]; then
        cleanup_ok=no
        FAILURE_REASON=secret_value_found_in_app_logcat
      fi
    fi

    if [ "$TEST_PACKAGE_INSTALLED" = yes ]; then
      "$ADB" -s emulator-5554 shell su 0 pm uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    fi
    "$ADB" -s emulator-5554 shell su 0 rm -f "$DEVICE_TEST_APK" >/dev/null 2>&1 || true
    if [ -n "$DEVICE_WRAP_SCRIPT" ] && [ -n "$DEVICE_SECRET_FIFO" ]; then
      "$ADB" -s emulator-5554 shell su 0 rm -f \
        "$DEVICE_WRAP_SCRIPT" "$DEVICE_SECRET_FIFO" >/dev/null 2>&1 || true
    fi
    if [ "$ADB_REVERSE_ADDED" = yes ]; then
      "$ADB" -s emulator-5554 reverse --remove tcp:"$HS_PORT" >/dev/null 2>&1 || true
    fi
    if [ "$ADB_DERP_REVERSE_ADDED" = yes ]; then
      "$ADB" -s emulator-5554 reverse --remove tcp:"$DERP_PORT" >/dev/null 2>&1 || true
    fi

    if [ -n "$ANDROID_USER" ]; then
      for _ in $(seq 1 20); do
        user_list=$("$ADB" -s emulator-5554 shell pm list users 2>/dev/null) || user_list=""
        printf '%s\n' "$user_list" | grep -q "UserInfo{$ANDROID_USER:" || break
        "$ADB" -s emulator-5554 shell am stop-user -w -f "$ANDROID_USER" >/dev/null 2>&1 || true
        "$ADB" -s emulator-5554 shell pm remove-user --wait "$ANDROID_USER" >/dev/null 2>&1 || true
        sleep 1
      done
      if user_list=$("$ADB" -s emulator-5554 shell pm list users 2>/dev/null) && \
         ! printf '%s\n' "$user_list" | grep -q "UserInfo{$ANDROID_USER:"; then
        for _ in $(seq 1 40); do
          data_exists=no
          for p in \
            "/data/user/$ANDROID_USER/$PACKAGE" "/data/user_de/$ANDROID_USER/$PACKAGE" \
            "/data/user/$ANDROID_USER/$TEST_PACKAGE" "/data/user_de/$ANDROID_USER/$TEST_PACKAGE"; do
            "$ADB" -s emulator-5554 shell su 0 /system/bin/ls -d "$p" >/dev/null 2>&1 && data_exists=yes
          done
          [ "$data_exists" = no ] && break
          sleep 0.5
        done
        if [ "$data_exists" = yes ]; then
          ANDROID_DATA_FORCED_CLEANUP=yes
          for p in \
            "/data/user/$ANDROID_USER/$PACKAGE" "/data/user_de/$ANDROID_USER/$PACKAGE" \
            "/data/user/$ANDROID_USER/$TEST_PACKAGE" "/data/user_de/$ANDROID_USER/$TEST_PACKAGE"; do
            "$ADB" -s emulator-5554 shell su 0 /system/bin/rm -rf "$p" >/dev/null 2>&1 || true
          done
        fi
        for p in \
          "/data/user/$ANDROID_USER/$PACKAGE" "/data/user_de/$ANDROID_USER/$PACKAGE" \
          "/data/user/$ANDROID_USER/$TEST_PACKAGE" "/data/user_de/$ANDROID_USER/$TEST_PACKAGE"; do
          "$ADB" -s emulator-5554 shell su 0 /system/bin/ls -d "$p" >/dev/null 2>&1 && android_data_absent=no
        done
      else
        android_data_absent=no
      fi
    fi

    if [ "$APP_APK_REPLACE_ATTEMPTED" = yes ]; then
      APP_APK_RESTORED=no
      if [ -n "$BASELINE_APK_FILE" ] && [ -f "$BASELINE_APK_FILE" ] && \
         "$ADB" -s emulator-5554 install -r -d "$BASELINE_APK_FILE" \
           >"$TASK_TMP/app-restore.log" 2>&1; then
        restored_apk_path=$("$ADB" -s emulator-5554 shell pm path "$PACKAGE" 2>/dev/null \
          | sed -n 's/^package://p' | head -1 | tr -d '\r')
        if [ -n "$restored_apk_path" ]; then
          restored_apk_hash=$("$ADB" -s emulator-5554 exec-out cat "$restored_apk_path" \
            | shasum -a 256 | awk '{print $1}')
          [ "$restored_apk_hash" = "$BASELINE_APK_HASH" ] && APP_APK_RESTORED=yes
        fi
      fi
    fi
  fi

  if [ -n "$TMUX_ROOT" ] && [ -x "$TMUX_BIN" ]; then
    TMUX= TMUX_TMPDIR="$TMUX_ROOT" "$TMUX_BIN" -f /dev/null -L "$TMUX_LABEL" kill-server >/dev/null 2>&1 || true
  fi
  stop_owned_pid "$DAEMON_PID" "$TASK_TMP/bin/agentmirrord"
  stop_owned_pid "$HEADSCALE_PID" "$TASK_TMP/headscale.yaml"
  stop_owned_pid "$DERP_PID" "$TASK_TMP/derper.json"
  [ -z "$DAEMON_PID" ] || ! kill -0 "$DAEMON_PID" 2>/dev/null || daemon_absent=no
  [ -z "$HEADSCALE_PID" ] || ! kill -0 "$HEADSCALE_PID" 2>/dev/null || headscale_absent=no
  [ -z "$DERP_PID" ] || ! kill -0 "$DERP_PID" 2>/dev/null || derper_absent=no
  [ -z "$TASK_TMUX_PID" ] || ! kill -0 "$TASK_TMUX_PID" 2>/dev/null || tmux_absent=no
  [ -z "$TASK_TMUX_PANE_PID" ] || ! kill -0 "$TASK_TMUX_PANE_PID" 2>/dev/null || tmux_pane_absent=no

  if [ -n "$TASK_TMP" ] && [ -d "$TASK_TMP" ]; then
    if ! TS_AUTHKEY="$AUTH_KEY" PAIR_TOKEN_SECRET="$PAIR_TOKEN" /opt/homebrew/bin/python3 \
      - "$TASK_TMP" "$ARTIFACT_DIR/diagnostics.log" <<'PY'
import os, pathlib, sys
root, dst = map(pathlib.Path, sys.argv[1:])
names = (
    "headscale-configtest.log", "headscale.stderr", "headscale-cli.stderr", "derper.stderr",
    "sandbox-check.log", "go-build.log", "daemon.stderr",
    "app-install.log", "app-test-install.log", "app-wrapper-prepare.log", "app-restore.log",
    "instrumentation.log", "app-logcat-redacted.log",
)
secrets = (os.environ.get("TS_AUTHKEY", ""), os.environ.get("PAIR_TOKEN_SECRET", ""))
parts = []
for name in names:
    path = root / name
    if not path.is_file():
        continue
    text = path.read_text(errors="replace")[-16000:]
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    parts.append(f"## {name}\n{text.replace(str(root), 'TASK_TMP')}\n")
dst.write_text("\n".join(parts))
PY
    then
      cleanup_ok=no
      FAILURE_REASON=diagnostic_redaction_failed
    fi
    open_files=$("$LSOF" +D "$TASK_TMP" 2>/dev/null | tail -n +2 | wc -l | tr -d ' ')
    case "$TASK_TMP" in /private/tmp/fix-ts-state-dir-e2e.*) rm -rf -- "$TASK_TMP" ;; *) cleanup_ok=no ;; esac
  fi
  [ -z "$TASK_TMP" ] && tmp_absent=yes
  [ -n "$TASK_TMP" ] && [ ! -e "$TASK_TMP" ] && tmp_absent=yes
  if [ -n "$TASK_TMP" ]; then
    for p in "$TASK_TMP/derper.json" "$TASK_TMP/derper-certs/127.0.0.1.key" \
             "$TASK_TMP/hs/derp_private.key"; do
      [ ! -e "$p" ] || derp_private_keys_absent=no
    done
  fi

  if [ -x "$ADB" ]; then
    current_wrap=$("$ADB" -s emulator-5554 shell su 0 getprop "$WRAP_PROPERTY" 2>/dev/null | tr -d '\r')
    [ -z "$current_wrap" ] || wrap_property_absent=no
    for p in "$DEVICE_TEST_APK"; do
      "$ADB" -s emulator-5554 shell su 0 test ! -e "$p" >/dev/null 2>&1 || device_files_absent=no
    done
    if [ -n "$DEVICE_WRAP_SCRIPT" ] && [ -n "$DEVICE_SECRET_FIFO" ]; then
      for p in "$DEVICE_WRAP_SCRIPT" "$DEVICE_SECRET_FIFO"; do
        "$ADB" -s emulator-5554 shell su 0 test ! -e "$p" >/dev/null 2>&1 || device_files_absent=no
      done
    fi
    test_path=$("$ADB" -s emulator-5554 shell pm path "$TEST_PACKAGE" 2>/dev/null | tr -d '\r')
    [ -z "$test_path" ] || test_package_absent=no
    if [ "$ANDROID_USER_CREATE_ATTEMPTED" = yes ]; then
      if user_list=$("$ADB" -s emulator-5554 shell pm list users 2>/dev/null); then
        if { [ -n "$ANDROID_USER" ] && printf '%s\n' "$user_list" | grep -q "UserInfo{$ANDROID_USER:"; } || \
           printf '%s\n' "$user_list" | grep -q ":$ANDROID_USER_NAME:"; then
          android_user_absent=no
        fi
      else
        android_user_absent=no
      fi
    fi
    if [ "$ADB_REVERSE_ADDED" = yes ] || [ "$ADB_DERP_REVERSE_ADDED" = yes ]; then
      reverse_list=$("$ADB" -s emulator-5554 reverse --list 2>/dev/null) || reverse_list=""
      printf '%s\n' "$reverse_list" | grep -Eq "tcp:($HS_PORT|$DERP_PORT)" && reverse_absent=no
    fi
    if [ -n "$ANDROID_ORIGINAL_USER" ]; then
      current_user=$("$ADB" -s emulator-5554 shell cmd activity get-current-user 2>/dev/null | tr -d '\r')
      [ "$current_user" = "$ANDROID_ORIGINAL_USER" ] || original_user_unchanged=no
    fi
  fi
  while IFS='|' read -r p expected_identity; do
    [ -n "$p" ] || continue
    current_identity=$(ps -p "$p" -o lstart=,comm= 2>/dev/null)
    [ "$current_identity" = "$expected_identity" ] || production_untouched=no
  done <<< "$PRODUCTION_SNAPSHOT"

  for p in "$HS_PORT" "$HS_METRICS_PORT" "$HS_GRPC_PORT" "$HS_STUN_PORT" "$DERP_PORT" "$DAEMON_PORT"; do
    tcp=$(port_count tcp "$p")
    udp=$(port_count udp "$p")
    [ "$tcp" = 0 ] && [ "$udp" = 0 ] || cleanup_ok=no
  done
  for p in "$daemon_absent" "$headscale_absent" "$derper_absent" "$tmux_absent" "$tmux_pane_absent" \
           "$android_user_absent" "$android_data_absent" "$reverse_absent" \
           "$wrap_property_absent" "$device_files_absent" "$test_package_absent" \
           "$original_user_unchanged" "$APP_APK_RESTORED" "$tmp_absent" "$production_untouched" \
           "$derp_private_keys_absent"; do
    [ "$p" = yes ] || cleanup_ok=no
  done
  [ "$open_files" = 0 ] || cleanup_ok=no

  if ! ARTIFACT_SECRET_LEAK=$(TS_AUTHKEY="$AUTH_KEY" PAIR_TOKEN_SECRET="$PAIR_TOKEN" \
    /opt/homebrew/bin/python3 -c '
import os, pathlib, sys
root = pathlib.Path(sys.argv[1])
secrets = [v.encode() for v in (os.environ.get("TS_AUTHKEY", ""), os.environ.get("PAIR_TOKEN_SECRET", "")) if v]
found = False
for path in root.rglob("*"):
    if not path.is_file():
        continue
    data = path.read_bytes()
    if not any(v in data for v in secrets):
        continue
    found = True
    if b"\0" in data:
        path.unlink()
        continue
    for v in secrets:
        data = data.replace(v, b"[REDACTED]")
    path.write_bytes(data)
print("yes" if found else "no")
' "$ARTIFACT_DIR"); then
    ARTIFACT_SECRET_LEAK=unknown
    cleanup_ok=no
    FAILURE_REASON=artifact_secret_scan_failed
  fi
  if [ "$ARTIFACT_SECRET_LEAK" = yes ]; then
    cleanup_ok=no
    FAILURE_REASON=secret_value_found_in_artifacts
  fi

  if [ "$incoming_rc" -eq 0 ] && [ "$RESULT_STATUS" = pass ] && [ "$cleanup_ok" = yes ]; then
    final_rc=0
  else
    final_rc=1
    RESULT_STATUS=fail
    if [ "$cleanup_ok" != yes ] && [ "$FAILURE_REASON" != secret_value_found_in_artifacts ] && \
       [ "$FAILURE_REASON" != artifact_secret_scan_failed ] && \
       [ "$FAILURE_REASON" != diagnostic_redaction_failed ] && \
       [ "$FAILURE_REASON" != secret_value_found_in_app_logcat ] && \
       [ "$FAILURE_REASON" != app_logcat_probe_failed ]; then
      [ "$FAILURE_REASON" = not_completed ] && FAILURE_REASON=cleanup_incomplete \
        || FAILURE_REASON="$FAILURE_REASON+cleanup_incomplete"
    fi
  fi

  {
    printf 'cleanup_status=%s\n' "$cleanup_ok"
    printf 'daemon_pid_absent=%s\n' "$daemon_absent"
    printf 'headscale_pid_absent=%s\n' "$headscale_absent"
    printf 'derper_pid_absent=%s\n' "$derper_absent"
    printf 'derp_private_keys_absent=%s\n' "$derp_private_keys_absent"
    printf 'isolated_tmux_pid_absent=%s\n' "$tmux_absent"
    printf 'isolated_tmux_pane_pid_absent=%s\n' "$tmux_pane_absent"
    printf 'android_task_user_absent=%s\n' "$android_user_absent"
    printf 'android_task_package_data_absent=%s\n' "$android_data_absent"
    printf 'android_task_data_forced_cleanup=%s\n' "$ANDROID_DATA_FORCED_CLEANUP"
    printf 'android_original_user_unchanged=%s\n' "$original_user_unchanged"
    printf 'emulator_app_apk_restored=%s\n' "$APP_APK_RESTORED"
    printf 'instrumentation_test_package_absent=%s\n' "$test_package_absent"
    printf 'app_wrap_property_absent=%s\n' "$wrap_property_absent"
    printf 'device_task_files_absent=%s\n' "$device_files_absent"
    printf 'adb_reverse_absent=%s\n' "$reverse_absent"
    printf 'app_log_secret_value_present=%s\n' "$APP_LOG_SECRET_VALUE"
    printf 'task_temp_absent=%s\n' "$tmp_absent"
    printf 'task_temp_open_files_before_remove=%s\n' "$open_files"
    printf 'production_agentmirrord_baseline_count=%s\n' "$PRODUCTION_BASELINE_COUNT"
    printf 'production_agentmirrord_baseline_untouched=%s\n' "$production_untouched"
    for p in "$HS_PORT" "$HS_METRICS_PORT" "$HS_GRPC_PORT" "$HS_STUN_PORT" "$DERP_PORT" "$DAEMON_PORT"; do
      printf 'port_%s_tcp_listeners=%s udp_sockets=%s\n' "$p" "$(port_count tcp "$p")" "$(port_count udp "$p")"
    done
  } >"$ARTIFACT_DIR/cleanup.txt" || final_rc=1

  jq -n \
    --arg status "$RESULT_STATUS" --arg failure "$FAILURE_REASON" \
    --arg nodes "$NODE_COUNT" --arg registration "$APP_REGISTRATION_OBSERVATION" \
    --arg state_dir "$STATE_DIR_PROOF" --arg app_state "$APP_TSNET_STATE_PROOF" \
    --arg pairing "$PAIRING_ENTRY_PROOF" --arg manager "$CONNECTION_MANAGER_PROOF" \
    --arg workspace "$WORKSPACE_VISIBLE" --arg session "$SESSION_VISIBLE" \
    --arg socks "$SOCKS_LOOPBACK_PROOF" --arg argv_flag "$ARGV_AUTHKEY_FLAG" \
    --arg argv_value "$ARGV_AUTHKEY_VALUE" --arg argv_shape "$ARGV_AUTHKEY_SHAPE" \
    --arg argv_pair "$ARGV_PAIR_TOKEN_VALUE" --arg app_argv_value "$APP_ARGV_AUTHKEY_VALUE" \
    --arg app_argv_shape "$APP_ARGV_AUTHKEY_SHAPE" --arg app_argv_pair "$APP_ARGV_PAIR_TOKEN_VALUE" \
    --arg app_log_secret "$APP_LOG_SECRET_VALUE" --arg leak "$ARTIFACT_SECRET_LEAK" \
    --arg cleanup "$cleanup_ok" --arg derp_listener "$DERP_LISTENER_PROOF" \
    --arg derp_route "$DERP_ROUTE_PROOF" \
    --arg derp_accepts "$DERP_ACCEPTS" --arg derp_received "$DERP_PACKETS_RECEIVED" \
    --arg derp_sent "$DERP_PACKETS_SENT" \
    '{
      status:$status,failure_reason:$failure,headscale_node_count:($nodes|tonumber),
      app_registration_observation:$registration,daemon_state_dir_proof:$state_dir,
      instrumentation:{runner:"ApiE2ERunner",ui_path_used:"no",activity_started:"no",
        pairing_entry:$pairing,tsnet_binding_state_file:$app_state,connection_manager:$manager,
        workspace_listing_received:$workspace,isolated_session_received:$session,
        secret_source:"TS_AUTHKEY environment via task FIFO wrapper"},
      socks5_proof:$socks,home_override_used:"no",camera_path_used:"no",
      derp:{listener_proof:$derp_listener,route_proof:$derp_route,accepts:($derp_accepts|tonumber),
        packets_received:($derp_received|tonumber),packets_sent:($derp_sent|tonumber)},
      foreground_mouse_or_window_targeting_used:"no",screenshots:[],
      argv:{daemon:{ts_authkey_flag_present:$argv_flag,authkey_value_present:$argv_value,
        authkey_shape_present:$argv_shape,pair_token_value_present:$argv_pair},
        app:{authkey_value_present:$app_argv_value,authkey_shape_present:$app_argv_shape,
        pair_token_value_present:$app_argv_pair}},
      app_log_secret_value_present:$app_log_secret,artifact_secret_value_present:$leak,
      cleanup_status:$cleanup
    }' >"$ARTIFACT_DIR/result.json" || final_rc=1

  if [ "$final_rc" -eq 0 ]; then
    printf 'PASS fix-ts-state-dir-e2e artifact=%s\n' "$ARTIFACT_DIR"
  else
    printf 'FAIL fix-ts-state-dir-e2e reason=%s artifact=%s\n' "$FAILURE_REASON" "$ARTIFACT_DIR" >&2
  fi
  exit "$final_rc"
}

trap cleanup EXIT
trap 'FAILURE_REASON=interrupted; exit 130' INT TERM
trap 'if [ "$FAILURE_REASON" = not_completed ]; then FAILURE_REASON="unexpected_command_failure_line_$LINENO"; fi' ERR

for tool in "$ADB" "$HEADSCALE" "$TMUX_BIN" "$SANDBOX_EXEC" "$LSOF" "$GRADLEW" \
  /opt/homebrew/bin/python3 /usr/bin/jq /usr/bin/curl /opt/homebrew/bin/openssl; do
  [ -x "$tool" ] || die missing_required_tool
done
[ "$("$HEADSCALE" version 2>/dev/null)" = 0.26.1 ] || die unsupported_headscale_version
"$ADB" -s emulator-5554 get-state >/dev/null 2>&1 || die emulator_5554_unavailable

# Inherited task residue is a blocker, not permission to guess and delete it.
PRIOR_TASK_TMP=$(find /private/tmp -maxdepth 1 -type d -name 'fix-ts-state-dir-e2e.*' -print -quit 2>/dev/null || true)
[ -z "$PRIOR_TASK_TMP" ] || die prior_task_temp_residue
PRIOR_TASK_USER=$("$ADB" -s emulator-5554 shell pm list users 2>/dev/null | grep ':fix-ts-state-e2e-' | head -1 || true)
[ -z "$PRIOR_TASK_USER" ] || die prior_task_android_user_residue
PRIOR_REVERSE=$("$ADB" -s emulator-5554 reverse --list 2>/dev/null \
  | grep -E "tcp:($HS_PORT|$DERP_PORT)" | head -1 || true)
[ -z "$PRIOR_REVERSE" ] || die prior_task_adb_reverse_residue
PRIOR_DEVICE_FILE=$("$ADB" -s emulator-5554 shell su 0 find /data/local/tmp -maxdepth 1 \
  -name 'fix-ts-state-dir-e2e-*' -print -quit 2>/dev/null | tr -d '\r')
[ -z "$PRIOR_DEVICE_FILE" ] || die prior_task_device_file_residue
PRIOR_TEST_PACKAGE=$("$ADB" -s emulator-5554 shell pm path "$TEST_PACKAGE" 2>/dev/null | tr -d '\r' || true)
[ -z "$PRIOR_TEST_PACKAGE" ] || die prior_task_test_package_residue
PRIOR_WRAP=$("$ADB" -s emulator-5554 shell su 0 getprop "$WRAP_PROPERTY" 2>/dev/null | tr -d '\r')
[ -z "$PRIOR_WRAP" ] || die app_wrap_property_already_set
PRIOR_TASK_TMP=""
PRIOR_TASK_USER=""
PRIOR_REVERSE=""
PRIOR_DEVICE_FILE=""
PRIOR_TEST_PACKAGE=""
PRIOR_WRAP=""

# Snapshot production daemon identities without reading argv. Cleanup only
# compares these tuples and never signals them.
PRODUCTION_SNAPSHOT=$(
  for p in $(pgrep -x agentmirrord 2>/dev/null || true); do
    current_identity=$(ps -p "$p" -o lstart=,comm= 2>/dev/null)
    [ -n "$current_identity" ] && printf '%s|%s\n' "$p" "$current_identity"
  done
)
if [ -n "$PRODUCTION_SNAPSHOT" ]; then
  PRODUCTION_BASELINE_COUNT=$(printf '%s\n' "$PRODUCTION_SNAPSHOT" | wc -l | tr -d ' ')
fi
EMULATOR_ROOT_ID=$("$ADB" -s emulator-5554 shell su 0 /system/bin/id 2>/dev/null | tr -d '\r')
case "$EMULATOR_ROOT_ID" in *'uid=0(root)'*) ;; *) die emulator_root_shell_unavailable ;; esac
EMULATOR_ROOT_ID=""

TASK_TMP=$(mktemp -d /private/tmp/fix-ts-state-dir-e2e.XXXXXX)
TMUX_ROOT="$TASK_TMP/tmux-root"
mkdir -p "$TASK_TMP/hs" "$TASK_TMP/daemon-state" "$TASK_TMP/workspace" \
  "$TASK_TMP/bin" "$TASK_TMP/androidTest/dev/agentmirror/app/e2e" "$TMUX_ROOT"

# Add one temporary androidTest source set at Gradle configuration time. No App
# source or build file is edited, and the test application id is task-unique.
cat >"$TASK_TMP/androidTest/dev/agentmirror/app/e2e/ApiE2ERunner.kt" <<'KOTLIN'
package dev.agentmirror.app.e2e

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.pairing.PairingConfig
import dev.agentmirror.app.pairing.PairingConfigStore
import dev.agentmirror.app.pairing.PairingStatus
import dev.agentmirror.app.pairing.PairingViewModel
import dev.agentmirror.app.service.OkHttpWebSocketTransport
import dev.agentmirror.app.tsnet.GomobileTsnetBackend
import dev.agentmirror.app.tsnet.TsnetProxy
import dev.agentmirror.app.tsnet.TsnetProxySocketFactory
import dev.agentmirror.app.tsnet.TsnetState
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct API E2E: environment -> pairing entry -> native tsnet -> SOCKS5
 * ConnectionManager -> authoritative listing. Result keys are presence-only.
 */
class ApiE2ERunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
        Thread({ execute() }, "fix-ts-state-dir-e2e-api").start()
    }

    private fun execute() {
        val result = Bundle()
        var ok = false
        var failureStage = "unexpected"
        var backend: GomobileTsnetBackend? = null
        var listingManager: ConnectionManager? = null
        try {
            val authKey = requiredEnv("TS_AUTHKEY")
            val pairToken = requiredEnv("AGENTMIRROR_PAIR_TOKEN")
            val pairUrl = requiredEnv("AGENTMIRROR_PAIR_URL")
            val controlUrl = requiredEnv("TS_CONTROL_URL")
            val expectedCwd = requiredEnv("E2E_EXPECTED_CWD")

            val tsRoot = File(targetContext.filesDir, "tsnet")
            requireStage(tsRoot.mkdirs() || tsRoot.isDirectory, "tsnet_state_dir_create")
            File(tsRoot, "control_url.txt").writeText(controlUrl)

            val proxyRef = AtomicReference<TsnetProxy>()
            val savedConfig = AtomicReference<PairingConfig>()
            val startGate = CountDownLatch(1)
            val startDone = CountDownLatch(1)
            val startCalled = AtomicBoolean(false)
            val startFailure = AtomicReference<String>()
            val nativeBackend = GomobileTsnetBackend()
            backend = nativeBackend

            fun transportFactory(): TransportFactory = TransportFactory { url ->
                val proxy = proxyRef.get() ?: throw StageFailure("proxy_missing_before_dial")
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .socketFactory(TsnetProxySocketFactory(proxy))
                    .build()
                OkHttpWebSocketTransport(url, client)
            }

            lateinit var viewModel: PairingViewModel
            viewModel = PairingViewModel(
                configStore = object : PairingConfigStore {
                    override fun load(): PairingConfig? = savedConfig.get()
                    override fun save(config: PairingConfig) {
                        savedConfig.set(config)
                    }
                    override fun clear() {
                        savedConfig.set(null)
                    }
                },
                connectionFactory = { config -> ConnectionManager(config, transportFactory()) },
                tsnetStarter = { suppliedKey ->
                    startCalled.set(true)
                    if (suppliedKey != authKey) {
                        startFailure.set("authkey_not_forwarded")
                        startDone.countDown()
                    } else {
                        Thread({
                            startGate.await()
                            try {
                                val proxy = nativeBackend.start(
                                    File(tsRoot, "node-instrumentation").absolutePath,
                                    "agentmirror-api-e2e",
                                    suppliedKey,
                                )
                                proxyRef.set(proxy)
                                viewModel.onTsnetState(TsnetState.Up(proxy))
                            } catch (_: Throwable) {
                                startFailure.set("tsnet_start_failed")
                                viewModel.onTsnetState(TsnetState.Error("instrumentation tsnet start failed"))
                            } finally {
                                startDone.countDown()
                            }
                        }, "fix-ts-state-dir-e2e-tsnet").start()
                    }
                },
            )

            // Invoke the production parsed-QR entry directly; no carrier/UI.
            val payload = JSONObject()
                .put("v", 1)
                .put("url", pairUrl)
                .put("token", pairToken)
                .put("ts_authkey", authKey)
                .toString()
            viewModel.onQrText(payload)
            startGate.countDown()

            val pairingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(95)
            while (System.nanoTime() < pairingDeadline) {
                if (viewModel.pairingStatus is PairingStatus.Success ||
                    viewModel.pairingStatus is PairingStatus.Failed
                ) break
                Thread.sleep(100)
            }
            requireStage(startCalled.get(), "tsnet_start_not_called")
            requireStage(startDone.await(1, TimeUnit.SECONDS), "tsnet_start_not_finished")
            startFailure.get()?.let { throw StageFailure(it) }
            requireStage(viewModel.pairingStatus is PairingStatus.Success, "pairing_not_success")
            val stored = savedConfig.get()
            requireStage(stored != null, "pairing_config_not_saved")
            requireStage(stored!!.url == pairUrl, "pairing_url_not_forwarded")
            requireStage(stored.token == pairToken, "pairing_token_not_forwarded")
            requireStage(stored.tsAuthKey == authKey, "pairing_authkey_not_forwarded")
            requireStage(
                tsRoot.walkTopDown().any { it.isFile && it.name == "tailscaled.state" },
                "tsnet_state_file_missing",
            )

            // Pairing closes its probe at READY. Keep a second direct manager
            // alive through listing so workspace proof is protocol data.
            val ready = CountDownLatch(1)
            val listed = CountDownLatch(1)
            val listing = AtomicReference<ListingFrame>()
            val manager = ConnectionManager(
                ConnectionConfig(pairUrl, pairToken),
                transportFactory(),
            )
            listingManager = manager
            manager.setListener(object : ConnectionManager.Listener {
                override fun onStateChanged(state: ConnectionState) {
                    if (state == ConnectionState.READY) ready.countDown()
                }
                override fun onFrame(frame: FramePayload) {
                    if (frame is ListingFrame) {
                        listing.set(frame)
                        listed.countDown()
                    }
                }
                override fun onBinary(frame: BinaryFrame) = Unit
                override fun onLocalDecodeError(code: FrameError, message: String) = Unit
                override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit
                override fun onReconnect(attempt: Int, delayMs: Long) = Unit
            })
            manager.start()
            requireStage(ready.await(20, TimeUnit.SECONDS), "connection_manager_not_ready")
            requireStage(listed.await(20, TimeUnit.SECONDS), "listing_not_received")
            val frame = listing.get() ?: throw StageFailure("listing_missing")
            val workspace = frame.workspaces.singleOrNull { it.cwd == expectedCwd }
                ?: throw StageFailure("isolated_workspace_missing")
            requireStage(workspace.sessions.isNotEmpty(), "isolated_session_missing")

            result.putString("pairing_entry", "onQrText_pass")
            result.putString("tsnet_start_called", "yes")
            result.putString("tsnet_state_file", "yes")
            result.putString("connection_manager_ready", "yes")
            result.putString("listing_received", "yes")
            result.putString("workspace_cwd_match", "yes")
            result.putString("session_present", "yes")
            result.putString("socks5_path", "explicit_tsnet_proxy_socket_factory")
            result.putString("secret_source", "environment")
            ok = true
        } catch (failure: StageFailure) {
            failureStage = failure.stage
        } catch (_: Throwable) {
            failureStage = "unexpected"
        } finally {
            runCatching { listingManager?.stop() }
            runCatching { backend?.close() }
        }

        result.putString("api_status", if (ok) "pass" else "fail")
        if (!ok) result.putString("failure_stage", failureStage)
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: throw StageFailure("missing_environment")

    private fun requireStage(condition: Boolean, stage: String) {
        if (!condition) throw StageFailure(stage)
    }

    private class StageFailure(val stage: String) : RuntimeException()
}
KOTLIN

cat >"$TASK_TMP/e2e.init.gradle" <<'GRADLE'
gradle.beforeProject { project ->
    project.pluginManager.withPlugin("com.android.application") {
        def android = project.extensions.getByName("android")
        android.defaultConfig.testApplicationId = "dev.agentmirror.app.fixstatee2e"
        android.defaultConfig.testInstrumentationRunner = "dev.agentmirror.app.e2e.ApiE2ERunner"
        android.sourceSets.getByName("androidTest").java.srcDir(System.getenv("E2E_ANDROID_TEST_SRC"))
    }
}
GRADLE

(cd "$ROOT/app" && \
  E2E_ANDROID_TEST_SRC="$TASK_TMP/androidTest" \
  "$GRADLEW" -I "$TASK_TMP/e2e.init.gradle" :app:assembleDebug :app:assembleDebugAndroidTest) \
  >"$ARTIFACT_DIR/app-build.log" 2>&1 || die instrumentation_apk_build_failed
[ -f "$DEBUG_APK" ] || die debug_apk_missing
[ -f "$TEST_APK" ] || die instrumentation_apk_missing

# If current source differs from the installed debug App, snapshot and replace
# it for the run; cleanup restores and hash-checks the exact original.
LOCAL_APK_HASH=$(shasum -a 256 "$DEBUG_APK" | awk '{print $1}')
INSTALLED_APK_PATH=$("$ADB" -s emulator-5554 shell pm path "$PACKAGE" 2>/dev/null \
  | sed -n 's/^package://p' | head -1 | tr -d '\r')
[ -n "$INSTALLED_APK_PATH" ] || die app_not_installed
INSTALLED_APK_HASH=$("$ADB" -s emulator-5554 exec-out cat "$INSTALLED_APK_PATH" | shasum -a 256 | awk '{print $1}')
if [ "$LOCAL_APK_HASH" != "$INSTALLED_APK_HASH" ]; then
  BASELINE_APK_FILE="$TASK_TMP/baseline-app.apk"
  BASELINE_APK_HASH="$INSTALLED_APK_HASH"
  "$ADB" -s emulator-5554 exec-out cat "$INSTALLED_APK_PATH" >"$BASELINE_APK_FILE" \
    || die baseline_apk_snapshot_failed
  [ "$(shasum -a 256 "$BASELINE_APK_FILE" | awk '{print $1}')" = "$BASELINE_APK_HASH" ] \
    || die baseline_apk_snapshot_hash_mismatch
  APP_APK_REPLACE_ATTEMPTED=yes
  "$ADB" -s emulator-5554 install -r "$DEBUG_APK" >"$TASK_TMP/app-install.log" 2>&1 \
    || die current_apk_install_failed
  INSTALLED_APK_PATH=$("$ADB" -s emulator-5554 shell pm path "$PACKAGE" 2>/dev/null \
    | sed -n 's/^package://p' | head -1 | tr -d '\r')
  INSTALLED_APK_HASH=$("$ADB" -s emulator-5554 exec-out cat "$INSTALLED_APK_PATH" \
    | shasum -a 256 | awk '{print $1}')
  [ "$LOCAL_APK_HASH" = "$INSTALLED_APK_HASH" ] || die current_apk_install_hash_mismatch
fi

for p in "$HS_PORT" "$HS_METRICS_PORT" "$HS_GRPC_PORT" "$HS_STUN_PORT" "$DERP_PORT" "$DAEMON_PORT"; do
  [ "$(port_count tcp "$p")" = 0 ] || die task_port_already_in_use
  [ "$(port_count udp "$p")" = 0 ] || die task_port_already_in_use
done
ANDROID_ORIGINAL_USER=$("$ADB" -s emulator-5554 shell cmd activity get-current-user 2>/dev/null | tr -d '\r')
case "$ANDROID_ORIGINAL_USER" in *[!0-9]*|'') die cannot_resolve_android_user ;; esac
LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || true)
case "$LAN_IP" in ''|127.*|169.254.*) die physical_lan_ip_unavailable ;; esac

(cd "$ROOT/server" && \
  GOCACHE="$TASK_TMP/go-cache" go build -o "$TASK_TMP/bin/agentmirrord" ./cmd/agentmirrord) \
  >"$TASK_TMP/go-build.log" 2>&1 || die daemon_build_failed
(
  unset HTTPS_PROXY HTTP_PROXY ALL_PROXY https_proxy http_proxy all_proxy
  GOBIN="$TASK_TMP/bin" GOCACHE="$TASK_TMP/go-cache" \
    go install tailscale.com/cmd/derper@v1.102.2
) >>"$TASK_TMP/go-build.log" 2>&1 || die derper_build_failed

cat >"$TASK_TMP/headscale.yaml" <<EOF
server_url: http://127.0.0.1:$HS_PORT
listen_addr: 127.0.0.1:$HS_PORT
metrics_listen_addr: 127.0.0.1:$HS_METRICS_PORT
grpc_listen_addr: 127.0.0.1:$HS_GRPC_PORT
grpc_allow_insecure: false
noise:
  private_key_path: $TASK_TMP/hs/noise_private.key
prefixes:
  v4: 100.64.0.0/10
  v6: fd7a:115c:a1e0::/48
  allocation: sequential
derp:
  server:
    enabled: false
    region_id: 999
    region_code: e2e
    region_name: E2E
    stun_listen_addr: 127.0.0.1:$HS_STUN_PORT
    private_key_path: $TASK_TMP/hs/derp_private.key
    automatically_add_embedded_derp_region: false
  urls: []
  paths:
    - $TASK_TMP/derp.yaml
  auto_update_enabled: false
  update_frequency: 24h
disable_check_updates: true
ephemeral_node_inactivity_timeout: 5m
database:
  type: sqlite
  sqlite:
    path: $TASK_TMP/hs/db.sqlite
    write_ahead_log: true
tls_cert_path: ""
tls_key_path: ""
tls_letsencrypt_hostname: ""
tls_letsencrypt_cache_dir: $TASK_TMP/hs/tls-cache
log:
  format: text
  level: warn
policy:
  mode: database
  path: ""
dns:
  magic_dns: false
  base_domain: e2e.invalid
  override_local_dns: false
  nameservers:
    global: []
    split: {}
  search_domains: []
  extra_records: []
unix_socket: $TASK_TMP/hs/headscale.sock
unix_socket_permission: "0700"
logtail:
  enabled: false
randomize_client_port: false
EOF

# Both task-owned tsnet nodes use this task-owned TLS DERP. The App reaches the
# same loopback endpoint through adb reverse; no host-wide route is changed.
cat >"$TASK_TMP/derp.yaml" <<EOF
regions:
  998:
    regionid: 998
    regioncode: e2e
    regionname: E2E relay
    nodes:
      - name: e2e-relay
        regionid: 998
        hostname: 127.0.0.1
        ipv4: 127.0.0.1
        ipv6: none
        stunport: -1
        derpport: $DERP_PORT
        insecurefortests: true
EOF

"$TASK_TMP/bin/derper" -a "127.0.0.1:$DERP_PORT" -http-port -1 -stun=false \
  -c "$TASK_TMP/derper.json" -certmode manual -certdir "$TASK_TMP/derper-certs" \
  -hostname 127.0.0.1 >"$TASK_TMP/derper.stdout" 2>"$TASK_TMP/derper.stderr" &
DERP_PID=$!
for _ in $(seq 1 60); do
  /usr/bin/curl --noproxy '*' -kfsS "https://127.0.0.1:$DERP_PORT/generate_204" \
    >/dev/null 2>&1 && break
  kill -0 "$DERP_PID" 2>/dev/null || die derper_exited_early
  sleep 0.25
done
/usr/bin/curl --noproxy '*' -kfsS "https://127.0.0.1:$DERP_PORT/generate_204" \
  >/dev/null 2>&1 || die derper_not_ready
[ "$(port_count tcp "$DERP_PORT")" = 1 ] || die derper_tcp_listener_missing
[ "$(port_count udp "$DERP_PORT")" = 0 ] || die derper_unexpected_udp_socket
DERP_LISTENER_PROOF=tls_health_and_tcp_listener_verified

"$HEADSCALE" -c "$TASK_TMP/headscale.yaml" configtest >"$TASK_TMP/headscale-configtest.log" 2>&1 \
  || die headscale_config_invalid
"$HEADSCALE" -c "$TASK_TMP/headscale.yaml" serve >"$TASK_TMP/headscale.stdout" 2>"$TASK_TMP/headscale.stderr" &
HEADSCALE_PID=$!
for _ in $(seq 1 60); do
  /usr/bin/curl --noproxy '*' -fsS "http://127.0.0.1:$HS_PORT/health" >/dev/null 2>&1 && break
  kill -0 "$HEADSCALE_PID" 2>/dev/null || die headscale_exited_early
  sleep 0.5
done
/usr/bin/curl --noproxy '*' -fsS "http://127.0.0.1:$HS_PORT/health" >/dev/null 2>&1 \
  || die headscale_not_ready

USER_JSON=$("$HEADSCALE" -c "$TASK_TMP/headscale.yaml" -o json users create fix-ts-state-e2e \
  2>"$TASK_TMP/headscale-cli.stderr") || die headscale_user_create_failed
USER_ID=$(printf '%s' "$USER_JSON" | jq -r '.id // empty')
[ -n "$USER_ID" ] || die headscale_user_id_missing
AUTH_JSON=$("$HEADSCALE" -c "$TASK_TMP/headscale.yaml" -o json preauthkeys create \
  --user "$USER_ID" --ephemeral --reusable --expiration 15m \
  2>>"$TASK_TMP/headscale-cli.stderr") || die headscale_authkey_create_failed
AUTH_KEY=$(printf '%s' "$AUTH_JSON" | jq -r '.key // empty')
[ -n "$AUTH_KEY" ] || die headscale_authkey_missing
USER_JSON=""
AUTH_JSON=""
PAIR_TOKEN=$(/opt/homebrew/bin/openssl rand -hex 24)

TMUX= TMUX_TMPDIR="$TMUX_ROOT" "$TMUX_BIN" -f /dev/null -L "$TMUX_LABEL" \
  new-session -d -s fix-ts-e2e -c "$TASK_TMP/workspace" \
  "/bin/sh -c 'printf \"isolated fix-ts-state-dir-e2e workspace\\n\"; exec sleep 600'" \
  || die isolated_tmux_start_failed
TASK_TMUX_PID=$(TMUX= TMUX_TMPDIR="$TMUX_ROOT" "$TMUX_BIN" -f /dev/null -L "$TMUX_LABEL" display-message -p '#{pid}')
TASK_TMUX_PANE_PID=$(TMUX= TMUX_TMPDIR="$TMUX_ROOT" "$TMUX_BIN" -f /dev/null -L "$TMUX_LABEL" display-message -p '#{pane_pid}')
case "$TASK_TMUX_PANE_PID" in *[!0-9]*|'') die isolated_tmux_pane_pid_missing ;; esac

UID_DIR="tmux-$(id -u)"
cat >"$TASK_TMP/daemon.sb" <<EOF
(version 1)
(allow default)
(deny file-read-data file-read-metadata (subpath "/private/tmp/$UID_DIR"))
(deny file-read-data file-read-metadata (subpath "/tmp/$UID_DIR"))
EOF

"$SANDBOX_EXEC" -f "$TASK_TMP/daemon.sb" /bin/sh -c 'exit 0' \
  >/dev/null 2>"$TASK_TMP/sandbox-check.log" || die sandbox_profile_invalid

(
  unset HTTPS_PROXY HTTP_PROXY ALL_PROXY https_proxy http_proxy all_proxy
  TS_AUTHKEY=$AUTH_KEY
  TS_CONTROL_URL="http://127.0.0.1:$HS_PORT"
  TS_DEBUG_ALWAYS_USE_DERP=1
  AGENTMIRROR_STATE_DIR="$TASK_TMP/daemon-state"
  AGENTMIRROR_LISTEN="127.0.0.1:$DAEMON_PORT"
  AGENTMIRROR_TOKEN=$PAIR_TOKEN
  AGENTMIRROR_HOST=$LAN_IP
  AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$TMUX_ROOT/$UID_DIR"
  TMUX_TMPDIR=$TMUX_ROOT
  export TS_AUTHKEY TS_CONTROL_URL TS_DEBUG_ALWAYS_USE_DERP AGENTMIRROR_STATE_DIR AGENTMIRROR_LISTEN
  export AGENTMIRROR_TOKEN AGENTMIRROR_HOST AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS TMUX_TMPDIR
  exec "$SANDBOX_EXEC" -f "$TASK_TMP/daemon.sb" "$TASK_TMP/bin/agentmirrord"
) >/dev/null 2>"$TASK_TMP/daemon.stderr" &
DAEMON_PID=$!

for _ in $(seq 1 180); do
  grep -q 'tailnet 已入网' "$TASK_TMP/daemon.stderr" 2>/dev/null && break
  kill -0 "$DAEMON_PID" 2>/dev/null || die daemon_exited_before_tailnet_up
  sleep 0.5
done
grep -q 'tailnet 已入网' "$TASK_TMP/daemon.stderr" || die daemon_tailnet_up_timeout
[ -f "$TASK_TMP/daemon-state/tsnet/tailscaled.state" ] || die daemon_tsnet_state_missing
STATE_DIR_PROOF=yes
DAEMON_TAILNET_IP=$(sed -n '/tailnet 已入网/ s/.* ip=\([^ ]*\).*/\1/p' "$TASK_TMP/daemon.stderr" | tail -1)
printf '%s\n' "$DAEMON_TAILNET_IP" | grep -Eq '^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}$' \
  || die daemon_tailnet_ip_missing

DAEMON_ARGV=$(ps -p "$DAEMON_PID" -o command= 2>/dev/null || true)
case "$DAEMON_ARGV" in *-ts-authkey*) ARGV_AUTHKEY_FLAG=yes ;; *) ARGV_AUTHKEY_FLAG=no ;; esac
case "$DAEMON_ARGV" in *"$AUTH_KEY"*) ARGV_AUTHKEY_VALUE=yes ;; *) ARGV_AUTHKEY_VALUE=no ;; esac
case "$DAEMON_ARGV" in *hskey-auth-*|*tskey-auth-*|*tskey-*) ARGV_AUTHKEY_SHAPE=yes ;; *) ARGV_AUTHKEY_SHAPE=no ;; esac
case "$DAEMON_ARGV" in *"$PAIR_TOKEN"*) ARGV_PAIR_TOKEN_VALUE=yes ;; *) ARGV_PAIR_TOKEN_VALUE=no ;; esac
[ "$ARGV_AUTHKEY_FLAG" = no ] && [ "$ARGV_AUTHKEY_VALUE" = no ] && \
  [ "$ARGV_AUTHKEY_SHAPE" = no ] && [ "$ARGV_PAIR_TOKEN_VALUE" = no ] \
  || die secret_found_in_daemon_argv
DAEMON_ARGV=""

# The same loopback headscale URL reaches the emulator through this task-owned
# reverse rule; no host-wide route or emulator UI is involved.
"$ADB" -s emulator-5554 reverse tcp:"$HS_PORT" tcp:"$HS_PORT" >/dev/null 2>&1 \
  || die adb_reverse_add_failed
ADB_REVERSE_ADDED=yes
"$ADB" -s emulator-5554 reverse tcp:"$DERP_PORT" tcp:"$DERP_PORT" >/dev/null 2>&1 \
  || die adb_derp_reverse_add_failed
ADB_DERP_REVERSE_ADDED=yes

ANDROID_USER_CREATE_ATTEMPTED=yes
CREATE_USER_OUT=$("$ADB" -s emulator-5554 shell pm create-user --ephemeral "$ANDROID_USER_NAME" 2>/dev/null) \
  || die android_user_create_failed
ANDROID_USER=$(printf '%s' "$CREATE_USER_OUT" | sed -n 's/.*id \([0-9][0-9]*\).*/\1/p' | tail -1)
[ -n "$ANDROID_USER" ] || die android_user_id_missing
CREATE_USER_OUT=""
"$ADB" -s emulator-5554 shell cmd package install-existing --user "$ANDROID_USER" "$PACKAGE" \
  >/dev/null 2>&1 || die app_install_existing_failed
"$ADB" -s emulator-5554 shell am start-user -w "$ANDROID_USER" >/dev/null 2>&1 \
  || die android_user_start_failed
for _ in $(seq 1 80); do
  USER_STATE=$("$ADB" -s emulator-5554 shell am get-started-user-state "$ANDROID_USER" 2>/dev/null | tr -d '\r')
  [ "$USER_STATE" = RUNNING_UNLOCKED ] && break
  sleep 0.5
done
[ "$USER_STATE" = RUNNING_UNLOCKED ] || die android_user_unlock_timeout

APP_UID=$("$ADB" -s emulator-5554 shell cmd package list packages -U --user "$ANDROID_USER" "$PACKAGE" 2>/dev/null \
  | sed -n 's/.* uid:\([0-9][0-9]*\).*/\1/p' | tr -d '\r')
case "$APP_UID" in *[!0-9]*|'') die app_uid_missing ;; esac
APP_DATA_DIR="/data/user/$ANDROID_USER/$PACKAGE"
DEVICE_WRAP_SCRIPT="$APP_DATA_DIR/w"
DEVICE_SECRET_FIFO="$APP_DATA_DIR/f"
WRAP_COMMAND="/system/bin/sh $DEVICE_WRAP_SCRIPT"

"$ADB" -s emulator-5554 push "$TEST_APK" "$DEVICE_TEST_APK" >/dev/null 2>&1 \
  || die instrumentation_apk_push_failed
"$ADB" -s emulator-5554 shell su 0 pm install --user "$ANDROID_USER" -t "$DEVICE_TEST_APK" \
  >"$TASK_TMP/app-test-install.log" 2>&1 || die instrumentation_apk_install_failed
TEST_PACKAGE_INSTALLED=yes
"$ADB" -s emulator-5554 shell su 0 rm -f "$DEVICE_TEST_APK" >/dev/null 2>&1 \
  || die instrumentation_apk_device_cleanup_failed

# A debuggable-package wrapper is the only path that gives a zygote-launched
# Instrumentation a real environment. Secrets cross the FIFO on stdin; neither
# adb/am argv nor the wrapper file contains their values.
cat >"$TASK_TMP/wrap.sh" <<EOF
#!/system/bin/sh
if ! {
  IFS= read -r TS_AUTHKEY
  IFS= read -r AGENTMIRROR_PAIR_TOKEN
  IFS= read -r AGENTMIRROR_PAIR_URL
  IFS= read -r TS_CONTROL_URL
  IFS= read -r E2E_EXPECTED_CWD
} < "$DEVICE_SECRET_FIFO"; then
  exit 111
fi
export TS_AUTHKEY AGENTMIRROR_PAIR_TOKEN AGENTMIRROR_PAIR_URL TS_CONTROL_URL E2E_EXPECTED_CWD
export TS_DEBUG_ALWAYS_USE_DERP=1
exec "\$@"
EOF
"$ADB" -s emulator-5554 shell \
  "su 0 sh -c 'mkdir -p $APP_DATA_DIR && chown $APP_UID:$APP_UID $APP_DATA_DIR && chmod 700 $APP_DATA_DIR && restorecon -RF $APP_DATA_DIR'" \
  >"$TASK_TMP/app-wrapper-prepare.log" 2>&1 || die app_data_root_prepare_failed
"$ADB" -s emulator-5554 shell -T "su 0 sh -c 'cat > $DEVICE_WRAP_SCRIPT'" \
  <"$TASK_TMP/wrap.sh" >>"$TASK_TMP/app-wrapper-prepare.log" 2>&1 || die app_wrapper_write_failed
"$ADB" -s emulator-5554 shell \
  "su 0 sh -c 'chown $APP_UID:$APP_UID $DEVICE_WRAP_SCRIPT && chmod 600 $DEVICE_WRAP_SCRIPT && mknod $DEVICE_SECRET_FIFO p && chown $APP_UID:$APP_UID $DEVICE_SECRET_FIFO && chmod 600 $DEVICE_SECRET_FIFO && restorecon -RF $APP_DATA_DIR'" \
  >>"$TASK_TMP/app-wrapper-prepare.log" 2>&1 || die app_wrapper_prepare_failed
"$ADB" -s emulator-5554 shell \
  "su 0 sh -c 'setprop $WRAP_PROPERTY \"$WRAP_COMMAND\"'" \
  >/dev/null 2>&1 || die app_wrap_property_set_failed
WRAP_PROPERTY_SET=yes
[ "$("$ADB" -s emulator-5554 shell su 0 getprop "$WRAP_PROPERTY" 2>/dev/null | tr -d '\r')" = "$WRAP_COMMAND" ] \
  || die app_wrap_property_verify_failed

PAIRING_URL="ws://$DAEMON_TAILNET_IP:$DAEMON_PORT/ws"
"$ADB" -s emulator-5554 shell am instrument --user "$ANDROID_USER" -w -r \
  "$TEST_PACKAGE/$TEST_RUNNER" >"$TASK_TMP/instrumentation.log" 2>&1 &
INSTRUMENT_HOST_PID=$!

{
  printf '%s\n' "$AUTH_KEY"
  printf '%s\n' "$PAIR_TOKEN"
  printf '%s\n' "$PAIRING_URL"
  printf 'http://127.0.0.1:%s\n' "$HS_PORT"
  printf '%s\n' "$TASK_TMP/workspace"
} | "$ADB" -s emulator-5554 shell -T "su 0 sh -c 'cat > $DEVICE_SECRET_FIFO'" \
  >/dev/null 2>&1 &
SECRET_FEED_PID=$!
for _ in $(seq 1 80); do
  kill -0 "$SECRET_FEED_PID" 2>/dev/null || break
  kill -0 "$INSTRUMENT_HOST_PID" 2>/dev/null || break
  sleep 0.25
done
kill -0 "$SECRET_FEED_PID" 2>/dev/null && die instrumentation_environment_not_consumed
wait "$SECRET_FEED_PID" || die instrumentation_environment_delivery_failed
SECRET_FEED_PID=""
"$ADB" -s emulator-5554 shell "su 0 sh -c 'setprop $WRAP_PROPERTY \"\"'" >/dev/null 2>&1 \
  || die app_wrap_property_clear_failed
WRAP_PROPERTY_SET=no

for _ in $(seq 1 80); do
  for p in $("$ADB" -s emulator-5554 shell su 0 /system/bin/pidof "$PACKAGE" 2>/dev/null || true); do
    PROC_UID=$("$ADB" -s emulator-5554 shell su 0 /system/bin/cat "/proc/$p/status" 2>/dev/null \
      | sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | tr -d '\r')
    if [ "$PROC_UID" = "$APP_UID" ]; then
      APP_PID=$p
      break
    fi
  done
  [ -n "$APP_PID" ] && break
  kill -0 "$INSTRUMENT_HOST_PID" 2>/dev/null || break
  sleep 0.25
done
[ -n "$APP_PID" ] || die instrumentation_app_pid_missing
PROC_UID=""

APP_ARGV=$("$ADB" -s emulator-5554 shell su 0 cat "/proc/$APP_PID/cmdline" 2>/dev/null | tr '\000' ' ')
case "$APP_ARGV" in *"$AUTH_KEY"*) APP_ARGV_AUTHKEY_VALUE=yes ;; *) APP_ARGV_AUTHKEY_VALUE=no ;; esac
case "$APP_ARGV" in *hskey-auth-*|*tskey-auth-*|*tskey-*) APP_ARGV_AUTHKEY_SHAPE=yes ;; *) APP_ARGV_AUTHKEY_SHAPE=no ;; esac
case "$APP_ARGV" in *"$PAIR_TOKEN"*) APP_ARGV_PAIR_TOKEN_VALUE=yes ;; *) APP_ARGV_PAIR_TOKEN_VALUE=no ;; esac
APP_ARGV=""
[ "$APP_ARGV_AUTHKEY_VALUE" = no ] && [ "$APP_ARGV_AUTHKEY_SHAPE" = no ] && \
  [ "$APP_ARGV_PAIR_TOKEN_VALUE" = no ] || die secret_found_in_app_argv

set +e
wait "$INSTRUMENT_HOST_PID"
INSTRUMENT_RC=$?
set -e
INSTRUMENT_HOST_PID=""

NODES_JSON=$("$HEADSCALE" -c "$TASK_TMP/headscale.yaml" -o json nodes list 2>/dev/null || true)
NODE_COUNT=$(printf '%s' "$NODES_JSON" | jq 'if type=="array" then length else 0 end' 2>/dev/null || printf 0)
if [ "$NODE_COUNT" -ge 2 ]; then
  APP_REGISTRATION_OBSERVATION=registered
else
  APP_REGISTRATION_OBSERVATION=no_app_node_in_headscale
fi

DERP_METRICS_JSON=$(/usr/bin/curl --noproxy '*' -kfsS \
  "https://127.0.0.1:$DERP_PORT/debug/vars" 2>/dev/null || true)
DERP_ACCEPTS=$(printf '%s' "$DERP_METRICS_JSON" | jq -r '.derp.accepts // 0' 2>/dev/null || printf 0)
DERP_PACKETS_RECEIVED=$(printf '%s' "$DERP_METRICS_JSON" \
  | jq -r '.derp.packets_received // 0' 2>/dev/null || printf 0)
DERP_PACKETS_SENT=$(printf '%s' "$DERP_METRICS_JSON" \
  | jq -r '.derp.packets_sent // 0' 2>/dev/null || printf 0)
DERP_METRICS_JSON=""
for p in "$DERP_ACCEPTS" "$DERP_PACKETS_RECEIVED" "$DERP_PACKETS_SENT"; do
  case "$p" in *[!0-9]*|'') die derp_metrics_invalid ;; esac
done
if [ "$DERP_ACCEPTS" -ge 2 ] && [ "$DERP_PACKETS_RECEIVED" -gt 0 ] && \
   [ "$DERP_PACKETS_SENT" -gt 0 ]; then
  DERP_ROUTE_PROOF=forced_route_with_two_clients_and_packet_flow
fi
jq -n --arg listener "$DERP_LISTENER_PROOF" --arg route "$DERP_ROUTE_PROOF" \
  --arg accepts "$DERP_ACCEPTS" \
  --arg received "$DERP_PACKETS_RECEIVED" --arg sent "$DERP_PACKETS_SENT" \
  '{listener_proof:$listener,route_proof:$route,accepts:($accepts|tonumber),packets_received:($received|tonumber),
    packets_sent:($sent|tonumber),forced_by:"TS_DEBUG_ALWAYS_USE_DERP",listen:"127.0.0.1:43914"}' \
  >"$ARTIFACT_DIR/derp-proof.json"

if [ "$INSTRUMENT_RC" -ne 0 ] || \
   ! grep -q '^INSTRUMENTATION_RESULT: api_status=pass$' "$TASK_TMP/instrumentation.log"; then
  STAGE=$(sed -n 's/^INSTRUMENTATION_RESULT: failure_stage=\([a-z0-9_]*\)$/\1/p' \
    "$TASK_TMP/instrumentation.log" | tail -1)
  case "$STAGE" in
    authkey_not_forwarded|connection_manager_not_ready|isolated_session_missing|isolated_workspace_missing|listing_missing|listing_not_received|missing_environment|pairing_authkey_not_forwarded|pairing_config_not_saved|pairing_not_success|pairing_token_not_forwarded|pairing_url_not_forwarded|proxy_missing_before_dial|tsnet_start_failed|tsnet_start_not_called|tsnet_start_not_finished|tsnet_state_dir_create|tsnet_state_file_missing|unexpected)
      die "instrumentation_$STAGE"
      ;;
    *) die instrumentation_failed_without_stage ;;
  esac
fi

[ "$NODE_COUNT" -eq 2 ] || die expected_two_headscale_nodes
[ "$DERP_ROUTE_PROOF" = forced_route_with_two_clients_and_packet_flow ] \
  || die derp_route_proof_missing
grep -q '^INSTRUMENTATION_RESULT: pairing_entry=onQrText_pass$' "$TASK_TMP/instrumentation.log" \
  || die pairing_entry_proof_missing
grep -q '^INSTRUMENTATION_RESULT: tsnet_state_file=yes$' "$TASK_TMP/instrumentation.log" \
  || die app_tsnet_state_proof_missing
grep -q '^INSTRUMENTATION_RESULT: connection_manager_ready=yes$' "$TASK_TMP/instrumentation.log" \
  || die connection_manager_proof_missing
grep -q '^INSTRUMENTATION_RESULT: workspace_cwd_match=yes$' "$TASK_TMP/instrumentation.log" \
  || die workspace_proof_missing
grep -q '^INSTRUMENTATION_RESULT: session_present=yes$' "$TASK_TMP/instrumentation.log" \
  || die session_proof_missing
grep -q '^INSTRUMENTATION_RESULT: socks5_path=explicit_tsnet_proxy_socket_factory$' "$TASK_TMP/instrumentation.log" \
  || die socks5_proof_missing

PAIRING_ENTRY_PROOF=onQrText_pass
APP_TSNET_STATE_PROOF=yes
CONNECTION_MANAGER_PROOF=ready
WORKSPACE_VISIBLE=yes
SESSION_VISIBLE=yes
SOCKS_LOOPBACK_PROOF=connection_manager_ready_via_explicit_tsnet_proxy_socket_factory

# headscale includes the reusable pre-auth key in every node record. Remove it
# in-stream so the authkey never reaches the durable artifact in the first place.
printf '%s\n' "$NODES_JSON" \
  | jq 'map(if .pre_auth_key? then .pre_auth_key |= del(.key) else . end)' \
  >"$ARTIFACT_DIR/headscale-nodes.json"
find "$TASK_TMP/daemon-state" -type f -print | sed "s|$TASK_TMP|TASK_TMP|g" | sort \
  >"$ARTIFACT_DIR/daemon-state-files.txt"
grep -E 'single-instance guard acquired|tailnet 已启用|tailnet 已入网' "$TASK_TMP/daemon.stderr" \
  | sed "s|$TASK_TMP|TASK_TMP|g" >"$ARTIFACT_DIR/daemon-summary.log"
grep '^INSTRUMENTATION_RESULT:' "$TASK_TMP/instrumentation.log" \
  >"$ARTIFACT_DIR/instrumentation-proof.txt"
{
  printf 'daemon_ts_authkey_flag_present=%s\n' "$ARGV_AUTHKEY_FLAG"
  printf 'daemon_authkey_value_present=%s\n' "$ARGV_AUTHKEY_VALUE"
  printf 'daemon_authkey_shape_present=%s\n' "$ARGV_AUTHKEY_SHAPE"
  printf 'daemon_pair_token_value_present=%s\n' "$ARGV_PAIR_TOKEN_VALUE"
  printf 'app_authkey_value_present=%s\n' "$APP_ARGV_AUTHKEY_VALUE"
  printf 'app_authkey_shape_present=%s\n' "$APP_ARGV_AUTHKEY_SHAPE"
  printf 'app_pair_token_value_present=%s\n' "$APP_ARGV_PAIR_TOKEN_VALUE"
} >"$ARTIFACT_DIR/argv-presence.txt"
{
  printf 'paired_url_class=tailnet_100_64_10\n'
  printf 'workspace_listing_received=%s\n' "$WORKSPACE_VISIBLE"
  printf 'isolated_session_received=%s\n' "$SESSION_VISIBLE"
  printf 'android_system_tailnet_route=not_used\n'
  printf 'socks5_path=%s\n' "$SOCKS_LOOPBACK_PROOF"
} >"$ARTIFACT_DIR/app-route-proof.txt"

RESULT_STATUS=pass
FAILURE_REASON=""
exit 0
