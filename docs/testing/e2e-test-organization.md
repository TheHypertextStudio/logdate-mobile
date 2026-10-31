# E2E Test Organization Standard

This document defines the systematic approach for organizing and running end-to-end tests in LogDate, particularly as we scale to hundreds of tests involving adb shell commands.

## Test Categories

## Android Device Targeting Standard

Android e2e coverage in this repo should default to Gradle Managed Devices. Use direct `adb` targeting only for the supplemental shell workflows that verify behavior Gradle instrumentation cannot cover:

```bash
./run test:android:e2e
```

Use managed-device Gradle tasks for Android instrumented tests and reserve `adb -s emulator-...` for supplemental shell/logcat workflows only.

### 1. Pure Server Tests (Gradle testApplication)

**Definition**: Tests that verify backend API logic without device interaction.

**Characteristics**:

- Use Ktor's `testApplication {}` framework
- Run in-process on the JVM
- No device/emulator required
- Pure gradle execution

**Examples**:

- `journeys/auth/AuthOnboardingAndSigninJourneyE2ETest` - Passkey signup and sign-in flows
- `journeys/auth/AuthRestoreCredentialJourneyE2ETest` - Restore-credential registration and redemption
- `journeys/sync/BackupAndSyncJourneyE2ETest` - Backup/sync server behavior
- `smoke/PlatformSanityE2ETest` - Endpoint smoke coverage

**How to Run**:

```bash
# Run all server e2e tests
./gradlew :server:test --tests "app.logdate.server.e2e.*"

# Run specific test class
./gradlew :server:test --tests "app.logdate.server.e2e.journeys.auth.AuthOnboardingAndSigninJourneyE2ETest"

# Run specific test method
./gradlew :server:test --tests "app.logdate.server.e2e.journeys.auth.AuthOnboardingAndSigninJourneyE2ETest.username availability endpoint works"
```

**When to Create Shell Script**: **NEVER** - These don't need shell scripts. Pure gradle.

---

### 2. Client Gradle Instrumented Tests (Gradle Managed Devices)

**Definition**: Android instrumented tests that run on Gradle Managed Devices using the gradle testing framework.

**Characteristics**:

- Use AndroidJUnit4, Espresso, ActivityScenario
- Run on managed virtual devices (requires Android SDK system images)
- Test Android framework behavior, UI interactions
- Gradle manages test execution and reporting

**Examples**:

- `MultiWindowEditorE2ETest` - Multi-window intent flags, activity lifecycle
- `IncomingShareE2ETest` - Share intents into LogDate drafts
- `ShareReceiverE2ETest` - Share chooser actions
- `SharingEntryPointsE2ETest` - Timeline and library share CTA wiring
- Future tests: Navigation flows, permission handling, database integration

**How to Run**:

```bash
# Run all client e2e tests
./run test:android:e2e

# Run specific test class
./run test:android:multiwindow

# Run with verbose output
./run test:android:multiwindow --info
```

**When to Create Shell Script**: **Only if** the test needs to verify actual device behavior via adb shell commands that gradle cannot test.

**Share UX runner**:
The Android share UX suite uses the same managed-device flow as the rest of the Android e2e suite:

```bash
./tests/e2e/test-share-functionality.sh
./tests/e2e/test-share-functionality.sh --verbose
```

---

### 3. Client ADB Shell Tests (Pure adb shell scripts)

**Definition**: Tests that verify actual device/UI behavior using adb shell commands directly.

**Characteristics**:

- Execute raw adb shell commands (e.g., `adb shell am start`, `adb shell dumpsys`)
- Test actual window management, task recents, activity lifecycle behavior
- Cannot be tested by gradle instrumented tests
- Require manual device setup and verification
- Run in shell scripts with color output and summary reporting

**Examples**:

- `tests/e2e/test-multi-window-editor.sh` - Supplemental adb verification for actual window creation, task isolation, and recents behavior

**How to Run**:

```bash
# Run the shell script directly
./tests/e2e/test-multi-window-editor.sh

# With options
./tests/e2e/test-multi-window-editor.sh --smoke
./tests/e2e/test-multi-window-editor.sh --verbose --auto-clear-data
```

**When to Create Shell Script**: **YES** - Create shell tests in `tests/e2e/` ONLY for tests that involve adb shell commands to verify real device behavior.

---

## Shell Script Organization

### Directory Structure

```
tests/e2e/
├── test-multi-window-editor.sh          # Supplemental adb-based window behavior checks
├── test-share-functionality.sh          # Managed-device Android share UX instrumentation
├── test-navigation-flows.sh             # (Future) Navigation flow tests via adb
├── test-permission-handling.sh          # (Future) Permission verification via adb
└── run-e2e-tests.sh                    # Master test runner
```

### Naming Convention

```
tests/e2e/test-<feature-name>.sh

Examples:
- test-multi-window-editor.sh
- test-navigation-flows.sh
- test-permission-handling.sh
- test-share-functionality.sh
```

**Guidelines**:

- Use lowercase with hyphens
- Be specific: `test-<feature-name>.sh` not `test-generic.sh`
- One script per major feature/component being tested

---

## Shell Script Structure Template

Every adb shell test script should follow this structure:

