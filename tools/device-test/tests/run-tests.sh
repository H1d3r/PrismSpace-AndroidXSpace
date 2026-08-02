#!/usr/bin/env bash
set -uo pipefail

readonly TESTS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SOURCE_HARNESS_ROOT="$(cd "$TESTS_ROOT/.." && pwd)"
readonly TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/prism-device-test-contract-XXXXXX")"

PASSED=0
FAILED=0

cleanup_temp_root() {
  if [[ -n "$TEMP_ROOT" && "$TEMP_ROOT" == "${TMPDIR:-/tmp}/prism-device-test-contract-"* ]]; then
    rm -rf -- "$TEMP_ROOT"
  fi
}
trap cleanup_temp_root EXIT

fail() {
  echo "  assertion failed: $*" >&2
  return 1
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local description="$3"
  [[ "$actual" == "$expected" ]] || fail "$description (expected '$expected', got '$actual')"
}

assert_contains() {
  local path="$1"
  local expected="$2"
  grep -F -- "$expected" "$path" >/dev/null 2>&1 || fail "$path does not contain '$expected'"
}

assert_not_contains() {
  local path="$1"
  local unexpected="$2"
  if [[ -f "$path" ]] && grep -F -- "$unexpected" "$path" >/dev/null 2>&1; then
    fail "$path unexpectedly contains '$unexpected'"
  fi
}

assert_file_exists() {
  [[ -f "$1" ]] || fail "missing file: $1"
}

assert_file_absent() {
  [[ ! -e "$1" ]] || fail "unexpected path: $1"
}

assert_recorded_process_stopped() {
  local marker_path="$1"
  local child_pid
  child_pid="$(<"$marker_path")"
  if [[ ! "$child_pid" =~ ^[1-9][0-9]*$ ]]; then
    fail "invalid recorded child pid: $child_pid"
    return
  fi
  local attempt
  for ((attempt = 0; attempt < 20; attempt++)); do
    if ! kill -0 "$child_pid" 2>/dev/null; then
      return 0
    fi
    sleep 0.05
  done
  fail "timed-out child process is still alive: $child_pid"
}

new_case() {
  local case_name="$1"
  local case_root="$TEMP_ROOT/$case_name"
  local harness_root="$case_root/tools/device-test"
  mkdir -p "$case_root/tools"
  cp -R "$SOURCE_HARNESS_ROOT" "$harness_root"
  cp "$TESTS_ROOT/fixtures/scenarios/"*.sh "$harness_root/scenarios/"
  cp "$TESTS_ROOT/fixtures/suites/"*.list "$harness_root/suites/"
  chmod +x "$harness_root/run.sh" "$harness_root/scenarios/"*.sh "$harness_root/tests/fake-adb.sh"
  printf '%s' "$case_root"
}

run_case_harness() {
  local case_root="$1"
  local suite="$2"
  local evidence="$3"
  shift 3
  local log="$case_root/fake-adb.log"
  : > "$log"
  FAKE_ADB_LOG="$log" \
  FAKE_ADB_SERIAL="fixture-device" \
  FAKE_ADB_EXPECTED_PORT="5038" \
  PRISM_DEVICE_TEST_LOCK_ROOT="$case_root/locks" \
    "$case_root/tools/device-test/run.sh" \
      --serial fixture-device \
      --adb "$case_root/tools/device-test/tests/fake-adb.sh" \
      --adb-server-port 5038 \
      --suite "$suite" \
      --artifacts "$evidence" \
      "$@"
}

test_success_and_configured_adb() {
  local case_root
  case_root="$(new_case success)"
  local evidence="$case_root/evidence"
  run_case_harness "$case_root" fixture-device-adb "$evidence" >/dev/null || return

  assert_contains "$evidence/summary.properties" "status=PASS" || return
  assert_contains "$evidence/summary.properties" "device.serial=fixture-device" || return
  assert_contains "$evidence/summary.properties" "adb.executable=$case_root/tools/device-test/tests/fake-adb.sh" || return
  assert_contains "$evidence/summary.properties" "adb.server_port=5038" || return
  assert_contains "$evidence/summary.properties" "device.sdk=35" || return
  assert_contains "$evidence/junit.xml" "tests=\"2\"" || return
  assert_contains "$evidence/junit.xml" "failures=\"0\"" || return
  assert_file_exists "$evidence/preflight/users.stdout" || return
  assert_contains "$case_root/fake-adb.log" "-P 5038 devices -l" || return
  assert_contains "$case_root/fake-adb.log" "-P 5038 -s fixture-device shell getprop" || return
  assert_contains "$evidence/scenarios/fixture-device-adb/execute.stdout" "repository_root=$case_root" || return
}

