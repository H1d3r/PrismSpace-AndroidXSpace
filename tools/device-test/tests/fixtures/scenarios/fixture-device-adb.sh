#!/usr/bin/env bash
set -euo pipefail

source "${PRISM_DEVICE_TEST_COMMON:?}"
require_device_test_environment

case "${1:-}" in
  precondition|cleanup) : ;;
  execute) device_adb shell getprop ro.product.model ;;
  *) exit 2 ;;
esac
