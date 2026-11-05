# Audio notes settings

## Intent

Make audio settings explain the capabilities available on this device and show
their actual readiness before offering a download. Audio notes include speech,
music, ambient sound, and recordings without a transcript.

## Scope

Rename the visible Voice notes settings destination to Audio notes. Replace its
two download cards with compact Transcription and Sound recognition settings rows,
each opening a dedicated page. Fix availability discovery in the underlying model
services on Android and desktop, and represent platform-managed and unsupported
capabilities honestly. Preserve existing route identifiers where useful for
compatibility.

Recording, playback, transcript editing, search, and processing preferences are
outside this first change. Do not add switches without implementing their effect.

## Approaches

Keeping both download cards on one page would involve less navigation, but would
continue to make installation the organizing concept for audio settings.

The selected approach is an Audio notes overview with two dedicated settings
pages. It adds one navigation step for model management while making capability,
readiness, and setup easier to understand. This follows the existing settings
navigation and visual primitives.

A broader recording and playback redesign is deferred at the user's request.

## Experience

The overview contains a short explanation of audio capabilities, followed by
Transcription and Sound recognition rows. Each row shows a concise capability
summary and its current state. Opening a row leads to its own titled settings
page with a standard back action; returning preserves state and scroll position.

The Transcription page explains the device's actual behavior: live text and
optional refinement where supported, or transcription after recording where
applicable. Avoid a universal claim that a download only improves punctuation.
The Sound recognition page explains that detected sounds describe a recording
and do not require speech.

Each detail page contains a short explanation and a single coherent readiness
and installation group. Offer Download when the optional files are absent;
availability checks and temporary download interruptions retry automatically. While downloading,
show transferred bytes and total size when known, then a distinct installation
state. Explain delays without asking someone to repair or recover the app.
Retry failed file discovery with capped exponential backoff. Automatically retry
connection and storage interruptions while preserving one service-owned operation;
retry an invalid download once per source revision, then check for a corrected
revision without repeatedly fetching the same package. Persist explicit download
requests across app restarts. Keep completed transfer bytes while waiting for
space to unpack them; interrupted unknown-length transfers wait for a meaningful
increase in space before starting again. Reconcile owned transfer leftovers when
complete installed files are found. A persistent failure leaves recording and
playback usable. Unsupported capabilities are
neutral explanatory states without retry controls. System-managed capabilities
do not offer a model download. Do not add model deletion or cancellation controls
in this change; those require additional lifecycle behavior.

Use Material 3 Expressive presentation within the existing settings shell. Connected
surfaces communicate the relationship between capability explanation and setup.
Transcription and sound recognition have distinct tonal emblems, with native
Material shapes and strong heading hierarchy. Setup actions use expressive touch
sizes and pressed shapes; downloads use wavy progress and checks use the native
morphing loading indicator. Honor reduced motion with static phase icons and
non-wavy determinate progress. Preserve accessible roles and announce state text
on its own semantic node.

Use existing settings surfaces, spacing, and adaptive layout patterns. Keep related
information together on wide and folding layouts; do not force one model into
each half merely to fill the space. All strings are localizable and statuses
remain understandable without color. Feature settings describe what someone can
do and what action they need to take. Avoid implementation terms such as model,
inference, server, or extraction. Keep details about English support, processing
on the device, download progress, and actual limitations where they help someone
decide. Do not repeat the download icon in both an idle status and its action or
add a redundant device heading above the status.

## State contract

Checking availability is distinct from not downloaded. Discover complete installed
files off the UI thread during app startup, before settings requests the services,
without starting a download or loading the inference engine. Settings reads the
already known state; only genuinely pending discovery needs a checking state. Publish verified availability from the service so every consumer sees
consistent state. An initial check must not overwrite an active download or its
result. Discovery failures must not masquerade as missing files.

Only offer Download after absence is verified. Preserve service-owned downloads
across settings navigation and reflect their progress on both overview and detail
pages. Reopening settings must not flash an unverified Not downloaded state.

## Acceptance

Regression coverage includes delayed discovery, installed and missing models,
automatic discovery retries, active downloads, installation, automatic retries for
connection/storage interruptions, bounded retries for invalid downloads,
reopening settings, and platform-managed or unsupported capabilities.

Inspect rendered overview and detail pages with realistic descriptions at compact
phone and wider sizes, light/dark themes, large text, RTL, and folding layouts.
Verify navigation and accessible status/action semantics. Android runtime checks
use emulators or Gradle Managed Devices only. Run affected tests, lint, and the
required Android build through the repository's resource guard.

## Follow-ups

Evaluate non-speech recordings as a complete experience, distinguish audio saved
from transcript processing, and improve continuity of seeking and playback between
editing and revisiting. Preserve meaningful working surfaces around waveform,
timer, transcript, and controls when reviewing those flows.
