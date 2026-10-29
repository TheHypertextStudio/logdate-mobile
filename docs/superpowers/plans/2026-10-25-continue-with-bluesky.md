# Continue with Bluesky implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** People use their existing Atmosphere identity in LogDate without creating a hosted identity or exposing their private journal.

**Architecture:** The server uses the existing confidential OAuth client. Encrypted PostgreSQL stores and a transaction-scoped advisory lock support callbacks and refreshes on different instances. A short-lived, proof-bound handoff returns control to the app; passkeys still protect journal keys. Hosted and external identities are explicit account kinds. The feature stays disabled until the complete account and app paths pass.

**Tech Stack:** Kotlin Multiplatform, Ktor, PostgreSQL, the existing `shared/atproto-oauth-client`, passkeys and sealed personal spaces.

**Spec:** `docs/superpowers/specs/2026-10-25-atproto-interop-readiness.md`, Direction B; `docs/superpowers/specs/2026-10-09-logdate-atproto-interoperability-design.md`.

## Global constraints

- The server is the confidential client; apps never receive provider tokens, private DPoP keys or client keys.
- Ask for `atproto` for sign-in. Do not request repository write permissions or silently fall back to broader access. Deliberate publishing needs its own later consent.
- Use `https://cloud.logdate.app/oauth/client-metadata.json` and `/oauth/bluesky/callback` in production; staging uses its own origin and sessions.
- Private records stay sealed in LogDate Cloud. External accounts never provision, publish or advertise a hosted LogDate DID or public repository.
- No automatic account association by email or handle. Associate only the verified provider DID.
- `LOGDATE_BLUESKY_SIGN_IN_ENABLED` defaults to false. Enabling requires shared database storage, stable provisioned client keys and the configured signing-key encryption key; never generate production keys on startup.
- Run builds and tests in the foreground through `disk-janitor guard`, with an explicit owner and artifact root under `/Volumes/WillieDrive/CodexScratch`, a 64 GiB retention limit, a 100 GiB external-volume reserve, and the default 20 GiB internal reserve and 6 GiB growth budget. Missing mounts reject the job. Ad-hoc runs use `--wait-seconds 300`; exit 75 means deferred capacity. Existing CI work stays in RunnerPools, without a nested guard. Resource pressure defers new work and reports overruns instead of killing a running build. Use Gradle `--no-daemon` and Gradle Managed Devices only; no physical Android interaction or real credentials in fixtures.

## Review focus

- A callback can land on another instance and can be consumed only once, including under a race.
- Database payload swaps, alteration and expired requests cannot substitute another identity or session.
- Refreshes on different instances serialize; cancellation and exceptions release locks without starving ordinary database work.
- OAuth alone never opens an existing sealed journal or replaces its passkey/device binding.
- Preserve `identityKind` through every account response, conversion, server-reported account and origin-scoped local cache. Missing or unfamiliar values remain unknown; a client never infers hosted ownership from a DID, handle or storage mode. An older server can still support its existing data path, but cannot implicitly authorize hosted identity operations without reporting the account kind.
- Provider outages, declined consent, unlinking and revocation leave journal access through other sign-in methods intact.

### Task 1: Durable OAuth state and sessions

**Files:** `server/build.gradle.kts`; new `server/.../bluesky/BlueskyOAuthSecretCodec.kt`, `server/.../database/PostgreSQLBlueskyOAuthStores.kt`, `server/.../database/PostgreSQLBlueskyOAuthSessionLock.kt`, and migration `V56__Add_bluesky_oauth_storage.sql`; PostgreSQL integration tests.

**Interfaces:** Implement the library's `OAuthStateStore`, `OAuthSessionStore`, and `OAuthSessionLock`. Constructors take an explicit data source; encrypted stores also take `AtRestKeyCipher` and state expiry uses a replaceable `Clock`.

