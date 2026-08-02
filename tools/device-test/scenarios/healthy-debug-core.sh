#!/usr/bin/env bash
set -euo pipefail

source "${PRISM_DEVICE_TEST_COMMON:?}"
require_device_test_environment

readonly PRISM_PACKAGE="com.yzddmr6.prismspace"
readonly TEST_PACKAGE="com.yzddmr6.prismspace.test"
readonly PROBE_PACKAGE="com.yzddmr6.prismprobe"
readonly REMOTE_TEST_APK="/data/local/tmp/prismspace-core-regression-test.apk"
readonly MAIN_TEST_FILE_PREFIX="prismspace-bridge-main"
readonly PROFILE_TEST_FILE_PREFIX="prismspace-bridge-clone"
readonly BASELINE_FILE="$PRISM_DEVICE_SCENARIO_DIR/baseline.properties"
readonly PROBE_INSTALL_INTENT="$PRISM_DEVICE_SCENARIO_DIR/probe-install-intent"
readonly DOWNLOADS_URI="content://media/external/downloads"

trim_cr() {
  tr -d '\r'
}

package_path() {
  local user_id="$1"
  local package_name="$2"
  device_adb shell "pm path --user $user_id $package_name" \
    | trim_cr \
    | sed -n 's/^package://p' \
    | sed -n '1p'
}

package_present() {
  [[ -n "$(package_path "$1" "$2")" ]]
}

root_shell() {
  local command="$1"
  device_adb shell "su -c '$command'" | trim_cr
}

content_shell() {
  local command="$1"
  device_adb shell "$command" | trim_cr
}

read_baseline() {
  local key="$1"
  sed -n "s/^${key}=//p" "$BASELINE_FILE" | sed -n '1p'
}

profile_owner_package() {
  local owners_file="$1"
  local user_id="$2"
  awk -v header="Profile Owner (User $user_id)" '
    index($0, header) { in_owner = 1; next }
    in_owner && /package=/ {
      sub(/^.*package=/, "")
      sub(/[[:space:]]+$/, "")
      print
      exit
    }
  ' "$owners_file"
}

