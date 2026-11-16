# AT Protocol OAuth sign-in

Every LogDate account has an AT Protocol identity: a handle such as `alice.logdate.app` and a
permanent identifier called a DID. Other apps in the AT Protocol network can ask a person to sign in
with that identity. This page describes how the LogDate server handles such a sign-in: the
endpoints, the checks at each step, how long tokens last, what a signed-in app may reach, and the
security rules of the page where the person approves the request.

Most LogDate accounts have no password. They sign in with a passkey, a credential that lives on the
person's device and proves the person is present without sending a secret to the server. The whole
flow is built so that a passkey is enough: the person approves another app on a page served by
LogDate, and no password is created or asked for.

The reasons LogDate holds the keys behind each identity are in
[AT Protocol identity custody](../explanation/atproto-identity-custody.md). What LogDate publishes
to the network, and what it never publishes, is in
[AT Protocol interoperability](../explanation/atproto-interoperability.md). Every environment
variable named here is documented in
[`server/docs/environment-variables.md`](../../server/docs/environment-variables.md), and the
sign-in endpoints the LogDate apps use for themselves are in
[`server/docs/auth-v1-api.md`](../../server/docs/auth-v1-api.md).

## The parts of the flow

The server is an OAuth 2.0 authorization server with the profile the AT Protocol requires. Four
terms recur throughout this page.

- A **client** is the other app. It has no account with LogDate. Its `client_id` is an HTTPS URL
  where it publishes a JSON document describing itself, called its client metadata document.
- A **pushed authorization request** (PAR) is the client sending its request to the server
  directly, before the person's browser is involved. The server answers with a short-lived
  `request_uri`, and the browser only ever carries that reference. The request itself cannot be
  altered in the browser's address bar.
- **PKCE** is a one-time secret the client keeps. It sends a hash of the secret (the
  `code_challenge`) with the pushed request and the secret itself (the `code_verifier`) when it
  collects its tokens, which proves that the same client did both.
- **DPoP** (demonstrating proof of possession) binds every token to a key pair only the client
  holds. With each request the client sends a small signed token, the DPoP proof, made with that
  key. A stolen access token is useless without the key.

Four different credentials are involved, and each answers a different question. The passkey answers
whether this person is present. The OAuth grant answers whether LogDate allowed this client to act.
The DPoP proof answers whether the caller holds the key the token was issued to. The DID answers
which identity the token acts for.

## Endpoints

All routes are registered in
[`OAuthRoutes.kt`](../../server/src/main/kotlin/app/logdate/server/routes/OAuthRoutes.kt).

| Route                                         | Purpose                                                              |
| --------------------------------------------- | -------------------------------------------------------------------- |
| `GET /.well-known/oauth-authorization-server` | Authorization server metadata                                        |
| `GET /.well-known/oauth-protected-resource`   | Protected resource metadata                                          |
| `GET /oauth/jwks`                             | Public keys that verify access tokens                                |
| `POST /oauth/par`                             | Pushed authorization request                                         |
| `GET /oauth/authorize`                        | Authorization page for a browser, or a JSON prompt for a LogDate app |
| `POST /oauth/authorize`                       | Cancel from the page, or approve or deny from a LogDate app          |
| `POST /oauth/authorize/passkey/options`       | Passkey challenge for one pushed request                             |
| `POST /oauth/authorize/passkey`               | Verify the passkey and approve the request                           |
| `POST /oauth/token`                           | Exchange an authorization code or a refresh token                    |
| `POST /oauth/revoke`                          | Revoke a refresh token                                               |

The issuer is `ATPROTO_OAUTH_ISSUER`, which defaults to the server's public AT Protocol address, and
must use `https`. The protected resource is `ATPROTO_OAUTH_RESOURCE`, which defaults to the issuer.

The authorization server metadata, built in
[`OAuthConfig.kt`](../../server/src/main/kotlin/app/logdate/server/oauth/OAuthConfig.kt), advertises
the following.

