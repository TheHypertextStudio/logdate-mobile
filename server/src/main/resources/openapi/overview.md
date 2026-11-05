LogDate Cloud is the server behind LogDate, a home to document, reflect on, and share memories. It keeps a person's entries, journals, photos and voice recordings in sync across their devices, holds encrypted backups, and, when it is switched on, gives each account an AT Protocol identity so a journal is portable. Journaling is supporting functionality within that broader product purpose.

This reference is written for anyone building against that server: a developer writing their first LogDate client, a hobbyist running their own copy on a weekend, or someone integrating an AT Protocol tool. It assumes you can read JSON and run `curl`, and nothing more. Every concept is explained the first time you meet it, every endpoint has a real example you can paste, and every error tells you what to do next.

The sidebar on the left groups endpoints by what they are for. Each endpoint page has three parts: what it does, what you send, and every response it can give. The **Try it** button sends a real request to the server you are looking at.

## Your first request

The only endpoint you need before you have an account is the one that describes the server. It needs no token and reports which features are switched on:

```bash
curl https://cloud.logdate.app/api/v1/server/info
```

```json
{
  "success": true,
  "data": {
    "serverOrigin": "https://cloud.logdate.app",
    "apiBaseUrl": "https://cloud.logdate.app/api/v1",
    "apiVersion": "v1",
    "deploymentKind": "FIRST_PARTY",
    "displayName": "LogDate Cloud",
    "handleDomain": "logdate.app",
    "passkey": { "rpId": "logdate.app", "rpName": "LogDate" },
    "capabilities": [
      "AUTH_PASSKEY", "SYNC_CONTENT", "SYNC_MEDIA",
      "ATPROTO_IDENTITY", "ATPROTO_OAUTH",
      "BILLING_SUBSCRIPTIONS", "MANAGED_QUOTA", "CLOUD_TRANSCRIPTION"
    ],
    "protocolFeatures": ["canonicalOwnerBindingV1"],
    "privacyPolicyUrl": "https://logdate.app/privacy",
    "termsOfServiceUrl": "https://logdate.app/terms"
  }
}
```

Two things to note in this response. `apiBaseUrl` is the prefix for everything under **Sync**, **Account** and **Cloud services**. `capabilities` is the list of things this deployment can do, so check it before showing a feature. Google sign-in has no capability flag: a server without Google credentials refuses it with `503 GOOGLE_AUTH_NOT_CONFIGURED`, so handle that response rather than assuming.

If you are running the server yourself, replace `https://cloud.logdate.app` with your own origin everywhere in this guide. Relative paths in the examples work against whichever server is serving this page.

## Your first sync

Here is the complete loop a client performs, in three requests: get a token, save an entry, ask what changed.

**1. Get a token.** The quickest way to a token from a terminal is a Google ID token, because passkeys need a browser or phone to sign the challenge. The ID token must be issued to one of the client IDs the server trusts (its `GOOGLE_OIDC_CLIENT_IDS` setting), so obtain it through the app or a client you registered with that ID, then:

```bash
curl -X POST https://cloud.logdate.app/api/v1/auth/signin/google \
  -H 'Content-Type: application/json' \
  -d '{ "idToken": "eyJhbGciOiJSUzI1NiIs…" }'
```

```json
{
  "success": true,
  "data": {
    "account": {
      "id": "3f8a1c2e-5b7d-4e9f-a1b2-c3d4e5f60718",
      "username": "willie",
      "displayName": "Willie",
      "email": "willie@example.com",
      "emailVerified": true,
      "linkedProviders": ["google"],
      "passkeyCredentialIds": [],
      "createdAt": "2026-09-12T14:03:11.412Z",
      "updatedAt": "2026-09-12T14:03:11.412Z"
    },
    "tokens": {
      "accessToken": "eyJhbGciOiJIUzI1NiIs…",
      "refreshToken": "eyJhbGciOiJIUzI1NiIs…"
    }
  }
}
```

If that Google account has never signed up, the response is `404 ACCOUNT_NOT_FOUND_SIGNUP_REQUIRED`; call **Sign up with Google** instead, which takes the same body plus a `username`. On your own server, add your Google client ID to the `GOOGLE_OIDC_CLIENT_IDS` environment variable first.

