# LogDate Agent Guidelines

> Principles, workflow, and non-negotiables for any AI agent working in this repository.

## Development Workflow

Every change lands on `main` through a pull request merged with **Rebase and merge**, the same way
every Hypertext Studio repository lands work. Work happens on a short-lived branch; there are no
long-lived feature branches, no merge commits, and no direct pushes to `main`. A rebase merge puts
each commit on `main` as-is, which demands extreme commit discipline — every commit must leave
`main` in a shippable state. Incomplete features are gated behind feature flags, not hidden in
branches.

Every change follows this process. No exceptions.

### 1. Understand

Before writing any code, determine:

- **What type of change is this?** Feature (`feat`), bug fix (`fix`), or maintenance (`chore`, including refactoring
  and documentation)? This determines the commit type.
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

Each feat/fix commit describes one complete user-visible outcome and forms a product changelog entry.
Maintenance uses chore with an informative body. Scope the complete change to its registered product
domain, even when it touches multiple supporting modules.

Because every commit lands on `main`, every commit must leave the app in a shippable state. If the
feature isn't ready for users, it must be behind a feature flag. A commit that breaks `main` is
unacceptable regardless of how correct the code is.

For staging safety and commit mechanics, see [
`docs/reference/standards/git-guidelines.md`](./docs/reference/standards/git-guidelines.md).

### 8. Land Through a Pull Request

Rebase the branch onto `origin/main` (never merge `main` into it), push it, and open a pull request
against `main`. Write the description from the repository's pull request template, or use
`## TL;DR`, `## Changes`, and `## Follow-ups and Next Work` when there is none. Once CI passes,
merge with **Rebase and merge** so history stays linear. Agents ask the developer before pushing a
branch or opening a pull request.