| Field                                              | Value                                 |
| -------------------------------------------------- | ------------------------------------- |
| `response_types_supported`                         | `code`                                |
| `grant_types_supported`                            | `authorization_code`, `refresh_token` |
| `code_challenge_methods_supported`                 | `S256`                                |
| `token_endpoint_auth_methods_supported`            | `none`, `private_key_jwt`             |
| `token_endpoint_auth_signing_alg_values_supported` | `ES256`, `ES256K`                     |
| `dpop_signing_alg_values_supported`                | `ES256`, `ES256K`                     |
| `scopes_supported`                                 | `atproto`, `transition:generic`       |
| `authorization_response_iss_parameter_supported`   | `true`                                |
| `require_pushed_authorization_requests`            | `true`                                |
| `client_id_metadata_document_supported`            | `true`                                |

## Client metadata

The server learns about a client by fetching the document at its `client_id`. There is no
registration step and no stored list of clients, so any app that publishes a valid document can
start a sign-in. The rules are in
[`OAuthClientMetadataResolver.kt`](../../server/src/main/kotlin/app/logdate/server/oauth/OAuthClientMetadataResolver.kt).

- The `client_id` must be an `https` URL. The one exception is `http://localhost`, which lets a
  developer test a client running on their own machine.
- The document must be returned with status `200`, and its `client_id` field must equal the URL it
  was fetched from. This stops one site from presenting another site's document as its own.
- It must list at least one `redirect_uri`, include `code` in `response_types`, include both
  `authorization_code` and `refresh_token` in `grant_types`, and leave `dpop_bound_access_tokens`
  set to `true`.
- A public client declares `token_endpoint_auth_method` as `none` and must not publish keys. A
  confidential client declares `private_key_jwt` and publishes exactly one of `jwks` or `jwks_uri`.
  A `jwks_uri` must use `https`. Only ES256 and ES256K keys are accepted.
- A document, or a key set at `jwks_uri`, that is malformed or is returned with any status other
  than `200` is answered as `invalid_client`. A malformed one is also logged as a warning. It is the
  client's mistake, so a client cannot make the server report server errors by publishing a broken
  document.
- A document or key set that cannot be fetched at all, because the host cannot be reached or the
  connection fails, is logged as an error and answered `500` with `server_error`, like any other
  unexpected failure.

A fetched document is cached in memory for 10 minutes by each server instance.

A confidential client authenticates on every request with a signed token called a client assertion,
sent as `client_assertion` with `client_assertion_type` set to
`urn:ietf:params:oauth:client-assertion-type:jwt-bearer`. The server checks the assertion's claims,
which are the named values inside the token.

- `iss` (who issued it) and `sub` (who it is about) must both equal the `client_id`.
- `aud` (who it is meant for) must equal the issuer.
- `iat` (when it was issued) must be no more than 60 seconds in the future.
- `exp` (when it expires) must be later than both `iat` and the current time.
- `jti` (a unique identifier for this one token) must be present, and each `jti` is accepted once.

The signing key is chosen by the assertion's `kid`, the key identifier in its header, or is the only
key when the client publishes one. The key's curve must match the assertion's `alg`.

The key a confidential client authenticated with at the pushed request is recorded with the grant.
The token exchange, every refresh and the revocation must authenticate with the same key, so a
grant cannot be continued by a different key from the same metadata document.

## Sign-in flow

### 1. The client pushes its request

`POST /oauth/par` takes a form body and a `DPoP` header. The form fields are `client_id`,
`redirect_uri`, `scope`, `response_type`, `code_challenge` and `code_challenge_method`, with
`state`, `login_hint`, `client_assertion_type` and `client_assertion` optional.

The server, in
[`OAuthAuthorizationService.kt`](../../server/src/main/kotlin/app/logdate/server/oauth/OAuthAuthorizationService.kt):

1. requires `response_type=code` and `code_challenge_method=S256`;
2. requires `scope` to include `atproto`;
3. fetches and validates the client metadata, and authenticates a confidential client;
4. requires `redirect_uri` to be listed in the metadata exactly;
5. refuses scopes beyond those the metadata declares, when the metadata declares any;
6. verifies the DPoP proof and remembers its key;
7. refuses a `code_challenge` that already started a request;
8. stores the request, keeping only the scopes the server enforces.

