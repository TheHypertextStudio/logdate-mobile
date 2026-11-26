# Stage 1E: Timeline Sheet, Date Navigation, Cards, and Editing UX

This specification directs Compose UI engineers executing Stage 1E in `client/ui` and `client/feature/location-timeline`. Every layout, surface, spacing, and header change must obey [Adaptive Home workspace](../../reference/architecture/home-workspace.md) and pass `checkHomeWorkspaceContract` without widening `config/workspace/home-allowlist.txt`.

## Home Workspace Contract Constraints

Keep these non-negotiable UI rules intact across every screen change:

- Map surfaces exclusively to `WorkspaceSurface`: `Canvas` (`surfaceContainer`), `Panel` (`surface`), `Group` (`surfaceContainerLow`), `Raised` (`surfaceContainerHigh`), and `Selected` (`secondaryContainer/onSecondaryContainer`).
- Keep `WorkspaceSupportingSheet` containment as `PanelContainment.Working` for `Your day`, embedded detail inspectors, and phone overlay sheets, and `PanelContainment.Collection` for expanded `Your places`.
- Keep `WorkspaceSectionSwitch` inside the supporting sheet header; never move day/place tabs above the map or stretch them into full-width tab bars.
- Keep `WorkspaceSearchScope` mounted only for `Your places` when no detail pane is open, leaving `WorkspaceAppBar` on one row even at 200% font scale.

## `WorkspaceSupportingSheet` Physics and Two-Way Synchronization

