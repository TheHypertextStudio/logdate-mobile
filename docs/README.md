# LogDate Documentation

This index lists where each kind of documentation lives. Module-specific documentation lives in each
module's `README.md`; start with [`app/README.md`](../app/README.md) and the module READMEs under
`client/`.

## Where to look

| Folder                                                | What it holds                                                             | Start with                                                                                                                                            |
| ----------------------------------------------------- | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| [`explanation/`](explanation/)                        | Concepts: how a system works and why it was built that way                | [Encryption](explanation/encryption.md), [Private storage](explanation/private-storage.md)                                                            |
| [`reference/`](reference/)                            | How things work today: architecture, platform integration, and standards  | [Development standards](reference/standards/README.md)                                                                                                |
| [`feature-design/`](feature-design/)                  | Product behavior and design decisions for a feature                       | [Android OS integration](feature-design/android-os-integration.md)                                                                                    |
| [`testing/`](testing/)                                | How to write and run each kind of test                                    | [Testing introduction](testing/introduction.md), [Screenshot tests](testing/screenshot-tests.md)                                                      |
| [`audits/`](audits/)                                  | Checklists that track a feature area against a quality bar, with evidence | [Android OS integration checklist](audits/android-os-integration-checklist.md), [Foldables checklist](audits/foldables-adaptive-quality-checklist.md) |
| [`launch/`](launch/)                                  | Release gates and launch readiness                                        | [Android public release](launch/android-public-release.md)                                                                                            |
| [`environment/`](environment/)                        | Setting up a development machine                                          | [Setup](environment/setup.md)                                                                                                                         |
| [`performance/`](performance/)                        | Benchmarks and performance plans                                          | [Performance index](performance/README.md)                                                                                                            |
| [`observability/`](observability/)                    | Logging, metrics, and error reporting                                     |                                                                                                                                                       |
| [`runbook/`](runbook/)                                | Operational procedures                                                    | [Handle resolution](runbook/handle-resolution.md)                                                                                                     |
| [`plans/`](plans/) and [`superpowers/`](superpowers/) | Dated implementation plans and design specs                               |                                                                                                                                                       |

## Reference highlights

- [Android OS integration guide](reference/android/os-integration.md): shortcuts, tiles, Live Updates,
  widgets, bubbles, keyboard and pointer input, desktop windowing, and AppFunctions
- [Home workspace](reference/architecture/home-workspace.md): the adaptive panel system behind Home
- [Navigation architecture](navigation-architecture.md)
- [Sync architecture](reference/sync-architecture.md) and
  [personal space sync](reference/personal-space-sync.md): how data moves between devices and the
  server, and the guarantees that protect it
- [Encryption](explanation/encryption.md) and [encryption formats](reference/encryption-formats.md):
  what is protected from whom, and the exact bytes on disk and on the wire
- [Private storage](explanation/private-storage.md) and [recovery keys](explanation/recovery-keys.md):
  why private data is sealed, and how a person keeps control of their identity
- [AT Protocol interoperability](explanation/atproto-interoperability.md),
  [identity custody](explanation/atproto-identity-custody.md) and
  [OAuth sign-in](reference/atproto-oauth-sign-in.md)
- [Wear OS companion](explanation/wear-companion.md) and the
  [watch sync protocol](reference/wear/sync-protocol.md)
- [Android media storage](reference/android-media-storage.md)
- [Google Play publishing](reference/google-play-publishing.md)
- [Commit messages](reference/standards/commit-messages.md) and [commit scopes](reference/standards/commit-scopes.md)

## Writing documentation

- **Write for someone new to the codebase.** Explain the concept before the API, say why a rule
  exists, and link to the code by path.
- **Keep docs next to their change.** A commit that changes behavior updates the guide, the
  checklist row, and the module README in the same commit.
- **Show, don't describe.** Embed committed screenshots instead of describing what a screen looks
  like.
- **Write commands that run.** Use `./run` commands where they exist. Every device command must
  target an emulator or a Gradle Managed Device, never a physical device.
