#!/usr/bin/env bash
set -euo pipefail

source "${PRISM_DEVICE_TEST_COMMON:?}"
source "$(dirname "$PRISM_DEVICE_TEST_COMMON")/prism-device.sh"
require_device_test_environment

readonly PRISM_PACKAGE="com.yzddmr6.prismspace"
readonly MAIN_COMPONENT="$PRISM_PACKAGE/.MainActivity"
readonly PROFILE_COMPONENT="$PRISM_PACKAGE/.settings.PrismSettingsActivity"
readonly PROFILE_ID_FILE="$PRISM_DEVICE_SCENARIO_DIR/profile-id.txt"
readonly REMOTE_HIERARCHY="/data/local/tmp/prismspace-release-gate-window.xml"

focused_application() {
  device_adb shell dumpsys window \
    | prism_trim_cr \
    | awk '/mFocusedApp=/ { if (!found) print; found = 1 }'
}

wake_display() {
  prism_root_shell "cmd power wakeup" >/dev/null
  prism_root_shell "wm dismiss-keyguard" >/dev/null
}

capture_observation() {
  local name="$1"
  local hierarchy_output="$PRISM_DEVICE_SCENARIO_DIR/$name-window.xml"
  local screenshot_output="$PRISM_DEVICE_SCENARIO_DIR/$name-screen.png"

  device_adb shell "uiautomator dump $REMOTE_HIERARCHY" >/dev/null
  device_adb pull "$REMOTE_HIERARCHY" "$hierarchy_output" >/dev/null
  prism_root_shell "rm -f $REMOTE_HIERARCHY" >/dev/null
  require_nonempty_file "$hierarchy_output" "$name UI hierarchy"
  grep -F '<hierarchy' "$hierarchy_output" >/dev/null || {
    echo "$name UI hierarchy is malformed" >&2
    return 1
  }

  device_adb exec-out screencap -p > "$screenshot_output"
  require_nonempty_file "$screenshot_output" "$name screenshot"
}

capture_surface() {
  local name="$1"
  local expected_user="$2"
  local activity_output="$PRISM_DEVICE_SCENARIO_DIR/$name-focused-app.txt"
  focused_application > "$activity_output"
  require_nonempty_file "$activity_output" "$name focused application"
  grep -F "$PRISM_PACKAGE" "$activity_output" >/dev/null || {
    echo "$name focused application is not PrismSpace: $(<"$activity_output")" >&2
    return 1
  }
  grep -E " u${expected_user} " "$activity_output" >/dev/null || {
    echo "$name focused application is not user $expected_user: $(<"$activity_output")" >&2
    return 1
  }

  capture_observation "$name"
}

dismiss_verified_permission_prompt_without_answering() {
  local profile_id="$1"
  local focused_output="$PRISM_DEVICE_SCENARIO_DIR/profile-permission-focused-app.txt"
  local activities_output="$PRISM_DEVICE_SCENARIO_DIR/profile-permission-activities.txt"
  local permission_package

  focused_application > "$focused_output"
  grep -E " u${profile_id} " "$focused_output" >/dev/null || {
    echo "Profile first-launch surface belongs to another user: $(<"$focused_output")" >&2
    return 1
  }
  device_adb shell dumpsys activity activities | prism_trim_cr > "$activities_output"
  grep -F 'act=android.content.pm.action.REQUEST_PERMISSIONS' "$activities_output" >/dev/null || return 1
  grep -F "launchedFromPackage=$PRISM_PACKAGE" "$activities_output" >/dev/null || return 1
  grep -F "u${profile_id} $PROFILE_COMPONENT" "$activities_output" >/dev/null || return 1
  permission_package="$(sed -n 's/.* u[0-9][0-9]* \([^/ ]*\)\/.*/\1/p' "$focused_output" | sed -n '1p')"
  [[ "$permission_package" =~ ^[A-Za-z0-9._]+$ && "$permission_package" != "$PRISM_PACKAGE" ]] || {
    echo "Cannot identify the verified permission-controller package" >&2
    return 1
  }

  capture_observation profile-permission
  printf '%s\n' "$permission_package" > "$PRISM_DEVICE_SCENARIO_DIR/permission-controller-package.txt"
  prism_root_shell "am force-stop --user $profile_id $permission_package" >/dev/null
  device_adb shell "am force-stop --user $profile_id $PRISM_PACKAGE" >/dev/null
}

precondition() {
  local current_user
  current_user="$(device_adb shell am get-current-user | prism_trim_cr | tr -d '\n')"
  [[ "$current_user" == "0" ]] || { echo "UI smoke requires parent user 0" >&2; return 1; }
  local owners="$PRISM_DEVICE_SCENARIO_DIR/owners.txt"
  prism_capture_owners "$owners"
  local profile_id
  profile_id="$(prism_require_single_owned_profile "$owners" "$PRISM_PACKAGE")"
  [[ "$(prism_user_state "$profile_id")" == "RUNNING_UNLOCKED" ]] || return 1
  printf '%s\n' "$profile_id" > "$PROFILE_ID_FILE"
}

execute() {
  local profile_id
  profile_id="$(<"$PROFILE_ID_FILE")"
  wake_display
  device_adb shell "am force-stop --user 0 $PRISM_PACKAGE" >/dev/null
  device_adb shell "am start --user 0 -W -n $MAIN_COMPONENT" \
    | prism_trim_cr > "$PRISM_DEVICE_SCENARIO_DIR/parent-launch.txt"
  sleep 1
  capture_surface parent 0

  device_adb shell "am force-stop --user $profile_id $PRISM_PACKAGE" >/dev/null
  device_adb shell "am start --user $profile_id -W -n $PROFILE_COMPONENT" \
    | prism_trim_cr > "$PRISM_DEVICE_SCENARIO_DIR/profile-launch.txt"
  sleep 1
  if ! focused_application | grep -F "$PRISM_PACKAGE" >/dev/null; then
    dismiss_verified_permission_prompt_without_answering "$profile_id"
    device_adb shell "am start --user $profile_id -W -n $PROFILE_COMPONENT" \
      | prism_trim_cr > "$PRISM_DEVICE_SCENARIO_DIR/profile-relaunch.txt"
    sleep 1
  fi
  capture_surface profile "$profile_id"
}

cleanup() {
  local failed=0
  local profile_id=""
  [[ -s "$PROFILE_ID_FILE" ]] && profile_id="$(<"$PROFILE_ID_FILE")"
  device_adb shell "am force-stop --user 0 $PRISM_PACKAGE" >/dev/null || failed=1
  if [[ "$profile_id" =~ ^[0-9]+$ ]]; then
    device_adb shell "am force-stop --user $profile_id $PRISM_PACKAGE" >/dev/null || failed=1
    if [[ -s "$PRISM_DEVICE_SCENARIO_DIR/permission-controller-package.txt" ]]; then
      local permission_package
      permission_package="$(<"$PRISM_DEVICE_SCENARIO_DIR/permission-controller-package.txt")"
      if [[ "$permission_package" =~ ^[A-Za-z0-9._]+$ && "$permission_package" != "$PRISM_PACKAGE" ]]; then
        prism_root_shell "am force-stop --user $profile_id $permission_package" >/dev/null || failed=1
      else
        failed=1
      fi
    fi
  fi
  prism_root_shell "rm -f $REMOTE_HIERARCHY" >/dev/null || failed=1
  return "$failed"
}

case "${1:-}" in
  precondition) precondition ;;
  execute) execute ;;
  cleanup) cleanup ;;
  *) exit 2 ;;
esac
