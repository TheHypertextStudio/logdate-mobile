# Markdown and durable audio transcripts

Markdown source remains the stored text. The shared JetBrains syntax-tree adapter drives source
styling, full reading, captions, and compact previews. Swift Markdown 0.9.0 drives the corresponding
native Apple adapter. Keep `test-fixtures/markdown-parity.json` byte-identical to the native Apple
repository's `LogDateTests/markdown-parity.json`. Its 17 cases check Unicode UTF-16 source ranges,
structure, formatting, line breaks, lists, tasks, tables, code indentation, and readable unsupported
syntax. Rendering changes must preserve the source and editor selection/undo behavior.
Text direction follows each paragraph's content in RTL layouts. The audio transport retains
elapsed-before-total ordering while its surrounding controls follow the layout direction.

## Audio record contract

Original media, authored captions, and structured transcripts are separate fields. A transcript
retains revision, document status, language, engine metadata, speakers, segments, word timing,
confidence, provenance, and finality where available. A valid FINAL document with no segments
represents completed no-speech recognition and suppresses automatic regeneration.

The optional wire field is an encrypted JSON string, bound to
`sync:note:<lowercase-note-id>:transcript`. Missing fields on older-client updates preserve the
existing transcript when the original media reference and duration are unchanged. Replacing the
audio reference or duration without a new transcript clears the old document so clients generate
one for the replacement.
Existing content version checks still apply. Server migration V35 adds storage;
deploy this backward-compatible support before enabling generation in newly released clients.

The local database associates durable transcription state with its source media URI and duration
(migration 50 to 51). A different URI or duration invalidates ownership and queues new
work; remote-to-local URI hydration atomically rebinds an existing transcript. This identity assumes
media references do not silently change their bytes in place. A future mutable-media API must supply
an explicit immutable revision or digest.

## Generation and persistence

Application startup scans saved audio newest first, and observed recording/import/sync changes
ensure missing transcripts. Newly saved and visible audio move ahead of queued recovery work;
active recognition retains its ownership. Android API 31 and later use same-identity WorkManager
expedited updates with ordinary-work fallback when quotas apply. API 30 keeps ordinary work
schedulable without introducing a foreground notification requirement. Interrupted jobs recover;
failed jobs can be retried. Jobs capture the
note, media identity, transcript revision, and work identity before recognition. Completion checks
those values again inside the persistence transaction. Deleted notes and superseded jobs cannot be
recreated or overwrite newer documents. Final empty documents are terminal.

Android uses its existing recognition engine. The shared file scheduler persists successful Desktop
and Compose iOS recognition results, with serialized recognition and cancellation ownership. Native
Apple uses an app-owned SpeechAnalyzer implementation behind a replaceable transcriber interface.

Transcript persistence and its sync outbox mutation share a Room transaction. The transcript path
does not acquire the metadata service mutex while holding the database writer. Every changed
transcript gets a fresh outbox operation identity, while an existing CREATE, queue age, and expected
server version are retained. An older upload acknowledgment cannot clear the newer transcript;
scoped and legacy DELETE operations are preserved. A transcript generated during the first upload
must progress from CREATE to a versioned UPDATE after successful or lost acknowledgment, without
settling the newer transcript mutation or erasing real caption/media conflicts.
When a transcript changes during an UPDATE, its pending server-version base advances atomically
after a confirmed upload to the same account and server, before the note's sync metadata is written.
The newer operation identity and deletion guards remain intact.

Search reads transcript text alongside captions. Backup/archive export and restore retain the full
document, including empty FINAL documents and speaker/engine metadata.

## Live transcription sessions

A recording can be transcribed twice. While the person speaks, an on-device recognizer streams
partial text straight to the editor (a live session). After the recording is saved, the same audio
can be transcribed from its file, which is how a recording recovers when no live session produced a
transcript. Everything above describes the durable result; this section describes how a live
session behaves and how a failure in either path is kept from losing the recording or going
unnoticed.

### What a live session guarantees

- **The recording survives transcription.** The audio recorder and the transcription session are
  separate. A start that fails, a recognizer that stops, or a persistence write that never succeeds
  leaves audio capture running and the saved file intact. The recording manager catches a failure
  while starting a session and reports it as a typed error, and a failure while stopping one falls
  back to transcribing the saved file.
- **Starting is acknowledged with a typed answer.** `TranscriptionService.startLiveTranscription`
  returns a `TranscriptionStartResult`: `Started`, `AlreadyRunning`, or `Failed` with a
  `TranscriptionFailure` reason. The call returns once the service owns a session. It does not wait
  for the session to end, so it adds no latency to partial text. A failed start both returns
  `Failed` and publishes the same `Error` on the result flow, so a screen that observes only the flow
  still sees it.
- **Partial text never waits on storage.** Partial results travel from the recognizer through the
  service's replaying result flow to the editor. Nothing on that path reads or writes Room or
  WorkManager. While a note is still being recorded, no transcript text is written to storage; its
  transcript is saved after recording stops, and again if a refinement pass rewrites it.
