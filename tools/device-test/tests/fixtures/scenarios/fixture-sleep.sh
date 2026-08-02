#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  precondition) : ;;
  execute)
    sleep "${FIXTURE_SLEEP_SECONDS:-30}" &
    child_pid=$!
    if [[ -n "${FIXTURE_ACTIVE_MARKER:-}" ]]; then
      printf '%s\n' "$child_pid" > "$FIXTURE_ACTIVE_MARKER"
    fi
    wait "$child_pid"
    ;;
  cleanup)
    if [[ -n "${FIXTURE_CLEANUP_MARKER:-}" ]]; then
      printf 'cleanup\n' >> "$FIXTURE_CLEANUP_MARKER"
    fi
    ;;
  *) exit 2 ;;
esac
