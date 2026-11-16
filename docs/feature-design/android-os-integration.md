# Android OS Integration

This document is the source of truth for how LogDate integrates with the Android operating system
outside its own screens. Those places include the launcher, Quick Settings, the notification shade,
the status bar, the lock screen, bubbles, keyboards and pointers, desktop windows, and on-device
assistants.

It records what LogDate does on each surface, why, which rules protect the user's journal, and how
each surface is rolled out. The how-to guides for each surface live in
[`docs/reference/android/os-integration.md`](../reference/android/os-integration.md), and each one
records the tests and captures that verify its surface.

## Why this matters

A journal is only as useful as the moment a thought can be captured. Most of those moments happen
while the person is doing something else: walking, reading, working at a laptop, looking at the lock
screen. Every second spent finding the app and the right screen is a second in which the thought can
disappear.

Android offers many surfaces that sit one gesture away from wherever the person already is. Using them
well turns "open LogDate, wait, tap New, tap the microphone" into "long-press the icon, tap Record
voice".

Two platform changes make this work urgent, not optional:

- **Android 17 (API 37)** removes the developer opt-out for orientation and resizability restrictions
  on large screens for apps that target it. LogDate already targets 37. The app must work at any
  window size.
- **Android 17 App Bubbles** let people turn any app into a floating window from the launcher. LogDate
  can be bubbled whether or not it is ready for it.

## Goals

1. Capture a text, voice, photo, or video entry from every relevant OS surface in one gesture.
2. Show ongoing work (recording, export, restore, sync) where Android shows ongoing work: in the status
   bar and on the lock screen.
3. Behave like a desktop-class app on tablets, Chromebooks, and Android desktop windowing: keyboard
   shortcuts, right-click, drag and drop between apps, titled windows, and caption-bar integration.
4. Let on-device assistants create entries, and let them read the journal only with explicit consent.
5. Ship every surface with tests, committed screenshots, and documentation in the same change.

## Non-goals

- A general "capture anything" notification bubble. Bubbles are reserved for the voice recorder.
- Journal content on the lock screen. Lock-screen surfaces capture; they never reveal.
- Per-app language settings. These wait until LogDate ships a second locale.
- Web Handoff fallback. This waits until logdate.app can rebuild app state from a URL.

## Decisions

| Decision              | Choice                                                                                                  | Reason                                                                                                          |
| --------------------- | ------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Assistant read access | Opt-in through the existing system-search visibility setting, and refused while the app lock is engaged | The journal is private. Writing an entry is low risk; reading one is not.                                       |
| Bubbles               | App Bubble readiness for the whole app, plus a dedicated recording bubble                               | Recording while using another app is a core LogDate moment. Bubbling the general editor duplicates App Bubbles. |
| Rollout               | Platform hygiene ships directly; each new surface has its own feature flag that is off by default       | Every commit lands on `main`, so unfinished surfaces must be invisible. Fixes should not wait.                  |
| Launch contract       | One public capture action and one editor launch mode for every surface                                  | Each surface would otherwise invent its own intent extras, and routing would drift.                             |
| Screenshots           | Mandatory in the same commit as the change, enforced by a coverage check                                | OS surfaces are visual. A surface without screenshots cannot be reviewed.                                       |

## The capture launch contract

Every surface that starts an entry goes through one contract. The contract has two halves.

**The editor launch mode.** `EntryEditorRoute` carries a `launchMode: EditorLaunchMode` that says
what the editor should prepare when it opens:

| Mode               | What the editor prepares                                |
| ------------------ | ------------------------------------------------------- |
| `Text`             | An empty text block with the keyboard up                |
| `Voice`            | An audio block that starts recording                    |
| `Camera`           | A camera block with the live preview                    |
| `Video`            | A camera block in video mode                            |
| `PhotoPrompt(uri)` | A photo block with the given image and a writing prompt |

This replaces the widget-only route flags `widgetRecord`, `widgetCamera`, and `widgetPhotoUri`.

**The public capture intent.** Other surfaces and other apps start capture with:

```text
action: app.logdate.action.CAPTURE
extras: mode = text | voice | camera | video     (optional, defaults to text)
        journal_id = <journal uuid>               (optional, pre-selects a journal)
```

or with the deep link `logdate://capture?mode=voice&journal=<uuid>`.