- [x] Write compiling failing tests for durable round trips, concurrent one-time state consumption, expiry, encrypted-at-rest payloads and row/type binding.
- [x] Implement hashed state lookup keys and encrypted payloads bound to their row kind and identifier. Expired requests are removed; session replacement is atomic.
- [x] Write a failing cross-instance refresh exclusion test and cancellation/exception release tests.
- [ ] Implement `pg_try_advisory_xact_lock` inside a pinned transaction, with a local per-DID queue and cancellation-safe rollback. Use a separate bounded pool for locks.
- [x] Run the PostgreSQL tests against isolated local databases, then server lint and size checks.

### Shared provider fixture

Move the existing independently verified JVM `MockAtmosphere` into the test-only `integration:atproto-oauth-fixtures` module. Both the OAuth library's JVM tests and server integration tests depend on it; production code does not. Preserve its Nimbus verification of client assertions and DPoP proofs, code and refresh-token replay detection, and provider error controls. Verify the existing client test suite after the move before relying on it for server acceptance.

### Task 2: Confidential client and proof-bound app return

**Files:** new `bluesky/BlueskyOAuthConfig.kt`, `bluesky/BlueskySignInService.kt`, `routes/BlueskyRoutes.kt`, a dedicated Koin definition file, routing composition, deployment configuration documentation, and route/contract tests.

**Interfaces:** Serve the library's client metadata at the configured client ID. Starting sign-in returns an authorization URL and an opaque attempt identifier; completion is exchanged with the app's original verifier, never with a provider token. The callback validates the library's state, issuer and subject before recording a verified identity.

- [x] Test disabled defaults and refusal of incomplete enabled configuration.
- [x] Wire the library with `JvmOAuthHttp.publicInternetClient`, the shared stores/lock and stable ES256 keys. Close owned clients/pools on shutdown.
- [x] Compose both supported DID methods and both handle-resolution methods (DNS TXT and HTTPS). Keep bidirectional handle verification and reject reserved/local handle domains before any network lookup.
- [ ] Test callbacks, denials, duplicate parameters, expiry, cross-instance return, verifier mismatch and one-time handoff consumption before implementing the routes.
- [ ] Bind the handoff to the originating app attempt and, for linking, a recent passkey-confirmed LogDate session. Use fixed verified app-return destinations and no tokens in URLs.
- [ ] Verify metadata contains public keys only, no network path reaches private addresses, and public error responses contain no raw provider errors.

### Task 3: External accounts and protected enrollment

**Files:** account model and repositories, a new identity-kind migration, account creation/authentication services, identity/repository guards, passkey enrollment and shared server models; unit and server-client integration tests.

- [x] Test that external signup creates exactly one LogDate account with the verified external DID, and never creates a hosted DID or public repository.
- [x] Add an explicit external identity kind. Internal lookup may use it; hosted discovery, PLC lifecycle, local public repository and event-stream paths must refuse it.
- [ ] Require passkey enrollment before a new account can sync. Existing account access still requires its passkey/recovery keys; provider authentication is not journal recovery.
- [x] Enforce one account per external DID transactionally. No handle/email matching and no overwrite of another account's link.
- [ ] Refresh external handle/PDS metadata through verified resolution on session restore. Preserve the fixed LogDate Cloud data home.

### Task 4: Android, iOS and desktop flows

**Files:** common account sign-in and sign-in-methods settings, domain/data/network contracts, platform browser return handling, strings, ViewModel tests and populated render fixtures.

- [ ] Add "Continue with Bluesky" within the established sign-in composition, behind server capability and client feature flags.
- [ ] Implement secure browser return and verifier storage on each platform. Cover process death, cancellation, duplicate returns, resumed enrollment and expired handoffs.
- [ ] Add visible linked, expired, revoked and disconnected states. Unlinking must require another usable sign-in method and preserve journal/device keys.
- [ ] Render and inspect matching phone/tablet/desktop states, large text, dark mode and accessibility; run settings contracts for sign-in-methods changes.

### Task 5: End-to-end acceptance

