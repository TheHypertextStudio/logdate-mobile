# `:client:feature:android-widgets`

This module is LogDate's integration with the Android launcher: four Jetpack Glance home screen widgets, the setup screens that configure them, and the model that decides which dynamic launcher shortcuts and Direct Share targets the app publishes. `:app:compose-main` depends on it, includes `widgetModule` in its Android Koin setup, and runs `DynamicShortcutPublisher` from a refresh worker. The Android instrumented tests in `:app:android-main` also depend on it.

For how these surfaces fit into the rest of Android, see the [OS integration guide](../../../docs/reference/android/os-integration.md), the [widgets page](../../../docs/reference/android/os-integration/widgets.md) and the [launcher shortcuts page](../../../docs/reference/android/os-integration/launcher-shortcuts.md).

## What it contains

Everything is in the package `app.logdate.client.feature.widgets`. The code that touches `AppWidgetManager`, activities or `RemoteViews` is in `androidMain`. The plain data and selection logic is in `commonMain`, so it can be tested without Android.

### Widgets

| Widget                                  | Receiver                    | Shows                                                                          | Setup screen                                                                                    |
| --------------------------------------- | --------------------------- | ------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------- |
| `OnThisDayWidget` (the Memories widget) | `OnThisDayWidgetReceiver`   | A memory recalled for today, or an empty or new-user message                   | `OnThisDayWidgetConfigActivity`: recall mode and content types                                  |
| `FixedMemoryWidget` (pinned memory)     | `FixedMemoryWidgetReceiver` | One memory the person chose, with an audio play button for audio memories      | `FixedMemoryWidgetConfigActivity`: pick a note                                                  |
| `NewEntryWidget`                        | `NewEntryWidgetReceiver`    | A recent photo as a prompt, with Write, Record and Camera actions              | `NewEntryWidgetConfigActivity`: photo prompt on or off and the photo shape (`PhotoWidgetShape`) |
| `CaptureWidget`                         | `CaptureWidgetReceiver`     | Write, Record and Photo buttons (`CaptureWidgetButton`) and no journal content | None                                                                                            |

All four declare `updatePeriodMillis="0"` in `res/xml/*_widget_info.xml`. The first three are home screen widgets sized 3 by 2 cells. `capture_widget_info.xml` sets the category to `home_screen|keyguard` with a 3 by 1 cell target, because it never shows journal content. The manifest declares `CaptureWidgetReceiver` as disabled, and the app enables it while the `LOCK_SCREEN_CAPTURE_WIDGET` feature flag is on.

The three content widgets render an `OnThisDayWidgetState` (in `commonMain`) through `OnThisDayWidgetContent`. The state is a sealed interface with `Loading`, `NewUser`, `NoMemoryToday`, `HasMemory`, `FixedMemory`, `ChooseMemory`, `MissingMemory`, `PhotoPrompt`, `PhotoUnavailable` and `NewEntryReady`. `OnThisDayWidgetStateDefinition` stores it as JSON per widget, and `OnThisDayWidgetRefreshWorker` writes it, so composition never calls a use case.

Taps launch the app's launcher activity with the extras from `WidgetNavConstants.kt` (`EXTRA_NAV_SOURCE`, `EXTRA_WIDGET_TARGET_DATE`, `EXTRA_WIDGET_NOTE_ID`, `EXTRA_WIDGET_PHOTO_URI`, `EXTRA_WIDGET_RECORD`, `EXTRA_WIDGET_CAMERA`), and `resolveMainActivityNavKey` in `:app:compose-main` turns them into a route. The capture widget sends the `CAPTURE_WIDGET_ACTION` intent with a `mode` extra instead. Its KDoc explains that the constants are repeated here because this module cannot depend on the app.

### Refresh and per-widget settings

`OnThisDayWidgetRefreshWorker` refreshes the Memories, pinned memory and new entry widgets independently, so one failing source does not block the others, and retries if any failed. `WidgetRefreshScheduler.kt` enqueues an immediate unique job and a 24-hour periodic job whose first run is at 5:00 AM local time. `publishWidgetPreviews` in `WidgetPreviewPublisher.kt` publishes generated picker previews on Android 15 and later, at most once every six hours for each receiver and app version.

`WidgetInstanceSettings` stores choices per placed widget ID in shared preferences. `WidgetSetupRequest` is the validated, serializable description of one setup save, and `SecureWidgetConfigActivity` is the base class that persists it and retries a failed save.

