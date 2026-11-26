# Stage 1 Exit Gate: Verification, Accuracy Benchmarks, and Rollout

This checklist defines the required verification gates for `logdate-mobile` release owners and contributors. Stage 2 (`logdate-apple` macOS display) must not start until every automated test, golden benchmark threshold, screenshot inspection, Managed Device E2E test, and field battery check below passes cleanly.

## 1. Golden Accuracy Benchmark Suite (`LocationReplayTool`)

Expand [LocationReplayTool.kt](../../../client/domain/src/jvmTest/kotlin/app/logdate/client/domain/location/history/replay/LocationReplayTool.kt) and [LocationReplayReport.kt](../../../client/domain/src/jvmTest/kotlin/app/logdate/client/domain/location/history/replay/LocationReplayReport.kt) with a synthetic deterministic corpus (no real user coordinates) executed by `:client:domain:jvmTest`:

| Scenario ID | Ground Truth Pattern | What Current v3 Fails On | Required v4 Pass Criteria |
| --- | --- | --- | --- |
| `B01` | 4h office stay with 65m Wi-Fi wander and two 2-sample cell-tower jumps (`450m`, `30m` accuracy degradation) | Splits stay at the 2-sample jump | Single confirmed `PlaceVisit`, centroid error `<= 20m`, false splits `= 0` |
| `B02` | 90m highway trip (`25 m/s`) with one `ENTER IN_VEHICLE` at `t = 0` and no further transitions | `applyActivityEvidence` drops activity at `t = 10m`, emitting `UNKNOWN` | Single `JourneyLeg` (`VEHICLE`), distance error `<= 5%`, duration error `<= 60s` |
| `B03` | Two adjacent urban stops `65m` apart (`Cafe` 35m then `Bookstore` 25m), `10m` GPS accuracy | Merges both stops into one `100m` stay | Two distinct `PlaceVisit` items matching `Cafe` and `Bookstore` |
| `B04` | `3m 45s` coffee pickup with `4` samples (`12m` accuracy) between two walking legs | Demoted to unconfirmed clue (`< 5m`) | Confirmed `PlaceVisit` flanked by two `WALKING` `JourneyLeg` items |
| `B05` | Walking inside a `350m` saved campus place for `45m` | Splits into alternating short walks and unconfirmed clues | Single confirmed `PlaceVisit` anchored to the campus `SemanticPlace` |
| `B06` | Multimodal commute: `6m` walk -> `18m` drive -> `4m` walk (no Activity Recognition permission) | All three legs labeled `TravelMode.UNKNOWN` | Split and classified as `WALKING`, `VEHICLE`, `WALKING` from kinematics |
| `B07` | Cross-timezone flight: morning stay in `America/Los_Angeles`, evening arrival in `America/New_York` | Slicing in viewer zone corrupts departure day | `isMultiZoneDay = true`, accurate local times on departure and arrival stays |
| `B08` | Overlapping two-device day (phone continuous + tablet static at home) | Single device only or interleaved routes if combined naively | Primary day intact; secondary fills an injected `45m` phone gap cleanly |
| `B09` | Indoor cold start with `180m` network fix followed by `15m` GPS after `90s` | `waitForAccurateLocation` gap cut (captured via stalled stream) | Continuous visit with `uncertaintyRadiusMeters` bounding true coordinate |
| `B10` | Mock GPS test sequence injected alongside real stay | Mock fixes pollute personal history | Filtered out when `includeMock = false`; personal stay untouched |

### Numeric Accuracy Targets Across the Corpus

- **Stay precision:** `>= 95%` (predicted stays matching ground-truth stays).
- **Stay recall:** `>= 92%` (ground-truth stays `>= 3m` captured).
- **Arrival and departure MAE:** `<= 90 seconds`.
- **Median visit centroid error:** `<= 25 meters`.
- **Travel-mode accuracy (moving legs with >= 4 fixes):** `>= 90%` across `WALKING`, `RUNNING`, `CYCLING`, `VEHICLE`.
- **Route distance error after Kalman + Douglas-Peucker:** `<= 8%` vs ground-truth path length.

Also run `./run location-replay` manually against private exported archives (`LOCATION_REPLAY_JSON`) outside the repository to confirm zero regressions compared with v3 before tagging Stage 1B done.

## 2. Automated Gradle Verification Matrix

Submit every Gradle verification run through `disk-janitor submit -- ...` per repository machine resource policy. All targets must exit `0`:

```sh
disk-janitor submit -- ./gradlew \
  :client:domain:jvmTest \
  :client:data:desktopTest \
  :client:database:jvmTest \
  :client:location:jvmTest \
  :client:location:testAndroidHostTest \
  :client:feature:location-timeline:desktopTest \
  :client:feature:timeline:jvmTest \
  :client:sync:desktopTest \
  :server:test \
  :integration:server-client-e2e:test \
  checkHomeWorkspaceContract \
  ktlintCheck \
  :app:android-main:assembleDebug
```

