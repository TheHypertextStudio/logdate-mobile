# LogDate Server Environment Variables

**Authoritative reference for all environment variables used by the LogDate server.**

> Last updated: 2026-10-23

---

## Table of Contents

- [Runtime Profile](#runtime-profile)
- [Network Edge](#network-edge)
- [Server Configuration](#server-configuration)
- [Database Configuration](#database-configuration)
- [Authentication & Security](#authentication--security)
- [Blob Storage](#blob-storage)
- [Encryption](#encryption)
- [Media Storage](#media-storage)
- [Sync & Maintenance](#sync--maintenance)
- [Redis (Optional)](#redis-optional)
- [AT Protocol](#at-protocol)

---

## Runtime Profile

### `LOGDATE_ENV`

- **Description**: Deployment profile. Selects whether the server enforces production-grade secret validation at startup.
- **Type**: String: `production` (or `prod`) | `development` (or `dev`, `local`) | `test`
- **Default**: `development` when unset or blank
- **Example**: `LOGDATE_ENV=production`
- **Required**: No (but must be `production` for real deployments)
- **Notes**:
  - Any other value stops the server at startup. A mistyped `production` would otherwise select the development profile and its relaxed checks.
  - When set to `production`, the server refuses to start if durable storage, database, public identity, encryption, signed-media, health, release, JWT, AT Protocol signing-key, or WebAuthn configuration is incomplete or unsafe.
  - Development and test profiles skip this validation so local runs and the test suite work without extra setup.

### `LOGDATE_EXPECT_FIRST_PARTY`

- **Description**: Asserts that this production deployment is LogDate Cloud rather than a self-hosted server.
- **Type**: Boolean
- **Default**: `false`
- **Example**: `LOGDATE_EXPECT_FIRST_PARTY=true`
- **Required**: Set to `true` for managed staging and production; leave unset for self-hosting.
- **Notes**: When enabled, startup requires `LOGDATE_DEPLOYMENT_KIND=first_party`, an enabled billing provider, Android passkey origins, and valid Android certificate fingerprints. This is intentionally opt-in so secure self-hosted deployments may use durable filesystem storage and disabled billing.

---

## Network Edge

### `ALLOWED_ORIGINS`

- **Description**: Comma-separated list of origins trusted by CORS (e.g. a web UI that calls the API).
- **Type**: String (CSV of `scheme://host[:port]`)
- **Default**: None (CORS plugin not installed → browsers enforce same-origin only)
- **Example**: `ALLOWED_ORIGINS=https://app.logdate.com,https://admin.logdate.com`
- **Required**: Only if a browser client on a different origin needs to talk to the API.
- **Notes**:
  - Each entry must include a scheme. Malformed entries are ignored.
  - The plugin reflects the request's origin back in `Access-Control-Allow-Origin` only for entries on the allowlist.

### `REQUIRE_HTTPS`

- **Description**: When `true`, installs Ktor's `HttpsRedirect` plugin so HTTP requests are 301'd to HTTPS.
- **Type**: Boolean
- **Default**: **`true` when `LOGDATE_ENV=production`**, `false` otherwise.
- **Example**: `REQUIRE_HTTPS=false`
- **Required**: No (profile default is correct for most deployments).
- **Notes**:
  - Combined with `TRUST_FORWARDED_HEADERS=true`, the redirect reads the scheme the load balancer saw from the client, so requests that arrived over HTTPS at the edge pass through unchanged.
  - Set to `false` only when you're deliberately serving HTTP (rare — usually a local dev proxy or a dev container).

### `TRUST_FORWARDED_HEADERS`

- **Description**: Installs Ktor's `XForwardedHeaders` so `request.scheme` / `remoteHost` reflect the `X-Forwarded-Proto` / `X-Forwarded-For` headers the load balancer sets, instead of the LB's backend socket.
- **Type**: Boolean
- **Default**: **`true` when `LOGDATE_ENV=production`**, `false` otherwise.
- **Example**: `TRUST_FORWARDED_HEADERS=false`
- **Required**: No.
- **Notes**:
  - Set to `false` when the server is directly internet-facing (no LB/reverse proxy). Trusting forwarded headers without a trusted proxy in front lets any client spoof their scheme and IP.
  - Needed in production so `REQUIRE_HTTPS` doesn't redirect-loop requests the LB already accepted over HTTPS but forwards internally as HTTP.
  - The client address is read from the **last** `X-Forwarded-For` entry, the one Cloud Run's front end appends. Earlier entries come from the client and can be invented, so they are ignored. Rate limits on sign-in, the OAuth authorization page and device linking are keyed on this address.

### `TRUSTED_PROXY_HOPS`

- **Description**: How many trusted proxies append to `X-Forwarded-For` after the one that saw the client. With Cloud Run alone this is `0`, and the last entry is the client. If a load balancer you control sits in front of Cloud Run and appends its own entry, set it to `1`.
- **Type**: Integer, `0` to `4`
- **Default**: `0` outside production
- **Example**: `TRUSTED_PROXY_HOPS=1`
- **Required**: **Yes, in production while `TRUST_FORWARDED_HEADERS` is on.** `LOGDATE_ENV=production` refuses to start without it, because the right value depends on what sits in front of the deployment and a wrong default fails silently.
- **Notes**: Only used when `TRUST_FORWARDED_HEADERS` is on. Setting it too high lets a client choose its own address; setting it too low makes every visitor share one rate limit. The first-party deployments set it in their Cloud Run environment: production serves through Cloud Run domain mappings alone (`0`), and staging serves its domain through a Google external Application Load Balancer, which appends both the client's address and its own (`1`).

---

## Server Configuration

### `PORT`

- **Description**: Server HTTP port
- **Type**: Integer
- **Default**: `8765` (`SERVER_PORT`, defined in `shared/config`)
- **Note**: Container images set `PORT=8080` explicitly (`Dockerfile`, `docker-compose.yml`),
  so Docker and Cloud Run listen on 8080. The default applies only when nothing sets `PORT` —
  for example `./gradlew :server:run`.
- **Example**: `PORT=3000`
- **Required**: No

### `HOST`

- **Description**: Server bind address
- **Type**: String
- **Default**: `0.0.0.0` (all interfaces)
- **Example**: `HOST=127.0.0.1`
- **Required**: No

### `LOGDATE_PUBLIC_ORIGIN`

- **Description**: Canonical public HTTPS origin for the server.
- **Type**: HTTPS URL
- **Example**: `LOGDATE_PUBLIC_ORIGIN=https://cloud.logdate.app`
- **Required**: Yes in production.
- **Notes**: Must match `ATPROTO_PDS_SERVICE_URL` exactly after an optional trailing slash is removed.

### `RELEASE_VERSION`

- **Description**: Immutable deployment identity returned by `/health`.
- **Type**: String, `logdate-server@<40-hex-sha>`
- **Example**: `RELEASE_VERSION=logdate-server@0123456789abcdef0123456789abcdef01234567`
- **Required**: Yes in production.
- **Notes**: This is a deployment release identifier, not the API contract `version` field.

### `HEALTH_INTERNAL_TOKEN`

- **Description**: Secret that unlocks `db_connected` in the internal `/health` response.
- **Type**: Secret string
- **Default**: None
- **Required**: Yes in production.
- **Security**: Store only in the deployment secret manager; never include it in source or logs.

---

## Database Configuration

The server supports two sets of database environment variables for flexibility:

### Primary Database Variables

### `DATABASE_URL`

- **Description**: Full PostgreSQL connection URL
- **Type**: String (JDBC URL)
- **Default**: None
- **Example**: `jdbc:postgresql://localhost:5432/logdate`
- **Required**: Yes in production, unless both `INSTANCE_CONNECTION_NAME` and `DB_NAME` are present for the platform connector contract.

### `DATABASE_USER`

- **Description**: PostgreSQL username
- **Type**: String
- **Default**: None
- **Example**: `DATABASE_USER=logdate_user`
- **Required**: Yes for both `DATABASE_URL` and `INSTANCE_CONNECTION_NAME` connections, unless the URL embeds it.

### `DATABASE_PASSWORD`

- **Description**: PostgreSQL password.
- **Type**: String
- **Default**: None — previous releases fell back to the literal string `logdate`; that fallback has been removed.
- **Example**: `DATABASE_PASSWORD=secure_password_here`
- **Required**: **Yes**, unless the password is already embedded in `DATABASE_URL` (e.g. `postgres://user:pass@host/db`). `LOGDATE_ENV=production` additionally rejects known-default passwords.
- **Security**: Store in a secret manager; never commit.

### `CLOUD_SQL_INSTANCE_CONNECTION_NAME` (removed)

- **Status**: No longer supported under this legacy name.
- **Migration**: Use `INSTANCE_CONNECTION_NAME` for the authenticated Cloud SQL Java connector, or set `DATABASE_URL` for Neon, Supabase, self-hosted PostgreSQL, or a separately managed proxy. Credentials come from `DATABASE_USER` and `DATABASE_PASSWORD` unless they are embedded in `DATABASE_URL`.

### `INSTANCE_CONNECTION_NAME`

- **Description**: Cloud SQL instance connection name used with `DB_NAME` as the managed connector contract.
- **Type**: String
- **Example**: `INSTANCE_CONNECTION_NAME=logdate-prod:us-central1:logdate`
- **Required**: Only when `DATABASE_URL` is absent in production.
- **Authentication**: Uses Application Default Credentials from the Cloud Run runtime service account. Service-account key files are not supported.

### Alternative Database Variables

These development/test-only variables provide the local PostgreSQL fallback when neither `DATABASE_URL` nor `INSTANCE_CONNECTION_NAME` is set. Production rejects this fallback.

### `DB_HOST`

- **Description**: PostgreSQL host address
- **Type**: String
- **Default**: `localhost`
- **Example**: `DB_HOST=db.example.com`
- **Required**: No

### `DB_PORT`

- **Description**: PostgreSQL port
- **Type**: Integer
- **Default**: `5432`
- **Example**: `DB_PORT=5433`
- **Required**: No

### `DB_NAME`

- **Description**: PostgreSQL database name
- **Type**: String
- **Default**: `logdate`
- **Example**: `DB_NAME=logdate_prod`
- **Required**: No

### `DB_USER`

- **Description**: PostgreSQL username (alternative to `DATABASE_USER`)
- **Type**: String
- **Default**: None
- **Example**: `DB_USER=postgres`
- **Required**: No

### `DB_PASSWORD`

- **Description**: PostgreSQL password (alternative to `DATABASE_PASSWORD`)
- **Type**: String
- **Default**: None
- **Example**: `DB_PASSWORD=password123`
- **Required**: No
- **Security**: Store securely, never commit to version control

---

## Authentication & Security

### `JWT_SECRET`

- **Description**: Secret key for JWT token signing.
- **Type**: String (minimum 32 characters required in production)
- **Default**: None (when unset in development, the server generates a random per-process secret; tokens invalidate on restart)
- **Example**: `JWT_SECRET=$(openssl rand -base64 32)`
- **Required**: **Yes, in production.** `LOGDATE_ENV=production` refuses to start without it.
- **Security**:
  - Must be at least 32 characters.
  - Known placeholder values (e.g. `your-secret-key-change-in-production`) are rejected at startup.
  - Store in a secret manager; never commit.
  - Rotate periodically. Hosted AT Protocol signing keys are encrypted with `ATPROTO_SIGNING_KEY_KEK`, and AT Protocol session tokens are signed with `ATPROTO_SESSION_SECRET`, so rotating this secret affects neither.

### `ATPROTO_SIGNING_KEY_KEK`

- **Description**: The secret that encrypts the server's stored private keys at rest (a _key-encryption key_): each account's hosted AT Protocol signing key, and the key that signs OAuth access tokens for third-party AT Protocol apps. The server derives an AES-256 key from it with HKDF-SHA256 to wrap the private keys stored in the database, binding each wrapped key to the table and row it is stored in, and unwraps them whenever it signs a repository commit, a PLC operation, a key export or an OAuth access token. Keys wrapped before that binding are still read and are re-wrapped at startup (see `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY`). A release from before the binding can't read re-wrapped keys, so once an instance of a newer release has run for 15 minutes, fix problems by rolling forward rather than back past it.
- **Type**: String (minimum 32 characters in production). The value is used byte for byte, including any trailing newline.
- **Default**: None in production. Development and test fall back to a fixed value published in the source code, which only ever protects keys on a developer's machine.
- **Example**: `ATPROTO_SIGNING_KEY_KEK=$(openssl rand -base64 48)`
- **Required**: **Yes, in production.** `LOGDATE_ENV=production` refuses to start without it. The server never falls back to `JWT_SECRET`.
- **Security**:
  - A different value leaves every stored signing key unreadable: commits, PLC operations and key exports fail, and so does every OAuth access token for third-party apps. Never change it in place; move to a new value with `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY`, below.
  - Before this variable existed, deployed servers encrypted signing keys with `JWT_SECRET`. An existing deployment must therefore start this secret from the **exact bytes** of the `JWT_SECRET` version it has been running with. Copy the version byte for byte, then compare SHA-256 digests of the two versions before deploying.
  - A new deployment with no stored keys uses a fresh random value, independent of `JWT_SECRET`.
  - Store in a secret manager; never commit.

### `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY`

- **Description**: A second key-encryption key the server can decrypt stored private keys with, but never encrypts with. It exists only while a deployment moves from one `ATPROTO_SIGNING_KEY_KEK` to another. Every instance re-encrypts the stored keys under `ATPROTO_SIGNING_KEY_KEK`, in the current format, once 15 minutes after startup (after a rollout has replaced the previous release's instances, which can't read that format), reading keys encrypted with this value while it's set, and logs the outcome: `SERVER_SIGNING_KEYS_REWRAPPED`, `SERVER_SIGNING_KEYS_CURRENT` when nothing needed re-encrypting, or the errors `SERVER_SIGNING_KEYS_UNREADABLE` (keys neither value can read, which are left untouched) and `SERVER_SIGNING_KEYS_REWRAP_FAILED`.
- **Type**: String (minimum 32 characters in production, and different from `ATPROTO_SIGNING_KEY_KEK`).
- **Default**: Unset.
- **Required**: No.
- **Moving to a new key-encryption key**: running instances keep serving during a deploy, so the new value has to be readable everywhere before anything is encrypted with it. Use two deploys:
  1. Create the new value as a new version of the secret. Deploy with `ATPROTO_SIGNING_KEY_KEK` unchanged and `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY` set to the **new** value. Nothing is re-encrypted yet, but every instance can now read keys encrypted with the new value.
  2. Deploy with `ATPROTO_SIGNING_KEY_KEK` set to the new value and `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY` set to the **old** value. Instances re-encrypt every stored key under the new value 15 minutes after startup, and instances still running the first deploy can read the result.
  3. Once every instance logs `SERVER_SIGNING_KEYS_REWRAPPED` or `SERVER_SIGNING_KEYS_CURRENT`, and none logs an error, remove `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY` from the tfvars (and, for staging, `staging-contract-template.json`). The deploy workflow only adds or updates secret mappings, so also remove it from each running service: `gcloud run services update <service> --region <region> --project <project> --remove-secrets ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY`.
  4. Keep the old version of the secret until no database backup still holds keys encrypted with it. Restoring such a backup without the old value loses every key in it.
- **Security**: Store in a secret manager; never commit.

### `OAUTH_SIGNING_KEY_MAX_AGE_DAYS`

- **Description**: How many days a key signs OAuth access tokens for third-party AT Protocol apps before the server replaces it. Rotation never signs anyone out. Every five minutes each instance checks whether a step is due:
  1. When the active key reaches this age, a new key is staged. It's published in the OAuth JWKS and accepted for verification, but nothing signs with it yet.
  2. An hour later, once every instance has picked it up, the new key starts signing. The previous key keeps verifying the tokens it signed for another 65 minutes, longer than an access token lives.
  3. Then the previous key is deleted.
- **Type**: Integer, at least 1.
- **Default**: `90`
- **Required**: No.
- **Logs**: each step logs `SERVER_OAUTH_KEY_STAGED`, `SERVER_OAUTH_KEY_PROMOTED` or `SERVER_OAUTH_KEY_RETIRED`, and a failed check logs the error `SERVER_OAUTH_KEY_ROTATION_FAILED`.
- **Rotating early**: to replace the key now, for example after a suspected leak, set a value lower than the key's current age. The rotation completes within about an hour. Restore the usual value afterwards.

### `ATPROTO_SESSION_SECRET`

- **Description**: The secret that signs the access and refresh tokens the server issues to AT Protocol clients through `com.atproto.server.createSession` and `refreshSession`. The LogDate apps sign in with their own tokens and do not use these.
- **Type**: String (minimum 32 characters in production).
- **Default**: None in production. Development and test fall back to a fixed value published in the source code.
- **Example**: `ATPROTO_SESSION_SECRET=$(openssl rand -base64 48)`
- **Required**: **Yes, in production.** `LOGDATE_ENV=production` refuses to start without it. The server never falls back to `JWT_SECRET`.
- **Security**:
  - Anyone holding this value can mint AT Protocol session tokens for any account, which is why a published fallback is never acceptable outside a developer's machine.
  - Before this variable existed, deployed servers signed these tokens with `JWT_SECRET`. A new value signs out every AT Protocol client session, and their users sign in again; nothing is lost. Copying the `JWT_SECRET` version byte for byte keeps those sessions working instead, at the cost of keeping the two secrets identical.
  - Store in a secret manager; never commit.

### `GOOGLE_OIDC_CLIENT_IDS`

- **Description**: Comma-separated Google OAuth client IDs accepted for ID token verification
- **Type**: String (CSV)
- **Default**: None
- **Example**: `GOOGLE_OIDC_CLIENT_IDS=123.apps.googleusercontent.com,456.apps.googleusercontent.com`
- **Required**: Yes (if Google sign-in is enabled)
- **Security**:
  - Keep aligned with released mobile/web client IDs only
  - Rotate/remove deprecated client IDs promptly
  - Prefer storing in secret manager for production deployments
- **Notes**:
  - When this is unset, Google auth routes are treated as not configured and return `GOOGLE_AUTH_NOT_CONFIGURED`

### `WEBAUTHN_RP_ID`

- **Description**: WebAuthn relying party ID used for passkey ceremonies
- **Type**: String
- **Default**: `logdate.app`
- **Example**: `WEBAUTHN_RP_ID=app.logdate.com`
- **Required**: Yes (for production passkeys)

### `WEBAUTHN_RP_NAME`

- **Description**: Display name presented as relying party name in passkey prompts
- **Type**: String
- **Default**: `LogDate`
- **Example**: `WEBAUTHN_RP_NAME=LogDate Cloud`
- **Required**: No

### `WEBAUTHN_ORIGIN`

- **Description**: Expected WebAuthn origin for registration/authentication responses
- **Type**: String (HTTPS origin)
- **Default**: `https://app.logdate.com`
- **Example**: `WEBAUTHN_ORIGIN=https://app.logdate.com`
- **Required**: Yes (for production passkeys)
- **Notes**:
  - If unset, the server derives the value from the configured public server origin when available

### `WEBAUTHN_ALLOWED_ORIGINS`

- **Description**: Additional client origins accepted during passkey ceremonies, on top of `WEBAUTHN_ORIGIN`. This is how native apps are allowed: an Android Credential Manager ceremony presents `clientDataJSON.origin` as `android:apk-key-hash:<base64url-SHA256(signing-cert)>` rather than an `https://` URL, so without the matching entry here every on-device passkey signup and sign-in is rejected.
- **Type**: String (comma-separated list of `https://` URLs and/or `android:apk-key-hash:<hash>` origins)
- **Default**: empty (only `WEBAUTHN_ORIGIN` is accepted — web only)
- **Example**: `WEBAUTHN_ALLOWED_ORIGINS=https://cloud.logdate.app,android:apk-key-hash:pNiP8Z...`
- **Required**: No, but required in practice for native Android passkeys
- **Notes**:
  - The canonical `WEBAUTHN_ORIGIN` is always accepted whether or not it is repeated here.
  - First-party deployment tooling owns the aggregate from the canonical origin and declared Android signers. Do not override it directly in `cloud_run_env`.
  - Staging declares RP `logdate-preview.hypertext.studio` and primary origin `https://app.logdate-preview.hypertext.studio`, independently of the API hostname and identity handle namespace. The aggregate includes this browser origin and the declared Android signer origins. Browser origins must belong to the configured RP; staging must be outside production's cookie domain.
  - Production leaves additional origins empty and retains its existing RP and primary origin. Terraform and the deployment renderer derive the allowed-origin aggregate from the primary browser origin and Android signers, without assuming that browser and API origins match.
  - Before a staging RP rollout, publish and verify the new RP's public native association metadata against its signer contract. Existing staging browser and native passkeys require re-registration; production credentials and native application identifiers are unchanged.
  - List one `android:apk-key-hash:` entry per signing certificate. With Play App Signing this means both the upload certificate and the Google-managed app-signing certificate; for local/staging builds it means the debug keystore certificate. The hash is `base64url(SHA-256(DER-encoded signing cert))` — the same certificate whose colon-hex SHA-256 goes in `assetlinks.json` (served by the `logdate-web` project at the relying-party domain).
  - Malformed entries are logged and skipped so one typo cannot break passkey verification for everyone.

### `WEBAUTHN_STRICT_VERIFICATION`

- **Description**: Enables strict WebAuthn4J cryptographic verification for passkeys and restore credentials.
- **Type**: Boolean
- **Default**: **`true` when `LOGDATE_ENV=production`**, `false` otherwise. Set explicitly to override either way.
- **Example**: `WEBAUTHN_STRICT_VERIFICATION=true`
- **Required**: No (profile default is correct for most deployments)
- **Notes**:
  - Strict mode requires valid Base64URL WebAuthn payloads from clients — the default in production.
  - Self-hosters running a non-standard client can set this to `false` explicitly, even in production.

---

## Blob Storage

### `LOGDATE_BLOB_STORAGE_DIR`

- **Description**: Absolute or relative path to a directory that the server uses as on-disk blob storage for media and backups when GCS is not configured.
- **Type**: String (filesystem path)
- **Default**: None. When both this and `GCS_*` are unset, media and backup uploads are rejected with `503 Service Unavailable`.
- **Example**: `LOGDATE_BLOB_STORAGE_DIR=/var/lib/logdate/blobs`
- **Required**: No — needed only for self-host deployments without Google Cloud Storage.
- **Notes**:
  - The server creates the directory if it doesn't exist.
  - Layout is `<namespace>/<ownerId>/<blobId>`, matching the GCS object naming.
  - GCS takes precedence when both are set (GCS is checked first).
  - Back this with durable storage. A crashed container loses anything written to an ephemeral volume.

---

## Encryption

### `SERVER_ENCRYPTION_KEY`

- **Description**: Base64-encoded AES encryption key for server-side encryption
- **Type**: String (Base64)
- **Default**: None
- **Example**: `SERVER_ENCRYPTION_KEY=YourBase64EncodedKeyHere==`
- **Required**: No (but recommended for production)
- **Security**:
  - Must decode to 16, 24, or 32 bytes (AES-128/192/256)
  - Store in secret manager (e.g., GCP Secret Manager, AWS Secrets Manager)
  - Never commit to version control
  - Generate with: `openssl rand -base64 32`
- **Notes**: If not set, encryption service uses NoOpKeyring (test mode)

### `SERVER_ENCRYPTION_KEY_ID`

- **Description**: Identifier for the active encryption key (enables key rotation)
- **Type**: String
- **Default**: `default`
- **Example**: `SERVER_ENCRYPTION_KEY_ID=key-2026-01`
- **Required**: No
- **Notes**: Change this when rotating keys to track which key encrypted each payload

### `ENCRYPTION_MODE`

- **Description**: Server encryption policy mode
- **Type**: Enum
- **Values**:
  - `AT_REST_ONLY` - Server encrypts all data, accepts client ciphertext (default)
  - `E2EE_REQUIRED` - Only accepts client-encrypted data, rejects plaintext
- **Default**: `AT_REST_ONLY`
- **Example**: `ENCRYPTION_MODE=E2EE_REQUIRED`
- **Required**: No

### `SERVER_ENCRYPTION_ENABLED`

- **Description**: Enable/disable server-side encryption
- **Type**: Boolean
- **Default**: `true` (if `SERVER_ENCRYPTION_KEY` is set)
- **Example**: `SERVER_ENCRYPTION_ENABLED=false`
- **Required**: No
- **Notes**: Setting to `false` disables encryption even if key is configured

### `ALLOW_PASSTHROUGH_CLIENT_CIPHERTEXT`

- **Description**: Allow client-encrypted payloads to pass through without server re-encryption
- **Type**: Boolean
- **Default**: `true`
- **Example**: `ALLOW_PASSTHROUGH_CLIENT_CIPHERTEXT=false`
- **Required**: No
- **Notes**: Disable this to force server-side re-encryption of all uploads

---

## Media Storage

### `GCS_PROJECT_ID`

- **Description**: Google Cloud Storage project ID
- **Type**: String
- **Default**: None
- **Example**: `GCS_PROJECT_ID=my-project-123456`
- **Required**: Yes (if using GCS)

### `GCS_BUCKET_NAME`

- **Description**: GCS bucket name for media storage
- **Type**: String
- **Default**: None
- **Example**: `GCS_BUCKET_NAME=logdate-media-prod`
- **Required**: Yes (if using GCS)
- **Notes**: Production requires this or a durable `LOGDATE_BLOB_STORAGE_DIR`; a container-local temporary directory is not durable storage.

### `SYNC_MEDIA_SIGNED_URLS`

- **Description**: Return signed, time-limited media download URLs.
- **Type**: Boolean
- **Default**: `false`
- **Example**: `SYNC_MEDIA_SIGNED_URLS=true`
- **Required**: Yes in production.

### `GCS_MEDIA_KMS_KEY`

- **Description**: GCS KMS key for media encryption at rest (Google-managed)
- **Type**: String (KMS key resource name)
- **Default**: None
- **Example**: `GCS_MEDIA_KMS_KEY=projects/my-project/locations/us/keyRings/my-ring/cryptoKeys/media-key`
- **Required**: No
- **Notes**: Separate from `SERVER_ENCRYPTION_KEY` - this is for GCS-level encryption

### `SYNC_MEDIA_SIGNED_URL_TTL_HOURS`

- **Description**: TTL for GCS signed URLs (hours)
- **Type**: Integer
- **Default**: `1`
- **Range**: 1-24
- **Example**: `SYNC_MEDIA_SIGNED_URL_TTL_HOURS=6`
- **Required**: No

---

## Sync & Maintenance

### `SYNC_TOMBSTONE_PURGE_ENABLED`

- **Description**: Enable automatic purging of old deletion markers (tombstones)
- **Type**: Boolean
- **Default**: `true`
- **Example**: `SYNC_TOMBSTONE_PURGE_ENABLED=false`
- **Required**: No
- **Notes**: Disable for debugging or to manually control purge operations

### `AUTO_MIGRATE`

- **Description**: Controls whether startup applies Flyway migrations and schema reconciliation.
- **Type**: Boolean
- **Default**: **`false` when `LOGDATE_ENV=production`**, `true` otherwise. Set explicitly to override.
- **Example**: `AUTO_MIGRATE=true`
- **Required**: No (profile default is correct for most deployments).
- **Notes**:
  - Production should run migrations as a dedicated CI step before the new container goes live; automatic migration on every container start means a rolling deploy can race two versions of the app against the same Flyway lock.
  - When `false`, startup still connects to the database but skips schema mutation.

### `LOGDATE_ALLOW_INMEMORY_FALLBACK`

- **Description**: Allows the server to start on in-memory repositories when the database is unreachable.
- **Type**: Boolean
- **Default**: `false`
- **Example**: `LOGDATE_ALLOW_INMEMORY_FALLBACK=true`
- **Required**: No
- **Notes**:
  - Unset, a database failure stops startup. The error names the connection variables to fix (`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`) and chains the underlying cause.
  - Set it only for local work that genuinely does not need persistence — nothing is written and every record is lost when the process exits. Startup logs a warning saying so.
  - Ignored in production (`LOGDATE_ENV=production`), where a database failure is always rethrown so the deployment's startup probe rolls the revision back.
  - Also readable as a system property (`-DLOGDATE_ALLOW_INMEMORY_FALLBACK=true`), matching how `PORT` and `HOST` are resolved.

### `SYNC_TOMBSTONE_RETENTION_DAYS`

- **Description**: How many days to keep deletion markers before purging
- **Type**: Integer
- **Default**: `30`
- **Range**: 1-3650 (1 day to 10 years)
- **Example**: `SYNC_TOMBSTONE_RETENTION_DAYS=90`
- **Required**: No
- **Notes**: Longer retention = more storage, but better sync reliability for offline devices

### `SYNC_TOMBSTONE_PURGE_INTERVAL_HOURS`

- **Description**: How often to run the tombstone purge job (hours)
- **Type**: Integer
- **Default**: `24`
- **Minimum**: 1
- **Example**: `SYNC_TOMBSTONE_PURGE_INTERVAL_HOURS=12`
- **Required**: No
- **Notes**: More frequent purges = lower storage, but higher database load

### `LOGDATE_PERSONAL_SPACE_ENABLED`

- **Description**: Turns on personal spaces: the `app.logdate.space.putRecord`, `deleteRecord`, `getRecord`, `listRepoOps`, `uploadBlob` and `getBlob` methods, where a LogDate device stores its owner's end-to-end encrypted records and files.
- **Type**: Boolean (only the exact value `true` enables it)
- **Default**: `false`
- **Example**: `LOGDATE_PERSONAL_SPACE_ENABLED=true`
- **Required**: No
- **Notes**:
  - Off, every one of the six methods answers `501 MethodNotImplemented` to a signed-in caller, and a caller without an access token still gets `401`. The server also leaves `personalSpaceV1` out of its server description, so clients can tell the feature is unavailable before they try it.
  - On, the server adds `personalSpaceV1` to its server description. Only the account's own LogDate devices can use the methods; every other caller is told the space doesn't exist.
  - Leave it off until the client sync work that writes to personal spaces is ready to ship. Turning it on first advertises a feature no app uses yet.
  - Leave it off until the database migration that adds `account_storage_usage` (`V47__Add_account_storage_usage.sql`) is deployed everywhere. The migration fills each account's space total from the rows it finds, and a server revision without it doesn't keep that total, so space writes made while an older revision is still serving would be missing from the totals and the quota would let accounts store more than their plan allows.
  - Deletion markers in a personal space follow `SYNC_TOMBSTONE_RETENTION_DAYS`. Uploaded files that no record refers to are removed after one day. A device that was offline longer than the tombstone retention gets `CursorTooOld` and downloads the space again from the start.
  - Each account has three request budgets per minute: 6,000 record writes (`putRecord` and `deleteRecord`), 6,000 reads (`getRecord`, `listRepoOps` and `getBlob`), and 1,200 file uploads. A device syncing as fast as it can stays under them. Past a budget, the method answers `429 RateLimitExceeded` with a `Retry-After` header. The counts live in each server instance's memory, so with several instances an account's budget applies to each one separately.
  - Records and files in a personal space share the account's one storage quota with media and backups, and `GET /api/v1/quota` includes them. The server keeps one running total per account for each kind of data (space, media and backups), so a quota check reads a single row however much the account stores, and checks every record write and file upload against the quota in the same database transaction that stores it, so requests sent at once can't together pass it. A record write that would go past the quota answers `402 QuotaExceeded`; replacing a record with one no larger and deleting a record never are. A file upload first holds room for its `Content-Length`, alongside any other uploads in progress, and answers `402 QuotaExceeded` before receiving anything when that room isn't free. With `BILLING_PROVIDER` unset, accounts have no quota.
  - An upload must send a `Content-Length` header, or it answers `411 LengthRequired`. The server streams the body to object storage as it arrives, hashing it on the way, and only records the file once storage holds exactly the bytes it hashed, so nothing is written to the instance's disk or memory beyond a 2 MiB write buffer and a 64 KiB chunk for each upload. Each instance runs up to 8 uploads and 16 downloads at once, sized for the 512 MiB instances LogDate Cloud uses: with a heap of about 384 MiB, those 24 transfers take about 50 MiB. Further transfers answer `503 ServerBusy` with a `Retry-After` header and cost the account none of its request budget. A transfer whose client sends or accepts nothing for 20 seconds is ended (`408 UploadStalled` for uploads) and its place freed. Room held for an upload that never finishes is released 2 minutes after it was taken, which is the request timeout plus a margin.

### `LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED`

- **Description**: Lets existing accounts move their journals from legacy storage into their personal spaces through the `/api/v1/space-migration` calls, which a LogDate device makes once it has synced everything.
- **Type**: Boolean (only the exact value `true` enables it)
- **Default**: `false`
- **Example**: `LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED=true`
- **Required**: No
- **Notes**:
  - It takes effect only together with `LOGDATE_PERSONAL_SPACE_ENABLED`. With both on, the server adds `personalSpaceMigrationV1` to its server description; otherwise every move call answers `404 FEATURE_DISABLED`.
  - Leave it off until the database migration that adds `space_migrations` (`V50__Add_space_migrations.sql`) is deployed everywhere.
  - Turning it off only stops new moves. Accounts that switched stay in their personal spaces, the legacy sync routes keep answering them with `403 SYNC_FORMAT_UPGRADE_REQUIRED`, and they can still switch back until their legacy copies are deleted 30 days after each switch: the status and switch-back calls keep working, and so do the hold and inventory calls for an account that switched. `LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED` pauses that deletion.
  - The [journal moves runbook](../../docs/runbook/journal-moves.md) describes rehearsing moves in staging and operating them.

### `LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED`

- **Description**: Whether the maintenance loop deletes the legacy copy of each journal that moved into its personal space, 30 days after its switch. Turning it off pauses only that deletion, so switching back keeps working for those accounts while it is paused.
- **Type**: Boolean
- **Default**: `true`
- **Example**: `LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED=false`
- **Required**: No
- **Notes**:
  - Unset or `true` deletes copies when they are due. Any other value pauses the deletion, so a mistyped value keeps data rather than deleting it. The deployment contract renderer accepts only `true` or `false`.
  - While it is paused, the server logs `SERVER_LEGACY_COPY_CLEANUP_PAUSED` when maintenance starts. Tombstone and personal-space purges keep running; `SYNC_TOMBSTONE_PURGE_ENABLED=false` stops the whole maintenance loop, this deletion included.
  - A copy whose 30 days passed while the deletion was paused is deleted on the first maintenance run after it is turned back on. The window for switching back is still 30 days, so pausing the deletion doesn't extend it.

### `LOGDATE_SPACE_MIGRATION_HOLD_GRACE_SECONDS`

- **Description**: How long a hold on an account's legacy writes must stand before a device may switch the account into its personal space, so that every write that started before the hold has finished.
- **Type**: Integer seconds, zero or more
- **Default**: `120`
- **Example**: `LOGDATE_SPACE_MIGRATION_HOLD_GRACE_SECONDS=120`
- **Required**: No
- **Notes**:
  - Keep it longer than the longest request the server can run. Cloud Run ends a request after 60 seconds on LogDate Cloud, so the default leaves a minute of margin.
  - The server-client end-to-end tests set it to `0` so a move doesn't wait two minutes; never set `0` on a deployed server, because a write that passed its storage check just before the hold could then land after the device checked legacy storage.

### `LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED`

- **Description**: Turns on the browser authorization page. When another AT Protocol app sends a person to `/oauth/authorize`, they see which app is asking and approve it by signing in with their LogDate passkey. Without it, only the LogDate apps can approve OAuth requests, so accounts without a password can't use their identity in other apps.
- **Type**: Boolean (only the exact value `true` enables it)
- **Default**: `false`
- **Example**: `LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED=true`
- **Required**: No
- **Notes**:
  - Browsers only offer a passkey on a page whose host is the relying-party ID or a subdomain of it. The page is served from the OAuth issuer (`ATPROTO_OAUTH_ISSUER`), so the issuer's host must sit inside `WEBAUTHN_RP_ID`. For example, `https://cloud.logdate.app` works with `WEBAUTHN_RP_ID=logdate.app`. If it doesn't, the server refuses to start and names both values.
  - The issuer's origin is added to the origins passkey verification accepts automatically; it does not need to be listed in `WEBAUTHN_ALLOWED_ORIGINS`.
  - The server also refuses to start when passkey verification is not strict, because the page would then accept an assertion without checking it. Set `WEBAUTHN_STRICT_VERIFICATION=true` (the production default).
  - An approved app never sees or changes the LogDate journal. With either scope it only learns which account signed in today: `transition:generic` reaches only collections open to apps, and none is open yet. Uploads are refused too. This holds whether or not the flag is on.
  - Design: `docs/superpowers/specs/2026-10-19-passkey-atproto-sign-in-design.md`.

### `LOGDATE_OAUTH_TRUSTED_CLIENT_IDS`

- **Description**: OAuth client IDs (the URLs of their client metadata documents) that LogDate trusts. The authorization page names a trusted app by its own `client_name`; every other app is named by its full client ID URL, because a self-reported name can claim to be anything. Refresh tokens issued to trusted apps last 30 days instead of 14.
- **Type**: Comma-separated list of URLs
- **Default**: empty (no app is trusted)
- **Example**: `LOGDATE_OAUTH_TRUSTED_CLIENT_IDS=https://bsky.app/oauth-client-metadata.json`
- **Required**: No
- **Notes**:
  - Each entry must match the app's `client_id` exactly.
  - Trust only changes how the app is named and how long its refresh token lasts. It grants no extra scope.

---

## Redis (Optional)

### `REDIS_URL`

- **Description**: Redis connection URL for caching/session storage
- **Type**: String (Redis URL)
- **Default**: None
- **Example**: `REDIS_URL=redis://localhost:6379`
- **Required**: No
- **Notes**: Currently not actively used, reserved for future caching features

---

## AT Protocol

Every LogDate account has an AT Protocol identity: a handle such as `alice.logdate.app`, a DID
(the permanent account identifier that handles point to), and a signing key the server holds for
the account. The server also hosts each account's repository and acts as an OAuth server for
third-party AT Protocol apps. These variables control that behavior. Most have safe defaults; in
production only `ATPROTO_PDS_SERVICE_URL` and `ATPROTO_HANDLE_DOMAIN` are set.

The secrets that sign AT Protocol session tokens and protect stored signing keys are documented
with the change that makes them required.

### `ATPROTO_PDS_SERVICE_URL`

- **Description**: Public HTTPS URL of this server as an AT Protocol data server (PDS). It is
  written into each account's DID document, so other AT Protocol services know where the
  account's data lives.
- **Type**: HTTPS URL
- **Default**: `https://<ATPROTO_HANDLE_DOMAIN>`
- **Example**: `ATPROTO_PDS_SERVICE_URL=https://cloud.logdate.app`
- **Required**: Yes in production.
- **Notes**: Must equal `LOGDATE_PUBLIC_ORIGIN` in production, or the server refuses to start.
  Plain `http` is accepted only for loopback hosts outside production, so an emulator can reach a
  server on the developer's machine. `ATPROTO_PDS_SERVICE_ENDPOINT` is an older name that is
  still read when this variable is unset.

### `ATPROTO_HANDLE_DOMAIN`

- **Description**: Domain under which accounts get their handles. An account with username
  `alice` gets the handle `alice.<domain>`.
- **Type**: Domain name
- **Default**: `logdate.app`
- **Example**: `ATPROTO_HANDLE_DOMAIN=logdate.app`
- **Required**: No
- **Notes**: The server's own identity is `did:web:<domain>`. Self-hosted servers should set
  their own domain so handles don't claim `logdate.app`.

### `ATPROTO_HOSTED_DID_METHOD`

- **Description**: Which kind of DID new hosted accounts get. `plc` creates a `did:plc` registered
  in the PLC directory. `web` creates a hostname-level `did:web` such as
  `did:web:alice.logdate.app`, which the server serves itself.
- **Type**: `plc` or `web`
- **Default**: `plc`
- **Example**: `ATPROTO_HOSTED_DID_METHOD=web`
- **Required**: No
- **Notes**: Any other value is rejected with a configuration error. Changing it affects new identities
  only.

### `ATPROTO_PLC_PUBLISH_ENABLED`

- **Description**: Whether the server sends `did:plc` operations to the PLC directory. Until an
  account's operations are published, nobody outside LogDate can resolve its DID.
- **Type**: Boolean (`true` to enable)
- **Default**: `false`
- **Example**: `ATPROTO_PLC_PUBLISH_ENABLED=true`
- **Required**: No
- **Notes**: Only applies when `ATPROTO_HOSTED_DID_METHOD` is `plc`. When enabled, the server
  advertises the `atprotoPlcPublishingV1` protocol feature, and clients may then offer identity
  changes such as signing-key rotation. When disabled, the server refuses those changes.
  Recovery keys do not need it; see `ATPROTO_RECOVERY_KEYS_ENABLED`. Production leaves it off.
  DIDs created while it is off are never published later on their own: turning it on means running
  the identity publishing command for existing accounts, which submits the hosted operation log as
  it is. Publishing can't be undone, so follow
  [identity publishing runbook](../../docs/runbook/identity-publishing.md) to turn it on. Only a
  production deployment contract may set it to `true`.

### `ATPROTO_RECOVERY_KEYS_ENABLED`

- **Description**: Whether people can register their own recovery key on their hosted `did:plc`
  identity and use it to replace the server's key. The recovery key is listed ahead of the
  server's key, so it can overrule it.
- **Type**: Boolean (`true` to enable)
- **Default**: `false`
- **Example**: `ATPROTO_RECOVERY_KEYS_ENABLED=true`
- **Required**: No
- **Notes**: Only applies when `ATPROTO_HOSTED_DID_METHOD` is `plc`. It works without
  `ATPROTO_PLC_PUBLISH_ENABLED`: the server's hosted operation log is then the identity's history,
  and nothing is sent to the PLC directory. Turning publishing on does not turn recovery keys on.
  When enabled, the server advertises the `atprotoRecoveryKeysV1` protocol feature. When it is not
  set, the recovery-key endpoints answer `409 RECOVERY_KEYS_UNAVAILABLE`. Registering a key,
  replacing it and replacing the server's key each also need a fresh passkey confirmation.
  A deployment sets it in the deployment contract's `env_vars` in either environment; the contract
  renderer accepts only `true` or `false`.

### `ATPROTO_FIREHOSE_ENABLED`

- **Description**: Whether the server announces public repositories to the rest of the AT Protocol
  network. When it is on, the server serves the repository event stream
  (`com.atproto.sync.subscribeRepos`) and `com.atproto.sync.listRepos`, and anyone can read public
  records and their blobs, the latest commit, the repository status and the repository itself
  without signing in. Each change to a public repository is written to an event log that relays
  replay from any point they name.
- **Type**: Boolean (`true` to enable)
- **Default**: `false`
- **Example**: `ATPROTO_FIREHOSE_ENABLED=true`
- **Required**: No
- **Notes**:
  - Only the profile, the sharing keys other people encrypt to, and the stories a person publishes are
    public (`app.logdate.actor.profile`, `app.logdate.crypto.keyDeclaration`,
    `site.standard.publication` and `site.standard.document`), plus any collection later opened to
    apps. The list is fixed in code. Nothing from a personal or shared space, no sealed record and no
    `studio.hypertext.logdate.*` or other `app.logdate.*` record is ever shown or announced.
  - A repository is shown only while its account is active, keeps its journal in its personal space,
    and has a DID other apps can look up (a `did:web`, or a `did:plc` once
    `ATPROTO_PLC_PUBLISH_ENABLED` has published it). A repository whose record tree holds even one
    private record is not exported or announced at all, because a tree names every record it holds.
  - With the flag on, an account in a personal space can write public records through
    `com.atproto.repo.createRecord` and `putRecord`, and is refused when it tries to write journal
    records into its repository.
  - Events live in the database (table `atproto_repo_events`, migration V51) and every server instance
    serves the same log. Events are kept for seven days; a relay whose cursor is older gets an
    `OutdatedCursor` notice and then everything that remains.
  - With the flag off, none of this exists: the stream route is not registered, the repository
    methods are open to the account's owner only, and no event is written.
  - A deployment sets it in the deployment contract's `env_vars` in either environment; the contract
    renderer accepts only `true` or `false`. Production turns it on in a separate step after identities
    are published.

### `ATPROTO_RELAY_HOSTS`

- **Description**: The relays the server asks to follow its event stream, by sending each one
  `com.atproto.sync.requestCrawl` when the server starts and when an account is first announced. The
  request names the host from `ATPROTO_PDS_SERVICE_URL`.
- **Type**: String (comma-separated hostnames, lowercase, no scheme, port or path)
- **Default**: Empty, so the server asks no relay and a relay has to be pointed at it by hand
- **Example**: `ATPROTO_RELAY_HOSTS=relay1.us-east.bsky.network,relay1.us-west.bsky.network`
- **Required**: No
- **Notes**:
  - Read only when `ATPROTO_FIREHOSE_ENABLED` is `true`. A value that is not a list of hostnames stops
    the server at startup.
  - At startup, a relay that cannot be reached is asked again after 10, 20, 40 and 80 seconds. After
    that, a round goes out when an account is announced for the first time, at most once every ten
    minutes, because a relay that already follows the server learns about later accounts from the stream.
  - The deployment contract renderer accepts it only for production, only with
    `ATPROTO_FIREHOSE_ENABLED` set to `true`, and only as distinct lowercase hostnames.

### `ATPROTO_PLC_DIRECTORY_URL`

- **Description**: The PLC directory to publish to and resolve `did:plc` identities from.
- **Type**: HTTPS URL
- **Default**: `https://plc.directory`
- **Example**: `ATPROTO_PLC_DIRECTORY_URL=https://plc.directory`
- **Required**: No
- **Notes**: Must use `https`, except outside production, where plain `http` is accepted for a
  loopback host such as `http://127.0.0.1:2582`. That is how `./run identity:rehearse` points a
  local server at a PLC directory running on the same machine. Never point a development or
  staging server at the public directory with publishing on.

### `ATPROTO_OAUTH_ISSUER`

- **Description**: Issuer URL of the server's OAuth authorization server for third-party AT
  Protocol apps. It appears in the OAuth discovery documents and in issued tokens.
- **Type**: HTTPS URL
- **Default**: The value of `ATPROTO_PDS_SERVICE_URL`
- **Example**: `ATPROTO_OAUTH_ISSUER=https://cloud.logdate.app`
- **Required**: No

### `ATPROTO_OAUTH_RESOURCE`

- **Description**: The protected resource URL that OAuth access tokens are issued for, published
  in the protected-resource discovery document.
- **Type**: HTTPS URL
- **Default**: The value of `ATPROTO_OAUTH_ISSUER`
- **Example**: `ATPROTO_OAUTH_RESOURCE=https://cloud.logdate.app`
- **Required**: No

---

## Configuration Examples

### Minimal Development Setup

```bash
# Server (8765 is the default; set PORT only to override it)
PORT=8765
HOST=0.0.0.0

# Database
DATABASE_URL=jdbc:postgresql://localhost:5432/logdate_dev
DATABASE_USER=postgres
DATABASE_PASSWORD=password

# Auth
JWT_SECRET=dev-secret-key-not-for-production-min-32-chars
```

### Production Setup (Google Cloud)

```bash
# Server (Cloud Run sets PORT itself; the container image defaults it to 8080)
PORT=8080
HOST=0.0.0.0

# Database (serverless Postgres, e.g. Neon). Use the pooled endpoint for the app
# runtime; run Flyway migrations against the direct (non-pooler) endpoint.
DATABASE_URL=${DATABASE_URL_FROM_SECRET_MANAGER}
DATABASE_USER=logdate_user
DATABASE_PASSWORD=${DB_PASSWORD_FROM_SECRET_MANAGER}
AUTO_MIGRATE=false

# Auth
JWT_SECRET=${JWT_SECRET_FROM_SECRET_MANAGER}
ATPROTO_SIGNING_KEY_KEK=${ATPROTO_SIGNING_KEY_KEK_FROM_SECRET_MANAGER}
ATPROTO_SESSION_SECRET=${ATPROTO_SESSION_SECRET_FROM_SECRET_MANAGER}

# Public identity, release, and internal health
LOGDATE_PUBLIC_ORIGIN=https://cloud.logdate.app
ATPROTO_PDS_SERVICE_URL=https://cloud.logdate.app
LOGDATE_EXPECT_FIRST_PARTY=true
LOGDATE_DEPLOYMENT_KIND=first_party
RELEASE_VERSION=logdate-server@${FULL_GIT_SHA}
HEALTH_INTERNAL_TOKEN=${HEALTH_INTERNAL_TOKEN_FROM_SECRET_MANAGER}
BILLING_PROVIDER=stripe

# Android passkeys
WEBAUTHN_ALLOWED_ORIGINS=https://cloud.logdate.app,android:apk-key-hash:${ANDROID_CERT_SHA256_BASE64URL}
ANDROID_CERT_FINGERPRINTS=${ANDROID_CERT_SHA256_COLON_HEX}

# Encryption
SERVER_ENCRYPTION_KEY=${ENCRYPTION_KEY_FROM_SECRET_MANAGER}
SERVER_ENCRYPTION_KEY_ID=prod-key-2026-01
ENCRYPTION_MODE=AT_REST_ONLY
SERVER_ENCRYPTION_ENABLED=true
ALLOW_PASSTHROUGH_CLIENT_CIPHERTEXT=true

# GCS
GCS_PROJECT_ID=logdate-prod
GCS_BUCKET_NAME=logdate-media-prod
GCS_MEDIA_KMS_KEY=projects/logdate-prod/locations/us/keyRings/media/cryptoKeys/media-encryption
SYNC_MEDIA_SIGNED_URLS=true
SYNC_MEDIA_SIGNED_URL_TTL_HOURS=1

# Maintenance
SYNC_TOMBSTONE_PURGE_ENABLED=true
SYNC_TOMBSTONE_RETENTION_DAYS=30
SYNC_TOMBSTONE_PURGE_INTERVAL_HOURS=24
```

### E2EE Mode (End-to-End Encryption)

```bash
# All production settings above, plus:
ENCRYPTION_MODE=E2EE_REQUIRED
ALLOW_PASSTHROUGH_CLIENT_CIPHERTEXT=true

# Note: This rejects plaintext uploads, requires clients to encrypt
```

---

## Security Best Practices

1. **Never commit secrets to version control**
   - Use `.env` files (add to `.gitignore`)
   - Use secret managers (GCP Secret Manager, AWS Secrets Manager, etc.)
   - Use environment variables in CI/CD pipelines

2. **Rotate secrets periodically**
   - `JWT_SECRET`: Rotate quarterly
   - `ATPROTO_SIGNING_KEY_KEK`: Never change in place; follow the two-deploy move under `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY`
   - `ATPROTO_SESSION_SECRET`: Rotate as needed; rotating signs out every AT Protocol client session
   - `SERVER_ENCRYPTION_KEY`: Support multiple keys via key rotation (increment `SERVER_ENCRYPTION_KEY_ID`)
   - Database passwords: Rotate annually or after security incidents

3. **Use strong values**
   - `JWT_SECRET`: Minimum 32 characters, random
   - `ATPROTO_SIGNING_KEY_KEK`: Minimum 32 characters, random, and independent of `JWT_SECRET` for new deployments
   - `ATPROTO_SESSION_SECRET`: Minimum 32 characters, random, independent of every other secret
   - `SERVER_ENCRYPTION_KEY`: Use `openssl rand -base64 32` for AES-256
   - Database passwords: 16+ characters, random

4. **Validate in production**
   - Ensure all required variables are set
   - Check secret manager integration
   - Test encryption/decryption before deploying

5. **Monitor access**
   - Log failed authentication attempts
   - Monitor encryption errors
   - Alert on unusual database access patterns

---

## Troubleshooting

### Common Issues

**"SERVER_ENCRYPTION_KEY not configured"**

- Set `SERVER_ENCRYPTION_KEY` environment variable
- Or disable encryption with `SERVER_ENCRYPTION_ENABLED=false` (not recommended for production)

**"SERVER_ENCRYPTION_KEY must be base64-encoded"**

- Ensure key is valid base64
- Generate new key: `openssl rand -base64 32`

**"SERVER_ENCRYPTION_KEY must decode to 16, 24, or 32 bytes"**

- Key must be exactly 128, 192, or 256 bits
- Use AES-256 (32 bytes): `openssl rand -base64 32`

**Database connection fails**

- Verify `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` are correct
- Check database server is running and accessible
- Verify firewall rules allow connection

**GCS access denied**

- Verify service account has Storage Object Admin role
- Check `GCS_PROJECT_ID` and `GCS_BUCKET_NAME` are correct
- Ensure Application Default Credentials are configured

---

## See Also

- [Google Cloud Production Setup](./google-cloud-production.md)
- [Google Cloud Architecture](./google-cloud-architecture.md)
- [OpenAPI](./openapi.md)
- [Auth V1 API](./auth-v1-api.md)
- [Sync V1 API](./sync-v1-api.md)
- [Encryption Architecture Design](../docs/plans/2026-01-27-encryption-architecture-overhaul-design.md)
