# Human location history

## Delivery state

Places and location-history browsing share the `PLACES_BETA` opt-in
(`places_beta_enabled`) in Settings > About LogDate > Labs. Existing explicit choices are
preserved; a missing preference remains off. The previous default-true
`HUMAN_LOCATION_HISTORY` preference does not enroll users in the beta. It remains the
internal encrypted-history sync compatibility gate, with its stored values unchanged.

Enabling the beta opens the Human history experience. Disabling it hides browsing entry
points and open details without deleting history or memories. No data migration is needed.
Complete-day recording still requires explicit consent and Android permissions; the beta
does not change recording preferences, synchronization, Rewind, export, or identity recovery.

Production server support shipped as `server-v2026.09.29.3` at commit `e1f3900c3`, before client
enablement. The [production deployment](https://github.com/WillieCubed/logdate-mobile/actions/runs/36666399343)
passed migrations, authenticated candidate checks, traffic promotion, and public durability checks.
The canonical health endpoint reports that revision, and the new history route rejects anonymous
requests. The client is delivered through the existing Play internal-track workflow;
public Android promotion remains governed by `docs/launch/android-public-release.md`.

The Locations destination has Your day and Your places. A day contains individually browsable
visits, compact journeys, explicit gaps, visit-specific memories, and optional replay. Place
collections include recorded visits and geotagged memories, with separate last-visit and
latest-memory dates. The collection has 30/90-day ranges ending on a selectable date.

## Implemented behavior

- The day, selection, recording source, and explicit detail dismissal survive navigation state
  restoration. Map gestures pause replay; list navigation remains available without a map.
- Semantic names lead the UI. External nearby results remain suggestions until selected. Saved
  labels and context remain available offline. Place merging preserves individual visits and notes.
- Text, photo, video, and existing playable audio previews belong to their particular visit.
  Retrospective Add a memory passes the historical place and visit time to the editor, persists
  that context in drafts, and creates an explicit link without rewriting note creation time.
- Users can change a place or activity, adjust arrival/departure dates and times, add a missing
  visit, merge places, link a memory, and confirm deletion. Concurrent field edits remain available
  for an explicit choice. Deleting history does not delete journal notes.
- Browsing does not require present location access. Off, paused, missing permission, disabled
  services, interrupted capture, empty days, empty search results, and load failures have distinct
  messages. Optional section failures preserve available history and previously loaded content.
- Complete-day recording requires an explicit action after Android permission setup. Existing
  recording settings are retained. Requested adaptive intervals remain approximately three minutes
  stationary, thirty seconds on foot, and ten seconds in a vehicle; delivery is not guaranteed.
  The ongoing notification provides pause/resume. Scheduled work supports recovery.

## Storage, reconstruction, and continuity

- Room schema 48 adds observation metadata, activity evidence, account/origin-scoped history
  records, and durable cursors. Migration 47 → 48 preserves legacy observations, notes, and places.
- Observations retain original timestamps, accuracy, speed, bearing, capture source, device, and
  timezone context. Activity evidence is independent of the sampling profile. Overlapping capture
  paths share deduplication rules, and activity record identifiers are opaque hashes.
- Reconstruction is versioned and separates observations, visits, journeys, gaps, places, and
  immutable corrections. One recording device supplies each day. Credible stays require two
  observations spanning two minutes, with a 75-meter base area and ten-minute maximum evidence
  gap. Sparse evidence stays approximate. Unknown movement is not called driving or transit.
- Day reads are date-bounded with adjacent-day context and indexed 256-record carry-in pages to
  establish a stable boundary for multi-day stays; note reads use the same bounded interval,
  plus explicit links to relevant evidence. A place collection unions day sources without
  interleaving device routes. Date browsing uses the current device timezone; legacy records
  without timezone metadata use that same documented fallback. Original new-evidence zones are
  retained for future travel-zone presentation.
- `TravelModeInferenceProvider` accepts evidence and returns candidates with provenance. The
  initial provider uses recorded activity; timetable/route-based transit inference is deferred.
- The server's separate versioned `/api/v1/location-history` collection stores encrypted LDSE2
  payloads, opaque routing/version metadata, and permanent deletion tombstones. Coordinates,
  names, activity details, corrections, and memory associations stay inside encrypted payloads.
- Uploads are batched and downloads paginated. Pages and cursors commit transactionally. Account
  and server origin are pinned during synchronization; identities are recovered before fresh-device
  history restore. Consented local-owner adoption is journaled and transactional.
- Visit deletion commits all corrections, tombstones, and raw observation cleanup in one local
  transaction. A real SQLite fault-injection test verifies full rollback after a later write fails.
- Local tombstones rebase against newer remote versions without reviving content. Server version
  checks reject stale live writes. Location-only remote accounts participate in identity recovery
  even when the feature flag is disabled. Existing note collections and unsupported older servers
  remain compatible.

## Validation evidence

Validation is performed only with JVM/desktop tests, Gradle Managed Devices, and an isolated local
server/database. No physical Android device has been used.

- Representative screenshot coverage: twelve scenes, including phone, tablet, dark mode, large
  text, places, visit details, audio, replay, and recovery. Rendered images were inspected. Fixture
  maps are explicitly placeholders and do not establish provider-map correctness.
- Managed-device UI coverage: eleven tests on each of API 36 phone and API 35 tablet, including
  repeated visits, memory taps, replay content, selection, and persistent dismissal.
- Connected managed-device coverage: a real Room/service/view-model visit appears on both device
  types and passes its historical context to Add a memory.
- Recording runtime passed on both managed devices using the real foreground service and fused
  provider. Injected emulator evidence retained its observation timestamp, accuracy, speed, bearing,
  and source metadata. Actual notification actions paused/resumed capture and retained the chosen
  mode. The test restores mock settings and deletes only its own injected observation.
- Domain, Room migration, capture, editor, server, sync, and real server/client integration suites
  cover jitter, movement, sparse evidence, delayed/duplicate observations, midnight/DST,
  multiple sources, corrections, deletion suppression, bounded notes, and partial failures.
- The isolated PostgreSQL 18 fixture applied all 28 production Flyway migrations, including V28.
- Phased Android exchange passed on two different installations: API 36 phone source and API 35
  tablet restore. Production Android secure storage and encryption uploaded 205 offline records,
  preserved one deletion, rejected a stale live upload with HTTP 409, and resumed an interrupted
  download after reopening Room. The host verified different Android installation IDs. PostgreSQL
  contained 204 LDSE2 records, one null-payload tombstone, and committed version 206.
- Final combined affected-module tests, server/client integration tests, repository-wide
  `ktlintCheck`, and Android `assembleDebug` passed in the final 57-second run. Evidence:
  `.superpowers/sdd/human-location-history/release-checks.log`.
- Final connected UI tests passed on the managed phone and tablet, including linking an existing
  memory without changing its original timestamp or location. The assertion uses database
  millisecond precision. All 12 history screenshots were refreshed and validated; the updated
  visit detail was visually inspected. Evidence: `.superpowers/sdd/human-location-history/ship-ui.log`.

Useful commands (run serially with the Android SDK configured):

```sh
./gradlew :client:domain:jvmTest :client:data:desktopTest :client:database:jvmTest \
  :client:location:jvmTest :client:location:testAndroidHostTest \
  :client:feature:location-timeline:desktopTest :client:feature:editor:desktopTest \
  :client:sync:desktopTest :server:test :integration:server-client-e2e:test \
  :app:android-main:assembleDebug
./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.e2e.HumanLocationHistoryE2ETest \
  -Plogdate.androidTestCoverage=false
./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.e2e.HumanLocationHistoryConnectedE2ETest \
  -Plogdate.androidTestCoverage=false
```

The phased exchange uses `EncryptedHistoryInstallationsE2ETest` with `historyPhase=source` on
the phone and `historyPhase=restore` on the tablet, against `AndroidHistoryHarness`. Build its
classpath with `:integration:server-client-e2e:writeAndroidHistoryHarnessClasspath`; start the
harness with an isolated PostgreSQL database and a private temporary control directory. The
fixture is loopback-only, keeps temporary account credentials out of command arguments, and
requires both phases to use the same running server. It rejects identical installation IDs.

Screenshot validation:

```sh
./gradlew :app:android-main:validateDebugScreenshotTest --tests '*HumanLocationHistory*'
```

The landing onto current main found an independently added V28 migration. Location history
uses V29; all eight server/client tests passed again against isolated PostgreSQL 18 with both
V28 and V29 applied. Evidence: `.superpowers/sdd/human-location-history/ship-postgres-e2e.log`.

Static-analysis helper extraction passed history unit tests and Android assembly. Shared history
UI also compiles for the iOS simulator after replacing a Java-only map call. The enabled default
passed datastore tests and Android assembly.

## Release gates and limits

- Backward-compatible production server support was verified before enabling Android clients.
  The release owner authorized shipping on September 29.
- Emulator results establish behavior, not real-world battery life or exhaustive travel capture.
  Battery use, background restrictions across vendors, and field travel reliability need separate
  release evidence.
- Native iOS recording/maps and automatic transit route inference are deferred as approved.
- Provider map tiles and real media/provider failures need release-environment checks in addition
  to the deterministic fixtures and connected emulator tests.
- Reconstruction pages backward from adjacent-day context until evidence proves a real boundary.
  A very long uninterrupted recording component can require many pages; UI/notes-only updates reuse
  cached evidence, and reconstruction runs off the main thread. Historical travel-zone presentation
  currently follows the browsing device timezone, while original capture zones remain stored.