- **Every accepted session ends with exactly one terminal result.** `TranscriptionSessionTerminalizer`
  serializes a session's results. `begin` emits `InProgress`, `progress` publishes partial and
  refining text, and `complete`, `fail` or `cancel` each end the session once. After a terminal
  result, late progress from a background coroutine is dropped, so a finished or cancelled session
  cannot show progress again. `complete` accepts only a final result that is not refining. The terminal result is published before the session's recognizer resources are released, and releasing them can be repeated safely, so a cancellation racing a failure cannot leave a screen waiting.
- **Cancellation is a result.** Cancelling a session emits `TranscriptionResult.Cancelled` and does
  not replay as an in-progress state.
- **Refinement is optional.** After a live session stops, a slower offline model may rewrite the
  transcript. The streaming transcript is published first with `isRefining = true` (and `isFinal =
true`), and the session stays open until refinement finishes. If refinement cannot run or cannot
  improve the text, the last streaming transcript is published again with `isRefining = false`, so
  the session still reaches a terminal success.
- **Silence is not a failure of the pipeline.** A session that heard no speech ends with
  `NoSpeechDetected`. When the same happens for a saved file, the transcript is stored as a final
  document with no segments, which suppresses regenerating it.

### Failure reasons

Each failure carries a stable `TranscriptionFailure` so screens can choose their own wording and no
raw exception text reaches the interface: `NoNetwork`, `OutOfStorage`, `PermissionDenied`,
`NotAvailable` (the recognizer or its models cannot load), `NotSupported`, `AudioError` (capture
failed), `NoSpeechDetected`, `PersistenceError` (a produced transcript could not be saved),
`SchedulingError` (recovery work could not be queued) and `Unknown`.

The Android recognizer treats a negative read result from the microphone, or 50 consecutive empty
reads, as an audio failure and ends the session instead of waiting on a recorder that produces
nothing. A model that does not load in time ends it as `NotAvailable`. A terminal error replaces the
editor's progress state, and the editor keeps showing the recording controls, any transcript already
produced, and the existing retry action.

### Saving a transcript

Handing a result to the repository can fail while the audio is fine, so the recording manager and
the file scheduler save through `TranscriptionPersistenceRetrier`. It tries up to 10 times, waiting
100 ms and doubling the wait up to a 2 s ceiling. It runs off the result-delivery path, so retries
never delay partial text. When every attempt fails, the editor receives a `PersistenceError` and the
audio stays on disk for later recovery.

When the person stops recording, the recognizer's last result is handed to the saving path before
control returns to navigation. Without that hand-off, a final result published to the flow after the
editor closed could be lost. If the live session was unhealthy (it failed to start, it ended in an
error, or the microphone chosen for the recording could not be confirmed as the one the recognizer
listens to), the saved file is transcribed instead.

### Recovery work

On Android, a saved recording is transcribed from its file by `TranscriptionWorker` under
WorkManager, which only orchestrates retries. Work requests carry the `TranscriptionWorker.TAG` tag and a per-note tag (`transcription_note_` followed by the note ID), which is how queued work for one note is found and cancelled. The worker needs no network and its constraints do not
include one, because recognition is entirely on the device; background recovery only requires that
the battery is not low. Queueing and cancelling work are awaited, and a queueing failure is stored
on the note as a failed transcription with the `SchedulingError` reason so the person can retry.

The worker reports success only after the terminal database write succeeds. `TranscriptionWorkRunner`
maps outcomes as follows:

| Recognition result              | Stored                          | Work result        |
| ------------------------------- | ------------------------------- | ------------------ |
| Text                            | Final transcript, completed     | Success            |
| No speech                       | Final empty document, completed | Success            |
| Any other error                 | Failed document with the reason | Failure (no retry) |
| In progress or cancelled        | Nothing                         | Retry              |
| The terminal write itself fails | Nothing                         | Retry              |

A row that stays `IN_PROGRESS` for 10 minutes without an update is treated as abandoned and queued
again; a recovery pass queues it regardless of age. A `FAILED` row is queued again by a recovery
pass or by the person asking for a retry.

### Diagnostics

Logs record the stage, the note identifier and the failure reason. The throwable is logged where a
failure becomes terminal. Audio samples and transcript text are never written to logs.

## Verification and rollout

Run affected database, data, media, sync, domain, UI, editor, server, and integration suites, touched
Kotlin lint/detekt checks, Android app/test APK builds, and affected iOS compilations. The native
Apple repository supplies `verify-server-client.py` using this repository's
`:integration:server-client-e2e:writeAndroidHistoryHarnessClasspath`. It starts a disposable loopback
server and verifies encrypted Kotlin-Swift documents and audio, omission preservation, offline
reopen, transcript search, reconnect uploads, and conflict resolution with synthetic data.

`MarkdownAudioParityScreenshotsTest` uses real synthetic spoken audio, decoded waveforms, timed
transcripts, playback and scrubbing. Use Android emulators or Gradle Managed Devices only. Inspect populated phone and
tablet captures in light, dark, RTL, and large text after geometry/control changes. Unit tests and screenshots alone do not
prove microphone ownership or interruption recovery in the running application.
