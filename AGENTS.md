# LogDate Agent Guidelines

> Principles, workflow, and non-negotiables for any AI agent working in this repository.

## Product Purpose and Positioning

**LogDate is a home to document, reflect on, and share memories.** This is the prime directive
for product decisions, design, documentation, and marketing. Read
[`docs/product-info/product-positioning.md`](./docs/product-info/product-positioning.md) before
shaping product work or writing product copy.

LogDate provides journaling functionality, but **do not sell it primarily as a "journaling app"
or "diary app."** The category is crowded and does not express the holistic memory-oriented
purpose. Journals, entries, and writing are supporting functionality and valid technical terms.
Documenting, reflecting, and sharing are equally part of the product direction; do not reduce
the story to writing entries or treat sharing as incidental. Ground each feature and asset in
what a person wants to preserve, revisit, understand, or share. Keep platform and release
availability claims accurate; the product purpose does not establish that a feature has shipped.

Continuously explore creative ways to record memories. Capture should be opinionated, fun, and
playful, with meaningful results people can preserve and revisit over a long time. Design the
recording experience and its lasting result together; avoid a collection of disconnected novelty
features. From an engineering perspective, LogDate is a shared memory platform with purpose-built
clients. Adapt workflows to each device, form factor, and use case, including watch, phone,
foldable, tablet, laptop, and potential TV experiences. Do not equate coherence with identical UI
or feature parity. These are product principles, not claims that every client or capability ships.

## Development Workflow

All changes land directly on `main`. There are no long-lived feature branches. This demands extreme
commit discipline — every commit must leave `main` in a shippable state. Incomplete features are
gated behind feature flags, not hidden in branches.

Every change follows this process. No exceptions.

### 1. Understand

Before writing any code, determine:

- **What type of change is this?** Feature (`feat`), bug fix (`fix`), refactor (`refactor`), or
  maintenance (`chore`)? This determines the commit type.
- **What user-facing behavior does this serve?** LogDate is a multiplatform product (app + server) —
  changes are always in service of the user. A feature may span `client/*`, `shared/*`, and
  `server/` modules, and that's expected. Think in terms of the feature, not the module boundary.
- **What already exists?** Read the affected code. Check for existing utilities and patterns before
  creating new ones.

### 2. Plan

Design the approach before writing code. For non-trivial changes:

- Brainstorm interactively with the developer to clarify requirements, trade-offs, and scope.
- Trace the feature across layers: what UI changes, what domain logic, what data/repository changes?
- If API contracts or sync behavior are involved, trace changes through `:server` and validate
  impact in `:integration:server-client-e2e`.
- Keep the scope tight — one logical feature or fix, even if it touches several modules.

### 3. Write Tests First

Tests define the contract for the change. Write them before the implementation.

- Write tests that describe the expected behavior.
- If the implementation doesn't exist yet, create stubs or interfaces so the tests compile. Tests
  should fail because the behavior isn't implemented, not because the code doesn't build.
- For features spanning multiple modules, write tests at the appropriate layer: unit tests for
  domain logic, integration tests for repository/data interactions.

```bash
# Run tests for affected modules
./gradlew :client:feature:timeline:test :client:repository:test
```

### 4. Implement

Build the implementation to satisfy the tests.

- Follow existing patterns. Consistency matters more than novelty.
- Keep it minimal — implement what the tests require, nothing more.
- Work across module boundaries as needed. A single feature touching `client/feature/`,
  `client/domain/`, `client/repository/`, `shared/*`, and `server/` can be normal.
- **Gate incomplete features behind feature flags.** Every commit lands on `main`, so
  partially-built features must be invisible to users. Structure code so flagged paths are easy to
  find and remove once the feature ships.

### 5. Iterate Until Green

Run tests and quality checks. Fix failures. Repeat.

```bash
# Run tests for all affected modules
./gradlew :client:feature:timeline:test :client:domain:test

# Kotlin lint
./gradlew ktlintCheck

# Full build
./gradlew :app:android-main:assembleDebug
```

All tests must pass and quality gates must succeed before committing.

### 6. Review

Get feedback from the developer before committing. Walk through the changes, confirm the approach is
correct, and adjust if needed.

### 7. Commit

Each commit should read like a changelog entry — a single, distinct change that a user of the app
would recognize. Scope to the primary feature module, even if the commit touches supporting modules.

Because every commit lands on `main`, every commit must leave the app in a shippable state. If the
feature isn't ready for users, it must be behind a feature flag. A commit that breaks `main` is
unacceptable regardless of how correct the code is.

For staging safety and commit mechanics, see [
`docs/reference/standards/git-guidelines.md`](./docs/reference/standards/git-guidelines.md).

## Non-Negotiable Principles

### You Touch It, You Fix It

