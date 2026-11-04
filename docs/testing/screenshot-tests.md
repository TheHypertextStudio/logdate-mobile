# Screenshot Testing Guide

Screenshot tests compare rendered Compose previews against baseline images to detect unintended visual changes. LogDate uses Google's Compose Preview Screenshot Testing plugin for automated visual regression testing.

## Overview

Screenshot testing captures the visual appearance of your Compose previews and compares them against baseline images. Any visual changes trigger test failures, helping catch unintended UI changes.

**Technology**: Google Compose Preview Screenshot Testing (com.android.compose.screenshot)
**Requirements**: Android Gradle Plugin 8.5.0+, Kotlin 1.9.20+
**Workflows**: Light/dark themes, multiple screen sizes

## Creating Screenshot Tests

### Basic Preview Test

Screenshot tests are Compose previews marked with `@PreviewTest` annotation. Place them in `src/screenshotTest/kotlin/`:

```kotlin
// app/android-main/src/screenshotTest/kotlin/app/logdate/screenshots/OnboardingScreenshots.kt
package app.logdate.screenshots

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.android.tools.screenshot.PreviewTest
import app.logdate.feature.onboarding.ui.OnboardingStartScreen
import app.logdate.ui.theme.LogDateTheme

@PreviewTest
@Preview(showBackground = true)
@Composable
fun OnboardingStart_Default() {
    LogDateTheme {
        OnboardingStartScreen(
            onNext = {},
            onStartFromBackup = {}
        )
    }
}
```

### Light and Dark Variants

Use `@PreviewLightDark` to test both themes automatically:

```kotlin
@PreviewTest
@PreviewLightDark
@Composable
fun EditorScreen_LightDark() {
    LogDateTheme {
        EditorScreenContent(
            state = EditorState.Editing(entry),
            onSave = {},
            onCancel = {}
        )
    }
}
```

This generates two baseline images: one for light theme, one for dark theme.

### Multiple Screen Sizes

Test across device sizes using `@Preview` parameters:

```kotlin
@PreviewTest
@Preview(name = "Phone", widthDp = 360, heightDp = 800)
@Composable
fun TimelineScreen_Phone() {
    LogDateTheme {
        TimelineScreenContent()
    }
}

@PreviewTest
@Preview(name = "Tablet", widthDp = 800, heightDp = 1200)
@Composable
fun TimelineScreen_Tablet() {
    LogDateTheme {
        TimelineScreenContent()
    }
}

@PreviewTest
@Preview(name = "Landscape", widthDp = 900, heightDp = 400)
@Composable
fun TimelineScreen_Landscape() {
    LogDateTheme {
        TimelineScreenContent()
    }
}
```

## Test Organization

Phone screenshot tests live in `app/android-main/src/screenshotTest/kotlin/app/logdate/screenshots/`:

```
common/                      preview matrices, device specs, ScreenshotTheme, shared fixtures
flows/flowNN_<area>/         user flows, one file per screen: FlowNN_MM_<Name>Screenshots.kt
  flow20_os_integration/     Android OS integration surfaces and the screens they open
components/<area>/           shared components
audit/adaptive/              foldable posture audits (book and tabletop)
audit/workspace/             Home workspace audits
```

Preview functions inside a flow file are numbered in display order (`S01_...`, `S02_...`).

### Preview matrices

Apply a matrix annotation instead of listing `@Preview`s by hand. They live in
`common/ScreenshotPreviewMatrix.kt`, and the device specs in `common/ScreenshotTestData.kt`.

| Annotation                       | Configurations                                                                             | Use it for                                                                             |
| -------------------------------- | ------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------- |
| `@ScreenshotPreviewMatrix`       | Phone, Phone Dark, Landscape, Tablet                                                       | Most screens                                                                           |
| `@LargeScreenAuditPreviewMatrix` | Split Medium, Tablet Landscape, Tablet Portrait, Desktop Window                            | Large-screen regressions                                                               |
| `@OsIntegrationPreviewMatrix`    | Phone, Phone Dark, Compact Phone, Landscape, Tablet, RTL, Large Text (200%), Dynamic Color | Every Android OS integration feature (required)                                        |
| `@BubbleWindowPreview`           | Bubble (360×480dp)                                                                         | Added next to `@OsIntegrationPreviewMatrix` for screens that can open in an app bubble |

