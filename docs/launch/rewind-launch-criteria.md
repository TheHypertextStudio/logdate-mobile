# Rewind Launch Criteria

This checklist covers the eventual multiplatform and paid Rewind experience. For the first public Android release, use [Android public release acceptance](android-public-release.md): local weekly Rewind is required, while annual and paid Rewind are deferred.

The checklist that says Rewind is ready to ship to all users. Treat every box as
load-bearing — uncheck means do-not-launch.

**How to read the boxes.** A box marked `[x]` here is checked because a test asserts it and that
test runs in CI, so it stays checked only as long as the test passes. Boxes that need a person —
a manual pass on a seeded account, hardware LogDate does not have, or a signature — are still
open, and no amount of automation closes them. Where a criterion was partly automatable the box
stays open and the automated part is noted, so nothing reads as done when it is not.

## What "ready" means

A user on any plan, on Android / iOS / Desktop / Wear, opens their Rewind for the
week or the year and gets a curated, story-shaped Rewind without the app crashing,
hanging on a spinner, or showing a chronological photo dump. That's the bar.

## Curation

- [x] `RewindContent.Image.significanceScore` is populated for every image panel
      a curated Rewind ships with. Asserted by `RewindMediaCuratorTest` for a small fixture and
      again at 240 photos. Sampling a _real_ account is still worth doing once — the tests use a
      seeded signal extractor, not a device's photo library.
- [x] Screenshots, document scans, and burst duplicates are filtered out. `PhotoHardFilterTest`
      and `RewindMediaCuratorTest` cover the rules and the mixed fixture, and the pipeline is
      common code, so all three platforms share it. **Still open per platform:** each supplies its
      own signal extractor, and only the shared pipeline is under test — see the iOS box below.
- [ ] Story-beat `evidenceIds` reference real items that exist in the period's
      content. No dangling UIDs.
- [x] `maxItemsPerBeat` and `maxTotalMedia` caps hold under heavy input
      (≥200 photos for a single week). `RewindMediaCuratorTest` runs 240 photos across a week with
      story beats and asserts both caps, plus the documented cited-item allowance.

      Note for anyone extending these: a default `MediaSignals` has no dimensions, so the hard filter drops it below `minResolutionPx` and the fixture curates to nothing — an assertion that iterates the result then passes without checking anything. And with no narrative there are no beats, so everything becomes a free agent, which by design is not counted against the total cap.

## Tier coverage

All three tiers tested end-to-end with an account flagged into each:

- [ ] **FREE** — `RewindAITier.NONE`. Local Rewinds produce per-day story beats,
      themes pulled from the user's writing, a dominant-activity card, verbatim
      highlighted quotes, and zero reflection prompts (per
      `WeekNarrative.reflectionPrompts` contract).
- [ ] **STANDARD** — `RewindAITier.QUOTES_ONLY`. Behaves like FULL for the
      Wrapped panels; falls through cleanly to LOCAL on AI unavailability.
- [ ] **PRO / UNLIMITED / SELF_HOST** — `RewindAITier.FULL`. AI-written narrative
      opening + closing + reflection prompts surface as expected. Falls back to
      LOCAL when the LLM call returns `AIResult.Unavailable` or `AIResult.Error`.

## Annual Rewind

- [ ] Generates automatically each January for the prior year (verify the
      January 7 worker fires in an emulator with date-shifting).
- [ ] Falls back to a locally-built narrative when AI is unavailable, with
      `YearNarrative.origin == LOCAL_HEURISTIC`. Sequencer skips the repetitive
      opening / closing panels for local origin.
- [ ] Skip path: user with < 4 weekly Rewinds for the year still gets a Year in
      Review built directly from raw data (not an error).

## Platform parity

- [ ] **Android** — RewindDetailScreen renders every panel type that the
      sequencer can emit (`NarrativeContext`, `Transition`, `Image`, `Video`,
      `TextNote`, `MapPanel`, `WeatherPanel`, `PersonalityCard`, `TopList`,
      `HighlightedQuote`). Reduced-motion respect verified.
- [ ] **Desktop** — Six audit-flagged screens audited (`overview canonical`,
      `detail populated`, `loading`, `error`, `past rewinds`, `settings`).
      Sharing opens the macOS share sheet with a properly-rendered card.
- [ ] **Wear** — Tap zones, progress bar, and exit gesture match the shared
      `RewindPlaybackController` contract; no drift from Android.
- [ ] **iOS** — `IosMediaSignalExtractor` populates dimensions, screenshot flag,
      burst grouping, and GPS for sampled assets.

## Sharing

- [ ] Per-panel branded share asset renders correctly on at least three Android
      OEMs. Logo, period label, attribution are present and crisp.
- [ ] Year-end annual share carries the special closing-card frame.
- [ ] Android video share via `MediaRecorder` succeeds on API 30+ emulator.

## Onboarding & retry

- [ ] First Rewind view shows the onboarding bottom sheet exactly once
      (`UserPreferences.hasSeenRewindOnboarding` persists across launches).
- [ ] Detail screen "Refresh" action re-runs generation for the same period
      and is reachable even when the original generation errored.
- [ ] Tier-NONE users see the upgrade chip with copy that explains the
      experience difference without overpromising.

## Build gates (emulator-only — see CLAUDE.md)

Run the following sequence cleanly with no failures:

These modules are multiplatform, so their tests aggregate under `allTests`; a bare `test` task
does not reach them at all.

```bash
./gradlew :client:intelligence:allTests :client:domain:allTests :client:repository:allTests \
          :client:feature:rewind:allTests :client:database:allTests :client:datastore:allTests
./gradlew ktlintCheck
./gradlew :app:android-main:assembleDebug :app:android-main:smokeDevicesGroupDebugAndroidTest
./gradlew :app:compose-main:packageDmg
./gradlew :app:wear:assembleDebug
```

Verified 2026-08-31: the module tests, `ktlintCheck`, and the Android and Wear debug assemblies
all pass. The managed-device and DMG steps have not been run as part of this pass.

No `connectedDebugAndroidTest`, no `adb install` against a physical device.

## End-to-end manual verification

Seed an emulator with:

- one week of journal entries (mix of short / long, person names, emotion words)
- one screenshot, one document scan, one 5-photo burst
- 30 normal photos with realistic timestamps

Then walk through:

- [ ] Tier override → FREE: Rewind opens with local panels, no reflection
      prompts, real verbatim quotes, screenshot / scan / burst dupes absent.
- [ ] Tier override → STANDARD: quotes / prompts panels appear, structural
      panels remain.
- [ ] Tier override → PRO: full narrative opening + resolution + reflection
      prompts plus everything above.
- [ ] Settings → Reduce motion: kinetic typography degrades to crossfade only.
- [ ] Trigger Annual generation manually: multi-facet panels (themes, top
      people, best quote) on every tier.
- [ ] Share each panel type → branded frame renders correctly on the share sheet.

## Sign-off

Two people verify the manual pass — typically the maintainer + one beta tester.
Both sign by adding their name and date below.

- [ ] _Name, date_
- [ ] _Name, date_
