#!/usr/bin/env bash
# Regression tests for hook validation utilities.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

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

# format-commit-message auto-wrap behavior
tmp_msg_file="$(mktemp "${TMPDIR:-/tmp}/commit-msg-test.XXXXXX")"
trap 'rm -f "$tmp_msg_file"' EXIT
cat << 'EOF' > "$tmp_msg_file"
fix(editor): wrap commit body

This body line is intentionally made very long so it exceeds seventy two characters and should be wrapped automatically by the formatter script.
EOF

assert_exit 0 "$FORMATTER" "$tmp_msg_file"
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
    :client:host-and-desktop)
      cat << 'EOF'
Verification tasks
------------------
desktopTest - Runs desktop tests.
testAndroidHostTest - Runs Android host tests.
EOF
      ;;
    :client:legacy-android)
      echo 'testAndroid - Runs Android target tests.'
      ;;
    :client:shared-with-ios)
      cat << 'EOF'
Verification tasks
------------------
desktopTest - Runs desktop tests.

Other tasks
-----------
compileTestKotlinIosSimulatorArm64 - Compiles a klibrary from the 'test' compilation in target 'iosSimulatorArm64'.
compileTestKotlinIosSimulatorArm64Extra
EOF
      ;;
    :client:shared-with-wasm)
      cat << 'EOF'
Verification tasks
------------------
jvmTest - Runs JVM tests.

Other tasks
-----------
compileTestKotlinIosSimulatorArm64 - Compiles a klibrary from the 'test' compilation in target 'iosSimulatorArm64'.
compileTestKotlinWasmJs - Compiles a klibrary from the 'test' compilation in target 'wasmJs'.
EOF
      ;;
    :client:device-only)
      cat << 'EOF'
connectedDebugAndroidTest - Runs device tests.
smokeDevicesGroupDebugAndroidTest - Runs managed device tests.
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

assert_exit 1 resolve_test_task_for_module ":client:feature:timeline" "balanced"
assert_exit 1 resolve_test_task_for_module ":client:legacy-android" "balanced"
assert_exit 1 resolve_test_task_for_module ":client:device-only" "balanced"
resolved_test_task="$(resolve_test_task_for_module ":client:host-and-desktop" "balanced")"
assert_equals ":client:host-and-desktop:testAndroidHostTest" "$resolved_test_task"

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
  ":client:feature:timeline" \
  ":client:legacy-android" \
  ":client:device-only" \
  ":unknown:module"
resolved_list="$(printf '%s\n' "${RESOLVED_TEST_TASKS[@]}")"
unresolved_list="$(printf '%s\n' "${UNRESOLVED_TEST_MODULES[@]}")"
assert_contains "^:app:android-main:test$" "$resolved_list"
assert_contains "^:client:permissions:jvmTest$" "$resolved_list"
assert_contains "^:unknown:module$" "$unresolved_list"
assert_contains "^:client:feature:timeline$" "$unresolved_list"
assert_contains "^:client:legacy-android$" "$unresolved_list"
assert_contains "^:client:device-only$" "$unresolved_list"

# Modules with iOS or Wasm targets also compile their tests for those targets; others add nothing.
MODULE_TASKS_CACHE_KEYS=()
MODULE_TASKS_CACHE_VALUES=()
resolve_test_tasks_for_modules "balanced" ":client:permissions" ":client:shared-with-ios" ":client:shared-with-wasm"
fetch_calls_before_native_lookup="$fetch_calls_total"
resolve_native_compile_tasks_for_modules ":client:permissions" ":client:shared-with-ios" ":client:shared-with-wasm"
assert_equals "$fetch_calls_before_native_lookup" "$fetch_calls_total"
assert_equals $':client:shared-with-ios:compileTestKotlinIosSimulatorArm64\n:client:shared-with-wasm:compileTestKotlinIosSimulatorArm64\n:client:shared-with-wasm:compileTestKotlinWasmJs' \
  "$(printf '%s\n' "${RESOLVED_NATIVE_COMPILE_TASKS[@]}")"
