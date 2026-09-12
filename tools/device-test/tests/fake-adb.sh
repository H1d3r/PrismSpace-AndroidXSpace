#!/usr/bin/env bash
set -uo pipefail

serial="${FAKE_ADB_SERIAL:-fixture-device}"
state="${FAKE_ADB_STATE:-device}"
log_path="${FAKE_ADB_LOG:?FAKE_ADB_LOG is required}"
printf '%q ' "$@" >> "$log_path"
printf '\n' >> "$log_path"

joined=" $* "
if [[ -n "${FAKE_ADB_SLEEP_MATCH:-}" && "$joined" == *"${FAKE_ADB_SLEEP_MATCH}"* ]]; then
  sleep "${FAKE_ADB_SLEEP_SECONDS:-5}"
fi

if [[ "${1:-}" == "-P" ]]; then
  if [[ -z "${2:-}" ]]; then
    echo "fake-adb: -P requires a port" >&2
    exit 90
  fi
  if [[ -n "${FAKE_ADB_EXPECTED_PORT:-}" && "$2" != "$FAKE_ADB_EXPECTED_PORT" ]]; then
    echo "fake-adb: expected port $FAKE_ADB_EXPECTED_PORT, got $2" >&2
    exit 90
  fi
  shift 2
elif [[ -n "${FAKE_ADB_EXPECTED_PORT:-}" ]]; then
  echo "fake-adb: expected -P $FAKE_ADB_EXPECTED_PORT" >&2
  exit 90
fi

if [[ "${1:-}" == "devices" && "${2:-}" == "-l" ]]; then
  printf 'List of devices attached\n'
  if [[ "$state" != "absent" ]]; then
    printf '%s\t%s product:fixture model:Fixture_Model device:fixture transport_id:1\n' "$serial" "$state"
  fi
  exit 0
fi

if [[ "${1:-}" != "-s" || "${2:-}" != "$serial" ]]; then
  echo "fake-adb: expected -s $serial, got: $*" >&2
  exit 91
fi
shift 2

case "$*" in
  "shell getprop ro.product.manufacturer") printf '%s\n' "Fixture" ;;
  "shell getprop ro.product.model") printf '%s\n' "Fixture Model" ;;
  "shell getprop ro.product.device") printf '%s\n' "fixture" ;;
  "shell getprop ro.build.version.sdk") printf '%s\n' "35" ;;
  "shell getprop ro.build.fingerprint") printf '%s\n' "fixture/device/build:15/TEST/1:userdebug/test-keys" ;;
  "shell am get-current-user") printf '%s\n' "0" ;;
  "shell pm list users") printf '%s\n' "Users:" "\tUserInfo{0:Owner:13} running" ;;
  "shell dumpsys device_policy | sed -n '1,/^  Admin Services:/p'")
    printf '%s\n' "Current Device Policy Manager state:" "  Profile Owner (User 10):" "    package=fixture.owner"
    ;;
  *)
    echo "fake-adb: unexpected command: $*" >&2
    exit 92
    ;;
esac