### Widget lifecycle delivery

The widget receivers extend `DurableWidgetReceiver`. A receiver runs in the `:widget_ingress` process and records each broadcast (update, delete, restore, options change, locale change) as a `WidgetLifecycleEvent` in `WidgetLifecycleInbox`, a file-backed inbox under the app's no-backup directory. `WidgetLifecycleJobService`, scheduled through `JobScheduler`, drains the inbox, refreshes widget data and hands each event back to the receiver. `WidgetLifecycleRestartReceiver` resumes this after boot and after an app update. `WidgetRestoreRepair`, with the `AndroidWidgetRestoreStateStore` and `AndroidWidgetPreferencesStore` classes in `AndroidWidgetRestoreStores.kt`, remaps per-widget settings when Android restores widgets under new IDs.

### Privacy

`requireWidgetContentAccess` in `WidgetPrivacyGate.kt` applies the app lock to widget setup and audio playback:

| App lock                                | Result                           |
| --------------------------------------- | -------------------------------- |
| None                                    | Allowed                          |
| Biometric                               | Allowed after a biometric prompt |
| Password, or the setting cannot be read | Refused                          |

`SecureWidgetConfigActivity` also sets `FLAG_SECURE` on its window. `WidgetAudioPlaybackActivity` plays audio memories from a widget.

### Dynamic shortcuts

`shortcuts/DynamicShortcutDescriptor` is a sealed interface with four kinds: `ContinueDraft`, `TodayTimeline`, `WeekRewind` and `ShareToJournal`. `DynamicShortcutPublisher.computeShortcuts()` returns the launcher descriptors followed by the sharing descriptors:

- Launcher shortcuts are limited to `DEFAULT_MAX_SHORTCUTS`, which is the four shortcuts launchers show minus the three static capture shortcuts, leaving one. They are considered in the order continue draft, today's timeline, last week's rewind. A draft counts only if it has notes and was updated within `DEFAULT_DRAFT_FRESHNESS` (seven days).
- Share targets are one `ShareToJournal` per journal with a non-blank title, favorites first and then by most recent update.

The publisher takes its data as function parameters, so tests can fake it. `androidShortcutsModule` in `:app:compose-main` supplies the real use cases. Shortcut strings and icons are `R.string.dynamic_shortcut_*` and `R.drawable.ic_shortcut_*` resources in this module.

## How it fits with other modules

`commonMain` depends on `:client:domain`, `:client:media`, `:client:repository` and `:shared:model`. `androidMain` adds `:client:device`, `:client:feature:core`, `:client:theme` and `:client:ui`, plus Glance (`glance-appwidget`, `glance-material3`), WorkManager, `androidx.biometric` and Koin with its WorkManager integration. `widgetModule` in `di/WidgetModule.kt` provides `AndroidMemoriesWidgetInstallController`, which implements `MemoriesWidgetInstallController` from `:client:feature:core` to request launcher pinning, and registers `OnThisDayWidgetRefreshWorker`.

## Platforms

Android and the JVM. The JVM target has no sources of its own and exists so `commonMain` compiles off Android.

## Running the tests

`commonTest` covers state mapping and serialization, the content models and the shortcut publisher. `androidHostTest` runs Robolectric tests for the lifecycle inbox, queue, custody and completion, job scheduling, restore repair, setup requests and recovery, and source preferences.

```bash
./gradlew :client:feature:android-widgets:jvmTest
./gradlew :client:feature:android-widgets:testAndroidHostTest
```

The instrumented tests are in `app/android-main/src/androidTest` and run on Gradle Managed Devices: `OnThisDayWidgetPreviewTest` renders each widget through Glance's `composeForPreview`, and `WidgetSetupScreenshotsTest`, `WidgetInstanceSettingsTest`, `WidgetPhotoPermissionFlowTest` and `WidgetAudioPlaybackTest` cover setup and playback.

```bash
./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest -Plogdate.androidTestClass=app.logdate.client.e2e.OnThisDayWidgetPreviewTest
```

`OsSurfaceScreenshotsTest#widgetRenders` produces the launcher-style renders committed under [`docs/screenshots/android-os-integration/widgets/`](../../../docs/screenshots/android-os-integration/widgets/). It runs on a device because RemoteViews need Android's own view inflation to draw faithfully:

```bash
OS_SURFACE_TEST=widgetRenders scripts/os-surface-screenshots.sh flagshipPhoneApi37
```
