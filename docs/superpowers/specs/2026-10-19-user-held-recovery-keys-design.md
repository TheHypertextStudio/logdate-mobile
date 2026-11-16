# User-held recovery keys

Part of the Identity safeguards project in the LogDate on the AT Protocol initiative. The umbrella
design is [AT Protocol interoperability](../../explanation/atproto-interoperability.md),
which lists "people can hold their own recovery key on every platform" as the third safeguard
before any identity is published.

## The problem

Every LogDate account has an AT Protocol identity: a `did:plc` identifier and the
`alice.logdate.app` name that other people and apps know the account by. A `did:plc` identity is a
chain of signed operations. Each operation lists **rotation keys**, and only those keys can sign
the next operation. Today the only rotation key is the server's own signing key. So the server can
change anyone's identity by itself, and nobody can recover or move an identity without it.

A recovery key fixes that. It is a second rotation key that only the person holds. PLC ranks
rotation keys by their position in the list: a key earlier in the list can undo an operation signed
by a later key for 72 hours after it was made. Putting the person's key **first**, ahead of the
server's key, means the person can overrule the server once the identity is published.

## Decisions

### Key type: P-256, derived from the recovery phrase

The recovery key is a P-256 (`secp256r1`) key. PLC accepts P-256 and K-256 rotation keys, and P-256
is the curve every platform supports natively: CryptoKit on iOS, the JDK and BouncyCastle on Android
and desktop.

The key is derived from the person's existing 12-word recovery phrase, but on its own branch of the
phrase's seed, separate from the journal key:

1. BIP-39 turns the phrase into its 64-byte seed (PBKDF2-HMAC-SHA512).
2. HKDF-SHA256 extracts a key from the **whole** seed with an all-zero salt.
3. HKDF-Expand with the info `logdate.atproto.plc.recovery.v1:<attempt>` gives a 32-byte candidate,
   starting at attempt 0.
4. The first candidate that is a valid P-256 scalar (between 1 and the curve order) is the key.
   In practice attempt 0 always is; the loop exists so the derivation can never fail on an
   unlucky value.

The journal encryption key is the first 32 bytes of the same seed, and it travels: device transfer
and the account key envelope both move it. An earlier version derived the recovery key from the
journal key, so anything holding the journal key could derive the recovery key too. Deriving from
the whole seed with its own label means only the phrase produces the recovery key. No recovery key
was registered in production before this change, so nothing had to migrate.

The seed, the intermediate HKDF key, rejected candidates and the private key are zeroed as soon as
they are no longer needed.

The public half is published as a `did:key` (`did:key:zDn...`). Signatures are raw 64-byte `r || s`
values with a low `s`, base64url without padding, which is the form PLC requires.

Why the phrase rather than a new secret:

- **Nothing new to write down.** People already save and confirm their phrase. A second secret
  would be a second thing to lose, and most people would lose one of them.
- **Same recovery story everywhere.** Anyone who can restore the journal on a new device can also
  recover the identity there.
- **Deterministic.** Every device that holds the phrase derives the same key, so there is no key
  file to sync or back up.

The trade-off is that the phrase now protects the identity as well as the journal. The setup screen
says so plainly: anyone with the 12 words can use the recovery key.

### Where the key lives

Nowhere, at rest. The private key is derived in memory from the stored phrase each time it is
needed (to show its public half or to sign) and then wiped. Only the `did:key` and signatures
cross the network. The server never sees the phrase or the private key.

The phrase itself stays where it already is: in the device's secure storage and, where the platform
offers it, the device's own backup (Block Store on Android, iCloud Keychain on iOS).

### What the person writes down

Their 12-word recovery phrase, if they have not already. Setup links to the existing Recovery phrase
screen: it shows the words behind the screen lock and then asks the person to type them back. The
recovery key can only be turned on once the phrase is saved on this device **and** confirmed.

The Recovery key page never opens phrase entry. Entering a phrase replaces the one that unlocks this
device's journal, so when the phrase cannot be read, the recovery key cannot be derived from it, or
the confirmation cannot be checked, the page says which of these failed and offers only Try again.

### A fresh passkey confirmation for every change

Registering a key, replacing a registered key and replacing the server's key each need a fresh
passkey confirmation (a step-up), as well as the session. Someone holding only a stolen access token
cannot make any of them.

1. `POST /api/v1/identity/recovery-key/step-up` returns WebAuthn request options for the account's
   own passkeys. The challenge is its own kind (`step_up`), bound to the account, single-use, and
   valid for five minutes. It cannot be spent as a sign-in challenge, and a sign-in challenge cannot
   be spent as a step-up.
