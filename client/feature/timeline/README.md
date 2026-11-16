# `:client:feature:timeline`

This module provides the detail panel for a single day of the timeline and the entry that lets someone caption a marked moment. The panel shows the sections someone sees when they open a day: summary, people, notes, events and locations. The module also holds the words that the Home timeline shows for a summary that could not be made and for the current location on the empty-day suggestion card. The Home screen in `:client:feature:core` renders the marked moment entry and uses those words, `:app:compose-main` shows the day panel and registers the module's Koin definitions, and `:client:screenshot-scenes` renders the marked moment content.

## What it contains

Everything lives under `app.logdate.feature.timeline`.

| Package      | What it does                                                                                                                                                                                                                                                                                                                                                            |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ui`         | `TimelineDomainText.kt` maps `TimelineSummaryUnavailableReason` to the string resource a day's summary section shows (`notice`) and `CurrentLocationDescription` to `UiText` (`toUiText`).                                                                                                                                                                              |
| `ui/details` | `TimelineDayDetailPanel` and its sections: `TldrSection`, `PeopleEncounteredSection`, `NotesListSection`, `EventsSection` and `LocationsSection`. `AudioNoteSnippet` wraps the shared voice card for a day's recordings, and `TimelineDetailsEmptyPlaceholder` shows when no day is selected. `DayLocation` is the place type that `LocationsSection` and the map take. |
| `ui/marks`   | `MarkedMomentEntry` loads what a marked moment needs and renders `MarkedMomentContent`. `MarkedMomentViewModel` retains caption edits and serializes writes.                                                                                                                                                                                                            |
| `di`         | `timelineFeatureModule` includes `domainModule`, `intelligenceModule` and `networkingModule` and registers `MarkedMomentViewModel`.                                                                                                                                                                                                                                     |

### The day panel

`TimelineDayDetailPanel` takes a `TimelineDayUiState` from `:client:ui` and draws it inside a `WorkspacePanel` with a header that carries back, decorate and rewind actions. The decorate and rewind actions appear when the caller passes `onDecorate` and `onOpenRewind`. If the caller passes no visited locations, the panel derives them from the places in the day state that have coordinates.

The state itself, the list of days, the sticky date headers, the moment layouts and the voice cards come from `:client:ui` (`app.logdate.ui.timeline` and `app.logdate.ui.audio`), so other surfaces draw them identically. `HomeViewModel` and `HomeTimelineMapping.kt` in `:client:feature:core` build the day states from the streaming timeline.

[Semantic timeline](../../../docs/feature-design/semantic-timeline.md) describes the day structure, the voice card and the rules to keep when changing them.

### Marked moments

`MarkedMomentEntry` enriches a mark with its place, looks up the name of a saved place near it, and observes photo suggestions from the media library. It renders `MarkedMomentContent`, which offers writing more in the editor, attaching a suggested photo, saving the caption, leaving the mark and deleting it after a confirmation.

### Locations on a day

`TimelineLocationsMap` is an `expect` composable. Android draws a Google map through `maps-compose` when `rememberGoogleMapsEnabled()` is true. In every other case, and on desktop and iOS always, it draws `TimelineLocationsStaticPreview`.

## How it fits with other modules

The build file lists dependencies on `:shared:model`, `:client:ui`, `:client:domain`, `:client:permissions`, `:client:repository`, `:client:intelligence`, `:client:media`, `:client:networking`, `:client:awareness`, `:client:util` and `:client:feature:editor`. Android additionally uses `maps-compose` and Accompanist permissions. Navigation is supplied by callers: `TimelineDayDetailPanel` takes callbacks such as `onExit`, `onOpenEvent`, `onOpenLocations`, `onNoteClick` and `onJournalClick`, and `MarkedMomentEntry` takes `onWriteMore`.

`:client:feature:core`, `:app:compose-main` and `:client:screenshot-scenes` depend on this module. `:app:android-main` uses it in its Android instrumentation and screenshot test source sets.

## Platforms

The module targets Android, desktop (`jvm("desktop")`), `iosArm64` and `iosSimulatorArm64`. Platform source sets contain the `TimelineLocationsMap` actuals.

## Running the tests

- `./gradlew :client:feature:timeline:desktopTest` runs the tests in `commonTest` on the JVM. They cover the note context menu items and marked moment caption editing.

## Things to know before changing it

- `targetSectionIndex` in `TimelineDayDetailPanel.kt` maps an entry id to the index of its section in the panel's `LazyColumn`. The sections appear in the order summary, people, notes, events, locations, and each appears only when it has content, so adding or reordering sections means updating it.
- `MarkedMomentViewModel` writes the caption before running a save, attach or delete action and ignores edits once the mark is no longer open.
