# JWT authentication

How LogDate Cloud authenticates clients. Reference for operators (rotating
secrets, debugging token issues) and contributors (extending auth flows).

## Overview

LogDate Cloud uses JSON Web Tokens (JWT) for session management and API
authentication. This document describes the token types, their lifecycle,
how they're issued, validated, stored, refreshed and revoked, across the server and
every client platform.

How a person obtains the first token (a passkey, Google, or the restore
credential) is described in [the auth API notes](../server/docs/auth-v1-api.md)
and in the interactive reference at `/docs`. This page starts after that:
what the server hands back and what happens to it.

## Token types

| Type        | Lifetime | Where it's used                                                             | Where it's set                                                |
| ----------- | -------- | --------------------------------------------------------------------------- | ------------------------------------------------------------- |
| **Access**  | 1 hour   | `Authorization: Bearer <token>` on protected routes                         | Returned by signup-complete / signin-complete / token-refresh |
| **Refresh** | 30 days  | Body of `POST /api/v1/auth/token/refresh` and of `POST /api/v1/auth/logout` | Returned alongside the access token at signup/signin          |

Both are HMAC-SHA256-signed JWTs (`alg: HS256`) with the same payload shape.
The `sessionToken` that a passkey sign-up returns from `begin` and sends back
to `complete` is not a JWT: it is an opaque ID of a 15-minute, single-use
record kept by `SessionManager` (in PostgreSQL, or in memory when no database is
configured).

```json
{
  "sub": "<account-id>",
  "iss": "logdate.app",
  "aud": "logdate-api",
  "exp": 1735689600,
  "iat": 1735686000,
  "type": "access" | "refresh",
  "jti": "<random UUID>",
  "did": "did:plc:ewvi7nxzyoun6zhxrhs64oiz",
  "sid": "<device session id>",
  "scp": "sync"
}
```

- `jti` is a unique ID on every token, so two tokens minted in the same
  second are still different strings and can be revoked separately.
- `did` is set for accounts that have a registered AT Protocol identity and
  omitted otherwise. The `iss`/`aud` pair is validated on every token check.
- `sid` and `scp` appear only on **device tokens**, the tokens a linked
  device such as a watch holds instead of a sign-in. `sid` is the device
  session the token belongs to and `scp` is the scope, `sync`. A full-access
  token, the kind a phone or computer gets by signing in, carries neither.
  A token with `scp` but no `sid`, or `sid` but no `scp`, is invalid. A
  token whose `scp` this build does not recognise is invalid.
  [Linked device sessions](../server/docs/device-sessions-api.md) describes
  how device tokens are issued, renewed and ended.

The server reads claims only after the signature verifies, and it ignores
claims it does not know. A later release can therefore add a claim without
invalidating tokens that are still in circulation during a rollback.

The `did` value is the account's AT Protocol DID. Hosted accounts get a
`did:plc` by default; a server configured with `ATPROTO_HOSTED_DID_METHOD=web`
gives each account a hostname-level `did:web` instead, such as
`did:web:alice.logdate.app`. AT Protocol only accepts those two forms, so a
path-based `did:web` such as `did:web:logdate.app:users:alice` never appears
here.

## Token security

### Signing algorithm

- **Algorithm**: HS256 (HMAC with SHA-256)
- **Key**: configurable secret (minimum 32 characters)
- **Implementation**: handcrafted in `JwtTokenService` using
  `javax.crypto.Mac.HmacSHA256` and `kotlinx.serialization.json` for the
  payload. No external JWT library. The surface area is small enough to
  audit; if you change anything in the signer, mirror it in
  `verifiedPayload`, the one place every token type is checked.

### Security best practices

1. **Secret key management**: only ever in environment variables mounted
   from Secret Manager (`logdate-jwt-secret`). Never in source control,
   never in build configs.
