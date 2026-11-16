# Linked device sessions, strict create and moments

A watch does not sign in the way a phone does. The product rule is that nobody signs in on the
watch: the phone already holds the account, so the phone sets the watch up
([the watch product contract](../../docs/explanation/wear-companion.md) explains why). A credential
that lives on a wrist should also not be able to change the profile, delete the account or read a
backup. The server therefore gives a watch its own kind of credential: a **device session** that a
signed-in phone creates on the watch's behalf, that reaches only the sync data, and that the account
owner can end at any time without signing anything else out.

Every endpoint, with examples and every error code, is also listed in the interactive reference at
`/docs` on a running server (see [`openapi.md`](openapi.md)).

All paths below are relative to `/api/v1`.

## Terms used on this page

A **full sign-in** is what a phone or computer gets after a passkey or Google sign-in. Its tokens
are **full-access tokens**: they may call every route the account may call.

A **linked device** is a device that was attached to an account by a full sign-in rather than by
signing in itself. The only linked device is a Wear OS watch. A linked device holds a
**device session**: one row in the server's database that says the device may sync until a given
time, unless it is removed first. Tokens issued for a device session are **device tokens**.

A **link code** is the one-time secret a phone asks the server for and hands to the watch. The watch
exchanges it for a device session.

A **scope** is the set of routes a token may call. There are two: full access, and `sync`.

A **tombstone** is the marker the server keeps after a record is deleted, so that other devices
learn about the deletion from the change feed.

A **marked moment** is a point in time someone flagged, usually with one tap on the watch, to fill
in later. The companion documents describe the product and the phone-to-watch transport:
[the watch product contract](../../docs/explanation/wear-companion.md) and
[the watch and phone sync protocol](../../docs/reference/wear/sync-protocol.md).

## How a device is linked

1. The phone, holding a full-access token, calls `POST /auth/devices/link` and receives a link code
   that is valid for two minutes.
2. The phone passes the code to the watch over its private channel to the watch.
3. The watch calls `POST /auth/devices/link/redeem` with the code and a display name. No
   `Authorization` header is sent, because the code is the credential. The server spends the code,
   creates the device session and returns device tokens.
4. The watch syncs with those tokens and refreshes them through the ordinary
   `POST /auth/token/refresh`.
5. The account owner removes the watch from the phone with `DELETE /auth/devices/{id}`, or the watch
   removes itself with `DELETE /auth/devices/self`.

The watch never sees a passkey, a password or a full-access token at any point.

## Data model

Device sessions and link codes are created by the migration
[`V39__Add_device_sessions.sql`](../src/main/resources/db/migration/V39__Add_device_sessions.sql).
The matching table objects are `DeviceSessionsTable` and `DeviceLinkCodesTable` in
[`Tables.kt`](../src/main/kotlin/app/logdate/server/database/Tables.kt). Every time column holds
milliseconds since the Unix epoch.

`device_sessions` has one row per linked device.

| Column              | Type                                                   | Meaning                                                                                                          |
| ------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| `id`                | `UUID`, primary key                                    | The session ID. It is the `sid` claim in the device's tokens and the `{id}` in `DELETE /auth/devices/{id}`.      |
| `account_id`        | `UUID`, references `accounts(id)`, `ON DELETE CASCADE` | The account the device belongs to. Deleting the account deletes its sessions.                                    |
| `device_name`       | `VARCHAR(120)`                                         | The name the device gave when it redeemed the code, shown to the account owner.                                  |
| `platform`          | `VARCHAR(32)`                                          | A short lowercase identifier. The watch sends `wear_os`.                                                         |
| `created_at`        | `BIGINT`                                               | When the code was redeemed.                                                                                      |
| `last_refreshed_at` | `BIGINT`                                               | When the session's expiry was last moved. It is set at creation and on each renewal, not on every token refresh. |
| `expires_at`        | `BIGINT`                                               | When the session stops working unless it is renewed first.                                                       |
| `revoked_at`        | `BIGINT`, nullable                                     | When the session was removed. A removed session keeps its row.                                                   |

The index `idx_device_sessions_account` covers `account_id`. A session is **active** when
`revoked_at` is null and `expires_at` is in the future.

`device_link_codes` has one row per minted code.

| Column        | Type                                                   | Meaning                                                                     |
| ------------- | ------------------------------------------------------ | --------------------------------------------------------------------------- |
| `code_hash`   | `VARCHAR(64)`, primary key                             | The SHA-256 hash of the code's 32 random bytes, as lowercase hexadecimal.   |
| `account_id`  | `UUID`, references `accounts(id)`, `ON DELETE CASCADE` | The account that minted the code.                                           |
| `created_at`  | `BIGINT`                                               | When the code was minted. The hourly mint limit counts rows by this column. |
| `expires_at`  | `BIGINT`                                               | Two minutes after `created_at`.                                             |
| `redeemed_at` | `BIGINT`, nullable                                     | When the code was spent.                                                    |
| `session_id`  | `UUID`, nullable                                       | The session the code created.                                               |

The indexes are `idx_device_link_codes_account_created` on `(account_id, created_at)` and
`idx_device_link_codes_expires` on `expires_at`.

The server never stores a link code, only its hash, so a copy of the database cannot be used to link
a device. Code rows are deleted lazily: each mint first deletes every row whose `expires_at` is more
than one hour in the past, which is the point where a row no longer counts toward any limit.