```bash
#!/bin/bash

#
# [Brief Description]
#
# Tests [what it tests] using adb shell commands
#
# Usage:
#   ./tests/e2e/test-<feature>.sh [options]
#
# Examples:
#   ./tests/e2e/test-<feature>.sh                    # Run all tests
#   ./tests/e2e/test-<feature>.sh --smoke            # Quick smoke test
#   ./tests/e2e/test-<feature>.sh --verbose          # Verbose output
#

set -e

# Color output
RED='\\033[0;31m'
GREEN='\\033[0;32m'
BLUE='\\033[0;34m'
NC='\\033[0m'

# Configuration
PACKAGE_NAME="${PACKAGE_NAME:-studio.hypertext.logdate}"
VERBOSE=false

# Test counters
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Helper functions
print_header() { /* ... */ }
print_test() { /* ... */ }
print_pass() { /* ... */ }
print_fail() { /* ... */ }
check_prerequisites() { /* ... */ }

# Individual test functions
test_<scenario_1>() {
    print_test "<description>"
    # adb commands to verify behavior
    # ...
}

test_<scenario_2>() {
    print_test "<description>"
    # adb commands to verify behavior
    # ...
}

# Summary and main execution
print_summary() { /* ... */ }
main() { /* ... */ }

main "$@"
```

---

## Integration with Test Index

The `docs/testing/e2e-test-index.md` should document:

### For Server Tests:

````markdown
**Commands**:

```bash
# Run via gradle (no shell script needed)
./gradlew :server:test --tests "app.logdate.server.e2e.TestClassName"
```
````

````

### For Gradle Instrumented Tests:
```markdown
**Commands**:
```bash
# Run via managed devices
./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest -Plogdate.androidTestClass=app.logdate.client.e2e.TestClassName

# For actual device behavior verification, see adb shell tests:
# ./tests/e2e/test-<feature>.sh
````

````

### For ADB Shell Tests:
```markdown
**Adb Shell Testing**:

For actual device behavior testing, use the dedicated shell script:

```bash
./tests/e2e/test-<feature>.sh
./tests/e2e/test-<feature>.sh --verbose
./tests/e2e/test-<feature>.sh --help
````

See: `tests/e2e/test-<feature>.sh` for full details

```

```

---

## Decision Tree: When to Create a Shell Script

```
Does the test involve:
├─ Gradle server testing (testApplication)?
│  └─ NO SCRIPT NEEDED → Use ./gradlew :server:test --tests "app.logdate.server.e2e.TestName"
│
├─ Gradle instrumented testing (managed devices)?
│  │
│  └─ Does it need to verify actual device behavior via adb?
│     ├─ NO → Use ./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest -Plogdate.androidTestClass=app.logdate.client.e2e.TestName
│     └─ YES → CREATE SCRIPT → ./tests/e2e/test-<feature>.sh
│         ├─ Script executes adb shell commands
│         ├─ Script verifies real device behavior
│         └─ Script has summary reporting
│
└─ Pure adb shell commands (no gradle)?
   └─ YES → CREATE SCRIPT → ./tests/e2e/test-<feature>.sh
```

---

## Common Adb Commands for Shell Tests

```bash
# Activity launching
adb shell am start -n "studio.hypertext.logdate/.ActivityName"
adb shell am start -n "studio.hypertext.logdate/.ActivityName" --es key "value"

# Window/task verification
adb shell dumpsys activity recents
adb shell dumpsys activity | grep "EditorActivity"
adb shell dumpsys package studio.hypertext.logdate

# App management. `pm clear` and `pm uninstall` are developer-only for LogDate packages --
# they destroy real journals, and scripts/block-device-wipe.sh blocks them. Force-stop instead.
adb shell am force-stop studio.hypertext.logdate
adb shell pm list packages | grep logdate

# Device info
adb shell getprop ro.build.version.sdk
adb devices -l

# Logcat monitoring
adb logcat -c
adb logcat | grep "YourTag"
```

---

## Shell Script Best Practices

1. **Prerequisite Checking**:
   - Verify adb is installed
   - Check device is connected
   - Check API level
   - Check app is installed

2. **Test Organization**:
   - Group related tests in functions
   - Use descriptive function names: `test_<scenario>()`
   - Increment counters for pass/fail tracking

3. **Output & Reporting**:
   - Color-coded output (green pass, red fail, blue info)
   - Clear test names and descriptions
   - Summary at the end with pass/fail counts
   - Helpful error messages with context

4. **Error Handling**:
   - `set -e` to exit on first error (or handle gracefully)
   - Validate command output before proceeding
   - Sleep delays between adb commands (app needs time to respond)

5. **Documentation**:
   - Header comment explaining purpose
   - Usage examples in script
   - --help flag showing options
   - Comments for complex adb commands

---

## Testing Different Scenarios

### Quick Smoke Tests

Create fast validation:

```bash
# Option: --smoke
# Runs only essential happy-path tests
# Takes ~30 seconds
```

### Full Test Suite

Comprehensive coverage:

```bash
# No options
# Runs all tests
# Takes ~2-5 minutes
```

### Debug/Verbose Mode

For troubleshooting:

```bash
# Option: --verbose
# Shows all adb commands being executed
# Helpful when tests fail
```

---

## Future Scaling: Multi-Platform Support

As we add iOS and Desktop platforms, extend this organization:

```
tests/e2e/
├── test-multi-window-editor.sh          # Android/adb
├── test-multi-window-editor.ios.sh      # iOS (if needed)
├── test-multi-window-editor.desktop.sh  # Desktop (if needed)
```

Or consolidate by platform:

```
tests/e2e/
├── android/
│   ├── test-multi-window-editor.sh
│   ├── test-navigation.sh
│   └── test-permissions.sh
├── ios/
│   └── test-*.sh
└── desktop/
    └── test-*.sh
```

---

## Continuous Integration Integration

Shell scripts are invoked by CI/CD via the master runner:

```bash
./tests/e2e/run-e2e-tests.sh all          # Runs all tests
./tests/e2e/run-e2e-tests.sh multi-window # Runs specific suite
```

CI/CD logs the full output and marks failures.

---

## See Also

- **E2E Test Index**: `docs/testing/e2e-test-index.md` - Complete list of all tests
- **Test Journeys**: `docs/e2e-test-journeys.md` - User workflows being tested
