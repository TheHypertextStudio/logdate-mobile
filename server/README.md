# `:server`

The LogDate server is a Ktor application on Netty. It stores the data that LogDate clients sync,
keeps encrypted backups, authenticates accounts, and hosts an AT Protocol identity and repository
for each account. The apps work without it; a person who signs in to LogDate Cloud, or a
self-hoster who points the apps at their own server, gets sync and backup through it.

## What the server does

- **Accounts and sign-in.** Accounts are created and signed in with passkeys (WebAuthn) or a Google
  ID token, and clients hold short-lived JWT access tokens with refresh tokens. Email verification,
  restore credentials, linked devices and watches, and a per-account key vault for encrypted data
  live alongside sign-in.
- **Sync.** Clients push and pull journals, entries, associations, drafts, moments, media, and
  location history under `/api/v1`. Every record carries a server version, so a client asks what
  changed since the last version it saw. Deletions are kept as tombstones until a maintenance job
  purges them. Accounts created while personal spaces are enabled store their data as sealed
  records in a personal space under `app.logdate.space.*` instead; `/api/v1/space-migration` moves
  an account from the first storage mode to the second.
- **Backup.** Clients upload encrypted backup archives and download them to restore on a new
  device.
- **Identity.** Each account has a handle and a `did:plc` identifier. The server signs and stores
  the identity's operation log and can publish it to a PLC directory when
  `ATPROTO_PLC_PUBLISH_ENABLED` is on.
- **AT Protocol hosting.** The server is a personal data server for its accounts. It serves the
  `com.atproto.server`, `com.atproto.repo`, `com.atproto.sync`, and `com.atproto.identity` XRPC
  methods, `/.well-known` handle and DID documents, and an OAuth authorization server with PAR,
  DPoP, and passkey approval. The repository event stream (`subscribeRepos`) runs only when
  `ATPROTO_FIREHOSE_ENABLED` is on.
- **Plans and quota.** Billing events from App Store Server Notifications and Stripe update an
  account's entitlements, and quota checks gate uploads.
- **Supporting services.** Cloud transcription sessions, Apple push notification delivery, diagnostic
  report uploads, and audit logging.

The interactive API reference is served at `/docs`, with the OpenAPI document at `/openapi.json`
and `/openapi.yaml`, and `GET /health` reports readiness. [`docs/openapi.md`](docs/openapi.md)
explains how the reference is generated and maintained.

## Structure

The code is in `src/main/kotlin/app/logdate/server`. `Application.kt` assembles the server one
concern per line, and each package holds one area.

| Package                                                      | Responsibility                                                                                                      |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- |
| `routes/`                                                    | Route registration for every HTTP surface, plus the route documentation in `routes/docs/`                           |
| `auth/`, `passkeys/`                                         | Accounts, tokens, sessions, email verification, WebAuthn, and passkey step-up                                       |
| `oauth/`                                                     | The AT Protocol OAuth authorization server                                                                          |
| `identity/`, `atproto/`                                      | Handles, DIDs, PLC operations, signing keys, recovery keys, hosted repositories, and the event stream               |
| `sync/`, `logdate/`                                          | The sync repositories, backups, media, blob storage, and the repository-backed journal records                      |
| `space/`, `migration/`, `transfer/`                          | Personal spaces and the migration of an account into one                                                            |
| `devices/`, `enrollment/`, `accountkeys/`                    | Linked devices and their sessions, and encrypted account key envelopes                                              |
| `crypto/`                                                    | Encryption of stored payloads                                                                                       |
| `billing/`, `entitlements/`                                  | Billing notifications, plans, entitlements, and quota                                                               |
| `notifications/`, `transcription/`, `diagnostics/`, `audit/` | Push delivery, transcription, diagnostic reports, and audit events                                                  |
| `database/`, `di/`                                           | Exposed tables and repositories, the connection pool, migrations, and Koin wiring, including `DatabaseBootstrap`    |
| `config/`, `logging/`, `ratelimit/`, `openapi/`              | Runtime profiles and production config validation, logging and error reporting, rate limits, and OpenAPI generation |

Persistence uses Exposed over PostgreSQL, with Flyway migrations as the schema; see
[`DATABASE.md`](DATABASE.md). The server depends on `shared/model`, `shared/config`, `shared/space`,
`shared/lexicon`, and the `shared/atproto-*` libraries, so the clients and the server read and write
the same types.

## Run it locally

