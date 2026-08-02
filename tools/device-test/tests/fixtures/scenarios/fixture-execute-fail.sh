#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  precondition) : ;;
  execute)
    echo "intentional execute failure" >&2
    exit 7
    ;;
  cleanup)
    if [[ -n "${FIXTURE_CLEANUP_MARKER:-}" ]]; then
      printf 'cleanup\n' >> "$FIXTURE_CLEANUP_MARKER"
    fi
    ;;
  *) exit 2 ;;
esac