2. The app asks the platform for an assertion and sends it as `stepUp` with the change.
3. The server checks the assertion with the same WebAuthn verification sign-in uses, and that the
   passkey belongs to the account. A missing confirmation answers `403 STEP_UP_REQUIRED`, a rejected
   one `403 STEP_UP_FAILED`. An account with no passkey answers `409 PASSKEY_REQUIRED` when the
   step-up begins.

A device that cannot show a passkey prompt (the desktop app today) never shows the Recovery key row.

### Registering the key

`POST /api/v1/identity/plc/recovery-key` with `{ "recoveryDidKey": "did:key:zDn...", "stepUp": ... }`.

The server builds a PLC update operation on top of the identity's latest operation:

- `rotationKeys` = `[recoveryKey, serverKey]`, with the person's key first;
- the signing key, handle and service endpoint stay the same;
- the server signs with its current key, and first checks that this key is a rotation key of the
  previous operation (otherwise `409 SERVER_KEY_NOT_ON_IDENTITY`).

Every operation the server builds afterwards (signing-key rotation, recovery) keeps the recovery key
first. Genesis operations created with a recovery key put it first too.

Registering the same key again does nothing. Registering a **different** key when one is already
set is refused with `409 RECOVERY_KEY_ALREADY_SET`; replacing it is its own request.

### Replacing a lost recovery key

`POST /api/v1/identity/plc/recovery-key/replace`, with the same body, is for someone who no longer
has the phrase the registered key came from. The page offers it when the registered key does not
match this device's confirmed phrase.

The old key is lost, so it cannot sign. The server signs the change with its own key, which is still
a rotation key, and lists the new key first. Once the identity is published, the old recovery key
ranks above the server's key and can undo this change for 72 hours, so a replacement made by someone
else can be reversed by the person who still has the old phrase.

### Recovering: replacing the server's key

The in-app recovery flow replaces the key the server uses for the identity. It is approved by the
recovery key, not the server:

1. `POST /api/v1/identity/recovery-key/recover/prepare`. The server generates a fresh signing key
   and builds an unsigned update operation:
   - `rotationKeys` = `[recoveryKey, newServerKey]`;
   - the `atproto` verification method is the new key;
   - `prev` is the latest operation.

   It returns the unsigned operation, the bytes to sign, and an opaque `preparedKey` token: the new
   private key and a SHA-256 digest of the bytes to sign, sealed with the server's key-encryption
   key and bound to the account. The server stores nothing between the two calls.

2. The app derives the recovery key from the phrase on this device and checks that it matches the
   one registered on the identity. It then **decodes the operation and refuses to sign** unless it is
   exactly the change asked for:
   - its canonical encoding is exactly the bytes it is asked to sign;
   - `prev` is the head of the identity's history, read from the operation log;
   - `rotationKeys` is this device's key, then the new server key, and nothing else;
   - the verification method is the new server key;
   - the handle and service are unchanged from the head, and the handle is the identity's.

   The recovery key outranks the server's key, and an operation it signs cannot be undone by a lower
   key, so a compromised or buggy server must not be able to get it to sign anything else.

3. The person confirms with a passkey, and the app sends the token, the signature and the step-up to
   `POST /api/v1/identity/recovery-key/recover/complete`. The server:
   - opens the token and checks that it belongs to this account;
   - rebuilds the operation and checks that `prev` is still the latest operation and that its digest
     matches the token (otherwise `409 IDENTITY_CHANGED`, for example after a handle change);
   - checks that the registered recovery key is a rotation key of the latest operation;
   - verifies the low-S signature against the registered recovery key
     (otherwise `400 RECOVERY_SIGNATURE_INVALID`);
   - appends the operation and makes the new key active.

Afterwards the old server key has no authority over the identity at all.

The screen says what will happen before anything is sent:

- this device checks the change and approves it with the recovery phrase, which never leaves the
  device;
- the person confirms with a passkey;
- the key the server uses for the identity stops working and a new one replaces it;
- the recovery key still ranks above the new key;
- the journal, sign-in methods and devices do not change.

### Concurrent requests

Two requests for the same account must not both build on the same operation. That would fork the
history and could leave the account naming one recovery key while the log names another.

- Every change runs under a per-account lock and starts from the account as stored, read again
  inside the lock, so requests on one server instance take turns.
- A unique index on `hosted_plc_operations (did, prev_cid)` refuses a second operation on the same
  head, so two server instances cannot fork the log either. The request that loses answers
  `409 IDENTITY_CHANGED`.
- The account is written with `updateIdentityIfUnchanged`, a conditional update. It never brings
  back an account deleted in the meantime and never overwrites an identity change made in between.

