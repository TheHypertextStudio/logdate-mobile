#!/usr/bin/env bash
# Regression tests for hook validation utilities.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

VALIDATOR="$REPO_ROOT/scripts/validation/validate-commit-message.sh"
FORMATTER="$REPO_ROOT/scripts/validation/format-commit-message.sh"
COMMON="$REPO_ROOT/scripts/validation/hook-common.sh"

pass_count=0

assert_equals() {
  local expected="$1"
  local actual="$2"
  if [[ "$expected" != "$actual" ]]; then
    echo "FAIL: expected '$expected', got '$actual'"
    exit 1
  fi
  pass_count=$((pass_count + 1))
}

assert_exit() {
  local expected="$1"
  shift
  set +e
  "$@" > /dev/null 2>&1
  local status=$?
  set -e
  if [[ $status -ne $expected ]]; then
    echo "FAIL: expected exit $expected, got $status for command: $*"
    exit 1
  fi
  pass_count=$((pass_count + 1))
}

assert_contains() {
  local needle="$1"
  local text="$2"
  if ! echo "$text" | grep -q "$needle"; then
    echo "FAIL: expected output to contain '$needle'"
    echo "$text"
    exit 1
  fi
  pass_count=$((pass_count + 1))
}

# validate-commit-message hard checks
assert_exit 0 "$VALIDATOR" --message "chore: update docs" --context checklist
assert_exit 1 "$VALIDATOR" --message "feat(bad-scope): add playback controls" --context checklist
assert_exit 1 "$VALIDATOR" --message "invalid title" --context checklist
assert_exit 1 "$VALIDATOR" --message "fix(timeline): this title is intentionally made very long so it exceeds seventy two characters for validator checks" --context checklist
assert_exit 1 "$VALIDATOR" --message $'feat(editor): update logic\n\nphase 2 rollout' --context checklist
assert_exit 1 "$VALIDATOR" --message $'fix(editor): wrap commit body\n\nThis body line is intentionally made very long so it exceeds seventy two characters and should fail validation checks.' --context checklist

# validate-commit-message warning behavior (imperative mood warning in commit-msg context)
set +e
warning_output="$("$VALIDATOR" --message "feat(editor): Added playback controls" --context commit-msg 2>&1)"
warning_status=$?
set -e
if [[ $warning_status -ne 0 ]]; then
  echo "FAIL: expected warning-only message to pass"
  echo "$warning_output"
  exit 1
fi
assert_contains "WARNING: Use imperative mood in commit title." "$warning_output"

# format-commit-message auto-wrap behavior
tmp_msg_file="$(mktemp "${TMPDIR:-/tmp}/commit-msg-test.XXXXXX")"
trap 'rm -f "$tmp_msg_file"' EXIT
cat << 'EOF' > "$tmp_msg_file"
fix(editor): wrap commit body

This body line is intentionally made very long so it exceeds seventy two characters and should be wrapped automatically by the formatter script.
EOF

assert_exit 0 "$FORMATTER" "$tmp_msg_file"
assert_exit 0 "$VALIDATOR" --file "$tmp_msg_file" --context checklist
assert_equals "fix(editor): wrap commit body" "$(head -n 1 "$tmp_msg_file")"

# hook-common module resolution
source "$COMMON"

# A real task list can exceed the pipe buffer; early matches must still pass under pipefail.
large_task_listing=$'jvmTest - Runs JVM tests.\n'"$(printf '%200000s' '')"
large_task_list_has_jvm_tests() {
  module_has_task "$large_task_listing" "jvmTest"
}
large_task_list_has_no_partial_match() {
  module_has_task "$large_task_listing" "jvmTestExtra"
}
assert_exit 0 large_task_list_has_jvm_tests
assert_exit 1 large_task_list_has_no_partial_match

resolved="$(modules_from_files $'client/feature/timeline/src/main/kotlin/Foo.kt\nclient/feature/timeline/src/main/kotlin/Bar.kt\nclient/repository/src/commonMain/kotlin/Baz.kt\nclient/datastore/src/commonMain/kotlin/Qux.kt')"
assert_contains "^:client:feature:timeline$" "$resolved"
assert_contains "^:client:repository$" "$resolved"
assert_contains "^:client:datastore$" "$resolved"

fetch_calls_total=0
fetch_module_tasks() {
  local module="$1"
  fetch_calls_total=$((fetch_calls_total + 1))

  case "$module" in
    :app:android-main)
      cat << 'EOF'
Verification tasks
------------------
test - Runs the tests.
testDebugUnitTest - Runs debug unit tests.
EOF
      ;;
    :client:permissions)
      cat << 'EOF'
Verification tasks
------------------
jvmTest - Runs JVM tests.
allTests - Runs tests for all targets.
EOF
      ;;
    :client:feature:timeline)
      cat << 'EOF'
Verification tasks
------------------
allTests - Runs tests for all targets.
EOF
      ;;
    *)
      cat << 'EOF'
Verification tasks
------------------
check - Runs all checks.
EOF
      ;;
  esac
}

MODULE_TASKS_CACHE_KEYS=()
MODULE_TASKS_CACHE_VALUES=()
resolved_test_task="$(resolve_test_task_for_module ":app:android-main" "balanced")"
assert_equals ":app:android-main:test" "$resolved_test_task"

resolved_test_task="$(resolve_test_task_for_module ":client:permissions" "balanced")"
assert_equals ":client:permissions:jvmTest" "$resolved_test_task"

resolved_test_task="$(resolve_test_task_for_module ":client:feature:timeline" "balanced")"
assert_equals ":client:feature:timeline:allTests" "$resolved_test_task"

# Same module should only be fetched once due to cache.
fetch_calls_before_cached_lookup="$fetch_calls_total"
resolved_test_task="$(resolve_test_task_for_module ":client:permissions" "balanced")"
assert_equals ":client:permissions:jvmTest" "$resolved_test_task"
assert_equals "$fetch_calls_before_cached_lookup" "$fetch_calls_total"

if resolve_test_task_for_module ":unknown:module" "balanced" > /dev/null 2>&1; then
  echo "FAIL: expected unknown module to fail test task resolution"
  exit 1
fi
pass_count=$((pass_count + 1))

MODULE_TASKS_CACHE_KEYS=()
MODULE_TASKS_CACHE_VALUES=()
resolve_test_tasks_for_modules "balanced" \
  ":app:android-main" \
  ":client:permissions" \
  ":unknown:module"
resolved_list="$(printf '%s\n' "${RESOLVED_TEST_TASKS[@]}")"
unresolved_list="$(printf '%s\n' "${UNRESOLVED_TEST_MODULES[@]}")"
assert_contains "^:app:android-main:test$" "$resolved_list"
assert_contains "^:client:permissions:jvmTest$" "$resolved_list"
assert_contains "^:unknown:module$" "$unresolved_list"

echo "Hook regression tests passed ($pass_count assertions)."
