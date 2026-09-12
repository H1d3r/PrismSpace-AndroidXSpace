#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  precondition|execute) : ;;
  cleanup)
    echo "intentional cleanup failure" >&2
    exit 9
    ;;
  *) exit 2 ;;
esac