resolve_native_compile_tasks_for_modules ":client:permissions"
assert_equals 0 "${#RESOLVED_NATIVE_COMPILE_TASKS[@]}"

# Probe the product hook's first subprocess without opening a window or invoking Gradle.
probe_dir="$(mktemp -d "${TMPDIR:-/tmp}/hook-headless-test.XXXXXX")"
trap 'rm -f "$tmp_msg_file"; rm -rf "$probe_dir"' EXIT
mkdir -p "$probe_dir/bin"
cat > "$probe_dir/HookHeadlessProbe.java" << 'EOF'
class HookHeadlessProbe {
    public static void main(String[] args) {
        System.out.println(java.awt.GraphicsEnvironment.isHeadless());
        System.out.println(System.getProperty("studio.hook.option"));
    }
}
EOF
cat > "$probe_dir/bin/git" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
    rev-parse)
        "$HOOK_PROBE_JAVA" "$HOOK_PROBE_SOURCE" > "$HOOK_PROBE_RESULT" 2> "$HOOK_PROBE_RESULT.stderr"
        printf '%s\n' "$HOOK_PROBE_ROOT"
        ;;
    rev-list) ;;
    *) exit 1 ;;
esac
EOF
chmod +x "$probe_dir/bin/git"
java_path="$(command -v java)"
printf 'refs/heads/probe aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa refs/heads/probe %s\n' "$ZERO_SHA" \
  | env PATH="$probe_dir/bin:$PATH" \
    JAVA_TOOL_OPTIONS='-Dstudio.hook.option=retained -Djava.awt.headless=false' \
    HOOK_PROBE_JAVA="$java_path" \
    HOOK_PROBE_SOURCE="$probe_dir/HookHeadlessProbe.java" \
    HOOK_PROBE_RESULT="$probe_dir/result" \
    HOOK_PROBE_ROOT="$REPO_ROOT" \
    bash "$REPO_ROOT/.hypertext/git-product/pre-push"
assert_equals $'true\nretained' "$(cat "$probe_dir/result")"

# Gradle's failure report names each failed task; tasks only the iOS or Wasm compile runs are native.
gradle_failure_report() {
  printf 'FAILURE: Build completed with %s failures.\n\n' "$#"
  local task=""
  for task in "$@"; do
    printf '1: Task failed with an exception.\n-----------\n* What went wrong:\n'
    printf "Execution failed for task '%s'.\n> Compilation finished with errors\n\n" "$task"
  done
  printf 'BUILD FAILED in 9s\n'
}
assert_equals "tests" "$(gradle_failure_report ':client:feature:editor:testAndroidHostTest' | gradle_failure_kinds)"
assert_equals "native" "$(gradle_failure_report ':client:feature:editor:compileTestKotlinIosSimulatorArm64' | gradle_failure_kinds)"
assert_equals "native" "$(gradle_failure_report ':client:util:compileTestKotlinWasmJs' | gradle_failure_kinds)"
assert_equals $'tests\nnative' "$(gradle_failure_report \
  ':client:feature:editor:desktopTest' \
  ':client:ui:compileKotlinIosSimulatorArm64' \
  ':client:ui:generateResourceAccessorsForIosMain' \
  ':client:util:downloadKotlinNativeDistribution' | gradle_failure_kinds)"
# A task both checks run blocks the tests too, so it is reported as a test failure.
assert_equals "tests" "$(gradle_failure_report ':client:ui:generateComposeResClass' | gradle_failure_kinds)"
assert_equals "native" "$(printf "* What went wrong:\nCould not determine the dependencies of task ':client:util:compileTestKotlinWasmJs'.\n" | gradle_failure_kinds)"
assert_equals "tests" "$(printf "* What went wrong:\nA problem occurred configuring project ':client:ui'.\n" | gradle_failure_kinds)"

