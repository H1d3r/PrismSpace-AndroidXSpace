#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  precondition) printf 'precondition ok\n' ;;
  execute) printf 'execute ok\n' ;;
  cleanup)
    if [[ -n "${FIXTURE_CLEANUP_MARKER:-}" ]]; then
      printf 'cleanup\n' >> "$FIXTURE_CLEANUP_MARKER"
    fi
    ;;
  *) exit 2 ;;
esac