Wear tests in `app/wear/src/screenshotTest/` use `@WearScreenshotPreviewMatrix` (small round and
large round).

### Shared scenes

A screen that also renders on desktop should be a shared scene in `client/screenshot-scenes`. Define
it once, and render it from the Android preview with `SharedScreenshotScene(id)`; the desktop lane
renders every shared scene automatically. Each scene lists the viewports it must render in. OS
integration scenes use `osIntegrationVariants`, which adds a compact phone, right-to-left, and 200%
text to the standard viewports, or `osIntegrationBubbleVariants` for bubble-capable screens. See
[`client/screenshot-scenes/README.md`](../../client/screenshot-scenes/README.md).

## Android OS integration screenshots

Every feature in the Native Android integration program
([design](../feature-design/android-os-integration.md)) ships screenshots in the same commit as
its code. That includes the right-to-left, 200% text, compact, and dynamic color configurations
that CLAUDE.md requires for UI acceptance.

1. Put the scene in `flows/flow20_os_integration/`, with `@OsIntegrationPreviewMatrix` (plus
   `@BubbleWindowPreview` where it applies). If it renders on desktop too, make it a shared scene with
   `osIntegrationVariants`.
2. Record references:

   ```bash
   ./gradlew :app:android-main:updateDebugScreenshotTest --tests '*Flow20_*'
   ./gradlew :app:compose-main:updateDesktopScreenshotTest -Dlogdate.desktopScreenshots.sceneFilter=<scene-id>
   ```

3. Open every new or changed reference and check it against the intended behavior before committing.
   A render that succeeded is not the same as a render that is right.

4. Register every screenshot the feature needs in `config/screenshots/os-integration-coverage.txt`,
   then run the coverage check:

   ```bash
   ./run screenshots:check
   ```

The first OS integration scene is `S01_PrivacySystemSearchVisibility` in
`Flow20_01_SystemSearchVisibilityScreenshots.kt`. It shows the privacy setting that controls system
search and assistant access.

### Real system UI captures

Some OS integration surfaces are drawn by Android, not by LogDate: the launcher's shortcut popup, the
Quick Settings shade, notification chips, bubbles, and the lock screen. Compose screenshot tests cannot
see them, so `OsSurfaceScreenshotsTest` captures them on Gradle Managed Devices:

```bash
# Every capture, on the Android 17 phone and tablet
./run screenshots:os

# One capture on the Android 17 phone
OS_SURFACE_TEST=launcherShortcuts ./run screenshots:os flagshipPhoneApi37
```

The test drives Android with UI Automator, and pins the status bar with SystemUI demo mode. The clock
reads 9:41, the battery is full, and no notification icons show, so captures of one state stay
comparable. Text evidence, such as Android's verdict on a Live Update, is saved next to the images.
When a wait fails, the test saves what the screen showed (a screenshot and the view tree) under
`failures/` in the managed device output, which is never copied. Images are copied to
`docs/screenshots/android-os-integration/<surface>/<state>--<phone|tablet>-api<level>.png`. They are
reviewed evidence rather than pixel-compared goldens, because system UI changes slightly between
emulator images. Look at each one, then register it under the `system` lane.

### Comparison sheets

Reviewing a feature means comparing its screenshots side by side, not opening forty files one at a
time. The comparison sheet builder lays out every registered image for a feature on one labeled
image, scaled to the same height:

```bash
./run screenshots:sheet -Pfeature=privacy-system-search
```

Sheets are written to `build/reports/screenshot-sheets/<feature>.png`. Run it without `-Pfeature` to
get one sheet per feature. Generating and reviewing the sheet is part of every OS integration
feature's Definition of Done.

### The coverage registry

"Every feature ships screenshots" is only a promise until something checks it.
`config/screenshots/os-integration-coverage.txt` lists the screenshots each feature must have, one per
line as `feature|lane|target`:

```text
privacy-system-search|android|app/logdate/screenshots/flows/flow20_os_integration/Flow20_01_SystemSearchVisibilityScreenshots.kt#S01_PrivacySystemSearchVisibility
privacy-system-search|desktop|privacy-settings
quick-settings-tiles|system|docs/screenshots/android-os-integration/quick-settings-tiles/shade-active--phone-api37.png
```