[WorkspaceSupportingSheet.kt](../../../client/ui/src/commonMain/kotlin/app/logdate/ui/workspace/WorkspaceSupportingSheet.kt#L158-L180) tracks vertical drag into a float `drag` total and snaps abruptly between `Peek`, `Browsing`, `Expanded`, and `Focused` on `onDragEnd` with no spring animation and no finger tracking.

Upgrade `WorkspaceSupportingSheet`:

1. **Live drag offset with spring settling:** Maintain an `Animatable<Float, AnimationVector1D>` (`currentHeightPx`) anchored to `anchors.peek`, `anchors.browsing`, `anchors.expanded`, and `mapBounds.height`. During vertical drag, update `currentHeightPx` synchronously (`snapTo`) clamped within `[peekPx, maxPx]`, and on `onDragEnd` use velocity from `VelocityTracker` plus threshold hysteresis (`24.dp` or `400.dp/s` fling) to `animateTo` the target anchor via `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)`. Respect `rememberSystemReduceMotion()` by calling `snapTo` directly.
2. **Visible drag handle pill:** Render a `32.dp x 4.dp` centered pill (`MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)`, `RoundedCornerShape(999.dp)`) at the top of the sheet when `side == null` so touch affordance is obvious.
3. **Nested scroll handoff:** Attach a `NestedScrollConnection` wrapping the supporting `LazyColumn` so dragging up from `Peek`/`Browsing` expands the sheet before scrolling the list, and pulling down when `listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0` collapses the sheet before overscrolling.
4. **Two-way map-to-list scroll sync:** In [HumanLocationHistoryContent.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/history/HumanLocationHistoryContent.kt#L533-L544), add a `LaunchedEffect(state.selectedItemId, state.selectionRevision)` that maps `selectedItemId` to its index in `state.items` (+ header item offset) and calls `listState.animateScrollToItem(targetIndex)` whenever selection comes from a map tap or Replay step.

## Cold-Open Flicker Fix and Legacy Screen Retirement

In [LocationTimelineEntry.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/LocationTimelineEntry.kt#L26), `collectAsStateWithLifecycle(false)` starts in `false` even though [FeatureFlag.kt](../../../client/datastore/src/commonMain/kotlin/app/logdate/client/datastore/featureflags/FeatureFlag.kt#L60) sets `HUMAN_LOCATION_HISTORY.defaultEnabled = true`. Fix that line immediately to `collectAsStateWithLifecycle(FeatureFlag.HUMAN_LOCATION_HISTORY.defaultEnabled)`.

Once Stage 1E parity and screenshot gates pass, delete the legacy location timeline code paths (`LegacyLocationTimelineScreen` in [LocationTimelineScreen.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/LocationTimelineScreen.kt), [LocationTimelineViewModel.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/LocationTimelineViewModel.kt), [LocationTimelineMap.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/LocationTimelineMap.kt), and [LocationTimelineBottomSheet.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/LocationTimelineBottomSheet.kt)) along with `ObserveLocationStopsUseCase` / `ObserveLocationMemoryPlacesUseCase` if no external module still injects them. Verify all callers first with a repo-wide symbol search.

## Activity-Aware Date Navigation

Users currently step day by day with blind `<` / `>` arrows (`HistoryDateHeader`) or pick blind dates in an unbadged `DatePicker`. Add three navigation aids backed by an indexed Room query (`ObserveRecordedDaySummariesUseCase` described in [06-data-models-migrations-and-sync-contracts.md](./06-data-models-migrations-and-sync-contracts.md)):

1. **7-day strip with activity dots:** Render a compact 7-day horizontal chip strip (`Mon..Sun` centered around `viewModel.date` or sliding by week) below the date header with subtle indicator dots: filled primary dot for days with visits and memories, outlined dot for days with visits only, no dot for empty days.
2. **"Today" quick-jump pill:** Show an inline `"Today"` `TextButton` or pill next to the prev/next arrows whenever `viewModel.date != todayInDeviceZone`.
3. **Activity-highlighted calendar sheet and timezone badge:** Replace the plain `DatePickerDialog` in [HumanLocationHistoryScreen.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/history/HumanLocationHistoryScreen.kt#L214-L227) with a month calendar sheet highlighting dates that hold recorded history or linked memories, plus a jump-to-latest-recorded-day action on empty dates. Whenever the day's resolved capture timezone ([02-reconstruction-v4-and-accuracy-engine.md](./02-reconstruction-v4-and-accuracy-engine.md#8-capture-timezone-day-framing-and-multi-device-fusion)) differs from the viewing device timezone, render a compact label (`"JST · UTC+9"`) beside the date.

## Redesigned Day Timeline Cards

Rebalance visual hierarchy and information density in [HistoryTimelineItem.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/history/HistoryTimelineItem.kt) and [HumanHistoryMapper.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/history/HumanHistoryMapper.kt):

- **Enriched day summary:** Replace `"2 visits"` with `"3 visits · 14.2 km · 4 memories"` using locale-aware distance units (`km` vs `mi`).
- **Balanced visit card typography:** Drop oversized `headlineSmall` (`line 158`) to `titleMedium` (`FontWeight.SemiBold`) with `12.dp` vertical padding (`8.dp` for approximate clues) so three visits plus journeys remain readable on a compact phone screen without feeling cramped.
- **Horizontal photo/video strip:** Render visual memories attached to a visit in a horizontal `LazyRow` of `56.dp` rounded thumbnails (`8.dp` spacing, showing a `+N` overflow badge past 5 items), keeping inline audio cards (`MomentAudioCard`) and compact 2-line text memory previews below the strip.
- **One-tap inline confirmation and disambiguation:** When `PlaceVisit.needsDisambiguation` is true or `place?.userConfirmed == false` with a suggested label, show compact action chips directly on the selected card (`"Confirm Café"` / `"Or Bakery?"`) so users fix labels in one tap without opening an edit dialog.
- **Rich journey rows:** Render `TravelMode`-specific icons (`DirectionsWalk`, `DirectionsRun`, `DirectionsBike`, `DirectionsCar`, `DirectionsBus`, `Train`, `Route` for unknown) and format subtitle as `"Walked · 1.4 km · 18 min"` (omitting distance on zero-point connectors while labeling `"Between visits · ~12 min"`).
- **Actionable gap rows:** Distinguish known pauses (`"Recording paused"`) from unexplained drops (`"Signal interrupted"`) and put an inline `"+ Add visit"` pill right on the gap row.

## Dedicated Editing Sheets

Replace `AlertDialog` in [HistoryEditDialog.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/history/HistoryEditDialog.kt) with focused bottom sheets (`ModalBottomSheet` on phones, `WorkspacePanel` inspector on wide layouts):

1. **Place Picker Sheet:**
   - Top search bar filtering saved `SemanticPlace` entries in real time and querying Google Places (`Autocomplete` / `Nearby`) when typing stops for `300ms`.
   - Distance labels (`"35m away"`) and category icons on every saved and nearby row.
   - Category selector `FlowRow` (`Home`, `Work`, `Coffee`, `Food`, `Outdoors`, `Fitness`, `Other`).
   - Full-width draggable center-pin map (`300.dp` tall) initialized to the visit's coordinate, falling back to `lastKnownPlace` or `defaultLocation` — never `LatLng(0.0, 0.0)` ([HumanPlacePicker.android.kt](../../../client/feature/location-timeline/src/androidMain/kotlin/app/logdate/feature/location/timeline/ui/history/HumanPlacePicker.android.kt#L34)).
   - Live `Slider` (`30m` to `500m`) drawing the editable radius circle directly on the picker map whenever creating or editing a saved place.
2. **Time and Structure Sheet:**
   - Replace raw text fields (`OutlinedTextField` `"10:15"`) with Material 3 `TimePicker` / `TimeInput` respecting the device's 12h/24h format plus date chips.
   - Add **Merge with previous visit** and **Merge with next visit** actions for adjacent visits fragmented by drift, and **Split visit at time** for a visit where two activities overlapped at one address. Persist both operations as immutable `HistoryCorrection` records ([06-data-models-migrations-and-sync-contracts.md](./06-data-models-migrations-and-sync-contracts.md)).

## `Your places` Collection, Device Names, and `HumanPlaceDetail`

Complete the remaining UX rough edges across [HistoryPlaces.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/history/HistoryPlaces.kt), [HumanPlaceDetail.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/history/HumanPlaceDetail.kt), and [HumanLocationHistoryScreen.kt](../../../client/feature/location-timeline/src/commonMain/kotlin/app/logdate/feature/location/timeline/ui/history/HumanLocationHistoryScreen.kt):

- **Remove dead toggle chips in Workspace mode:** Hide the `"List"` and `"Map"` `FilterChip` pair in `HistoryPlaceSearch` (`HistoryPlaces.kt:124-137`) whenever `LocalWorkspaceEnabled.current` is `true` because `WorkspaceSupportingSheet` already keeps the map mounted behind the sheet.
- **Full range presets, sort, and category filter:** Expand range cycling (`30 days`, `90 days`, `This year`, `All time`), add sort selection (`Most recent`, `Most visited`, `Most memories`, `A–Z`), and add category filter chips.
- **Human device labels:** Replace `"Device ${index + 1}"` in `HumanLocationHistoryScreen.kt:170` with device labels joined from `UserDeviceEntity.label` (`UserDeviceDao`), plus an `"All devices"` merged option backed by Stage 1B non-overlapping fusion.
- **Upgraded `HumanPlaceDetail`:** Add a focused header showing category badge, total visits, total hours spent, first/last visit dates, inline **Rename**, **Edit radius**, and **Change category** buttons, chronological visit rows, photo grid preview, and a searchable merge target picker.