- [ ] Run the complete confidential-client flow against an independent local provider fixture, including restart between start/callback/exchange and concurrent refreshes.
- [ ] Verify no outbound repository writes occur and no private markers leave LogDate Cloud.
- [ ] Verify Android/iOS/desktop passkey enrollment and return flows with the permitted native test targets.
- [ ] Deploy to staging only through the authorized release path, then complete live Bluesky sign-in and revocation acceptance. Local mock success is not live interoperability.
- [ ] Keep affected source files within the repository size limits before delivery.
- [ ] Deliver through intentional commits and a reviewed PR; respect repository approval and atomic staging requirements.

## Trade-offs

The lock transaction holds one dedicated connection while a provider request runs; this avoids expiring leases that can reuse a refresh token, at the cost of bounded extra connections per instance. A local per-DID queue avoids spending several connections on one person. PostgreSQL releases transaction-scoped locks on rollback or disconnect, including through a transaction-pooling proxy. Tokens and pending private keys use the existing rotating encryption-key policy; encrypted envelopes bind their store and row identifier so swapping ciphertext between rows is rejected. Encryption does not make database deletion or replay harmless: those require an explicit reauthentication state, while the private journal remains independent.

Existing-account adoption is the next handoff item and requires a separate interruption-safe resealing/cutover plan. A permanent old-DID alias is insufficient: sealed records, space authority, sharing state and devices must work under the adopted DID before the unused hosted identity is retired. Nothing in this sign-in change publishes that identity or changes journal ownership.

## Confidential-client configuration contract

The server client configuration uses `LOGDATE_BLUESKY_SIGN_IN_ENABLED=false` by default. The explicit enabled value is `true`; invalid boolean values fail validation. This preparatory configuration is not an active sign-in endpoint until the service and application paths are wired.

An enabled client requires the existing shared PostgreSQL connection configuration (`DATABASE_URL` or `INSTANCE_CONNECTION_NAME`), an explicit `ATPROTO_SIGNING_KEY_KEK` of at least 32 characters, `LOGDATE_PUBLIC_ORIGIN`, and `BLUESKY_OAUTH_CLIENT_JWKS`. The latter is a private JWK Set containing one to ten stable ES256 client key pairs, with unique key identifiers; the first signs new sessions. Keep retired client keys available until no session depends on them. Provision them through the deployment's secret-management path. Do not put private keys in source, public metadata, logs or URLs.

Origins use HTTPS on a LogDate subdomain, with no port, credentials, path, query or fragment. Production uses `https://cloud.logdate.app`; staging has its own origin and client keys. Metadata is served at `/oauth/client-metadata.json`, and the provider callback is `/oauth/bluesky/callback`. Only public halves enter metadata, and its sole declared scope is `atproto`. Configuration validates that each private scalar can sign a message verified by its public coordinates before accepting it. Parsing failures discard their original cause so serializer errors cannot expose private key JSON.

## App-return proof contract

The app-return verifier is independent of the provider's PKCE verifier. The app generates and retains its verifier; the server receives only its S256 challenge. The server generates an unguessable 32-byte attempt identifier, stores an encrypted pending attempt with a five-minute lifetime, and includes that identifier only in the OAuth client's server-side app state. A verified provider callback records its DID once and starts a separate five-minute exchange window. Duplicate callbacks cannot replace the DID or extend that window.

Exchanging an attempt requires the original app verifier. A mismatch or a still-pending callback returns no identity and leaves the valid attempt available; a matching completed exchange removes the attempt atomically. The encrypted envelope binds the challenge, expiry, optional account association and verified DID to the row. Its expiry remains authoritative even if the separate cleanup/index column is changed. A browser return URL contains only the attempt identifier, never provider tokens, a journal key or a LogDate session.

Any linking caller must obtain the account association from a recent passkey-confirmed server session, never from a request-supplied account ID. This store proves control of the originating app attempt and the provider identity; it does not unlock an existing journal or authorize DID adoption. Service wiring must preserve those boundaries and rate-limit starts and exchanges.

## Identity-resolution requirement

