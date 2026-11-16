# Navigation architecture

LogDate has one navigation graph, written once in shared code and used unchanged by the Android,
iOS and desktop apps. It is built on Navigation 3, the library in which the app owns the back stack
as a plain list and describes each destination with an entry. This page explains the pieces of that
graph, how a feature adds a screen to it, and the conventions that keep the screens independent of
the navigation machinery. The Navigation 3 artifacts are the `jetbrains-navigation3` entries in
`gradle/libs.versions.toml`. The Wear OS app has its own, separate navigation.

All of the shared graph lives in `app/compose-main/src/commonMain/kotlin/app/logdate/navigation/`.
The Android activity, the desktop window and the iOS view controller each call the same composable,
`LogDateNavDisplay`.

## The concepts

- A **route** (a `NavKey`) is a small serializable value that names a destination and carries its
  arguments, such as `JournalDetailsRoute(journalId)`. The back stack is a list of routes.
- An **entry** says what to show for a route. Entries are registered in one `entryProvider`.
- A **scene** is how one or more entries are laid out together. The default scene shows only the top
  entry; a **scene strategy** can choose a richer scene, such as two panes, from the entries at the
  top of the stack.
- A **decorator** wraps every entry's content, for behaviour that applies to all screens, such as
  keeping saved state or framing content.
- **Metadata** is a map attached to an entry at registration. The scene strategies and decorators
  read it to learn what kind of screen an entry is.

## Routes

Routes are `@Serializable` data classes (or objects) defined in the feature that owns the screen,
in its `navigation` package, and they implement `NavKey`. Two rules apply to them:

- **Arguments are strings and primitives, not `Uuid`s.** Saved-state serialization on iOS cannot
  handle `Uuid`, so `EntryEditorRoute` carries `entryId: String?` and the entry parses it.
- **Every route must be registered in `appNavSavedStateConfiguration`** (`AppNavKeys.kt`). Compose
  Multiplatform's Navigation 3 cannot discover `NavKey` subclasses by reflection on iOS, so the
  polymorphic serializer lists each one. A route missing from the list works until the back stack is
  saved and restored, then fails.

The back stack is created with `rememberNavBackStack(appNavSavedStateConfiguration, BaseRoute)`, so
it survives rotation, theme changes and process death.

## Registering screens

A feature exposes its screens as extension functions on `EntryProviderScope<NavKey>` that take the
navigation actions as callbacks:

```kotlin
fun EntryProviderScope<NavKey>.editorEntry(
    onNavigateBack: () -> Unit,
    onEntrySaved: (EntryEditorRoute) -> Unit,
    metadata: Map<String, Any> = emptyMap(),
) {
    taggedEntry<EntryEditorRoute>(metadata = metadata + ViewModelsPerVisit) { route ->
        // ...
    }
}
```

`LogDateNavDisplay` calls each of these and supplies callbacks that change the back stack, for
example `onNavigateBack = { backStack.removeLastOrNull() }`. The feature therefore never learns the
shape of the surrounding stack, and the screen composables below it take plain callbacks rather than
a navigator. This is what lets a screen be previewed, tested and reused without any navigation setup.

Use `taggedEntry<T>` from `client/ui` instead of the library's `entry<T>`. It also records the route
class in the entry's metadata, which scene strategies need because Navigation 3 does not expose an
entry's key.

Helpers that change the stack are extension functions beside the route, for example
`navigateToNoteDetail` (which does nothing if that note is already on top), `replaceNoteDetail` and
`redirectJournalDetail` (which swap a stack element in place). Write new helpers on
`MutableList<NavKey>`: `NavBackStack` is one, and a plain list lets tests exercise the helper without
Compose. Some older helpers, such as `navigateToEditor`, are written on `NavBackStack` instead.

## Starting the app

The stack starts as `BaseRoute`, a blank placeholder, because onboarding and lock state are not
known yet. When they resolve, `resolveNavBootstrapAction` decides whether to replace the stack:

- Not onboarded: reset to `OnboardingStart`, unless the top is already an onboarding route or the
  first-entry editor.
- Onboarded but locked: leave the stack alone; the lock screen covers it.
- Onboarded and unlocked: reset to `HomeRoute` only if the stack is still on `BaseRoute` or is
  stranded on an onboarding route. A stack the person has actually navigated is never discarded, and
  neither are the final onboarding screens.

