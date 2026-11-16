# User-held recovery keys

Every LogDate account has an AT Protocol identity, and by default the server that hosts the account
is the only party that can change it. A recovery key is a second key, held only by the person, that
ranks above the server's key on that identity. This page explains what the key is for, how a device
derives it from the recovery phrase, what it protects against and what it does not, how it gets
registered without the person doing anything, and why a device refuses to sign anything with it
except one exactly specified change.

Other pages cover neighbouring ground and are not repeated here. The
[identity publishing explanation](identity-publishing.md) describes what an identity is made of and
why publishing one cannot be undone. The
[account key vault reference](../reference/account-key-vault.md) describes how the journal's keys
reach a new device through an encrypted envelope, and the
[Android recovery custody reference](../reference/native-android-recovery-custody.md) describes
where the recovery phrase and the journal identity key are stored on Android. The
[encryption explanation](encryption.md) covers the journal's key hierarchy, and the
[identity custody explanation](atproto-identity-custody.md) covers the server-held signing key that
the recovery key ranks above.

## Who is allowed to change an identity

An account's identity is a `did:plc` identifier: a DID (decentralized identifier) whose history is a
chain of signed operations. The first operation, the genesis operation, names the account's handle,
the server that hosts its data, the key that signs its data, and a list of rotation keys. A rotation
key is a key that is allowed to sign the next operation in the chain. Nothing else can change the
identity, so whoever holds a rotation key controls the handle, the hosting server and every other
key.

The order of the list matters. The PLC directory, the public service that stores these chains, ranks
rotation keys by position. A key that is listed earlier than the key that signed an operation can
replace that operation for 72 hours after the directory accepted it. A key listed first can
therefore overrule every key below it, and nothing can overrule an operation that the first key
signed.

A LogDate server normally creates an identity with a single rotation key, the same key that signs
the account's data, and it holds that key itself. With only that key on the list, the server can
change anyone's identity alone, and nobody can recover or move an identity without the server's
help.

## What the recovery key changes

The recovery key is a rotation key that only the person holds. The server lists it first, ahead of
its own key, so that the person outranks the server on their own identity. Every operation the
server builds afterwards keeps it first; the builder that produces update operations always emits
the rotation keys as the recovery key followed by the server's key, in `buildHostedAtprotoUpdate` in
[`PlcIdentityService.kt`](../../server/src/main/kotlin/app/logdate/server/identity/PlcIdentityService.kt).
An identity created for an account that already has a recovery key recorded lists it first from the
genesis operation onwards.

The key gives the person who holds it two powers.

