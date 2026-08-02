#!/usr/bin/env bash
set -uo pipefail

readonly DEVICE_TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$DEVICE_TEST_ROOT/../.." && pwd)"

SERIAL=""
ADB_EXECUTABLE="adb"
ADB_SERVER_PORT=""
SUITE="foundation"
ARTIFACTS_PATH=""
ADB_TIMEOUT_SECONDS=30
ACTION_TIMEOUT_SECONDS=120
LOCK_ROOT="${PRISM_DEVICE_TEST_LOCK_ROOT:-${TMPDIR:-/tmp}/prismspace-device-test-locks}"

RUN_DIR=""
RUN_ID=""
STARTED_AT_UTC=""
STARTED_NS=0
LOCK_FD=""
CURRENT_SCENARIO=""
CURRENT_SCENARIO_SCRIPT=""
CURRENT_CLEANUP_ATTEMPTED=0
ACTIVE_ACTION_PID=""
FINALIZED=0
EVIDENCE_ERROR=0

DEVICE_MANUFACTURER=""
DEVICE_MODEL=""
DEVICE_NAME=""
DEVICE_SDK=""
DEVICE_FINGERPRINT=""
DEVICE_CURRENT_USER=""

declare -a RESULT_IDS=()
declare -a RESULT_STATUSES=()
declare -a RESULT_DURATIONS_MS=()
declare -a RESULT_MESSAGES=()
declare -A SEEN_SCENARIOS=()

usage() {
  cat <<'USAGE'
Usage: tools/device-test/run.sh --serial SERIAL [options]

Runs a repository-owned Android device-test suite against one explicit device.

Required:
  --serial SERIAL              Exact ADB device serial; never inferred

Options:
  --adb PATH                   ADB executable (default: adb from PATH)
  --adb-server-port N          ADB server port, 1-65535 (default: ADB default)
  --suite NAME                 Suite manifest name (default: foundation)
  --artifacts PATH             New evidence directory; must not already exist
  --adb-timeout-seconds N      Per-ADB-command timeout (default: 30)
  --action-timeout-seconds N   Per-scenario-action timeout (default: 120)
  -h, --help                   Show this help

Initial host support: Bash 4+ on Linux/WSL with GNU flock and timeout.
USAGE
}

error() {
  echo "device-test: $*" >&2
}

require_option_value() {
  local option="$1"
  local value="${2:-}"
  if [[ -z "$value" || "$value" == --* ]]; then
    error "$option requires a value"
    usage >&2
    exit 2
  fi
}