Each change appends its operation first, then activates the new server key (revoking the old one and
storing the new one in one transaction), then writes the account. If a later step fails, sending the
same request again finds the operation already at the head of the log and finishes the remaining
steps instead of appending again.

### Without publishing to the PLC directory

Publishing identities to the public PLC directory is off in production and stays off. Recovery keys
work without it:

- The server's **hosted operation log** (`hosted_plc_operations`) is the identity's history.
  When publishing is off, the latest operation is found by following the chain from the genesis
  operation through each `prev`. When publishing is on, it is the latest entry in the directory's
  audit log, as before.
- Each stored operation records its CID, computed locally from its DAG-CBOR encoding
  (`PlcEncoding.operationCid`). It is the same CID the directory would assign. Older rows without a
  CID have it computed from their stored JSON.
- The log only takes operations the directory would accept: low-S signatures from a rotation key of
  the previous operation.
- Nothing is sent to the directory unless `ATPROTO_PLC_PUBLISH_ENABLED=true`. A test with a recording
  directory client runs every flow with publishing off and checks that it receives no requests.

The protection becomes complete once identities are published and a client can submit an operation
to the directory without the server. Until then every change goes through the server, the hosted log
is the only history, and the server could rewrite it. The copy says so, and says that undoing a
change is not available yet and, when it is, works only within 72 hours.

### Gating

**Server.** `ATPROTO_RECOVERY_KEYS_ENABLED` is off by default. The server supports recovery keys only
when it hosts `did:plc` identities and this flag is on; turning on PLC publishing does not turn them
on. When it does, it advertises the protocol feature `atprotoRecoveryKeysV1` in `/api/v1/server/info`.
When it does not, the recovery-key endpoints answer `409 RECOVERY_KEYS_UNAVAILABLE`, before any
step-up is spent.

**Client.** The `IDENTITY_RECOVERY_KEY` feature flag is off by default and can be turned on from
Labs. The Account screen shows the **Recovery key** row only when all of these are true:

- the flag is on;
- the person is signed in;
- the connected server advertises `atprotoRecoveryKeysV1`;
- this device can show a passkey prompt.

If the page is opened another way while any of these is false, for example from a restored back
stack, it shows that recovery keys are not available here and offers no action.

### Platforms

| Platform | Derivation and signing                                                     | Reachable today                                                                            |
| -------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| Android  | BouncyCastle (`JvmPlcRecoveryKeySupport`)                                  | Yes, when signed in                                                                        |
| iOS      | `cryptography-kotlin` over CryptoKit (`CryptographyPlcRecoveryKeySupport`) | Yes, when signed in                                                                        |
| Desktop  | BouncyCastle (`JvmPlcRecoveryKeySupport`)                                  | Built and wired; the desktop app cannot show a passkey prompt, so the row is never offered |

### Copy

The copy never says DID, PLC, rotation key, did:key or operation, and never "we". It talks about:

- "your identity" and the person's `@handle`;
- "the server that hosts your account", by its name;
- "your recovery phrase", "your recovery key" and "your passkey".

It only claims what is true while publishing is off: the recovery key ranks above the server's key,
and it can replace the server's key through the server. Every error says what failed, that nothing
was changed, and what to do next.

## Testing

- **Shared crypto:** a fixed P-256 vector (the RFC 6979 A.2.5 key) gives the same `did:key` on every
  platform. Signatures are 64 bytes, low-`s` and verify. A known-answer test runs the real platform
  `CryptoManager` from the standard BIP-39 test phrase to a pinned `did:key` on Android host, desktop
  and iOS; the vector was computed independently, and the intermediate seed matches the published
  BIP-39 vector. `EcKeySupport.hasLowS` tells a signature from its high-S twin.
- **PLC encoding:** `operationCid` matches the CID the public directory assigned to a real genesis
  operation.
- **Server:** registration without publishing appends a correctly chained, correctly ordered
  operation. Re-registering is a no-op, a different key is refused, and replacing a lost key works.
  Two parallel registrations and two parallel recoveries, on one server and on two server instances
  sharing a database, leave one key and one chain. A request that failed part-way finishes when
  repeated. A deleted account is not brought back. A high-S signature, a signer that is not a
  rotation key, a stale token, a changed handle, a wrong signature, a token from another account and
  an unregistered key are each refused with their own code. The step-up is required, single-use and
  bound to the account. Nothing reaches the directory while publishing is off. On PostgreSQL, eight
  concurrent appends on one head store exactly one.
- **Client:** the repository refuses to sign operations that drop the key, add a key, move the
  handle, change the service, skip the latest change, or whose bytes differ from what is shown. It
  sends a step-up with every change and maps every server code. The view model covers every state,
  including unreadable phrases and not being offered. Screenshot tests render each screen state.