If you modify ANY file, leave it better than you found it. Fix all lint violations, compile errors,
and broken tests in that file. No exceptions — "that was already there" is not acceptable.

### Honesty Over Completion

Never claim success with unresolved errors. Never lie by omission. Always check exit codes —
non-zero is a failure. If you cannot complete a task, say so clearly rather than producing broken
output.

### Commit Messages Describe User Impact

Commits are changelog entries. Think in terms of features, not layers. Scope to the feature the user
interacts with, not the internal module that happens to contain the code. Generic scopes like "
client" or "domain" are not valid — pick the specific feature scope.

Use [`docs/reference/standards/commit-messages.md`](./docs/reference/standards/commit-messages.md)
as the source of truth for commit message structure and detail. Follow that guide when writing
commit messages.

- `feat` and `fix` are reserved for changes that affect end-user behavior. If the user wouldn't
  notice the difference, use `refactor`, `chore`, or `docs` instead.
- Write `feat` commits as if consumed by the general public. The test: "Can I do something different
  because of this?"
- Focus on behavior, not implementation. Capitalize proper nouns (API, Kotlin, Android, Compose,
  Gradle, etc.).

### Minimal, Focused Changes

Keep modifications focused on the task. Don't add comments that restate code. Don't refactor
surrounding code unless asked. Don't over-engineer.

### Android Device Safety

Physical Android devices are forbidden test and deployment targets for agent-driven work in this
repository.

- Never run `./gradlew :app:android-main:connectedDebugAndroidTest`. This task is forbidden in this
  repository, regardless of target selection, and must not be used as a shortcut for emulator or
  device validation.
- Never run `connected*AndroidTest`, `install*`, `adb install`, `adb shell am instrument`,
  `adb uninstall`, `adb shell pm clear`, or any other `adb` command against a physical device.
- Never suggest or run app-data-destructive commands for any LogDate package — the current
  `studio.hypertext.logdate` and the retired `co.reasonabletech.logdate`, which may still be
  installed with real data (for example `adb uninstall`, `adb shell pm uninstall`,
  `adb shell pm clear`, `adb shell cmd package uninstall`,
  `adb shell rm -rf /data/data/studio.hypertext.logdate`, or Gradle `uninstall*` tasks).
- Only use Android emulators or Gradle Managed Devices for Android app installs, instrumentation
  tests, UI tests, screenshots, benchmarks, and manual validation.
- If Android instrumentation coverage is needed, use an emulator-only or Gradle Managed Device task
  such as `:app:android-main:smokeDevicesGroupDebugAndroidTest` instead of
  `:app:android-main:connectedDebugAndroidTest`.
- Before any Android command that could talk to a device, verify the target is safe. A safe target
  is:
    - an emulator with an `adb` serial that starts with `emulator-`, or
    - a Gradle Managed Device started by Gradle for the current task.
- If any connected target is a physical device, do not use it. Stop immediately and either:
    - run the task on an emulator,
    - run the task on a Gradle Managed Device, or
    - ask the developer to disconnect the physical device before proceeding.
- Convenience is irrelevant here. If there is any uncertainty about the target device type, treat it
  as unsafe and do not run the command.
- A user must explicitly override this in the current conversation before any physical-device
  interaction is allowed. Repository defaults forbid it.

## Key References

| Topic                     | Location                                                                                                                                    |
|---------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Build commands            | [`./run help`](./run)                                                                                                                       |
| Product purpose & positioning | [`docs/product-info/product-positioning.md`](./docs/product-info/product-positioning.md) |
| Architecture & module map | [`README.md`](./README.md) and module `README.md` files                                                                                     |
| Commit message format     | [`docs/reference/standards/commit-messages.md`](./docs/reference/standards/commit-messages.md)                                              |
| Valid commit scopes       | [`allowed-scopes.txt`](./allowed-scopes.txt) and [`docs/reference/standards/commit-scopes.md`](./docs/reference/standards/commit-scopes.md) |
| Git workflow & safety     | [`docs/reference/standards/git-guidelines.md`](./docs/reference/standards/git-guidelines.md)                                                |
| Entrypoint structure & size limits | [`docs/reference/standards/entrypoint-structure.md`](./docs/reference/standards/entrypoint-structure.md) |

## Code Conventions

- **Logging**: Napier only. Never `System.out`, `println`, or `Log.d`.
- **UUIDs**: `kotlin.uuid.Uuid` with `Uuid.random()`, not Java's UUID.
- **Dates**: `kotlinx.datetime.Instant`.
- **Serialization**: `kotlinx.serialization`.
- **DI**: Constructor injection with Koin.
- **State**: Sealed classes/interfaces for UI state.
- **Error handling**: try-catch with Napier. Prefer nullable returns over exceptions.
- **Imports**: `kotlin.*` > `androidx.*`/`kotlinx.*` > `app.logdate.*`. No wildcards.

