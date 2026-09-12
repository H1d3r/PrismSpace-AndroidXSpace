#!/usr/bin/env bash
set -uo pipefail

readonly DEVICE_TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$DEVICE_TEST_ROOT/../.." && pwd)"

source "$DEVICE_TEST_ROOT/lib/release-attestation.sh"

SERIAL=""
ADB_EXECUTABLE="adb"
ADB_SERVER_PORT=""
EXPECTED_FINGERPRINT=""
ALLOW_DESTRUCTIVE=0
AUTOMATED_ONLY=0
ATTESTATION=""
ARTIFACTS_PATH=""
ADB_TIMEOUT_SECONDS=180
ACTION_TIMEOUT_SECONDS=900

RUN_DIR=""
REVISION=""
CANDIDATE_HASH=""
FINAL_STATUS="FAIL"
FINAL_REASON="not_started"
LOCAL_STATUS="NOT_RUN"
DEVICE_STATUS="NOT_RUN"
ATTESTATION_STATUS="NOT_RUN"
STARTED_AT=""
STARTED_NS=0
FINALIZED=0
ACTIVE_CHILD_PID=""

usage() {
  cat <<'USAGE'
Usage: tools/device-test/release-gate.sh --serial SERIAL --expected-fingerprint TEXT --allow-destructive [options]

Required:
  --serial SERIAL
  --expected-fingerprint TEXT
  --allow-destructive

Readiness mode (default):
  --attestation PATH           Current human-confirmation attestation

Development mode:
  --automated-only             Allow AUTOMATED_PASS; never reports RELEASE_READY

Other options:
  --adb PATH
  --adb-server-port N
  --artifacts PATH
  --adb-timeout-seconds N      Default: 180
  --action-timeout-seconds N   Default: 900
  -h, --help
USAGE
}

fail_usage() {
  echo "release-gate: $*" >&2
  usage >&2
  exit 2
}

require_value() {
  [[ -n "${2:-}" && "${2:-}" != --* ]] || fail_usage "$1 requires a value"
}

