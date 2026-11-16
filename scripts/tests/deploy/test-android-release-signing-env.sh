#!/usr/bin/env bash
set -euo pipefail

source "$(git rev-parse --show-toplevel)/scripts/tests/lib/assertions.sh"
enter_repo_root

# Release signing is decided only by the release plugin; the app applies it and holds no signing logic.
app_build="app/android-main/build.gradle.kts"
signing="build-logic/src/main/kotlin/app/logdate/ReleaseSigning.kt"

assert_file_contains 'id("app.logdate.android-release")' "$app_build"
assert_file_not_contains 'LOGDATE_RELEASE_STORE_FILE' "$app_build"
assert_file_not_contains 'releaseTaskRequested' "$app_build"
assert_file_not_contains 'if (baselineProfileRequested || !hasReleaseSigningConfig)' "$app_build"

assert_file_contains '"storeFile" to "LOGDATE_RELEASE_STORE_FILE"' "$signing"
assert_file_contains '"storePassword" to "LOGDATE_RELEASE_STORE_PASSWORD"' "$signing"
assert_file_contains '"keyAlias" to "LOGDATE_RELEASE_KEY_ALIAS"' "$signing"
assert_file_contains '"keyPassword" to "LOGDATE_RELEASE_KEY_PASSWORD"' "$signing"
assert_file_not_contains '"LOGDATE_RELEASE_${prop.uppercase()}"' "$signing"
assert_file_contains 'logdate.allowDebugReleaseSigning' "$signing"
assert_file_contains 'Release signing is not configured.' "$signing"

print_pass_summary "Android release signing environment"