Required new unit and migration tests included in those suites:

- `Migration52To53Test` in `client/database/src/jvmTest`: verifies zero row loss from schema `52 -> 53`, default `ingestion_seq` population, `place_resolution_cache` creation, and SQLite rollback fault-injection on visit deletion.
- `PersistentPlaceResolutionCacheTest`: verifies concurrent coroutine safety under `Mutex`, true LRU eviction, `-0.0005` vs `+0.0005` `floor()` quantization, 5-minute negative error TTL, and 8-neighbor invalidation on place save/merge.
- `ScoredPlaceMatcherTest` and `AssociateVisitMemoriesTest`: verifies two places within 65m disambiguation, overnight residential POI suppression, and `±5m` visit-edge photo matching.
- `TimelinePlacesUnificationTest`: verifies `GetTimelineUseCase` populates semantic places from `LocationHistoryService` and never falls back to raw coordinate strings when a stay or coarse address exists.

## 3. Populated Screenshot Matrix

Extend [HomeWorkspaceScreenshots.kt](../../../app/android-main/src/screenshotTest/kotlin/app/logdate/screenshots/audit/workspace/HomeWorkspaceScreenshots.kt) and run:

```sh
disk-janitor submit -- ./gradlew :app:android-main:validateDebugScreenshotTest --tests '*HumanLocationHistory*'
```

Visually inspect every rendered PNG across compact phone (`360x780`), standard phone (`412x915`), landscape, tablet portrait/landscape, foldable book posture, foldable tabletop posture, dark theme, dynamic colors, RTL, `200%` font scale, and reduced motion. Include populated scenes showing:

1. `Your day` with numbered category pins, mode-styled polylines, photo filmstrip, and enriched summary header.
2. Selected approximate stay showing the `uncertaintyRadiusMeters` halo and `"Confirm / Were you at ...?"` disambiguation chip.
3. Multi-timezone flight day displaying the capture-timezone pill (`"UTC+9"`).
4. `Your places` zoomed-out cluster badges, category filters, and upgraded `HumanPlaceDetail` inspector.
5. Empty day with 7-day activity dot strip, paused/interrupted recovery banner, and Place Picker / Time & Structure sheets.

## 4. Gradle Managed Device E2E Gate (Never Physical Devices)

Per repository Android Device Safety rules, run instrumentation and live Google Maps checks strictly on Gradle Managed Devices (`smokeDevicesGroupDebugAndroidTest`):

```sh
disk-janitor submit -- ./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.e2e.HumanLocationHistoryE2ETest \
  -Plogdate.androidTestCoverage=false

disk-janitor submit -- ./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest \
  -Plogdate.androidTestClass=app.logdate.client.e2e.HumanLocationHistoryConnectedE2ETest \
  -Plogdate.androidTestCoverage=false
```

Also run `EncryptedHistoryInstallationsE2ETest` (`historyPhase=source` on phone Managed Device, `historyPhase=restore` on tablet Managed Device via `AndroidHistoryHarness`) to verify `LDSE2` round-trips with the new `LocationObservation` and `SemanticPlace` fields plus `SUGGESTED_PLACE` and `RADIUS` corrections.

Verify on the Managed Device renders:

- Real Google Maps tiles load with light and dark JSON `MapStyleOptions`.
- Google attribution remains visible above `WorkspaceSupportingSheet` across `Peek`, `Browsing`, and `Expanded`.
- Selecting a journey frames full route bounds rather than zooming to a single endpoint.
- Dragging the sheet tracks pointer velocity smoothly without skipping `LazyColumn` scroll handoff.
- Injected emulator activity transitions plus 100-meter geofence exit switch profiles cleanly without losing state across process restart.

## 5. Android Field Dogfood & Battery Protocol

Managed Devices cannot measure modem/GPS radio power or OEM background throttling. Before declaring Stage 1 complete and starting Stage 2:

1. **24-hour stationary + mixed commute dogfood:** Run an internal-track Android build with complete-day recording enabled across at least one Pixel device and one non-Pixel OEM device (such as Samsung) for 48 hours.
2. **Battery drain target:** Confirm Android Battery Usage attributes `<= 1.0%/hr` screen-off stationary and `<= 3.5%` overnight (8 hours) to LogDate.
3. **Departure sharpness check:** Verify arrival and departure timestamps on real door-to-door walks/drives land within `<= 90 seconds` of ground truth with zero unexplained gaps while moving outdoors.
4. **Sync durability check:** Confirm `/api/v1/location-history` uploads every day's `LDSE2` batch cleanly with zero HTTP 409 conflicts on single-device writes.