| Lane      | Target                                                                | What the check requires                                                                                                                                         |
| --------- | --------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `android` | `File.kt#Function` under `app/android-main/src/screenshotTest/kotlin` | The function exists, uses `@OsIntegrationPreviewMatrix`, and has a reference for every configuration in it (plus Bubble when `@BubbleWindowPreview` is applied) |
| `desktop` | Shared scene id                                                       | References for `phone`, `phone-dark`, `compact-phone`, `phone-rtl`, and `phone-large-text`                                                                      |
| `system`  | Repository path                                                       | A real PNG captured from a managed device, or non-empty `.txt` evidence                                                                                         |
| `glance`  | Repository path                                                       | A real PNG widget reference                                                                                                                                     |

`checkOsIntegrationScreenshotCoverage` (`./run screenshots:check`) fails when:

- a registered screenshot is missing;
- a preview in `flows/flow20_os_integration/` uses the OS integration matrix but is not registered;
- any phone screenshot reference has no matching test.

That last rule keeps the reference set honest: when you rename or delete a preview, delete its
references in the same commit. The pre-push hook runs the check. The logic lives in
`build-logic/src/main/kotlin/app/logdate/ScreenshotCoverage.kt`, with tests in
`build-logic/src/test/kotlin/app/logdate/ScreenshotCoverageTest.kt`.

## Guidelines for Screenshot Tests

### What to Screenshot

Capture key user-facing screens and states:

```kotlin
// Onboarding journey
@PreviewTest @PreviewLightDark
fun OnboardingWelcome() { }

@PreviewTest @PreviewLightDark
fun OnboardingPermissions() { }

@PreviewTest @PreviewLightDark
fun OnboardingComplete() { }

// Core screens
@PreviewTest @PreviewLightDark
fun TimelineScreen() { }

@PreviewTest @PreviewLightDark
fun EditorEmpty() { }

@PreviewTest @PreviewLightDark
fun JournalDetail() { }

// Important states
@PreviewTest @PreviewLightDark
fun LoadingState() { }

@PreviewTest @PreviewLightDark
fun ErrorState() { }

@PreviewTest @PreviewLightDark
fun EmptyState() { }
```

### What NOT to Screenshot

Skip screenshots for:

- Low-level component variations (use UI tests instead)
- Implementation details
- Every single state combination (select important ones)
- Components that change frequently during development

## Running Screenshot Tests

Screenshot tests are host-side preview tests and do not use a connected Android device. For any Android runtime verification you do alongside screenshot work, target the emulator explicitly instead of relying on whichever device is attached:

```bash
export ANDROID_SERIAL=emulator-5554
adb -s emulator-5554 devices
```

### Generate Baseline Images

Create or update reference images for the first time or after intentional changes. `./run
test:screenshots:update` updates every lane at once. Updating rewrites every reference in a lane,
including ones your change did not touch, so prefer updating only your own tests with `--tests`
(for example `--tests '*HomeWorkspaceLayoutScreenshotsKt.Workspace*InDesktopWindow*'`) and review what
changed before committing:

```bash
# Update baselines — phone app
./gradlew :app:android-main:updateDebugScreenshotTest

# Update baselines — Wear OS app
./gradlew :app:wear:updateDebugScreenshotTest

# Update baselines — Desktop app
./gradlew :app:compose-main:updateDesktopScreenshotTest
```

Phone baselines: `app/android-main/src/screenshotTestDebug/reference/`
Wear baselines: `app/wear/src/screenshotTestDebug/reference/`
Desktop baselines: `app/compose-main/src/desktopTest/reference/`

**In CI/CD**: Commit baseline images to version control alongside your code.

### Validate Against Baselines

Check if current screenshots match baselines. `./run test:screenshots` validates every lane, and
keeps going past a failing lane so each one reports:

```bash
# Validate — phone app
./gradlew :app:android-main:validateDebugScreenshotTest

# Validate — Wear OS app
./gradlew :app:wear:validateDebugScreenshotTest

# Validate — Desktop app
LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS=true TZ=America/Los_Angeles ./gradlew :app:compose-main:validateDesktopScreenshotTest
```