# The product pre-push runs the affected module's tests and its iOS test compile in one Gradle call
# and reports each failed check by the task that failed.
push_fixture="$probe_dir/push-fixture"
mkdir -p "$push_fixture/bin" "$push_fixture/repo/scripts/validation" "$push_fixture/repo/client/feature/sample"
cp "$COMMON" "$push_fixture/repo/scripts/validation/hook-common.sh"
touch "$push_fixture/repo/client/feature/sample/build.gradle.kts"
cat > "$push_fixture/bin/git" << 'EOF'
#!/usr/bin/env bash
case "$1" in
    rev-parse) printf '%s\n' "$PUSH_FIXTURE_ROOT" ;;
    rev-list) printf 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n' ;;
    diff-tree) printf 'client/feature/sample/src/commonTest/kotlin/SampleTest.kt\n' ;;
    *) exit 1 ;;
esac
EOF
printf '#!/bin/sh\nexit 0\n' > "$push_fixture/bin/searchdeadcode"
cat > "$push_fixture/repo/gradlew" << 'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$PUSH_FIXTURE_ROOT/gradle-calls"
case " $* " in
    *" :client:feature:sample:tasks "*)
        printf 'testAndroidHostTest - Runs Android host tests.\n'
        printf "compileTestKotlinIosSimulatorArm64 - Compiles a klibrary from the 'test' compilation.\n"
        ;;
    *" --continue "*)
        failed=()
        [[ "$PUSH_FIXTURE_FAIL" == *tests* ]] && failed+=(":client:feature:sample:testAndroidHostTest")
        [[ "$PUSH_FIXTURE_FAIL" == *native* ]] && failed+=(":client:feature:sample:compileTestKotlinIosSimulatorArm64")
        [[ ${#failed[@]} -eq 0 ]] && exit 0
        for task in "${failed[@]}"; do
            printf "* What went wrong:\nExecution failed for task '%s'.\n" "$task" >&2
        done
        exit 1
        ;;
esac
exit 0
EOF
chmod +x "$push_fixture/bin/git" "$push_fixture/bin/searchdeadcode" "$push_fixture/repo/gradlew"
push_with_failures() {
  : > "$push_fixture/repo/gradle-calls"
  set +e
  push_output="$(printf 'refs/heads/sample bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb refs/heads/sample %s\n' "$ZERO_SHA" \
    | env PATH="$push_fixture/bin:$PATH" PUSH_FIXTURE_ROOT="$push_fixture/repo" PUSH_FIXTURE_FAIL="$1" \
      bash "$REPO_ROOT/.hypertext/git-product/pre-push" 2>&1)"
  push_status=$?
  set -e
}

push_with_failures "none"
assert_equals 0 "$push_status"
check_calls="$(grep -e '--continue' "$push_fixture/repo/gradle-calls")"
assert_equals 1 "$(printf '%s\n' "$check_calls" | grep -c .)"
assert_contains ":client:feature:sample:testAndroidHostTest" "$check_calls"
assert_contains ":client:feature:sample:compileTestKotlinIosSimulatorArm64" "$check_calls"
assert_equals 1 "$(grep -c "compileTestKotlinIosSimulatorArm64" "$push_fixture/repo/gradle-calls")"

push_with_failures "native"
assert_equals 1 "$push_status"
assert_contains "NATIVE: Affected modules' tests do not compile for iOS or Wasm" "$push_output"
assert_exit 1 grep -q "TESTS:" <<< "$push_output"

push_with_failures "tests"
assert_equals 1 "$push_status"
assert_contains "TESTS: Tests failed for affected modules" "$push_output"
assert_exit 1 grep -q "NATIVE:" <<< "$push_output"

push_with_failures "tests native"
assert_equals 1 "$push_status"
assert_contains "TESTS: Tests failed for affected modules" "$push_output"
assert_contains "NATIVE: Affected modules' tests do not compile for iOS or Wasm" "$push_output"

echo "Hook regression tests passed ($pass_count assertions)."
