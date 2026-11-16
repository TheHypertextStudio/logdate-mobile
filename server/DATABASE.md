# Server database

The LogDate server stores its data in PostgreSQL. It reaches the database through a HikariCP
connection pool and queries it with Exposed. Flyway owns the schema: the SQL files in
`src/main/resources/db/migration` are the only thing that creates or changes tables, and nothing at
runtime reconciles the schema with the Exposed table definitions in
`src/main/kotlin/app/logdate/server/database`, so a change to one needs the matching change to the
other in the same commit.

The local development database is PostgreSQL 16 in Docker (`./run start`); Cloud SQL for
PostgreSQL 16 is the deployed one.

## Connecting

`DatabaseConfig.createDataSource` picks the connection from the environment, in this order:

1. `DATABASE_URL`, as a JDBC URL (`jdbc:postgresql://host:5432/logdate`) or a `postgres://` or
   `postgresql://` URL that carries the user and password.
2. `INSTANCE_CONNECTION_NAME` with `DB_NAME`, which connects to Cloud SQL through the Cloud SQL
   socket factory using the runtime's Application Default Credentials.
3. Outside production only, `DB_HOST`, `DB_PORT`, and `DB_NAME`, which default to
   `localhost:5432/logdate`.

The user and password come from `DATABASE_USER` and `DATABASE_PASSWORD` (or `DB_USER` and
`DB_PASSWORD`) unless the URL embeds them. There is no default password: a missing credential stops
startup with a message naming the variable. The pool holds at most 16 connections, matching the
requests Cloud Run sends to one instance at a time, keeps no minimum number of idle connections, and closes idle ones after five minutes.
[`docs/environment-variables.md`](docs/environment-variables.md) describes every variable.

For local work, `./run start` starts PostgreSQL in Docker on port 15432 with the user, password,
and database `logdate`, and `./run server` connects to it. `./run dev:reset` deletes that data and
starts again.

## How migrations run

Migrations are plain SQL files named `V<version>__<Description>.sql`. Flyway applies them in
version order and records each one, with a checksum, in the `flyway_schema_history` table, so a
migration runs once per database and an applied file must never be edited.

Whether the server applies them at startup depends on `AUTO_MIGRATE`:

- In development and test, it defaults to on. Starting the server brings a new or outdated database
  up to date.
- In production, it defaults to off. A deployment applies migrations in a separate step before the
  new revision starts, by running the Flyway container against the same database
  (`scripts/run-migrations.sh`, called from the Cloud Run deploy workflow). A restart then never
  changes the schema, and two revisions that overlap during a rollout do not compete for the Flyway
  lock.

`AUTO_MIGRATE` set to `true`, `1`, or `yes` enables migrations and `false`, `0`, or `no` disables them, in either profile. Any other value uses the profile default.

### The beforeMigrate script

`db/migration/beforeMigrate.sql` is a Flyway callback that runs before every `migrate`. It creates
the four legacy sync tables, `sync_content`, `sync_journals`, `sync_associations`, and `sync_media`,
if they do not exist.

The script exists because migrations V2, V3, and V4 alter those tables but no migration creates
them. Early on, the server created them itself when it started, so every existing database already
had them. A new, empty database has none, and migrating it would fail at V2 with
`relation "sync_content" does not exist`. Deployments migrate before the server ever starts, so
nothing else could create the tables first.

It is a callback and not a versioned migration so that it has no checksum and no entry in
`flyway_schema_history`; adding a versioned migration would change the history of databases that
already applied V2. Every statement is `CREATE TABLE IF NOT EXISTS`, so on an existing database
the script changes nothing. The columns that later migrations add (`user_id`, `duration_ms`, and
others) are deliberately left out, because their `ALTER TABLE ... ADD COLUMN` statements are not
conditional and would fail on a table that already has the column.

## How the schema is organized

The tables fall into these areas. Each name below is a table, or a family of tables with a shared
prefix.

**Accounts and sign-in.** `accounts` is the root that many other tables reference. Around it are `passkeys`, `webauthn_challenges`,
`sessions`, `account_identities` and `account_link_events` (the sign-in methods linked to an
account), `pending_email_verifications`, `revoked_refresh_tokens`, and `restore_credentials`.
Columns on `accounts` carry the storage mode, moderation state, and public identity consent.

**Devices and keys.** `account_devices` is the device registry. `device_enrollments`,
`device_link_codes`, and `device_sessions` handle linking a new device or watch and the session each
linked device holds. `account_key_vault` holds the per-account key vault, and `account_key_envelopes` extends it with the account key encrypted for each passkey, as ciphertext
only, so clients can open end-to-end encrypted data on a new device.