The server needs PostgreSQL. From the repository root:

```bash
./run start     # PostgreSQL in Docker, on port 15432
./run server    # Ktor server on http://localhost:8765
```

`./run` loads the connection settings from `docs/environment/.env.example`. To run Gradle yourself,
export them first:

```bash
export DATABASE_URL="jdbc:postgresql://localhost:15432/logdate"
export DATABASE_USER=logdate
export DATABASE_PASSWORD=logdate
./gradlew :server:run
```

The database user and password are always required; the server has no default password. In the
development profile, which is the default, migrations run at startup, and a missing `JWT_SECRET`
becomes a random secret for that process, so tokens stop working on restart. A database
failure during startup stops the server unless `LOGDATE_ALLOW_INMEMORY_FALLBACK=true`, which runs on
in-memory repositories that lose everything on exit; [`DATABASE.md`](DATABASE.md) has the details.

The settings that matter most:

| Variable                                                        | Purpose                                                                                                                                                                                                       |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `LOGDATE_ENV`                                                   | `development` (default), `test`, or `production`. Production validates secrets and configuration at startup and refuses to start when they are missing or unsafe.                                             |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`            | PostgreSQL connection. `INSTANCE_CONNECTION_NAME` and `DB_NAME` select a Cloud SQL instance instead.                                                                                                          |
| `AUTO_MIGRATE`                                                  | Whether startup applies migrations. On except in production.                                                                                                                                                  |
| `PORT`, `HOST`                                                  | Listen address. The default port is 8765. The container images set `PORT=8080`.                                                                                                                               |
| `JWT_SECRET`                                                    | Signs access tokens. Required in production.                                                                                                                                                                  |
| `LOGDATE_BLOB_STORAGE_DIR`, `GCS_PROJECT_ID`, `GCS_BUCKET_NAME` | Where media and backups are stored. With none of them set, media and backups are held in memory and lost when the process exits. Production startup requires `GCS_BUCKET_NAME` or `LOGDATE_BLOB_STORAGE_DIR`. |
| `WEBAUTHN_RP_ID`, `WEBAUTHN_ORIGIN`                             | Passkey relying party for the server's domain.                                                                                                                                                                |
| `ATPROTO_PDS_SERVICE_URL`, `ATPROTO_HANDLE_DOMAIN`              | The public address and handle domain for hosted identities.                                                                                                                                                   |
| `LOGDATE_PERSONAL_SPACE_ENABLED`                                | Starts new accounts on personal-space storage.                                                                                                                                                                |

[`docs/environment-variables.md`](docs/environment-variables.md) lists every variable with its
default and whether production requires it. The API documents are
[`docs/auth-v1-api.md`](docs/auth-v1-api.md), [`docs/sync-v1-api.md`](docs/sync-v1-api.md),
[`docs/location-history-api.md`](docs/location-history-api.md), and
[`docs/audit-schema.md`](docs/audit-schema.md).

## Test it

```bash
./run test:server                                # ./gradlew :server:test
./gradlew :server:check                          # tests, OpenAPI validation, and the 90% coverage gate
./gradlew :integration:server-client-e2e:test    # the real client against the real server
```

Server tests can run without PostgreSQL, using in-memory repositories and an H2 test dependency. The `*PostgresTest` suites run against
a real PostgreSQL only when `LOGDATE_DIAGNOSTIC_TEST_DISPOSABLE=true` and
`LOGDATE_DIAGNOSTIC_TEST_DATABASE_URL` points at a disposable database on `localhost`; otherwise they are
skipped. `./gradlew :server:generateOpenApi` writes the OpenAPI documents to `server/build/openapi`,
and `:server:validateOpenApi` checks them.

## Deployment

The server is built into a container image from the repository's `Dockerfile` and runs on Google
Cloud Run, with Cloud SQL for PostgreSQL and Cloud Storage for media and backups. Staging deploys
after CI passes on `main`; production deploys from a `server-v*` tag on a commit already running in
staging. A deploy applies the Flyway migrations as a separate step before the new revision starts,
because production runs with `AUTO_MIGRATE=false`. The Terraform for the environments is in
`infra/terraform`. See [Release and recovery](../docs/RELEASE.md),
[Staging and production configuration](../docs/runbook/staging-production-configuration.md),
[`docs/google-cloud-architecture.md`](docs/google-cloud-architecture.md), and
[`docs/google-cloud-production.md`](docs/google-cloud-production.md).
