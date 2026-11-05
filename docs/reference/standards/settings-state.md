# Settings state ownership

Settings pages preserve their structure while a person reads or changes a setting. Loading and
feedback belong to the smallest region affected by the operation. A page-level state is appropriate
only when the page has no usable data yet.

## Ownership

| Owner | State |
| --- | --- |
| Repository or service | Persisted preferences, account capabilities, secure storage, background synchronization |
| Screen ViewModel | Loaded values, availability, refresh, persistence results, authentication intent, operation cancellation |
| Local UI state holder | Dialog visibility, focus, scroll, expanded help, clipboard progress and feedback, animation |
| Platform | Device authentication UI, clipboard, accessibility motion preference |

A ViewModel is useful when state coordinates business rules, repositories, or work across UI elements.
Do not create one for every row. A composable may use a local state holder alongside its ViewModel;
transient presentation does not become business state merely because it needs more than a Boolean.

Never save secrets to SavedStateHandle, rememberSaveable, navigation arguments, logs, screenshots of
real accounts, or test receipts. A ViewModel can hold a revealed phrase only for its active presentation.
Leaving the route must invalidate pending authentication and reads as well as clear visible words.

## Screen state and operations

Use an explicit initial state. A preference that has not loaded is neither false nor disabled, and an
account that has not loaded is neither signed out nor signed in. Do not show actionable toggles with
invented initial values. Keep authoritative values visible during refresh when they remain usable.

Separate the screen snapshot from individual operations. Authenticating, copying, saving a value,
renaming a device, and checking a server must not replace unrelated content. Disable only conflicting
actions, prevent duplicate requests, and make retries clear the corresponding old error immediately.

Authentication feedback for an individual action must remain distinct from the application’s lock
policy. A failed challenge keeps the global retry gate while its caller receives the specific result.
Only the app’s own fresh unlock callback releases its lifecycle lock. Retained provider state and
sensitive-action successes cannot release it. Android permits one native credential pause per unlock
request; subsequent pause cycles and authoritative background events retire the request. Successful
results received while paused wait for foreground return. Native providers serialize challenge ownership
so one request cannot redirect another request’s callback. Cancellation leaves explicit retry available. The ViewModel attempts automatic unlock once per
foreground session, resets that policy on actual background, and preserves it through the native
credential handoff. Rendering a brief unlocked frame is not required to arm the next return. Locked presentation removes protected composition,
including separate modal windows; saveable UI state and navigation/ViewModel owners survive outside
that rendering boundary. Editors flush their latest draft on removal as well as background stop.

Cancellation is an ordinary outcome. Rethrow coroutine cancellation rather than mapping it to failure.
Dismissed authentication or confirmation returns to the prior stable presentation. Typed errors name
the failed operation and its available recovery; raw exception messages belong in logs.

Use one owner for initial loading. Lifecycle callbacks may refresh genuinely stale data or data changed
by a child route, but must not duplicate initialization. Coalesce refreshes or invalidate older requests;
late callbacks must not overwrite newer choices or restore secrets after navigation.

## Local actions and transitions

Clipboard writes remain at the platform/UI boundary. Report success only after the write completes,
report a failed write on the same action, and retain the content needed to retry. Copying a secret does
not establish that it was saved elsewhere. Mark supported clipboard entries sensitive.

Use short, local transitions to connect meaningful presentation changes. Respect the system reduced
motion preference. Outgoing animation must not retain sensitive text after Hide, navigation, or a
background privacy boundary. Preserve navigation and focus while action labels change.

Collect screen flows with lifecycle awareness. A lightweight secret-presentation snapshot must remain
observed through the stopped state so backgrounding can actually remove the secret from composition;
this is an explicit exception to stopping collection at STARTED. Dialog visibility and scroll may be saved when useful;
sensitive input and authentication intent must not be restored from persistent UI state.

## Recovery phrase page

The page serves viewing and copying an existing recovery phrase. Keep its hidden presentation focused
on privacy and the Show action, without a word-count badge or introductory encryption claims.
On-demand help uses a visual explanation of recovery on another device and a short description of
when the phrase is needed; external links describe their real destination. The viewer must not
read or write setup acknowledgements or control backup scheduling. Encryption and backup use the
account identity independently of viewing, copying, or manually saving a recovery phrase.
Authentication progress stays on Show. Keep a short recovery description with a labeled help link
at the start of the body. Contextual Hide belongs in the shared app bar. Group the privacy reminder
inside the phrase surface; Copy phrase belongs directly beneath it. The app bar and body share a bounded reading width.
Missing phrase storage does not imply that this device lacks the derived journal key. Short reading
panels use the native compact header and a smaller privacy visual, selected from measured local bounds.

Authentication that temporarily delegates to system UI is distinct from leaving the route. Hold a
successful result until the screen is active again; never expose words behind system UI. An explicit
route departure invalidates that result. Pausing an already revealed phrase covers it, including when navigation retains its outgoing
composition for an exit transition.

## Route contracts

Each route owns its business state in its ViewModel. Shared patterns do not require one giant Settings
ViewModel or a generic wrapper that hides feature-specific behavior.

| Route | Availability and operation boundary |
| --- | --- |
| App lock | Lifecycle-owned gating; serialized native challenge ownership; scoped unlock feedback and fresh request callbacks |
| Privacy and security | Independent preference observations; per-preference authentication and persistence; localized prompt content supplied by the UI |
| Devices | Account-scoped observations distinguish cached rows, remote success and failure; refresh settles only after the request; typed mutations are keyed by device; route polling stops with the screen |
| Timeline and day boundary | Persisted settings and fallback time arrive before controls become actionable; independent sleep and time writes; explicit permission-request intent |
| Location | Persisted values with per-setting writes; interval drag preview stays local and saves once on release; single-key writes avoid replacing unrelated preferences |
| Hosting | The server observation owns initialization and origin changes; local checks retain the known address and reject results for older origins |
| Sign-in methods | Retained methods during refresh; serialized add/remove operations; durable, identified success notices |
| Data and sync | Independent account, quota, archive, synchronization and integrity regions; quota snapshots carry account provenance; durable feedback is acknowledged by exact notice ID |
| Automatic diagnostic reporting | The controller owns reporting/privacy policy; its ViewModel owns consent preparation, serialized mutations, initial availability and independent toggle/delete feedback |
| Restore | Every completed restore has its own operation identity; duplicate delivery cannot start a second audit |

Settings persistence uses failure-reporting repository views so rejected reads or writes cannot become
fabricated defaults or success. Other service consumers retain their existing storage policy. A successful
write can reconcile its accepted field after an observation ends; a newer observed value remains authoritative.

A cached device list is usable while its remote request remains pending. A successful empty response
is distinct from a failed request without a snapshot. Device mutations and delayed observations cannot
update a retired account; the active mutation dialog owns its feedback while open.

Account changes invalidate account-owned quota, device observations and in-flight sync work. Token rotation within the same
account does not discard usable values. An unknown account remains unknown until its observation resolves.

Deliver notices only while STARTED and acknowledge after display completes. Stopping a screen cancels
presentation without consuming the notice. On return, the same notice can be presented again; an old
acknowledgement cannot remove a newer notice. Every responsive layout must mount the corresponding host
inside its own pane.

Regression coverage should exercise delayed initial values, success, failure, cancellation, retry, stale
callbacks, account changes, stopped-screen delivery and return from child routes. Collect Settings screen
flows with lifecycle awareness while domain background work remains in its service.