find_single_apk() {
  local directory="$1"
  local description="$2"
  local -a apks=()
  while IFS= read -r path; do
    apks+=("$path")
  done < <(find "$directory" -maxdepth 1 -type f -name '*.apk' -print | sort)
  if ((${#apks[@]} != 1)); then
    echo "Expected one $description APK under $directory, found ${#apks[@]}" >&2
    return 1
  fi
  printf '%s\n' "${apks[0]}"
}

delete_test_media() {
  local user_id="$1"
  local display_name_prefix="$2"
  local query_output
  query_output="$(content_shell "content query --user $user_id --uri $DOWNLOADS_URI --projection _id:_display_name:relative_path")" || return

  local -a row_ids=()
  while IFS= read -r row_id; do
    [[ -n "$row_id" ]] && row_ids+=("$row_id")
  done < <(
    printf '%s\n' "$query_output" \
      | grep -E "_display_name=${display_name_prefix}( \\([0-9]+\\))?\\.txt," \
      | sed -n 's/.*_id=\([0-9][0-9]*\),.*/\1/p' \
      || true
  )

  local row_id
  for row_id in "${row_ids[@]}"; do
    content_shell "content delete --user $user_id --uri $DOWNLOADS_URI/$row_id" >/dev/null || return
  done
}

verify_test_media_absent() {
  local user_id="$1"
  local display_name_prefix="$2"
  local query_output
  query_output="$(content_shell "content query --user $user_id --uri $DOWNLOADS_URI --projection _id:_display_name:relative_path")" || return
  if printf '%s\n' "$query_output" \
      | grep -E "_display_name=${display_name_prefix}( \\([0-9]+\\))?\\.txt," >/dev/null; then
    echo "Test MediaStore residue remains for user=$user_id prefix=$display_name_prefix" >&2
    return 1
  fi
}

precondition() {
  local current_user
  current_user="$(device_adb shell am get-current-user | trim_cr | tr -d '\n')"
  if [[ "$current_user" != "0" ]]; then
    echo "Core regression requires parent user 0 in foreground, got $current_user" >&2
    return 1
  fi

  local owners_file="$PRISM_DEVICE_SCENARIO_DIR/device-policy-owners.txt"
  trim_cr < "$PRISM_DEVICE_RUN_DIR/preflight/device-policy-owners.stdout" > "$owners_file"
  local -a profile_ids=()
  while IFS= read -r profile_id; do
    profile_ids+=("$profile_id")
  done < <(sed -n 's/^  Profile Owner (User \([0-9][0-9]*\)):.*/\1/p' "$owners_file")
  if ((${#profile_ids[@]} != 1)); then
    echo "Expected exactly one managed profile owner, found ${#profile_ids[@]}" >&2
    return 1
  fi
  local profile_id="${profile_ids[0]}"
  local owner_package
  owner_package="$(profile_owner_package "$owners_file" "$profile_id")"
  if [[ "$owner_package" != "$PRISM_PACKAGE" ]]; then
    echo "Profile $profile_id is owned by '$owner_package', not PrismSpace" >&2
    return 1
  fi

  local profile_state
  profile_state="$(device_adb shell "am get-started-user-state $profile_id" | trim_cr | tr -d '\n')"
  printf '%s\n' "$profile_state" > "$PRISM_DEVICE_SCENARIO_DIR/profile-state.txt"
  if [[ "$profile_state" != "RUNNING_UNLOCKED" ]]; then
    echo "Profile $profile_id must be RUNNING_UNLOCKED, got $profile_state" >&2
    return 1
  fi

  local parent_path profile_path probe_parent_path
  parent_path="$(package_path 0 "$PRISM_PACKAGE")"
  profile_path="$(package_path "$profile_id" "$PRISM_PACKAGE")"
  probe_parent_path="$(package_path 0 "$PROBE_PACKAGE")"
  if [[ -z "$parent_path" || -z "$profile_path" || -z "$probe_parent_path" ]]; then
    echo "PrismSpace must be installed in both users and Probe must be installed in parent" >&2
    return 1
  fi
  if [[ "$parent_path" != "$profile_path" ]]; then
    echo "Parent/profile PrismSpace package paths differ" >&2
    return 1
  fi
  if [[ ! "$parent_path" =~ ^/data/app/[A-Za-z0-9._~+/=-]+/base\.apk$ ]]; then
    echo "Unexpected installed PrismSpace base path: $parent_path" >&2
    return 1
  fi

  local package_flags
  package_flags="$(device_adb shell "dumpsys package $PRISM_PACKAGE | grep pkgFlags" | trim_cr)"
  printf '%s\n' "$package_flags" > "$PRISM_DEVICE_SCENARIO_DIR/package-flags.txt"
  if [[ "$package_flags" != *"DEBUGGABLE"* ]]; then
    echo "Installed PrismSpace package is not debuggable" >&2
    return 1
  fi

  local root_id
  root_id="$(root_shell id)"
  printf '%s\n' "$root_id" > "$PRISM_DEVICE_SCENARIO_DIR/root-id.txt"
  if [[ "$root_id" != uid=0* ]]; then
    echo "Root is unavailable for test-fixture deployment: $root_id" >&2
    return 1
  fi

  local probe_profile_present=0
  if package_present "$profile_id" "$PROBE_PACKAGE"; then
    probe_profile_present=1
  fi
  {
    printf 'profile_id=%s\n' "$profile_id"
    printf 'installed_base_path=%s\n' "$parent_path"
    printf 'probe_profile_present=%s\n' "$probe_profile_present"
  } > "$BASELINE_FILE"
  printf 'profile=%s state=%s probe_profile_present=%s\n' \
    "$profile_id" "$profile_state" "$probe_profile_present"
}

execute() {
  require_nonempty_file "$BASELINE_FILE" "core-regression baseline"
  command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; return 1; }
  command -v find >/dev/null || { echo "find is required" >&2; return 1; }
  if [[ ! -x "$PRISM_DEVICE_REPOSITORY_ROOT/gradlew" ]]; then
    echo "Gradle wrapper is unavailable under $PRISM_DEVICE_REPOSITORY_ROOT" >&2
    return 1
  fi

  local profile_id installed_base_path
  profile_id="$(read_baseline profile_id)"
  installed_base_path="$(read_baseline installed_base_path)"
  if [[ ! "$profile_id" =~ ^[0-9]+$ || -z "$installed_base_path" ]]; then
    echo "Invalid core-regression baseline" >&2
    return 1
  fi

  "$PRISM_DEVICE_REPOSITORY_ROOT/gradlew" \
    -p "$PRISM_DEVICE_REPOSITORY_ROOT" \
    --console=plain \
    :assembly:assembleCompleteNormalDebug \
    :assembly:assembleCompleteNormalDebugAndroidTest

  local candidate_apk test_apk local_hash installed_hash
  candidate_apk="$(find_single_apk \
    "$PRISM_DEVICE_REPOSITORY_ROOT/assembly/build/outputs/apk/completeNormal/debug" \
    "Complete Normal Debug")"
  test_apk="$(find_single_apk \
    "$PRISM_DEVICE_REPOSITORY_ROOT/assembly/build/outputs/apk/androidTest/completeNormal/debug" \
    "Complete Normal Debug AndroidTest")"
  local_hash="$(sha256sum "$candidate_apk" | awk '{print $1}')"
  installed_hash="$(root_shell "sha256sum $installed_base_path" | awk '{print $1}')"
  {
    printf 'candidate.apk=%s\n' "$candidate_apk"
    printf 'candidate.sha256=%s\n' "$local_hash"
    printf 'installed.path=%s\n' "$installed_base_path"
    printf 'installed.sha256=%s\n' "$installed_hash"
    printf 'android_test.apk=%s\n' "$test_apk"
  } > "$PRISM_DEVICE_SCENARIO_DIR/candidate.properties"
  if [[ ! "$local_hash" =~ ^[0-9a-f]{64}$ || "$local_hash" != "$installed_hash" ]]; then
    echo "Installed candidate mismatch: local=$local_hash installed=$installed_hash" >&2
    return 1
  fi

  delete_test_media 0 "$MAIN_TEST_FILE_PREFIX"
  delete_test_media "$profile_id" "$PROFILE_TEST_FILE_PREFIX"
  root_shell "pm uninstall --user 0 $TEST_PACKAGE" >/dev/null 2>&1 || true
  root_shell "rm -f $REMOTE_TEST_APK"

  device_adb push "$test_apk" "$REMOTE_TEST_APK"
  local install_output
  install_output="$(root_shell "pm install -r -t --user 0 $REMOTE_TEST_APK")"
  printf '%s\n' "$install_output" > "$PRISM_DEVICE_SCENARIO_DIR/test-install.txt"
  if [[ "$install_output" != *"Success"* ]]; then
    echo "Instrumentation package install failed: $install_output" >&2
    return 1
  fi

  if [[ "$(read_baseline probe_profile_present)" == "0" ]]; then
    : > "$PROBE_INSTALL_INTENT"
    local probe_output
    probe_output="$(root_shell "cmd package install-existing --user $profile_id $PROBE_PACKAGE")"
    printf '%s\n' "$probe_output" > "$PRISM_DEVICE_SCENARIO_DIR/probe-install.txt"
    if ! package_present "$profile_id" "$PROBE_PACKAGE"; then
      echo "Probe fixture was not installed for profile $profile_id: $probe_output" >&2
      return 1
    fi
  fi

  local instrumentation_output="$PRISM_DEVICE_SCENARIO_DIR/instrumentation.txt"
  local instrumentation_code
  set +e
  device_adb shell \
    "am instrument -w -r $TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" \
    | trim_cr > "$instrumentation_output"
  instrumentation_code=${PIPESTATUS[0]}
  set -e
  cat "$instrumentation_output"
  if ((instrumentation_code != 0)) || \
     grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$instrumentation_output" >/dev/null || \
     ! grep -E '^OK \([1-9][0-9]* tests?\)$' "$instrumentation_output" >/dev/null; then
    echo "AndroidJUnit core regression failed (exit=$instrumentation_code)" >&2
    return 1
  fi
}

cleanup() {
  local cleanup_failed=0
  local profile_id=""
  local probe_was_present=""
  if [[ -s "$BASELINE_FILE" ]]; then
    profile_id="$(read_baseline profile_id)"
    probe_was_present="$(read_baseline probe_profile_present)"
  fi

  root_shell "pm uninstall --user 0 $TEST_PACKAGE" >/dev/null 2>&1 || true
  root_shell "rm -f $REMOTE_TEST_APK" >/dev/null || cleanup_failed=1

  if [[ "$profile_id" =~ ^[0-9]+$ ]]; then
    delete_test_media 0 "$MAIN_TEST_FILE_PREFIX" || cleanup_failed=1
    delete_test_media "$profile_id" "$PROFILE_TEST_FILE_PREFIX" || cleanup_failed=1
    if [[ -f "$PROBE_INSTALL_INTENT" ]]; then
      root_shell "pm uninstall --user $profile_id $PROBE_PACKAGE" >/dev/null 2>&1 || true
    fi

    verify_test_media_absent 0 "$MAIN_TEST_FILE_PREFIX" || cleanup_failed=1
    verify_test_media_absent "$profile_id" "$PROFILE_TEST_FILE_PREFIX" || cleanup_failed=1

    local probe_present_after=0
    if package_present "$profile_id" "$PROBE_PACKAGE"; then
      probe_present_after=1
    fi
    if [[ "$probe_was_present" =~ ^[01]$ && "$probe_present_after" != "$probe_was_present" ]]; then
      echo "Probe profile installation was not restored: before=$probe_was_present after=$probe_present_after" >&2
      cleanup_failed=1
    fi
  fi

  if package_present 0 "$TEST_PACKAGE"; then
    echo "Instrumentation package remains installed" >&2
    cleanup_failed=1
  fi
  if ! root_shell "test ! -e $REMOTE_TEST_APK" >/dev/null; then
    echo "Remote instrumentation APK remains at $REMOTE_TEST_APK" >&2
    cleanup_failed=1
  fi
  return "$cleanup_failed"
}

case "${1:-}" in
  precondition) precondition ;;
  execute) execute ;;
  cleanup) cleanup ;;
  *)
    echo "Usage: $0 precondition|execute|cleanup" >&2
    exit 2
    ;;
esac
