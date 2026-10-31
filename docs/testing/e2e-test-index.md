# E2E Test Index

Complete index of all end-to-end tests in the LogDate project with terminal commands for running them.

## Organization Standard

Tests are organized into three categories:

- **Pure Server Tests**: Gradle `testApplication` tests, no device needed
- **Client Gradle Tests**: Android instrumented tests on Gradle Managed Devices
- **Client ADB Tests**: Verify actual device behavior via adb shell scripts

See [E2E Test Organization Standard](./e2e-test-organization.md) for the complete framework and guidelines for adding new tests.

## Quick Start Commands

```bash
# Run all Android app e2e tests
./run test:android:e2e

# Run all server-side e2e tests
./gradlew :server:test --tests "app.logdate.server.e2e.*"

# Run real client-server integration e2e tests
./gradlew :integration:server-client-e2e:test

# Run specific e2e test suite
./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest -Plogdate.androidTestClass=app.logdate.client.e2e.TestClassName

# Run with verbose output
./run test:android:e2e --info

# Run the Android share UX suite
./tests/e2e/test-share-functionality.sh

# List available e2e tests without running
./run test:android:e2e --dry-run
```

## Client-Side E2E Tests (Android/Compose)

Client-side tests verify complete user workflows on the Android app using Espresso, Compose testing, and activity scenarios.

### Location

`app/android-main/src/androidTest/kotlin/app/logdate/client/e2e/`

### 1. Multi-Window Editor E2E Tests

**File**: `MultiWindowEditorE2ETest.kt`

**Scenario**: User edits multiple journal entries simultaneously in separate windows

**What it tests**:

- Opening existing entries in new editor windows
- Creating new blank editor windows
- Independent state management across multiple windows
- Intent extras (entry_id, journal_id, initial_text, attachments)
- Multi-window flags (FLAG_ACTIVITY_NEW_DOCUMENT, FLAG_ACTIVITY_MULTIPLE_TASK)
- Split-screen and multi-window support
- Task affinity configuration for separate task stacks
- Window metrics and configuration changes

**Requirements**:

- Android SDK system images for the managed phone and tablet profiles
- Host machine resources for the managed device snapshots

**Commands**:

```bash
# Run all multi-window editor tests
./run test:android:multiwindow

# Run specific test
./run test:android:multiwindow

# Run with debugging enabled
./run test:android:multiwindow --info
```

**ADB Shell Testing**:

For comprehensive device behavior verification using adb shell commands, use the dedicated shell script:

```bash
# Run full test suite
./tests/e2e/test-multi-window-editor.sh

# Run quick smoke test
./tests/e2e/test-multi-window-editor.sh --smoke

# Run with verbose output
./tests/e2e/test-multi-window-editor.sh --verbose

# See all options
./tests/e2e/test-multi-window-editor.sh --help
```

See: `tests/e2e/test-multi-window-editor.sh` for details on testing actual window behavior, task recents, and multi-window integration.

### 2. Share UX E2E Tests

**Files**:

- `IncomingShareE2ETest.kt`
- `ShareReceiverE2ETest.kt`
- `SharingEntryPointsE2ETest.kt`

**Scenario**: User shares content into LogDate, uses chooser actions, and taps outbound share CTAs from timeline and library surfaces.

**What it tests**:

- `ACTION_SEND` text import into the editor
- image and multi-image import into one draft
- unsupported share rejection
- chooser `Copy link` action behavior
- timeline memory recall share CTA wiring
- media detail share CTA wiring

**Commands**:

```bash
# Build, provision, and run the full share UX suite on managed devices
./tests/e2e/test-share-functionality.sh

# Run with verbose Gradle output
./tests/e2e/test-share-functionality.sh --verbose
```

**Coverage**:

- `IncomingShareE2ETest` - 5 tests
- `ShareReceiverE2ETest` - 1 test
- `SharingEntryPointsE2ETest` - 2 tests

**Test Coverage**:

- ✅ testOpenEntryInNewWindow - Existing entry loading
- ✅ testCreateNewBlankEditorWindow - New entry creation
- ✅ testCreateEditorWindowWithInitialContent - Initial text/attachments
- ✅ testOpenDifferentEntryTypesInSeparateWindows - Various note types
- ✅ testEditorActivityIsInSeparateTask - Task isolation
- ✅ testEditorActivitySupportsSplitScreen - Split-screen support
- ✅ testEditorWindowHandlesConfigurationChanges - Rotation/foldable support

**Related Documentation**:

