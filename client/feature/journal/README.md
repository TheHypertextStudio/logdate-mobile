# `:client:feature:journal`

This module is the journals experience: browsing all journals, creating one, reading a journal's entries, viewing a single note, editing a journal's settings, sharing it, merging two journals, and adding existing notes to a journal. The Home screen in `:client:feature:core` embeds the journals overview, and `:app:compose-main` registers its navigation entries.

## What it contains

Everything lives under `app.logdate.feature.journals`.

| Package       | What it does                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `navigation`  | Navigation 3 route keys and entry registrations. `journalEntries` registers every journal route at once from caller-supplied callbacks. The routes are `JournalsOverviewRoute`, `JournalDetailsRoute`, `JournalCreationRoute`, `JournalSettingsRoute`, `ShareJournalRoute`, `NoteDetailRoute`, `JournalContentPickerRoute` and `JournalMergeRoute`. `selectJournal` and `selectJournalNote` change the selection in a collection-and-detail layout without stacking extra entries. |
| `ui`          | The overview: `JournalsOverviewScreen`, `JournalsOverviewViewModel`, `JournalListPanel`, `JournalFilterBar`, `JournalCover` and `JournalCoverFlowCarousel`. The overview has a grid and a carousel layout (`JournalLayoutMode`), a sort option and filters. Its search text goes to the shared workspace header through `WorkspaceSearchScope` and searches journals and entries. `AddToJournalPicker` lets someone toggle a note's membership in several journals.                |
| `ui/creation` | `JournalCreationScreen` and `JournalCreationViewModel`, plus `rememberMediaPickerLauncher` and `MediaPickerState`, which are `expect` declarations with Android, desktop and iOS implementations.                                                                                                                                                                                                                                                                                  |
| `ui/detail`   | A journal's reading view (`JournalDetailScreen`, `JournalDetailViewModel`, `JournalReadingContent`) and the note viewers (`NoteViewerScreen`, `NoteViewerViewModel`, `AudioNoteViewerViewModel`). `toEntryDisplayData` in `EntryDisplayMapping.kt` converts a `JournalNote` into the display model for its kind.                                                                                                                                                                   |
| `ui/list`     | `JournalsList` and `JournalsListDialog`, a scrolling list of journals with covers and titles, and a dialog that shows it.                                                                                                                                                                                                                                                                                                                                                          |
| `ui/settings` | `JournalSettingsScreen` and `JournalSettingsViewModel` for editing and deleting a journal.                                                                                                                                                                                                                                                                                                                                                                                         |
| `ui/share`    | `ShareJournalScreen` and `ShareJournalViewModel`, which share a journal to Instagram, as a link, or as a QR code. The link and QR code actions run only when the connected server lets a journal link reach someone else.                                                                                                                                                                                                                                                          |
| `ui/picker`   | `JournalContentPickerScreen` and `JournalContentPickerViewModel`, which add existing notes to a journal.                                                                                                                                                                                                                                                                                                                                                                           |
| `ui/merge`    | The merge flow: destination picker, review and result.                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `di`          | `journalsFeatureModule`, which registers the view models with Koin and includes `sharingModule`.                                                                                                                                                                                                                                                                                                                                                                                   |

### Merging journals

`JournalMergeRoute` carries the source journal id and an optional pending operation id. `journalMergeEntry` registers it, and `completeJournalMerge` removes the merge route, the detail routes of both journals and the source journal's settings, share and content picker routes from the back stack, then opens the destination journal. `JournalMergeViewModel` keeps the operation id in saved state, so confirming twice repeats one merge. `ui/merge/JournalMergeAvailability.kt` offers the action only when the `JOURNAL_MERGE` flag is on and the configured server advertises `ServerProtocolFeature.JOURNAL_MERGE_V1`.

The merge itself is `MergeJournalsUseCase` in `:client:domain`. How it is stored, retried, sent and resolved on the server is described in [Journal merge](../../../docs/feature-design/journal-merge.md).

When a journal has been merged into another, `JournalDetailsRoute` resolves the requested id through `JournalRepository.resolveJournalId` and reports the redirect through `onJournalRedirected`. `redirectJournalDetail` then replaces the stale route in the back stack.

## How it fits with other modules

The build file lists dependencies on `:shared:model`, `:shared:config`, `:client:ui`, `:client:repository`, `:client:datastore`, `:client:domain`, `:client:sharing`, `:client:awareness`, `:client:util`, `:client:media` and `:client:feature:editor`. Entries that open the editor receive that navigation as callbacks (`onOpenEditorForJournal`, `onEditNote`), so the module never owns the back stack.

`:client:feature:core` and `:app:compose-main` depend on this module. `:app:android-main` uses it in its Android instrumentation and screenshot test source sets.

## Platforms

The module targets Android, desktop (`jvm("desktop")`), `iosArm64` and `iosSimulatorArm64`. `commonMain` holds almost all code. The only platform-specific files are the Android, desktop and iOS `MediaPickerLauncher` files under `ui/creation`.

## Running the tests

- `./gradlew :client:feature:journal:desktopTest` runs the view model, navigation and mapping tests from `commonTest` and the Compose UI tests from `desktopTest` on the JVM.

## Things to know before changing it

- The module contains two classes named `JournalDetailViewModel`, one in `ui` and one in `ui.detail`. The Koin module registers the `ui.detail` one.
- `NoteViewerViewModel` takes an optional journal id. With one, the viewer shows that journal's accent color, title and previous and next entries.
- `deriveCoverColor` in `ui/JournalColors.kt` is the single function that turns a journal id into its cover color, and the Android launcher shortcut icons in `:app:compose-main` call it too.
