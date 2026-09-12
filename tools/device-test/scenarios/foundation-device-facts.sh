#!/usr/bin/env bash
set -euo pipefail

source "${PRISM_DEVICE_TEST_COMMON:?}"
require_device_test_environment

action="${1:-}"
case "$action" in
  precondition)
    require_nonempty_file "$PRISM_DEVICE_RUN_DIR/preflight/manufacturer.stdout" "manufacturer fact"
    require_nonempty_file "$PRISM_DEVICE_RUN_DIR/preflight/model.stdout" "model fact"
    require_nonempty_file "$PRISM_DEVICE_RUN_DIR/preflight/device.stdout" "device-name fact"
    require_nonempty_file "$PRISM_DEVICE_RUN_DIR/preflight/sdk.stdout" "SDK fact"
    require_nonempty_file "$PRISM_DEVICE_RUN_DIR/preflight/fingerprint.stdout" "fingerprint fact"
    require_nonempty_file "$PRISM_DEVICE_RUN_DIR/preflight/current-user.stdout" "current-user fact"
    require_nonempty_file "$PRISM_DEVICE_RUN_DIR/preflight/users.stdout" "Android-user inventory"
    require_nonempty_file "$PRISM_DEVICE_RUN_DIR/preflight/device-policy-owners.stdout" "device-policy-owner facts"
    ;;
  execute)
    sdk="$(tr -d '\r\n' < "$PRISM_DEVICE_RUN_DIR/preflight/sdk.stdout")"
    current_user="$(tr -d '\r\n' < "$PRISM_DEVICE_RUN_DIR/preflight/current-user.stdout")"
    if [[ ! "$sdk" =~ ^[0-9]+$ ]] || ((sdk < 24)); then
      echo "Unsupported or invalid Android API level: $sdk" >&2
      exit 1
    fi
    if [[ ! "$current_user" =~ ^[0-9]+$ ]]; then
      echo "Invalid current Android user: $current_user" >&2
      exit 1
    fi
    printf 'device=%s model=%s sdk=%s current_user=%s\n' \
      "$(tr -d '\r\n' < "$PRISM_DEVICE_RUN_DIR/preflight/device.stdout")" \
      "$(tr -d '\r\n' < "$PRISM_DEVICE_RUN_DIR/preflight/model.stdout")" \
      "$sdk" \
      "$current_user"
    ;;
  cleanup)
    # This scenario is read-only; cleanup remains explicit and idempotent.
    :
    ;;
  *)
    echo "Usage: $0 precondition|execute|cleanup" >&2
    exit 2
    ;;
esac