- [E2E Test Journeys](../e2e-test-journeys.md#55-multi-window-editor-journey) - Full user journey
- [EditorActivity.kt](../../app/compose-main/src/androidMain/kotlin/app/logdate/client/EditorActivity.kt) - Implementation
- [EditorManager.kt](../../app/compose-main/src/androidMain/kotlin/app/logdate/navigation/EditorManager.kt) - Window management

---

## Server-Side E2E Tests (Ktor)

**Pure Gradle Tests** - No device/emulator required. Use Ktor's `testApplication {}` framework for API endpoint testing.

Server-side tests verify API endpoints and backend functionality using Ktor's test application DSL.

### Location

`server/src/test/kotlin/e2e/`

### 1. Auth Onboarding and Sign-in E2E Tests

**File**: `journeys/auth/AuthOnboardingAndSigninJourneyE2ETest.kt`

**Scenario**: Username availability, passkey signup through to auth tokens, and the response
when Google sign-in is not configured

```bash
./gradlew :server:test --tests "app.logdate.server.e2e.journeys.auth.AuthOnboardingAndSigninJourneyE2ETest"
./tests/e2e/test-accounts-e2e.sh
```

### 2. Auth Restore Credential E2E Tests

**File**: `journeys/auth/AuthRestoreCredentialJourneyE2ETest.kt`

**Scenario**: Registering and redeeming a restore credential when moving to a new device,
including the register-then-restore round trip, replay rejection, and the unauthenticated cases

```bash
./gradlew :server:test --tests "app.logdate.server.e2e.journeys.auth.AuthRestoreCredentialJourneyE2ETest"
./tests/e2e/run-e2e-tests.sh auth
```

### 3. Backup and Sync E2E Tests

**File**: `journeys/sync/BackupAndSyncJourneyE2ETest.kt`

**Scenario**: Multi-device sync flow with conflict detection and media download, plus a round
trip of text, image, video, and audio entries

```bash
./gradlew :server:test --tests "app.logdate.server.e2e.journeys.sync.BackupAndSyncJourneyE2ETest"
./tests/e2e/test-sync-e2e.sh
```

### 4. Platform Sanity E2E Tests

**File**: `smoke/PlatformSanityE2ETest.kt`

**Scenario**: Smoke coverage that health and root answer, and that the auth and sync endpoints
validate their requests and reject calls without a token

```bash
./gradlew :server:test --tests "app.logdate.server.e2e.smoke.PlatformSanityE2ETest"
```

### 5. Real Client-Server Integration E2E Tests

**Module**: `integration/server-client-e2e`

**Scenario**: Real `LogDateCloudApiClient` interactions against a booted Ktor server for auth and sync journeys.

```bash
./gradlew :integration:server-client-e2e:test
```

See [Server-Client E2E README](../../integration/server-client-e2e/README.md) for suite layout and harness details.

---

## Test Organization Summary

| Test Name                             | Type                     | Location                            | Shell Script                        |
| ------------------------------------- | ------------------------ | ----------------------------------- | ----------------------------------- |
| MultiWindowEditorE2ETest              | Client Gradle            | `app/.../e2e/`                      | ✅ `./run test:android:multiwindow` |
| Share UX Suite                        | Client Instrumented      | `app/.../e2e/`                      | ✅ `./run test:android:share`       |
| AuthOnboardingAndSigninJourneyE2ETest | Pure Server              | `server/.../e2e/journeys/auth/`     | ✅ `test-accounts-e2e.sh`           |
| AuthRestoreCredentialJourneyE2ETest   | Pure Server              | `server/.../e2e/journeys/auth/`     | ✅ `run-e2e-tests.sh auth`          |
| BackupAndSyncJourneyE2ETest           | Pure Server              | `server/.../e2e/journeys/sync/`     | ✅ `test-sync-e2e.sh`               |
| PlatformSanityE2ETest                 | Pure Server              | `server/.../e2e/smoke/`             | ❌ None                             |
| server-client-e2e                     | Pure Server + Client API | `integration/server-client-e2e/...` | ❌ None                             |

**Legend**:

- **Client Gradle**: Android instrumented test + optional adb shell script
- **Pure Server**: No device needed, pure gradle testApplication
- **Shell Script**: Dedicated script for adb-based device behavior testing

---

## Running All E2E Tests

### Run Everything

```bash
# All e2e tests (client + server)
./run test:android:e2e && ./gradlew :server:test --tests "app.logdate.server.e2e.*"

# Client only
./run test:android:e2e

# Server only
./gradlew :server:test --tests "app.logdate.server.e2e.*"
```

### Run by Category

```bash
# All multi-window tests
./run test:android:multiwindow

# All account tests
./gradlew :server:test --tests "app.logdate.server.e2e.journeys.auth.*"

# All authentication tests
./gradlew :server:test --tests "app.logdate.server.e2e.auth.*"
```

### Run with Reports

```bash
# Generate test report
./run test:android:e2e --info

# Generate HTML report
./run test:android:e2e

# View report (after build)
# HTML report location: build/reports/tests/
```

---

## CI/CD Integration

`.github/workflows/ci.yml` runs on every push and pull request targeting `main` or `develop`.
Two of its jobs cover the suites on this page:

- **`test` — Unit tests and coverage**: `./gradlew test allTests`, with Postgres and Redis service
  containers. This is what exercises the `:server:test` e2e classes above. It leaves out the iOS
  and Wasm test targets, which the `multiplatform-tests` job runs on a runner of its own.
- **`e2e-tests` — Server / client e2e**: `./gradlew :integration:server-client-e2e:test`, with a
  Postgres service container.

**No Android instrumented test runs in CI.** Every Gradle Managed Device task —
`./run test:android:e2e` and every lane beneath it — is local-only. Breakage in the
`app/android-main` androidTest suite will not fail a pull request, so run those lanes by hand
before trusting them.

### Running what CI runs

```bash
# The two jobs that cover the suites on this page
./gradlew test allTests
./gradlew :integration:server-client-e2e:test

# The client e2e lane CI does not run
./run test:android:e2e
```

---

## Debugging E2E Tests

### Enable Logging

```bash
# Run with napier debug logging
./run test:android:multiwindow --info

# Inspect managed-device logs under build outputs when needed
find app/android-main/build/outputs/androidTest-results/managedDevice -type f | sort
```

### Managed Device Artifacts

```bash
# List managed-device result artifacts
find app/android-main/build/outputs/androidTest-results/managedDevice -type f | sort

# List managed-device HTML reports
find app/android-main/build/reports/androidTests/managedDevice -type f | sort
```

### Stop on First Failure

```bash
./run test:android:multiwindow --fail-fast
```

---

## Test Requirements Checklist

Before running E2E tests, verify:

- [ ] **Android SDK Images Installed**: Managed device system images are available
- [ ] **Disk Space**: Host machine has enough free space for managed device snapshots
- [ ] **Network**: If testing with backend, network connectivity available
- [ ] **Storage**: Host machine has minimum 500MB free storage for test artifacts

### Pre-Test Setup

```bash
# Run the managed Android e2e lane
./run test:android:e2e
```

---

## Troubleshooting

### Managed Device Provisioning Fails

```bash
./run test:android:e2e --info
sdkmanager --list | rg "system-images;android"
```

### Tests Timeout

```bash
# Increase timeout (in gradle.properties)
android.testInstrumentationRunnerArguments.timeout=60000

# Or run with extended timeout
./run test:android:e2e \
  -Pandroid.testInstrumentationRunnerArguments.timeout=120000
```

### Multi-Window Tests Skipped

- Ensure the managed device system images were downloaded successfully
- Re-run with `--info` to inspect provisioning and instrumentation output

### Activity Not Found

```bash
./gradlew :app:android-main:processDebugManifest --info
```

---

## Adding New E2E Tests

To add a new e2e test:

1. **Create test class** in appropriate directory:
   - Client: `app/android-main/src/androidTest/kotlin/app/logdate/client/e2e/`
   - Server: `server/src/test/kotlin/e2e/[category]/`

2. **Name it with E2ETest suffix**: `MyFeatureE2ETest.kt`

3. **Add to this index**: Document commands and requirements

4. **Document in ../e2e-test-journeys.md**: Add user journey if applicable

5. **Include terminal commands**: In doc comments for easy reference

6. **Test locally before committing**:

   ```bash
   ./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest -Plogdate.androidTestClass=app.logdate.client.e2e.MyFeatureE2ETest
   ```

7. **Verify CI passes**: PR will run full test suite

---

## See Also

- [E2E Organization Standard](./e2e-test-organization.md) - Test categorization, shell script standards, and guidelines
- [Testing Strategy](./introduction.md) - Complete testing guide
- [E2E Test Journeys](../e2e-test-journeys.md) - User workflows being tested
- [Build Commands](../../CLAUDE.md#build-commands) - Project build docs
- [Android Testing Guide](https://developer.android.com/training/testing) - Official Android docs
