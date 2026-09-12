#!/usr/bin/env bash
set -euo pipefail

source "${PRISM_DEVICE_TEST_COMMON:?}"
require_device_test_environment

case "${1:-}" in
  precondition|cleanup) : ;;
  execute)
    device_adb shell getprop ro.product.model
    printf 'repository_root=%s\n' "$PRISM_DEVICE_REPOSITORY_ROOT"
    ;;
  *) exit 2 ;;
esac