The full flow, including conflict handling under the hooks, is in
[Branch and Pull Request Workflow](./docs/reference/standards/git-guidelines.md#branch-and-pull-request-workflow).

## Commit policy authority

Use the reviewed `.hypertext/commits.json` domain boundaries and the current Commit Message Standards.
All commits require a registered scope and exactly `feat`, `fix`, or `chore`; old module/activity
examples are historical. Preserve existing hooks until supported canonical composition is activated.
A registry file or independent review alone does not authorize hook activation or protected CI changes.

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
  notice the difference, use `chore` instead, with an informative body.
- Write `feat` commits as if consumed by the general public. The test: "Can I do something different
  because of this?"
- Focus on behavior, not implementation. Capitalize proper nouns (API, Kotlin, Android, Compose,
  Gradle, etc.).

### Minimal, Focused Changes

Keep modifications focused on the task. Don't add comments that restate code. Don't refactor
surrounding code unless asked. Don't over-engineer.

### Background automation and foreground focus

The Studio company standard requires routine agent, build, test, and rendering work to
preserve the operator's active app, keyboard focus, and Spaces/desktops. Use background
commands, APIs, files, and supported offscreen/headless modes. Never open or activate an
app, browser, visible terminal, or emulator window as an implicit build/test step.

LogDate's shared root Gradle configuration sets `java.awt.headless=true` on the
offscreen `desktopTest` JVMs. Preserve that worker setting; a flag on the Gradle daemon
alone is insufficient. Tests that genuinely require a display need a separate interactive
task, explicit opt-in for the run, and authorization for visible interaction. Do not
silently fall back to headed execution or claim headless renders prove native-window
coverage. Run Android emulators without a window where supported and retain all device
and app-data protections below. Report unrun interactive checks as unverified.

For the shared policy and configuration-only Gradle check, see Hypertext Studio
`product-standards/docs/repository-standards.md`, **Background automation**, and
`product-standards/docs/product-standards.md`, **Background automation and foreground focus**.

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
- Use Gradle Managed Devices for every Android device run: instrumentation tests, UI tests,
  screenshots, benchmarks, and manual validation, as the Studio device testing standard requires.
  The devices and groups are listed in [`docs/DEVELOPMENT.md`](./docs/DEVELOPMENT.md#devices).
  Never create a hand-made emulator.
- If Android instrumentation coverage is needed, use an emulator-only or Gradle Managed Device task
  such as `:app:android-main:smokeDevicesGroupDebugAndroidTest` instead of
  `:app:android-main:connectedDebugAndroidTest`.
- Before any Android command that could talk to a device, verify the target is safe. A safe target
  is:
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

| Topic                              | Location                                                                                                                                              |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Build commands                     | [`./run help`](./run)                                                                                                                                 |
| Architecture & module map          | [`README.md`](./README.md) and module `README.md` files                                                                                               |
| Commit message format              | [`docs/reference/standards/commit-messages.md`](./docs/reference/standards/commit-messages.md)                                                        |
| Valid commit scopes                | [`.hypertext/commits.json`](./.hypertext/commits.json) and [`docs/reference/standards/commit-scopes.md`](./docs/reference/standards/commit-scopes.md) |
| Git workflow & safety              | [`docs/reference/standards/git-guidelines.md`](./docs/reference/standards/git-guidelines.md)                                                          |
| Entrypoint structure & size limits | [`docs/reference/standards/entrypoint-structure.md`](./docs/reference/standards/entrypoint-structure.md)                                              |

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

| Meaning                          | Token                                   |
| -------------------------------- | --------------------------------------- |
| Workspace canvas/navigation      | surfaceContainer                        |
| Working panel                    | surface/onSurface                       |
| Subordinate meaningful group     | surfaceContainerLow                     |
| Raised control/transient surface | surfaceContainerHigh                    |
| Selected item                    | secondaryContainer/onSecondaryContainer |

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
It is enabled by default and stays as a presentation rollback switch; keep the flag-off presentation
working until the switch is retired. Run `checkHomeWorkspaceContract` for relevant changes.

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
for reading, for the Journals collection, and for phone sheets over maps. `workspaceControlBackdrop` paints a raised sticky-control background only while
content overlaps it. Expanded navigation uses the expanded wide navigation rail from 1200dp in standard posture;
intermediate widths use the rail, phones use the short navigation bar (icons beside labels in tabletop posture
and in landscape, where height is under 480dp), and book posture retains compact navigation. Standalone Timeline uses `PanelConstraints.ReadingCollection` (560dp maximum);
beside an opened day it uses the shared browse constraints. Composition must enforce panel maxima
instead of leaving an oversized empty surface around bounded content, except that each half of a
vertical hinge is filled so neither the navigation nor the fold has a gap beside it.

Use `WorkspaceAccountAction` for the shared account target and quiet status marker. Backup status,
recovery actions, streak, and settings belong to its on-demand menu. Do not add separate streak/sync
buttons or permanent backup banners to the shared header. Visual/supporting composition must fit
both minimum widths and a useful dominant visual area; medium portrait should use the transforming
supporting sheet rather than forcing two narrow columns. Grid/media sizes respond to panel-local
bounds. Preserve each destination's established browsing and playback interactions while migrating framing.

## Settings UI contract

Before changing settings UI, read [Settings UI composition](docs/reference/standards/settings-ui.md)
and compare the main Settings page's existing composition. Emit individual rows directly inside
`SettingsSection`; a `Column` around multiple rows suppresses the container's automatic gaps.
Use functional wrappers (`SettingsNavigationItem`, `ToggleSettingsItem`, `SettingsChoiceItem`,
`LinkedToggleSettingsItem`, `SimpleSettingsItem`) before their shared `SettingsRow` base.
Only `SettingsRow` implements Material 3 `ListItem` for settings; supporting text inherits its
`bodyMedium` role. Use theme tokens. Extend shared state fixtures and inspect
matching renders, including visible platform-specific rows, before accepting screenshot references.
Run `:client:feature:core:checkSettingsUiContract` for settings changes.
