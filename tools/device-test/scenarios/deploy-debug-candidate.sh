#!/usr/bin/env bash
set -euo pipefail

source "${PRISM_DEVICE_TEST_COMMON:?}"
source "$(dirname "$PRISM_DEVICE_TEST_COMMON")/prism-device.sh"
require_device_test_environment

readonly PRISM_PACKAGE="com.yzddmr6.prismspace"
readonly REMOTE_APK="/data/local/tmp/prismspace-release-gate-candidate.apk"
readonly BASELINE_FILE="$PRISM_DEVICE_SCENARIO_DIR/baseline.properties"

precondition() {
  require_destructive_authorization
  local current_user
  current_user="$(device_adb shell am get-current-user | prism_trim_cr | tr -d '\n')"
  [[ "$current_user" == "0" ]] || { echo "Candidate deployment requires parent user 0" >&2; return 1; }

  local owners="$PRISM_DEVICE_SCENARIO_DIR/owners-before.txt"
  prism_capture_owners "$owners"
  local profile_id
  profile_id="$(prism_require_single_owned_profile "$owners" "$PRISM_PACKAGE")"
  [[ "$(prism_user_state "$profile_id")" == "RUNNING_UNLOCKED" ]] || {
    echo "Owned profile $profile_id is not running and unlocked" >&2
    return 1
  }
  local installed_path
  installed_path="$(prism_package_path 0 "$PRISM_PACKAGE")"
  [[ -n "$installed_path" ]] || { echo "PrismSpace is not installed in parent" >&2; return 1; }
  [[ "$(prism_package_path "$profile_id" "$PRISM_PACKAGE")" == "$installed_path" ]] || {
    echo "Parent and profile PrismSpace paths differ" >&2
    return 1
  }
  local root_id
  root_id="$(prism_root_shell id)"
  [[ "$root_id" == uid=0* ]] || { echo "Root unavailable for update-only deployment" >&2; return 1; }

  local old_hash
  old_hash="$(prism_root_shell "sha256sum $installed_path" | awk '{print $1}')"
  {
    printf 'profile_id=%s\n' "$profile_id"
    printf 'installed_path_before=%s\n' "$installed_path"
    printf 'installed_hash_before=%s\n' "$old_hash"
  } > "$BASELINE_FILE"
}

execute() {
  "$PRISM_DEVICE_REPOSITORY_ROOT/gradlew" \
    -p "$PRISM_DEVICE_REPOSITORY_ROOT" \
    --console=plain \
    :assembly:assembleCompleteNormalDebug \
    :assembly:assembleCompleteNormalDebugAndroidTest
  local candidate
  candidate="$(prism_find_single_apk \
    "$PRISM_DEVICE_REPOSITORY_ROOT/assembly/build/outputs/apk/completeNormal/debug" \
    "Complete Normal Debug")"
  local local_hash
  local_hash="$(sha256sum "$candidate" | awk '{print $1}')"

  prism_root_shell "rm -f $REMOTE_APK" >/dev/null
  device_adb push "$candidate" "$REMOTE_APK"
  local install_output
  install_output="$(prism_root_shell "pm install -r -t $REMOTE_APK")"
  printf '%s\n' "$install_output" > "$PRISM_DEVICE_SCENARIO_DIR/install.txt"
  [[ "$install_output" == *"Success"* ]] || {
    echo "Update-only candidate deployment failed: $install_output" >&2
    return 1
  }

  local profile_id owners installed_path installed_hash
  profile_id="$(sed -n 's/^profile_id=//p' "$BASELINE_FILE")"
  owners="$PRISM_DEVICE_SCENARIO_DIR/owners-after.txt"
  prism_capture_owners "$owners"
  [[ "$(prism_require_single_owned_profile "$owners" "$PRISM_PACKAGE")" == "$profile_id" ]] || {
    echo "Profile ownership changed during candidate deployment" >&2
    return 1
  }
  installed_path="$(prism_package_path 0 "$PRISM_PACKAGE")"
  [[ "$(prism_package_path "$profile_id" "$PRISM_PACKAGE")" == "$installed_path" ]] || return 1
  installed_hash="$(prism_root_shell "sha256sum $installed_path" | awk '{print $1}')"
  {
    printf 'candidate_apk=%s\n' "$candidate"
    printf 'candidate_sha256=%s\n' "$local_hash"
    printf 'installed_path_after=%s\n' "$installed_path"
    printf 'installed_sha256=%s\n' "$installed_hash"
  } > "$PRISM_DEVICE_SCENARIO_DIR/candidate.properties"
  [[ "$local_hash" =~ ^[0-9a-f]{64}$ && "$installed_hash" == "$local_hash" ]] || {
    echo "Candidate hash mismatch local=$local_hash installed=$installed_hash" >&2
    return 1
  }
}

cleanup() {
  local failed=0
  prism_root_shell "rm -f $REMOTE_APK" >/dev/null || failed=1
  prism_root_shell "test ! -e $REMOTE_APK" >/dev/null || failed=1
  return "$failed"
}

case "${1:-}" in
  precondition) precondition ;;
  execute) execute ;;
  cleanup) cleanup ;;
  *) exit 2 ;;
esac