The storage interface is `DeviceSessionRepository` in
[`DeviceSessionRepository.kt`](../src/main/kotlin/app/logdate/server/devices/DeviceSessionRepository.kt).
A server with a database uses
[`PostgreSQLDeviceSessionRepository`](../src/main/kotlin/app/logdate/server/database/PostgreSQLDeviceSessionRepository.kt);
a server started without one uses `InMemoryDeviceSessionRepository` from the same file, so linking
works on a development server and is forgotten when it stops.

A moment is a record in the account's repository under the collection
`studio.hypertext.logdate.moment`, and it is indexed for sync in the existing
`logdate_collection_records` table with `collection = 'moment'`. Moments therefore draw their
server versions from the same per-account sequence (`logdate_collection_states.last_version`) as
entries and journals.

## Tokens

LogDate tokens are JWTs signed with HMAC-SHA256 by
[`JwtTokenService`](../src/main/kotlin/app/logdate/server/auth/JwtTokenService.kt). An access token
lasts one hour and a refresh token lasts 30 days. Three claims matter here.

| Claim | On which tokens    | Meaning                                                                                                                                |
| ----- | ------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| `jti` | Every token        | A unique ID. Two tokens minted in the same second for the same account are still different strings, so each can be revoked separately. |
| `sid` | Device tokens only | The device session's ID.                                                                                                               |
| `scp` | Device tokens only | The scope. The only value is `sync`. A full-access token carries no `scp` claim.                                                       |

The decoder applies three rules, each of which fails closed:

- Claims the build does not know are ignored once the signature verifies. This is what allows a
  later release to add a claim without invalidating tokens that an earlier build must still read.
- An `scp` value the build does not know makes the token invalid. A future scope is never mistaken
  for full access by an older build.
- `sid` and `scp=sync` must appear together. A token with only one of them is invalid.

`validateAccessToken` and `validateRefreshToken` return an account only for full-access tokens. A
route that should accept a device token has to ask for the claims explicitly, which is the basis of
the scope model below.

## Device routes

The device routes live in
[`DeviceRoutes.kt`](../src/main/kotlin/app/logdate/server/routes/DeviceRoutes.kt) and the rules
behind them in
[`DeviceLinkService.kt`](../src/main/kotlin/app/logdate/server/devices/DeviceLinkService.kt). The
wire types are in
[`DeviceLinkModels.kt`](../../shared/model/src/commonMain/kotlin/app/logdate/shared/model/devices/DeviceLinkModels.kt).
Errors on these routes use the auth envelope, `{ "error": { "code": "...", "message": "..." } }`.
The server omits response fields that equal their default value, so `"current": false` and a null
`refreshToken` do not appear.

| Method and path                  | Who may call it                   | Success                                    |
| -------------------------------- | --------------------------------- | ------------------------------------------ |
| `POST /auth/devices/link`        | Full-access token                 | `200` with a link code                     |
| `POST /auth/devices/link/redeem` | Anyone holding a valid code       | `200` with a device session and its tokens |
| `GET /auth/devices`              | Full-access token or device token | `200` with the active linked devices       |
| `DELETE /auth/devices/self`      | Device token                      | `204`                                      |
| `DELETE /auth/devices/{id}`      | Full-access token                 | `204`                                      |

### Create a link code

`POST /auth/devices/link` takes no body. The response is sent with `Cache-Control: no-store`.

```json
{ "code": "q3J9xV0mH5tY2bW8nK1pR6sD4fG7hL0zC2vB5nM8aQ4", "expiresAt": 1760000120000 }
```

`code` is 32 bytes from a cryptographic random source, encoded as URL-safe Base64 without padding
(43 characters). `expiresAt` is 120 seconds after the request.

| Status and code                | When                                                                 |
| ------------------------------ | -------------------------------------------------------------------- |
| `401 INVALID_TOKEN`            | The token is missing, malformed or expired.                          |
| `403 DEVICE_SCOPE_DENIED`      | A device token was sent. A linked device cannot link another device. |
| `409 DEVICE_LIMIT_REACHED`     | The account already has five active device sessions.                 |
| `429 DEVICE_LINK_RATE_LIMITED` | The account minted ten codes in the last hour.                       |

The device limit is checked before the hourly limit, so an account at five devices always sees
`409`.

### Redeem a link code

`POST /auth/devices/link/redeem` is unauthenticated. The response is sent with
`Cache-Control: no-store`.

```json
{
  "code": "q3J9xV0mH5tY2bW8nK1pR6sD4fG7hL0zC2vB5nM8aQ4",
  "deviceName": "Pixel Watch 3",
  "platform": "wear_os"
}
```

`platform` is optional and defaults to `wear_os`. It must match `[a-z][a-z0-9_]{0,31}`.
`deviceName` is trimmed and must then be 1 to 120 characters with no control characters.

```json
{
  "accountId": "0b8f6f0e-6f0a-4a57-9b0e-3d1a5f2c7e90",
  "sessionId": "8b0f5a4e-2c1d-4f3a-9e8b-7c6d5e4f3a2b",
  "tokens": { "accessToken": "...", "refreshToken": "..." }
}
```

| Status and code            | When                                                                                                                        |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `400 INVALID_REQUEST`      | The body is not the expected JSON.                                                                                          |
| `400 LINK_CODE_INVALID`    | The code is not 32 bytes of canonical Base64, is unknown, has expired, or was already spent. The server does not say which. |
| `400 VALIDATION_ERROR`     | `deviceName` or `platform` is invalid. The code is not spent.                                                               |
| `409 DEVICE_LIMIT_REACHED` | The account reached five active device sessions after the code was minted. The code is not spent.                           |
| `429 RATE_LIMIT_EXCEEDED`  | More than ten redeem requests came from one client address within 60 seconds.                                               |