**In CI/CD**: This command runs automatically on pull requests.

### Review Failed Screenshots

When validation fails, check the diff:

```bash
open app/android-main/build/outputs/screenshotTest-results/preview/debug/
```

Inspect:

- `rendered/` - Current screenshots
- `reference/` - Baseline screenshots
- `diffs/` - Difference highlights (if generated)

## Workflow Example

### Initial Setup

1. Write your Compose screen:

   ```kotlin
   @Composable
   fun MyNewScreen() { }
   ```

2. Create screenshot test:

   ```kotlin
   @PreviewTest
   @PreviewLightDark
   fun MyNewScreen_LightDark() {
       LogDateTheme {
           MyNewScreen()
       }
   }
   ```

3. Generate baseline:

   ```bash
   ./gradlew :app:android-main:updateDebugScreenshotTest
   ```

4. Commit baseline image to git:
   ```bash
   git add app/android-main/src/screenshotTestDebug/reference/
   ```

### During Development

1. Make UI changes

2. Run validation to check impact:

   ```bash
   ./gradlew :app:android-main:validateDebugScreenshotTest
   ```

3. If changes are intentional, update baseline:

   ```bash
   ./gradlew :app:android-main:updateDebugScreenshotTest
   ```

4. Review and commit the new baseline

### Code Review

1. Pull request includes UI changes
2. CI runs screenshot validation
3. Reviewer sees generated diff
4. If approved, baseline update is part of commit

## Managing Baseline Images

### Storing Baselines

Baseline images live in version control:

```
app/android-main/src/screenshotTestDebug/reference/
├── app/logdate/screenshots/
│   ├── EditorScreenKt/
│   │   ├── EditorEmpty_light.png
│   │   └── EditorEmpty_dark.png
│   └── TimelineScreenKt/
│       ├── Timeline_light.png
│       └── Timeline_dark.png
```

### Updating Baselines

When a screenshot fails but changes are intentional:

```bash
# Update specific screenshot
./gradlew :app:android-main:updateDebugScreenshotTest

# Commit the new baseline
git add app/android-main/src/screenshotTestDebug/reference/
git commit -m "refactor(ui): update screenshots for new design"
```

### Removing Baselines

When deleting screenshot tests:

```bash
# Remove the @PreviewTest function
# Remove the reference image
git rm app/android-main/src/screenshotTestDebug/reference/path/to/image.png
```

## Advanced Testing

### Testing Complex States

```kotlin
@PreviewTest
@PreviewLightDark
fun EditorWithSuggestionsExpanded() {
    LogDateTheme {
        EditorScreenContent(
            state = EditorState.Editing(
                entry = testEntry(),
                suggestionsVisible = true,
                suggestions = listOf("suggestion1", "suggestion2")
            ),
            onSave = {},
            onCancel = {}
        )
    }
}

@PreviewTest
@PreviewLightDark
fun TimelineWithLoadingError() {
    LogDateTheme {
        TimelineScreenContent(
            state = TimelineState.Error(
                message = "Failed to load entries",
                retryAvailable = true
            ),
            onRetry = {}
        )
    }
}
```

### Testing Different Data

```kotlin
@PreviewTest
@PreviewLightDark
fun JournalDetailWithManyEntries() {
    LogDateTheme {
        JournalDetail(
            journal = testJournal().copy(
                entries = (1..20).map { testEntry(content = "Entry $it") }
            )
        )
    }
}

@PreviewTest
@PreviewLightDark
fun JournalDetailEmpty() {
    LogDateTheme {
        JournalDetail(
            journal = testJournal().copy(entries = emptyList())
        )
    }
}
```

## Wear OS screenshots

Wear OS screenshot tests render on round watch displays instead of rectangular phone screens.
A custom multi-preview annotation targets both supported form factors:

```kotlin
// app/wear/src/screenshotTest/.../WearScreenshotPreviewMatrix.kt
@Preview(name = "Small Round", device = "id:wearos_small_round", showBackground = true)
@Preview(name = "Large Round", device = "id:wearos_large_round", showBackground = true)
annotation class WearScreenshotPreviewMatrix
```