test_missing_and_malformed_serial_do_not_contact_adb() {
  local case_root
  case_root="$(new_case invalid-serial)"
  local fake_adb="$case_root/tools/device-test/tests/fake-adb.sh"
  local log="$case_root/fake-adb.log"
  : > "$log"

  FAKE_ADB_LOG="$log" "$case_root/tools/device-test/run.sh" --adb "$fake_adb" >/dev/null 2>&1
  assert_eq "2" "$?" "missing serial exit" || return
  assert_eq "" "$(<"$log")" "missing serial ADB log" || return

  FAKE_ADB_LOG="$log" "$case_root/tools/device-test/run.sh" --serial '../unsafe' --adb "$fake_adb" >/dev/null 2>&1
  assert_eq "2" "$?" "malformed serial exit" || return
  assert_eq "" "$(<"$log")" "malformed serial ADB log" || return

  local invalid_port
  for invalid_port in 0 65536 not-a-port 05038; do
    FAKE_ADB_LOG="$log" "$case_root/tools/device-test/run.sh" \
      --serial fixture-device --adb "$fake_adb" --adb-server-port "$invalid_port" >/dev/null 2>&1
    assert_eq "2" "$?" "invalid ADB port '$invalid_port' exit" || return
  done
  assert_eq "" "$(<"$log")" "invalid ADB port log" || return
}

test_default_adb_port_is_omitted() {
  local case_root
  case_root="$(new_case default-port)"
  local evidence="$case_root/evidence"
  local log="$case_root/fake-adb.log"
  : > "$log"

  FAKE_ADB_LOG="$log" FAKE_ADB_SERIAL="fixture-device" \
  PRISM_DEVICE_TEST_LOCK_ROOT="$case_root/locks" \
    "$case_root/tools/device-test/run.sh" \
      --serial fixture-device \
      --adb "$case_root/tools/device-test/tests/fake-adb.sh" \
      --suite fixture-device-adb \
      --artifacts "$evidence" >/dev/null 2>&1
  assert_eq "0" "$?" "default ADB port exit" || return
  assert_contains "$evidence/summary.properties" "adb.server_port=" || return
  assert_not_contains "$log" "-P " || return
  assert_contains "$log" "-s fixture-device shell getprop" || return
}

test_unavailable_device_fails_preflight() {
  local case_root
  case_root="$(new_case unavailable)"
  local evidence="$case_root/evidence"
  local log="$case_root/fake-adb.log"
  : > "$log"

  FAKE_ADB_LOG="$log" FAKE_ADB_SERIAL="fixture-device" FAKE_ADB_STATE="offline" \
  PRISM_DEVICE_TEST_LOCK_ROOT="$case_root/locks" \
    "$case_root/tools/device-test/run.sh" \
      --serial fixture-device \
      --adb "$case_root/tools/device-test/tests/fake-adb.sh" \
      --suite fixture-pass \
      --artifacts "$evidence" >/dev/null 2>&1
  assert_eq "1" "$?" "offline device exit" || return
  assert_contains "$evidence/summary.properties" "status=FAIL" || return
  assert_contains "$evidence/summary.properties" "reason=preflight_failed" || return
  assert_not_contains "$log" "shell getprop" || return
  assert_file_absent "$evidence/scenarios/fixture-pass/execute.stdout" || return
}

test_unavailable_adb_leaves_failure_evidence() {
  local case_root
  case_root="$(new_case unavailable-adb)"
  local evidence="$case_root/evidence"

  PRISM_DEVICE_TEST_LOCK_ROOT="$case_root/locks" \
    "$case_root/tools/device-test/run.sh" \
      --serial fixture-device \
      --adb "$case_root/does-not-exist/adb" \
      --suite fixture-pass \
      --artifacts "$evidence" >/dev/null 2>&1
  assert_eq "1" "$?" "unavailable ADB exit" || return
  assert_contains "$evidence/summary.properties" "status=FAIL" || return
  assert_contains "$evidence/summary.properties" "reason=host_preflight_failed" || return
  assert_contains "$evidence/junit.xml" "name=\"host-preflight\"" || return
  assert_file_absent "$evidence/preflight/devices.stdout" || return
}

test_evidence_is_not_overwritten() {
  local case_root
  case_root="$(new_case no-overwrite)"
  local evidence="$case_root/evidence"
  mkdir "$evidence"
  printf 'sentinel\n' > "$evidence/sentinel"

  run_case_harness "$case_root" fixture-pass "$evidence" >/dev/null 2>&1
  assert_eq "1" "$?" "existing evidence exit" || return
  assert_contains "$evidence/sentinel" "sentinel" || return
  assert_file_absent "$evidence/summary.properties" || return
}