A code works exactly once. The store makes that true with a guarded update inside one transaction:
it reads the code row, then runs an update that only matches while `redeemed_at` is null and
`expires_at` is in the future, and it creates the session only if that update changed exactly one
row. When two requests race with the same code, the second one's update matches nothing after the
first commits, and it answers `LINK_CODE_INVALID`. The guard counts changed rows instead of using
`UPDATE ... RETURNING` so the same statement also runs on the H2 database used by
[`PostgreSQLDeviceSessionRepositoryTest`](../src/test/kotlin/app/logdate/server/database/PostgreSQLDeviceSessionRepositoryTest.kt).

Because the code is spent before the response is sent, a response that is lost on the way to the
watch spends the code. The watch cannot retry with it; the phone mints another.

### List linked devices

`GET /auth/devices` returns the account's active device sessions, oldest first. Removed and expired
sessions are not listed.

```json
{
  "devices": [
    {
      "id": "8b0f5a4e-2c1d-4f3a-9e8b-7c6d5e4f3a2b",
      "deviceName": "Pixel Watch 3",
      "platform": "wear_os",
      "createdAt": 1760000000000,
      "lastRefreshedAt": 1760000000000,
      "expiresAt": 1762592000000,
      "current": true
    }
  ]
}
```

`current` is `true` on the entry whose session made the request, which is how a watch finds itself
in the list. A phone never sees `current`.

### Remove a device

`DELETE /auth/devices/{id}` ends another device's session from a full sign-in. `{id}` must be a
UUID (`400 VALIDATION_ERROR` otherwise). A session that does not belong to the account answers
`404 DEVICE_NOT_FOUND`. Removing a session that was already removed answers `204` again, so the
call is safe to retry. A device token answers `403 DEVICE_SCOPE_DENIED`: a watch cannot remove a
different watch.