From here on, every request carries the access token:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIs…
```

**2. Save an entry.** An entry is a *content*. You choose its ID (the apps use ULIDs; any stable string works) and `PUT` it. The same request sent twice is safe: the first creates, the second updates.

```bash
curl -X PUT https://cloud.logdate.app/api/v1/contents/01J7Q2X4Y5Z6A7B8C9D0E1F2G3 \
  -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiIs…' \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "01J7Q2X4Y5Z6A7B8C9D0E1F2G3",
    "type": "TEXT",
    "content": "Walked to the lake before work. Fog on the water.",
    "mediaUri": null,
    "createdAt": 1789221791412,
    "lastUpdated": 1789221791412,
    "deviceId": "pixel-9"
  }'
```

```
HTTP/1.1 201 Created
Location: /api/v1/contents/01J7Q2X4Y5Z6A7B8C9D0E1F2G3
```

```json
{ "id": "01J7Q2X4Y5Z6A7B8C9D0E1F2G3", "serverVersion": 1789221791530, "uploadedAt": 1789221791530 }
```

The LogDate apps encrypt `content`, `caption` and `location` on the device before uploading. The server treats them as opaque strings and never reads them. Your own client may send plain text, but then the server can read it too.

**3. Ask what changed.** Sync is a change feed. You tell the server the last version you have seen (`since`) and it returns everything newer. Start from `0`:

```bash
curl 'https://cloud.logdate.app/api/v1/contents?since=0&limit={{sync.limit.default}}' \
  -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiIs…'
```

```json
{
  "changes": [
    {
      "id": "01J7Q2X4Y5Z6A7B8C9D0E1F2G3",
      "type": "TEXT",
      "content": "Walked to the lake before work. Fog on the water.",
      "createdAt": 1789221791412,
      "lastUpdated": 1789221791412,
      "serverVersion": 1789221791530,
      "isDeleted": false
    }
  ],
  "deletions": [],
  "lastTimestamp": 1789221791530,
  "hasMore": false
}
```

Store `lastTimestamp`. Next time, send it as `since` and you will receive only what is new. While `hasMore` is `true`, call again with the returned `lastTimestamp` before treating the client as up to date. That is the entire sync protocol; the rest of this guide fills in the details.

## Concepts

**Access token and refresh token.** Signing in returns two tokens. The *access token* is what you send on every request. It is a JWT, a signed string the server can verify without looking anything up, and it expires after a short time on purpose: if it leaks, the damage is bounded. The *refresh token* lives longer and has exactly one job: exchanging it at **Refresh the access token** for a new access token. When you see `401 INVALID_TOKEN`, refresh and retry once. When the refresh itself fails, sign in again. **Log out** takes the refresh token, not the access token, because revoking the refresh token is what stops new access tokens from being minted; the old access token simply expires.

**Passkeys, and why there is a begin and a complete.** A passkey is a key pair stored on the person's device (or in their password manager). The server never sees the private key. To prove ownership, the server sends a random *challenge* (**begin**), the device signs it with the private key, and the client sends the signature back (**complete**). That is why every passkey flow is two calls, and why the second call must carry the `sessionToken` or `challenge` from the first. In a browser the signing step is `navigator.credentials.get()`; on Android and iOS it is the platform credential manager.

**The `since` cursor.** Every write the server accepts gets a *version*: a number that only ever increases for that account, whatever the device's clock says. The change feeds take `since`, return every record whose version is greater than it, and return `lastTimestamp`, the highest version in that page. Despite the name it is a version, not a time. Send it back as the next `since`. Because versions are assigned by the server, two devices with wrong clocks still see changes in the order the server accepted them.

One caveat when `hasMore` is `true`: `changes` and `deletions` are each cut to `limit` separately but share the one `lastTimestamp`, so a record with a lower version than `lastTimestamp` can still be waiting in whichever list was cut short. Use a `limit` large enough that pages are rarely full (the default is {{sync.limit.default}}), or when `hasMore` is `true` page again from the cursor you sent rather than from `lastTimestamp`.

**Tombstones.** Deleting something does not remove it from the feed; it becomes a *tombstone*, a small record saying "this ID was deleted at this time" with a fresh version. Tombstones arrive in the `deletions` list of a change-feed response so every device learns about the deletion, however long it was offline. The `isDeleted` field on items in `changes` is always `false`; deletions only travel in `deletions`.

**Conflicts and `versionConstraint`.** A `PUT` always succeeds: the last write becomes the current version. A `PATCH` can ask for protection by sending `"versionConstraint": { "type": "known", "serverVersion": 1789221791530 }`, meaning "I last saw this version". If the server has moved on, it answers `409 CONFLICT` instead of overwriting, and your client can fetch the newer copy and merge. Send `{ "type": "none" }` (or leave it out) to skip the check.

**Multipart uploads.** Photos, recordings and backups are sent as `multipart/form-data`, the same encoding a browser uses for a file-upload form: several named *parts* in one request, some text and one binary. `curl -F name=value -F data=@file` builds one for you. Each upload endpoint lists its parts; for media, `sizeBytes` must equal the byte length of `data` exactly, so that a truncated upload is rejected rather than stored half-finished.

**OAuth and DPoP (skip unless you are building an AT Protocol client).** OAuth 2.0 is how a third-party app gets permission to act on someone's data without ever seeing their password: the app sends the person to the server to approve, and receives a code it exchanges for tokens. DPoP ("Demonstrating Proof of Possession") adds one thing: the app signs each token request with its own key, so the token only works together with that key. The LogDate apps do not use either; they use **Authentication**.

## Base URLs and self-hosting

| Deployment | Base URL | Notes |
|---|---|---|
| LogDate Cloud | `https://cloud.logdate.app` | The hosted service behind the official apps. |
| Your own server | whatever you deploy at | Same API, same paths. `GET /api/v1/server/info` reports `"deploymentKind": "SELF_HOSTED"`. |

