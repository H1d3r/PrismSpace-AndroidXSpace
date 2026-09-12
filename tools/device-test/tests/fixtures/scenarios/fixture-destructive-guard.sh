#!/usr/bin/env bash
set -euo pipefail

source "${PRISM_DEVICE_TEST_COMMON:?}"
require_device_test_environment

case "${1:-}" in
  precondition) require_destructive_authorization ;;
  execute) printf 'destructive-executed\n' ;;
  cleanup) : ;;
  *) exit 2 ;;
esac
