# AT Protocol identity custody

Every LogDate account can have an identity in the AT Protocol, the open network that other apps
such as Bluesky also use. An identity in that network is controlled by cryptographic keys, and
someone has to hold them. For a hosted LogDate account that someone is the LogDate server: it
creates the account's signing key, keeps it encrypted, and signs with it on the person's behalf.
This page explains what that arrangement means, why the credentials around it are kept separate,
which kind of identifier an account gets and why, and what happens to a signing key from the moment
it is created to the moment it is replaced.

Three related pages go further on neighbouring topics. How another app signs in with a LogDate
identity is in [AT Protocol OAuth sign-in](../reference/atproto-oauth-sign-in.md). The key a person
holds for themselves, which can overrule the server, is in
[recovery keys](recovery-keys.md). How LogDate encrypts journal content, which is a separate system
with separate keys, is in [encryption](encryption.md).

## What an identity is made of

Suppose Alice has a LogDate account. Her AT Protocol identity has three parts.

- A **handle**, such as `alice.logdate.app`. It is readable and it can change.
- A **DID** (decentralized identifier), a permanent identifier such as
  `did:plc:abcdefghijklmnopqrstuvwx`. Everything in the network that refers to Alice refers to her
  DID, so it has to stay the same when her handle changes. The word after `did:` is the DID method,
  which says how the identifier is looked up. [Which kind of DID an account
  gets](#which-kind-of-did-an-account-gets) describes the two methods the AT Protocol accepts.
- A **signing key**, a key pair. The public half is published in her DID document, the small
  public record that says which key speaks for the DID and which server hosts its data. The private
  half signs her data.

The data lives in a repository, a signed collection of records hosted by a server the protocol
calls a personal data server, or PDS. Each change to a repository produces a commit, and each commit
is signed with the private half of the signing key. Anyone can fetch Alice's DID document, read the
public key, and check that a commit really came from her identity, without asking LogDate.

The private key therefore decides who can speak as Alice. Whoever holds it is the custodian of her
identity.

### What a DID document contains

A DID document is a small JSON record. The one LogDate builds for an account, in `documentFor` in
[`AtprotoIdentityService.kt`](../../server/src/main/kotlin/app/logdate/server/identity/AtprotoIdentityService.kt),
has five fields.

```json
{
  "@context": ["https://www.w3.org/ns/did/v1", "https://w3id.org/security/multikey/v1"],
  "id": "did:plc:abcdefghijklmnopqrstuvwx",
  "alsoKnownAs": ["at://alice.logdate.app"],
  "verificationMethod": [
    {
      "id": "did:plc:abcdefghijklmnopqrstuvwx#atproto",
      "type": "Multikey",
      "controller": "did:plc:abcdefghijklmnopqrstuvwx",
      "publicKeyMultibase": "zQ3sh…"
    }
  ],
  "service": [
    {
      "id": "#atproto_pds",
      "type": "AtprotoPersonalDataServer",
      "serviceEndpoint": "https://logdate.app"
    }
  ]
}
```

- `id` is the DID the document describes.
- `alsoKnownAs` holds the handle, written as `at://` followed by the handle. A handle is trusted
  only when it points at a DID and that DID's document points back at the handle here.
- `verificationMethod` holds exactly one entry, whose `id` is the DID followed by `#atproto`. The
  AT Protocol looks for that name to find the key that signs the repository. Its
  `publicKeyMultibase` is the public half of the account's active signing key.
- `service` holds exactly one entry, named `#atproto_pds`, whose `serviceEndpoint` is the address
  of the server that hosts the repository. The address comes from the deployment setting
  `ATPROTO_PDS_SERVICE_URL`.

The server also has an identity of its own, and its document is smaller. It has the `id` and the
same `#atproto_pds` service entry, and it lists no key and no handle.

For a `did:web` account the server serves this document itself. For a `did:plc` account the public
copy is the one the PLC directory builds from the identity's operations, and the server's copy is
what it returns alongside a repository description.

## The server as custodian

LogDate generates Alice's signing key on the server, stores it there, and uses it there. The server
is the custodian: it holds the key **on Alice's behalf**, and it does not own her identity.

The key lives on the server because of when signing happens. A repository commit is signed at the
moment a record is written, and writes arrive from any of Alice's devices at any time, including
from devices that hold no key material at all. The server is the one place that is always present
when a write lands. A person also should not lose their identity because they lost a phone. Hosted
AT Protocol servers generally work this way for the same reasons.

What the server does with the key is narrow.

- It signs repository commits for writes made through Alice's signed-in sessions. The signer,
  [`HostedRepoCommitSigner.kt`](../../server/src/main/kotlin/app/logdate/server/atproto/HostedRepoCommitSigner.kt),
  fails the write when the account or its key cannot be found. For an account's own repository it
  never stores a commit with something else in place of a signature, and it never invents a key the
  DID document does not list.
- It signs the operations that define and update a `did:plc` identity, described below.
- It publishes the public half, and only the public half, in the DID document.

The signer has one exception, and it involves no account's key. Sync storage for an account that has
no DID of its own is kept under a synthetic repository identifier, of the form
`did:web:sync-<account id>.logdate.internal`. No account owns that identifier, no DID document names
it, and its hostname never resolves, so there is no key to sign with. Commits there carry a plain
digest of their contents in place of a signature. Such a repository can never be published, so the
digest is never presented to anyone as a signature.

Custody is a relationship of trust, and it is worth being exact about its limits. Nothing
cryptographic stops a custodian from signing: holding the key is what custody means. What the design
adds are ways for the person to stop depending on that trust.

- **Export.** `POST /api/v1/identity/signing-key/export` returns the active key encrypted under a
  passphrase the caller supplies, so a person can take the key elsewhere.
- **A recovery key.** A key derived from the person's recovery phrase, which only they hold, can be
  listed as the first key allowed to change their `did:plc` identity, ahead of the server's key.
  Its purpose is to let the person replace the server's key without the server's agreement. That
  holds only once the identity is in the public PLC directory, where the history can no longer be
  rewritten. While an identity is unpublished, the server's own log is the identity's whole
  history, every change goes through the server, and a server operator could rewrite that log, so
  the recovery key does not overrule the server. Even for a published identity, the app cannot send
  an operation to the directory without the server. The server side of the feature is gated by
  `ATPROTO_RECOVERY_KEYS_ENABLED`, which is off by default. See
  [the protection is incomplete while an identity is unpublished](recovery-keys.md#the-protection-is-incomplete-while-an-identity-is-unpublished)
  in the recovery keys explanation.
- **Deletion.** Deleting an account deletes its signing keys with it and revokes every grant the
  account gave to other apps.

The obligations that follow from custody are plain. The server must serve the DID document
accurately and never substitute a key. It must keep the history of keys it has used. It must not
make an identity public without its owner's choice, which is the subject of
[Private until its owner publishes it](#private-until-its-owner-publishes-it).

## Four credentials, four questions

A common mistake is to treat signing in and being someone as the same thing. In a centralized
service they are: the password that signs a person in is also the only proof of who they are. In an
open network they have to be separate, because identity must survive a change of server and a
sign-in credential must not.

LogDate keeps four credentials apart. Each lives in a different place and answers a different
question.

| Credential      | Where it lives                          | The question it answers                             |
| --------------- | --------------------------------------- | --------------------------------------------------- |
| Passkey         | The person's device or password manager | Is this person present right now?                   |
| Access token    | Issued by the server, held by an app    | Was this app allowed to act?                        |
| Signing key     | The server, encrypted                   | Did this data come from this identity?              |
| Recovery phrase | Only with the person                    | Who has the last word when everything else is lost? |

A **passkey** authenticates a person to LogDate. In the passkey standard, the service a passkey
signs in to is called the relying party, and every passkey is registered under that service's
domain name, its relying-party ID. A LogDate passkey is bound to LogDate's relying-party ID, so it
works only on LogDate's own pages and apps, and it cannot be moved to another service. It is never
published in a DID document. If Alice moved her identity to a different server, she would register
new passkeys there and her DID would not change.

An **access token** records that a sign-in happened and what it allows. The LogDate apps hold
LogDate's own bearer tokens. Other apps hold OAuth tokens whose subject is the DID and which are
bound to a key the app holds. Both are short-lived and both are worthless as proof of identity to
anyone but the LogDate server.

The **signing key** proves provenance. It is the only one of the four that signs data the rest of
the network reads, and its public half is what the DID document publishes for that purpose. It is
deliberately not a passkey. A passkey signs sign-in challenges for one
website. It cannot sign repository commits, cannot be listed in a DID document, and cannot follow
an identity to a new server.

The **recovery phrase** is twelve words that only the person holds. LogDate never stores it and the
server never receives it. The key that encrypts the journal is derived from it on the device, and so
is a separate recovery key for the `did:plc` identity. The two derivations start from different
branches of the same phrase, so holding the journal key does not give anyone the identity recovery
key. The private half of the recovery key never leaves the device. The server receives its public
half and the operations it signs. The public half is listed in the identity's operation history,
so once an identity is published it is visible to the network there, as a key allowed to change
the identity and not as a key that signs data.

One more secret sits beside these four and belongs to the deployment, not to any person: the
key-encryption key that wraps stored signing keys. It is described under
[Wrapping](#wrapping-with-the-key-encryption-key).

Keeping these apart is what lets each one change without disturbing the others. A person can add or
remove a passkey without touching their identity. The server can rotate a signing key without
signing anyone out. A deployment can replace its key-encryption key without changing any published
key.

## Account IDs and DIDs

Inside LogDate, an account is identified by a random UUID, `accounts.id`. The DID is stored beside
it in `accounts.did`, and it is unique whenever it is present.

The UUID stays the internal key and the DID is the external one. Every internal query, every
foreign key and the whole journal sync API use the UUID. The DID appears wherever the outside world
is involved: it is the subject of an OAuth access token, the `repo` an AT Protocol method names, and
the value a handle resolves to. When a request arrives carrying a DID, the server looks the account
up by DID once and works with the UUID from then on.

The rule exists for two reasons. An account can exist before it has any DID, because identities are
created on demand, so the DID cannot be the primary key. And an identity can change, for example
when a signing key is replaced, while every row that belongs to the account keeps pointing at the
same UUID.

## Which kind of DID an account gets

The AT Protocol accepts two DID methods, and they differ in what the identifier depends on.

The first is `did:plc`. PLC stands for Public Ledger of Credentials. A `did:plc` identity is a chain
of signed operations: the first one creates the identity, and each later one changes its handle,
keys or hosting server and must be signed by a key the previous operation allowed. The **PLC
directory** is the public registry that accepts those operations, keeps them permanently, and
answers anyone who asks what a `did:plc` identifier currently resolves to. LogDate uses the
directory at `https://plc.directory` unless `ATPROTO_PLC_DIRECTORY_URL` names another.

The second is `did:web`, where the identifier is a hostname and the DID document is a file that
hostname serves.

|                                      | `did:plc`                                             | `did:web`                                          |
| ------------------------------------ | ----------------------------------------------------- | -------------------------------------------------- |
| Looks like                           | `did:plc:abcdefghijklmnopqrstuvwx`                    | `did:web:alice.example.com`                        |
| Resolved by                          | Asking the PLC directory                              | Fetching `/.well-known/did.json` from the hostname |
| Depends on                           | The directory service                                 | Control of the hostname                            |
| Survives a handle or domain change   | Yes                                                   | No, the hostname is the identifier                 |
| Key change                           | A signed operation appended to the identity's history | The served document changes                        |
| Recovery key that overrules the host | Supported                                             | Not available                                      |
| Fits                                 | Many accounts on one server                           | One identity on its own domain                     |

**Hosted accounts get `did:plc` by default.** A `did:plc` identifier is derived from the hash of the
identity's first signed operation, so it names nothing about LogDate. Alice can change her handle,
and in principle her server, and keep her DID. Changes are made by appending signed operations, and
the operation lists which keys may sign the next one, which is what makes a person-held recovery
key possible.

**`did:web` is for deployments where the hostname is the identity.** Setting
`ATPROTO_HOSTED_DID_METHOD=web` makes new accounts `did:web:<handle>`, served by the server itself
with no directory involved. That suits a dedicated or self-hosted server with one identity on a
domain its owner controls. It is a poor fit for many accounts on a shared domain, because each
identity is then tied to a hostname the host controls and cannot leave with the person. The server's
own identity is always `did:web:<handle domain>`.

**Path-based `did:web` is refused everywhere.** The general DID specification allows forms such as
`did:web:example.com:users:alice`, which would let one hostname serve many identities. The AT
Protocol does not accept them, so an identity built that way would be invalid to every other app.
[`AtprotoDid.kt`](../../shared/atproto-identity/src/commonMain/kotlin/studio/hypertext/atproto/identity/AtprotoDid.kt)
rejects any `did:web` whose identifier contains a path, and any method other than `plc` and `web`.

Changing `ATPROTO_HOSTED_DID_METHOD` affects only identities created afterwards. Existing accounts
keep the DID they have.

## Private until its owner publishes it

Having an identity and having a public identity are different states. LogDate creates the handle,
DID and signing key so that the account is ready, and it keeps them to itself. **An identity
becomes public only when its owner explicitly chooses to publish it. Publishing is never the
default, and a deployment setting must never publish on a person's behalf.**

The reason is that publishing cannot be undone. An operation accepted by the public PLC directory
stays in the identity's history permanently, and a published identity invites every other service
to resolve the handle and read the account's public repository. That is a decision about a person's
presence on an open network, and only that person can make it.

The server records the choice in `accounts.public_identity_did`.

- The column holds the exact DID the owner made public. Empty means private.
- Creating an identity, or sending an operation to a directory, does not establish consent.
- Ordinary account saves cannot grant or revoke it, and it is written only while the stored DID
  still equals the DID being consented to. Consent given for one DID never carries over to another.

No server route or app action sets `public_identity_did`, so every identity is private and the
anonymous lookups and `PublicRepoGate` refuse every account.

Every anonymous lookup checks it. `/.well-known/atproto-did` and `/.well-known/did.json` answer only
for an account whose `public_identity_did` equals its DID, and the gate that decides whether
strangers may read a repository,
[`PublicRepoGate.kt`](../../server/src/main/kotlin/app/logdate/server/atproto/firehose/PublicRepoGate.kt),
requires it too. A `did:web` that would technically resolve, or a server setting that is switched
on, is not consent.

The deployment setting `ATPROTO_PLC_PUBLISH_ENABLED` is a different thing. It decides whether the
server is able to send operations to the PLC directory at all. It is a capability of the deployment,
and it is off by default and off in production. The code that uses that capability does not check
the owner's choice, so the setting must stay off. The reasons a published operation is permanent
are in [identity publishing](identity-publishing.md), and the operator command and its
settings are in the [identity publishing reference](../reference/identity-publishing.md).

## The life of a signing key

### Generation

An account gets its identity the first time something needs it: at sign-up, at sign-in, when
another app signs in through OAuth, or on a first AT Protocol request. The work is done by
`ensureIdentity` in
[`AtprotoIdentityService.kt`](../../server/src/main/kotlin/app/logdate/server/identity/AtprotoIdentityService.kt).
It is idempotent, so an account that already has a handle, a DID and a key is returned unchanged.
The server does not create identities for every account when it starts, and an anonymous handle
lookup never creates one. An operator can run a one-shot command, `--backfill-identities`, that
gives an identity to each active account still missing one, in the same way sign-in would. It
creates identities and makes none of them public. With `--dry-run` it only counts the accounts. On
a server that sends operations to the PLC directory it refuses to run unless the operator also
passes `--allow-plc-publishing`, because every identity it created there would be sent to the
directory. Because that publishing does not check the owner's choice, the combination must not be
used.

Two requests for the same account must not create two identities. Within one server instance,
provisioning runs under a lock chosen by the account's ID and starts from the stored account, read
again inside the lock. The server keeps a fixed set of 64 such locks and picks one from the ID, so
two unrelated accounts occasionally wait on the same lock, which costs a short delay and nothing
else. The result is written with a conditional update that succeeds only while the account's
identity is unchanged, so a DID that another instance assigned first is kept and a deleted account
is never written back.

[`SigningKeyService.kt`](../../server/src/main/kotlin/app/logdate/server/identity/SigningKeyService.kt)
generates the key pair. New keys use the K-256 curve (secp256k1). The AT Protocol accepts K-256 and
P-256 signing keys, and accounts whose keys were created as P-256 keep working. The public half is
encoded as a multibase multikey, the text form a DID document carries, and the private half is
serialized as PKCS#8 bytes.

For a `did:plc` account the new key immediately signs the genesis operation, which names the
handle, the server that hosts the data, the signing key, and the rotation keys allowed to sign later
operations. The server lists the account's own signing key as a rotation key, after the person's
recovery key when one is registered. The DID is the hash of that signed operation, and the operation
is stored in the server's own log, `hosted_plc_operations`.

The key that signs an account's repository commits is therefore also the server's rotation key for
that identity. This is a trade-off. A leak of that one key, which the server uses on every write,
lets its holder rewrite the identity as well as the repository.
The person's recovery key, listed first, is what lets them overrule a change made with it once the
identity is published.

### Wrapping with the key-encryption key

A private key is never written to the database in the clear. It is wrapped, meaning encrypted under
another key, by
[`AtRestKeyCipher.kt`](../../server/src/main/kotlin/app/logdate/server/identity/AtRestKeyCipher.kt).

- The wrapping key is derived from a deployment secret, `ATPROTO_SIGNING_KEY_KEK`, called the
  key-encryption key. The server takes the SHA-256 of the secret's bytes and uses the result as an
  AES-256 key.
- Each private key is encrypted with AES-GCM using a fresh random 12-byte IV and a 128-bit
  authentication tag.
- The stored value is the Base64 of the IV followed by the ciphertext and tag.

The secret is hashed once and not stretched, so it has to be long and random. Production refuses to
start without it and accepts nothing shorter than 32 characters. Development and tests fall back to
a value published in the source code, which only ever protects keys on a developer's machine.

The key-encryption key has one job. It is separate from the secret that signs LogDate session
tokens, so that rotating a session secret can never make every stored signing key unreadable. The
same cipher also wraps the key that signs OAuth access tokens.

Wrapping protects against one specific loss: a copy of the database, such as a backup or a
snapshot, is not enough to sign as anyone. It does not protect against someone who has both the
database and the deployment secret. That is the boundary of custody, and it is why the secret lives
in a secret manager and never in the repository.

### Storage

Keys are kept in the `signing_keys` table.

| Column                  | Holds                                                                  |
| ----------------------- | ---------------------------------------------------------------------- |
| `id`                    | The row's own UUID                                                     |
| `account_id`            | The account the key belongs to. Deleting the account deletes its keys. |
| `purpose`               | `atproto`                                                              |
| `algorithm`             | `K-256` or `P-256`                                                     |
| `public_key_multibase`  | The public half, as published                                          |
| `private_key_encrypted` | The wrapped private half                                               |
| `created_at`            | When the key was created                                               |
| `revoked_at`            | When the key stopped being active. Empty for the active key.           |

An account has one active key: the row whose `revoked_at` is empty. The account row also carries a
copy of the active public key in `accounts.signing_key_public`, which is what the DID document is
built from and what commit signatures are checked against.

Revoked rows are kept. A key that once signed commits may be needed to understand the account's
history, so replacing a key marks the old row and never deletes it. Revoked keys are never decrypted
again.

### What binds a stored key to its row

The wrapped value authenticates its own contents: a ciphertext that has been altered, or that was
wrapped under a different key-encryption key, fails to decrypt. The encryption carries no associated
data, though, so the ciphertext does not itself name the account or the row it belongs to. A wrapped
key is tied to its account by the row it sits in, through `account_id`, and by the public key stored
next to it.

Three things rest on that row-level binding.

- A private key that ended up under the wrong account would produce signatures that fail against
  the public key that account publishes, so the mismatch would show as invalid commits, not as
  silently valid ones.
- When a deployment moves to a new key-encryption key, each stored value is replaced only while it
  still equals the value that was read. Two instances re-encrypting at the same moment cannot
  overwrite each other, and a value nobody can read is reported and left untouched.
- The one place a wrapped key travels outside its row is the two-step recovery flow, where the
  server hands a prepared key to the device and gets it back. That token is sealed with the
  key-encryption key and does name its account, the operation it was prepared on, and a digest of
  the operation it prepares. It is refused for any other account and expires after 30 minutes.

### Use

The private key is unwrapped in memory at the moment something is signed, and is not cached in the
clear. It signs repository commits and `did:plc` operations. Verification never needs it: commits
are checked against the public key on the account.

### Rotation

Rotation replaces the active key with a new one. How it happens depends on the DID method, because
the public record that names the key lives in a different place for each.

For a `did:web` account, the server generates a new key, then revokes the old row and inserts the
new one in a single transaction. That transaction matters: a failure can never leave the account
with no active key. Immediately afterwards, in a separate write, the server updates the copy of the
public key on the account. The DID document is built from the account, so it changes as soon as
that second write lands. The two writes are not one transaction. A failure between them would leave the
account publishing the old public key while the new key signs, so new commits would fail
verification, and nothing repairs that state automatically.

For a `did:plc` account, the public record is the identity's operation history, so the order is
stricter.

1. The server prepares a new key without storing it.
2. It builds an update operation naming the new key and signs it with the **current** key, which
   the previous operation lists as allowed to sign.
3. It sends the operation to the PLC directory and records it in its own log.
4. Only then does it activate the new key and update the account.

A failure at any step before the last leaves the old key active and the published identity
unchanged, so the server never signs with a key the directory has not heard of.

This path needs the directory, so it requires `ATPROTO_PLC_PUBLISH_ENABLED`. While that setting is
off, `POST /api/v1/identity/signing-key/rotate` answers `409` for a `did:plc` account. That is the
case in production.

The route takes a LogDate session token and a passphrase, which must not be blank. A successful
rotation answers with the account's DID and handle, the previous public key in `did:key` form, and
an export bundle of the new key encrypted under that passphrase, in the format described next. The
caller therefore leaves a rotation holding a copy of the key that replaced the old one, without a
second request.

### Export and import

`POST /api/v1/identity/signing-key/export` returns the active key in a bundle encrypted under a
passphrase the caller sends. The server derives a 256-bit key from the passphrase with
PBKDF2-HMAC-SHA256, 120,000 iterations and a random 16-byte salt, and encrypts the PKCS#8 bytes
with AES-GCM. The bundle carries the algorithm, the public key in both multibase and `did:key`
form, the encrypted private key, the salt, the IV and the derivation parameters, alongside the
account's DID and handle. The route requires a LogDate session token.

`POST /api/v1/identity/signing-key/import` accepts such a bundle. It is conservative so that a
published identity can never drift from the key the server holds.

- When the bundle's public key is the one the account already publishes, the server restores the
  private key material and changes nothing public.
- When it is a different key and the account is `did:web`, the server replaces the active key and
  the served document.
- When it is a different key and the account is `did:plc`, the server must publish the matching
  operation first, so the import is refused with `409` while directory publishing is off.

No screen in the LogDate apps calls export, rotation or import.

### Changing the key-encryption key

A deployment can move to a new `ATPROTO_SIGNING_KEY_KEK` without losing access to any key. A second
secret, `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY`, can decrypt stored keys but never encrypts. While it
is set, each instance re-encrypts every active key under the current secret once at startup. The
order of deploys, the log codes that report the outcome and the rule for retiring the old secret are
in [`server/docs/environment-variables.md`](../../server/docs/environment-variables.md).