Paths in this reference are relative, so the **Try it** panel talks to whichever server is serving the page. The API is versioned by path prefix: everything account- and sync-related lives under `/api/v1/`. AT Protocol endpoints keep their protocol-defined paths (`/oauth/`, `/xrpc/`, `/.well-known/`).

## Authentication in depth

There are four ways to obtain an access token:

1. **Passkey sign-up or sign-in** (`/auth/signup/passkey/*`, `/auth/signin/passkey/*`): two calls each, see Concepts.
2. **Google sign-up or sign-in** (`/auth/signup/google`, `/auth/signin/google`): one call with a Google ID token. If exactly one existing account has the same *verified* email, both calls link the Google identity to it (sign-up additionally requires that account's ID to equal `requestedOwnerId`, otherwise `409 ACCOUNT_LINK_CONFLICT`). If more than one matches, sign-up answers `409 ACCOUNT_LINK_CONFLICT` and sign-in answers `404 ACCOUNT_NOT_FOUND_SIGNUP_REQUIRED`; sign in with a passkey instead.
3. **Restore credential** (`/auth/restore/*`): a special passkey the app registers so a person who has lost every device can still regain access. Register it while signed in; use it later without a username.
4. **Refresh** (`/auth/token/refresh`): exchanges a refresh token for a new access token. This is the only auth call a client makes routinely.

Send the access token as `Authorization: Bearer <accessToken>` on every endpoint marked with a lock icon (the XRPC repository methods also accept the OAuth form described next). Logging out (`/auth/logout`) revokes the refresh token; a revoked token answers `401 REFRESH_TOKEN_REVOKED` forever after.

Third-party AT Protocol clients use the **OAuth** section instead, and send DPoP-bound tokens as `Authorization: DPoP <token>` together with a `DPoP` proof header.

## Errors

Every error response has an HTTP status that indicates the severity and a machine-readable code that identifies the cause. Because LogDate Cloud implements several protocols, there are five envelope shapes. Which one you get depends on the section the endpoint is in, and each endpoint's response list shows the exact shape.

| Section | Envelope | Example |
|---|---|---|
| Authentication, Account, Identity | `{ "error": { "code", "message" } }` | `{"error":{"code":"INVALID_TOKEN","message":"Invalid or expired access token"}}` |
| Contents, Journals, Associations, Drafts, Media, Backups, Sync status | `{ "code", "message", "details", "timestamp" }` | `{"code":"CONFLICT","message":"Server has a newer version","details":{},"timestamp":"2026-09-12T14:03:11.412Z"}` |
| Quota, Transcription | `{ "error" }` | `{"error":"missing or invalid Authorization header"}` |
| XRPC | `{ "error", "message" }` with an AT Protocol error name | `{"error":"RecordNotFound","message":"Could not locate record"}` |
| OAuth | `{ "error", "error_description" }` per RFC 6749 | `{"error":"invalid_grant","error_description":"Authorization code has expired"}` |

Codes that appear across the API, and what to do about them:

| Code | Status | What happened | What to do |
|---|---|---|---|
| `INVALID_REQUEST`, `VALIDATION_ERROR` | 400 | The body was not valid JSON, or a field was missing or malformed. | Fix the request; the `message` names the field. Do not retry unchanged. |
| `INVALID_PARAMETER` | 400 | A query or path parameter was missing or the wrong type. | Same as above. |
| `INVALID_TOKEN`, `UNAUTHORIZED` | 401 | The access token is missing, malformed or expired. | Refresh the access token and retry once; if that fails, sign in again. |
| `QUOTA_EXCEEDED` | 402 | The account has no room for this upload. | Show the person their usage (**Get storage quota**) and stop retrying. |
| `NOT_FOUND` | 404 | No such record for this account. | Treat as deleted. |
| `CONFLICT` | 409 | Your `versionConstraint` is behind the server. | Fetch the current record, merge, patch again. |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests in the window. | Wait; honor `Retry-After` when it is present. |
| `SERVER_ERROR` | 500 | Something failed on the server. | Retry with backoff; if it persists, report it. |
| `SERVER_MISCONFIGURED`, `*_NOT_CONFIGURED`, `*_UNAVAILABLE` | 500, 501, 503 | This deployment has the feature switched off. | Check `capabilities` on **Describe this server** and hide the feature. |

## Rate limits

Limits exist to stop credential stuffing and misbehaving upload loops, not to meter normal use. A client that syncs every few minutes will never encounter one.

| What | Limit | Counted per | On exceed |
|---|---|---|---|
| Sign-up (passkey and Google) | {{auth.signup}} | IP address | `429 RATE_LIMIT_EXCEEDED`, no `Retry-After` header |
| Sign-in (passkey and Google) | {{auth.signin}} | IP address | `429 RATE_LIMIT_EXCEEDED`, no `Retry-After` header |
| Media uploads | {{media.upload}} | account | `429` with `Retry-After` and `details.retryAfterSeconds` |
| Backup uploads | {{backup.upload}} | account | `429` with `Retry-After` and `details.retryAfterSeconds` |
| Transcription sessions | {{transcription.sessions}} | account | `429` with `Retry-After` |

Token refresh, logout, reads and deletes are not rate limited.

## Quotas

Uploads count against the account's plan. Media and backup uploads that would exceed it answer `402 QUOTA_EXCEEDED` with `details.reason` set to `STORAGE_BYTES` or `BACKUP_COUNT`, plus `details.limit` and `details.current` so you can show a meaningful message. **Get storage quota** returns the same numbers at any time. A self-hosted server with no billing configured has no limits.

## Sync model in depth

- **Identity.** You choose IDs for contents, journals and drafts; the server never renames them. Associations are identified by `(journalId, contentId)`. Media IDs are assigned by the server, deterministically from `(account, contentId, fileName)`, so the same upload retried lands on the same ID. Backup IDs are random.
- **Writes.** `PUT /{collection}/{id}` creates or replaces and is safe to retry. For contents and journals it answers `201 Created` with a `Location` header the first time and `200 OK` after that; a single association link answers `204`, and a draft always `200`. `PATCH` changes only the fields you send and honors `versionConstraint`. `DELETE` answers `204` even when the record is already gone.
- **Versions.** Every accepted write gets a new server version (`serverVersion` in responses), strictly increasing per account across all collections. Whatever `syncVersion` or `serverVersion` a client sends is ignored; only `versionConstraint` is read.
- **Reading.** `GET /{collection}?since=<version>&limit=<n>` returns `changes`, `deletions`, `lastTimestamp` and `hasMore`. `since` is exclusive and defaults to `0`. `limit` defaults to {{sync.limit.default}} and is clamped to 1…{{sync.limit.max}}; it applies to `changes` and `deletions` separately, so a page can hold up to twice `limit` records. A non-numeric `since` is a `400 INVALID_PARAMETER`; a non-numeric `limit` silently falls back to the default.
- **Drafts** are the exception: their feed lives at `/drafts/changes`, defaults `limit` to {{drafts.limit.default}} without clamping, and treats a bad `since` as `0`.
- **Timestamps you send** (`createdAt`, `lastUpdated`) are stored as given for display; the server stamps `uploadedAt` and `updatedAt` itself.

## Conventions

- Sync objects use **milliseconds since the Unix epoch** (UTC) for every time field. Account objects and error envelopes use **ISO-8601 strings**. Each field's description says which.
- Successful creates answer `201` with a `Location` header pointing at the new resource. Successful deletes answer `204` with no body.
- JSON bodies are `Content-Type: application/json`; unknown fields are ignored, so adding fields to a client never breaks an older server.
- Booleans and enums are case-sensitive and spelled exactly as shown.

## Status

`GET /health` (not part of the versioned API) answers `{ "status": "healthy", "timestamp": "…", "version": "1.0.0", "release": "logdate-server@<commit>" }` and needs no token. The `release` value identifies the deployed build when you report a problem.
