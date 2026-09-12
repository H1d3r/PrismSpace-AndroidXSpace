#!/usr/bin/env bash
set -euo pipefail

source "${PRISM_DEVICE_TEST_COMMON:?}"
source "$(dirname "$PRISM_DEVICE_TEST_COMMON")/prism-device.sh"
require_device_test_environment

readonly PRISM_PACKAGE="com.yzddmr6.prismspace"
readonly TEST_PACKAGE="com.yzddmr6.prismspace.test"
readonly REMOTE_TEST_APK="/data/local/tmp/prismspace-release-gate-lifecycle-test.apk"
readonly BASELINE_FILE="$PRISM_DEVICE_SCENARIO_DIR/baseline.properties"
readonly DELETE_METHOD="com.yzddmr6.prismspace.device.ProfileLifecycleDriverTest#deleteCurrentProfileThroughProductionPath"
readonly CREATE_METHOD="com.yzddmr6.prismspace.device.ProfileLifecycleDriverTest#createProfileThroughProductionPath"

read_baseline() {
  sed -n "s/^$1=//p" "$BASELINE_FILE" | sed -n '1p'
}

read_verifier_setting() {
  device_adb shell settings get global verifier_verify_adb_installs \
    | prism_trim_cr \
    | tr -d '\n'
}

read_max_users_property() {
  device_adb shell getprop fw.max_users | prism_trim_cr | tr -d '\n'
}

restore_side_effects() {
  local verifier="$1"
  local max_users="$2"
  if [[ "$verifier" == "null" || -z "$verifier" ]]; then
    prism_root_shell "settings delete global verifier_verify_adb_installs" >/dev/null
  else
    [[ "$verifier" =~ ^-?[0-9]+$ ]] || { echo "Unsafe verifier baseline: $verifier" >&2; return 1; }
    prism_root_shell "settings put global verifier_verify_adb_installs $verifier" >/dev/null
  fi
  [[ -z "$max_users" || "$max_users" =~ ^[0-9]+$ ]] || {
    echo "Unsafe fw.max_users baseline: $max_users" >&2
    return 1
  }
  prism_root_shell "setprop fw.max_users \"$max_users\"" >/dev/null
}

test_apk_path() {
  prism_find_single_apk \
    "$PRISM_DEVICE_REPOSITORY_ROOT/assembly/build/outputs/apk/androidTest/completeNormal/debug" \
    "Complete Normal Debug AndroidTest"
}

install_test_driver() {
  local user_id="$1"
  local test_apk
  test_apk="$(test_apk_path)"
  prism_root_shell "rm -f $REMOTE_TEST_APK" >/dev/null
  device_adb push "$test_apk" "$REMOTE_TEST_APK"
  local install_output
  install_output="$(prism_root_shell "pm install -r -t --user 0 $REMOTE_TEST_APK")"
  [[ "$install_output" == *"Success"* ]] || {
    echo "Lifecycle test package install failed: $install_output" >&2
    return 1
  }
  if [[ "$user_id" != "0" ]]; then
    prism_root_shell "cmd package install-existing --user $user_id $TEST_PACKAGE" >/dev/null
    prism_package_present "$user_id" "$TEST_PACKAGE" || {
      echo "Lifecycle test package is absent from user $user_id" >&2
      return 1
    }
  fi
}

run_driver() {
  local user_id="$1"
  local method="$2"
  local output_path="$3"
  local code
  set +e
  device_adb shell \
    "am instrument --user $user_id -w -r -e class $method $TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" \
    | prism_trim_cr > "$output_path"
  code=${PIPESTATUS[0]}
  set -e
  printf '%s\n' "$code" > "$output_path.exit-code"
}

driver_has_explicit_failure() {
  local output_path="$1"
  grep -E 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2' \
    "$output_path" >/dev/null 2>&1
}

current_structural_profile() {
  local owners_path="$1"
  prism_capture_owners "$owners_path" >/dev/null 2>&1 || return 1
  local profile_id
  profile_id="$(prism_require_single_owned_profile "$owners_path" "$PRISM_PACKAGE" 2>/dev/null || true)"
  [[ "$profile_id" =~ ^[0-9]+$ ]] || return 1
  [[ "$(prism_user_state "$profile_id" 2>/dev/null || true)" == "RUNNING_UNLOCKED" ]] || return 1
  prism_package_present "$profile_id" "$PRISM_PACKAGE" || return 1
  printf '%s\n' "$profile_id"
}