The existing `DefaultIdentityResolver` composes one DID resolver with one handle resolver; using only `DidPlcResolver` and `WellKnownHandleResolver` omits did:web and DNS-only custom handles. The production adapter must cover both. The [AT Protocol handle specification](https://atproto.com/specs/handle) requires bidirectional confirmation, prefers DNS TXT, and permits HTTPS resolution. `_atproto.<handle>` TXT values use the `did=` prefix; multiple distinct valid DID answers are ambiguous. Reserved and local domains must fail resolution. All DID-document and HTTPS requests must use the same public-address-filtered engine as OAuth requests.

## Callback acceptance and provider retention

The client callback verifies issuer, subject and granted scope before invoking the application's identity-acceptance hook under the DID's refresh lock. The server validates and completes the app attempt in that hook, before the client revokes or replaces any prior provider session. Rejection or cancellation inside the hook revokes only the newly issued grant. This prevents expiry during provider verification from invalidating an existing provider login.

An accepted app proof certifies a verified provider identity and the originating app's verifier; it does not promise retained provider access or journal decryption. It may survive cancellation or a later failure to store the provider session. Once accepted, a later provider-storage failure can still end the previous provider connection, so provider-dependent actions must require reauthentication until a usable connection is restored. The app must distinguish that connection state from the identity proof and continue to require passkey enrollment or existing journal keys. A canceled browser flow must not automatically start enrollment; an explicit resume can exchange a still-valid proof.

The client stores the exact requested scope with each pending authorization, and refuses broader token scopes before replacing an existing session. New grants rejected for excess permissions are revoked. Refreshes cannot expand an existing grant; an expanded refresh is revoked and that provider session ends. Older serialized requests without the optional scope field use the client's configured permissions as their limit. LogDate's metadata and individual requests both remain exactly `atproto`.

## Runtime ownership and identity lookup

The enabled runtime requires an initialized PostgreSQL data source and validates the required OAuth columns without modifying the schema. It owns one public-address-filtered HTTP client and a separate refresh-lock pool capped at four connections with a five-second connection wait. Application shutdown closes the OAuth client's derived clients, the owned HTTP client and the lock pool. It leaves the application's main database pool to its existing owner. Partial construction closes resources already opened. A disabled runtime allocates none of them.

Identity lookup prefers `_atproto` TXT records. Distinct valid DIDs are ambiguous and fail; unrelated or malformed DID values do not become identities. If DNS is absent or unavailable, HTTPS is tried. A timeout owned by the DNS lookup is an availability failure permitting fallback; cancellation by the caller remains cancellation. The JVM adapter uses the system resolver through a fixed DNS JNDI factory, asks only for TXT on an absolute name, bounds responses, and closes its context and enumeration. TXT character strings are concatenated without deleting whitespace inside quoted chunks. No lookup invokes a JNDI object lookup or accepts a caller-selected provider URL.

HTTPS identity requests stream at most 8 KiB for a handle and 64 KiB for a DID document, allow up to three manual HTTPS redirects on port 443, and validate document DID and handle backlinks. Reserved/local handle and did:web domains fail before lookup. All HTTP connections, including redirected identity requests and the OAuth client's derived clients, use the same public-address-filtered engine. DNS itself uses the deployment's configured recursive resolver; it does not introduce a new DNS service. The JDK DNS timeouts and interruptible wrapper bound lookup work; the runtime additionally sets connect, request and socket timeouts for identity HTTP requests. Protocol requests use the OAuth library's own bounded responses and deadlines.

Handle backlinks use the first syntactically valid `at://` alias, as the [DID specification](https://atproto.com/specs/did) requires. A later alias cannot replace a first, unverified claim. The shared `DidDocument.claimedHandle()` helper is used by both server identity lookup and the OAuth resolver. Production did:web resolution rejects explicit ports; localhost and its development-only port exception are unavailable in the public sign-in path.

The enabled application serves the confidential client's metadata at `/oauth/client-metadata.json` as anonymous JSON with a five-minute public cache lifetime. It exposes only public JWK members and the declared `atproto` permission. Disabled runtime contributes neither a client binding nor a metadata route. Key rotation must publish both old and new public keys before selecting the new signing key, allowing metadata caches to refresh; keep retired keys until their sessions end.

External OAuth payloads use the existing signing-key encryption keyring. Decrypt-only seeds permit reads during rotation and new writes use the active seed. The existing hosted-signing-key rewrap routine does not itself rewrite these OAuth tables: retain the needed decrypt-only seed until existing provider sessions have refreshed under the new key, been rewrapped, or been deliberately ended and reconnected. Dropping a needed seed requires provider reauthentication; it does not recover or decrypt journal data. This behavior must remain explicit in the operational rollout guidance.

## External identity ownership

An external account keeps its verified DID and uses personal-space storage. LogDate holds the sealed journal, while the external PDS remains the home of the public AT Protocol repository. An external account has no hosted signing key, hosted PLC recovery key, or LogDate publication consent. A DID is unique across accounts; neither a matching handle nor a matching email establishes ownership.

Ordinary saves preserve the stored identity kind. They also preserve the DID, handle and hosted key fields of external and unknown kinds. Only hosted accounts enter hosted identity provisioning, key lifecycle, repository signing or public discovery. Unknown kinds remain readable and their original database strings survive profile updates, but lifecycle and storage-mode changes fail closed. External handle updates need a separate verified-resolution operation; a profile edit cannot claim a new handle.

The identity-kind migration leaves all existing accounts hosted. It widens the DID column because a valid hostname-only did:web identifier can exceed 255 characters. Database uniqueness remains the final protection against two concurrent enrollments claiming the same DID. The in-memory repository applies the same uniqueness rules for tests.

Adoption must coordinate with hosted key operations already in flight before changing the identity kind, and remove obsolete hosted keys only after personal-space/device continuity is established. These account guards do not by themselves perform adoption or prove migration of sealed records. External enrollment still needs the one-use provider proof bound to a passkey registration; provider authentication alone never issues journal access.

## Passkey enrollment and account confirmation

Exchanging the originating app's OAuth proof yields an opaque, five-minute account continuation. It identifies a new-account path or an existing-account passkey confirmation; it is never a LogDate access token. Account matching uses the verified DID only. Linking proofs bound to another account cannot enter this ordinary sign-in path.

Continuations are encrypted and bound to hashed bearer-token keys. Identity, registration and authentication are separate stages. Spending a token commits before its payload is decoded, so a damaged, expired or mismatched-stage token cannot be replayed. Each passkey ceremony receives a fresh stage token; failed ceremonies need a fresh confirmation. Invalid profile input is rejected before the identity continuation is spent.

A new account keeps the app's canonical owner UUID and the verified external DID, uses personal-space storage, and receives no hosted signing or recovery key. A verified passkey must exist before the account can be returned for authentication. Account creation, passkey storage and the passkey identity insert are one database transaction. A DID, owner, username, passkey or identity conflict must never attach to an existing owner or leave an account without its passkey.

Both new-account and existing-account ceremonies require strict WebAuthn verification, including development deployments. Lightweight fixture verification cannot protect this flow. Existing-account confirmation uses a dedicated challenge purpose, checks that the passkey's actual owner matches the account selected by the verified DID, and rechecks that DID and account standing after verification. Ordinary sign-in and recovery step-up cannot spend a Bluesky confirmation challenge. The external account's Cloud data home stays fixed.

The passkey identity subject supports the full credential size accepted by WebAuthn. [WebAuthn Level 3](https://www.w3.org/TR/2026/REC-webauthn-3-20260825/#credential-id) permits credential IDs up to 1023 bytes, which occupy 1364 unpadded base64url characters. The account identity column must accommodate those IDs without truncation or a partial enrollment.

## Account HTTP continuations

The account endpoints live under `/api/v1/auth/bluesky` and are registered only when the confidential-client runtime is enabled. Route registration requires personal-space storage and constructs the strict passkey service at startup. Serving public OAuth metadata alone does not create account routes.

| POST endpoint              | Result                                                                                                              |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `/exchange`                | Exchanges the originating app's verifier for a one-use account continuation and next step; no LogDate access token. |
| `/registration/begin`      | Binds account details and the canonical owner UUID to a fresh passkey registration challenge.                       |
| `/registration/complete`   | Verifies the attestation, atomically creates the external account/passkey, then issues a LogDate session.           |
| `/authentication/begin`    | Binds a fresh passkey challenge to the existing account selected by the verified DID.                               |
| `/authentication/complete` | Confirms that account's passkey, rechecks the stored identity, then issues a LogDate session.                       |

All responses use `Cache-Control: no-store`. Errors expose stable categories and plain messages, without provider or storage error details. Each operation has an IP-based rate limit; a limited request gets `429` and `Retry-After`.

LogDate session handling distinguishes the stored identity kind from hosted provisioning. External accounts retain their DID and use their personal space without a hosted signing key. Unknown kinds and inconsistent external identities cannot receive refreshed full-account or linked-device tokens. Device refresh uses the current stored DID. Profile edits preserve identity ownership. Hosted recovery step-up grants are issued only for hosted identities.

The browser start/callback, fixed verified app return, client verifier storage, capability advertisement and native flows remain separate acceptance requirements. Flag-gated operation documentation must be checked with the feature enabled before rollout.

## Browser boundary and app return contract

`POST /api/v1/auth/bluesky/start` accepts a handle and the originating app's
SHA-256 verifier challenge. It returns the provider authorization URL and a
public attempt identifier. The app must persist its verifier and the exact
original backend before opening the system browser. No provider token or
LogDate session goes through the browser return.

`GET /oauth/bluesky/callback` accepts one state, one issuer, and exactly one
non-empty code or error. Duplicate parameters and ambiguous callbacks are
refused before consuming provider state. Only an SDK-verified state and issuer
can attach a callback or cancellation to an app attempt. Provider error text
is discarded. The successful or bound-error return is fixed to
`https://app.logdate.app/auth/bluesky/return?attempt=<id>`; unbound failures use
the browser-only `/auth/bluesky/error` page. Neither target is request-controlled.

A bound cancellation records a one-use encrypted failure. The app's original
verifier is still required to consume it; a wrong verifier cannot burn the
pending result. The exchange responds with the bounded `BLUESKY_CANCELED`
category. Provider-session persistence may fail after a verified proof was
accepted; that already accepted proof remains independently exchangeable.

The website fallback has no scripts or credential storage and only a fixed
`studio.hypertext.logdate://auth/bluesky/return?attempt=<id>` action. Custom
schemes can be intercepted, so proof of possession remains essential even
with a verified HTTPS link. Apple clients require an explicit
`applinks:app.logdate.app` association to select its narrow return policy;
the wildcard association selects the apex file. Login, passkey, account,
and error pages must remain browser destinations. Native app return parsing,
secure pending-flow storage, and signed OS acceptance are separate requirements
before enabling the feature. See the website's `docs/deep-linking.md` for the
matching cross-repository contract.

Native preparation lives in `client/device/.../auth`. Return matching accepts
only the canonical HTTPS and custom-scheme URLs with one unencoded 43-character
attempt parameter; other hosts, explicit ports, fragments, extra parameters,
and ambiguous encodings cannot resume sign-in. `BlueskyAttemptStore` retains
one pending attempt in the existing platform secure storage, including the
original HTTPS backend and verifier. It verifies storage writes and removals,
excludes credentials from diagnostic strings, and expires local resumption
after ten minutes; server-side expiry and one-use consumption remain authoritative.
The application graph must own a single store instance, persist it before opening
the browser, and keep the original backend pinned through exchange. Clearing an
old result must not remove a newer attempt. These helpers do not yet constitute
platform browser wiring, secure continuation resumption, or passkey enrollment UI.

## Native HTTP and proof boundary

`BlueskyApiClient` pins an HTTPS origin in its constructor. It creates a
separate request pipeline using the injected transport's engine, without
inheriting authentication, cookies, logging, default query parameters, or
redirect behavior. It owns and closes that pipeline; the injected transport
continues to own the engine. A redirect is an error rather than a reason to
forward a verifier, continuation, or passkey response elsewhere. Requests
have bounded connect, socket, and total timeouts. Streamed success responses
are limited to 1 MiB and error responses to 8 KiB.

The client implements start, exchange, registration begin/complete, and
authentication begin/complete. Error messages and causes from the transport
or server are discarded; only known status/code pairs become specific
failure categories. Browser destinations must use HTTPS without credentials
or a fragment. A session response must explicitly include its account fields
before decoding the shared model, whose older-server defaults are unsuitable
for an external account. External identity kind and personal-space storage
are required; an absent identity kind cannot silently become hosted.

`BlueskyAppProof` generates a fresh 32-byte cryptographic random verifier and
its unpadded base64url SHA-256 challenge. The transformation is covered by the
[known test vector in RFC 7636 Appendix B](https://www.rfc-editor.org/rfc/rfc7636.html#appendix-B).
This proof protects the app-to-LogDate boundary independently of the OAuth
client's provider-facing proof. Verifiers are excluded from diagnostic
strings and must be stored with the original backend before browser launch.

## Durable enrollment and passkey integration

The client keeps a single enrollment in secure storage. Its identity step
selects either account creation or existing-account passkey confirmation.
Advancing to registration retains the exact profile, original owner, options,
and replacement grant; advancing to authentication retains the options and
replacement grant. Each stage has a five-minute local lifetime, and the
server remains authoritative. Advances compare the complete expected state,
so an old response cannot overwrite a newer stage or attempt. A fresh browser
controller must also verify its original provider attempt before initially
replacing enrollment state.

`BlueskyPasskeyCeremony` adapts these stages to `PasskeyRegistrationCoordinator`.
It persists replacement grants before returning options to the platform,
reuses stored options on restart, and refuses changed profiles, challenges,
tokens, origins, and stale stages. Both the stored enrollment and the concrete
API client must name the same backend. Only public WebAuthn credential fields
are sent; PRF unlock material stays with the existing coordinator. External
sessions cannot provide hosted-identity recovery grants.

The existing coordinator continues to enforce canonical ownership, consent
before offline entries are adopted, local account-key unlock, guarded session
installation, and platform bookkeeping. Backend, owner, and authorization are
rechecked around the passkey ceremony. Enrollment persistence alone does not
install a session. If a response is lost after the server consumes a one-use
completion, a fresh sign-in may be needed; journal/device keys must remain
intact throughout recovery.

The native Apple client also needs sealed personal-space sync before external
accounts can be enabled. Its legacy sync path is incompatible with external
accounts. Reuse the existing LDKE1 identity-plus-media key envelope: personal
space sealing keys derive from the same 32-byte identity key through the
shared space protocol. This work must follow the existing cross-platform
conformance vectors and preserve installed keys and journal data; it does not
require inventing another root secret or changing the envelope protocol.

## Account keys before session acceptance

Provider identity and a valid passkey assertion do not prove that this device
holds the account's journal keys. Personal-space sign-in must unlock the
account envelope or verify already installed keys for that account before
adopting local ownership or publishing its session. A missing PRF result or
unsupported envelope endpoint requires device approval or recovery. New
account creation may bind the installation's retained keys, but must not mint
a replacement root after a reset or lost-key race.

Personal-space uploads read keys for the captured account and retain their
original session fence across suspending key access. Background provisioning
must not label an arbitrary offline root as an account key, restore a root
without account admission, or infer an empty personal space from legacy sync
endpoints. Legacy accounts retain their existing provisioning rules.

Both the browser proof and every enrollment stage retain the original local
owner as well as the backend. Resumption must compare that owner with the
current installation before exchanging credentials or starting a passkey
ceremony. Account details cannot change the owner of a saved registration.

An installation may already contain an offline journal encrypted with a
different root. Keeping those keys and rows intact is mandatory. Existing
owner-row adoption and key recovery helpers do not together implement that
transition: recovery replaces the active identity, and owner adoption does
not preserve a second identity's media. Before enabling existing-account
sign-in for this case, implement a durable preservation and cutover process
that verifies the incoming account keys, retains the original root and media,
and resumes safely after interruption. A temporary refusal protects the
journal but does not satisfy the complete sign-in experience.

## App flow and browser resumption

`BlueskySignInController` owns the app flow as a single DI instance. Its public
repository exposes stages and bounded failure categories; verifier and
continuation values stay in secure storage. Starting sign-in saves the proof,
server, and original owner before returning the browser destination. Returning
links must match the supported fixed URL and a saved attempt. The controller
rechecks the original context after exchange and verifies the proof is still
current before writing enrollment. A durable enrollment takes precedence over
a duplicate return, so an app restart does not spend its browser proof twice.

Restoring the app shows the saved stage and registration details without
launching a passkey prompt. The person explicitly creates or confirms their
LogDate passkey; the concrete repository delegates that action to the existing
guarded coordinator. The coordinator independently checks the ceremony's
original owner before requesting a challenge. Local-data consent and device
approval are separate states. A temporary transport error or coroutine
cancellation preserves resumable proof. Explicit Cancel interrupts the active
operation before clearing saved state. Each request pipeline is closed after
use while its injected transport retains ownership of the engine.

Flow serialization is local to the single controller instance. DI must not
construct a controller per screen or browser callback. The secure stores still
compare saved stages and attempt identifiers around asynchronous responses;
that persistence is required even when no competing screen is present.

Provider cancellation is terminal for its matching browser attempt; it must
not restore as an authorization still awaiting a browser. Offline-data consent
is checked before starting authentication, then checked again against the
returned account. If key approval or a late consent check stops acceptance
after the server has completed verification, discard the consumed enrollment
and mark the next action as requiring new sign-in. That grant cannot be
reused after approval. Device approval still needs its own trusted peer or
recovery route; this state alone does not install keys or provide a complete
recovery experience.

Before sending a one-use passkey completion, persist a completion-started
marker by comparing the exact saved registration/authentication stage. A
second completion cannot claim it again. The marker survives process death
and is not restored as a passkey prompt. If the response, key installation,
or app execution is interrupted, fresh authorization is required. Marking
before sending deliberately also requires fresh authorization when the
request never reaches the server; avoiding a replay of an ambiguously
consumed grant takes precedence over retrying that completion.

## Availability and dependency wiring

The data layer registers one `BlueskySignInRepository` shared by screens and
browser returns. Platforms without the real passkey account repository expose
an unavailable implementation without resolving secure storage or transport.
The real repository also checks current local passkey support before starting
or continuing the flow. Its HTTP client uses the injected transport while
owning an isolated request pipeline.

`BLUESKY_SIGN_IN` defaults off. A client also requires its current server's
`blueskySignInV1`, `personalSpaceV1`, and `encryptedAccountKeysV1` protocol
features, passkey support, and matching HTTPS server/API origins. A cached
descriptor for another backend cannot enable the flow. Availability must react
to backend, descriptor, feature preference, and platform capability changes.

The server advertises `blueskySignInV1` only when its confidential OAuth runtime
is present and both private-journal services are enabled. Enabling
`LOGDATE_BLUESKY_SIGN_IN_ENABLED` also requires
`LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED=true` and
`LOGDATE_PERSONAL_SPACE_ENABLED=true`; startup refuses an incomplete setup.
These checks do not publish a DID or authorize a hosted identity operation.
The feature remains gated until native journal, key-transition and sign-in
acceptance is complete.

## Reuse the existing account setup experience

Bluesky supplies verified identity and a bound account-creation submission. It
does not introduce another sign-up flow or a separate passkey setup screen.
Use `CloudAccountOnboardingScreen` and its existing name, username and account
confirmation steps. `CloudAccountCreationOverride` changes the submission and
its busy/error state while ordinary signup keeps its existing use case. A
saved registration locks editing through the shared confirmation component.
Provider-only screens cover the handle, browser return and recovery states;
account creation delegates to the existing setup content.
