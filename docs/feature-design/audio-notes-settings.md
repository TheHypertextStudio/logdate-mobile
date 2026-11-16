# Audio notes settings

Audio notes settings explain what the device can do with a recording and show whether each
capability is ready before offering a download. An audio note can hold speech, music, ambient
sound, or anything else a person records, and the recording stays useful even when it has no
transcript and no recognized sounds.

## What the settings show

The Audio notes page opens with a short explanation of the audio capabilities, followed by two
rows: Transcription and Sound recognition. Each row gives a one-line summary of the capability and
its current state. Opening a row leads to that capability's own page with a standard back action,
and returning keeps the overview's state and scroll position. The routes keep their earlier
Voice notes identifiers so existing links still open the page.

The Transcription page describes what this device actually does: live text with optional
refinement where the platform supports it, or transcription after recording where it does not.
It never claims that a download only improves punctuation. The Sound recognition page explains
that detected sounds describe a recording and do not need speech.

Each capability page has a short explanation and one group that covers readiness and setup:

- **Not downloaded.** Download is offered only after the files are confirmed absent.
- **Downloading.** Progress shows the bytes transferred and the total size when it is known,
  followed by a separate installing state.
- **Waiting.** Delays are explained without asking the person to repair or recover the app.
- **Ready.** The capability can be used.
- **Managed by the system.** The platform provides the capability, so no download is offered.
- **Not supported.** A neutral explanation, with no retry control.

A failure that persists leaves recording and playback working. The settings have no controls to
delete or cancel a model, because those need lifecycle behavior the services do not have.

## Writing and presentation

The pages use Material 3 Expressive presentation inside the existing settings shell. Connected
surfaces show that a capability's explanation and its setup belong together. Transcription and
sound recognition each have a distinct tonal emblem with native Material shapes and a strong
heading hierarchy. Setup actions use expressive touch sizes and pressed shapes, downloads use wavy
progress, and checks use the native morphing loading indicator. With reduced motion, phase icons
are static and determinate progress is not wavy. State text is announced on its own semantic node,
and every status is understandable without color.

The copy describes what a person can do and what they need to do next. It avoids implementation
terms such as model, inference, server, or extraction, and keeps details that help a decision:
English support, processing on the device, download progress, and real limitations. An idle status
does not repeat the download icon that its action already shows, and there is no device heading
above the status. Wide and folding layouts keep related information together rather than placing
one capability in each half to fill the space.

## How readiness is decided

Checking availability is a different state from not downloaded. During app startup, before
settings asks for the services, the app looks for complete installed files off the UI thread,
without starting a download or loading the inference engine. Settings reads the state that is
already known, so it shows a checking state only while discovery is genuinely pending. The service
publishes verified availability, so every screen that observes it agrees.

The service owns each download, and one operation runs at a time:

- A check that finishes late never overwrites an active download or its result.
- A failure to inspect the files is reported as a failed check, never as missing files. Failed
  checks retry with capped exponential backoff.
- Connection and storage interruptions retry automatically within the same operation.
- An invalid download is retried once for each source revision; after that, the service waits for
  a corrected revision instead of fetching the same package again.
- A download the person asked for continues after the app restarts.
- Completed transfer bytes are kept while the device waits for space to unpack them. An
  interrupted transfer of unknown length waits for a meaningful increase in free space before
  it starts again.
- When complete installed files are found, the service removes leftovers from its own earlier
  transfers.

Downloads continue across settings navigation, and both the overview and the capability page show
their progress. Reopening settings never flashes an unverified "not downloaded" state.

The download controller and its states live in `client/media`, under
`app.logdate.client.media.audio.download`. The settings pages live in `client/feature/core`, under
`app.logdate.feature.core.settings.ui`.
