# Consolidated Places beta implementation plan

> **For agentic workers:** Use superpowers:executing-plans for the central implementation and verification. Independent route and data-continuity audits are complete.

**Goal:** One saved opt-in controls Places and location-history browsing, including secondary entry points.

**Architecture:** Reuse `places_beta_enabled` with its existing false default. It owns both browsing surfaces; enabled browsing uses the Human history experience. Retain `human_location_history_enabled` only for its existing internal history-sync compatibility role. No data migration or consent change occurs.

**Tech Stack:** Kotlin Multiplatform, Compose, DataStore, Navigation 3.

**Spec:** The approved ownership decision in this task and `docs/implementation/human-location-history.md`.

## Global constraints

- Preserve stored Places true/false choices; an absent Places key remains off.
- Never derive beta consent from the legacy history flag, which defaults true.
- Preserve legacy history-sync values, recording consent, OS permissions, background capture, retained records, export/recovery and Rewind.
- Disabling browsing removes open browser/detail UI without deleting history.
- Keep contextual day/note/Rewind metadata visible; hide dedicated browsing actions when unavailable.
- Prior native proof predates this behavior change and cannot establish consolidation.

## Review focus

- All nine raw persisted-key combinations preserve choices and unrelated preferences.
- Loading/restored/deep-link states cannot expose either browser before opt-in is known.
- A legacy sync-off installation can still opt into the Human browsing experience.
- Disabling an open browser/detail disposes its UI while retaining data for re-enabling.
- Secondary Settings/note entry points must be hidden off and invoke real navigation on.

## Task 1: Pin ownership and persistence

Files: `client/datastore/.../FeatureFlag.kt`, `FeatureFlagStoreTest.kt`, `PlacesBetaPersistenceTest.kt`.

- [x] Add raw absent/false/true preference matrix and reopen coverage.
- [x] Clarify ownership comments without changing keys/defaults or sync wiring.
- [x] Run `:client:logdate-datastore:jvmTest` and inspect results.

## Task 2: Gate both renderers and navigation entry points

Files: `client/feature/location-timeline/.../LocationTimelineEntry.kt`, its desktop test, `app/compose-main/.../LogDateNavDisplay.kt`, existing `PlacesNavigationTest.kt`.

- [x] Add UI regression cases for beta/legacy combinations, loading, disable/re-enable and nested detail disposal; verify the existing selector fails.
- [x] Observe only the consolidated browsing flag at the feature boundary; never render Legacy as an opt-out fallback.
- [x] Reuse existing Home/back-stack/deep-link guards and pass their nullable navigation action to Settings and journal entries.
- [x] Run focused feature and app navigation tests.

## Task 3: Complete secondary browsing affordances

Files: core Location Settings, Settings entries, journal entries/note viewer, and their focused desktop tests.

- [x] Add off/on/off visibility and real callback tests, preserving recording/location metadata.
- [x] Thread nullable `onOpenLocationTimeline` callbacks through existing entry APIs; null hides browsing actions.
- [x] Run core/journal focused tests and inspect affected renders if layout changes.

## Task 4: Verify data continuity and actual native behavior

Files: `PlacesBetaSettingsE2ETest.kt`, `docs/implementation/human-location-history.md`.

- [x] Extend native coverage with legacy sync off, Human browser selection, open details, disable/re-enable, persisted history and unchanged recording/legacy preferences.
- [ ] Retain existing sync identity-recovery, export and Rewind behavior; do not gate those paths by beta state.
- [x] Update the existing implementation documentation with ownership and migration semantics.
- [ ] Run affected host suites and Android compilation under the shared guard, then coordinate one fresh cached API 34 native run.
- [ ] Review the final diff, commit/push with ordinary hooks, inspect final-head CI/reviews and integrate by rebase only when eligible. Hand off one internal publication.
