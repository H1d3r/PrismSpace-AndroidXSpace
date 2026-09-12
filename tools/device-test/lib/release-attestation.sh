#!/usr/bin/env bash

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "release-attestation.sh must be sourced" >&2
  exit 2
fi

release_attestation_value() {
  local path="$1"
  local key="$2"
  local -a values=()
  while IFS= read -r value; do values+=("$value"); done < <(
    awk -v property="$key" '
      index($0, property "=") == 1 {
        print substr($0, length(property) + 2)
      }
    ' "$path"
  )
  if ((${#values[@]} != 1)); then
    echo "Attestation requires exactly one '$key' property" >&2
    return 1
  fi
  printf '%s\n' "${values[0]}"
}

validate_release_attestation() {
  local path="$1"
  local expected_revision="$2"
  local expected_serial="$3"
  local expected_fingerprint="$4"
  local expected_candidate_hash="$5"
  [[ -f "$path" ]] || { echo "Attestation file is missing: $path" >&2; return 1; }

  local version revision serial fingerprint candidate operator completed
  version="$(release_attestation_value "$path" format.version)" || return
  revision="$(release_attestation_value "$path" repository.revision)" || return
  serial="$(release_attestation_value "$path" device.serial)" || return
  fingerprint="$(release_attestation_value "$path" device.fingerprint)" || return
  candidate="$(release_attestation_value "$path" candidate.sha256)" || return
  operator="$(release_attestation_value "$path" operator)" || return
  completed="$(release_attestation_value "$path" completed.at.utc)" || return

  [[ "$version" == "1" ]] || { echo "Unsupported attestation format: $version" >&2; return 1; }
  [[ "$revision" == "$expected_revision" ]] || { echo "Attestation revision mismatch" >&2; return 1; }
  [[ "$serial" == "$expected_serial" ]] || { echo "Attestation serial mismatch" >&2; return 1; }
  [[ "$fingerprint" == "$expected_fingerprint" ]] || { echo "Attestation fingerprint mismatch" >&2; return 1; }
  [[ "$candidate" == "$expected_candidate_hash" ]] || { echo "Attestation candidate hash mismatch" >&2; return 1; }
  [[ -n "$operator" ]] || { echo "Attestation operator is empty" >&2; return 1; }
  [[ "$completed" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
    echo "Attestation completion time is not UTC ISO-8601" >&2
    return 1
  }

  local item
  for item in \
    normal.managed_provisioning \
    normal.clone_package_installer \
    normal.storage_access_framework \
    normal.unknown_sources_guidance \
    normal.pause_unlock_recovery; do
    [[ "$(release_attestation_value "$path" "$item")" == "PASS" ]] || {
      echo "Attestation item is not PASS: $item" >&2
      return 1
    }
  done
}
