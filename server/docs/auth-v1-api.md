# Auth V1 API

This document is the human-readable reference for the rules behind the auth endpoints. All of them
live under `/api/v1/auth`.

Canonical sources:

- `GET /docs` (interactive reference)
- `GET /openapi.json` and `GET /openapi.yaml` (machine-readable)
- `server/docs/openapi.md` (how the reference is maintained)

## Endpoint reference

Every endpoint, its parameters, examples and error codes are documented in the interactive
reference at `/docs` (Authentication, Account and Identity sections). This page keeps only the
rules that are not visible from a single endpoint. How tokens are issued, stored, refreshed and
revoked is in [`docs/jwt-authentication.md`](../../docs/jwt-authentication.md).

## Ways to sign in

An account is reached in one of three ways, and a client may offer any of them:

- **Passkey.** Sign-up and sign-in are each two calls: `begin` returns a challenge and the client
  signs it with the platform's passkey support, then `complete` sends the result back.
- **Google.** One call with a Google ID token creates the account or signs in.
- **Restore credential.** A second, special passkey that the app registers while signed in
  (`/auth/restore/register/begin` and `/complete`). It exists so that a person who has lost every
  device can still regain access: `/auth/restore/begin` and `/complete` sign in with it without a
  username. It is not listed among the passkeys the person manages, and it is registered under its
  own challenges and storage, separate from ordinary passkeys. Restore sign-in asks the device not
  to require user verification, because the credential is meant to be used on a fresh installation.

Passkey and Google are first-class identity providers. An account can hold several passkeys
(`/auth/me/passkeys`) and a linked Google identity, and `GET /auth/me/identities` lists every sign-in
method the account has.

## Flow Rules

- A passkey sign-up names the owner of the new account. The client sends `requestedOwnerId`, the
  identifier of the journal it already holds locally, and the server uses it as the account ID. If an
  account with that ID exists, sign-up answers `409 CANONICAL_OWNER_ID_TAKEN`, which tells the client to
  sign in instead of choosing another username. See
  [device identity](../../docs/reference/device-identity.md).
- Username rules: 3 to 50 characters, letters, numbers and underscores only, and not on the reserved
  list. A display name is 1 to 100 characters. Both are checked at `begin`, so a username that is
  taken or invalid is reported before the person is asked for a passkey.
- Implicit account linking occurs only when Google returns `email_verified=true` and exactly one
  account matches that verified email.
- When linking is ambiguous, the two Google calls answer differently. Sign-up answers
  `409 ACCOUNT_LINK_CONFLICT`. Sign-in answers `404 ACCOUNT_NOT_FOUND_SIGNUP_REQUIRED`, and the person
  signs in with a passkey instead. Sign-in with no matching account also answers
  `ACCOUNT_NOT_FOUND_SIGNUP_REQUIRED`.
- Sign-up through Google additionally requires, when it links to an existing account, that the
  account's ID equals `requestedOwnerId`. Otherwise it answers `409 ACCOUNT_LINK_CONFLICT`.

## Passkey ceremonies

A challenge is 32 random bytes. It is valid for five minutes, can be used once, and is bound to its
purpose and to the user it was issued for, so a registration challenge cannot be spent as a sign-in
challenge. Using a challenge consumes it even when verification then fails. Production verifies
registrations and assertions cryptographically with WebAuthn4J; the `WEBAUTHN_STRICT_VERIFICATION`
setting defaults to on in production and off in development. Normal sign-in asks the device for user
verification (`userVerification: required`).

The signup `sessionToken` that `begin` returns is an opaque ID of a server-side record that lasts 15
minutes and can be used once. It ties `complete` to the `begin` that produced it.

## Rate Limits

The sign-up and sign-in limits are rendered from the policies the server enforces in the
**Rate limits** table of the `/docs` overview; this page does not repeat the numbers. Limit
violations return `429` with code `RATE_LIMIT_EXCEEDED`.

## Google Verification

- Server validates Google ID tokens via Google token introspection endpoint.
- Allowed client IDs are controlled by `GOOGLE_OIDC_CLIENT_IDS`.
- Missing or invalid tokens return `GOOGLE_TOKEN_INVALID`.
- A token whose email is not verified returns `400 GOOGLE_EMAIL_UNVERIFIED`.

## Tokens and sign-out

- Sign-up and sign-in return an access token and a refresh token. Sync calls send the access token as
  a bearer token. The server derives the account from the token's subject, so sync requests carry no
  separate account identifier.
- On `401` from an authenticated call, a client calls `POST /auth/token/refresh` with its refresh
  token and retries once. `POST /auth/logout` revokes a refresh token; a revoked token answers
  `401 REFRESH_TOKEN_REVOKED` from then on. Access tokens already issued stay valid until they expire.

## Audit Logging

- Successful passkey signup/signin and Google signup/signin emit structured audit log entries.
- Implicit Google link operations emit `audit.auth.link.google.implicit` entries.
- Logged fields include account ID and hashed request metadata (`ipHash`, `userAgentHash`).
- Full category/key registry: `server/docs/audit-schema.md`.

## Auth Metrics

- `GET /api/v1/auth/metrics` returns JSON counters for auth operations, errors by code, and rate-limit hits.
- `GET /api/v1/auth/metrics/prometheus` returns Prometheus text format.
- Both endpoints require bearer authentication.

## Client Integration Notes

- Client auth callers use the `/auth/*` paths.
- How the app walks a person through these calls is in
  [`docs/reference/cloud-account-setup.md`](../../docs/reference/cloud-account-setup.md).
- Account security screens are built from the account the server returns: `linkedProviders` and
  `passkeyCredentialIds` say which sign-in methods exist. `GET /me/identities` lists identities and
  `DELETE /me/passkeys/{credentialId}` removes a passkey.
- Sync endpoints are resource-first under `/api/v1/*` (`/contents`, `/journals`, `/associations`, `/media`, `/backups`) with ops under `/api/v1/ops/sync/*`.
- The sync contract is documented in `server/docs/sync-v1-api.md`.
- A linked device such as a watch does not sign in. It holds a device token that reaches only the sync
  routes; see [`device-sessions-api.md`](device-sessions-api.md).
