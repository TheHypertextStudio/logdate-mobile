# atproto-oauth-client

`studio.hypertext.atproto:atproto-oauth-client` lets a server sign people in with accounts hosted
anywhere in the Atmosphere (Bluesky, a self-hosted PDS, or any other AT Protocol host) and then
call those accounts' data servers on their behalf.

It implements the [AT Protocol OAuth profile](https://atproto.com/specs/oauth) as a
**confidential client**: the client proves who it is with its own signing key, which lets
authorization servers trust it with longer sessions than a browser-only app would get.

## How an AT Protocol sign-in works

If you have used "Sign in with Google", the shape is familiar: send the person to their provider,
they approve, they come back with a code, and you trade the code for tokens. Three things make the
AT Protocol version different, and the client handles all of them.

1. **There is no single provider.** A handle such as `alice.example.com` first resolves to a
   permanent account ID, a DID (`did:plc:…`). The DID document names the account's data server
   (its PDS). The data server's `/.well-known/oauth-protected-resource` document names its
   authorization server, which may be the PDS itself or a shared "entryway" such as
   `https://bsky.social`. The client follows that chain on every sign-in.
2. **Clients register themselves.** There is no developer console. Your `client_id` is the
   `https://` URL of a small JSON document (the _client metadata_) that you host. Authorization
   servers fetch it to learn your redirect URIs and public keys.
3. **Every token is bound to a key.** Each session gets its own DPoP key. Every request (to the
   authorization server and to the data server) carries a short signed proof made with that key,
   so a stolen token is useless without the key. Servers also hand out a rotating _nonce_ that
   proofs must include; when a nonce changes, the server answers `use_dpop_nonce` and the client
   retries once with the new value.

The full flow, end to end:

| Step             | What the client does                                                                                                                                                                                                                                         | Standard                                              |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------- |
| Resolve          | Handle → DID → DID document (handle checked in both directions) → PDS → authorization server metadata, checked against the AT Protocol profile (see below)                                                                                                   | AT Protocol identity, RFC 9728, RFC 8414              |
| Push the request | POSTs the request parameters to the PAR endpoint with a PKCE challenge, a DPoP proof, and a signed client assertion                                                                                                                                          | RFC 9126, RFC 7636 (S256), RFC 9449, RFC 7523         |
| Redirect         | Returns `authorization_endpoint?client_id=…&request_uri=…` for you to redirect the browser to                                                                                                                                                                | RFC 9126                                              |
| Callback         | Checks `state`, then requires `iss` to match the server the request went to, even on an error response. Exchanges the code with the PKCE verifier, then confirms that `sub` is the expected DID and that the DID's PDS really uses this authorization server | RFC 9207, AT Protocol OAuth "Identity Authentication" |
| Use              | Adds `Authorization: DPoP <token>` and a proof with an `ath` hash to every data server request                                                                                                                                                               | RFC 9449                                              |
| Refresh          | Re-verifies the account's authorization server, then trades the single-use refresh token for new tokens with the same DPoP key and client key                                                                                                                | RFC 6749, AT Protocol OAuth "Tokens"                  |
| Sign out         | Revokes the refresh token and deletes the stored session                                                                                                                                                                                                     | RFC 7009                                              |

### Which authorization servers the client accepts

Before it sends anything to an authorization server, the client checks the server's metadata and
refuses the server unless all of these hold:

- It supports client metadata documents, pushed authorization requests, and **requires** them
  (`require_pushed_authorization_requests`).
- It sends `iss` in every authorization response (`authorization_response_iss_parameter_supported`),
  so a callback cannot be passed off as coming from a different server (a "mix-up" attack).
- It supports the `authorization_code` and `refresh_token` grants, the `code` response type, PKCE
  `S256`, DPoP with ES256, the `atproto` scope, and `private_key_jwt` client authentication with
  ES256. A server that lists `none` among its client assertion algorithms is refused.
- Every endpoint is an `https://` URL at the issuer's own origin (same host and port). The AT
  Protocol does not spell this out, but the reference server and every known deployment work this
  way, and it stops a tampered metadata document from pointing token requests at another server.

## Setting up a client

### 1. Create a client key

The client authenticates with an ES256 (P-256) key. Generate one once and keep the private JSON in
your secret manager:

```kotlin
val key: OAuthClientKey = JvmOAuthCrypto.generateClientKey(kid = "2026-10")
val privateJwkJson = Json.encodeToString(JsonWebKey.serializer(), key.privateJwk)
```

Load it at startup with `OAuthClientKey(Json.decodeFromString(JsonWebKey.serializer(), json))`, or
convert an existing PKCS#8 key with `JvmOAuthCrypto.privateJwkFromPkcs8(bytes, kid)`.

**Rotating keys:** put the new key first in `keys` (it signs new sessions) and keep the old key
after it. Each session stays bound to the key that started it, so remove the old key only when no
stored session uses it. Sessions whose key was removed end with `OAuthSessionRevokedException`
when they next renew, and pending sign-ins that used it fail with `OAuthClientKeyRemovedException`.

### 2. Configure and build the client

```kotlin
val config =
    OAuthClientConfig(
        clientId = "https://logdate.example/oauth/client-metadata.json",
        redirectUris = listOf("https://logdate.example/oauth/callback"),
        keys = listOf(key),
        scope = "atproto transition:generic",
        clientName = "LogDate",
        clientUri = "https://logdate.example",
    )

val httpClient = JvmOAuthHttp.publicInternetClient() // see "Network safety" below
val identityResolver =
    DefaultIdentityResolver(DidPlcResolver(httpClient), WellKnownHandleResolver(httpClient))

val oauth =
    AtprotoOAuthClient(
        config = config,
        identityResolver = identityResolver, // from atproto-identity
        httpClient = httpClient,
        crypto = JvmOAuthCrypto,
        stateStore = myStateStore,           // your database
        sessionStore = mySessionStore,       // your database
        sessionLock = mySharedLock,          // required with more than one instance, see below
    )
```

`OAuthClientConfig` rejects settings the specification forbids, such as an `http://` client ID, a
port in the client ID, or a scope without `atproto`.

Each authorization attempt remembers its requested permissions. A token response may grant fewer
permissions, but cannot add permissions the attempt did not request. The client revokes an expanded
new grant before storing it, preserving any existing session. A refresh cannot expand the session's
previously granted permissions either; the client revokes the new tokens and ends that session.
Request additional permissions through a separate authorization that explains the new access.

Create one `AtprotoOAuthClient` per process and keep it for the life of the process. It opens two
HTTP clients on your engine (one for authorization servers, one for data servers) that every
session shares; call `oauth.close()` on shutdown to release them. Closing it leaves your own client
and its engine open.

### 3. Serve the client metadata

Serve `oauth.clientMetadataJson()` at exactly the `clientId` URL, with HTTP 200 and
`Content-Type: application/json`. Authorization servers fetch it on every sign-in and periodically
afterwards. If you set `jwksUri`, also serve `oauth.jwks` there.

### 4. Sign people in

```kotlin
// GET /login?handle=alice.example.com
val redirect = oauth.authorize(handle, OAuthAuthorizeOptions(appState = "/journal"))
call.respondRedirect(redirect.url)

// GET /oauth/callback?code=…&state=…&iss=…
val query = call.request.queryParameters
require(query.names().all { query.getAll(it)?.size == 1 }) { "Duplicate callback parameters" }
val parameters = query.names().associateWith { query[it]!! }
val result = oauth.callback(parameters)
val did = result.session.did // the verified account ID; store this, not the handle
```

`authorize` also accepts a DID, or the `https://` URL of a data server or entryway for people who
know their provider but not their handle.

Handle verification follows the [AT Protocol identity rules](https://atproto.com/specs/did): only
the first syntactically valid `at://` handle in the DID document is its claimed handle. Other URI
aliases are ignored, and a later handle cannot replace a first handle whose link does not verify.
`DidDocument.claimedHandle()` exposes the same selection rule for other callers.

Use the `callback(parameters, acceptIdentity)` overload to validate an application's pending
sign-in after provider identity and permissions are verified. The hook runs under the DID's
session lock, before an existing provider session is revoked or replaced. If the hook rejects or
is canceled, only the new grant is revoked; the existing provider session is preserved.

An identity proof recorded by the hook is distinct from retaining provider access. Its writes can
survive a later provider-session storage failure or cancellation. Such a failure can end the prior
provider connection after acceptance, so require reauthentication for provider-dependent actions
until a usable session is restored. Do not treat an accepted identity proof as a journal decryption
key or as evidence that provider tokens were stored.

### 5. Call the account's data server

```kotlin
val session = oauth.restore(did)
val xrpc = session.xrpcClient()
val profile = xrpc.queryRaw(Nsid.require("com.atproto.server.getSession"))
```

Sessions are free to create: they hold no tokens and no HTTP client of their own, so restoring one
on every request is fine. Before each XRPC call the session renews its access token if needed and
looks up the account's current data server, so it keeps working after the account moves.

For anything XRPC does not cover, `session.request { url("$pdsUrl/…") }` sends an authenticated
Ktor request. It only sends requests to the account's own data server and does not follow
redirects (a 3xx comes back as it is), so the token cannot leak to another host. It uses your
client's engine but **not its plugins**: if you installed request logging or default headers on the
client you passed in, they do not apply here. That is deliberate, so that a logging plugin never
records an access token.

## Network safety

Signing someone in means fetching URLs that strangers control: their handle's well-known file,
the data server in their DID document, and that server's metadata. Left unchecked, a handle or DID
document could point your server at `localhost`, a cloud metadata service such as
`169.254.169.254`, or another machine on your private network. This is called server-side request
forgery (SSRF).

**You must pass an `HttpClient` whose engine refuses private, loopback, and link-local addresses,
and checks them after DNS resolution** (so a name that resolves to a public address once and a
private one later, a trick called DNS rebinding, is still refused). Use the same client for the
identity resolver.

On the JVM, `JvmOAuthHttp.publicInternetClient()` is such a client. It is backed by OkHttp and
checks the address of every connection at the moment the socket connects. It ignores proxy
settings, because a proxy would resolve names where the check cannot see them. You can pass it
extra configuration, such as timeouts; the proxy and socket settings are applied after yours, so
nothing you pass can turn the check off. On other platforms, build the equivalent with your
engine's connection hooks.

The OAuth client also protects itself:

- **Only the engine of your client is used.** Metadata, pushed authorization, token, refresh, and
  revocation requests go through an internal client with no plugins, so tokens and client
  assertions never pass through a logging plugin. Do not add logging interceptors to the engine
  itself (for example an OkHttp `HttpLoggingInterceptor`), because those would see them.
- **Redirects are refused, not followed.** A metadata document must be served at its well-known
  URL, and token endpoints must answer directly.
- **Responses are capped.** Metadata documents and authorization server responses larger than
  64 KiB are refused.
- **Requests time out.** Connections give up after 10 seconds, and authorization server requests
  after 20.
- **Error messages never quote a response body.** They carry the OAuth `error` and
  `error_description` only, shortened and stripped of control characters.

## Sessions, storage, and multiple server instances

The library never decides where data lives. You provide two stores:

- `OAuthStateStore` holds a pending sign-in between `authorize` and `callback`. `take` must return
  and delete in one step so a callback cannot be replayed. `save` receives an `expiresAt` time
  (one hour after the sign-in started, by default): most sign-ins that start are never finished,
  so give entries a time to live, such as a Redis `EXPIREAT` or a column a cleanup job checks. The
  client refuses an expired entry either way.
- `OAuthSessionStore` holds the session for each DID: tokens, the private DPoP key, and the client
  key ID. Both stored types are `@Serializable`, so saving them as JSON is one line.

Both contain secrets. Encrypt them at rest with a deployment key kept outside the store, and do not
log their JSON. `OAuthAuthorizationState.requestedScope` is optional when reading older stored
attempts; those use the configured client scope as their permission limit. Preserve the field in
new attempts so narrower requests remain bounded after a restart.

Because the session object holds no state of its own, any instance that shares the stores can
`restore` and use a session, including after a restart. `InMemoryOAuthStateStore` and
`InMemoryOAuthSessionStore` exist for tests and single-process development only.

Refresh tokens are single-use. If two instances refresh the same session at the same time, the
second one presents a refresh token that has already been spent, and authorization servers that
detect this (the reference one included) end the whole session. The person is signed out. The
client reads sessions without any lock, and takes the `OAuthSessionLock` only to renew one. Inside
the lock it reads the session again, so a request that waited behind another renewal uses that
renewal's tokens instead of spending the refresh token a second time. Replacing an account's
session after a new sign-in, and signing out, also happen under the lock.

The default `InProcessOAuthSessionLock` covers one process. **If more than one instance shares the
session store, they must share an `OAuthSessionLock` too**: one backed by a database advisory lock
or a Redis lease, for example. When a refresh is rejected, the client waits a second and re-reads
the store in case another instance has just saved newer tokens, but that does not replace the
lock. On a server that detects refresh token reuse, those newer tokens have already been revoked,
so the next request ends the session. The re-read only helps against servers without reuse
detection, or when a lock's lease ran out in the middle of a refresh.

When a renewal finds that the account's authorization server changed, or the new tokens belong to
another account, the client revokes the tokens it can and ends the session.

### Durable connection state and guarded sign-out

Stores can override `delete(did, reason)` to retain a bounded termination reason in the same
transaction as token removal. Existing stores delegate to `delete(did)` by default. Expiry,
known revocation, ambiguous refresh rejection and changed hosting authority remain distinct.
A delayed data-server rejection checks the rejected session under the shared lock before deletion,
so it cannot erase a newer callback grant.

`revokeIf(did, beforeRevoke, removeLocal)` evaluates the guard inside the existing session lock
before contacting the provider. Its local completion callback also runs under that lock, including
on provider failure or cancellation, so an application can atomically remove tokens and write its
own disconnection receipt. The callback must actually complete local removal; it must not reacquire
the same non-reentrant session lock. A false guard performs no revocation or removal. `NO_SESSION`
does not confirm remote revocation, and only `CONFIRMED` records a successful provider response.
Local completion failure is thrown, with any earlier revocation failure retained as suppressed.

Applications that require fresh consent after a PDS move can set
`requireAuthorizationAfterPdsChange = true`. A verified PDS or issuer change then ends the old
session with `OAuthSessionAuthorityChangedException` before renewal and without forwarding tokens
to the new host. The default preserves the client's existing same-issuer migration behavior.
This policy changes authorization; it does not move an identity or its data.

## Errors

Every failure is a subclass of `OAuthClientException`, so you can tell the person what actually
happened. The exceptions are invalid arguments (a redirect URI or scope outside the configuration),
which throw `IllegalArgumentException`, and errors from your own stores and lock, which pass
through unchanged.

| Exception                                                                                             | Meaning                                                                                                                                                       | What to tell the person                                      |
| ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| `OAuthIdentityResolutionException`                                                                    | The handle or DID could not be resolved                                                                                                                       | Check the handle                                             |
| `OAuthHandleMismatchException`                                                                        | The handle points at an account that does not claim it                                                                                                        | The handle is misconfigured                                  |
| `OAuthPdsNotFoundException`                                                                           | The account has no usable data server                                                                                                                         | Contact their provider                                       |
| `OAuthServerMetadataException`                                                                        | Their server's OAuth setup is missing or non-compliant                                                                                                        | Their provider does not support this sign-in                 |
| `OAuthConsentDeniedException`                                                                         | They declined on the consent screen                                                                                                                           | Nothing went wrong; offer to try again                       |
| `OAuthAuthorizationErrorException`                                                                    | The server returned another OAuth error                                                                                                                       | Try again later                                              |
| `OAuthCallbackException`                                                                              | The callback did not match a pending sign-in (`reason` says why)                                                                                              | Start the sign-in again                                      |
| `OAuthIssuerMismatchException`, `OAuthSubjectMismatchException`, `OAuthInvalidTokenResponseException` | A security check failed; any tokens were revoked                                                                                                              | Sign-in failed                                               |
| `OAuthServerResponseException`                                                                        | An authorization server request failed (`error` holds the OAuth code)                                                                                         | Try again later                                              |
| `OAuthSessionNotFoundException`                                                                       | No stored session for this DID                                                                                                                                | Sign in                                                      |
| `OAuthSessionExpiredException`                                                                        | The access token expired and the server issued no refresh token                                                                                               | Sign in again                                                |
| `OAuthSessionRejectedException`                                                                       | The server refused the refresh token (`invalid_grant`): the session expired or was revoked, and the server does not say which                                 | Sign in again                                                |
| `OAuthSessionAuthorityChangedException`                                                               | The verified PDS or issuer changed under the strict hosting policy                                                                                            | Reconnect through the newly discovered authority             |
| `OAuthSessionRevokedException`                                                                        | The session failed a security check, or its client key was removed                                                                                            | Sign in again                                                |
| `OAuthForeignOriginException`                                                                         | A `session.request` was addressed to a server other than the account's data server, or a renewal found the account had moved while the request was being made | Nothing; retry the call                                      |
| `OAuthRevocationException`                                                                            | Sign-out removed the local session but the server did not confirm                                                                                             | Nothing; the tokens expire on their own                      |
| `OAuthClientKeyRemovedException`                                                                      | A pending sign-in or session uses a client key you removed from the configuration                                                                             | Start the sign-in again; keep retired keys configured longer |

Sessions that end (`OAuthSessionEndedException`) are removed from the store automatically. Through
`xrpcClient()`, session errors arrive as the `cause` of an `XrpcTransportException`.

If signing out fails both to revoke the tokens and to delete the stored session, the store's
exception is thrown with the revocation failure attached as a suppressed exception.

## Platforms

The protocol logic is common Kotlin and compiles for the JVM, Android, and iOS. Signing, hashing,
and secure randomness come from an `OAuthCrypto` implementation; the module ships `JvmOAuthCrypto`
for the JVM. On other platforms, implement `OAuthCrypto` (four functions) with the platform's
crypto library.

## Known limits

- **Handle resolution depends on your `IdentityResolver`.** The AT Protocol allows handles to be
  published through DNS TXT records or an HTTPS well-known file. Pass a resolver that supports both,
  or some people will not be able to sign in.
- **Only ES256** is used for DPoP proofs and client assertions. It is the algorithm every AT
  Protocol server must support.
- **Missing DPoP nonces are tolerated by default.** The AT Protocol requires servers to issue
  nonces, but development servers can run with them turned off, so like the reference TypeScript
  client this one does not insist. Pass `strictDpopNonces = true` to refuse an authorization server
  whose pushed authorization response has no `DPoP-Nonce` header. Every sign-in starts with that
  request, so such a server is refused before it issues any tokens.
- **Request bodies must be re-sendable.** A data server request may be sent up to three times (new
  nonce, renewed token), so streaming bodies are not supported through `session.request`.

## How this is tested

There is no automated test against a local copy of the reference server. Confidential clients need
an `https://` client ID that the authorization server can fetch, and the reference server refuses
private and `localhost` addresses for that fetch, so a local Docker setup would need public DNS and
TLS for the test client. Instead there are two layers:

- `MockAtmosphere` (in `src/jvmTest`) is a stand-in built from the specifications: a PLC directory,
  handle resolution, a data server, and an entryway authorization server. It requires server
  nonces, checks every DPoP proof and client assertion with an independent JOSE library (Nimbus),
  binds sessions to their DPoP key and client key, rotates single-use refresh tokens, and detects
  replays. The flow tests run every sign-in, refresh, revocation, and failure path against it.
- `LiveBlueskyInteropTest` runs against Bluesky's production authorization server, which uses the
  reference `@atproto/oauth-provider`, when `ATPROTO_OAUTH_LIVE=1` is set. It resolves a real
  account to `https://bsky.social`, and sends a pushed authorization request that Bluesky accepts
  up to the point of fetching client metadata, which confirms the DPoP proofs, nonce retry, and
  request encoding work with the reference server.

```bash
./gradlew :shared:atproto-oauth-client:jvmTest
ATPROTO_OAUTH_LIVE=1 ./gradlew :shared:atproto-oauth-client:jvmTest --tests '*LiveBlueskyInteropTest*'
```
