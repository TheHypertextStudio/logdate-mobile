# `:client:feature:library`

**The Library: every photo, video and audio recording attached to an entry, browsed as one collection.**

## Overview

An entry can carry photos, videos and voice recordings. The Library shows all of them together, grouped by month,
and opens any one of them in a detail view with its date, location and the journals it appears in. It reads the
person's one local LogDate data set. It never filters or partitions what it shows by a signed-in account, and it needs
no network connection, because the notes and the media files it reads are both on the device.

Where the media files themselves live, and how an entry names them, is described in
[Media References](../../../docs/reference/media-references.md) and
[Android Media Storage](../../../docs/reference/android-media-storage.md).

## Architecture

```
commonMain/
  ui/LibraryMediaSource.kt        builds the list of items from notes and the media index
  ui/LibraryViewModel.kt          turns that list into LibraryUiState; retry() restarts collection
  ui/LibraryUiState.kt            Loading, PermissionRequired, Empty, Error, Content
  ui/LibraryScreen.kt             adapts the grid column count; hosts search and Postcards entry
  ui/LibraryContent.kt            the panel that renders each state
  ui/components/                  month headers, the thumbnail grid and its tiles
  ui/detail/                      MediaDetailViewModel, MediaDetailUiState, MediaDetailScreen
  navigation/LibraryNavRoute.kt   LibraryOverviewRoute and MediaDetailRoute (Navigation 3)
  di/LibraryFeatureModule.kt      Koin wiring
```

## Where the items come from

Two sources describe the same media, and `buildLibraryMediaSources` merges them:

- **Notes** are the source of truth for what the person owns. Every image, video and audio note contributes an item,
  keyed by its media reference.
- **The media index** (`IndexedMediaRepository`) holds extra metadata for images and videos, such as EXIF data. It has
  no audio entries.

The merge follows four rules, and each exists to keep the Library honest:

1. **A note-backed item is always visible.** The index is filled in after a note is saved, so it can lag or fail. An
   item that is only in a note still appears, so the Library never hides media the person just added.
2. **The index enriches but never duplicates.** Items are collapsed by media reference. When both sources describe one
   reference, the indexed record supplies the item and every matching note is kept alongside it, so the detail view can
   show journals and location.
3. **Two notes that point at the same reference are one item.** Different files whose display names match are not merged.
4. **Newest first.** Items are sorted by timestamp, descending, and then grouped by month.

An item is an image, a video or an audio recording, recorded as the `isVideo` and `isAudio` flags (neither flag means an
image). Audio items carry their duration. `JournalNote.Audio` has no caption or transcript field, so the Library has none
to show for a recording.

## What each state means

| State                | Shown when                                                                                                                                                                 |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Loading`            | Collection has started and nothing has arrived yet. The panel shows nothing during this state.                                                                             |
| `Content`            | At least one item exists.                                                                                                                                                  |
| `Empty`              | The sources loaded and contain no media.                                                                                                                                   |
| `Error`              | A repository flow failed. The panel offers a Try again button that calls `LibraryViewModel.retry()`.                                                                       |
| `PermissionRequired` | Rendered if supplied, with a message about photo and video access. The view model does not produce this state, because everything the Library reads is the app's own data. |

The panel crossfades between `Loading` and every other state with a 200 ms fade. A failed load is an `Error`, never an `Empty`. An empty Library tells the person they have no memories, so showing it
for a failure would be wrong. `retry()` increments a counter that restarts the combined flow with `flatMapLatest`, so
repeated taps replace the previous collection instead of adding a second one.

## The grid

`MediaThumbnailGrid` lays tiles out in a `LazyVerticalGrid`, keyed by item id so scroll position and tile state survive
regrouping. The column count comes from the panel's own measured width (one column per 140 dp, at least two) when the
workspace layout is on, and from the window size class otherwise (3, 4 or 5 columns). With the workspace layout off, a book posture splits the groups
between the two halves of the screen.

- Images show the file itself. Videos add a play badge. Audio has no picture, so it shows an audio icon tile.
- Every tile has a content description built from its kind and capture date.
- Tapping opens the detail view. A long press picks the media up to drag it into another app, so the context menu opens on
  right click only.
- Image and video tiles take part in a shared-bounds transition into the detail view, described in
  [Navigation motion](../../../docs/reference/architecture/navigation-motion.md).

## Search

The search bar is `LibraryTopBar`, or the shared workspace header when the workspace layout is on. It does not search
by itself: it calls an action that opens the app's unified search. `LibraryScreen` accepts that action as
`onOpenSearch`, and an app-wide workspace search action, when one is provided, takes precedence over it. Library search
is therefore whatever the host passes in; the Home-embedded Library passes a route that pre-selects the media caption and
transcription filters.

## Detail view

`MediaDetailViewModel` combines the index, the notes and the selected id. It finds the item whose id matches, or whose
matching notes include that id, and produces one of `ImageContent`, `VideoContent` or `AudioContent`. Each carries the
creation time, the resolved location name, and the journals the media appears in. Images also carry EXIF data.

- The location name is resolved in this order: a saved place, an external place suggestion, the coarse address, the note's
  own display name, and finally raw coordinates.
- The viewer pages across every item in the Library, so swiping stays inside the same ordered collection.
- On wide layouts (840 dp and up) the media and its details sit side by side. On compact layouts the details move into a
  bottom sheet.
- Audio plays through the shared `AudioPlaybackManager`. Presenting to an external display supports images and videos
  only.
- When the requested id is not found, or loading fails, the screen shows a message with a Go back button. It does not
  fall back to some other item.

## Testing notes

Screenshot fixtures for the Library must be decodable images, not placeholder URIs. A grid or detail that renders only
blank tiles passes a render task but proves nothing, so inspect the output.