`DELETE /auth/devices/self` ends the calling device's own session. A full-access token answers
`400 NOT_A_LINKED_DEVICE`, because a full sign-in ends with `POST /auth/logout` instead. Repeating
the call after it succeeded answers `401 DEVICE_SESSION_REVOKED` on the server instance that handled
the removal, since the token no longer belongs to an active session. Another instance may answer
`204` again until its cached answer for the session expires (see
[Removal and the 30-second cache](#removal-and-the-30-second-cache)); the removal itself is
idempotent, so either answer means the session is ended.

### What the existing auth routes do for a device

`POST /auth/token/refresh` accepts a device refresh token in the same request body as any other
refresh token. For a device token the server reads the session from the store on every call; it
never uses the cache described below. If the session is missing, removed, expired or belongs to a
different account, the answer is `401 DEVICE_SESSION_REVOKED`. Otherwise the response carries a new
access token, and, only when the session had less than 15 days left, a new refresh token as well:

```json
{ "success": true, "data": { "accessToken": "...", "refreshToken": "..." } }
```

A device must store `refreshToken` whenever it is present. Renewal moves `expires_at` to 30 days
from now, so a watch that syncs at least once every 30 days stays linked indefinitely, and a watch
that is switched off for longer has to be linked again. A refresh does not invalidate the refresh
token it used, so a response that is lost in transit leaves the watch with a token that still
works.

`GET /auth/me` accepts a device token and returns the account as it does for a phone. The `tokens`
in its response are device tokens for the same session, never full-access tokens, so this route
cannot be used to turn a linked device into a full sign-in.

## The scope model

Scope is deny by default. A device token is refused on every route unless that route opts in, and
opting in is an explicit `allowSyncScope = true` argument to
[`AccessTokenVerifier.verify`](../src/main/kotlin/app/logdate/server/auth/AccessTokenVerifier.kt).
The reason for building it this way round is that a new route written without thinking about
watches is closed to them automatically. The alternative, a list of routes a watch may not call,
would have left every future route open until someone remembered to add it.

The verifier checks in this order: a full-access token is granted everywhere; anything that is not
a well-formed device token is invalid; a device token on a route that did not opt in is denied by
scope; and a device token whose session is not active is refused as revoked. A removed watch that
calls a route outside its scope therefore sees the scope refusal, not the revocation.

A device token is accepted on these routes and on no others:

| Group                              | Routes                                                                                                                                                       |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Entries                            | `PUT`, `GET`, `HEAD`, `PATCH` and `DELETE /contents/{contentId}`; `GET /contents`                                                                            |
| Journals                           | `PUT`, `GET`, `PATCH` and `DELETE /journals/{journalId}`; `GET /journals`                                                                                    |
| Links between entries and journals | `POST`, `GET` and `DELETE /associations`; `PUT` and `DELETE /associations/{journalId}/{contentId}`                                                           |
| Drafts                             | `PUT` and `DELETE /drafts/{draftId}`; `GET /drafts/changes`                                                                                                  |
| Media                              | `POST /media`; `GET /media/receipts/{intent}`; `GET` and `DELETE /media/{mediaId}`; `GET /media/{mediaId}/binary`                                            |
| Moments                            | `PUT`, `GET`, `PATCH` and `DELETE /moments/{momentId}`; `GET /moments`                                                                                       |
| Sync status                        | `GET /ops/sync/status`                                                                                                                                       |
| Account and devices                | `GET /auth/me`; `GET /auth/devices`; `DELETE /auth/devices/self`                                                                                             |
| Personal space                     | The `app.logdate.space.*` methods under `/xrpc`: `putRecord`, `deleteRecord`, `getRecord`, `listRepoOps`, `uploadBlob`, `getBlob` and `getBlobUploadReceipt` |

The personal space methods are on the list because a linked watch is one of the owner's own
devices, and an account whose journal lives in its personal space syncs through those methods
rather than through the per-kind routes. The per-kind sync routes serve only accounts in `legacy`
storage, and they refuse a watch on any other account exactly as they refuse a phone:

- An account whose journal was moved from legacy storage into its personal space answers
  `403 SYNC_FORMAT_UPGRADE_REQUIRED`.
- An account in any other storage mode, including one the server does not recognise, answers
  `403 SYNC_STORAGE_MODE_MISMATCH`.
- A legacy account whose journal is being checked just before such a move answers
  `503 SYNC_MIGRATION_IN_PROGRESS` with `Retry-After: 60` to every request that is not a `GET`.
  `GET` requests are still served.

The check is `requireLegacyStorage` in
[`SyncHelpers.kt`](../src/main/kotlin/app/logdate/server/routes/sync/SyncHelpers.kt).
[`sync-v1-api.md`](sync-v1-api.md#storage-modes) describes storage modes and what an app does with
each code, and [the personal space sync reference](../../docs/reference/personal-space-sync.md)
describes the methods.

Everything else refuses a device token. That includes changing or deleting the profile, passkeys,
email verification, minting a link code, removing another device, merging journals
(`POST /journals/{journalId}/merge`), backups, location history, diagnostic reports, quota,
billing, cloud transcription, identity and signing-key operations, device enrollment, encrypted
account-key envelopes, the account's device registry, the maintenance and metrics routes, and the
AT Protocol and OAuth endpoints. The `/auth` routes and the routes that share the sync helpers
answer `403 DEVICE_SCOPE_DENIED`. The remaining routes only ever look for a full-access token, so
they refuse a device token with the same `401` or `403` they give any token they do not recognise.

The allowlist is enforced by a test rather than by convention.
[`LinkedDeviceRouteScopeTest`](../src/test/kotlin/app/logdate/server/routes/LinkedDeviceRouteScopeTest.kt)
reads the running server's routing table, sends a device token to every protected route, and fails
if a route outside the list accepts it. A route that is neither documented in the OpenAPI document
nor classified in that test also fails, so a new route cannot be added without a decision about
whether a watch may call it.

## Removal and the 30-second cache

Every request from a linked device has to confirm that its session is still active. Reading the
database on each request would add a query to every sync call, so
[`DeviceSessionGuard`](../src/main/kotlin/app/logdate/server/devices/DeviceSessionGuard.kt) caches
the answer for each session for 30 seconds, in memory, per server instance.

Removing a device clears the cached answer on the instance that handled the removal, so on that
instance the device's next request is refused at once. Other instances notice when their cached
answer expires. On a deployment with more than one instance, a removed device can therefore keep
reaching the sync routes and the personal space methods on the other instances for up to
30 seconds. The same window applies to a session that expires.

Token refresh is not cached. A removed device cannot obtain a new access token from any instance,
so the 30-second window is the full extent of the delay, and it only applies to an access token the
device already held.

A device whose session is not active is told so explicitly, with `401 DEVICE_SESSION_REVOKED`,
rather than with the generic `401` an expired token gets. A watch uses that code to stop syncing
and to treat itself as unlinked. A server or test fixture built without a session store uses
`DeviceSessionGuard.Disabled`, which treats every session as inactive.

## Limits

| Limit                                                     | Value                                                     | Where it is defined                                                     |
| --------------------------------------------------------- | --------------------------------------------------------- | ----------------------------------------------------------------------- |
| Link code entropy                                         | 32 random bytes (256 bits)                                | `DeviceLinkService.LINK_CODE_BYTES`                                     |
| Link code lifetime                                        | 120 seconds                                               | `DeviceLinkService.LINK_CODE_LIFETIME_MS`                               |
| Link code uses                                            | One                                                       | `PostgreSQLDeviceSessionRepository.redeemLinkCode`                      |
| Active device sessions per account                        | 5                                                         | `DeviceLinkService.MAX_ACTIVE_DEVICES`                                  |
| Link codes minted per account                             | 10 in any 60 minutes                                      | `DeviceLinkService.MAX_LINK_CODES_PER_HOUR`, `LINK_CODE_RATE_WINDOW_MS` |
| Redeem requests per client address                        | 10 in any 60 seconds                                      | `DEVICE_LINK_REDEEM_RATE_LIMIT` in `DeviceRoutes.kt`                    |
| Device name                                               | 1 to 120 characters after trimming, no control characters | `DeviceLinkService.MAX_DEVICE_NAME_LENGTH`                              |
| Platform                                                  | `[a-z][a-z0-9_]{0,31}`                                    | `DeviceLinkService.PLATFORM`                                            |
| Device session lifetime                                   | 30 days                                                   | `DeviceLinkService.SESSION_LIFETIME_MS`                                 |
| Renewal window                                            | A refresh with less than 15 days left renews the session  | `DeviceLinkService.SESSION_RENEWAL_WINDOW_MS`                           |
| Access token lifetime                                     | 1 hour                                                    | `JwtTokenService.ACCESS_TOKEN_DURATION`                                 |
| Refresh token lifetime                                    | 30 days                                                   | `JwtTokenService.REFRESH_TOKEN_DURATION`                                |
| Session cache                                             | 30 seconds per server instance                            | `DeviceSessionGuard.cacheTtl`                                           |
| Moment payload                                            | 65,536 bytes of UTF-8, starting with `LDSE2:`             | `MAX_MOMENT_PAYLOAD_LENGTH` in `SyncMomentRoutes.kt`                    |
| Moment schema version                                     | 1 or higher                                               | `rejectInvalidMoment` in `SyncMomentRoutes.kt`                          |
| Change feed page for entries, journals, links and moments | 200 by default, clamped to 1 through 500                  | `DEFAULT_SYNC_PAGE_SIZE`, `MAX_SYNC_PAGE_SIZE`, `resolvePageSize`       |
| Change feed page for drafts                               | 100 by default; the caller's `limit` is not clamped       | `DRAFT_SYNC_PAGE_SIZE`                                                  |

The device limit is enforced twice, when a code is minted and again when it is redeemed, because
five codes minted in quick succession would otherwise each pass the first check.

The redeem limit is keyed on a hash of the client address and held in memory by each instance. A
link code has 256 bits of entropy, so it cannot be guessed; the limit exists only to bound how much
guessing traffic reaches the database.

## Create-only uploads

A watch records while it is offline and uploads later, sometimes hours later and sometimes twice,
because a response was lost. In that time the phone may have edited the same entry or deleted it.
An ordinary `PUT` would overwrite the phone's edit. A create-only upload writes the record if, and
only if, nothing holds its ID, and otherwise changes nothing.

There are two ways to ask for one, and they differ in what they answer for an ID that was deleted.

**`?ifAbsent=true`** is the strict form and the one a watch uses. A live ID answers `412` and a
deleted ID answers `410`, and in both cases nothing is written: no repository commit, no new server
version and no entry in the change feed. The `410` tells the device to drop its local copy instead
of uploading it again.

**`If-None-Match: *`** is the header form. Clients send it when they upload an entry or journal
that was written offline, so that a retried upload cannot replace a newer copy; the Kotlin sync
client sets it on its entry and journal uploads
([`CloudContentRequests.kt`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/cloud/CloudContentRequests.kt),
[`CloudJournalRequests.kt`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/cloud/CloudJournalRequests.kt)).
A live ID answers the same `412`. A deleted ID is handled as an ordinary write to that ID would be,
which depends on the collection.

When a request carries both, `?ifAbsent=true` decides.

| Record state             | Plain `PUT`             | `If-None-Match: *`      | `?ifAbsent=true`     |
| ------------------------ | ----------------------- | ----------------------- | -------------------- |
| Never written            | `201`                   | `201`                   | `201`                |
| Live                     | `200`, replaced         | `412 <KIND>_EXISTS`     | `412 <KIND>_EXISTS`  |
| Deleted entry or journal | `409 DELETION_CONFLICT` | `409 DELETION_CONFLICT` | `410 <KIND>_DELETED` |
| Deleted moment           | `201`, recreated        | `201`, recreated        | `410 MOMENT_DELETED` |

`<KIND>` is `CONTENT`, `JOURNAL` or `MOMENT`. A `201` carries a `Location` header and the usual
upload response with the new `serverVersion`.

Entries and journals have a permanent **deletion fence**: once an ID is deleted, no ordinary write
may bring it back, and the tombstone is never purged. That is why the header form answers
`409 DELETION_CONFLICT` for them. The `409` and the `410` both leave the record deleted; the
difference is what the client is told. `DELETION_CONFLICT` means "an edit lost to a deletion, keep
it for the person to review", which is right for an app holding an unsaved edit. `<KIND>_DELETED`
means "this capture was already removed, drop it", which is right for a watch retrying an upload.

Moments are the one collection outside the deletion fence. A plain `PUT` or an `If-None-Match: *`
upload to a deleted moment ID recreates it, and a deleted moment's tombstone is purged once it is
older than the tombstone retention period (`SYNC_TOMBSTONE_RETENTION_DAYS`, 30 days by default).
For moments, then, `?ifAbsent=true` is the only form that refuses to resurrect, and it can do so
only while the tombstone still exists. A strict create for a moment whose tombstone has been purged
succeeds as a new record.

How long that protection lasts therefore depends on when the purge runs, not only on the retention
period. The purge is a scheduled maintenance job
([`SyncMaintenance.kt`](../src/main/kotlin/app/logdate/server/SyncMaintenance.kt)) that runs every
`SYNC_TOMBSTONE_PURGE_INTERVAL_MINUTES` (60 by default), so a tombstone survives for the retention
period plus up to one interval. Setting `SYNC_TOMBSTONE_PURGE_ENABLED=false` stops the job, and a
deleted moment then stays protected for as long as the job stays off. In the other direction, a full
sign-in can purge its own account's moment tombstones early through the hidden
`POST /ops/sync/tombstones:purge?retentionDays=` route, whose shortest retention is one day (see
[the maintenance purge contract](sync-v1-api.md#maintenance-purge-contract)).
[`environment-variables.md`](environment-variables.md) describes the retention period and the
switch.

The strict form is a separate query parameter, and the header's answers are unchanged. Existing
clients send `If-None-Match: *` and act on its answers, and the two answers for a deleted ID ask a
client to do different things. A server that predates the
parameter ignores it and performs a plain overwrite, which is the opposite of what the caller
wanted. A client must therefore see `conditionalCreateV1` in the server descriptor before it sends
`?ifAbsent=true`.

### Error bodies and headers

The sync routes use the sync error envelope
([`SimpleErrorResponse`](../src/main/kotlin/app/logdate/server/responses/ApiResponse.kt)), and
the details of a refused create travel in its `details` map as strings, not in a separate body
shape:

```json
{
  "code": "CONTENT_EXISTS",
  "message": "Content already exists",
  "details": { "serverVersion": "42" }
}
```

```json
{
  "code": "CONTENT_DELETED",
  "message": "Content was deleted",
  "details": { "deletedAt": "1760000000000", "serverVersion": "43" }
}
```

`serverVersion` in a `412` is the version of the record that holds the ID, so a device can tell
whether the server already has what it was about to send.

### Where strict create applies

| Route                                     | Strict behavior                                |
| ----------------------------------------- | ---------------------------------------------- |
| `PUT /contents/{contentId}?ifAbsent=true` | `412 CONTENT_EXISTS` or `410 CONTENT_DELETED`  |
| `PUT /journals/{journalId}?ifAbsent=true` | `412 JOURNAL_EXISTS` or `410 JOURNAL_DELETED`  |
| `PUT /moments/{momentId}?ifAbsent=true`   | `412 MOMENT_EXISTS` or `410 MOMENT_DELETED`    |
| `POST /associations?ifAbsent=true`        | Always `200`; each link is reported separately |
| `HEAD /contents/{contentId}`              | Reports the state without writing              |

`POST /associations?ifAbsent=true` takes the same batch as the plain route and writes only the
links that were never written. The response sorts the rest into two lists, so one taken link does
not fail the batch:

```json
{
  "uploadedCount": 1,
  "uploadedAt": 1760000000000,
  "existing": [{ "journalId": "journal-1", "contentId": "a" }],
  "deleted": [
    { "journalId": "journal-1", "contentId": "b", "deletedAt": 1759990000000, "serverVersion": 40 }
  ]
}
```

`HEAD /contents/{contentId}` lets a device skip an upload the server already has. It answers `200`
for a live entry, `410` for a deleted one and `404` for an ID the server has no record of. `200` and
`410` carry `X-LogDate-Server-Version`, and `410` also carries `X-LogDate-Deleted-At`. A `HEAD`
response has no body, so a refused token has no error code to read. There is no `HEAD` for journals
or moments.

### How the create is made atomic

Two uploads of the same new ID can arrive at the same moment, on different server instances. Exactly
one must be told `201`.

[`RepoConditionalWriter`](../src/main/kotlin/app/logdate/server/logdate/RepoConditionalWriter.kt)
first reads the sync index, so a duplicate or a deleted ID is answered without touching the
account's repository at all. If the ID looks free it calls the repository engine's
`putRecordIfAbsent`
([`DefaultRepoEngine`](../../shared/atproto-repo/src/commonMain/kotlin/studio/hypertext/atproto/repo/DefaultRepoEngine.kt)),
which fails when the key appeared in the meantime. That check is atomic across writers because
every repository write ends with a compare-and-swap on the repository head: the loser of a race
finds the head moved, retries, sees the key present and reports `412`. On PostgreSQL the repository
write and the index row are committed in one database transaction, so a failure between them leaves
neither behind.

That transaction runs once.
[`PostgreSQLRepoBlockStore.write`](../src/main/kotlin/app/logdate/server/database/PostgreSQLRepoBlockStore.kt)
limits it to a single attempt, so a failed write is reported to the caller and the database layer
never replays it. When the transaction fails, the store also clears its cache of recently read
repository blocks, so a block the rolled-back write placed there is never served afterwards.

The index row and the record are committed together. An index row visible to the change feed before
its record exists would let another device page past a change it cannot yet read.

## Moments

Moments are stored and synced by
[`SyncMomentRoutes.kt`](../src/main/kotlin/app/logdate/server/routes/sync/SyncMomentRoutes.kt) and
[`RepoBackedLogDateMomentsRepository`](../src/main/kotlin/app/logdate/server/logdate/RepoBackedLogDateMomentsRepository.kt).
The wire types are in
[`SyncModels.kt`](../../shared/model/src/commonMain/kotlin/app/logdate/shared/model/sync/SyncModels.kt).

Moments have a collection of their own, rather than being a new entry type, so that the entry and
journal change feeds never carry one. An app that was released before moments existed never
requests `/moments`, never downloads a record it cannot interpret, and sees no change in its counts
from `GET /ops/sync/status`.

The server stores five fields of a moment in the clear: `id`, `capturedAt`, `lastUpdated`,
`deviceId` and `schema`. It does not order or page by the two times; the change feed pages by server
version. Everything else about a moment is inside `payload`, one value encrypted on the device with
the `LDSE2:` envelope described in
[the encryption formats reference](../../docs/reference/encryption-formats.md). The server checks
that the payload starts with `LDSE2:` and fits the size limit; it cannot read it. `schema` is the
version of the payload's contents, chosen by the apps.

| Method and path              | Purpose                                               | Success                                                 |
| ---------------------------- | ----------------------------------------------------- | ------------------------------------------------------- |
| `PUT /moments/{momentId}`    | Create or replace a moment                            | `201` with `Location` when created, `200` when replaced |
| `GET /moments?since=&limit=` | Page changes and tombstones                           | `200`                                                   |
| `GET /moments/{momentId}`    | Read one live moment                                  | `200`                                                   |
| `PATCH /moments/{momentId}`  | Replace the payload, with optional conflict detection | `200`                                                   |
| `DELETE /moments/{momentId}` | Delete a moment                                       | `204`                                                   |

### Create or replace

```json
{
  "id": "6f0b2d4e-8a1c-4e3f-b5d7-9c2a4e6f8b10",
  "capturedAt": 1760000000000,
  "lastUpdated": 1760000000000,
  "deviceId": "watch-device-id",
  "payload": "LDSE2:...",
  "schema": 1
}
```

`id` must equal `momentId` in the path (`400 VALIDATION_ERROR` otherwise). The ID is chosen by the
client and must be a valid AT Protocol record key: 1 to 512 characters from `A-Z a-z 0-9 . _ : ~ -`,
and not `.` or `..`. The apps use UUIDs. `deviceId` and `schema` are optional; `schema` defaults
to 1.

A moment that fails validation answers `400 INVALID_MOMENT`, and `message` says which rule it
broke: the ID is not a record key, the payload does not start with `LDSE2:`, the payload is larger
than 64 KiB, or `schema` is below 1.

The response is `{ "id", "serverVersion", "uploadedAt" }`. `?ifAbsent=true` and `If-None-Match: *`
behave as described under [Create-only uploads](#create-only-uploads).

### Page changes

`GET /moments` works like the entry change feed and uses the same cursor, because moments share the
account's version sequence. `since` defaults to 0 and must be a whole number
(`400 INVALID_PARAMETER` otherwise). `limit` defaults to 200 and is clamped to the range 1 to 500.

```json
{
  "changes": [
    {
      "id": "6f0b2d4e-8a1c-4e3f-b5d7-9c2a4e6f8b10",
      "capturedAt": 1760000000000,
      "lastUpdated": 1760000000000,
      "deviceId": "watch-device-id",
      "payload": "LDSE2:...",
      "schema": 1,
      "serverVersion": 42
    }
  ],
  "deletions": [
    {
      "id": "1c9e7a5b-3d2f-4b6a-8e0c-2f4a6c8e0b1d",
      "deletedAt": 1759990000000,
      "serverVersion": 41
    }
  ],
  "lastTimestamp": 42,
  "hasMore": true
}
```

Send `lastTimestamp` back as the next `since` and keep paging while `hasMore` is present and true.

### Read, update and delete

`GET /moments/{momentId}` returns one live moment in the shape of a `changes` item. A deleted or
unknown ID answers `404 NOT_FOUND`.

`PATCH /moments/{momentId}` replaces `payload` and `schema`, and `capturedAt` when it is sent:

```json
{
  "payload": "LDSE2:...",
  "schema": 1,
  "lastUpdated": 1760000100000,
  "deviceId": "phone-device-id",
  "versionConstraint": { "type": "known", "serverVersion": 42 }
}
```

`versionConstraint` is optional. With `{ "type": "known", "serverVersion": n }`, the update is
applied only if the stored version is still `n`; otherwise the answer is `409 CONFLICT` and nothing
changes. The version check and the write happen inside one per-account lock, so two concurrent
patches with the same known version cannot both succeed. Without a constraint the last write wins.
The response is `{ "id", "serverVersion", "updatedAt" }`.

Patching an ID that does not exist answers `404 NOT_FOUND`. This differs on purpose from entries,
where a `PATCH` to an unknown ID creates the entry. A moment's payload is one opaque encrypted value,
so there is nothing a partial update could sensibly be merged into, and creating a moment from a
patch would hide a client that lost track of what it had uploaded.

`DELETE /moments/{momentId}` answers `204` whether or not the moment existed. Deleting a live moment
leaves a tombstone that appears in `deletions`; deleting an ID that was never written leaves
nothing.

No storage quota is charged for moments, as none is charged for entries. Every moment route also
returns the storage-mode refusals that all per-kind sync routes share:
`403 SYNC_FORMAT_UPGRADE_REQUIRED`, `403 SYNC_STORAGE_MODE_MISMATCH` and
`503 SYNC_MIGRATION_IN_PROGRESS`, as listed under [The scope model](#the-scope-model).

## How clients discover these features

`GET /server/info` returns the server descriptor, and its `protocolFeatures` list names each
optional capability. The constants are in
[`ServerDescriptor.kt`](../../shared/model/src/commonMain/kotlin/app/logdate/shared/model/ServerDescriptor.kt).

| Flag                  | Meaning                                                                                                                             |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `deviceLinkingV1`     | The `/auth/devices` routes exist, tokens may carry `sid` and `scp=sync`, and a removed device answers `401 DEVICE_SESSION_REVOKED`. |
| `conditionalCreateV1` | `?ifAbsent=true` is honored on the four routes above, and `HEAD /contents/{contentId}` exists.                                      |
| `momentsV1`           | The `/moments` routes exist.                                                                                                        |

All three are advertised unconditionally by
[`ServerDescriptorConfig`](../src/main/kotlin/app/logdate/server/ServerDescriptorConfig.kt), on
every kind of deployment, including a development server with no database. A client must still
check them, because it may be talking to a server built from an older release. The check matters
most for `conditionalCreateV1`: a server without it silently overwrites.

## Rollout invariant

Device tokens outlive deployments. A watch holds a refresh token that is valid for 30 days and
renews its own session, so at any moment there are tokens in the field that were minted by an
earlier build and must be read by the current one, and after a rollback, tokens minted by a newer
build that must be read by an older one. Three rules follow.

**Every build that serves traffic must ignore token claims it does not know.** `JwtTokenService`
decodes with `ignoreUnknownKeys = true`, and
`JwtTokenServiceTest` pins it with a token that carries extra claims. If a build that rejected
unknown claims were ever put in front of clients, every device token would fail to decode. The
refresh route would answer `401 INVALID_REFRESH_TOKEN`, which the apps treat as the end of the
session: each watch would discard its credentials and would stay unlinked until its owner linked it
again from the phone. A server must therefore never be rolled back to a build older than tolerant
decoding once any device token exists.

**A rollback must not go past the build that enforces scope.** A build that tolerates unknown
claims but predates `sid` and `scp` would read a device token as an ordinary access token for the
account. For as long as that build served traffic, a watch's token would be accepted on every route
and removing the watch would have no effect. Watches keep working through such a rollback, and the
wider access is available only to something that already holds a device token, which the server
hands to nothing but the linked device itself. That makes a short rollback survivable, but it is a
security change, not a routine one, and it should be kept as brief as possible.

**A new scope value must be deployed everywhere before any token carries it.** The decoder refuses
an `scp` it does not recognise, so tokens with a new scope are rejected, not over-privileged, by an
older build. That is the safe direction, but it means a rollback past the release that introduced a
scope signs out every device holding one.

The same reasoning explains how the features are gated on the client. A watch uploads directly to
the server only when the descriptor lists both `deviceLinkingV1` and `conditionalCreateV1`, so a
server that has sessions but not strict create is never sent an upload that could overwrite. Moments
sit in their own collection behind `momentsV1`, so no released app can be affected by them.

## Other things called "device" on the server

Three unrelated mechanisms have "device" in their route. They are easy to confuse.

- `/auth/devices` is this page: sync-only sessions for linked devices, revocable one by one.
- `/device-enrollments` lets a device that is already signed in approve a new one by scanning the
  pairing QR code the new device shows. The new device receives a full sign-in of its own, sealed in
  an encrypted envelope. It does not create a device session and its tokens are not `scp=sync`.
- `/devices` is the account's registry of the devices that have signed in, with their names and
  when they were last active. It stores no credentials.

## Tests that pin this contract

| Behavior                                                      | Test                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Which routes accept a device token                            | [`LinkedDeviceRouteScopeTest`](../src/test/kotlin/app/logdate/server/routes/LinkedDeviceRouteScopeTest.kt), [`SyncLinkedDeviceAccessTest`](../src/test/kotlin/app/logdate/server/routes/sync/SyncLinkedDeviceAccessTest.kt)                                                                                                                                                                                                                                                                                |
| Linking, listing and removal                                  | [`DeviceLinkRoutesTest`](../src/test/kotlin/app/logdate/server/routes/DeviceLinkRoutesTest.kt), [`DeviceLinkServiceTest`](../src/test/kotlin/app/logdate/server/devices/DeviceLinkServiceTest.kt)                                                                                                                                                                                                                                                                                                          |
| Removal ends access and refresh                               | [`DeviceSessionRevocationTest`](../src/test/kotlin/app/logdate/server/routes/DeviceSessionRevocationTest.kt)                                                                                                                                                                                                                                                                                                                                                                                               |
| A code is redeemed once under concurrency                     | [`DeviceLinkCodePostgresTest`](../src/test/kotlin/app/logdate/server/database/DeviceLinkCodePostgresTest.kt)                                                                                                                                                                                                                                                                                                                                                                                               |
| Token claims, scope and tolerant decoding                     | [`JwtTokenServiceTest`](../src/test/kotlin/app/logdate/server/auth/JwtTokenServiceTest.kt), [`AccessTokenVerifierTest`](../src/test/kotlin/app/logdate/server/auth/AccessTokenVerifierTest.kt)                                                                                                                                                                                                                                                                                                             |
| Create-only answers for each record state                     | [`SyncConditionalCreateRoutesTest`](../src/test/kotlin/app/logdate/server/routes/sync/SyncConditionalCreateRoutesTest.kt), [`ConditionalCreateProbeTest`](../src/test/kotlin/app/logdate/server/logdate/ConditionalCreateProbeTest.kt)                                                                                                                                                                                                                                                                     |
| Concurrent creates on PostgreSQL                              | [`ConditionalCreatePostgresTest`](../src/test/kotlin/app/logdate/server/database/ConditionalCreatePostgresTest.kt)                                                                                                                                                                                                                                                                                                                                                                                         |
| Moment routes, isolation and concurrency                      | [`SyncMomentRoutesTest`](../src/test/kotlin/app/logdate/server/routes/sync/SyncMomentRoutesTest.kt), [`SyncMomentConcurrencyRoutesTest`](../src/test/kotlin/app/logdate/server/routes/sync/SyncMomentConcurrencyRoutesTest.kt), [`MomentUpdatePostgresTest`](../src/test/kotlin/app/logdate/server/database/MomentUpdatePostgresTest.kt)                                                                                                                                                                   |
| Which tombstones are permanent                                | [`CollectionDeletionWinsTest`](../src/test/kotlin/app/logdate/server/database/CollectionDeletionWinsTest.kt)                                                                                                                                                                                                                                                                                                                                                                                               |
| Link, upload, remove, refuse, end to end with the real client | [`WatchDeviceLinkE2ETest`](../../integration/server-client-e2e/src/test/kotlin/app/logdate/integration/e2e/journeys/WatchDeviceLinkE2ETest.kt), [`WatchSessionRefreshClientServerE2ETest`](../../integration/server-client-e2e/src/test/kotlin/app/logdate/integration/e2e/journeys/WatchSessionRefreshClientServerE2ETest.kt), [`ConditionalCreateClientServerE2ETest`](../../integration/server-client-e2e/src/test/kotlin/app/logdate/integration/e2e/journeys/ConditionalCreateClientServerE2ETest.kt) |

[`scripts/wear-companion-smoke.py`](../../scripts/wear-companion-smoke.py) runs the same journey
against a deployed server: it links a device, uploads with strict create, checks the `412` and `410`
answers, removes the device and confirms that both its access token and its refresh token are
refused.
