#!/usr/bin/env bash

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "prism-device.sh must be sourced by a device-test scenario" >&2
  exit 2
fi

prism_trim_cr() {
  tr -d '\r'
}

prism_root_shell() {
  local command="$1"
  device_adb shell "su -c '$command'" | prism_trim_cr
}

prism_package_path() {
  local user_id="$1"
  local package_name="$2"
  device_adb shell "pm path --user $user_id $package_name" \
    | prism_trim_cr \
    | sed -n 's/^package://p' \
    | sed -n '1p'
}

prism_package_present() {
  [[ -n "$(prism_package_path "$1" "$2")" ]]
}

prism_capture_owners() {
  local output_path="$1"
  device_adb shell "dumpsys device_policy | sed -n '1,/^  Admin Services:/p'" \
    | prism_trim_cr > "$output_path"
  require_nonempty_file "$output_path" "device-policy owners"
}

prism_profile_owner_package() {
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

prism_owned_profile_ids() {
  local owners_file="$1"
  local package_name="$2"
  local user_id
  while IFS= read -r user_id; do
    if [[ "$(prism_profile_owner_package "$owners_file" "$user_id")" == "$package_name" ]]; then
      printf '%s\n' "$user_id"
    fi
  done < <(sed -n 's/^  Profile Owner (User \([0-9][0-9]*\)):.*/\1/p' "$owners_file")
}

prism_require_single_owned_profile() {
  local owners_file="$1"
  local package_name="$2"
  local -a ids=()
  while IFS= read -r user_id; do
    ids+=("$user_id")
  done < <(prism_owned_profile_ids "$owners_file" "$package_name")
  if ((${#ids[@]} != 1)); then
    echo "Expected exactly one $package_name profile owner, found ${#ids[@]}" >&2
    return 1
  fi
  printf '%s\n' "${ids[0]}"
}

prism_user_ids() {
  device_adb shell pm list users \
    | prism_trim_cr \
    | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p' \
    | sort -n -u
}

prism_user_exists() {
  local expected="$1"
  prism_user_ids | grep -Fx "$expected" >/dev/null
}

prism_capture_verbose_users() {
  local output_path="$1"
  device_adb shell cmd user list -v | prism_trim_cr > "$output_path"
  require_nonempty_file "$output_path" "verbose Android-user inventory"
}

prism_user_is_managed_profile_of_parent() {
  local users_file="$1"
  local user_id="$2"
  local parent_id="$3"
  awk -v id="$user_id" -v parent="$parent_id" '
    $0 ~ "^[0-9]+: id=" id "," &&
    $0 ~ "type=profile[.]MANAGED" &&
    $0 ~ "[(]parentId=" parent "[)]" { found = 1 }
    END { exit found ? 0 : 1 }
  ' "$users_file"
}

prism_user_state() {
  device_adb shell "am get-started-user-state $1" | prism_trim_cr | tr -d '\n'
}

prism_find_single_apk() {
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

prism_wait_for_user_absent() {
  local user_id="$1"
  local attempts="${2:-120}"
  local attempt
  for ((attempt = 0; attempt < attempts; attempt++)); do
    if ! prism_user_exists "$user_id"; then
      return 0
    fi
    sleep 0.5
  done
  echo "Android user $user_id was not removed within the bounded wait" >&2
  return 1
}

prism_wait_for_healthy_owner() {
  local package_name="$1"
  local owners_path="$2"
  local attempts="${3:-240}"
  local attempt profile_id
  for ((attempt = 0; attempt < attempts; attempt++)); do
    if prism_capture_owners "$owners_path" >/dev/null 2>&1; then
      profile_id="$(prism_require_single_owned_profile "$owners_path" "$package_name" 2>/dev/null || true)"
      if [[ "$profile_id" =~ ^[0-9]+$ ]] &&
         [[ "$(prism_user_state "$profile_id" 2>/dev/null || true)" == "RUNNING_UNLOCKED" ]] &&
         prism_package_present "$profile_id" "$package_name"; then
        printf '%s\n' "$profile_id"
        return 0
      fi
    fi
    sleep 0.5
  done
  echo "No structurally healthy $package_name profile appeared within the bounded wait" >&2
  return 1
}