The answer is `201 Created` with `request_uri` and `expires_in`, plus a `DPoP-Nonce` header. A
pushed request lasts 5 minutes. A nonce is a value the server hands out and expects to see again in
the next DPoP proof, which proves the proof was made recently. The pushed request is the one step
that does not demand a nonce, because it is where the client first receives one.

### 2. The person approves in a browser

The client sends the person's browser to
`GET /oauth/authorize?client_id=…&request_uri=…`. What happens next depends on who is asking.

- A browser arriving from another app sends no `Authorization` header. When the authorization page
  is turned on, it receives an HTML page, described in
  [The authorization page](#the-authorization-page).
- A LogDate app sends its own bearer token. It receives a JSON prompt naming the client, the
  requested scope, and the account's DID and handle, and then posts `decision=approve` or
  `decision=deny` to `POST /oauth/authorize`.

Either way, a request can be decided once. Deciding it removes the stored request, so of two
decisions racing for the same request only the one that removed it continues.

On approval the server creates an authorization code that lasts 1 minute and redirects the browser
to the client's `redirect_uri` with `code`, `state` and `iss`. On denial it redirects with
`error=access_denied`, `state` and `iss`. Every redirect carries `iss`, the issuer's URL. AT
Protocol clients compare it with the server they started at and stop when it is missing or
different, which defeats an attack where a second server answers in place of the first.

### 3. The client exchanges the code

`POST /oauth/token` with `grant_type=authorization_code` takes `code`, `redirect_uri`, `client_id`
and `code_verifier`, a `DPoP` header, and a client assertion for a confidential client.

- The code is removed as soon as it is presented, in one locked transaction, so two exchanges of one
  code cannot both succeed. A failed exchange also spends the code.
- The `client_id` and `redirect_uri` must match the pushed request.
- The SHA-256 of `code_verifier` must equal the stored `code_challenge`.
- The DPoP proof must carry the server's current nonce and must be signed with the same key as the
  proof on the pushed request.

The answer contains `access_token`, `token_type` set to `DPoP`, `expires_in`, `refresh_token`,
`sub` and `scope`. The `sub` is the account's DID. The response is marked `Cache-Control: no-store`
and carries a `DPoP-Nonce` header.

### 4. The client refreshes

`POST /oauth/token` with `grant_type=refresh_token` takes `refresh_token` and `client_id`. The DPoP
proof must use the key the grant was issued to. Each refresh returns a new refresh token and
retires the old one. Any other `grant_type` is answered with `unsupported_grant_type`.

### 5. The client revokes

`POST /oauth/revoke` takes `token` and `client_id` and the same DPoP and client checks as a refresh.
Revoking a token the server does not know is a success, so a client can safely repeat the call.

## Token lifetimes

| Item                               | Lifetime                      | Why                                                                           |
| ---------------------------------- | ----------------------------- | ----------------------------------------------------------------------------- |
| Pushed request (`request_uri`)     | 5 minutes                     | Long enough to pick a passkey, short enough that an abandoned link is useless |
| Authorization code                 | 1 minute                      | It only has to survive one redirect                                           |
| Access token                       | 15 minutes                    | Access tokens cannot be revoked, so they stay short                           |
| Refresh token                      | 14 days from its last refresh | The session an app keeps without asking again                                 |
| Refresh token for a trusted client | 30 days from its last refresh | See `LOGDATE_OAUTH_TRUSTED_CLIENT_IDS`                                        |
| DPoP nonce                         | 5 minutes                     | Replaced on a timer; only the current one is accepted                         |

The refresh-token lifetime is counted from the most recent refresh, not from the moment the person
approved the app. Each refresh issues a replacement token with the full lifetime again, so an app
that refreshes at least once in every 14 days (30 for a trusted client) keeps its session until the
grant is revoked. An app that stays silent for longer than that has to send the person through the
sign-in again.

## Access tokens

An access token is a JSON Web Token signed with ES256 by a key the server publishes at
`/oauth/jwks`. Its header has `typ` set to `at+jwt` and a `kid` naming the signing key. Its claims
are `iss`, `sub`, `aud`, `exp`, `iat`, `jti`, `scope`, `client_id` and `cnf.jkt`.

- `sub` is the account's DID, never the internal account ID. Clients resolve the DID to a handle
  through the network's own identity lookup, and the server maps it back to an account.
- `aud` is the protected resource URL.
- `cnf.jkt` is the thumbprint of the client's DPoP key, which is what binds the token to that key.

The signing key is stored encrypted, shared by every server instance, and rotated on a schedule
without signing anyone out. The schedule and the secrets involved are documented under
`OAUTH_SIGNING_KEY_MAX_AGE_DAYS` and `ATPROTO_SIGNING_KEY_KEK` in
[`server/docs/environment-variables.md`](../../server/docs/environment-variables.md).

An app presents the token on AT Protocol methods under `/xrpc` as
`Authorization: DPoP <token>` together with a `DPoP` proof header. The server checks the token's
signature, issuer, audience and expiry, then the proof, and then that the proof's key matches
`cnf.jkt` and that the account is still active. OAuth tokens are not accepted by the LogDate
`/api/v1` routes, which take the LogDate apps' own bearer tokens.

`com.atproto.server.getSession` accepts only AT Protocol session tokens sent as `Bearer`, so a
DPoP-bound OAuth token cannot use it.

Cross-origin access is configured only for the origins in `ALLOWED_ORIGINS`. It does not allow the
`DPoP` request header or expose `DPoP-Nonce`, so a browser app on another origin cannot send a proof
or read a nonce.

## DPoP proof rules

[`OAuthDpopVerifier.kt`](../../server/src/main/kotlin/app/logdate/server/oauth/OAuthDpopVerifier.kt)
accepts a proof only when all of the following hold. The claim names are the ones defined by
RFC 9449, the DPoP specification, and each is explained where it appears.

- It is a compact JWT whose header has `typ` set to `dpop+jwt`, `alg` set to `ES256` or `ES256K`,
  and a `jwk` holding an EC public key on the P-256 or secp256k1 curve.
- `htm`, the HTTP method the proof was made for, equals the request method.
- `htu`, the URL the proof was made for, equals the URL the server was called at, compared by
  scheme, host, port and path. The server builds that URL from its configured issuer or resource
  origin and the request path. Nothing a client sends, such as an `X-Forwarded-Host` header, can
  change it.
- `jti`, a unique identifier the client chooses for this one proof, is present.
- `iat`, the time the proof was issued, is within 5 minutes of the server's clock, in either
  direction.
- `nonce` equals the server's current nonce, on every step after the pushed request.
- `ath`, the hash of the access token the proof accompanies, equals the SHA-256 of that token, when
  the proof accompanies one.
- The signature verifies against the `jwk` under the algorithm the header names. The server does not
  compare the key's curve with `alg` as a separate step: a key on the other curve fails here, and
  the proof is refused as one whose signature could not be verified.
- The proof has not been used before.

When the nonce is missing or stale, the OAuth endpoints answer `400` with the error `use_dpop_nonce`
and a `DPoP-Nonce` header holding the value to retry with. The `/xrpc` methods answer `401` with the
same header but without a `WWW-Authenticate: DPoP error="use_dpop_nonce"` header. A client that
looks for that header before retrying, as RFC 9449 section 9 describes for resource servers, does
not retry.

Each server instance makes its own nonce when it starts and replaces it on its own timer, and it
accepts only the one it holds. A deployment that runs several instances, or starts and stops them as
load changes, therefore refuses a proof whose nonce came from a different instance or from before a
restart. A client that retries with the nonce in the refusal succeeds, and one that does not
fails intermittently.

## Scopes

A scope is a word in the request that names what the client wants to do. The server grants two and
drops every other requested scope at the pushed request, so the `scope` in a token lists exactly
what the server enforces and nothing it merely tolerates. The rules are in
[`OAuthScopes.kt`](../../server/src/main/kotlin/app/logdate/server/oauth/OAuthScopes.kt).

| Scope                        | Allows                                                                        |
| ---------------------------- | ----------------------------------------------------------------------------- |
| `atproto`                    | Confirming which account signed in. Nothing in the repository, read or write. |
| `atproto transition:generic` | Also reading and writing collections that are open to apps.                   |

A collection is a named group of records in an account's repository, such as
`app.logdate.journal.entry`. A collection is open to apps only when it is listed on purpose in
`OAuthScopes.collectionsOpenToApps`. Names are compared in canonical form, so a differently cased
name cannot slip past the list.

**That list is empty.** No collection is open to apps. Two families of collection can never be
added: names starting with `studio.hypertext.logdate.` and names starting with `app.logdate.`. They
hold a person's journal, and the server refuses to start when either appears on the list. This rule
exists because the repository an OAuth token points at can hold private journal data, and a sign-in
with another app must never become a way to read or change it.

So an app signed in through OAuth can read and change nothing. The answer it gets from the
AT Protocol methods in
[`XrpcRoutes.kt`](../../server/src/main/kotlin/app/logdate/server/routes/XrpcRoutes.kt) depends on
the method and, for some methods, on which scopes were granted. The methods fall into two groups.

The record methods check the scope first and the collection second. A grant of `atproto` alone is
refused before the collection is looked at. A grant that includes `transition:generic` passes the
scope check and is then stopped because the collection is closed.

| Method                                                       | Granted `atproto` alone | Granted `atproto transition:generic` |
| ------------------------------------------------------------ | ----------------------- | ------------------------------------ |
| `com.atproto.repo.listRecords`                               | `403 InsufficientScope` | `200` with an empty list             |
| `com.atproto.repo.getRecord`                                 | `403 InsufficientScope` | `400 RecordNotFound`                 |
| `com.atproto.repo.createRecord`, `putRecord`, `deleteRecord` | `403 InsufficientScope` | `403 Forbidden`                      |

With `transition:generic`, a closed collection reads as if it were empty: listing it returns no
records and fetching a record from it reports that the record does not exist. The answers are the
same whether or not the collection holds anything, so an app cannot use them to learn what a person
keeps.

The second group is refused for every OAuth grant, before any scope is checked, so the answer is
always `403 Forbidden` and never `InsufficientScope`.

| Method                                                         | Any OAuth grant |
| -------------------------------------------------------------- | --------------- |
| `com.atproto.repo.uploadBlob`                                  | `403 Forbidden` |
| `com.atproto.sync.getRepo`, `getLatestCommit`, `getRepoStatus` | `403 Forbidden` |
| `com.atproto.sync.getBlob`                                     | `403 Forbidden` |

Each of these has its own reason.

- Exporting the repository, and reading its latest commit or status, are refused because each
  carries or summarises every record in the repository, including closed collections.
- Downloading a blob (an uploaded file) is refused because a blob does not record which app uploaded
  it, so the server cannot tell an app's own files from a person's journal media.
- Uploads stay closed even with `transition:generic`. With no open collection, an uploaded file
  could never be referred to by a record or removed, so an app could only fill the account's
  storage.

Both tables describe a repository the server does not show to the public. That is every repository
unless a deployment turns on `ATPROTO_FIREHOSE_ENABLED` and the account meets the conditions listed
under
[Which accounts are shown](repository-event-stream.md#which-accounts-are-shown), one of which is
that its owner has made the identity public. For a repository that is shown, the read methods
(`getRecord`, `listRecords`, the three `com.atproto.sync` reads and `getBlob`) are answered from its
public records before any token is looked at. An OAuth app then receives exactly what a caller with
no sign-in receives, and its grant adds nothing. Which records are public is covered in
[AT Protocol interoperability](../explanation/atproto-interoperability.md).

`com.atproto.repo.describeRepo` needs no sign-in, so it is limited in two ways. It answers only for
an account whose owner has made the identity public, and for every other account it answers
`400 RepoNotFound`, the same as for a repository that does not exist. For a public identity it lists
only the collections that are open to apps, and none are. What making an identity public
means is covered in
[AT Protocol identity custody](../explanation/atproto-identity-custody.md#private-until-its-owner-publishes-it).

The LogDate apps never use OAuth tokens. They keep their own sign-in and keep full access to their
own data, so none of these limits change them.

## The authorization page

The page is where a person with no password approves another app. It is rendered by
[`OAuthAuthorizationPage.kt`](../../server/src/main/kotlin/app/logdate/server/routes/OAuthAuthorizationPage.kt)
and driven by
[`OAuthPasskeySignInService.kt`](../../server/src/main/kotlin/app/logdate/server/oauth/OAuthPasskeySignInService.kt).

### Continuing is the consent

The page offers **Continue with passkey** and **Cancel**. Picking a passkey in the browser's own
passkey sheet is the approval. There is no separate "Allow" step after signing in, which means the
page never holds a signed-in browser session and there is no extra token to protect between two
steps. Every request is approved by its own passkey ceremony.

The page states what continuing allows: the app learns which LogDate account this is, and cannot
see or change anything in the account. That sentence is true for both scopes while no collection is
open to apps, and it must change in the same commit that opens one.

### How the app is named

A client chooses its own `client_name`, and a self-reported name can claim to be anything. The page
therefore names an app by its full `client_id` URL, which the app cannot fake because the server
fetched its metadata from that address. Only a client listed in `LOGDATE_OAUTH_TRUSTED_CLIENT_IDS`
is named by its own `client_name`, and the page then also shows the host its `client_id` lives at.
Trust changes the name shown and the refresh-token lifetime. It grants no extra scope.

### How the account is named

The page names an account, such as `@alice.logdate.app`, only when the client passed a `login_hint`
that matches an active account on this server. The lookup only reads. Nobody has proven who they are
at that point, so nothing about any account is created or changed. When the hint names no account
here, the page says so and offers no sign-in.

### Response headers

Every response that renders the page carries these headers.

| Header                    | Value                                                                                                                        | Why                                                                                       |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `Content-Security-Policy` | `default-src 'none'; script-src 'nonce-…'; style-src 'nonce-…'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'` | Only the page's own inline script and style run, and the script may only call this server |
| `X-Frame-Options`         | `DENY`                                                                                                                       | Another site cannot place the page in a frame and hide its button under its own           |
| `X-Content-Type-Options`  | `nosniff`                                                                                                                    | The browser does not reinterpret the response as another type                             |
| `Referrer-Policy`         | `no-referrer`                                                                                                                | The `request_uri` in the address is not passed to the next site                           |
| `Cache-Control`           | `no-store`                                                                                                                   | The page is never kept by a browser or a proxy                                            |

The nonce in the content security policy is 16 random bytes, generated for each response. Every
value written into the page is HTML-escaped, and the request data handed to the script is escaped so
that it cannot close its `<script>` element.

### The passkey ceremony

1. The page posts `{"request_uri": …}` to `POST /oauth/authorize/passkey/options`. The server checks
   the request is still valid and issues a challenge of its own kind, `oauth_authorize`, bound to
   that one `request_uri`. A challenge issued for signing in to the LogDate apps cannot approve a
   request, and one request's challenge cannot approve another.
2. The answer contains `challenge`, `rp_id`, `allow_credentials`, `timeout` and `user_verification`.
   `rp_id` is the relying-party ID. In the passkey standard the relying party is the service a
   passkey signs in to, and its ID is the domain name every passkey for that service is registered
   under. **`allow_credentials` is always empty**, even when the `login_hint` names a real account.
   If the options listed an account's credential IDs, anyone could push a request for someone's
   handle and learn which passkeys that person has. With an empty list the browser offers the
   person's own discoverable passkeys, which is the kind LogDate creates. `user_verification` is
   `required`, and `timeout` is 300,000 milliseconds.
3. The browser runs `navigator.credentials.get`, and the page posts the result with the
   `request_uri` and `challenge` to `POST /oauth/authorize/passkey`.
4. The server verifies the assertion cryptographically against the bound challenge, loads the
   account the passkey belongs to, requires it to be active, and makes sure it has its DID and
   handle. When the request carried a `login_hint`, the account must match it.
5. The server approves the pushed request and answers `{"redirect_uri": …}`, which carries `code`,
   `state` and `iss`. The page sends the browser there.

A failure keeps the person on the page with a message they can act on, and leaves the pushed request
usable so that they can try another passkey. A passkey that cannot be verified, or that is not
registered to a LogDate account, is answered `401 access_denied`. A passkey for a different account
from the one the client asked for is answered `403 access_denied`.

### Cancelling

**Cancel** posts `decision=deny` and the `request_uri` to `POST /oauth/authorize`, and the browser
returns to the client with `error=access_denied`. Cancelling needs no sign-in. Only the browser that
started the request knows its `request_uri`, and denying it affects nothing but that request.
Approving without a bearer token is not possible on this route: any other decision is answered
`401 login_required`.

### What the page shows when something is wrong

| Situation                                                      | Page                                      | Status |
| -------------------------------------------------------------- | ----------------------------------------- | ------ |
| The request is unknown, already decided or expired             | "This sign-in link has expired"           | `400`  |
| The `client_id` in the address differs from the pushed request | "This sign-in link doesn't match its app" | `400`  |
| The `login_hint` names no account on this server               | "No LogDate account for …"                | `400`  |
| The server could not load the request                          | "Sign-in isn't available right now"       | `503`  |

"Expired" is shown only for a request that is unknown or past its time. A server that cannot reach
its database is the server's problem, and telling the person their link expired would send them
back to start again for nothing.

### Attempt limits and the trusted forwarded address

Both passkey endpoints allow 10 attempts per minute per client address, the same limit as passkey
sign-in in the LogDate apps. Beyond it they answer `429` with a `Retry-After` header. The two
endpoints are counted separately, and the count is kept in memory by each server instance.

The limit is only useful when the address is the visitor's, and behind a proxy the connection always
comes from the proxy. The server therefore reads the address from the `X-Forwarded-For` header, and
is strict about which entry it believes. The rule is in
[`ClientAddress.kt`](../../server/src/main/kotlin/app/logdate/server/ClientAddress.kt).

- Each proxy appends the address it received the request from. The **last** entry is the one the
  nearest trusted proxy wrote. Everything before it arrived with the request and may be invented.
- `TRUSTED_PROXY_HOPS` counts further trusted proxies that append after that one, and defaults to
  `0`. The server reads the entry at that many positions from the end.
- When there are not enough entries, or `TRUST_FORWARDED_HEADERS` is off, the address is the
  connection's peer.
- A value of `TRUSTED_PROXY_HOPS` that is not a whole number of zero or more stops the server at
  startup. Guessing too high would let clients choose their own address, and guessing too low would
  put every visitor in one shared limit.

From the same proxy the server trusts only the scheme it received the request over. A client-sent
`X-Forwarded-Host` or `X-Forwarded-Port` is ignored.

## Server failures

A database failure is the server's fault, not the caller's. It is logged with the full error and
answered with a fixed message that never contains SQL text.

| Failure                                                    | Answer                               |
| ---------------------------------------------------------- | ------------------------------------ |
| A database error anywhere in an OAuth request              | `503` with `temporarily_unavailable` |
| Any other unexpected error                                 | `500` with `server_error`            |
| A DPoP check on `/xrpc` that cannot reach the replay store | `503 TemporarilyUnavailable`         |

A DPoP check that cannot reach its store is reported as a server failure, never as a bad proof, so
a client does not discard a good session because of an outage.

## Replay protection

Three kinds of value are accepted once.

| Value                                            | Remembered for                                               |
| ------------------------------------------------ | ------------------------------------------------------------ |
| A DPoP proof, by its key thumbprint and `jti`    | 10 minutes, the full window in which its `iat` is acceptable |
| A PKCE `code_challenge`                          | 1 hour                                                       |
| A client assertion, by its `client_id` and `jti` | Until the assertion expires                                  |

A deployment runs more than one server instance, so a replay can reach a different instance from
the first use. With a database, all three are recorded in one table every instance shares,
`oauth_used_values`, through
[`PostgreSQLOAuthReplayStore.kt`](../../server/src/main/kotlin/app/logdate/server/database/PostgreSQLOAuthReplayStore.kt).

- Each row holds the SHA-256 of the namespaced value and its expiry, so the values themselves are
  never stored.
- Recording a value is one insert that does nothing when the row already exists, so two instances
  racing on the same value cannot both accept it.
- Each insert also deletes up to 100 expired rows, which keeps the table small without a separate
  job. The delete checks expiry again, so a value another instance has just recorded is left alone.

A server running without a database keeps the same records in memory, up to 100,000 entries, each
expiring on its own lifetime.

## Revocation

Removing a passkey from an account, or deleting the account, revokes every grant the account has
made. The removed passkey may be the one that approved an app, and a deleted account must not leave
working tokens behind. Account deletion revokes inside
[`AccountDeletionService.kt`](../../server/src/main/kotlin/app/logdate/server/auth/AccountDeletionService.kt),
so every way of deleting an account gets it. Because access tokens cannot be revoked, an app keeps
working for at most the 15 minutes its current access token has left.

Revocation has to hold even against a refresh that is already running. Every grant records when the
person approved it, and revoking records the revocation time for the account in
`oauth_subject_revocations`. A grant approved at or before that time can never be used again.

- Refreshing replaces the old refresh token in one transaction. It locks the token's row, checks
  that the token is unrevoked, unexpired and approved after the account's last revocation, deletes
  it and saves the replacement. The new tokens are returned only when this succeeds.
- Two refreshes of one token cannot both succeed. The second waits for the row lock and then finds
  the token gone.
- When a refresh and a revocation interleave, the replacement token keeps its grant's approval time,
  which is before the revocation, so it can never be used.
- Revoking also deletes the account's unredeemed authorization codes, and redeeming a code saves its
  refresh token only when the grant was approved after the last revocation.
- The database store takes both times from the database clock, so clock differences between server
  instances cannot let a grant outlive its revocation.

## The flag that gates the page

`LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED` turns the authorization page on. Only the exact value `true`
enables it. It is off by default.

With the flag off, a browser without a bearer token gets `401 login_required` from
`/oauth/authorize`, and both passkey endpoints answer `501`. Only a LogDate app holding a bearer
token can approve a request. Scope enforcement, token lifetimes, replay protection and revocation
apply whether the flag is on or off.

A browser offers a passkey only on a page whose host is the passkey's relying-party ID, the domain
name the passkey was registered under, or a subdomain of it. LogDate's relying-party ID is
configured as `WEBAUTHN_RP_ID`. The page is served from the issuer, so the issuer's host must sit
inside that domain. For example, an issuer at `cloud.logdate.app` works with the relying party
`logdate.app`. When the flag is on:

- the issuer's origin is added automatically to the origins passkey verification accepts;
- the server refuses to start when the issuer's host is outside the relying party, because the page
  would show a button that can never work;
- the server refuses to start when `WEBAUTHN_STRICT_VERIFICATION` is off, because an assertion would
  then be accepted without being checked and anyone could approve an app on someone else's behalf.

Both refusals name the values involved.

## Passwords and app passwords

Sign-in is passkey-only. `com.atproto.server.createSession` and `com.atproto.server.createAccount`,
the AT Protocol methods that take a password, are refused with `400 InvalidRequest` before their
body is read, so no request reaches the account or password services through them.

An app password is a separate secret that a person creates in a signed-in app and pastes into a
client that only knows how to sign in with a password. LogDate does not offer them, because they
are long-lived secrets that can be copied. A passkey-only account therefore cannot use clients that
only support password sign-in.

## Where the state lives

| State                                                   | With a database                                                                     | Without one |
| ------------------------------------------------------- | ----------------------------------------------------------------------------------- | ----------- |
| Pushed requests, authorization codes, refresh tokens    | `oauth_authorization_requests`, `oauth_authorization_codes`, `oauth_refresh_tokens` | Memory      |
| Revocation times                                        | `oauth_subject_revocations`                                                         | Memory      |
| Used DPoP proofs, PKCE challenges and client assertions | `oauth_used_values`                                                                 | Memory      |
| Access-token signing keys                               | `oauth_signing_keys`, encrypted                                                     | Memory      |
| Current DPoP nonce                                      | Memory, per instance                                                                | Memory      |
| Client metadata cache                                   | Memory, per instance                                                                | Memory      |

Keeping the first four in the database means a restart or a second instance between any two steps
of the flow does not lose the sign-in.
