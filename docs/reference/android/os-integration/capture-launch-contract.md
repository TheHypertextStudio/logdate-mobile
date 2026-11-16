# Capture Launch Contract

Status: shipped. Launcher shortcuts, tiles, notifications, and assistants adopt it as each of those surfaces lands.

## What it is

Many surfaces need to say "open the editor ready to record", or "open the editor with the camera
on": launcher shortcuts, Quick Settings tiles, notification actions, assistants, keyboard shortcuts,
and other apps. The capture launch contract is the single way they all say it.

It has two parts:

- **`EditorLaunchMode`**, a field on the editor's route that says what the editor prepares when it
  opens.
- **`app.logdate.action.CAPTURE`**, a public intent action (and matching `logdate://capture` deep
  link) that any surface can send.

A shared contract keeps every surface consistent. It also gives tests one resolver to cover, and
lets a new surface start capture without inventing new intent extras.

## What the person sees

Whichever surface they use, the editor opens already doing the thing they asked for:

| Mode     | The editor opens with                                     |
| -------- | --------------------------------------------------------- |
| `Text`   | An empty text block and the keyboard up                   |
| `Voice`  | An audio block, already recording                         |
| `Camera` | A camera block showing the live preview, ready for photos |
| `Video`  | A camera block showing the live preview, ready for video  |

A route can also carry a `promptPhotoUri`: the New entry widget's photo prompts start the entry with
that photo, followed by text, or by a recording in `Voice` mode.

Screenshots of each mode are added here when the contract ships.

## How it works

### The route

`EntryEditorRoute` in
`client/feature/editor/src/commonMain/kotlin/app/logdate/feature/editor/navigation/EditorNavRoute.kt`
carries `launchMode: EditorLaunchMode` and an optional `promptPhotoUri`. When a new entry first
composes, `NoteEditorScreen` calls `EntryEditorViewModel.initializeLaunchMode` (or
`initializePhotoPrompt` for a photo prompt). The view model adds the matching block once. A camera
block opened in `Video` mode carries `initialCaptureMode = VIDEO`, which the camera applies before
the first frame. An audio block opened in `Voice` mode carries `startOnOpen = true`: as soon as the
microphone is allowed, `AudioBlockEditor` clears the flag and starts recording, so the person who
tapped Record voice does not have to tap Record again.

Before the contract, the route carried three widget-specific fields: `widgetRecord`, `widgetCamera`,
and `widgetPhotoUri`. The launch mode replaced them. Saved back stacks that still hold the old
fields restore as a plain new entry, because unknown saved fields are ignored and both new fields
have defaults.

### The intent

```text
action:  app.logdate.action.CAPTURE
extras:  mode        text | voice | camera | video    optional, defaults to text
         journal_id  journal UUID                     optional, pre-selects that journal
```

A capture intent from another app always opens an empty entry. Starting text would let an app, or
an assistant, put words in the person's journal for them to accept, so `MainActivity` ignores a
`text` extra on the public action. LogDate's own components can still start an entry with text the
person wrote, such as a notification reply that could not be saved: they launch
`app.logdate.client.InternalCaptureActivity`, a non-exported alias of `MainActivity` that no other
app can reach (`internalCaptureIntent` in `client/capture/InternalCapture.kt`).

The equivalent deep links are:

```text
logdate://capture/voice?journal=<uuid>
logdate://capture?mode=voice&journal=<uuid>
```

The constants and the parsing live in `app/compose-main/src/commonMain/kotlin/app/logdate/client/capture/CaptureLaunch.kt`
(`ACTION_CAPTURE`, `captureRoute`, `widgetCaptureRoute`). `resolveMainActivityNavKey` in
`app/compose-main/src/androidMain/kotlin/app/logdate/client/DeepLinkResolver.kt` checks the capture
action before anything else. An unknown `mode` falls back to `Text` rather than failing, so a newer
shortcut cannot break an older app. An invalid journal id is ignored for the same reason.

### Legacy extras that keep working

Widgets that are already on a home screen and shortcuts that are already pinned carry the old extras.
They must keep resolving:

| Old intent                                                    | Resolves to                                                              |
| ------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `EXTRA_NAV_SOURCE=widget_new_entry` with `widget_record=true` | `Voice`                                                                  |
| `EXTRA_NAV_SOURCE=widget_new_entry` with `widget_camera=true` | `Camera`                                                                 |
| `EXTRA_NAV_SOURCE=widget_new_entry` with `widget_photo_uri`   | `Text` with that `promptPhotoUri` (or `Voice` with `widget_record=true`) |
| `ambient_prompt_target=new_entry`                             | `Text`                                                                   |

### The lock

Capture never skips the app lock. `LogDateNavDisplay` adds the editor route to the back stack only
after onboarding is complete and the app is unlocked.

## Flag and rollout

None. The contract is plumbing that existing surfaces move onto. It changes no visible behavior on
its own.

## Privacy

A capture intent can only create. It never returns journal data to the caller.

## How to test

- `CaptureLaunchTest` (compose-main `commonTest`) covers every mode, case and spacing, an unknown
  mode, valid and invalid journal ids, and every legacy widget extra.
- The editor's `TextEditingTest` checks that each launch mode adds exactly one capture block, and
  that video opens the camera ready for video.
- `DeepLinkResolverTest` runs the same cases on a managed device:

  ```bash
  ./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest -Plogdate.androidTestClass=app.logdate.client.DeepLinkResolverTest
  ```

- On an emulator:

  ```bash
  adb -s emulator-5554 shell am start -a app.logdate.action.CAPTURE --es mode voice
  ```

## Updating screenshots

There is one Lane 1 editor scene per launch mode in the `flow2x_os_integration` screenshot flow.
Record them with `./run test:screenshots:update`, then check them with
`./run screenshots:check` and `./run screenshots:sheet capture-launch-contract`.
