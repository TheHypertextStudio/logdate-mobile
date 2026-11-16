# Quiet Save

Quiet Save stops an audio recording after two continuous minutes of quiet and saves
the complete entry as a draft. It works while the screen is locked or LogDate is in
the background. Sound resets the timer; pausing the recording or changing the
microphone starts a fresh quiet window.

The silent **Quiet Save** notification confirms that the draft was saved. Choose
**Open draft** to return to the recording. Notification permission is optional:
saving still works when notifications are disabled.

When you return to LogDate, the acknowledgement fits the time you see it. A morning
return can explain that the recording was saved last night. Bedtime wording uses
sleep information already available to LogDate; Quiet Save does not request health
access or infer that you fell asleep from a quiet microphone. Without reliable
bedtime context, it uses a neutral message. Older recordings use a date.

## Recording preferences

On Android, **Settings → Voice notes → Quiet Save** controls automatic stopping.
Turn it off for an intentional ambient recording. The choice applies to the next
recording; it does not change one already running. The timeout is fixed at two
minutes, and Quiet Save keeps the whole recording, including its quiet ending.

The setting appears only in builds where Quiet Save is available. Its rollout
control is separate from the saved on/off preference.

## Recovery

Audio is written to recoverable AAC storage and associated with its draft before
recording starts. Session metadata is checkpointed every 30 seconds and when
recording pauses, resumes, or stops. Checkpoints do not copy the growing audio file.

If recording or saving is interrupted, the draft retains the information needed
to recover available audio when it is reopened. Audio that the recorder had not
yet written may be lost. A failed save never produces a success notification, and
the completed file or unfinished AAC source remains available for recovery.

After a device reboot, Android recovery waits for the first user unlock before
accessing credential-encrypted storage. Locking the screen again does not make
that storage unavailable or prevent Quiet Save from saving the recording.

## Detection and validation

Quiet Save uses microphone peak levels, independently of the optional transcription
and sound-tagging models. Levels are normalized from the recorder's 0–32767 range.
The noticeable-sound threshold is at least 0.0015 and at most 0.0036. A bounded
background estimate adapts to low room noise without rising above that ceiling.
A missing, invalid, or more than one-second-late observation starts a new window.
These thresholds identify quiet; they do not identify speech or sleep.

For crash-safe Android recordings, the quiet window must also contain two minutes
of completed AAC frames. An encoder delay can make elapsed time run ahead of the
audio written to disk. Quiet Save waits for both timelines before stopping, so
time spent waiting for the encoder does not count as completed audio.
Incomplete trailing frames wait for their remaining bytes; unreadable encoded
progress cannot trigger automatic stopping.

`QuietSaveDetectorTest` covers the exact two-minute boundary, room noise, soft
speech, sustained sound, interrupted input, and resets. Recording lifecycle tests
cover preparation before microphone capture, checkpointing, overlapping stop
requests, failed storage, and notification ordering. Draft tests cover atomic
writes, account ownership, repeated recovery, retained content, and acknowledgement
persistence. Return-message tests cover a single acknowledgement and a working
Open draft action in phone and tablet workspaces.

`QuietSaveRuntimeE2ETest` exercises native capture on an Android emulator, a locked
screen, a playable saved file, and the notification's Open draft action. The
`logdate.quietSaveMinimumDurationMs` instrumentation argument supports audio
injection before the first quiet window ends. `logdate.quietSaveNotification=false`
checks saving with notification permission denied; `logdate.quietSaveOptOut=true`
checks that intentional recording continues beyond two minutes of quiet.
Notification permission must match the selected scenario before starting the test.

The runtime test retains the source AAC and saved M4A in the Managed Device's
additional test output. It compares the count and ordered payloads of their AAC
frames, checks the reported duration against the captured samples, and separately
requires the minimum native recording duration. Start-to-publication elapsed time
is diagnostic; it includes startup and finalization. `QuietSaveAudioRetentionTest`
checks complete capture, truncated output, and incorrect duration metadata with
deterministic AAC fixtures.

The runtime suite also delays the Managed Device's software encoder across the
quiet deadline and verifies the saved duration with the same minimum bound.
This regression requires a rooted Gradle Managed Device and resumes the encoder
in cleanup, including when the test fails.

`QuietSaveRecoveryE2ETest` has two phases. Run
`prepareRecordingForProcessTermination`, terminate the emulator app after its ready
marker, and run `recoverRecordingAfterProcessTermination`. It checks the unfinished
AAC source, remuxing after restart, the original draft's content, and idempotent
completion. These tests reject physical devices and never require clearing app data.

The settings reference matrix covers phone, tablet, landscape, dark theme, dynamic
colors, right-to-left layout, compact screens, and large text. Native captures of
the notification, saved draft, and return message are in
[`docs/screenshots/android-os-integration/quiet-save`](../screenshots/android-os-integration/quiet-save).

## Internal distribution

The ordinary build default remains disabled. Android builds may opt into the
rollout with `-Plogdate.quietSaveDefault=true`; this sets the default for an unset
rollout flag without changing the saved recording preference. An explicit rollout
override still wins. The Play internal publishing workflow enables this default
for the phone app. Its `publish_wear` option can be disabled for a phone-only
internal release. Quiet Save remains unavailable on Wear OS and other platforms.

An internal upload begins release acceptance. Promotion to production requires the
existing [Android release acceptance](../RELEASE.md#android-acceptance-journeys), including
retained-data and recovery checks against the installed Play candidate.

The internal publishing workflow reads the Play internal track after uploading and
confirms a unique completed release for the candidate commit. An upload response
alone does not satisfy that check. The same lookup accepts an explicit version
code when verifying an already tested candidate for later promotion.