## Home workspace visual contract

Home destinations and their immediate details compose independently responsive panels. Use
`WorkspaceScaffold`, `AdaptiveWorkspaceLayout`, `WorkspacePanel`, `PanelHeader`, `PanelGroup`,
`WorkspaceSupportingSheet`, and `WorkspacePlaybackLayout` from `client/ui/.../workspace`.
Surface tokens belong to `client/theme/WorkspaceTokens.kt`.

| Meaning | Token |
| --- | --- |
| Workspace canvas/navigation | surfaceContainer |
| Working panel | surface/onSurface |
| Subordinate meaningful group | surfaceContainerLow |
| Raised control/transient surface | surfaceContainerHigh |
| Selected item | secondaryContainer/onSecondaryContainer |

The shell owns destination identity, navigation, global status, system insets, and contextual creation.
Composition owns fit, ordering, hinges translated into content coordinates, and panel placement.
Panels own surface, placement-aware corners, framing, and measured local bounds. Features own
content/actions/state and must respond to panel bounds, never global window classes or hinge splits.
Headers inherit their panel. Focus does not recolor a whole panel. Use 16dp framing/gutters,
24dp sections, 8dp related elements, and 12dp groups through existing `Spacing` tokens.
Do not wrap a whole Home route/navigation shell in another panel or add a competing destination app bar.
Extend the shared primitive for new visual behavior; narrowly document reviewed exceptions in
`config/workspace/home-allowlist.txt`. Register new route/panel roots in `home-roots.txt`.

`HOME_WORKSPACE_V2` gates migration presentation only; it must not change data or recording settings.
Keep it disabled until populated shared-shell scenes and runtime acceptance pass. Preserve the
flag-off presentation until rollout is accepted. Run `checkHomeWorkspaceContract` for relevant changes.

UI acceptance requires inspecting populated renders at matching phone/tablet sizes and a consolidated
comparison sheet, plus compact phone, landscape, book/tabletop, dark/dynamic colors, RTL, 200% text,
and reduced motion. Empty media fixtures or successful render tasks with reported render errors are
not acceptance. Native Google Maps must be verified on an emulator/Managed Device; deterministic
preview geometry proves layout only. Required map attribution must remain visible above supporting content.

WorkspaceRouteFrame supplies shared safe insets and adaptive placement for direct links and nested immediate details. Inside WorkspaceScaffold it is a pass-through; route decorators must not create a second host. LocalWorkspaceHosted indicates actual ownership, separately from feature enablement.

Workspace search uses `WorkspaceAppBar` and `WorkspaceSearchBar` outside the panels. Search aligns with the leading content edge on phones and wider workspaces; the account action stays at the trailing edge. Navigation and the accessible pane title identify the destination without a competing header label. The default search action opens the existing unified search route. Collection filters remain panel content; never add another destination search bar inside a collection or viewer. Omit redundant headings such as “Your days,” “Your latest story,” and “Previous stories”; retain meaningful dates and individual story titles.

Use `WorkspaceSearchScope` for a bounded contextual search in the single shared header (Your places),
and release it on disposal; do not introduce a second panel field. Default search opens unified search.
Keep search and account actions on one app-bar row, including compact screens and large text.
Use `WorkspaceSectionSwitch` as a compact selector in the supporting panel header. Use the shared
`WorkspaceSupportingSheet` header/actions slots; never put day/place modes in a separate row above
the map or stretch them into full-width tabs. The Home destination label is Places; preserve its
existing location-history route keys. `PanelContainment.Collection` supplies measured panel bounds without painting
another surface behind already contained objects. Retain a working surface for Locations > Your day,
for reading, and for phone sheets over maps. `workspaceControlBackdrop` paints a raised sticky-control background only while
content overlaps it. Expanded navigation uses a 240dp sidebar from 1200dp in standard posture;
intermediate widths use the rail, phones/tabletop use bottom navigation, and book posture retains
compact navigation. Standalone Timeline uses `PanelConstraints.ReadingCollection` (560dp maximum);
beside an opened day it uses the shared browse constraints. Composition must enforce panel maxima,
including hinge-safe regions, instead of leaving an oversized empty surface around bounded content.

Use `WorkspaceAccountAction` for the shared account target and quiet status marker. Backup status,
recovery actions, streak, and settings belong to its on-demand menu. Do not add separate streak/sync
buttons or permanent backup banners to the shared header. Visual/supporting composition must fit
both minimum widths and a useful dominant visual area; medium portrait should use the transforming
supporting sheet rather than forcing two narrow columns. Grid/media sizes respond to panel-local
bounds. Preserve each destination's established browsing and playback interactions while migrating framing.