parse_arguments() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --serial)
        require_option_value "$1" "${2:-}"
        SERIAL="$2"
        shift 2
        ;;
      --adb)
        require_option_value "$1" "${2:-}"
        ADB_EXECUTABLE="$2"
        shift 2
        ;;
      --adb-server-port)
        require_option_value "$1" "${2:-}"
        ADB_SERVER_PORT="$2"
        shift 2
        ;;
      --suite)
        require_option_value "$1" "${2:-}"
        SUITE="$2"
        shift 2
        ;;
      --artifacts)
        require_option_value "$1" "${2:-}"
        ARTIFACTS_PATH="$2"
        shift 2
        ;;
      --adb-timeout-seconds)
        require_option_value "$1" "${2:-}"
        ADB_TIMEOUT_SECONDS="$2"
        shift 2
        ;;
      --action-timeout-seconds)
        require_option_value "$1" "${2:-}"
        ACTION_TIMEOUT_SECONDS="$2"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        error "unknown argument: $1"
        usage >&2
        exit 2
        ;;
    esac
  done

  if [[ -z "$SERIAL" ]]; then
    error "--serial is required"
    usage >&2
    exit 2
  fi
  if [[ ! "$SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]]; then
    error "invalid serial: use only letters, digits, '.', '_', ':', or '-'"
    exit 2
  fi
  if [[ ! "$SUITE" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
    error "invalid suite name: $SUITE"
    exit 2
  fi
  if [[ -n "$ADB_SERVER_PORT" ]] &&
     { [[ ! "$ADB_SERVER_PORT" =~ ^[1-9][0-9]{0,4}$ ]] ||
       ((10#$ADB_SERVER_PORT > 65535)); }; then
    error "--adb-server-port must be an integer from 1 through 65535"
    exit 2
  fi
  if [[ ! "$ADB_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
    error "--adb-timeout-seconds must be a positive integer"
    exit 2
  fi
  if [[ ! "$ACTION_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
    error "--action-timeout-seconds must be a positive integer"
    exit 2
  fi
}

require_host_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    error "required host command is unavailable: $command_name"
    return 1
  fi
}

validate_host() {
  local command_name
  for command_name in flock timeout date mktemp sed awk git mkdir mv tr dirname basename; do
    require_host_command "$command_name" || return
  done

  if [[ "$ADB_EXECUTABLE" == */* ]]; then
    if [[ ! -x "$ADB_EXECUTABLE" ]]; then
      error "ADB executable is not executable: $ADB_EXECUTABLE"
      return 1
    fi
  elif ! command -v "$ADB_EXECUTABLE" >/dev/null 2>&1; then
    error "ADB executable is unavailable: $ADB_EXECUTABLE"
    return 1
  fi
}

validate_evidence_host() {
  if ((BASH_VERSINFO[0] < 4)); then
    error "Bash 4 or newer is required"
    return 1
  fi

  local command_name
  for command_name in date mktemp mkdir mv dirname basename awk; do
    require_host_command "$command_name" || return
  done
}

sanitize_tsv() {
  local value="$1"
  value="${value//$'\t'/ }"
  value="${value//$'\r'/ }"
  value="${value//$'\n'/ }"
  printf '%s' "$value"
}

escape_property() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

escape_xml() {
  local value="$1"
  value="${value//&/\&amp;}"
  value="${value//</\&lt;}"
  value="${value//>/\&gt;}"
  value="${value//\"/\&quot;}"
  value="${value//\'/\&apos;}"
  printf '%s' "$value"
}

now_ns() {
  date +%s%N
}

duration_ms_since() {
  local started_ns="$1"
  local ended_ns
  ended_ns="$(now_ns)"
  echo $(((ended_ns - started_ns) / 1000000))
}

create_evidence_directory() {
  local safe_serial="${SERIAL//:/_}"
  local evidence_root
  if [[ -n "$ARTIFACTS_PATH" ]]; then
    if [[ -e "$ARTIFACTS_PATH" ]]; then
      error "evidence destination already exists: $ARTIFACTS_PATH"
      return 1
    fi
    if ! mkdir -p "$(dirname "$ARTIFACTS_PATH")" || ! mkdir "$ARTIFACTS_PATH"; then
      error "cannot create evidence destination: $ARTIFACTS_PATH"
      return 1
    fi
    RUN_DIR="$(cd "$ARTIFACTS_PATH" && pwd)"
  else
    evidence_root="$REPOSITORY_ROOT/build/device-test"
    mkdir -p "$evidence_root" || return
    RUN_DIR="$(mktemp -d "$evidence_root/$(date -u +%Y%m%d-%H%M%S)-${safe_serial}-XXXXXX")" || return
  fi

  RUN_ID="$(basename "$RUN_DIR")"
  STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  STARTED_NS="$(now_ns)"
  mkdir -p "$RUN_DIR/preflight" "$RUN_DIR/scenarios" || return
  printf 'timestamp_utc\tphase\tid\tstatus\tduration_ms\tdetail\n' > "$RUN_DIR/events.tsv" || return
  write_summary "RUNNING" "run_in_progress" 0 0 || return
  echo "Evidence: $RUN_DIR"
}

repository_revision() {
  git -C "$REPOSITORY_ROOT" rev-parse HEAD 2>/dev/null || printf 'unknown'
}

write_summary() {
  local status="$1"
  local reason="$2"
  local total="$3"
  local failed="$4"
  local summary_tmp="$RUN_DIR/.summary.properties.tmp"
  local finished_at=""
  local duration_ms=""

  if [[ "$status" != "RUNNING" ]]; then
    finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    duration_ms="$(duration_ms_since "$STARTED_NS")"
  fi

  {
    printf 'format.version=1\n'
    printf 'run.id=%s\n' "$(escape_property "$RUN_ID")"
    printf 'status=%s\n' "$status"
    printf 'reason=%s\n' "$(escape_property "$reason")"
    printf 'repository.revision=%s\n' "$(escape_property "$(repository_revision)")"
    printf 'suite=%s\n' "$(escape_property "$SUITE")"
    printf 'device.serial=%s\n' "$(escape_property "$SERIAL")"
    printf 'adb.executable=%s\n' "$(escape_property "$ADB_EXECUTABLE")"
    printf 'adb.server_port=%s\n' "$(escape_property "$ADB_SERVER_PORT")"
    printf 'started.at.utc=%s\n' "$STARTED_AT_UTC"
    printf 'finished.at.utc=%s\n' "$finished_at"
    printf 'duration.ms=%s\n' "$duration_ms"
    printf 'device.manufacturer=%s\n' "$(escape_property "$DEVICE_MANUFACTURER")"
    printf 'device.model=%s\n' "$(escape_property "$DEVICE_MODEL")"
    printf 'device.name=%s\n' "$(escape_property "$DEVICE_NAME")"
    printf 'device.sdk=%s\n' "$(escape_property "$DEVICE_SDK")"
    printf 'device.fingerprint=%s\n' "$(escape_property "$DEVICE_FINGERPRINT")"
    printf 'device.current_user=%s\n' "$(escape_property "$DEVICE_CURRENT_USER")"
    printf 'results.total=%s\n' "$total"
    printf 'results.failed=%s\n' "$failed"
    printf 'results.passed=%s\n' "$((total - failed))"
  } > "$summary_tmp" || return
  mv "$summary_tmp" "$RUN_DIR/summary.properties"
}

record_event() {
  local phase="$1"
  local id="$2"
  local status="$3"
  local duration_ms="$4"
  local detail="$5"
  if ! printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$(sanitize_tsv "$phase")" \
    "$(sanitize_tsv "$id")" \
    "$(sanitize_tsv "$status")" \
    "$duration_ms" \
    "$(sanitize_tsv "$detail")" >> "$RUN_DIR/events.tsv"; then
    EVIDENCE_ERROR=1
    error "failed to append event evidence"
    return 1
  fi
}

add_result() {
  RESULT_IDS+=("$1")
  RESULT_STATUSES+=("$2")
  RESULT_DURATIONS_MS+=("$3")
  RESULT_MESSAGES+=("$4")
}

write_junit() {
  local total="${#RESULT_IDS[@]}"
  local failed=0
  local total_ms=0
  local index
  for ((index = 0; index < total; index++)); do
    total_ms=$((total_ms + RESULT_DURATIONS_MS[index]))
    if [[ "${RESULT_STATUSES[index]}" != "PASS" ]]; then
      failed=$((failed + 1))
    fi
  done

  local junit_tmp="$RUN_DIR/.junit.xml.tmp"
  {
    printf '<?xml version="1.0" encoding="UTF-8"?>\n'
    printf '<testsuite name="%s" tests="%s" failures="%s" errors="0" time="%s">\n' \
      "$(escape_xml "device-test:$SUITE")" "$total" "$failed" "$(awk -v ms="$total_ms" 'BEGIN { printf "%.3f", ms / 1000 }')"
    for ((index = 0; index < total; index++)); do
      printf '  <testcase classname="%s" name="%s" time="%s">' \
        "$(escape_xml "device-test.$SUITE")" \
        "$(escape_xml "${RESULT_IDS[index]}")" \
        "$(awk -v ms="${RESULT_DURATIONS_MS[index]}" 'BEGIN { printf "%.3f", ms / 1000 }')"
      if [[ "${RESULT_STATUSES[index]}" != "PASS" ]]; then
        printf '<failure message="%s" />' "$(escape_xml "${RESULT_MESSAGES[index]}")"
      fi
      printf '</testcase>\n'
    done
    printf '</testsuite>\n'
  } > "$junit_tmp" || return
  mv "$junit_tmp" "$RUN_DIR/junit.xml"
}

finalize_run() {
  local requested_status="$1"
  local reason="$2"
  local final_status="$requested_status"
  local total="${#RESULT_IDS[@]}"
  local failed=0
  local index

  if ((FINALIZED == 1)); then
    return
  fi

  for ((index = 0; index < total; index++)); do
    if [[ "${RESULT_STATUSES[index]}" != "PASS" ]]; then
      failed=$((failed + 1))
    fi
  done
  if ((failed > 0)); then
    final_status="FAIL"
  fi
  if ((EVIDENCE_ERROR == 1)); then
    final_status="FAIL"
    reason="evidence_recording_failed"
  fi

  if ! write_junit; then
    final_status="FAIL"
    reason="evidence_finalization_failed"
    error "failed to write JUnit evidence"
  fi
  if ! write_summary "$final_status" "$reason" "$total" "$failed"; then
    final_status="FAIL"
    error "failed to write final summary"
  fi

  FINALIZED=1
  echo "Result: $final_status"
  if [[ "$final_status" == "PASS" ]]; then
    return 0
  fi
  return 1
}

acquire_device_lock() {
  local safe_serial="${SERIAL//:/_}"
  mkdir -p "$LOCK_ROOT" || return
  local lock_file="$LOCK_ROOT/$safe_serial.lock"
  exec {LOCK_FD}>"$lock_file" || return
  if ! flock -n "$LOCK_FD"; then
    error "device is already in use by another test run: $SERIAL"
    return 1
  fi
  record_event "harness" "device-lock" "PASS" 0 "$lock_file"
}

run_bounded_capture() {
  local timeout_seconds="$1"
  local stdout_path="$2"
  local stderr_path="$3"
  shift 3
  timeout --kill-after=5s "${timeout_seconds}s" "$@" >"$stdout_path" 2>"$stderr_path"
}

capture_adb() {
  local id="$1"
  local scope="$2"
  shift 2
  local stdout_path="$RUN_DIR/preflight/$id.stdout"
  local stderr_path="$RUN_DIR/preflight/$id.stderr"
  local started_ns
  local duration_ms
  local code
  local status
  local -a adb_command=("$ADB_EXECUTABLE")
  if [[ -n "$ADB_SERVER_PORT" ]]; then
    adb_command+=("-P" "$ADB_SERVER_PORT")
  fi
  started_ns="$(now_ns)"

  if [[ "$scope" == "global" ]]; then
    run_bounded_capture "$ADB_TIMEOUT_SECONDS" "$stdout_path" "$stderr_path" "${adb_command[@]}" "$@"
    code=$?
  else
    run_bounded_capture "$ADB_TIMEOUT_SECONDS" "$stdout_path" "$stderr_path" "${adb_command[@]}" -s "$SERIAL" "$@"
    code=$?
  fi
  duration_ms="$(duration_ms_since "$started_ns")"
  if ((code == 0)); then
    status="PASS"
  elif ((code == 124 || code == 137)); then
    status="TIMEOUT"
  else
    status="FAIL"
  fi
  record_event "preflight" "$id" "$status" "$duration_ms" "exit=$code"
  return "$code"
}

read_single_line_fact() {
  local path="$1"
  tr -d '\r' < "$path" | sed -n '1p'
}

REQUIRED_FACT_FAILURE=""

capture_required_fact() {
  local fact_id="$1"
  shift
  if capture_adb "$fact_id" "device" "$@"; then
    return 0
  fi
  REQUIRED_FACT_FAILURE="$fact_id"
  return 1
}

preflight_device() {
  local started_ns
  local duration_ms
  local device_state
  started_ns="$(now_ns)"

  if ! capture_adb "devices" "global" devices -l; then
    duration_ms="$(duration_ms_since "$started_ns")"
    add_result "preflight" "FAIL" "$duration_ms" "adb device listing failed or timed out"
    return 1
  fi

  device_state="$(awk -v serial="$SERIAL" '$1 == serial { print $2; exit }' "$RUN_DIR/preflight/devices.stdout")"
  if [[ "$device_state" != "device" ]]; then
    duration_ms="$(duration_ms_since "$started_ns")"
    if [[ -z "$device_state" ]]; then
      device_state="absent"
    fi
    record_event "preflight" "device-state" "FAIL" 0 "$device_state"
    add_result "preflight" "FAIL" "$duration_ms" "selected device state is $device_state"
    return 1
  fi
  record_event "preflight" "device-state" "PASS" 0 "$device_state"

  REQUIRED_FACT_FAILURE=""
  if ! capture_required_fact "manufacturer" shell getprop ro.product.manufacturer ||
     ! capture_required_fact "model" shell getprop ro.product.model ||
     ! capture_required_fact "device" shell getprop ro.product.device ||
     ! capture_required_fact "sdk" shell getprop ro.build.version.sdk ||
     ! capture_required_fact "fingerprint" shell getprop ro.build.fingerprint ||
     ! capture_required_fact "current-user" shell am get-current-user ||
     ! capture_required_fact "users" shell pm list users ||
     ! capture_required_fact "device-policy-owners" shell "dumpsys device_policy | sed -n '1,/^  Admin Services:/p'"; then
    duration_ms="$(duration_ms_since "$started_ns")"
    add_result "preflight" "FAIL" "$duration_ms" "required fact failed: $REQUIRED_FACT_FAILURE"
    return 1
  fi

  DEVICE_MANUFACTURER="$(read_single_line_fact "$RUN_DIR/preflight/manufacturer.stdout")"
  DEVICE_MODEL="$(read_single_line_fact "$RUN_DIR/preflight/model.stdout")"
  DEVICE_NAME="$(read_single_line_fact "$RUN_DIR/preflight/device.stdout")"
  DEVICE_SDK="$(read_single_line_fact "$RUN_DIR/preflight/sdk.stdout")"
  DEVICE_FINGERPRINT="$(read_single_line_fact "$RUN_DIR/preflight/fingerprint.stdout")"
  DEVICE_CURRENT_USER="$(read_single_line_fact "$RUN_DIR/preflight/current-user.stdout")"

  if [[ -z "$DEVICE_MANUFACTURER" || -z "$DEVICE_MODEL" || -z "$DEVICE_NAME" ||
        ! "$DEVICE_SDK" =~ ^[0-9]+$ || -z "$DEVICE_FINGERPRINT" ||
        ! "$DEVICE_CURRENT_USER" =~ ^[0-9]+$ ||
        ! -s "$RUN_DIR/preflight/users.stdout" || ! -s "$RUN_DIR/preflight/device-policy-owners.stdout" ]]; then
    duration_ms="$(duration_ms_since "$started_ns")"
    record_event "preflight" "required-facts" "FAIL" "$duration_ms" "missing_or_invalid"
    add_result "preflight" "FAIL" "$duration_ms" "required device fact is missing or invalid"
    return 1
  fi

  duration_ms="$(duration_ms_since "$started_ns")"
  record_event "preflight" "required-facts" "PASS" "$duration_ms" "captured"
  add_result "preflight" "PASS" "$duration_ms" "device facts captured"
}

load_suite() {
  local manifest="$DEVICE_TEST_ROOT/suites/$SUITE.list"
  if [[ ! -f "$manifest" ]]; then
    error "unknown suite: $SUITE"
    return 1
  fi

  SUITE_SCENARIOS=()
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="$(printf '%s' "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -z "$line" ]] && continue
    if [[ ! "$line" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
      error "invalid scenario id in $manifest: $line"
      return 1
    fi
    if [[ -n "${SEEN_SCENARIOS[$line]:-}" ]]; then
      error "duplicate scenario id in $manifest: $line"
      return 1
    fi
    local scenario_script="$DEVICE_TEST_ROOT/scenarios/$line.sh"
    if [[ ! -x "$scenario_script" ]]; then
      error "scenario is missing or not executable: $scenario_script"
      return 1
    fi
    SEEN_SCENARIOS["$line"]=1
    SUITE_SCENARIOS+=("$line")
  done < "$manifest"

  if ((${#SUITE_SCENARIOS[@]} == 0)); then
    error "suite has no scenarios: $SUITE"
    return 1
  fi
}

export_scenario_environment() {
  local scenario_dir="$1"
  export PRISM_DEVICE_ADB="$ADB_EXECUTABLE"
  export PRISM_DEVICE_ADB_SERVER_PORT="$ADB_SERVER_PORT"
  export PRISM_DEVICE_SERIAL="$SERIAL"
  export PRISM_DEVICE_ADB_TIMEOUT_SECONDS="$ADB_TIMEOUT_SECONDS"
  export PRISM_DEVICE_RUN_DIR="$RUN_DIR"
  export PRISM_DEVICE_SCENARIO_DIR="$scenario_dir"
  export PRISM_DEVICE_TEST_COMMON="$DEVICE_TEST_ROOT/lib/common.sh"
}

LAST_ACTION_CODE=0
LAST_ACTION_STATUS="PASS"
LAST_ACTION_DURATION_MS=0

run_scenario_action() {
  local scenario="$1"
  local script="$2"
  local action="$3"
  local scenario_dir="$RUN_DIR/scenarios/$scenario"
  local stdout_path="$scenario_dir/$action.stdout"
  local stderr_path="$scenario_dir/$action.stderr"
  local started_ns
  if ! mkdir -p "$scenario_dir"; then
    LAST_ACTION_CODE=1
    LAST_ACTION_STATUS="FAIL"
    LAST_ACTION_DURATION_MS=0
    EVIDENCE_ERROR=1
    error "cannot create scenario evidence directory: $scenario_dir"
    return 1
  fi
  export_scenario_environment "$scenario_dir"
  started_ns="$(now_ns)"

  timeout --kill-after=5s "${ACTION_TIMEOUT_SECONDS}s" "$script" "$action" >"$stdout_path" 2>"$stderr_path" &
  ACTIVE_ACTION_PID=$!
  wait "$ACTIVE_ACTION_PID"
  LAST_ACTION_CODE=$?
  ACTIVE_ACTION_PID=""
  LAST_ACTION_DURATION_MS="$(duration_ms_since "$started_ns")"

  if ((LAST_ACTION_CODE == 0)); then
    LAST_ACTION_STATUS="PASS"
  elif ((LAST_ACTION_CODE == 124 || LAST_ACTION_CODE == 137)); then
    LAST_ACTION_STATUS="TIMEOUT"
  else
    LAST_ACTION_STATUS="FAIL"
  fi
  record_event "scenario:$scenario" "$action" "$LAST_ACTION_STATUS" "$LAST_ACTION_DURATION_MS" "exit=$LAST_ACTION_CODE"
}

cleanup_current_scenario() {
  if [[ -z "$CURRENT_SCENARIO" || "$CURRENT_CLEANUP_ATTEMPTED" == 1 ]]; then
    return 0
  fi
  CURRENT_CLEANUP_ATTEMPTED=1
  run_scenario_action "$CURRENT_SCENARIO" "$CURRENT_SCENARIO_SCRIPT" "cleanup"
  return "$LAST_ACTION_CODE"
}

run_scenario() {
  local scenario="$1"
  local script="$DEVICE_TEST_ROOT/scenarios/$scenario.sh"
  local scenario_started_ns
  local scenario_duration_ms
  local failure_message=""
  local cleanup_code

  CURRENT_SCENARIO="$scenario"
  CURRENT_SCENARIO_SCRIPT="$script"
  CURRENT_CLEANUP_ATTEMPTED=0
  scenario_started_ns="$(now_ns)"

  run_scenario_action "$scenario" "$script" "precondition"
  if ((LAST_ACTION_CODE != 0)); then
    failure_message="precondition $LAST_ACTION_STATUS"
  else
    run_scenario_action "$scenario" "$script" "execute"
    if ((LAST_ACTION_CODE != 0)); then
      failure_message="execute $LAST_ACTION_STATUS"
    fi
  fi

  cleanup_current_scenario
  cleanup_code=$?
  if ((cleanup_code != 0)); then
    if [[ -n "$failure_message" ]]; then
      failure_message="$failure_message; cleanup $LAST_ACTION_STATUS"
    else
      failure_message="cleanup $LAST_ACTION_STATUS"
    fi
  fi

  scenario_duration_ms="$(duration_ms_since "$scenario_started_ns")"
  if [[ -z "$failure_message" ]]; then
    add_result "$scenario" "PASS" "$scenario_duration_ms" "scenario completed"
  else
    add_result "$scenario" "FAIL" "$scenario_duration_ms" "$failure_message"
  fi

  CURRENT_SCENARIO=""
  CURRENT_SCENARIO_SCRIPT=""
  CURRENT_CLEANUP_ATTEMPTED=0
  [[ -z "$failure_message" ]]
}

handle_signal() {
  local signal_name="$1"
  trap - INT TERM
  error "received $signal_name; stopping active action and attempting cleanup"
  if [[ -n "$ACTIVE_ACTION_PID" ]]; then
    kill -TERM "$ACTIVE_ACTION_PID" 2>/dev/null || true
    wait "$ACTIVE_ACTION_PID" 2>/dev/null || true
    ACTIVE_ACTION_PID=""
  fi
  if [[ -n "$CURRENT_SCENARIO" ]]; then
    cleanup_current_scenario || true
    add_result "$CURRENT_SCENARIO" "FAIL" 0 "host interrupted by $signal_name"
  fi
  finalize_run "FAIL" "interrupted_$signal_name" || true
  exit 130
}

main() {
  parse_arguments "$@"
  validate_evidence_host || exit 1
  create_evidence_directory || exit 1
  trap 'handle_signal INT' INT
  trap 'handle_signal TERM' TERM

  if ! validate_host; then
    record_event "harness" "host-preflight" "FAIL" 0 "required command or ADB unavailable"
    add_result "host-preflight" "FAIL" 0 "required host command or ADB executable is unavailable"
    finalize_run "FAIL" "host_preflight_failed"
    exit 1
  fi
  if ! record_event "harness" "host-preflight" "PASS" 0 "required commands available"; then
    add_result "evidence" "FAIL" 0 "cannot record host preflight"
    finalize_run "FAIL" "evidence_recording_failed"
    exit 1
  fi

  if ! acquire_device_lock; then
    if ((EVIDENCE_ERROR == 1)); then
      add_result "evidence" "FAIL" 0 "cannot record device lock"
      finalize_run "FAIL" "evidence_recording_failed"
    else
      add_result "device-lock" "FAIL" 0 "selected device is locked"
      finalize_run "FAIL" "device_locked"
    fi
    exit 1
  fi

  if ! load_suite; then
    add_result "suite-contract" "FAIL" 0 "suite manifest is invalid"
    finalize_run "FAIL" "invalid_suite"
    exit 1
  fi

  if ! preflight_device; then
    finalize_run "FAIL" "preflight_failed"
    exit 1
  fi
  if ((EVIDENCE_ERROR == 1)); then
    add_result "evidence" "FAIL" 0 "cannot record preflight evidence"
    finalize_run "FAIL" "evidence_recording_failed"
    exit 1
  fi

  local scenario
  for scenario in "${SUITE_SCENARIOS[@]}"; do
    if ! run_scenario "$scenario"; then
      finalize_run "FAIL" "scenario_failed"
      exit 1
    fi
    if ((EVIDENCE_ERROR == 1)); then
      add_result "evidence" "FAIL" 0 "cannot record scenario evidence"
      finalize_run "FAIL" "evidence_recording_failed"
      exit 1
    fi
  done

  finalize_run "PASS" "all_checks_passed"
}

main "$@"