**Legacy sync and backup.** `sync_content`, `sync_journals`, `sync_associations`, and `sync_media`
hold the records of accounts on the original REST sync. `logdate_backups` and `logdate_media_records`
index backups and uploaded media, `location_history` and `location_history_versions` hold
encrypted location history, and `diagnostic_reports` and `diagnostic_report_uploads` hold reports
that clients submit. `journal_merge_operations` records journal merges.

**AT Protocol identity and repositories.** `signing_keys` and `hosted_plc_operations` hold each
account's repository signing key and its PLC operation log. The `atproto_repo_*` tables (including `atproto_repo_announced`, `atproto_repo_block_links`, and `atproto_repo_events`) store
repository blocks, commits, heads, and the sequenced event stream. `atproto_password_credentials`
and `atproto_sessions` back AT Protocol sessions. `logdate_collection_records`,
`logdate_collection_states`, and `logdate_atproto_blobs` index LogDate records and blobs inside
those repositories, and `resource_routes` records which account owns each journal, note, or rewind resource.

**OAuth.** The `oauth_*` tables hold the authorization server's state: authorization requests and
codes, refresh tokens, signing keys and their rotation, used one-time values for replay protection,
and per-subject revocation times.

**Personal spaces.** `space_records`, `space_states`, `space_blobs`, `space_record_blobs`,
`space_blob_upload_receipts`, and `space_upload_reservations` store sealed records and their blobs.
`space_migrations` tracks moving an account from legacy sync into a personal space.

**Plans and storage.** `plans`, `account_entitlements`, and `billing_events` hold the plan catalog
and what each account is entitled to. `account_storage_usage` tracks bytes used, and
`account_blob_cleanup` and `account_blob_cleanup_paths` queue the stored objects to delete after an
account is removed. Triggers on `accounts` enqueue that cleanup, so a deletion cannot lose the
pointers to its files.

## Adding a migration

1. Find the highest version in `db/migration` and create the next file, named
   `V<next>__Short_description.sql`. Use a version number that no other file has.
   `MigrationVersionsTest` fails when two files share a version or a file does not match the naming
   pattern, which Flyway would otherwise skip silently or refuse to start on.
2. Write SQL that moves an existing database to the new shape and also builds the same shape on an
   empty database. Never edit a migration that has been applied anywhere; add a new one.
3. Update the Exposed table definitions in the `database` package to match, and any repository that
   reads or writes the changed columns.
4. Run `./gradlew :server:test --tests 'app.logdate.server.database.*'`. To exercise the migration
   against a real database, write a `*PostgresTest` next to the existing ones and run it against a
   disposable local PostgreSQL by setting `LOGDATE_DIAGNOSTIC_TEST_DISPOSABLE=true` and
   `LOGDATE_DIAGNOSTIC_TEST_DATABASE_URL`.
5. Land the migration with the code that needs it. Production applies it during the next deploy,
   before the new revision starts, so the previous revision must keep working against the migrated
   schema.

## When the database is unavailable at startup

`initializeDatabase` in `di/DatabaseBootstrap.kt` runs at startup. It builds the data source, applies
migrations when `AUTO_MIGRATE` allows it, connects Exposed, and, in production, checks that the
account blob cleanup table and its triggers exist. That check is the only query the bootstrap makes
besides the migration run. What happens when any of these steps fails depends on the runtime
profile, which `LOGDATE_ENV` selects.

| Profile                                                             | Failure behavior                                                                                                                                                                                                                                                                                   |
| ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `production`                                                        | The original exception is rethrown and the process exits, so Cloud Run's startup probe fails and the revision is rolled back. `LOGDATE_ALLOW_INMEMORY_FALLBACK` is ignored. Production configuration validation has already refused to start without `DATABASE_URL` or `INSTANCE_CONNECTION_NAME`. |
| `development` (the default) and `test`                              | The server stops with an `IllegalStateException` that names `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, and `LOGDATE_ALLOW_INMEMORY_FALLBACK`, and chains the underlying cause.                                                                                                          |
| `development` or `test` with `LOGDATE_ALLOW_INMEMORY_FALLBACK=true` | The server logs a warning and starts on in-memory repositories. Nothing is written to disk, every record is lost when the process exits, and `/health` reports `db_connected` as `false`.                                                                                                          |

The fallback exists for local work that does not need persistence and is enabled only by
`LOGDATE_ALLOW_INMEMORY_FALLBACK=true`.

## Troubleshooting

**Connection refused or timed out.** Confirm the database is running (`./run status`) and that
`DATABASE_URL` uses port 15432 for the Docker database.

**Authentication failed.** Check that `DATABASE_USER` and `DATABASE_PASSWORD` match the database.
`./run` loads `logdate` and `logdate` from `docs/environment/.env.example`; a direct Gradle run needs
them exported.

**A migration fails or the history disagrees with the files.** Read `flyway_schema_history` to see
which versions applied and whether the last one failed. On a local database with no data to keep,
`./run dev:reset` starts from an empty database.
