# Encrypted account-key recovery

LogDate encrypts a person's journal on their devices, so the server cannot read it
([Encryption](../explanation/encryption.md) explains the design). A new device therefore needs the
account's keys from somewhere other than the server. The **account key vault** is one of the ways it
gets them: the server stores the keys sealed in an envelope that only a secret produced by the
person's passkey can open. The server holds ciphertext it cannot read, and it never holds the secret
that opens it.

Two terms are used on this page. A **passkey** is a cryptographic key pair held by the device or its
password manager, which the person uses to sign in. The **PRF extension** is an optional WebAuthn
feature that lets a passkey derive a stable secret from a fixed input, the same secret each time for
the same passkey. LogDate uses that secret, called the **unlock secret**, as the key for the envelope.

## How a device unlocks its keys at sign-in

The flow is: sign in, unlock the journal locally, then sync.

1. The client signs in with a passkey and also asks the passkey for PRF output over a fixed input
   (`PasskeyUnlockInput` in `client/permissions`). The client requests it locally, as part of the
   sign-in ceremony. The output is a 32-byte unlock secret.
2. The client removes the extension results from the sign-in response before sending it, so the unlock
   secret is never part of the request to LogDate and never uploaded.
3. [`AccountKeyUnlockCoordinator`](../../client/data/src/commonMain/kotlin/app/logdate/client/data/account/AccountKeyUnlockCoordinator.kt)
   asks the server for the envelope stored for the credential that was just used. If one exists, the
   client opens it with the unlock secret and installs the identity key and media key it holds.
4. If no envelope exists and this device already holds the account's keys, the client seals them with the
   unlock secret and uploads the envelope. A device only does this when its local journal belonged to
   this account before sign-in, so a newly adopted account never publishes unrelated local keys.
5. If the passkey produced no unlock secret, the server does not offer the feature, or no envelope exists
   and this device has no keys to publish, the device gets its keys another way: another signed-in
   device approves it, or the person types the recovery phrase. Google sign-in alone cannot open an
   envelope, because it produces no unlock secret.

PRF support depends on the credential provider. A passkey that cannot produce the output makes
step 5 the path, so a device never depends on PRF to be usable.

## The envelope

The envelope is called LDKE1. It is 97 bytes: a five-byte version marker, a 12-byte nonce and 80 bytes
of AES-256-GCM ciphertext holding 64 bytes of key material (the identity key and the media key)
and a 16-byte authentication tag. HKDF-SHA256 derives the wrapping key from the unlock secret, with
the account and credential binding as salt and `logdate-account-key-envelope-v1` as context. The header
and binding are authenticated, so an envelope opens only for its own account and credential. A wrong
account, credential or secret, modified ciphertext, and a plaintext key payload all fail instead of
returning keys. The exact layout is in the
[encryption formats reference](./encryption-formats.md#account-key-envelope-ldke1).

## Server routes

An envelope is stored per passkey credential, under
`/api/v1/account/key-envelopes/{credentialId}`, with a full-access token:

- `GET` returns `{ "ciphertext": "<Base64>" }`, or `404` when the credential has no envelope.
- `PUT` stores an envelope, answering `204`.

The server checks that the credential belongs to the signed-in account and answers `404` otherwise, so
the route does not reveal which credentials exist. It accepts only a value that is exactly 132
Base64 characters, decodes to 97 bytes, begins with `LDKE1` and re-encodes to the same string, and
answers `400 INVALID_ENVELOPE` for anything else. An envelope is immutable: a `PUT` with the same bytes is
accepted again so that a lost response can be retried, and a `PUT` with different bytes answers
`409 ENVELOPE_EXISTS`. Responses carry `Cache-Control: no-store`.

Envelopes live in the `account_key_envelopes` table, which stores only the credential, the
account and the ciphertext. The rows are deleted with their passkey and with their account, so removing
a passkey removes the envelope it unlocked.

The feature is switched by `LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED` and is off unless the value is
`true`. When it is off the routes answer `503 UNAVAILABLE`, and the server does not advertise the
protocol feature `encryptedAccountKeysV1` in its descriptor. Clients check that feature before calling
the routes.

## Approving a device from another device

When a device cannot unlock from the vault, an already signed-in device can hand it the keys. The new
device shows a connection code, as a QR code, that contains its one-time public key, a device name, a
confirmation code and, depending on the kind of code, either a request ID and the account ID or a
claim secret. The approving device scans it. When the code names a request that already exists, the approving
device checks every field it read against the request the server holds before it encrypts anything,
and it rejects a code that is not current. The keys travel
in a device enrollment envelope that only the new device can open, and the server holds it only until
the new device collects it or the request expires after five minutes. The format of that envelope is in
the [encryption formats reference](./encryption-formats.md#device-enrollment-envelope), and the routes
are `/api/v1/device-enrollments` in the interactive reference at `/docs`.

## What the vault cannot do

The server cannot recover a missing identity key. A journal key cannot be derived from encrypted
records, so an account whose keys exist on no device and in no envelope can only be opened with the
recovery phrase. An envelope can only be created from a device that already has the keys, which is why
an existing account creates its first envelope the next time it signs in from a device that is unlocked.