precondition() {
  require_destructive_authorization
  local current_user
  current_user="$(device_adb shell am get-current-user | prism_trim_cr | tr -d '\n')"
  [[ "$current_user" == "0" ]] || { echo "Lifecycle requires parent user 0" >&2; return 1; }
  [[ "$(prism_root_shell id)" == uid=0* ]] || { echo "Lifecycle requires root" >&2; return 1; }

  local owners="$PRISM_DEVICE_SCENARIO_DIR/owners-before.txt"
  prism_capture_owners "$owners"
  local profile_id
  profile_id="$(prism_require_single_owned_profile "$owners" "$PRISM_PACKAGE")"
  [[ "$(prism_user_state "$profile_id")" == "RUNNING_UNLOCKED" ]] || return 1
  prism_package_present "$profile_id" "$PRISM_PACKAGE" || return 1

  local -a user_ids=()
  while IFS= read -r user_id; do user_ids+=("$user_id"); done < <(prism_user_ids)
  if ((${#user_ids[@]} != 2)) || [[ "${user_ids[0]}" != "0" || "${user_ids[1]}" != "$profile_id" ]]; then
    echo "Lifecycle authority requires exactly parent 0 and owned profile $profile_id; users=${user_ids[*]}" >&2
    return 1
  fi

  local candidate installed_path local_hash installed_hash
  candidate="$(prism_find_single_apk \
    "$PRISM_DEVICE_REPOSITORY_ROOT/assembly/build/outputs/apk/completeNormal/debug" \
    "Complete Normal Debug")"
  installed_path="$(prism_package_path 0 "$PRISM_PACKAGE")"
  local_hash="$(sha256sum "$candidate" | awk '{print $1}')"
  installed_hash="$(prism_root_shell "sha256sum $installed_path" | awk '{print $1}')"
  [[ "$local_hash" == "$installed_hash" ]] || {
    echo "Lifecycle candidate mismatch local=$local_hash installed=$installed_hash" >&2
    return 1
  }

  {
    printf 'old_profile_id=%s\n' "$profile_id"
    printf 'verifier=%s\n' "$(read_verifier_setting)"
    printf 'fw_max_users=%s\n' "$(read_max_users_property)"
    printf 'candidate_sha256=%s\n' "$local_hash"
  } > "$BASELINE_FILE"
}

execute() {
  local old_profile_id
  old_profile_id="$(read_baseline old_profile_id)"
  install_test_driver "$old_profile_id"

  local delete_output="$PRISM_DEVICE_SCENARIO_DIR/delete-driver.txt"
  run_driver "$old_profile_id" "$DELETE_METHOD" "$delete_output"
  prism_wait_for_user_absent "$old_profile_id" 240
  if driver_has_explicit_failure "$delete_output"; then
    echo "Profile deletion driver reported an explicit failure" >&2
    return 1
  fi

  local owners_after_delete="$PRISM_DEVICE_SCENARIO_DIR/owners-after-delete.txt"
  prism_capture_owners "$owners_after_delete"
  local stale_or_live_owner
  while IFS= read -r stale_or_live_owner; do
    if prism_user_exists "$stale_or_live_owner"; then
      echo "Live PrismSpace profile owner remains after deleting user $old_profile_id: $stale_or_live_owner" >&2
      return 1
    fi
  done < <(prism_owned_profile_ids "$owners_after_delete" "$PRISM_PACKAGE")

  local create_output="$PRISM_DEVICE_SCENARIO_DIR/create-driver.txt"
  printf 'production_create_started=true\n' > "$PRISM_DEVICE_SCENARIO_DIR/creation-attempted.properties"
  run_driver 0 "$CREATE_METHOD" "$create_output"
  local new_profile_id
  new_profile_id="$(prism_wait_for_healthy_owner \
    "$PRISM_PACKAGE" "$PRISM_DEVICE_SCENARIO_DIR/owners-after-create.txt" 300)"
  [[ "$new_profile_id" != "$old_profile_id" ]] || {
    echo "Profile recreation reused the removed user id unexpectedly" >&2
    return 1
  }
  printf '%s\n' "$new_profile_id" > "$PRISM_DEVICE_SCENARIO_DIR/new-profile-id.txt"
  if driver_has_explicit_failure "$create_output"; then
    echo "Profile creation driver reported an explicit failure" >&2
    return 1
  fi

  local profile_path profile_hash
  profile_path="$(prism_package_path "$new_profile_id" "$PRISM_PACKAGE")"
  profile_hash="$(prism_root_shell "sha256sum $profile_path" | awk '{print $1}')"
  [[ "$profile_hash" == "$(read_baseline candidate_sha256)" ]] || {
    echo "Recreated profile PrismSpace hash mismatch" >&2
    return 1
  }

  local verifier_after max_users_after
  verifier_after="$(read_verifier_setting)"
  max_users_after="$(read_max_users_property)"
  {
    printf 'new_profile_id=%s\n' "$new_profile_id"
    printf 'verifier_after=%s\n' "$verifier_after"
    printf 'fw_max_users_after=%s\n' "$max_users_after"
    printf 'profile_sha256=%s\n' "$profile_hash"
  } > "$PRISM_DEVICE_SCENARIO_DIR/lifecycle-result.properties"
  [[ "$verifier_after" == "$(read_baseline verifier)" ]] || {
    echo "verifier_verify_adb_installs was not restored" >&2
    return 1
  }
  [[ "$max_users_after" == "$(read_baseline fw_max_users)" ]] || {
    echo "fw.max_users was not restored" >&2
    return 1
  }
}

recover_profile_if_needed() {
  local old_profile_id="$1"
  local current_profile
  current_profile="$(current_structural_profile "$PRISM_DEVICE_SCENARIO_DIR/owners-cleanup-before.txt" || true)"
  if [[ "$current_profile" =~ ^[0-9]+$ ]]; then
    return 0
  fi

  if prism_user_exists "$old_profile_id"; then
    device_adb shell "am start-user -w $old_profile_id" >/dev/null || true
    current_profile="$(current_structural_profile "$PRISM_DEVICE_SCENARIO_DIR/owners-cleanup-old.txt" || true)"
    [[ "$current_profile" =~ ^[0-9]+$ ]] && return 0
    echo "Original profile $old_profile_id remains but is not structurally healthy" >&2
    return 1
  fi

  [[ -f "$PRISM_DEVICE_SCENARIO_DIR/creation-attempted.properties" ]] || {
    echo "Refusing to remove an unattributed Android user during recovery" >&2
    return 1
  }
  local users_verbose="$PRISM_DEVICE_SCENARIO_DIR/users-before-recovery.txt"
  prism_capture_verbose_users "$users_verbose" || return 1
  local user_id
  while IFS= read -r user_id; do
    [[ "$user_id" == "0" ]] && continue
    if ! prism_user_is_managed_profile_of_parent "$users_verbose" "$user_id" 0; then
      echo "Refusing to remove non-managed or unrelated Android user $user_id" >&2
      return 1
    fi
    printf '%s\n' "$user_id" >> "$PRISM_DEVICE_SCENARIO_DIR/recovery-removal-candidates.txt"
    prism_root_shell "pm remove-user $user_id" >/dev/null || return 1
    prism_wait_for_user_absent "$user_id" 120 || return 1
  done < <(prism_user_ids)

  install_test_driver 0 || return 1
  run_driver 0 "$CREATE_METHOD" "$PRISM_DEVICE_SCENARIO_DIR/create-recovery-driver.txt"
  current_profile="$(prism_wait_for_healthy_owner \
    "$PRISM_PACKAGE" "$PRISM_DEVICE_SCENARIO_DIR/owners-cleanup-recreated.txt" 300)" || return 1
  printf '%s\n' "$current_profile" > "$PRISM_DEVICE_SCENARIO_DIR/recovery-profile-id.txt"
}

cleanup() {
  local failed=0
  local old_profile_id=""
  local verifier=""
  local max_users=""
  if [[ -s "$BASELINE_FILE" ]]; then
    old_profile_id="$(read_baseline old_profile_id)"
    verifier="$(read_baseline verifier)"
    max_users="$(read_baseline fw_max_users)"
  fi

  if [[ "$old_profile_id" =~ ^[0-9]+$ ]]; then
    recover_profile_if_needed "$old_profile_id" || failed=1
    restore_side_effects "$verifier" "$max_users" || failed=1
  fi

  prism_root_shell "pm uninstall --user 0 $TEST_PACKAGE" >/dev/null 2>&1 || true
  prism_root_shell "rm -f $REMOTE_TEST_APK" >/dev/null || failed=1
  prism_package_present 0 "$TEST_PACKAGE" && failed=1
  prism_root_shell "test ! -e $REMOTE_TEST_APK" >/dev/null || failed=1

  if [[ "$old_profile_id" =~ ^[0-9]+$ ]]; then
    [[ "$(read_verifier_setting)" == "$verifier" ]] || failed=1
    [[ "$(read_max_users_property)" == "$max_users" ]] || failed=1
    local final_profile
    final_profile="$(current_structural_profile "$PRISM_DEVICE_SCENARIO_DIR/owners-final.txt" || true)"
    [[ "$final_profile" =~ ^[0-9]+$ ]] || failed=1
    printf 'final_profile_id=%s\nverifier=%s\nfw_max_users=%s\n' \
      "$final_profile" "$(read_verifier_setting)" "$(read_max_users_property)" \
      > "$PRISM_DEVICE_SCENARIO_DIR/final-state.properties"
  fi
  return "$failed"
}

case "${1:-}" in
  precondition) precondition ;;
  execute) execute ;;
  cleanup) cleanup ;;
  *) exit 2 ;;
esac
