# Audio Notes Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make audio settings understandable and model readiness accurate before any download action.

**Architecture:** A shared model-download controller owns initial discovery and serializes checks and downloads for Android and desktop services. Settings observe those service states, with an Audio notes overview linking to focused Transcription and Sound recognition pages.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, Compose Multiplatform, Navigation 3, Koin.

**Spec:** `docs/superpowers/specs/2026-11-03-audio-notes-settings-design.md`

## Global Constraints

- Original audio remains useful without speech, transcription, or recognition.
- Downloads remain service-owned; platform-managed and unsupported capabilities are neutral states.
- Preserve existing route identifiers and use existing settings primitives and localized strings.
- No recording/playback redesign, model removal, or new processing preferences.
- All build/test workflows use disk-janitor guard; Android runtime validation uses emulators or Managed Devices only.
- Keep the current isolated worktree. Review changes with the developer before committing; use repository staging and commit rules if a commit is authorized.

## Review Focus

- A late availability check cannot overwrite an active download or its result.
- Reopening settings cannot flash an unverified missing-model state.
- Failure to inspect files cannot be presented as verified absence.
- Platform-managed transcription cannot promise an installed offline model.
- Long text and narrow/folding layouts preserve navigation, status, and actions.

### Task 1: Discover model availability independently of downloading

**Files:** Shared media download controller/status and tests; Android/desktop transcription and tagging services; install-time facades; iOS transcription status.

**Interfaces:** `ModelDownloadController(scope, isReady, download, dispatcher)` exposes `status: StateFlow<ModelDownloadStatus>`, `checkAvailability()`, and `startDownload()`. Add `Checking`, `CheckFailed`, and `ManagedBySystem` statuses. Service interfaces expose check methods with default no-op implementations for unsupported/system-managed providers.

- [x] Write controller tests for delayed discovery, installed/missing files, discovery exception and retry, download failure/retry, duplicate requests, cancellation, and check/download races. Use test dispatchers and deferred gates; do not invoke native inference.
- [x] Run the tests against compiling stubs and observe behavioral failures.
- [x] Implement background discovery and serialized operations; preserve cancellation and log real failures with Napier.
- [x] Wire all four downloadable services and both Android facades to the controller. Report iOS transcription as system-managed.
- [x] Run media tests and compile affected targets; inspect the terminal result.

### Task 2: Compose overview and dedicated capability pages

**Files:** VoiceNotesSettingsViewModel and overview; new AudioModelSettingsScreen/status presentation; settings routes/entries; voice-notes strings; settings UI tests.

**Interfaces:** Keep `VoiceNotesSettingsRoute` as the overview identifier. Add `TranscriptionSettingsRoute` and `SoundRecognitionSettingsRoute`. Detail content takes a capability, `UiState`, `onDownload`, `onRetryCheck`, and `onBack`. View-model initial state reads the services' current states rather than assuming absence.

- [x] Write tests asserting overview navigation, no download action during checking, ready/system-managed/unsupported states, contextual check retry, download bytes/progress, installation, and errors. Include a view-model reopen regression.
- [x] Observe the tests fail before implementing the new UI.
- [x] Build connected expressive overview controls and dedicated pages with platform-specific explanations, shared state text, and a single installation group.
- [x] Keep related content in one hinge-safe reading pane on book layouts; use bounded settings content at other widths.
- [x] Run affected desktop tests and lint, compile Android, and run the required debug build.

### Task 3: Review rendered behavior and deliver

**Files:** Focused settings render tests and retained screenshots outside source; design follow-up document for the broader audio experience.

**Interfaces:** Exercise the same stateless content used by production navigation. Screenshot output is an explicitly declared external artifact directory.

- [x] Render overview and both detail pages across compact phone, wide, dark, 200% text, RTL, and book/tabletop contexts with realistic descriptions and states.
- [x] Inspect screenshots and a consolidated comparison; correct clipping, hierarchy, or missing actions and rerun affected checks.
- [x] Obtain a fresh whole-change code review, resolve material findings, and verify the final diff.
- [x] Record concise verified outcomes or blockers on the existing Docket records, preserving evergreen descriptions and explicit priorities/states.
- [x] Present the settings result and a concrete ambient-audio proposal. Do not claim unperformed runtime, device, or release acceptance.

### Follow-up: Prepare availability before settings and simplify wording

- [x] Start Android and desktop service-owned discovery during application initialization without starting recording, inference, or downloads.
- [x] Keep desktop discovery read-only when app storage does not exist.
- [x] Add startup regressions using actual installed-file fixtures and service creation before settings requests them.
- [x] Replace technical implementation language with plain feature, setup, and download wording; distinguish Android live transcription from its optional download.
- [x] Remove the redundant device heading and idle download icon.
- [x] Run affected media/settings tests, quality checks, Android assembly, and inspect the updated populated renders.

### Follow-up: Handle interrupted setup automatically

- [x] Persist explicit download requests and resume them at startup without enabling unrequested downloads.
- [x] Retry transient discovery and connection failures with backoff, without check/retry controls in settings.
- [x] Reuse complete transfers while waiting for unpacking space and gate interrupted unknown-length transfers on increased space.
- [x] Remember rejected package revisions and check for updates without repeatedly downloading invalid bytes.
- [x] Reconcile owned transfer leftovers without hiding a valid installation.
- [x] Complete the expanded regression, quality, Android build, and render checks.