2. **Token rotation**: refresh access tokens before they expire (the
   client does this on 401, see [Refresh flow](#refresh-flow)).
3. **Secure storage on client**: platform-specific secure storage
   (Keychain / Android Keystore via `EncryptedSharedPreferences`). See
   [Client-side storage](#client-side-storage).
4. **Transport security**: HTTPS only. With `LOGDATE_ENV=production` the
   server redirects plain HTTP to HTTPS unless `REQUIRE_HTTPS` says
   otherwise; see [environment variables](../server/docs/environment-variables.md).

## API endpoints

### Token refresh

```http
POST /api/v1/auth/token/refresh
Content-Type: application/json

{
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response:**

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}
```

For a full-access refresh token the response carries only a new access token;
the refresh token stays the same until it expires after 30 days. For a device
token the response may also carry a new `refreshToken`, which the device must
store. The server issues one only when the device session is close to the end
of its lifetime and extends the session at the same time.

The refresh endpoint answers `401` in three ways:

| Code                     | Meaning                                                                                  |
| ------------------------ | ---------------------------------------------------------------------------------------- |
| `INVALID_REFRESH_TOKEN`  | The token is missing, malformed, expired, signed by another secret, or of the wrong type |
| `REFRESH_TOKEN_REVOKED`  | The token was revoked by a sign-out, see [Sign-out](#sign-out)                           |
| `DEVICE_SESSION_REVOKED` | A device token whose session was removed or has expired                                  |

All three mean the credential will never work again, and the client should
clear local session state and ask for a sign-in.

### Protected endpoint example

```http
GET /api/v1/auth/me
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

A `401 INVALID_TOKEN` response from a protected endpoint means the access
token is invalid or expired — the client should attempt a refresh and
retry once before bouncing the user to the auth screen.

Device tokens are accepted only on the routes that opt in to the `sync` scope.
On routes that do not accept the `sync` scope, a device token answers
`403 DEVICE_SCOPE_DENIED` from its signature and scope alone, whether or not its
device session is still active. `401 DEVICE_SESSION_REVOKED` comes only from
routes that accept the scope, where the session is checked.

## Server-side implementation

`server/src/main/kotlin/app/logdate/server/auth/JwtTokenService.kt` is the
production implementation, behind the `TokenService` interface:

```kotlin
interface TokenService {
    fun generateAccessToken(accountId: String, did: String? = null): String
    fun generateRefreshToken(accountId: String, did: String? = null): String
    fun generateDeviceAccessToken(accountId: String, did: String?, sessionId: String): String
    fun generateDeviceRefreshToken(accountId: String, did: String?, sessionId: String): String
    fun generateEnrollmentTokens(accountId: String, did: String?, enrollmentId: String, issuedAt: Instant): AccountTokens
    fun generateSessionToken(sessionId: String): String
    fun validateAccessToken(token: String): String?
    fun validateRefreshToken(token: String): String?
    fun decodeAccessToken(token: String): AccessTokenClaims?
    fun decodeRefreshToken(token: String): AccessTokenClaims?
    fun validateSessionToken(token: String): String?
}
```

`InMemoryTokenService` in `server/.../auth/InMemoryTokenService.kt` is wired
in for tests when token cryptography isn't the thing under test.

`validate*Token` returns:

- the `sub` claim string when the token is well-formed, signed correctly,
  not expired, and the `type`/`iss`/`aud` match the call;
- `null` for any other reason (signature mismatch, expired, wrong type,
  malformed) and, for the access and refresh variants, for every device
  token. The client cannot distinguish "wrong signature" from "expired" by
  validation result; it gets a generic 401, which is the right shape — the
  only place the difference matters is server-side debugging via Cloud
  Logging.

`decode*Token` returns an `AccessTokenClaims` (account, DID, device session and
scope) for full-access and device tokens alike. `AccessTokenVerifier` uses it
to decide whether a route accepts a token: a full-access token passes
everywhere, and a device token passes only on routes marked as accepting the
`sync` scope and only while its device session is still active.

Tokens from `generateEnrollmentTokens` are deterministic for a given enrollment
and issue time, so a device-approval request that is retried after a lost
response or a server restart gets the same session back instead of a second one.

### The signer

`JwtTokenService` signs and verifies tokens itself, with no external JWT
library. It is JVM-only, because the server is the only place tokens are
generated or validated; clients treat them as opaque. It supports one
algorithm, HS256, and one shared secret, and is built on
`javax.crypto.Mac.HmacSHA256` and `kotlinx.serialization.json`.
`verifiedPayload` is the single place where a token's signature, expiry, type,
issuer and audience are checked, so a change to the signer must be mirrored
there.

### Error handling

- Invalid tokens return `null` from validation methods.
- Expired tokens are automatically rejected.
- Type mismatches (e.g. using a refresh token where an access token is
  required) are rejected with a logged warning.
- Malformed tokens are caught in the JWT-shape parser and rejected
  without throwing.

## Production configuration

### Environment variables

Required:

```bash
# JWT signing secret. Mounted from Secret Manager (logdate-jwt-secret).
# Minimum 32 characters; ProductionConfigValidator refuses to boot
# below that, with placeholder strings, or unset in production.
JWT_SECRET=<32+ random characters>
```

Outside production an unset `JWT_SECRET` makes the server generate a random
secret for the process, so every token stops working when the process
restarts.

`JWT_SECRET` signs only these tokens. Two other secrets are separate and must
differ from it in production: `ATPROTO_SIGNING_KEY_KEK` encrypts the hosted AT
Protocol signing keys stored in the database, and `ATPROTO_SESSION_SECRET`
signs AT Protocol OAuth session tokens. Rotating `JWT_SECRET` therefore affects
neither. See [environment variables](../server/docs/environment-variables.md).

Token lifetimes are NOT configurable via environment variables. They
live as constants in `JwtTokenService.Companion`:

```kotlin
private val ACCESS_TOKEN_DURATION = 1.hours
private val REFRESH_TOKEN_DURATION = 30.days
private val SESSION_TOKEN_DURATION = 15.minutes
```

Changing them is a code change.

### Generating a fresh JWT_SECRET

```bash
openssl rand -base64 32
```

44 characters of base64 carry 256 bits of entropy, which is the minimum
HS256 key size and is above the 32-character floor the server enforces.

### Rotating JWT_SECRET

```bash
# 1. Generate the new value.
NEW_VALUE=$(openssl rand -base64 32)

# 2. Add a new Secret Manager version.
echo -n "$NEW_VALUE" \
  | gcloud secrets versions add logdate-jwt-secret \
      --project="$PROJECT" --data-file=-

# 3. Force a new Cloud Run revision so it picks up the latest version.
gcloud run services update "$SERVICE" \
  --project="$PROJECT" --region="$REGION" \
  --update-secrets=JWT_SECRET=logdate-jwt-secret:latest
```

**Rotating the secret invalidates every issued token at once**, device tokens
included. Existing sessions on every device will hit a refresh failure on the
next API call and be forced to re-sign-in. Rotate during a low-traffic window
unless you're rotating because the previous secret was compromised, in
which case signing everyone out fast is exactly the goal.

## Client-side storage

`client/datastore/src/commonMain/kotlin/app/logdate/client/datastore/SessionStorage.kt`
is the multiplatform interface every client uses. Every platform binds it
to `SecureSessionStorage`, which keeps the session in the platform's
secure storage: `EncryptedSharedPreferences` under an Android Keystore
master key on Android, Keychain items on iOS, and an AES-GCM-encrypted
preferences node under the device key on desktop. The
[encryption formats reference](./reference/encryption-formats.md#secure-storage)
describes each store.

A session is a `UserSession(accessToken, refreshToken, accountId)`. It is
stored as one JSON record, `session_record_v2`, and is always read together
with the server it came from as an `OriginBoundSession`, so a session is only
used against the server that issued it and changing the configured server
cannot cause one server's tokens to be sent to another.

Code outside `SecureSessionStorage` never touches the secure store directly.
It receives a `SessionStorage` through Koin and calls its session methods.
The storage also provides guarded variants of these methods. A sign-in, a
refresh and a sign-out each capture a snapshot first and commit only if no
later sign-in or sign-out replaced the session in the meantime. This is what
stops a slow refresh from reviving a session the person has just ended.

### Per-backend-URL scoping

Storage keys are scoped by the URL-safe Base64 of the backend URL, for example
`session_record_v2_<b64>`. The client supports multiple backends (cloud,
self-hosted, etc.), and switching backends doesn't pollute another backend's
session. Switching the backend URL effectively logs the user out from the
perspective of the running app.

## Refresh flow

The client manages refresh entirely on its own — there's no
server-driven refresh hint. When an authenticated call returns 401, the
client:

1. Calls `POST /api/v1/auth/token/refresh` with the stored refresh
   token.
2. On success, stores the new access token (and the new refresh token, if
   the response carries one) and retries the original request once.
3. If the server answers that the refresh credential is itself finished
   (`INVALID_REFRESH_TOKEN` or `REFRESH_TOKEN_REVOKED`, and for a linked
   device also `DEVICE_SESSION_REVOKED`), clears the local session so the
   person is asked to sign in again.
4. For any other failure, such as a network error or a 5xx, keeps the
   session and reports the failure. A flaky connection must not sign anyone
   out.

Two classes share this pattern. `AccountSessionRefreshCoordinator` (in
`client/data`) wraps account calls such as reading the profile or removing a
passkey. `SyncTokenRefresher` (in `client/sync`) wraps every upload and
download. A new authenticated call site should go through one of them and not
refresh on its own.

## Sign-out

`AccountSessionRefreshCoordinator.signOut` clears the local session first, so
signing out always succeeds on the device, and then calls
`POST /api/v1/auth/logout` with the refresh token. If that request fails the
failure is logged and the local sign-out stays.

The server records a SHA-256 hash of the revoked refresh token in the
`revoked_refresh_tokens` table (an in-memory set when no database is
configured) and checks it on every refresh. A revoked token answers
`REFRESH_TOKEN_REVOKED` from then on. Revocation applies to the refresh token
only. Access tokens are not checked against it, so an access token that was
already issued keeps working until its hour is up.

## Monitoring and logging

### What the server logs

- **Token generation**: no token value is ever logged. A token in a log is a
  credential leak.
- **Token validation failures**: each cause logs its own warning, so patterns
  can be spotted in Cloud Logging without re-running the validator: an
  invalid format, a failed signature, a type mismatch, and an
  issuer/audience mismatch. An expired token logs at debug level.
- **Sign-in and sign-up**: structured audit entries, described in
  [`audit-schema.md`](../server/docs/audit-schema.md).

A spike in signature failures is the leading indicator of a rotated or leaked
`JWT_SECRET`.

### Metrics

`AuthMetricsRegistry` counts every auth operation (success, error, duration),
errors by code and rate-limit hits. Token refresh is recorded as
`auth.token.refresh`. Both endpoints require a bearer token:
`GET /api/v1/auth/metrics` returns JSON and
`GET /api/v1/auth/metrics/prometheus` returns Prometheus text.

## Troubleshooting

### "Invalid token" / 401 on a request you expected to succeed

1. Check token format on the wire — a JWT has exactly 3 parts
   separated by `.`. A token with 2 parts means the client
   constructed it wrong; a token with 4+ parts means something is
   double-encoding.
2. Verify the `Authorization` header is `Bearer <token>`, not
   `<token>` or `Token <token>`.
3. Confirm the token hasn't expired:
   `base64url-decode the middle segment, look at exp`. (Decoding to
   _read_ is fine; never trust the decoded payload without verifying
   the signature.)
4. Confirm it is the right kind of token. A refresh token sent as a bearer
   token is rejected, and so is a device token on a route that does not accept
   the `sync` scope.
5. A refresh does not check that the account still exists, so a deleted
   account can still obtain a new access token. The protected route then
   answers `404 ACCOUNT_NOT_FOUND`.

### "JWT signature verification failed" in server logs after a deploy

Almost always means `JWT_SECRET` rotated mid-deploy and a client is
presenting a token signed with the old secret. Confirm:

```bash
gcloud secrets versions list logdate-jwt-secret --project=$PROJECT
gcloud run revisions describe <revision> --project=$PROJECT --region=$REGION \
  | grep -A2 'JWT_SECRET'
```

If the live revision points to a different secret version than the
one the token was signed with, the client just needs to re-sign-in;
no server fix.

### Refresh tokens repeatedly rejected

- **Token expired (older than 30 days)** — expected; client should
  prompt sign-in.
- **Token revoked** — the person signed out. The code is
  `REFRESH_TOKEN_REVOKED`.
- **Token was issued by a different signing secret** — see the
  signature-verification troubleshooting above.

### Server clock skew

`exp` and `iat` are absolute epoch seconds. If the Cloud Run
container clock and the client clock disagree by more than the
access token's lifetime (1 hour, the shortest in use), validation
flaps. Cloud Run uses NTP-synced clocks in practice, but a stuck
container with a frozen clock will produce confusing "expired
immediately on issue" errors. Restart the revision if you suspect
this.

## Usage example

```kotlin
// Initialize the service in Koin / DI.
val tokenService: TokenService = JwtTokenService(
    secret = System.getenv("JWT_SECRET"),
    issuer = "logdate.app",
    audience = "logdate-api",
)

// Generate tokens during signup-complete or signin-complete.
val accessToken = tokenService.generateAccessToken("account-123")
val refreshToken = tokenService.generateRefreshToken("account-123")

// Validate inbound tokens on every protected request.
val accountId: String? = tokenService.validateAccessToken(accessToken)
// Returns "account-123" on a valid token, null otherwise.
```

## Cross-platform considerations

The `TokenService` interface is JVM-only — token generation and
validation never need to run anywhere else. The client's
`SessionStorage` interface is Kotlin Multiplatform, with
`SecureSessionStorage` bound on Android, iOS and desktop.

The client treats JWT bytes as opaque — never decode them client-side
to extract `accountId`. Use `getAccountInfo()` instead. Decoding
without verifying the signature is a foot-gun if device storage is
ever tampered with.