`resolveMainActivityNavKey` in
`app/compose-main/src/androidMain/kotlin/app/logdate/client/DeepLinkResolver.kt` checks the capture
action first and turns it into an `EntryEditorRoute`. The older widget extras
(`EXTRA_NAV_SOURCE=widget_new_entry` with `widget_record` / `widget_camera`) and ambient extras
(`ambient_prompt_target=new_entry`) keep resolving. Widgets that are already placed and shortcuts
that are already pinned must keep working after an update.

Capture still passes the app's lock gate. `LogDateNavDisplay` only adds the pending route to the back
stack once the person is onboarded and the app is unlocked.

## Surfaces

Each row links to its guide.

| Surface                             | What the person can do                                                                                                                       | Flag                                            | Guide                                                                                                             |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Launcher shortcuts                  | Long-press the icon for New entry, Record voice, Take photo, Continue draft, Today, and last week's Rewind. Pin journals to the home screen. | none                                            | [launcher-shortcuts](../reference/android/os-integration/launcher-shortcuts.md)                                   |
| Direct Share                        | Share text, photos, or video straight into a specific journal from the share sheet                                                           | none                                            | [launcher-shortcuts](../reference/android/os-integration/launcher-shortcuts.md)                                   |
| Quick Settings tiles                | Start an entry or a voice recording from the shade, and see when a recording is running                                                      | `QUICK_SETTINGS_TILES`                          | [quick-settings-tiles](../reference/android/os-integration/quick-settings-tiles.md)                               |
| Live Updates                        | See recording time and export, restore, and sync progress in the status bar and on the lock screen                                           | `LIVE_UPDATES`                                  | [live-updates](../reference/android/os-integration/live-updates.md)                                               |
| Notification quick reply            | Write an entry straight from a capture nudge, or start recording from it                                                                     | `NOTIFICATION_QUICK_REPLY`                      | [notification-quick-reply](../reference/android/os-integration/notification-quick-reply.md)                       |
| Widgets                             | Home-screen New entry, On This Day, and pinned memory widgets, plus a capture-only lock-screen and hub widget                                | `LOCK_SCREEN_CAPTURE_WIDGET` (lock screen only) | [widgets](../reference/android/os-integration/widgets.md)                                                         |
| App Bubbles                         | Use LogDate as a floating window after bubbling it from the launcher                                                                         | none                                            | [bubbles](../reference/android/os-integration/bubbles.md)                                                         |
| Recording bubble                    | Keep a voice recording running in a floating recorder while using other apps                                                                 | `RECORDING_BUBBLE`                              | [bubbles](../reference/android/os-integration/bubbles.md)                                                         |
| Keyboard, pointer, drag and drop    | Keyboard shortcuts, right-click menus, tooltips, and dragging text and media in and out                                                      | none                                            | [keyboard-pointer-drag-and-drop](../reference/android/os-integration/keyboard-pointer-drag-and-drop.md)           |
| Large screens and desktop windowing | Titled editor windows, side-by-side windows, and an app bar that sits in the desktop caption bar                                             | none                                            | [large-screens-and-desktop-windowing](../reference/android/os-integration/large-screens-and-desktop-windowing.md) |
| AppFunctions                        | Ask an assistant to add an entry or start a voice note, and, with consent, to search the journal                                             | `APP_FUNCTIONS`                                 | [app-functions](../reference/android/os-integration/app-functions.md)                                             |
| Editor Handoff                      | Pick up an unfinished entry on another Android device; it opens once backup delivers its draft                                               | `EDITOR_HANDOFF`                                | [app-functions](../reference/android/os-integration/app-functions.md#cross-device-handoff)                        |

## Privacy rules

These rules apply to every surface. A surface that cannot meet them does not ship.

1. **Lock-screen surfaces never show journal content.** The lock-screen widget, notification replies
   sent from the lock screen, and Live Updates on the lock screen show actions and status only. They
   never show entry text, photos, places, or people.
2. **Capture never bypasses the app lock for reading.** A surface may start a new entry without
   unlocking. Opening the rest of the journal still requires the configured app lock.
3. **Assistants read only with consent.** Read functions check the system-search visibility setting
   (`LogdatePreferencesDataSource.observeSystemSearchVisibilityEnabled`) and the app lock state on
   every call. Either check failing returns a specific refusal, never partial data.
4. **Errors stay specific.** A failed import, reply, or recording shows what failed and what to do
   next, never a silent drop or a generic message.
5. **UI copy never speaks as "we".** Notifications and settings state what happened or what failed.

## Feature flags

New surfaces are invisible until their flag is on. Flags live in
`client/datastore/src/commonMain/kotlin/app/logdate/client/datastore/featureflags/FeatureFlag.kt`.

| Flag                         | Default | What it hides                                                                                    |
| ---------------------------- | ------- | ------------------------------------------------------------------------------------------------ |
| `QUICK_SETTINGS_TILES`       | off     | Both tile services, through their manifest components                                            |
| `LIVE_UPDATES`               | off     | Promotion of recording, export, restore, and sync notifications                                  |
| `NOTIFICATION_QUICK_REPLY`   | off     | The Write and Record actions on capture nudges                                                   |
| `LOCK_SCREEN_CAPTURE_WIDGET` | off     | The keyguard widget receiver                                                                     |
| `RECORDING_BUBBLE`           | off     | The Float action and the recording bubble activity                                               |
| `APP_FUNCTIONS`              | off     | Registration of LogDate's AppFunctions                                                           |
| `EDITOR_HANDOFF`             | off     | Offering editor windows to other devices. An editor launched by a handoff still opens its draft. |

Some surfaces are declared in the manifest (tiles, widget receivers, the bubble activity), so a
runtime check alone cannot hide them. These components are declared `android:enabled="false"`.
`PlatformComponentToggler` turns each one on or off with `PackageManager.setComponentEnabledSetting`
whenever its flag changes.

## Screenshot and documentation requirements

Every feature task in this program ships, in the same commit:

1. Tests, written first.
2. **Lane 1 screenshots.** These are Compose and Glance references in the
   `@OsIntegrationPreviewMatrix` configurations: phone, phone dark, compact phone, landscape, tablet,
   right-to-left, 200% text, dynamic color, and bubble size where it applies.
3. **Lane 2 screenshots.** These are real system UI captured on a Gradle Managed Device and committed
   under `docs/screenshots/android-os-integration/`.
4. Registry rows in `config/screenshots/os-integration-coverage.txt`, with
   `checkOsIntegrationScreenshotCoverage` passing.
5. A reviewed comparison sheet. Every changed golden image is explained by the intended behavior.
6. An updated surface guide with embedded screenshots and the evidence described in
   [what counts as verified](../testing/screenshot-tests.md#what-counts-as-verified), the module
   README, and KDoc.

[`docs/testing/screenshot-tests.md`](../testing/screenshot-tests.md) explains the two lanes and the
coverage check.

## Rollout

Platform hygiene (the manifest attributes, the capture launch contract, window titles, and the share
receiver) ships directly, because it fixes behavior that is wrong on every build. Each new surface
ships behind its own flag, which is off by default, so a surface that is still being verified is
invisible to users. The flags are listed above.

While a surface is being verified, its flag can be turned on in Settings > Advanced > Developer tools

> Feature previews, in any build. A flag is ready to turn on for everyone when its guide records the
> tests and captures that verify the surface. When a surface ships to everyone, its flag is deleted,
> and the manifest `android:enabled="false"` and the `PlatformComponentToggler` entry go with it in the
> same change.

The editor handoff between two devices cannot be exercised on managed emulators. It needs two real
devices signed in to the same Google account and LogDate account, with the draft delivered through
backup.

## Platform constraints

These constraints shaped the design, and each holds in the current code.

| Constraint                                                                                | How LogDate handles it                                                                                                                                                                                                                                                                                                          |
| ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android may refuse to show a non-conversation notification as a bubble                    | The recorder bubble is a separate `MessagingStyle` notification linked to a long-lived shortcut, because Android only bubbles conversations. It floats on Android 16 and 17 phones and lands in the Bubble Bar on an Android 17 tablet. Where bubbles are not allowed, the same notification offers the system's bubble button. |
| Starting the microphone foreground service from a tile can violate background-start rules | The Record voice tile does not start the microphone. It opens the editor in voice mode through the capture action, and the editor starts recording in the foreground once the microphone is allowed and the window has focus.                                                                                                   |
| Gemini's AppFunctions integration is in private preview                                   | Functions are verified through Android's own registry with `cmd app_function`.                                                                                                                                                                                                                                                  |
| System UI renders slightly differently between emulator images                            | Captures use SystemUI demo mode and pinned managed-device images, and are reviewed evidence rather than pixel-diff gates.                                                                                                                                                                                                       |
| A managed device cannot show desktop windowing                                            | `./run screenshots:desktop` runs the desktop-window captures on a separate Android 17 tablet emulator with desktop windowing turned on.                                                                                                                                                                                         |