Use `@WearScreenshotPreviewMatrix` instead of `@PreviewLightDark` for Wear OS screens:

```kotlin
@PreviewTest
@WearScreenshotPreviewMatrix
@Composable
fun S01_WalkieTalkieReady() {
    MaterialTheme {
        ReadyContent(onTouchDown = {}, onTouchUp = {})
    }
}
```

Each preview generates two baselines (small round + large round). Wrap content in Wear
`MaterialTheme` (not phone `LogDateTheme`).

```bash
# Generate Wear OS baselines
./gradlew :app:wear:updateDebugScreenshotTest

# Validate Wear OS screenshots
./gradlew :app:wear:validateDebugScreenshotTest
```

## KMP considerations

Google Compose Screenshot Testing works with Android targets only. LogDate now wraps that limitation in a repo-level universal screenshot lane:

1. **Android and Wear**: real baseline screenshot validation via `com.android.compose.screenshot`
2. **Desktop**: real committed baseline validation via `:app:compose-main:validateDesktopScreenshotTest`
3. **iOS**: simulator-side `iosSimulatorArm64Test` validation for screenshot-covered UI modules

Use the root tasks when you want the platform-agnostic entrypoint:

```bash
# Validate the full universal screenshot lane
./gradlew validateAllScreenshots

# Update every supported baseline lane
./gradlew updateAllScreenshots

# Per-platform entrypoints
./gradlew validateAndroidScreenshots
LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS=true TZ=America/Los_Angeles ./gradlew validateDesktopScreenshots
./gradlew validateIosScreenshots
```

Desktop now participates as a real baseline lane. `updateAllScreenshots` updates Android, Wear, and Desktop baselines. iOS remains validate-only until a committed iOS screenshot baseline renderer lands.

```kotlin
// This only runs on Android
@PreviewTest
@PreviewLightDark
fun ComposablePreview() {
    // Android screenshot test
}
```

## CI/CD Integration

`.github/workflows/screenshot-test.yml` validates screenshots on pushes to `main`, and on pull requests
that touch app, feature, UI, theme, or build code. It runs three jobs:

| Job                         | Runner                                                                                          | Command                                                          |
| --------------------------- | ----------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `android-wear-screenshots`  | macOS with JetBrains JDK 21 (phone and Wear, matching the committed references' rendering host) | `./gradlew :app:<module>:validateDebugScreenshotTest --continue` |
| `desktop-screenshots`       | macOS                                                                                           | `./gradlew validateDesktopScreenshots --continue`                |
| `ios-screenshot-validation` | macOS                                                                                           | `./gradlew validateIosScreenshots --continue`                    |

Android rendering results, XML, and HTML diagnostics upload even when validation fails; the runner change does not relax image thresholds or regenerate baselines. Each job uploads its results (rendered images and diffs) as a build artifact. When a job fails,
download the artifact and compare the `diffs/` images with the references.

Android and Wear validation use `TZ=America/Los_Angeles` and
`JAVA_TOOL_OPTIONS=-Duser.timezone=America/Los_Angeles`, matching the committed references' local
date/time labels. Use the same environment when validating or recording their references locally.
The validation step has a 40-minute timeout inside a 45-minute job, leaving time to upload rendered
images, diffs, and XML/HTML reports even when validation times out.

## Troubleshooting

### "Expected images not found"

Baseline images missing. Generate them:

```bash
# Phone
./gradlew :app:android-main:updateDebugScreenshotTest
git add app/android-main/src/screenshotTestDebug/reference/

# Wear OS
./gradlew :app:wear:updateDebugScreenshotTest
git add app/wear/src/screenshotTestDebug/reference/

# Desktop
./gradlew :app:compose-main:updateDesktopScreenshotTest
git add app/compose-main/src/desktopTest/reference/
```

### "Images do not match"

Visual changes detected. Options:

1. If changes are unintended: Fix the UI code
2. If changes are intentional: Update baseline
   ```bash
   ./gradlew :app:android-main:updateDebugScreenshotTest  # phone
   ./gradlew :app:wear:updateDebugScreenshotTest           # wear
   ./gradlew :app:compose-main:updateDesktopScreenshotTest # desktop
   ```

### Screenshot filename confusion

Filenames are auto-generated from:

- Package: `app.logdate.screenshots`
- Class: `EditorScreenshotsKt`
- Function: `EditorEmpty`
- Theme: `_light.png` or `_dark.png`

Example:

```
app/logdate/screenshots/EditorScreenshotsKt/EditorEmpty_light.png
app/logdate/screenshots/EditorScreenshotsKt/EditorEmpty_dark.png
```

## Best Practices

1. **Screenshot Critical Journeys Only**: Focus on key screens
2. **Use Light/Dark Variants**: Always test both themes
3. **Keep Baselines in Git**: Version control your visual expectations
4. **Review Diffs in PR**: Let reviewers see what changed
5. **Document State**: Include comments for non-obvious test setup
6. **Update Regularly**: Keep baselines current during development
7. **Combine with UI Tests**: Screenshots for appearance, UI tests for behavior

## Resources

- [Compose Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)
- [Google Issue #402137754](https://issuetracker.google.com/issues/402137754) - KMP compatibility
- [Visual Regression Testing Best Practices](https://www.smashingmagazine.com/2021/06/visual-regression-testing/)

## Desktop Screenshot Operations

Desktop screenshot validation for `app/compose-main` uses hidden offscreen Swing surfaces. Set `LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS=true` to run real rendering and comparisons through the desktop test task. Regular desktop unit tests stay headless. Required screenshot validation fails when a display surface or a matching reference host is unavailable; the CI runner matches `render-host.txt`.

Core tasks:

```bash
# Validate committed desktop baselines
LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS=true TZ=America/Los_Angeles ./gradlew :app:compose-main:desktopTest \
  --tests '*shared catalog matches baselines*'

# Convenience alias for the same validation lane
LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS=true TZ=America/Los_Angeles ./gradlew :app:compose-main:validateDesktopScreenshotTest

# Refresh committed desktop baselines
./gradlew :app:compose-main:updateDesktopScreenshotTest
```

Default behavior:

- Desktop screenshot tests now run in hidden mode by default.
- The harness creates a displayable off-screen surface without forcing a visible foreground window.
- Hidden mode avoids stealing focus from the current desktop window.
- Hidden mode also avoids hover and focus contamination from the mouse pointer during screenshot capture.

Scene filtering:

Use `logdate.desktopScreenshots.sceneFilter` to run only a subset of the shared screenshot catalog. The filter matches `SharedScreenshotSceneId.value` substrings.

```bash
# Run only onboarding-start scenes
LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS=true TZ=America/Los_Angeles ./gradlew -Dlogdate.desktopScreenshots.sceneFilter=onboarding-start \
  :app:compose-main:desktopTest \
  --tests '*shared catalog matches baselines*'

```

Desktop baseline locations:

- References: `app/compose-main/src/desktopTest/reference`
- Actual renders: `app/compose-main/build/reports/desktopScreenshotTest/actual`
- Pixel diffs: `app/compose-main/build/reports/desktopScreenshotTest/diff`

Recommended workflow for desktop screenshot changes:

1. Narrow the scope with `logdate.desktopScreenshots.sceneFilter` while iterating on a scene.
2. Run hidden mode by default to avoid desktop interference.
3. Inspect the rendered content, wrapping and actions before accepting changed references.
4. Refresh baselines with `:app:compose-main:updateDesktopScreenshotTest` after the render path is deterministic.
5. Run `LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS=true TZ=America/Los_Angeles ./gradlew :app:compose-main:desktopTest --tests '*shared catalog matches baselines*'` before committing.

## Native backup settings fixtures

The shared catalog includes signed-out, signed-in and native-quota states in
`NativeBackupScreenshotScenes.kt`. Each explicitly enables Android coverage explanations
and uses the OS integration viewport matrix. Follow the scoped commands and review
criteria in [Settings UI composition](../reference/standards/settings-ui.md).

## Populated Rewind media fixtures

Supply predecoded fixture painters through `LocalRewindPreviewPainters`, keyed by
exact media URI. `ImageNotePanel` uses these only in inspection mode, so populated
previews render their actual fixture image without waiting for an asynchronous
readiness callback. Loading and unavailable fixtures omit the matching painter.
Runtime media continues to use the real image loader and its readiness callbacks.