test_same_device_lock_blocks_before_adb() {
  local case_root
  case_root="$(new_case lock)"
  local evidence="$case_root/evidence"
  local lock_root="$case_root/locks"
  local log="$case_root/fake-adb.log"
  mkdir -p "$lock_root"
  : > "$log"
  exec {held_lock_fd}>"$lock_root/fixture-device.lock"
  flock -n "$held_lock_fd" || return

  FAKE_ADB_LOG="$log" FAKE_ADB_SERIAL="fixture-device" \
  PRISM_DEVICE_TEST_LOCK_ROOT="$lock_root" \
    "$case_root/tools/device-test/run.sh" \
      --serial fixture-device \
      --adb "$case_root/tools/device-test/tests/fake-adb.sh" \
      --suite fixture-pass \
      --artifacts "$evidence" >/dev/null 2>&1
  local code=$?
  flock -u "$held_lock_fd"
  exec {held_lock_fd}>&-

  assert_eq "1" "$code" "same-device lock exit" || return
  assert_eq "" "$(<"$log")" "locked run ADB log" || return
  assert_contains "$evidence/summary.properties" "reason=device_locked" || return
}

test_different_device_lock_does_not_block() {
  local case_root
  case_root="$(new_case different-lock)"
  local evidence="$case_root/evidence"
  local lock_root="$case_root/locks"
  local log="$case_root/fake-adb.log"
  mkdir -p "$lock_root"
  : > "$log"
  exec {held_lock_fd}>"$lock_root/fixture-device.lock"
  flock -n "$held_lock_fd" || return

  FAKE_ADB_LOG="$log" FAKE_ADB_SERIAL="other-device" \
  PRISM_DEVICE_TEST_LOCK_ROOT="$lock_root" \
    "$case_root/tools/device-test/run.sh" \
      --serial other-device \
      --adb "$case_root/tools/device-test/tests/fake-adb.sh" \
      --suite fixture-pass \
      --artifacts "$evidence" >/dev/null 2>&1
  local code=$?
  flock -u "$held_lock_fd"
  exec {held_lock_fd}>&-

  assert_eq "0" "$code" "different-device lock exit" || return
  assert_contains "$evidence/summary.properties" "status=PASS" || return
  assert_contains "$log" "-s other-device shell getprop" || return
}

test_adb_timeout_is_distinct_and_bounded() {
  local case_root
  case_root="$(new_case adb-timeout)"
  local evidence="$case_root/evidence"
  local log="$case_root/fake-adb.log"
  : > "$log"

  FAKE_ADB_LOG="$log" FAKE_ADB_SERIAL="fixture-device" \
  FAKE_ADB_SLEEP_MATCH=" devices -l " FAKE_ADB_SLEEP_SECONDS=5 \
  PRISM_DEVICE_TEST_LOCK_ROOT="$case_root/locks" \
    "$case_root/tools/device-test/run.sh" \
      --serial fixture-device \
      --adb "$case_root/tools/device-test/tests/fake-adb.sh" \
      --suite fixture-pass \
      --artifacts "$evidence" \
      --adb-timeout-seconds 1 >/dev/null 2>&1
  assert_eq "1" "$?" "ADB timeout exit" || return
  assert_contains "$evidence/events.tsv" $'preflight\tdevices\tTIMEOUT' || return
  assert_contains "$evidence/summary.properties" "reason=preflight_failed" || return
}

test_scenario_failure_runs_cleanup() {
  local case_root
  case_root="$(new_case scenario-failure)"
  local evidence="$case_root/evidence"
  local cleanup_marker="$case_root/cleanup.marker"

  FIXTURE_CLEANUP_MARKER="$cleanup_marker" \
    run_case_harness "$case_root" fixture-execute-fail "$evidence" >/dev/null 2>&1
  assert_eq "1" "$?" "scenario failure exit" || return
  assert_contains "$cleanup_marker" "cleanup" || return
  assert_contains "$evidence/events.tsv" $'scenario:fixture-execute-fail\texecute\tFAIL' || return
  assert_contains "$evidence/events.tsv" $'scenario:fixture-execute-fail\tcleanup\tPASS' || return
  assert_contains "$evidence/summary.properties" "status=FAIL" || return
}

test_action_timeout_runs_cleanup() {
  local case_root
  case_root="$(new_case action-timeout)"
  local evidence="$case_root/evidence"
  local cleanup_marker="$case_root/cleanup.marker"
  local active_marker="$case_root/active.marker"

  FIXTURE_SLEEP_SECONDS=5 FIXTURE_CLEANUP_MARKER="$cleanup_marker" FIXTURE_ACTIVE_MARKER="$active_marker" \
    run_case_harness "$case_root" fixture-sleep "$evidence" --action-timeout-seconds 1 >/dev/null 2>&1
  assert_eq "1" "$?" "scenario action timeout exit" || return
  assert_contains "$cleanup_marker" "cleanup" || return
  assert_recorded_process_stopped "$active_marker" || return
  assert_contains "$evidence/events.tsv" $'scenario:fixture-sleep\texecute\tTIMEOUT' || return
  assert_contains "$evidence/junit.xml" "execute TIMEOUT" || return
}

