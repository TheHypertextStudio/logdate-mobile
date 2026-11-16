# Camera And Audio Emulator Test Matrix

This matrix lists the emulator and Gradle Managed Device runs that verify LogDate's camera,
microphone, and audio-output behavior without physical hardware. The rules these runs enforce are in
the [media quality checklist](../reference/standards/media-quality-checklist.md#camera-microphone-and-output-routes).
The device groups and the managed-device policy are in [Development](../DEVELOPMENT.md).

Use these runs for selector UI, route handoff copy, preview and state synchronization, and
background-playback notification behavior. Real USB camera, USB microphone, wired headset, and
Bluetooth peripheral behavior stays a manual check, because an emulator cannot model the device
itself.

## What the emulator can prove

- The camera selector is visible, accessible, and stable across compact and expanded layouts.
- Built-in camera switching updates the preview and state gate.
- The audio selector is visible for input and output, and system-controlled routes keep their
  settings or output-switcher action.
- Audio-note playback continues while the app is not visible, and the notification and media
  session stay synchronized with the app's playback state.
- Video stays visible in multi-window and Picture-in-Picture, and the Picture-in-Picture control is
  reachable.
- The Wear output picker shows the current route and falls back to Bluetooth settings when Wear OS
  owns routing.

The Wear remote camera is covered only by host unit tests (`WearRemoteCameraViewModelTest`,
`RemoteCameraDeviceDataMapperTest`, `RemoteCameraCaptureResultDataMapperTest`, and
`PhoneWearSyncBridgeTest`). No managed-device run exercises the watch-to-phone flow.

## What needs a person with hardware

- Preview and capture from a real external USB camera, including what happens when it is unplugged
  and which orientation it uses.
- Recording from a real USB, headset, or Bluetooth microphone, and that the recorder follows a
  microphone that is plugged in or removed while recording.
- Playback through real wired, USB, or Bluetooth output on a phone and on Wear, and through an
  external display.
- The Wear-to-phone remote camera flow on a real watch and phone, with built-in and external
  cameras.
- Tapping the media controls on the keyguard while the device is still locked.
  `AudioPlaybackBackgroundE2ETest` wakes the device, taps the visible media controls when SystemUI
  exposes them, and otherwise sends the notification actions; it does not assert a tap on the
  locked keyguard itself.

## Which test covers which surface

| Surface                                                                                                                                              | Test class                                                                            | Module and device                     |
| ---------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- | ------------------------------------- |
| Shared selector: rows, selected and in-use state, external-device state, output-switcher action                                                      | `MediaDeviceSelectorE2ETest`                                                          | `:app:android-main`, phone and tablet |
| Saved-audio block in the editor                                                                                                                      | `EditorAudioBlockE2ETest`                                                             | `:app:android-main`, phone and tablet |
| Audio note viewer and immersive screen                                                                                                               | `AudioNoteViewerE2ETest`                                                              | `:app:android-main`, phone and tablet |
| Mini-player                                                                                                                                          | `MiniAudioPlayerE2ETest`                                                              | `:app:android-main`, phone and tablet |
| Inline audio card in journal detail                                                                                                                  | `JournalInlineAudioE2ETest`                                                           | `:app:android-main`, phone and tablet |
| Timeline day-detail audio snippet                                                                                                                    | `TimelineAudioSnippetE2ETest`                                                         | `:app:android-main`, phone and tablet |
| Background playback: Home, another app, 65 seconds locked, wake, notification and media-session actions, and visible but not focused in multi-window | `AudioPlaybackBackgroundE2ETest`                                                      | `:app:android-main`, phone and tablet |
| Shared video player: Picture-in-Picture entry and multi-window visibility                                                                            | `VideoPlaybackVisibilityE2ETest`                                                      | `:app:android-main`, phone and tablet |
| Editor video block, video note viewer, library video detail with presenter controls                                                                  | `EditorVideoBlockE2ETest`, `NoteViewerVideoE2ETest`, `LibraryMediaDetailVideoE2ETest` | `:app:android-main`, phone and tablet |
| Wear day-detail output picker and Bluetooth settings fallback                                                                                        | `WearDayDetailPlaybackTest`                                                           | `:app:wear`, the Wear device          |
| Wear playback notification channel and background synchronization                                                                                    | `WearAudioPlaybackBackgroundE2ETest`                                                  | `:app:wear`, the Wear device          |
| Route resolution, fallback copy, and the selection models                                                                                            | `MediaDeviceSelectionUiStateTest`, `RecordingInputSelectionTest`                      | `:client:media`, host JVM             |

## Running them

1. Run a phone and tablet suite on the managed-device group, naming the test class with
   `logdate.androidTestClass`. A comma separates several classes.

   ```bash
   ./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest \
     -Plogdate.androidTestClass=app.logdate.client.e2e.MediaDeviceSelectorE2ETest \
     --console=plain --no-build-cache
   ```

   Run `AudioPlaybackBackgroundE2ETest`, `VideoPlaybackVisibilityE2ETest`, and the other classes
   in the table the same way. A class that holds a phone-only and a tablet-only case skips the case
   that does not apply to the device it runs on.

2. Run the Wear playback route flow on the Wear managed device. The Wear module does not read
   `logdate.androidTestClass`; select a class with the Android Gradle plugin's runner argument.

   ```bash
   ./gradlew :app:wear:wearSmallRoundApi34DebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=app.logdate.wear.e2e.WearDayDetailPlaybackTest \
     --console=plain --no-build-cache
   ```

   A Wear test that needs a suitable audio output is skipped when the managed device reports an
   unsuitable one, so a skipped background-playback case does not show that playback works.

3. Run the state tests that need no device.

   ```bash
   ./gradlew :client:media:desktopTest \
     :client:media:testAndroidHostTest \
     :app:wear:testDebugUnitTest \
     --console=plain --no-configuration-cache
   ```

4. Inspect the camera selector on an Android Emulator AVD with camera mapping enabled. This is a
   manual check.
   - Map one camera slot to the host webcam and the second to the emulator's photo and video
     source.
   - Confirm the compact selector and the expanded sheet select the front and back rows correctly.
   - Confirm the preview waits for a fresh transition to streaming after a switch, so a stale
     frame is never shown as the new camera.

5. Inspect output routing on the Wear OS emulator with Bluetooth audio emulation. This is a manual
   check.
   - Confirm the output picker shows the current route and the Bluetooth settings action.
   - Confirm the watch screen stays readable when Wear OS owns routing or no output is available.

## Keeping the background-playback test reliable

`AudioPlaybackBackgroundE2ETest` plays a generated silent clip while the test backgrounds the app,
locks the device for 65 seconds, and wakes it. The clip has to outlast that whole timeline, including
the slower notification-shade search on the tablet, or playback ends and the notification's action
flips to Play before the test asserts Pause. The clip is 300 seconds; lengthen it when the test
grows a longer wait.
