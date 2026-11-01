# Commit Scope Reference

[`.hypertext/commits.json`](../../../.hypertext/commits.json) is the current domain registry after reviewed adoption. Each entry defines its description, includes, excludes and explicit technical applicability. Choose the feature/domain served by the complete change; module paths do not determine the scope.

Every new commit has a registered scope. Use shared `auth`, `billing`, `search`, and `notifications` for their actual capabilities. `sync` includes backup eligibility/progress and sync-queue conflict recovery. `restore` owns authenticated archive download, handoff and restoration. General notification delivery belongs to `notifications`; reminder triggers remain `reminders`. Settings include confirmed local-data erasure and exclude cloud-account deletion.

No omitted scope, activity name or generic layer/platform catchall is allowed. Maintenance uses the domain it supports. `account` and `passkeys` map to `auth` for new work. Keep historical names in the legacy list and published commits; do not rewrite them to retrofit policy.

| Scope               | Domain                                  |
| ------------------- | --------------------------------------- |
| `auth`              | Accounts and sign-in                    |
| `billing`           | Subscriptions and purchases             |
| `editor`            | Writing journal entries                 |
| `export`            | Exporting journal data                  |
| `restore`           | Restoring journal data                  |
| `profile`           | Personal profiles                       |
| `settings`          | App preferences and local data controls |
| `journal`           | Journal collections                     |
| `library`           | Media library                           |
| `location-timeline` | Places and location history             |
| `onboarding`        | Getting started                         |
| `rewind`            | Revisiting memories                     |
| `search`            | Finding entries and memories            |
| `timeline`          | Entry timeline                          |
| `events`            | Events and linked memories              |
| `postcards`         | Postcards                               |
| `stickers`          | Personal stickers                       |
| `android-widget`    | Home-screen memories                    |
| `streaks`           | Journaling streaks                      |
| `reminders`         | Journaling reminders                    |
| `wear`              | Watch journaling                        |
| `health-connect`    | Health context                          |
| `intelligence`      | Suggestions and insights                |
| `location`          | Location capture                        |
| `media`             | Capturing and playing media             |
| `sharing`           | Sharing journal content                 |
| `sync`              | Cloud backups and synchronization       |
| `notifications`     | Notification delivery                   |
| `activitypub`       | ActivityPub integration library         |
| `atproto`           | AT Protocol libraries                   |

Only the existing reusable ActivityPub and AT Protocol library domains are technical. A technical flag does not excuse implementation-heavy prose for consumer behavior. Registry changes require independent delegated-maintainer review of necessity, boundaries and technical applicability; authors cannot approve their own additions or manufacture an approval digest.

<details>
<summary>Historical module-to-scope lookup (superseded)</summary>

The following lookup and examples interpret older commits only. They are not current instructions and do not override the registry.

# Commit Scope Reference

> **Authoritative lookup table for all module commit scopes**

Zero ambiguity: Look up the exact scope for the module you're modifying. Just follow the tables below.

## Feature Scopes (Primary)

These are the main scopes for user-facing features. **Prefer these scopes** for most commits.

| Directory                                                                              | Scope               |
| -------------------------------------------------------------------------------------- | ------------------- |
| `client/feature/core/` (account)                                                       | `account`           |
| `client/feature/core/` (export)                                                        | `export`            |
| `client/domain/` (restore)                                                             | `restore`           |
| `client/feature/core/` (profile)                                                       | `profile`           |
| `client/feature/core/` (settings)                                                      | `settings`          |
| `client/feature/editor/`                                                               | `editor`            |
| `client/feature/journal/`                                                              | `journal`           |
| `client/feature/location-timeline/`                                                    | `location-timeline` |
| `client/feature/onboarding/`                                                           | `onboarding`        |
| `client/feature/passkeys/`                                                             | `passkeys`          |
| `client/feature/rewind/`                                                               | `rewind`            |
| `client/feature/search/`                                                               | `search`            |
| `client/feature/timeline/`                                                             | `timeline`          |
| `client/feature/android-widgets/`                                                      | `android-widget`    |
| `client/domain/` (streak), streak UI in any feature                                    | `streaks`           |
| `client/domain/` (reminders), reminder settings and delivery on the phone or the watch | `reminders`         |

## Apps Scopes

| Directory   | Scope    |
| ----------- | -------- |
| `app/wear/` | `wear`   |
| `server/`   | `server` |

## Client Library Scopes

| Directory                | Scope            |
| ------------------------ | ---------------- |
| `client/auth/`           | `auth`           |
| `client/billing/`        | `billing`        |
| `client/data/`           | `data`           |
| `client/database/`       | `database`       |
| `client/datastore/`      | `datastore`      |
| `client/device/`         | `device`         |
| `client/health-connect/` | `health-connect` |
| `client/intelligence/`   | `intelligence`   |
| `client/location/`       | `location`       |
| `client/media/`          | `media`          |
| `client/networking/`     | `networking`     |
| `client/permissions/`    | `permissions`    |
| `client/repository/`     | `repository`     |
| `client/sensor/`         | `sensor`         |
| `client/sharing/`        | `sharing`        |
| `client/sync/`           | `sync`           |
| `client/theme/`          | `theme`          |
| `client/ui/`             | `ui`             |
| `client/util/`           | `util`           |

## Shared Module Scopes

| Directory                      | Scope         |
| ------------------------------ | ------------- |
| `shared/activitypub/`          | `activitypub` |
| `shared/atproto-bom/`          | `atproto`     |
| `shared/atproto-crypto/`       | `atproto`     |
| `shared/atproto-identity/`     | `atproto`     |
| `shared/atproto-lexicon/`      | `atproto`     |
| `shared/atproto-licensing/`    | `atproto`     |
| `shared/atproto-oauth-client/` | `atproto`     |
| `shared/atproto-pds/`          | `atproto`     |
| `shared/atproto-pds-runtime/`  | `atproto`     |
| `shared/atproto-plc/`          | `atproto`     |
| `shared/atproto-repo/`         | `atproto`     |
| `shared/atproto-syntax/`       | `atproto`     |
| `shared/atproto-xrpc/`         | `atproto`     |

## Cross-Cutting Changes

| Scenario                                                       | Scope                          |
| -------------------------------------------------------------- | ------------------------------ |
| Multiple modules in different categories                       | Omit scope or use `infra`      |
| Changes to build/tooling                                       | `build` or `infra`             |
| Error reporting, crash monitoring, logging infra (e.g. Sentry) | `observability`                |
| Changes affecting entire platform                              | `platform` or omit             |
| Root-level config/docs                                         | omit scope (e.g., `docs: ...`) |
| iOS-specific changes across modules                            | `ios`                          |
| Android-specific changes across modules                        | `android`                      |
| Desktop-specific changes across modules                        | `desktop`                      |

## Examples

```bash
# Feature modules (preferred for user-facing changes)
git commit -m "feat(editor): add voice recording playback controls"
git commit -m "fix(timeline): correct date grouping for notes"
git commit -m "refactor(onboarding): simplify permission request flow"

# Client libraries (for infrastructure/data layer changes)
git commit -m "feat(sync): implement conflict resolution for notes"
git commit -m "fix(database): handle migration edge case"
git commit -m "refactor(data): extract validation use cases"

# Cross-cutting
git commit -m "chore(infra): update gradle wrapper version"
git commit -m "docs: update architecture guide"

# Platform-specific
git commit -m "fix(android): resolve permission handling on API 34"
git commit -m "feat(ios): add haptic feedback support"
```

## Related

- [Commit Message Standards](./commit-messages.md)
- [Git Guidelines](./git-guidelines.md)

</details>
