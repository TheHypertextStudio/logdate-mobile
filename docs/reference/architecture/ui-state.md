# UI state and ViewModels

Every screen in LogDate is split into two composables: a thin screen that owns state, and a content
composable that only draws it. This page describes that split, what a ViewModel owns, how it
exposes state, and when state belongs elsewhere. The rules apply to Android, iOS and desktop alike,
because ViewModels and screens live in shared code.

## Screen and content

The **screen** composable is the only place that talks to a ViewModel. It obtains the ViewModel
from Koin as a default parameter, collects its state, and passes plain values and callbacks down to
the **content** composable:

```kotlin
@Composable
fun SearchScreen(
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val searchState by viewModel.searchState.collectAsState()
    SearchScreenContent(
        searchState = searchState,
        onQueryChange = viewModel::updateQuery,
        onGoBack = onGoBack,
        modifier = modifier,
    )
}
```

The pair appears throughout the client (`JournalDetailScreen` and `JournalDetailScreenContent`,
`RewindDetailScreen` and `RewindDetailScreenContent`). Keeping them apart has three effects:

- Previews, screenshot tests and UI tests construct the content with fixed state and no Koin.
- A route argument that must reach the ViewModel (a note id, a search query from a deep link) is
  delivered in the screen, in a `LaunchedEffect`, where the ViewModel is in reach.
- Shared UI in `client/ui` stays free of ViewModels, so it can be reused by any screen.

Only screen composables call `koinViewModel()`. A child composable never receives a ViewModel; it
receives the state it displays and the callbacks it may invoke. Tests may construct a ViewModel
directly and pass it in through the default parameter.

## What a ViewModel owns

A ViewModel owns state that comes from business logic: values loaded from repositories, the result
of an operation, and the choices a person has made that change what is loaded (a search query, a
filter). It receives use cases for operations and repository interfaces for observing data; see
[Dependency injection](../../dependency-injection.md).

State that is only presentation stays in the composable, held with `remember` or `rememberSaveable`:
whether a dialog is open, which row is expanded, scroll position, focus and animation. The
[settings state ownership](../standards/settings-state.md) page gives the full ownership table. It
also lists what must never be saved in `SavedStateHandle`, `rememberSaveable` or navigation
arguments (secrets).

### Local state holders

When presentation state has several related fields and rules, put it in a small plain class with a
`remember...State()` factory instead of a ViewModel. `rememberCanvasViewportState`,
`rememberInkCaptureState`, `rememberRewindNavigationState` and `rememberAudioPlaybackState` are
examples. A holder whose state must survive recreation is created with `rememberSaveable` and a
`Saver`, as `rememberEntryPageState` does for the entry overview's scroll offsets, expanded earlier
entries and focused-block anchor. A holder has no repositories and does no business work; if it
needs either, it belongs in a ViewModel.

## Exposing state

A ViewModel exposes read-only `StateFlow`s and takes input through methods:

- Keep the mutable `MutableStateFlow` private and expose it as `StateFlow`.
- Build screen state by combining flows, then convert with
  `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue)`. The five-second
  grace period keeps the upstream running across a configuration change but stops it when the screen
  has been gone for good. `SearchViewModel` is a complete example: a query flow is debounced,
  combined with filters, flat-mapped to a use case, and shared as one state.
- Model mutually exclusive states with a sealed interface (`AccountUiState`, `MoveServerUiState`),
  and give "not loaded yet" its own case. A value that has not loaded is neither false nor empty;
  see the settings state page for why.
- Give every field of a non-sealed UI state class a reasonable default, so previews and tests can
  build a state without listing each field.
- Launch work from methods with `viewModelScope.launch`. Do not expose `suspend` functions to
  composables, which have no way to consume them.
- Keep the small identifiers a screen needs to find its place again after process death in a
  `SavedStateHandle`: the editor stores its draft id (`editor.draftId`), the journal merge screen its
  stage and search query. Large content such as entry text belongs in the draft repository, not in
  saved state.

Collect state in screens with `collectAsState()` or `collectAsStateWithLifecycle()`. A flow that must
keep running while the screen is stopped is the exception that needs a documented reason. The
[settings state ownership](../standards/settings-state.md) page gives one: a snapshot that presents a
secret keeps observing while stopped, so that backgrounding the app can remove the secret from
composition.

## ViewModel lifetime

Where a ViewModel lives follows the navigation entry that creates it; see
[Navigation architecture](../../navigation-architecture.md). By default a ViewModel belongs to the
activity, which is right for steps of one flow that share state, such as onboarding, where every
step asks for the same `OnboardingViewModel`. A screen that should start from a clean slate each time
it is opened registers with `ViewModelsPerVisit`. Do not give each screen of a linked flow its own
ViewModel unless a screen is complex enough to deserve it.