parse_arguments() {
  while (($# > 0)); do
    case "$1" in
      --serial) require_value "$1" "${2:-}"; SERIAL="$2"; shift 2 ;;
      --adb) require_value "$1" "${2:-}"; ADB_EXECUTABLE="$2"; shift 2 ;;
      --adb-server-port) require_value "$1" "${2:-}"; ADB_SERVER_PORT="$2"; shift 2 ;;
      --expected-fingerprint) require_value "$1" "${2:-}"; EXPECTED_FINGERPRINT="$2"; shift 2 ;;
      --allow-destructive) ALLOW_DESTRUCTIVE=1; shift ;;
      --automated-only) AUTOMATED_ONLY=1; shift ;;
      --attestation) require_value "$1" "${2:-}"; ATTESTATION="$2"; shift 2 ;;
      --artifacts) require_value "$1" "${2:-}"; ARTIFACTS_PATH="$2"; shift 2 ;;
      --adb-timeout-seconds) require_value "$1" "${2:-}"; ADB_TIMEOUT_SECONDS="$2"; shift 2 ;;
      --action-timeout-seconds) require_value "$1" "${2:-}"; ACTION_TIMEOUT_SECONDS="$2"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) fail_usage "unknown argument: $1" ;;
    esac
  done

  [[ "$SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] || fail_usage "valid --serial is required"
  [[ "$EXPECTED_FINGERPRINT" =~ ^[A-Za-z0-9._/:+=,@-]+$ ]] || fail_usage "valid --expected-fingerprint is required"
  ((ALLOW_DESTRUCTIVE == 1)) || fail_usage "--allow-destructive is required"
  [[ "$ADB_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail_usage "invalid ADB timeout"
  [[ "$ACTION_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail_usage "invalid action timeout"
  if [[ -n "$ADB_SERVER_PORT" ]] &&
     { [[ ! "$ADB_SERVER_PORT" =~ ^[1-9][0-9]{0,4}$ ]] || ((10#$ADB_SERVER_PORT > 65535)); }; then
    fail_usage "invalid ADB server port"
  fi
  if ((AUTOMATED_ONLY == 1)) && [[ -n "$ATTESTATION" ]]; then
    fail_usage "--automated-only and --attestation are mutually exclusive"
  fi
  if ((AUTOMATED_ONLY == 0)) && [[ -z "$ATTESTATION" ]]; then
    fail_usage "release-ready mode requires --attestation (or use --automated-only)"
  fi
}

now_ns() { date +%s%N; }

escape_xml() {
  local value="$1"
  value="${value//&/\&amp;}"
  value="${value//</\&lt;}"
  value="${value//>/\&gt;}"
  value="${value//\"/\&quot;}"
  printf '%s' "$value"
}

create_run_dir() {
  local root
  if [[ -n "$ARTIFACTS_PATH" ]]; then
    [[ ! -e "$ARTIFACTS_PATH" ]] || { echo "Evidence destination exists: $ARTIFACTS_PATH" >&2; return 1; }
    mkdir -p "$(dirname "$ARTIFACTS_PATH")" && mkdir "$ARTIFACTS_PATH" || return
    RUN_DIR="$(cd "$ARTIFACTS_PATH" && pwd)"
  else
    root="$REPOSITORY_ROOT/build/device-release-gate"
    mkdir -p "$root" || return
    RUN_DIR="$(mktemp -d "$root/$(date -u +%Y%m%d-%H%M%S)-${SERIAL//:/_}-XXXXXX")" || return
  fi
  STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  STARTED_NS="$(now_ns)"
  printf 'Evidence: %s\n' "$RUN_DIR"
}

write_summary() {
  local finished duration ready=false
  finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  duration=$((( $(now_ns) - STARTED_NS ) / 1000000))
  [[ "$FINAL_STATUS" == "RELEASE_READY" ]] && ready=true
  local tmp="$RUN_DIR/.summary.properties.tmp"
  {
    printf 'format.version=1\n'
    printf 'status=%s\n' "$FINAL_STATUS"
    printf 'reason=%s\n' "$FINAL_REASON"
    printf 'release.ready=%s\n' "$ready"
    printf 'repository.revision=%s\n' "$REVISION"
    printf 'candidate.sha256=%s\n' "$CANDIDATE_HASH"
    printf 'device.serial=%s\n' "$SERIAL"
    printf 'device.fingerprint=%s\n' "$EXPECTED_FINGERPRINT"
    printf 'started.at.utc=%s\n' "$STARTED_AT"
    printf 'finished.at.utc=%s\n' "$finished"
    printf 'duration.ms=%s\n' "$duration"
    printf 'local.status=%s\n' "$LOCAL_STATUS"
    printf 'device.status=%s\n' "$DEVICE_STATUS"
    printf 'attestation.status=%s\n' "$ATTESTATION_STATUS"
    printf 'device.evidence=%s\n' "$RUN_DIR/device"
  } > "$tmp" || return
  mv "$tmp" "$RUN_DIR/summary.properties"
}

write_junit() {
  local failures=0
  [[ "$LOCAL_STATUS" == "PASS" ]] || failures=$((failures + 1))
  [[ "$DEVICE_STATUS" == "PASS" ]] || failures=$((failures + 1))
  if ((AUTOMATED_ONLY == 0)); then
    [[ "$ATTESTATION_STATUS" == "PASS" ]] || failures=$((failures + 1))
  fi
  local tests=2
  ((AUTOMATED_ONLY == 0)) && tests=3
  local tmp="$RUN_DIR/.junit.xml.tmp"
  {
    printf '<?xml version="1.0" encoding="UTF-8"?>\n'
    printf '<testsuite name="device-release-gate" tests="%s" failures="%s">\n' "$tests" "$failures"
    printf '  <testcase name="local-gate">'
    [[ "$LOCAL_STATUS" == "PASS" ]] || printf '<failure message="%s" />' "$(escape_xml "$LOCAL_STATUS")"
    printf '</testcase>\n'
    printf '  <testcase name="device-gate">'
    [[ "$DEVICE_STATUS" == "PASS" ]] || printf '<failure message="%s" />' "$(escape_xml "$DEVICE_STATUS")"
    printf '</testcase>\n'
    if ((AUTOMATED_ONLY == 0)); then
      printf '  <testcase name="human-attestation">'
      [[ "$ATTESTATION_STATUS" == "PASS" ]] || printf '<failure message="%s" />' "$(escape_xml "$ATTESTATION_STATUS")"
      printf '</testcase>\n'
    fi
    printf '</testsuite>\n'
  } > "$tmp" || return
  mv "$tmp" "$RUN_DIR/junit.xml"
}

finalize() {
  local code="$1"
  ((FINALIZED == 0)) || return "$code"
  FINALIZED=1
  write_junit || code=1
  write_summary || code=1
  printf 'Result: %s\n' "$FINAL_STATUS"
  return "$code"
}

handle_signal() {
  trap - INT TERM
  if [[ "$ACTIVE_CHILD_PID" =~ ^[1-9][0-9]*$ ]]; then
    kill -TERM "$ACTIVE_CHILD_PID" 2>/dev/null || true
    wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
    ACTIVE_CHILD_PID=""
  fi
  FINAL_STATUS="FAIL"
  FINAL_REASON="interrupted"
  finalize 130
  exit 130
}

build_inputs_clean() {
  git -C "$REPOSITORY_ROOT" diff --quiet -- . ':(exclude).gitignore' &&
    git -C "$REPOSITORY_ROOT" diff --cached --quiet -- . ':(exclude).gitignore' &&
    [[ -z "$(git -C "$REPOSITORY_ROOT" ls-files --others --exclude-standard)" ]]
}

run_local_gate() {
  (
    echo "revision=$REVISION"
    git -C "$REPOSITORY_ROOT" status --short
    build_inputs_clean || { echo "Build-affecting tracked or untracked changes are present" >&2; return 1; }
    timeout --kill-after=10s 300s "$DEVICE_TEST_ROOT/tests/run-tests.sh" || return
    timeout --kill-after=30s 1800s "$REPOSITORY_ROOT/gradlew" \
      -p "$REPOSITORY_ROOT" \
      --console=plain \
      :mobile:testDebugUnitTest \
      :shared:testDebugUnitTest \
      :engine:testDebugUnitTest \
      :installer:testDebugUnitTest \
      :watcher:testDebugUnitTest \
      :open:testDebugUnitTest \
      :probe:testDebugUnitTest \
      :assembly:assembleCompleteNormalDebug \
      :assembly:assembleCompleteNormalDebugAndroidTest \
      :assembly:assembleCompleteNormalRelease \
      :probe:assembleDebug
  ) > "$RUN_DIR/local.stdout" 2> "$RUN_DIR/local.stderr" &
  ACTIVE_CHILD_PID=$!
  wait "$ACTIVE_CHILD_PID"
  local code=$?
  ACTIVE_CHILD_PID=""
  return "$code"
}

run_device_gate() {
  local -a command=(
    "$DEVICE_TEST_ROOT/run.sh"
    --serial "$SERIAL"
    --adb "$ADB_EXECUTABLE"
    --suite release-gate
    --artifacts "$RUN_DIR/device"
    --adb-timeout-seconds "$ADB_TIMEOUT_SECONDS"
    --action-timeout-seconds "$ACTION_TIMEOUT_SECONDS"
    --allow-destructive
    --expected-fingerprint "$EXPECTED_FINGERPRINT"
  )
  [[ -n "$ADB_SERVER_PORT" ]] && command+=(--adb-server-port "$ADB_SERVER_PORT")
  "${command[@]}" > "$RUN_DIR/device-runner.stdout" 2> "$RUN_DIR/device-runner.stderr" &
  ACTIVE_CHILD_PID=$!
  wait "$ACTIVE_CHILD_PID"
  local code=$?
  ACTIVE_CHILD_PID=""
  return "$code"
}

record_child_hashes() {
  local output="$RUN_DIR/device-files.sha256"
  : > "$output"
  while IFS= read -r -d '' path; do
    sha256sum "$path" >> "$output" || return
  done < <(find "$RUN_DIR/device" -type f -print0 | sort -z)
}

main() {
  parse_arguments "$@"
  create_run_dir || exit 1
  trap handle_signal INT TERM
  REVISION="$(git -C "$REPOSITORY_ROOT" rev-parse HEAD)"

  if ! run_local_gate; then
    LOCAL_STATUS="FAIL"
    FINAL_REASON="local_gate_failed"
    finalize 1
    exit 1
  fi
  LOCAL_STATUS="PASS"

  if ! run_device_gate; then
    DEVICE_STATUS="FAIL"
    FINAL_REASON="device_gate_failed"
    finalize 1
    exit 1
  fi
  DEVICE_STATUS="PASS"
  record_child_hashes || {
    FINAL_REASON="device_evidence_hash_failed"
    finalize 1
    exit 1
  }

  local candidate_file="$RUN_DIR/device/scenarios/deploy-debug-candidate/candidate.properties"
  CANDIDATE_HASH="$(sed -n 's/^candidate_sha256=//p' "$candidate_file" | sed -n '1p')"
  [[ "$CANDIDATE_HASH" =~ ^[0-9a-f]{64}$ ]] || {
    FINAL_REASON="candidate_hash_missing"
    finalize 1
    exit 1
  }

  if ((AUTOMATED_ONLY == 1)); then
    ATTESTATION_STATUS="NOT_REQUIRED_AUTOMATED_ONLY"
    FINAL_STATUS="AUTOMATED_PASS"
    FINAL_REASON="automated_checks_passed"
    finalize 0
    exit 0
  fi

  if ! validate_release_attestation \
      "$ATTESTATION" "$REVISION" "$SERIAL" "$EXPECTED_FINGERPRINT" "$CANDIDATE_HASH" \
      > "$RUN_DIR/attestation.stdout" 2> "$RUN_DIR/attestation.stderr"; then
    ATTESTATION_STATUS="FAIL"
    FINAL_REASON="attestation_rejected"
    finalize 1
    exit 1
  fi
  cp "$ATTESTATION" "$RUN_DIR/release-attestation.properties" || {
    ATTESTATION_STATUS="FAIL"
    FINAL_REASON="attestation_copy_failed"
    finalize 1
    exit 1
  }
  sha256sum "$RUN_DIR/release-attestation.properties" > "$RUN_DIR/release-attestation.sha256" || {
    ATTESTATION_STATUS="FAIL"
    FINAL_REASON="attestation_hash_failed"
    finalize 1
    exit 1
  }
  ATTESTATION_STATUS="PASS"
  FINAL_STATUS="RELEASE_READY"
  FINAL_REASON="all_release_requirements_passed"
  finalize 0
}

main "$@"
