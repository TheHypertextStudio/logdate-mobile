# Audio Recording on Wear OS

Voice capture is the primary input on the watch. The home screen is the recorder: one large button,
two ways to use it, and nothing between pressing it and being recorded.

The watch app contains two recorder screens, because it contains two experiences: the one that
opens by default, and a redesign behind the `WATCH_EXPERIENCE_V2` feature flag (see the
[`:app:wear` README](../../app/wear/README.md#two-experiences-and-the-switch-between-them)). The
next two sections describe the default recorder.
[The recorder in the new watch experience](#the-recorder-in-the-new-watch-experience) describes the
other. Everything from [Recording infrastructure](#recording-infrastructure) onward, including the
audio format, the file lifecycle and recovery, is shared by both.

## Using the recorder

| You do                                                        | What happens                                                                                                                                |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Tap the button                                                | Recording starts and keeps going after you lift your finger. Tap again to stop and save. Pause and Discard sit on either side while it runs |
| Press and hold                                                | Recording starts at once and saves when you let go, like a walkie-talkie                                                                    |
| Stop                                                          | The note is saved immediately. For five seconds an **Undo** button removes it and its audio file                                            |
| Record again during the Undo window                           | The previous note stays saved and the window closes                                                                                         |
| Close the app or swipe back out of the screen while recording | The recording is stopped and saved, the same as tapping stop. Sending the app to the background with the side button does not stop it       |

A recording shorter than half a second is dropped as "Too short". Recordings stop on their own at 30
minutes, with a haptic warning a minute before, and are saved. That length is about 29 MB of audio, which
stays under the phone's 31 MiB upload limit.

Tapping the button while a tap-started recording is paused saves it; the button on its left resumes.
Someone using TalkBack gets a single action on the button that behaves like a tap.

A hint ("Tap to record or hold to talk") shows in place of the greeting until the first recording has
been saved.

## The screen states

`WearRecordingViewModel` drives the screen. Its phase moves through:

`READY` → `STARTING` → `RECORDING` ⇄ `PAUSED` → `SAVING` → `SAVED` → `READY`

with `TOO_SHORT` and `ERROR` as side exits. Every phase change happens **before** the recorder is asked
to do anything. That ordering is what stops the ViewModel from mistaking its own discard or stop for the
recorder ending the session by itself.

Errors say what failed and what to do:

| Error                                          | Shown                                  | Offered                                                                 |
| ---------------------------------------------- | -------------------------------------- | ----------------------------------------------------------------------- |
| Microphone permission denied                   | "Microphone is off"                    | An Allow button                                                         |
| Not enough space for a full-length recording   | "Watch storage is full. Free up space" |                                                                         |
| The recorder did not start                     | "Couldn't start. Try again"            | Press again                                                             |
| The recording finished but could not be stored | "Couldn't save. Tap to retry"          | Press again: it saves the same recording rather than starting a new one |
| The recorder produced no usable file           | "Recording failed. Try again"          | Press again to start a new recording                                    |

A call or another app taking the audio pauses the recording, and the screen says "Paused by another
app". It does not resume by itself.

## The recorder in the new watch experience

In the redesigned watch app the recorder is not a screen. It is the Record button at the bottom
edge of Today, the app's home, and while a recording runs its controls take the place of Today's
summary. The recorder underneath is the same one: both experiences record through
`WearAudioRecordingManager` and save through `AudioMomentSaver`, so the format, the files and
recovery are identical. What differs is the gesture, the states, and where a running recording
shows.

### One gesture on Record

| You do                    | What happens                                                                  |
| ------------------------- | ----------------------------------------------------------------------------- |
| Tap Record                | A locked recording starts: it continues after the finger lifts. Stop saves it |
| Hold Record               | Hold to talk: recording runs while the finger is down and saves when it lifts |
| Hold, then slide up 56 dp | The recording locks. A haptic confirms it, and Stop, Pause and Discard appear |
| Touch Record and scroll   | Nothing is recorded. The list scrolls                                         |

Record sits at the end of a scrolling list, so a touch on it may be the start of a scroll. The
gesture resolves that with one rule: it only watches until the system long-press timeout has
passed. Before the timeout, a lift is a tap, and a drag yields, whether the list took the touch or
the finger only travelled past the touch slop. After the timeout the gesture consumes every touch
event, so nothing scrolls, and a finger that slides sideways off the button does not end the
recording until it lifts. A touch the system cancels after the timeout, as it does for a palm,
counts as a lift and saves. The timeout is the system's, not a constant, so it follows the
accessibility setting for touch-and-hold delay.

The rules are a pure state machine,
[`RecordGestureMachine`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/recorder/gesture/RecordGestureMachine.kt),
with four phases (`Idle`, `Pressed`, `Holding`, `Locked`). It is fed by
[`Modifier.recordGesture`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/recorder/gesture/RecordGestureModifier.kt),
which listens on the Initial pointer pass, ahead of the button's own click and of the list, and
consumes the first touch so the click never fires. The lock distance is
`RecordGestureDefaults.LockDistance`.

The recorder starts when the finger touches Record, before the gesture has decided anything, so
the first word is never clipped. Nothing on screen changes yet. If the touch becomes a scroll, the
recording is discarded without a trace, including when the recorder was still starting.

Someone using a screen reader gets a single control whose description follows the recorder's
state, with one action, "Start recording", that does what a tap does.

### States

[`RecorderViewModel`](../../app/wear/src/main/kotlin/app/logdate/wear/v2/recorder/RecorderViewModel.kt)
holds one state:

`Ready` → `Arming` → `Live` → `Saving` → `Saved` → `Ready`

with `TooShort` and `Failed` as side exits. `Arming` is the time between the touch and the
gesture's decision; the recorder may already be running, and the screen does not show it. `Live`
carries a mode, `HOLDING` or `LOCKED`, along with whether it is paused, how far the finger has slid
toward the lock, the elapsed time and the input levels.

The view model belongs to the app, not to a screen, so a locked recording outlives the screen
that started it. When the activity finishes, a hold-to-talk recording is stopped and saved, a
locked recording keeps running, and a save that had failed is tried once more. Nothing recorded is
dropped silently.

While `Live`:

- **Holding** shows the elapsed time, "Release to save" and "Slide up to lock", with a lock glyph
  that fills in as the finger slides toward the lock.
- **Locked** shows Stop, Pause or Resume, and Discard. Discard asks first: the first tap changes
  it to "Tap again to discard", and a second tap within 3 seconds deletes the recording.
- The input level is drawn as bars around the edge of the screen.
- A haptic warns one minute before the 30 minute limit.
- A call or another app taking the audio pauses the recording, with "Paused by another app".

After a save, Today shows "Saved" with the recording's length. Undo works for 5 seconds, and the
five feelings are offered for 4 seconds, so a mood takes one tap; choosing one saves a mood
check-in beside the recording. The feelings are skipped when the shared setting
`capture.moodAfterRecording` is off. Recording again closes the Undo window and leaves the
previous note saved. A recording under half a second is discarded with "Too short to save".

Each failure shows what went wrong and the one action that helps:

| Failure                                        | Shown                                    | Action                              |
| ---------------------------------------------- | ---------------------------------------- | ----------------------------------- |
| Microphone permission refused                  | "LogDate needs the microphone to record" | Allow: asks again                   |
| Microphone permission refused for good         | "The microphone is off for LogDate"      | Open Settings                       |
| Not enough space for a full-length recording   | "The watch is out of storage"            | Storage                             |
| The recorder did not start                     | "Couldn’t start recording"               | OK                                  |
| The recording finished but could not be stored | "Couldn’t save the recording"            | Try again: saves the same recording |
| The recorder produced no file                  | "The recording had no audio"             | OK                                  |

While a failed save waits, Record tries the save again instead of starting a new recording.

### Permissions

The microphone is requested the first time a recording needs it, and again each time recording
starts without it. A recording that started from the permission prompt is locked, since the
finger is no longer on the button. Notifications are requested once, at the first locked
recording, because the recording's entry on the watch face needs them and recording itself does
not.

### On the watch face and in ambient mode

A locked recording has to stay visible after the person lowers their wrist. Two mechanisms cover
that.

The recording's notification is also an Ongoing Activity, the system's way of showing a running
task on the watch face: the record glyph with a stopwatch that stands still while paused. Tapping
it opens Today, where Stop is. It is added through a hook in the shared recording service,
[`RecordingNotificationDecorator`](../../client/media/src/androidMain/kotlin/app/logdate/client/media/audio/RecordingNotificationDecorator.kt),
which the watch's `Application` supplies and
[`OngoingRecordingDecorator`](../../app/wear/src/main/kotlin/app/logdate/wear/recording/OngoingRecordingDecorator.kt)
implements. The hook does nothing on the phone, and nothing on the watch while the flag is off.
Without the notification permission the system posts no notification, so the watch face shows
nothing.

On watches whose system provides the ambient service, which means Wear OS 6, the app also stays on
screen while a recording is live. The ambient face shows "Recording" and the whole minutes
recorded, white on black, updated at each ambient tick, once a minute. Where the watch asks for
burn-in protection, the text moves a few pixels at each tick. The app turns this on only while
recording. On Wear OS 5 the ambient service is absent, the screen dims to the watch face, and the
Ongoing Activity is what shows. The older ambient observer cannot stand in there, because it turns
always-on display on when the activity is created and cannot turn it off.

### Starting a recording from outside the app

The Record complication, the Today tile's Record button and a reminder's Record action all open
the app with a Record request. The new experience starts a locked recording for it, the same way a
tap does, and leaves a recording or a save that is already in progress alone. The app has to open,
because a microphone foreground service cannot start from the background.

Inside the app, Today has two more ways to start a recording besides the edge button, both only
while the separate activity awareness flag is on (see the
[`:app:wear` README](../../app/wear/README.md#two-experiences-and-the-switch-between-them)). While
the person is moving, a Record button leads Today's list and starts a locked recording, the same
as a tap on the edge button. After a walk, ride or trip, and after a night's sleep, Today shows a
card, and tapping it starts a recording.

When the phone is showing the live recording, its Stop is acknowledged only after the watch has
finalized and saved the capture, so the phone never reports a recording as stopped that was not
saved.

### Haptics

The new recorder plays the semantic haptic events the phone shares with the watch: recording
started, locked, paused, resumed and finished, save succeeded, limit approaching, action rejected
(too short, or a failure) and destruction confirmed (Undo or Discard). A watch setting limits
haptics to the essential ones.

## Recording infrastructure

The watch reuses the phone's recording service, `AudioRecordingService` in `client:media`, which
brings a confirmed start, error handling and file finalization.

- **`WearAudioRecordingManager`** holds the session logic. Start reports success only once the service
  confirms the recorder is running. Stop returns only after the file is complete. Both run under one
  mutex, and both check the microphone permission and free space first.
- **`RecordingSessionOptions`** switch on watch-only behavior in the service without changing the
  phone: a maximum length (30 minutes), pausing when another app takes audio focus, and a partial wake
  lock so a dozing watch does not drop audio.
- **`StorageSpaceChecker`** measures free space where recordings are written, `filesDir`, and requires
  room for a full-length recording plus headroom.

### Audio format

| Parameter   | Value                   |
| ----------- | ----------------------- |
| Codec       | AAC                     |
| Bitrate     | 128 kbps                |
| Sample rate | 44.1 kHz                |
| Container   | M4A                     |
| Size        | about 960 KB per minute |

### File lifecycle

1. The service writes raw AAC to `filesDir/audio_notes/recording_<id>.aac`. Raw AAC frames can be played up
   to the moment of a cut, which an MPEG-4 file cannot, because it keeps its index at the end.
2. On stop, the service wraps the raw file into `recording_<id>.m4a`, the file the rest of the app plays
   and uploads. The raw file stays beside it.
3. On save, a `JournalNote.Audio` is created in Room with the m4a path and the duration read back from
   the file, and then the raw file is deleted. Until then the raw file marks the recording as unsaved.
4. Discarding, an Undo, and a too-short recording delete the m4a and the raw file. A file that a saved
   note references is never deleted.
5. The note reaches the phone over the Data Layer, and the watch keeps it pending until the phone
   acknowledges it. [Sync with the phone](../../app/wear/README.md#sync-with-the-phone) in the
   module README summarizes the two protocols the watch speaks, and the
   [watch and phone sync protocol](../reference/wear/sync-protocol.md) reference has the paths,
   acknowledgements and retry rules.
6. An Undo also tells the phone. A note that is still pending is dropped from the watch's queue before
   it is ever sent, so the watch sends the phone an explicit delete, and the phone records that the note
   was deleted. Data items and channels are not ordered against each other, so a delete can arrive
   before the note it removes, and without that record the late note and audio would be stored anyway.

### Recovery after the app is killed

`WearRecordingRecovery` runs at every launch, on a background thread, over raw files written before the
process started. It wraps a raw file that has no m4a, saves a note for any recording whose note is
missing, and removes the raw file once the note exists. A raw file that cannot be wrapped yet stays for
the next launch. An m4a with no raw file beside it is never adopted, because it was saved or deleted.

Audio pulled from the phone for playback lives in `filesDir/synced_audio/<note id>.<extension>`, apart
from recordings, and appears there only once the download is complete, so recovery never sees it and
an interrupted download never becomes a note. A raw file in `audio_notes` that a note references is
saved audio, not an unsaved recording, so recovery leaves it alone.

Starting a recording needs room for the raw file and the m4a together, about twice the recording.

## Haptics

In the default experience, recording interactions use patterns from `WearHapticEngine`:

| Event                               | Pattern                |
| ----------------------------------- | ---------------------- |
| Start recording                     | `startRecording()`     |
| Stop recording                      | `stopRecording()`      |
| Pause / resume                      | `pause()` / `resume()` |
| Too short, or an undo               | `rejection()`          |
| Saved                               | `success()`            |
| A minute before the 30 minute limit | `warning()`            |

## Permissions

| Permission                                               | Required for                                   |
| -------------------------------------------------------- | ---------------------------------------------- |
| `RECORD_AUDIO`                                           | Microphone access                              |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` | Recording that survives the screen turning off |
| `POST_NOTIFICATIONS`                                     | The recording notification                     |
| `WAKE_LOCK`                                              | Recording continues with the screen off        |
| `VIBRATE`                                                | Haptic feedback                                |

## Testing

```bash
# Unit tests: the recorder session, the ViewModel, the player
./gradlew :app:wear:testDebugUnitTest

# Every recorder state on small and large round displays
./gradlew :app:wear:validateDebugScreenshotTest

# The record gesture, the watch face entry and a Record launch, on the managed Wear emulator
./gradlew :app:wear:wearSmallRoundApi34DebugAndroidTest
```

The new recorder's gesture rules are tested phase by event in `RecordGestureMachineTest`, and its
states in `RecorderViewModelTest`. `RecordGestureTest` drives real touches on the managed emulator:
a tap, a hold, a slide to the lock, a quick drag that yields to the list, a sideways slide that
keeps recording, a touch the system cancels before and after the timeout, and the screen-reader
action.

The microphone itself cannot be tested on an emulator, and neither can the ambient face, which
needs Wear OS 6. [The dogfood checklist](../testing/wear-dogfood-checklist.md) lists what to check
on a real watch.