Launch intents, deep links and app shortcuts reach the stack through `pendingNavKey`, the
`DeepLinkBus` (open journal, note, rewind or location timeline) and `AppShortcutRequests`. None of
them opens a screen while the app is locked or onboarding is unfinished.

## Choosing a layout: activity presentation

Whether a destination opens beside the screen it came from is decided by metadata, not by the
screen. `ActivityPresentation` (in `client/ui`) classifies an entry:

| Presentation                              | Meaning                                                                                                   |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `Replacement`                             | The default. The screen replaces the one below it.                                                        |
| `Home`                                    | The Home workspace.                                                                                       |
| `Collection(context)`                     | A list that can open details, such as the journals overview.                                              |
| `Detail(context, childContext)`           | A detail of a collection with the same `context`. `childContext` names what this detail can open in turn. |
| `SettingsRoot`, `SettingsDetail(section)` | The settings list and one settings page.                                                                  |

Attach it with `ActivityPresentation.Collection("journals").asMetadata()`.

`RelatedContentSceneStrategy` shows two panes when the top entry is a `Detail` whose `context`
matches the entry beneath it (that entry's `Collection.context`, or its `Detail.childContext`) and
the window is wide enough. A journal's details therefore open beside the journals list, and a note
opened from a journal opens beside that journal, while an unrelated screen never joins a scene.
`SettingsActivitySceneStrategy` does the same for settings, pairing the settings list with the
selected page. The strategies are tried in that order, settings first.

"Wide enough" is computed by `activitySceneGeometry`. The window must be at least 480dp tall and
848dp wide, both scaled up by the font scale so that large text collapses to one pane sooner. When a
vertical hinge splits the window, the two panes together must reach 848dp. A horizontal fold never
produces two panes, and neither does a separating hinge without a usable split.

Panel framing, surfaces and spacing inside these scenes are the subject of
[Adaptive Home workspace](./reference/architecture/home-workspace.md). `rememberWorkspaceRouteDecorator`
wraps `Collection` and `Detail` entries in `ActivityRouteFrame` so they receive the same safe insets
as Home content.

## ViewModels belong to a visit

By default a ViewModel obtained inside an entry belongs to the activity, so it outlives the screen
and shows its previous state the next time the screen opens. Entries that should start fresh add the
`ViewModelsPerVisit` metadata. `rememberPerVisitViewModelsDecorator` then gives the entry its own
ViewModel store, which survives configuration changes and is cleared when the entry leaves the back
stack. The editor, note viewer and the account pages in settings use it.

Screens that are steps of one flow do the opposite on purpose. Every onboarding step asks for the
same `OnboardingViewModel`, so the flow shares one instance for as long as the activity lives.

## Back navigation and gating

- The back action pops the last entry: `onBack = { backStack.removeLastOrNull() }`. On iOS an edge
  swipe also goes back whenever the stack has more than one entry.
- Entries for capabilities that may be unavailable are registered conditionally. The Watch pages
  exist only where `isWatchSupported` is true (Android). When `FeatureFlag.EVENTS` is not available
  for launch, the event detail route is registered as an entry that pops itself, so a stale
  restored stack cannot strand the person on it. The location timeline route renders only when the
  Places destination is enabled.
- The decorators wrapped around every entry, in order, keep saveable state per entry, apply
  per-visit ViewModels, expose the entry's animated-visibility scope for shared-element transitions
  (the editor's creation transition uses it), and apply the workspace frame.

## Adding a screen

1. Define a `@Serializable` route in the feature's `navigation` package, with string arguments.
2. Write an `EntryProviderScope<NavKey>` extension that registers it with `taggedEntry`, taking
   callbacks for everything that leaves the screen. Add `ActivityPresentation` metadata if it should
   open beside a list, and `ViewModelsPerVisit` if it must start fresh.
3. Add the route to the polymorphic list in `AppNavKeys.kt`.
4. Call the extension from `LogDateNavDisplay`, wiring its callbacks to the back stack.
5. Keep the screen composable free of the back stack, and test the stack changes with a plain list:
   the helpers are extensions on `MutableList<NavKey>`, so `NavBootstrapActionTest` and
   `HomeEntryCreationAdmissionTest` need no Compose or Android.
