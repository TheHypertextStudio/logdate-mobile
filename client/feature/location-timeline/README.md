# `:client:feature:location-timeline`

This module is the Places destination of LogDate: a screen that shows where the user has been, which memories belong to each place, and lets them correct what was recorded. It is a Compose Multiplatform feature module. `:app:compose-main` hosts the screen and registers the module's Koin definitions, and the Android instrumented and screenshot test sources in `:app:android-main` use its composables directly.

## What it contains

Everything lives under `app.logdate.feature.location.timeline`.

| Package                  | Contents                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `di`                     | `locationTimelineFeatureModule`, the Koin module that provides `SuggestNearbyHistoryPlacesUseCase`, `HumanLocationHistoryViewModel` and `LocationTimelineViewModel`. `locationTimelineModule` is a deprecated alias for it.                                                                                                                                                                                                                     |
| `ui`                     | `LocationTimelineScreen`, the entry point other modules call. It reads the `FeatureFlag.HUMAN_LOCATION_HISTORY` flag (enabled by default) and shows either the history workspace or the original timeline screen.                                                                                                                                                                                                                               |
| `ui` (original timeline) | `LocationTimelineViewModel` combines the current location, recent stops and memory places into a `LocationTimelineUiState`. `LegacyLocationTimelineScreen` and `LocationTimelineContent` render it. `LocationTimelineBottomSheet.kt` holds `LocationTimelineBottomSheet` and the quick-peek sheet. `LocationTimelineMap` is an `expect` composable, and `LocationTimelineStaticMapPreview` draws a static preview where there is no native map. |
| `ui.model`               | `LocationTimelineUiState` (`Content` or `Error`), `LocationTimelineErrorUiState` (`PermissionRequired`, `LocationServicesDisabled`, `TemporarilyUnavailable`) and the UI models for the current location, places, memory previews and stops.                                                                                                                                                                                                    |
| `ui.history`             | The history workspace. `HumanLocationHistoryScreen` wires `HumanLocationHistoryViewModel` to `HumanLocationHistoryContent`. State types are in `HumanLocationHistoryState.kt`. `HumanHistoryMapper.kt` maps location day items and journal notes to `HistoryItemUi` and `HistoryMemoryUi`, and the remaining files are the detail, place, edit and map composables.                                                                             |

### The history workspace

The workspace has two tabs, `HistoryTab.Day` and `HistoryTab.Places`. The Day tab lists the visits, journeys and gaps of one date (`HistoryItemKind`), shows them on a map, and can replay the day one item at a time. The Places tab lists places from the last 30 or 90 days, with a search field and an optional map.

`HumanLocationHistoryViewModel` reads from `LocationHistoryService` and `LocationTrackingSettingsRepository`, and resolves place names through `PlaceResolutionCache`. It keeps the selected date, item, tab and source in a `SavedStateHandle`. The corrections a user can make are the `HistoryEditAction` values: change place, change activity, change time, add a visit, add a memory, link a memory and delete. Each one except add a memory is applied through `LocationHistoryService`; add a memory calls the screen's `onAddMemory` callback. When a change fails to save, the view model keeps the action so `retry()` can run it again.

The view model also exposes whether background recording is enabled, and the screen asks `rememberHistoryRecordingAccess()` whether the device can record. If recording was interrupted or cannot start, the workspace shows a recovery message with an action to resolve it.

### Maps

`HumanHistoryMap` delegates to `NativeHistoryMap`, an `expect` composable that receives the day's items, the selected item and the camera callbacks. On Android it is a Google Maps Compose map when `rememberGoogleMapsEnabled()` is true, and a "Map unavailable" panel otherwise. On iOS and desktop it reports that it is not ready and shows a "Map unavailable on this device" panel. In Compose previews (`LocalInspectionMode`) `HumanHistoryMap` instead draws the day's markers and paths on a Compose `Canvas` using `historyMapGeometry` and `historyMapProjection`. `HistoryCameraPolicy.kt` decides when the map camera fits the day or follows the selected item.

## How it fits with other modules

The module depends on `:shared:model`, `:shared:config`, `:client:device`, `:client:datastore`, `:client:domain`, `:client:ui`, `:client:awareness`, `:client:repository`, `:client:location`, `:client:permissions` and `:client:util`. On Android it also uses `google.maps.compose` and `androidx.activity.compose`, and its manifest declares the Maps API key meta-data and an optional `org.apache.http.legacy` library.

Strings are Compose resources in `commonMain/composeResources/values`. The generated resource class is public, in the package `logdate.client.feature.location.timeline.generated.resources`.

## Platforms

Android, iOS (`iosArm64`, `iosSimulatorArm64`) and desktop JVM (target `desktop`). The `ui` and `ui.history` packages each have `androidMain`, `iosMain` and `jvmMain` implementations of the platform pieces: `formatCoordinateValue`, `LocationTimelineMap`, `rememberHistoryRecordingAccess`, `HumanPlacePicker` and `NativeHistoryMap`. The `jvmMain` directory is added to the `desktopMain` source set.

## Running the tests

All tests are in `commonTest` and cover the map geometry and camera policy, the history mapper, the history state and the error mapping:

```bash
./gradlew :client:feature:location-timeline:desktopTest
./gradlew :client:feature:location-timeline:testAndroidHostTest
```

## Things to know

- `LocationTimelineScreen` takes `onOpenNote` and `onAddMemory` callbacks. `onAddMemory` receives a `VisitMemoryContext` built from the selected visit.
- When `HUMAN_LOCATION_HISTORY` is off, `LocationTimelineScreen` shows the original timeline screen, so changes to either presentation should keep both working.