test_cleanup_failure_has_failure_precedence() {
  local case_root
  case_root="$(new_case cleanup-failure)"
  local evidence="$case_root/evidence"

  run_case_harness "$case_root" fixture-cleanup-fail "$evidence" >/dev/null 2>&1
  assert_eq "1" "$?" "cleanup failure exit" || return
  assert_contains "$evidence/events.tsv" $'scenario:fixture-cleanup-fail\tcleanup\tFAIL' || return
  assert_contains "$evidence/junit.xml" "cleanup FAIL" || return
  assert_contains "$evidence/summary.properties" "status=FAIL" || return
}

test_interruption_runs_cleanup() {
  local case_root
  case_root="$(new_case interruption)"
  local evidence="$case_root/evidence"
  local cleanup_marker="$case_root/cleanup.marker"
  local active_marker="$case_root/active.marker"
  local log="$case_root/fake-adb.log"
  : > "$log"

  FAKE_ADB_LOG="$log" FAKE_ADB_SERIAL="fixture-device" \
  FIXTURE_SLEEP_SECONDS=30 FIXTURE_CLEANUP_MARKER="$cleanup_marker" FIXTURE_ACTIVE_MARKER="$active_marker" \
  PRISM_DEVICE_TEST_LOCK_ROOT="$case_root/locks" \
    "$case_root/tools/device-test/run.sh" \
      --serial fixture-device \
      --adb "$case_root/tools/device-test/tests/fake-adb.sh" \
      --suite fixture-sleep \
      --artifacts "$evidence" \
      --action-timeout-seconds 60 >/dev/null 2>&1 &
  local runner_pid=$!

  local attempt
  for ((attempt = 0; attempt < 100; attempt++)); do
    [[ -f "$active_marker" ]] && break
    sleep 0.05
  done
  if [[ ! -f "$active_marker" ]]; then
    kill -TERM "$runner_pid" 2>/dev/null || true
    wait "$runner_pid" 2>/dev/null || true
    fail "interruption fixture never entered execute"
    return
  fi

  kill -TERM "$runner_pid"
  wait "$runner_pid"
  local code=$?
  assert_eq "130" "$code" "interrupted run exit" || return
  assert_contains "$cleanup_marker" "cleanup" || return
  assert_recorded_process_stopped "$active_marker" || return
  assert_contains "$evidence/summary.properties" "reason=interrupted_TERM" || return
  assert_contains "$evidence/summary.properties" "status=FAIL" || return
}

test_help_and_shell_syntax() {
  bash -n \
    "$SOURCE_HARNESS_ROOT/run.sh" \
    "$SOURCE_HARNESS_ROOT/lib/common.sh" \
    "$SOURCE_HARNESS_ROOT/scenarios/foundation-device-facts.sh" \
    "$SOURCE_HARNESS_ROOT/scenarios/healthy-debug-core.sh" \
    "$TESTS_ROOT/fake-adb.sh" \
    "$TESTS_ROOT/fixtures/scenarios/"*.sh || return
  local output
  output="$($SOURCE_HARNESS_ROOT/run.sh --help)" || return
  [[ "$output" == *"--serial SERIAL"* ]] || fail "help omits required serial"
  [[ "$output" == *"never inferred"* ]] || fail "help omits explicit selection guarantee"
}

run_test() {
  local name="$1"
  local function_name="$2"
  printf 'TEST %s ... ' "$name"
  if "$function_name"; then
    PASSED=$((PASSED + 1))
    echo "PASS"
  else
    FAILED=$((FAILED + 1))
    echo "FAIL"
  fi
}

run_test "success and configured ADB" test_success_and_configured_adb
run_test "serial validation" test_missing_and_malformed_serial_do_not_contact_adb
run_test "default ADB port" test_default_adb_port_is_omitted
run_test "unavailable device" test_unavailable_device_fails_preflight
run_test "unavailable ADB evidence" test_unavailable_adb_leaves_failure_evidence
run_test "evidence non-overwrite" test_evidence_is_not_overwritten
run_test "same-device lock" test_same_device_lock_blocks_before_adb
run_test "different-device lock" test_different_device_lock_does_not_block
run_test "ADB timeout" test_adb_timeout_is_distinct_and_bounded
run_test "scenario failure cleanup" test_scenario_failure_runs_cleanup
run_test "scenario timeout cleanup" test_action_timeout_runs_cleanup
run_test "cleanup failure precedence" test_cleanup_failure_has_failure_precedence
run_test "interruption cleanup" test_interruption_runs_cleanup
run_test "help and shell syntax" test_help_and_shell_syntax

printf 'RESULT passed=%s failed=%s\n' "$PASSED" "$FAILED"
((FAILED == 0))