The first is to replace the server's key with a new one by signing that change themselves. The
server and the app's data layer implement this, as described under
[replacing the server's key](#replacing-the-servers-key-with-the-recovery-key), but no screen in
the app starts it.

The second applies only to an identity that is in the public directory. Because the directory
accepts any operation with a valid signature from a rotation key, the key's holder can in principle
sign an overriding operation and submit it to the directory themselves. That would undo a change
the server made, within the directory's 72-hour window, or move the identity to another host
without the current server's cooperation. LogDate has no screen or tool that submits an operation
to the directory, so the app cannot take this action.

## What it protects against, and what it does not

The design answers five specific threats.

- **A server that changes an identity on its own.** The recovery key ranks above the server's key,
  so a change signed only by the server is one the person can overrule. This is the purpose of the
  key, and it is fully effective only once the identity is published, which is each person's own
  choice and may never happen for a given account; the section on
  [unpublished identities](#the-protection-is-incomplete-while-an-identity-is-unpublished) explains
  the gap.
- **A server that is compromised or wrong and asks the device to sign something else.** The device
  decodes what it is asked to sign and refuses anything but the one expected change. The section on
  [refusing to sign](#why-the-device-refuses-to-sign-anything-else) explains why this check is the
  most important one on the page.
- **A stolen session token.** Registering a key, replacing a key and replacing the server's key each
  need a fresh confirmation with a passkey as well as a signed-in session, so a token alone cannot
  make any of them. A passkey is the sign-in credential that a device holds for the account and
  that the person unlocks with a fingerprint, a face or the screen lock. One operation,
  installing an exported signing key with the recovery key's approval, does not ask for the
  passkey; the section on
  [passkey confirmation](#which-changes-need-a-fresh-passkey-confirmation) says what guards it
  instead.
- **Something that holds the journal key but not the phrase.** The journal's encryption key moves
  between devices, and the recovery key is derived on a separate branch so that it cannot be
  computed from the journal key.
- **Two requests racing each other.** Concurrent changes cannot fork an identity's history or leave
  the account record and the history naming different keys.

It does not protect against someone who has the 12 words. The recovery phrase produces the recovery
key, so anyone who holds the phrase holds the key. The in-app flows also need a session and a
passkey, but a PLC operation needs only a valid signature, so once an identity is published the
phrase alone is enough to sign for it. The phrase protects the identity as well as the journal.

It also does not protect against a compromised device, which can read the stored phrase, and it does
not protect against a server that rewrites history it has never published.

## How the key is derived from the recovery phrase

The recovery key is a P-256 (`secp256r1`) signing key. The PLC directory accepts P-256 and K-256
rotation keys, and P-256 is the curve that every client platform can compute with its own
cryptography library.

A device derives the key from the person's existing 12-word recovery phrase, in
[`PlcRecoveryKeyManager.kt`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/PlcRecoveryKeyManager.kt):

1. BIP-39, the standard that defines the 12-word phrase, turns the phrase into a 64-byte seed with
   PBKDF2-HMAC-SHA512.
2. HKDF-SHA256, a standard way to derive independent keys from one secret, extracts a pseudorandom
   key from the **whole** seed with a salt of 32 zero bytes.
3. One HKDF expansion block with the label `logdate.atproto.plc.recovery.v1:<attempt>` gives a
   32-byte candidate, starting at attempt 0.
4. The first candidate that is a valid P-256 private key, a number of at least 1 and less than the
   curve's order, is the key. Almost every candidate is valid, so attempt 0 is the one used in
   practice. The loop runs up to 32 attempts so that an unlucky value cannot make the derivation
   fail.

The seed, the pseudorandom key, every rejected candidate and the private key itself are overwritten
with zeros as soon as each is no longer needed.

The public half is written as a `did:key`, a self-describing encoding of a public key that starts
`did:key:zDn` for P-256. Signatures are ECDSA over SHA-256, encoded as the raw 64 bytes of `r`
followed by `s`, in base64url without padding. They are always in the low-S form, in which `s` is no
greater than half the curve's order, because the PLC directory rejects the other of the two valid
signatures for a message.

### Why the phrase, and not a new secret

The key comes from the phrase the person already has for three reasons. There is nothing new to
write down: a second secret would be a second thing to lose. The recovery story is the same
everywhere: a device that can restore the journal from the phrase can also act for the identity. And
the derivation is deterministic, so every device that holds the phrase computes the same key and
there is no key file to sync or back up.

The cost is the one stated above: the phrase protects two things instead of one.

### Why its own branch of the seed, and not the journal key

The journal's encryption key is the first 32 bytes of the same BIP-39 seed (see `deriveMasterKey` in
the platform `CryptoManager` implementations). That key travels. A new device can receive it from an
existing device or from the encrypted account key envelope, and in neither case does the phrase
travel with it; the [account key vault reference](../reference/account-key-vault.md) and the
[Android recovery custody reference](../reference/native-android-recovery-custody.md) describe those
paths.

Deriving the recovery key from the journal key would let everything that ever held the journal key
derive the recovery key too. Deriving from the whole seed under its own label means that only the
phrase produces the recovery key. A test in
[`PlcRecoveryKeyDerivationTest.kt`](../../client/device/src/commonTest/kotlin/app/logdate/client/device/crypto/PlcRecoveryKeyDerivationTest.kt)
checks that the key derived from the journal key is a different key. The label is version 1 (`v1`).

A consequence is that a device which received its journal key without the phrase cannot derive the
recovery key. Automatic registration does nothing on such a device, and the account's recovery key
is registered from a device that does hold the phrase.

### The key is never stored

The private key exists only in memory. A device derives it from the stored phrase each time it needs
the public half or a signature, uses it and wipes it. Only the `did:key` and signatures cross the
network, and the server never receives the phrase or the private key.

### The same key on every platform

Derivation and signing are implemented on all three client platforms, and each produces the same
`did:key` for the same phrase.

| Platform | P-256 implementation                                                                                                                                                                                                     | Offered to people |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------- |
| Android  | BouncyCastle, in [`JvmPlcRecoveryKeySupport.kt`](../../client/device/src/androidMain/kotlin/app/logdate/client/device/crypto/JvmPlcRecoveryKeySupport.kt)                                                                | Yes               |
| iOS      | The platform provider of the `cryptography-kotlin` library, in [`CryptographyPlcRecoveryKeySupport.kt`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/CryptographyPlcRecoveryKeySupport.kt) | Yes               |
| Desktop  | BouncyCastle, in [`JvmPlcRecoveryKeySupport.kt`](../../client/device/src/desktopMain/kotlin/app/logdate/client/device/crypto/JvmPlcRecoveryKeySupport.kt)                                                                | No                |

The iOS implementation normalises signatures to low-S itself, because the platform library does not.
The desktop app derives and signs correctly and is wired the same way as the others, but its passkey
manager reports that passkeys are unsupported, and every change the app offers needs a passkey, so
the feature is never offered there.

## Registration needs nothing from the person

Nobody has to open, save, write down or confirm the recovery phrase for the recovery key to be
registered. The app already holds the phrase, so it registers the key by itself. This is deliberate:
a safeguard that depends on people completing a setup task protects only the people who complete it.

[`AutomaticAccountRecovery.kt`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/settings/account/recovery/AutomaticAccountRecovery.kt)
runs when the app starts, after each passkey sign-in, and when the Recovery phrase page opens. When
the feature is offered, someone is signed in and the device holds a phrase, it derives the `did:key`
and compares it with the key the server reports for the identity.

- If no key is registered, it registers this device's key.
- If this device's key is already registered, it does nothing.
- If a different key is registered, it changes nothing by itself. Replacing a key always needs the
  person to confirm with a passkey, described below.

Registration needs proof that the person confirmed with a passkey recently, and the person has
usually just done that by signing in. A passkey sign-in or sign-up therefore returns a **step-up
grant**: the server's record that this account confirmed with a passkey a moment ago. The grant is
valid for five minutes, works once, is bound to the account, and is accepted only for registering an
account's first recovery key. The app keeps it in memory, treats it as usable for four minutes to
leave a margin, and spends it on the registration, so the person sees no second prompt. The server
issues a grant only when the sign-in really was a passkey confirmation and the server supports
recovery keys.

When no usable grant exists, for example because the app was already signed in when the feature
became available, nothing is sent. The Recovery phrase page then says that one passkey confirmation
is still needed and offers a button that shows the passkey prompt.

Some failures can pass without the person doing anything: a connection problem, a refusal from the
server that has no more specific cause, an identity change that happened at the same moment, and a
failure the app cannot classify. The app retries these automatically after 10 seconds, 1 minute,
5 minutes and 30 minutes. A failure that the person has to fix, or one that is still there after
the last retry, stays visible on the page with what to do about it.

On the server, registering appends an update operation on top of the identity's latest operation.
Its rotation keys are the person's key and then the server's key, and the handle, the hosting server
and the data-signing key stay as they were. The server signs it with its current key, after checking
that the latest operation actually lists that key as a rotation key. Registering the key that is
already registered changes nothing, and registering a different key is refused, because replacing a
key is a separate request with stricter confirmation.

## Which changes need a fresh passkey confirmation

A recovery key makes four changes to an identity possible: registering a key, replacing a
registered key, replacing the server's key, and installing a data-signing key that the person
exported earlier. A signed-in session is not enough for the first three. Each needs a **step-up**: a
fresh passkey assertion, which is the signed answer a passkey gives to a challenge, made for this
purpose. The reason is that a session token can be stolen from a device or a log, while a passkey
assertion needs the passkey and the person's unlock.

The app first asks the server for a step-up challenge, then asks the platform for a passkey
assertion over it, and sends the assertion with the change. The challenge is its own kind
(`step_up`), bound to the account, single-use and valid for five minutes, and user verification is
required. Because the kind is recorded with the challenge, a step-up challenge cannot be spent as a
sign-in challenge and a sign-in challenge cannot be spent as a step-up. The grant described above is
a third kind (`step_up_grant`) for the same reason. The server checks the assertion with the same
verification that sign-in uses, following WebAuthn, the web standard that defines passkeys, and
also checks that the passkey belongs to the account making the change. A challenge is spent whether
or not its assertion verifies.

A change sent without a confirmation is answered `STEP_UP_REQUIRED`, and one whose confirmation is
rejected is answered `STEP_UP_FAILED`. An account with no passkey cannot begin a step-up and is
answered `PASSKEY_REQUIRED`, and a device that cannot show a passkey prompt is never offered the
feature. The contract and the interface are in
[`PasskeyStepUp.kt`](../../server/src/main/kotlin/app/logdate/server/passkeys/PasskeyStepUp.kt).

### The change that does not ask for a passkey

The fourth change is the exception. Installing an exported signing key with the recovery key's
approval uses two requests, `POST /api/v1/identity/signing-key/import/recovery/prepare` and
`POST /api/v1/identity/signing-key/import/recovery/complete`, in
[`IdentityApiRoutes.kt`](../../server/src/main/kotlin/app/logdate/server/routes/IdentityApiRoutes.kt).
They belong to a family of signing-key routes and do not ask for a step-up. What they
require instead is all of the following:

- a signed-in session;
- the exported key bundle and the passphrase that encrypts it;
- a recovery key already registered on the account;
- a valid low-S signature from that recovery key over the operation;
- a server that supports recovery keys and also publishes to the PLC directory.

A stolen session token still cannot complete this change, because it cannot produce the recovery
key's signature without the phrase and cannot open the export without its passphrase. The passkey is
not part of the defence, though, which makes this path weaker than the other three. No screen in the
app calls these routes.

The same family includes routes that rotate or import the server's signing key under the
server's own signature. They also take only a session, they keep the recovery key first in the
operation they build, and for a `did:plc` identity they change the identity only on a server that
publishes to the directory. The
[identity custody explanation](atproto-identity-custody.md#the-life-of-a-signing-key) describes
them.

## Replacing a recovery key whose phrase is lost

A person can lose the phrase their registered key came from, for example by starting again on a new
device with a new phrase. The old key cannot sign anything, because nothing can derive it any more.
The replace request therefore has the server sign the change with its own key, which is still a
rotation key, and list the new key first.

The design relies on the same ranking that makes the recovery key useful at all. The old recovery
key ranks above the server's key, so once the identity is published, a person who still holds the
old phrase can overrule a replacement they did not make by submitting their own operation to the
directory within 72 hours. LogDate has no tool for that submission, and an unpublished identity
has no 72-hour window at all, so the passkey prompt is the only guard on a replacement. The sign-in
grant is not accepted for it, and the app never replaces a key without the person choosing to.

## Replacing the server's key with the recovery key

The recovery key's own operation replaces the key the server uses for the identity. It is the one
change that the recovery key approves and the server does not. It takes two requests, because the
signature has to be made on the device.

In the first request the server generates a fresh key pair and builds, but does not sign, an update
operation: the rotation keys are the recovery key and then the new server key, the data-signing key
is the new server key, and the operation follows the identity's latest one. The server returns the
unsigned operation, the exact bytes to sign, and an opaque token. The token holds the new private
key, the identifier of the operation this one follows and a SHA-256 digest of the bytes to sign,
sealed with the server's key-encryption key and bound to the account. It expires after 30 minutes.
Because everything the server needs comes back in the token, the server stores nothing between the
two requests, and an abandoned attempt leaves nothing behind.

The device then derives the recovery key from its phrase and checks that it is the key registered on
the identity. It checks the operation as the next section describes, signs the bytes, asks for a
passkey confirmation, and sends the token, the signature and the step-up in the second request.

In the second request the server opens the token and checks that it belongs to this account and has
not expired. It rebuilds the operation from the account as it is now and compares it with the token:
if the identity's latest operation has changed, or the rebuilt operation's digest differs because
the handle or the hosting server changed in between, the request is refused as `IDENTITY_CHANGED`
and nothing is written. It checks that the registered recovery key is a rotation key of the latest
operation and that the signature is that key's low-S signature over the operation. Only then does it
append the operation and make the new key the account's active key. From that point the old server
key has no authority over the identity.

The implementation is in
[`RecoveryKeyService.kt`](../../server/src/main/kotlin/app/logdate/server/identity/RecoveryKeyService.kt)
and
[`RecoveryKeyRoutes.kt`](../../server/src/main/kotlin/app/logdate/server/routes/RecoveryKeyRoutes.kt).
Request and response shapes and the full list of error codes are in the generated API reference,
whose text lives in
[`RecoveryKeyDocs.kt`](../../server/src/main/kotlin/app/logdate/server/routes/docs/RecoveryKeyDocs.kt);
[the API reference guide](../../server/docs/openapi.md) says how to view it.

## Why the device refuses to sign anything else

The device does not sign bytes because the server asked it to. Before the recovery key signs, the
device decodes the operation and requires it to be exactly the change the person asked for, in
[`RecoveryOperationCheck.kt`](../../client/data/src/commonMain/kotlin/app/logdate/client/data/account/RecoveryOperationCheck.kt):

- the operation's canonical encoding is exactly the bytes the device was asked to sign, so the
  operation that was checked is the operation that gets signed;
- the operation follows the head of the identity's history, which the device works out itself by
  walking the operation log from the genesis operation and refusing a log that forks, loops or has
  no single start;
- the rotation keys are this device's recovery key, then the new server key, and nothing else;
- the data-signing key is the new server key and there is no other verification method;
- the handle and the services are unchanged from the head, and the handle is the account's own;
- the operation is an ordinary update, not a deactivation, and carries no fields the device does not
  understand.

The rule exists because of the ranking that makes the key valuable. The recovery key is first in the
list, so an operation it signs cannot be overruled by any lower key, including the server's, and
there is no key above it to appeal to. A signature from the recovery key is final in a way that no
signature from the server is.

That makes the device's signature the most dangerous thing a compromised or buggy server could
obtain. If the device signed whatever it was handed, a server could present an operation that drops
the recovery key from the list, puts an attacker's key ahead of it, points the identity at another
host or changes the handle, and the person's own key would have made the theft permanent. The check
removes that possibility by fixing the outcome before signing: whatever the server sends, the only
operation the recovery key will sign leaves the recovery key first, adds exactly one key below it,
and changes nothing else. After any operation the device agrees to sign, the person still outranks
every other key on their identity.

The check has a limit that follows from its inputs. The device cannot tell whether the new server
key is one the honest server generated or one an attacker supplied, and it reads the identity's
history and handle from the same server that proposed the operation. What the check guarantees is
the shape of the result, not the honesty of the party that proposed it. A dishonest new key sits
below the recovery key, where the person can replace it again.

The same check guards the one other operation the recovery key signs: installing a data-signing key
that the person exported earlier, which the server permits only when it publishes to the directory.
For that operation the device also requires the new key named in the operation to be the key in the
export.

## Two requests at the same moment

Two changes to the same account must never both build on the same operation. If they did, the
identity's history would fork, and the account record could name one recovery key while the history
named another. Three mechanisms prevent it, and they are layered because each covers a case the
others do not.

Every change runs under a lock selected by the account, and reads the account again inside the lock
before it does anything, so requests that reach the same server process take turns and the second
one sees what the first one did.

A lock inside one process does nothing when two server instances share a database. The operation
log's table, `hosted_plc_operations`, therefore has a unique index on the pair `(did, prev_cid)`,
which is the identity and the operation being built on. The index is created in
[this migration](../../server/src/main/resources/db/migration/V48__Recovery_key_operation_chain_and_step_up.sql).
The database refuses a second operation that follows the same one, and the request that loses is
answered `IDENTITY_CHANGED` with nothing changed.

The account record is written with a conditional update that succeeds only while the stored identity
is still the one the request started from. It never inserts, so it cannot bring back an account that
was deleted in the meantime, and it never overwrites an identity change made in between.

Each change also happens in a fixed order: append the operation, then make the new server key active
if there is one, then write the account. If a later step fails, repeating the same request finds the
operation already at the head of the history and finishes the remaining steps instead of appending
again. A failure part-way through therefore never needs manual repair, and a retry never produces
two operations.

## The protection is incomplete while an identity is unpublished

Recovery keys work on a server that does not publish identities to the PLC directory. Whether a
server is able to send operations to the directory at all is the deployment setting
`ATPROTO_PLC_PUBLISH_ENABLED`, which is off by default and is described in the
[environment variable reference](../../server/docs/environment-variables.md). While it is off the
server's hosted operation log is the identity's whole history.
The server finds the latest operation by following the chain from the genesis operation, computes
each operation's content identifier (CID) locally in the same way the directory would, and accepts
only operations the directory would accept: low-S signatures from a rotation key of the previous
operation. Nothing is sent to the directory. Keeping the log to the directory's rules is what lets
it be published later exactly as it stands.

In that mode the recovery key does not deliver its main promise, for three reasons.

- The only copy of the history is on the server. A server operator, or anyone who controls the
  server's database, could rewrite or discard an unpublished log, including the operation that
  listed the recovery key. Nothing outside the server would show that it had happened.
- Every change goes through the server. The app has no way to submit an operation to the directory
  by itself, so a server that refuses to cooperate cannot be bypassed.
- The 72-hour window does not exist. It is a rule the directory enforces on operations it has
  accepted, so there is nothing to undo against while nothing has been accepted.

What does hold while unpublished is narrower: a stolen session cannot change the key, a key
registered is already first in the history that will be published, and the device will not be
tricked into signing away its own authority. The protection becomes complete when the identity is in
the public directory, where the history can no longer be rewritten, and the app can submit a signed
operation there without the server.

Publishing is not a step that reaches every account in time. Making an identity public is each
person's own choice, recorded per account, and the deployment setting does not stand in for it. No
screen or route lets a person make that choice. The
[identity custody explanation](atproto-identity-custody.md#private-until-its-owner-publishes-it)
gives the rule and the reasons for it. An account whose owner never publishes it stays in the
unpublished state indefinitely. It keeps only the narrower protections in the previous paragraph,
and the 72-hour window and recovery without the server never apply to it.

The app's Recovery phrase page says the phrase can move the account to another server even if the
current server cannot help. That is not true of an unpublished identity, and for a published one it
still depends on a way to submit an operation without the server, which LogDate does not have.

## When the feature is offered

Two switches control recovery keys, one on each side.

On the server, `ATPROTO_RECOVERY_KEYS_ENABLED` is off by default. The server supports recovery keys
only when it hosts `did:plc` identities and this setting is on; turning on directory publishing with
`ATPROTO_PLC_PUBLISH_ENABLED` does not turn it on. A server that supports them advertises the
protocol feature `atprotoRecoveryKeysV1` in its server description. A server that does not answers
every recovery-key request with `RECOVERY_KEYS_UNAVAILABLE`, and it checks this before spending a
step-up. The [environment variable reference](../../server/docs/environment-variables.md) lists
both settings.

In the app, the feature flag `IDENTITY_RECOVERY_KEY` is on by default and can be turned off in Labs,
the settings page where features that are still being tried out are switched on and off. The app
offers the feature, and registers automatically, only when all of these are true:

- the flag is on;
- someone is signed in;
- the connected server advertises `atprotoRecoveryKeysV1`;
- this device can show a passkey prompt.

When any of them is false the app shows nothing about recovery keys, so it never offers a change the
server would refuse. The rule is in
[`AccountRecovery.kt`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/settings/account/recovery/AccountRecovery.kt).

## How the app talks about it

People are never shown the vocabulary on this page. The app's text does not say DID, PLC, rotation
key, `did:key` or operation, and the app never speaks as "we". It talks about the recovery phrase,
the passkey, the account and the server, and it describes the outcome a person cares about: the
phrase can move the account. Every failure message says what failed and what to do next. The strings
are in
[`strings_account_recovery.xml`](../../client/feature/core/src/commonMain/composeResources/values/strings_account_recovery.xml).

## How the behavior is tested

The tests below cover the behaviors listed with each of them, and they are the place to look when
changing one of those behaviors.

- **Derivation.**
  [`PlcRecoveryKeyDerivationTest.kt`](../../client/device/src/commonTest/kotlin/app/logdate/client/device/crypto/PlcRecoveryKeyDerivationTest.kt)
  runs the real platform cryptography from a standard BIP-39 test phrase to a pinned `did:key` on
  Android, desktop and iOS. The expected value was computed independently of the code, and the
  intermediate seed matches the published BIP-39 vector. It also checks that the private key is
  zeroed after use.
- **Signing.**
  [`PlcRecoveryKeySupportTest.kt`](../../client/device/src/commonTest/kotlin/app/logdate/client/device/crypto/PlcRecoveryKeySupportTest.kt)
  uses the P-256 test key from RFC 6979 appendix A.2.5 and checks that signatures are 64 bytes,
  low-S and verify.
- **Refusing to sign.**
  [`DefaultAccountIdentityRepositoryTest.kt`](../../client/data/src/commonTest/kotlin/app/logdate/client/data/account/DefaultAccountIdentityRepositoryTest.kt)
  checks that the device refuses operations that remove its key, add a rotation key, move the
  handle, change the service, skip the latest change, or whose bytes differ from the operation
  shown. It also checks that a sign-in grant is spent on the first registration and that a closed
  passkey prompt sends nothing.
- **Automatic registration.**
  [`AutomaticAccountRecoveryTest.kt`](../../client/feature/core/src/commonTest/kotlin/app/logdate/feature/core/settings/account/recovery/AutomaticAccountRecoveryTest.kt)
  covers registering without a prompt, waiting when no confirmation is available, retrying, and
  doing nothing while signed out, without a phrase or where the feature is not offered.
- **Server lifecycle.**
  [`RecoveryKeyLifecycleTest.kt`](../../server/src/test/kotlin/app/logdate/server/identity/RecoveryKeyLifecycleTest.kt)
  covers registration, replacing the server's key, a signature from the wrong key, a token that is
  stale, altered, expired or from another account, and stored operations that have no recorded CID.
- **Server safeguards.**
  [`RecoveryKeySafeguardsTest.kt`](../../server/src/test/kotlin/app/logdate/server/identity/RecoveryKeySafeguardsTest.kt)
  covers parallel requests on one server and on two server instances; a request that failed
  part-way and is repeated; a deleted account; a handle that changed after preparing; a high-S
  signature; a server key the identity does not list; replacing a lost key; and a check that nothing
  reaches the directory while publishing is off. The two-instance tests share in-memory
  repositories that refuse a second operation on the same head. They simulate the conflict the
  database's unique index raises and do not run against PostgreSQL.
- **The unique index itself.**
  [`RecoveryKeyMigrationPostgresTest.kt`](../../server/src/test/kotlin/app/logdate/server/database/RecoveryKeyMigrationPostgresTest.kt)
  checks on a migrated PostgreSQL database that concurrent appends on one head store exactly one
  operation and that a step-up challenge is spent once. It runs only when a disposable local
  database is supplied through environment variables and is skipped otherwise, so the index is
  enforced by the database whether or not this test ran.
- **Passkey confirmation over HTTP.**
  [`RecoveryKeyRoutesTest.kt`](../../server/src/test/kotlin/app/logdate/server/routes/RecoveryKeyRoutesTest.kt)
  checks that registering, replacing and completing each need a confirmation; that a step-up is
  single-use and cannot be replaced by a sign-in challenge or another account's passkey; that a
  sign-in grant registers a first key once, only for its own account, and cannot replace a key; and
  that a server without recovery keys refuses every request.
  [`AuthSigninStepUpGrantTest.kt`](../../server/src/test/kotlin/app/logdate/server/routes/auth/AuthSigninStepUpGrantTest.kt)
  checks when a passkey sign-in returns a grant.
- **Operation identifiers.**
  [`PlcEncodingTest.kt`](../../shared/atproto-plc/src/commonTest/kotlin/studio/hypertext/atproto/plc/PlcEncodingTest.kt)
  checks that the locally computed CID of a real genesis operation equals the CID the public
  directory assigned to it.
