#!/usr/bin/env bash

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "common.sh must be sourced by a device-test scenario" >&2
  exit 2
fi

require_device_test_environment() {
  local name
  for name in \
    PRISM_DEVICE_ADB \
    PRISM_DEVICE_SERIAL \
    PRISM_DEVICE_ADB_TIMEOUT_SECONDS \
    PRISM_DEVICE_RUN_DIR \
    PRISM_DEVICE_SCENARIO_DIR \
    PRISM_DEVICE_REPOSITORY_ROOT; do
    if [[ -z "${!name:-}" ]]; then
      echo "Missing device-test environment: $name" >&2
      return 2
    fi
  done
}

device_adb() {
  require_device_test_environment || return
  local -a adb_command=("$PRISM_DEVICE_ADB")
  if [[ -n "${PRISM_DEVICE_ADB_SERVER_PORT:-}" ]]; then
    adb_command+=("-P" "$PRISM_DEVICE_ADB_SERVER_PORT")
  fi
  adb_command+=("-s" "$PRISM_DEVICE_SERIAL")
  timeout \
    --kill-after=5s \
    "${PRISM_DEVICE_ADB_TIMEOUT_SECONDS}s" \
    "${adb_command[@]}" "$@"
}

require_nonempty_file() {
  local path="$1"
  local description="$2"
  if [[ ! -s "$path" ]]; then
    echo "Missing or empty $description: $path" >&2
    return 1
  fi
}
