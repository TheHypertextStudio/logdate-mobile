# Encryption formats

This page lists every encrypted format LogDate reads or writes: the envelopes that travel between a
device and the server, the blobs the server stores, and the files a device keeps on disk. For each
one it gives the tag that identifies it, the field layout, the algorithm, the nonce and tag sizes,
and how the key is derived. Each section links to the file that implements it.

For why the system is shaped this way, read [Encryption](../explanation/encryption.md) first. It
explains the threat model, the key hierarchy and the limits of each layer.

## Conventions used on this page

A few rules hold for every format below unless a section says otherwise.

- **Cipher.** AES in Galois/Counter Mode (AES-GCM) with a 256-bit key. AES-GCM is an authenticated
  cipher: decryption fails if the ciphertext, the nonce or the associated data was changed. The one
  exception to the key size is the server's own payload key, used for `LDSM1`, `LDBK1`, `LDSD1` and
  `LDSF1`: the server accepts a 128-bit, 192-bit or 256-bit key there, and the deployment chooses.
  The device classes that encrypt media also accept a 128-bit or 192-bit media key, although key
  derivation always produces 256 bits.
- **Nonce.** 12 bytes (96 bits). A nonce is a value that must never repeat under the same key. Some
  code and field names call it an IV; the two words mean the same thing here.
- **Tag.** 16 bytes (128 bits), appended to the ciphertext. Wherever a layout says "ciphertext", it
  means the encrypted bytes followed by the tag.
- **Associated data.** Extra bytes that are authenticated but not encrypted. Decryption fails unless
  the reader supplies the same bytes, which is how an envelope is tied to its context. This page
  calls it AAD.
- **Integers** are big-endian.
- **Key identifiers.** Only the server payload header and the sealed record envelope name their key.
  `LDSE2`, `LDCE2`, `LDCB2` and `LDKE1` carry no key identifier or version.
- **Tags.** A binary file or blob format starts with a five-character ASCII tag such as `LDCE2`,
  where the last character is the format version. The synced text format starts with the tag and a
  colon, such as `LDSE2:`. Three entries in the index below are not five-character tags.
  `LDLINK1` is the label mixed into the watch link's key derivation.
  `logdate-device-enrollment-v1` is the label in that envelope's key derivation and associated
  data. `app.logdate.crypto.sealed` is the record type of a sealed record.
- **HKDF** means HKDF-SHA256 (RFC 5869). It takes input keying material, a salt and an info string,
  and produces an independent key for each distinct salt and info.

## Format index

| Tag or name                    | What it carries                                                  | Written by      | Status                |
| ------------------------------ | ---------------------------------------------------------------- | --------------- | --------------------- |
| `LDSE2:`                       | One synced text value                                            | Devices         | Current               |
| `LDSE1:`                       | One synced text value, without a key fingerprint                 | Devices         | Read only             |
| `LDCE2`                        | One media file, in chunks                                        | Devices         | Current               |
| `LDCE1`                        | One media file, sealed whole                                     | Devices         | Read only             |
| `LDCB2`                        | A whole-journal backup archive, in chunks                        | Android         | Current               |
| `LDCB1`                        | A whole-journal backup archive, sealed whole                     | Android         | Read only in practice |
| `LDKE1`                        | An account's identity key and media key, wrapped for one passkey | Devices         | Current               |
| `logdate-device-enrollment-v1` | Account keys sent to a newly approved device                     | Devices         | Current               |
| `app.logdate.crypto.sealed`    | One record in a personal space                                   | Devices         | Current               |
| `LDLINK1`                      | The key exchange that links a watch to its phone                 | Phone and watch | Current               |
| `LDSM1`                        | A media payload encrypted by the server                          | Server          | Current               |
| `LDBK1`                        | A backup payload encrypted by the server                         | Server          | Current               |
| `LDSD1`, `LDSF1`               | A diagnostic report and its fingerprint, encrypted by the server | Server          | Current               |

"Read only" means current code decrypts the format and no longer produces it.

## Key derivation

### From recovery phrase to identity key

The recovery phrase is a 12-word BIP-39 mnemonic
([`Bip39`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/Bip39.kt)).

| Step         | Value                                                                                                                                    |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Entropy      | 16 random bytes (128 bits)                                                                                                               |
| Checksum     | The first 4 bits of SHA-256 of the entropy                                                                                               |
| Words        | 12 words of 11 bits each, from the 2048-word English BIP-39 list                                                                         |
| Seed         | PBKDF2-HMAC-SHA512 over the words joined by single spaces, with the salt `mnemonic` (no passphrase), 2048 iterations, 64 bytes of output |
| Identity key | Bytes 0 to 31 of the seed                                                                                                                |

The identity key is the root of every content key. `CryptoManager.deriveMasterKey` produces it on
each platform: the JDK's `PBKDF2WithHmacSHA512` on Android and desktop, and CommonCrypto on iOS.

### Derived keys

[`KeyDerivation.deriveKey(identityKey, context, contentId)`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/KeyDerivation.kt)
runs HKDF with `contentId` as the salt and `context` as the info, and returns 32 bytes. An empty
salt is replaced by 32 zero bytes. The input key must be exactly 32 bytes. The table gives the salt
and info for each key in the system, including those derived by other code.

| Key                               | Input keying material   | Salt                                                            | Info                                        | Output              |
| --------------------------------- | ----------------------- | --------------------------------------------------------------- | ------------------------------------------- | ------------------- |
| Field key                         | Identity key            | The field ID (see [field IDs](#field-ids))                      | `journal_content`                           | 32 bytes            |
| Media key                         | Identity key            | `media_payload_v1`                                              | `media_payload`                             | 32 bytes            |
| Media file key                    | Media key               | 16 random bytes stored in the file header                       | `LDCE2 media`                               | 32 bytes            |
| Cloud archive key                 | Identity key            | `cloud_archive_v1`                                              | `cloud_archive`                             | 32 bytes            |
| Account key envelope wrapping key | A 32-byte unlock secret | The binding string (see [`LDKE1`](#account-key-envelope-ldke1)) | `logdate-account-key-envelope-v1`           | 32 bytes            |
| Personal space content key        | Identity key            | `app.logdate.space.personal`                                    | `sealed-record v1`                          | 32 bytes            |
| Personal space index key          | Identity key            | `app.logdate.space.personal`                                    | `sealed-rkey v1`                            | 32 bytes            |
| Device enrollment key             | An X25519 shared secret | The request ID, lowercase                                       | `logdate-device-enrollment-v1`              | 32 bytes            |
| Identity recovery signing key     | The full 64-byte seed   | 32 zero bytes                                                   | `logdate.atproto.plc.recovery.v1:<attempt>` | A P-256 private key |

Three details matter when implementing these.

The media key is stored after it is first derived, under the secure-storage name
`media_payload_key_v1`, and a stored key always wins over derivation, so a stored random media key
is used as is rather than replaced by a derived one
([`MediaPayloadKeyProvider`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/crypto/MediaPayloadKeyProvider.kt)).

The identity recovery signing key starts from the whole seed, not from the identity key, so nothing
that holds the identity key can derive it. The attempt counter starts at 0 and increases until the
output is a valid P-256 scalar, up to 32 attempts
([`PlcRecoveryKeyManager`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/PlcRecoveryKeyManager.kt)).
[User-held recovery keys](../explanation/recovery-keys.md) explains what that key is for.

The Android database key is not derived from anything. It is 32 random bytes.

### Identity key fingerprint

A fingerprint identifies an identity key without revealing it
([`IdentityKeyFingerprint`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/IdentityKeyFingerprint.kt)).
It is the first 8 bytes of HMAC-SHA256 keyed with the identity key over the ASCII message
`sync-payload-fingerprint`, encoded as standard Base64 with padding (12 characters). Every `LDSE2`
value carries it, and a watch checks the key its phone sent against it.

## Synced text values: `LDSE2` and `LDSE1`

Each private text value is encrypted separately and uploaded as a string
([`SyncPayloadCipher`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/crypto/SyncPayloadCipher.kt),
[`ContentEncryptionService`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/ContentEncryptionService.kt)).

### `LDSE2`

The value is the literal prefix `LDSE2:` followed by a JSON object:

```json
{
  "fp": "<identity key fingerprint>",
  "env": {
    "v": 1,
    "alg": "AES-GCM",
    "iv": "<12-byte nonce, Base64>",
    "ct": "<ciphertext, Base64>",
    "aad": "<associated data, Base64>"
  }
}
```

| Property  | Value                                                                                           |
| --------- | ----------------------------------------------------------------------------------------------- |
| Key       | The field key: HKDF of the identity key with the field ID as salt and `journal_content` as info |
| Nonce     | 12 random bytes, new for every encryption                                                       |
| AAD       | The UTF-8 bytes of `type=CONTENT\|v=1\|id=<field ID>`                                           |
| Plaintext | The UTF-8 bytes of the value                                                                    |
| Base64    | Standard alphabet with padding                                                                  |

A reader handles a value in this order:

1. It compares `fp` with the fingerprint of its own identity key. A mismatch means the value was
   made with a different key, and the reader sets the record aside without attempting decryption.
2. It requires `v` to be 1 and `alg` to be `AES-GCM`.
3. It derives the field key from the field ID it expects and decrypts with the nonce and the AAD
   bytes carried in the envelope.

The reader does not rebuild the AAD from the field ID for `LDSE2`. A value moved to a different
field still fails, because the key is derived from the field ID and the wrong key fails
authentication.

A string that starts with `LDSE` followed by digits other than 1 or 2 is rejected as an unsupported
version. A string with no `LDSE` prefix at all is returned unchanged.

### `LDSE1`

`LDSE1` is `LDSE1:` followed by the inner envelope alone, with no `fp`. A reader accepts
it only when `v` is 1, `alg` is `AES-GCM`, the nonce is 12 bytes, the ciphertext is at least 16
bytes, and the AAD equals `type=CONTENT|v=1|id=<field ID>` for the field it expects.

### Field IDs

The field ID names one value of one record. It is the HKDF salt, so it must be reproduced exactly.

| Value                                          | Field ID                                                                     |
| ---------------------------------------------- | ---------------------------------------------------------------------------- |
| Journal title and description                  | `sync:journal:<journal ID>:title`, `sync:journal:<journal ID>:description`   |
| Entry text, or an encoded handwriting document | `sync:note:<note ID>:text`                                                   |
| Entry caption                                  | `sync:note:<note ID>:caption`                                                |
| Entry place                                    | `sync:note:<note ID>:location`                                               |
| Audio transcript                               | `sync:note:<note ID, lowercase>:transcript`                                  |
| Capture device                                 | `sync:note:<note ID>:capturedOn`                                             |
| Capture activity                               | `sync:note:<note ID>:capturedDuring`                                         |
| Draft text and draft blocks                    | `sync:draft:<draft ID>:content`, `sync:draft:<draft ID>:blocks`              |
| Marked moment                                  | `sync:moment:<moment ID>:payload`                                            |
| Location history record                        | `location-history:<server origin>:<account ID>:<record type>:<record ID>:v1` |

### What the server enforces

The server treats these strings as opaque and never decrypts them. It requires the `LDSE2:` prefix
for location history records (7 to 65,536 characters), marked moment payloads (at most 64 KiB) and
draft blocks. It does not check the form of an entry's text, a caption or a journal title.
[The sync API](../../server/docs/sync-v1-api.md) and
[the location history API](../../server/docs/location-history-api.md) give the request shapes.

## Client media: `LDCE2` and `LDCE1`

### `LDCE2`

Media is encrypted in independent chunks so that neither side holds more than one chunk in memory
([`ChunkedMediaEncryption.kt`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/crypto/ChunkedMediaEncryption.kt)).
The file is a 32-byte header followed by one or more sealed chunks.

```
Offset  Size  Field
0       5     ASCII "LDCE2"
5       4     Chunk size in bytes (u32)
9       16    Salt
25      7     Nonce prefix
32      ...   Sealed chunks, back to back
```

| Property                    | Value                                                                                                                         |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| File key                    | HKDF of the media key with the header's salt and the info `LDCE2 media`                                                       |
| Chunk size written          | 65,536 bytes (64 KiB) of plaintext                                                                                            |
| Largest chunk size accepted | 16,777,216 bytes (16 MiB). A header that declares 0, or more than this, is rejected.                                          |
| Chunk nonce                 | Nonce prefix (7 bytes), then the chunk index (u32, starting at 0), then one byte that is 1 for the last chunk and 0 otherwise |
| AAD for every chunk         | The whole 32-byte header                                                                                                      |
| Sealed chunk                | The chunk's ciphertext with its 16-byte tag                                                                                   |

Every chunk except the last carries exactly the declared chunk size of plaintext. The last chunk
carries whatever remains and is the only one flagged as last. An empty file is one empty chunk
flagged as last, so the shortest valid file is 48 bytes. The encrypted length of a file with `n`
plaintext bytes is `32 + n + 16 × max(1, ceil(n / 65536))`.

The index in the nonce stops chunks being reordered. The last-chunk flag stops a file being cut
short at a chunk boundary, because the chunk that would become the last one was sealed with the flag
at 0. The header as AAD stops the header being altered. A fresh salt per file gives each file its
own key, so nonces never repeat across files.

### `LDCE1`

`LDCE1` seals the whole file in one operation. Devices decrypt it and do not write it
([`AesGcmMediaPayloadCrypto`](../../client/sync/src/androidMain/kotlin/app/logdate/client/sync/crypto/AesGcmMediaPayloadCrypto.android.kt)).

```
Offset  Size  Field
0       5     ASCII "LDCE1"
5       12    Nonce
17      ...   Ciphertext
```

The key is the media key itself, and there is no AAD. A streaming reader decrypts an `LDCE1` payload
whole and refuses one larger than 31 MiB.

### How a reader chooses

A reader looks at the first five bytes. `LDCE2` is decrypted in chunks, `LDCE1` is decrypted whole,
and anything that does not start with `LDCE` is passed through unchanged as media that was never
encrypted by a device. A payload that starts with `LDCE` followed by an unknown version is rejected,
because treating it as plaintext would save ciphertext as a photo or recording.

## Cloud archive: `LDCB2` and `LDCB1`

Android's cloud backup uploads one ZIP archive of the journal, encrypted before upload
([`CloudArchiveCipher`](../../client/feature/core/src/androidMain/kotlin/app/logdate/feature/core/export/CloudArchiveCipher.kt)).
Only Android implements this format.

### `LDCB2`

```
Offset  Size  Field
0       5     ASCII "LDCB2"
5       8     Nonce prefix
13      ...   Chunk records, back to back
```

Each chunk record is a plaintext length (u32) followed by that chunk's ciphertext. The archive ends
with a terminator record: a length of 0 followed by a 16-byte tag over an empty plaintext.

| Property           | Value                                                                                         |
| ------------------ | --------------------------------------------------------------------------------------------- |
| Key                | The cloud archive key, used directly for every chunk                                          |
| Chunk size written | 1,048,576 bytes (1 MiB) of plaintext                                                          |
| Chunk nonce        | Nonce prefix (8 bytes), then the chunk counter (u32, starting at 0)                           |
| AAD                | ASCII `LDCB2`, then the nonce prefix, then the counter (u32), then the plaintext length (u32) |
| Terminator         | Uses the next counter value and a plaintext length of 0                                       |

A reader rejects a length above 1 MiB, any chunk that follows a chunk shorter than 1 MiB, any bytes
after the terminator, and a decrypted archive that does not begin with the ZIP signature `PK`.
Because the terminator is authenticated with its own counter, an archive cut short at any point
fails to decrypt.

Alongside the upload, the device sends this manifest string, which the server stores as text:
`{"format":"logdate-cloud-backup","encryption":"identity-aes-gcm-chunked-v2"}`.

### `LDCB1`

```
Offset  Size  Field
0       5     ASCII "LDCB1"
5       12    Nonce
17      ...   Ciphertext
```

The key is the cloud archive key and there is no AAD. The backup worker writes `LDCB2`, and a restore
reads both. A restore also accepts a bare ZIP that begins with `PK`, an unencrypted archive.

## Account key envelope: `LDKE1`

An account key envelope wraps an account's identity key and media key so that a passkey can unlock
them on another device
([`AccountKeyEnvelopeCipher`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/AccountKeyEnvelopeCipher.kt)).
[Encrypted account-key recovery](./account-key-vault.md) describes the sign-in flow and
the server routes around it. The envelope is always 97 bytes.

```
Offset  Size  Field
0       5     ASCII "LDKE1"
5       12    Nonce
17      80    Ciphertext: 64 bytes of key material and a 16-byte tag
```

| Property       | Value                                                                                                           |
| -------------- | --------------------------------------------------------------------------------------------------------------- |
| Plaintext      | The identity key (32 bytes) followed by the media key (32 bytes)                                                |
| Binding string | `account-key:v1:<account ID as a canonical UUID>:<credential ID>`                                               |
| Credential ID  | 1 to 2048 characters from `A-Z`, `a-z`, `0-9`, `_` and `-`                                                      |
| Wrapping key   | HKDF of the 32-byte unlock secret with the binding string as salt and `logdate-account-key-envelope-v1` as info |
| AAD            | ASCII `LDKE1` followed by the UTF-8 bytes of the binding string                                                 |

The server stores the envelope as standard Base64, which is exactly 132 characters. It accepts only
a value that decodes to 97 bytes, starts with `LDKE1` and re-encodes to the same string. It cannot
open the envelope, because the unlock secret never leaves the device.

## Device enrollment envelope

When a signed-in device approves a new one, it encrypts the account's keys to a one-time public key
that the new device displayed in its connection code
([`CryptographyDeviceTransfer`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/CryptographyDeviceTransfer.kt)).
The envelope is a JSON object with a version number and three byte fields. The three byte fields
(`pk`, `iv` and `ct`), and the keys inside the plaintext, are written as URL-safe Base64 without
padding. The recipient's public key is accepted with or without padding.

```json
{ "v": 1, "pk": "<sender public key>", "iv": "<12-byte nonce>", "ct": "<ciphertext>" }
```

| Step          | Value                                                                                                                                                    |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Key agreement | X25519 between a fresh sender key pair and the recipient's 32-byte public key                                                                            |
| Key           | HKDF of the shared secret with the lowercase request ID as salt and `logdate-device-enrollment-v1` as info, 32 bytes                                     |
| AAD           | The UTF-8 bytes of `logdate-device-enrollment-v1\|<account ID, lowercase>\|<request ID, lowercase>\|<confirmation code>`                                 |
| Plaintext     | A JSON object with `identityKey`, an optional `legacyMediaKey`, and an optional `session` holding `accountDisplayName`, `accessToken` and `refreshToken` |

`pk` is the sender's public key, which the recipient needs to compute the same shared secret.
Absent optional fields are omitted, not written as `null`. Binding the request ID and the
confirmation code into the AAD means an envelope made for one approval cannot be replayed into
another.

## Sealed records in a personal space

A record in a personal space is an `app.logdate.crypto.sealed` envelope with four fields: `version`
(1), `keyId`, `nonce` (12 bytes) and `ciphertext`
([`SealedRecordCodec`](../../shared/space/src/commonMain/kotlin/app/logdate/space/SealedRecordCodec.kt)).
[Personal space sync](./personal-space-sync.md) describes the record layout, the padding buckets
and the transport, and [private storage](../explanation/private-storage.md) explains why each part
exists. The values that concern keys are these:

| Property           | Value                                                                                                            |
| ------------------ | ---------------------------------------------------------------------------------------------------------------- |
| Plaintext          | The inner record encoded as DAG-CBOR, then padded to a size bucket                                               |
| Key                | The personal space content key                                                                                   |
| AAD                | The UTF-8 bytes of `app.logdate.crypto.sealed\|1\|<space URI>\|<author>\|<sealed record key>`                    |
| `keyId`            | The first 8 bytes of HMAC-SHA256 keyed with the content key over `keyId`, in lowercase Base32                    |
| Sealed record key  | HMAC-SHA256 keyed with the index key over `<collection>/<record key>`, in lowercase Base32, cut to 26 characters |
| Largest ciphertext | 4,000,000 bytes                                                                                                  |

Media in a personal space is an `LDCE2` blob under the account's media key, exactly as above.

## Watch link: `LDLINK1`

Linking a watch to its phone uses an X25519 exchange, HKDF salted with a hash of everything both
devices saw, AES-256-GCM for the bundle that carries the identity key, and HMAC-SHA256 for the
watch's confirmation. The labels are `LDLINK1`, `LDLINK1 bundle`, `LDLINK1 confirm` and
`LDLINK1 pair`
([`LinkHandshake.kt`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/link/LinkHandshake.kt)).
The [watch sync protocol](./wear/sync-protocol.md) is the reference for the message layouts.

## Server payloads: `LDSM1` and `LDBK1`

The server can add its own encryption to media and backup payloads before storing them
([`PayloadCodec`](../../server/src/main/kotlin/app/logdate/server/crypto/PayloadCodec.kt),
[`PayloadHeaderCodec`](../../server/src/main/kotlin/app/logdate/server/crypto/PayloadHeaderCodec.kt)).
Both formats share one layout and differ only in the tag: `LDSM1` for media, `LDBK1` for backups.

```
Offset   Size  Field
0        5     ASCII "LDSM1" or "LDBK1"
5        1     Version, 0x01
6        2     Key ID length K (u16), 1 to 128
8        K     Key ID, UTF-8
8+K      12    Nonce
20+K     ...   Ciphertext
```

| Property               | Value                                                                                  |
| ---------------------- | -------------------------------------------------------------------------------------- |
| Key                    | The server key from `SERVER_ENCRYPTION_KEY`: Base64 that decodes to 16, 24 or 32 bytes |
| Key ID                 | `SERVER_ENCRYPTION_KEY_ID`, or `default` when unset                                    |
| AAD                    | None                                                                                   |
| Shortest valid payload | 37 bytes                                                                               |

The server holds one key at a time
([`EnvironmentKeyring`](../../server/src/main/kotlin/app/logdate/server/crypto/EncryptionKeyring.kt)).
The header names the key that encrypted the payload, and a payload whose key ID is not the loaded
key fails to decrypt.

When `SERVER_ENCRYPTION_KEY` is not set, the server falls back to a fixed all-zero key with the ID
`noop`. That fallback offers no protection and exists for tests and local development. A production
start is refused unless `SERVER_ENCRYPTION_ENABLED` is set to `true`, `SERVER_ENCRYPTION_KEY` is
valid Base64 that decodes to 16, 24 or 32 bytes, and `SERVER_ENCRYPTION_KEY_ID` is set
([`ProductionConfigValidator`](../../server/src/main/kotlin/app/logdate/server/config/ProductionConfigValidator.kt)).

### How the server decides what to do with an upload

The server classifies each media or backup upload by its first five bytes and applies a policy
([`EncryptionPolicy`](../../server/src/main/kotlin/app/logdate/server/crypto/EncryptionPolicy.kt)).
A payload that starts with `LDCE1` or `LDCE2` is device ciphertext. One that starts with `LDSM1` or
`LDBK1` is server ciphertext. Everything else is treated as plaintext, and that includes an `LDCB2`
or `LDCB1` cloud archive.

| Payload           | `AT_REST_ONLY`                                                                                               | `E2EE_REQUIRED`  |
| ----------------- | ------------------------------------------------------------------------------------------------------------ | ---------------- |
| Device ciphertext | Stored unchanged. Wrapped in a server payload instead when `ALLOW_PASSTHROUGH_CLIENT_CIPHERTEXT` is `false`. | Stored unchanged |
| Server ciphertext | Stored unchanged                                                                                             | Stored unchanged |
| Anything else     | Wrapped in a server payload. Stored unchanged when `SERVER_ENCRYPTION_ENABLED` is `false`.                   | Refused          |

On download the server removes its own layer when the stored bytes start with its tag and returns
everything else as it is stored. Device ciphertext is therefore always returned as device
ciphertext.

Classification looks only at the five-byte tag. The server does not check the length or structure
of an `LDCE1` or `LDCE2` payload, so any bytes that start with one of those tags are accepted as
device ciphertext. A stored payload is never re-encrypted when settings change: a payload stored as
plaintext stays plaintext until it is uploaded again.

Three settings select the behavior. [The environment variable reference](../../server/docs/environment-variables.md)
describes each one.

| Setting                               | Default        | Effect                                                                                                      |
| ------------------------------------- | -------------- | ----------------------------------------------------------------------------------------------------------- |
| `ENCRYPTION_MODE`                     | `AT_REST_ONLY` | Selects the column in the table above. Any value other than the two mode names stops the server at startup. |
| `SERVER_ENCRYPTION_ENABLED`           | `true`         | When `false`, plaintext is stored as it arrives in `AT_REST_ONLY`. Not possible in production.              |
| `ALLOW_PASSTHROUGH_CLIENT_CIPHERTEXT` | `true`         | When `false`, device ciphertext gets a server layer as well in `AT_REST_ONLY`                               |

The defaults in this table are what the server uses when a variable is absent. Production is
stricter: the production startup check requires `SERVER_ENCRYPTION_ENABLED` to be present and
`true`, so the "stored unchanged" cell for plaintext in the policy table applies only to
development and tests.

The deployment configuration in [`infra/terraform`](../../infra/terraform/) sets
`SERVER_ENCRYPTION_ENABLED` to `true` and supplies the key and key ID from a secret manager for both
staging and production. It does not set `ENCRYPTION_MODE`, so both run `AT_REST_ONLY`.

### Where these payloads are used

| Route                                   | Stored in                                                                                        | Behavior                                             |
| --------------------------------------- | ------------------------------------------------------------------------------------------------ | ---------------------------------------------------- |
| `POST /api/v1/media`                    | Blob storage, or the `data` column of `logdate_media_records` when no blob storage is configured | Policy applied to the whole payload in memory        |
| `GET /api/v1/media/{mediaId}/binary`    |                                                                                                  | Server layer removed if present                      |
| `POST /api/v1/backups`                  | Blob storage                                                                                     | Policy applied by streaming through a temporary file |
| `GET /api/v1/backups/{backupId}/binary` |                                                                                                  | Server layer removed if present                      |

The `logdate_media_records` table has three columns for encryption metadata: `encryption_version`,
`encryption_key_id` and `encryption_mode`. The media upload route writes the constants `1`,
`default` and `SERVER` into them for every upload. They do not record which policy decision was
taken or which key was used, so the payload's own header is the only reliable source for the key ID.

A failure in this layer is reported as a server error. The media routes answer HTTP 500 with the
code `MEDIA_ENCRYPT_FAILED` or `MEDIA_DECRYPT_FAILED`, and the backup routes answer HTTP 500 with
`BACKUP_ENCRYPT_FAILED` or `BACKUP_DECRYPT_FAILED`. A payload refused by policy under
`E2EE_REQUIRED` takes the same path, so it is also an HTTP 500, not a distinct "encryption
required" response.

### Diagnostic reports: `LDSD1` and `LDSF1`

Diagnostic reports that a person chooses to send are stored with the same header layout and the
same server key, under their own tags so that media or backup ciphertext can never be read as a
report
([`DiagnosticReportService`](../../server/src/main/kotlin/app/logdate/server/diagnostics/DiagnosticReportService.kt)).
Unlike media and backups, these use AAD: `diagnostic-report:<owner ID>:<report ID>` for `LDSD1`
and `diagnostic-fingerprint:<owner ID>:<report ID>` for `LDSF1`. Report storage is unavailable
when the server has no real key. [Sync diagnostics](../observability/sync-diagnostics.md) describes
the feature.

## Private keys the server stores

The server holds some private keys for its own work: the signing key it creates for each account's
AT Protocol identity, and its OAuth keys. These keys are generated by the server or imported into
it. They are not derived from a recovery phrase and they encrypt no journal content. The server
encrypts each one before writing it to the database
([`AtRestKeyCipher`](../../server/src/main/kotlin/app/logdate/server/identity/AtRestKeyCipher.kt)).

| Property                                       | Value                                                         |
| ---------------------------------------------- | ------------------------------------------------------------- |
| Stored form                                    | Standard Base64 of a 12-byte nonce followed by the ciphertext |
| Key                                            | SHA-256 of the UTF-8 bytes of a deployment secret             |
| Secret, for both signing keys and OAuth keys   | `ATPROTO_SIGNING_KEY_KEK`                                     |
| Additional secret accepted for decryption only | `ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY`                        |
| AAD                                            | None                                                          |

There is no tag or version prefix. New values are always encrypted under the main secret. While the
decrypt-only secret is set, the server can also read values encrypted under it and re-encrypts them
under the main secret at startup, which is how a deployment moves from one secret to another.

## Storage on the device

### Secure storage

Secure storage is a small key-value store for secrets, with one implementation per platform
([`SecureStorage`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/storage/SecureStorage.kt)).
Byte values are stored as standard Base64 strings.

| Platform | Backing store                                                                                                                                                                         |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android  | `EncryptedSharedPreferences` in the file `logdate_secure_storage`. Names are encrypted with AES-256-SIV and values with AES-256-GCM, under a master key held in the Android Keystore. |
| iOS      | Generic-password Keychain items under the service `app.logdate`. The code sets no accessibility class, so the Keychain default applies.                                               |
| Desktop  | The `java.util.prefs` node `app.logdate.secure`. Each value is standard Base64 of a 12-byte nonce followed by AES-256-GCM ciphertext, under the device key described below.           |

These are the names that hold encryption material:

| Name                            | Value                                                                                                         |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `identity_key_v1`               | The identity key                                                                                              |
| `identity_recovery_phrase_v1`   | The recovery phrase, words separated by spaces. Absent on a device that received its key from another device. |
| `identity_recovery_verified_v1` | `true` once the person has confirmed the phrase                                                               |
| `identity_account_id_v1`        | The account the identity key belongs to                                                                       |
| `media_payload_key_v1`          | The media key                                                                                                 |
| `db_encryption_key`             | The Android database key                                                                                      |

### Android database

| Property              | Value                                                                                                   |
| --------------------- | ------------------------------------------------------------------------------------------------------- |
| File                  | `logdate` in the app's database directory                                                               |
| Engine                | SQLCipher (`net.zetetic:sqlcipher-android`), opened through `SupportOpenHelperFactory`                  |
| Key                   | 32 random bytes, passed to SQLCipher as the passphrase                                                  |
| Cipher settings       | SQLCipher's defaults. The code sets none.                                                               |
| Older databases       | Opened with `PRAGMA cipher_compatibility = 3`, then upgraded in place with `PRAGMA cipher_migrate`      |
| Key in secure storage | `db_encryption_key`                                                                                     |
| Key file              | `db_recovery/passphrase_backup` in the app's files directory: the key as standard Base64 text           |
| Interrupted key write | `db_recovery/passphrase_backup.part`, offered as a candidate key and never treated as the committed one |

Sources:
[`Database.android.kt`](../../client/database/src/androidMain/kotlin/app/logdate/client/database/Database.android.kt),
[`DatabasePassphraseProvider`](../../client/database/src/androidMain/kotlin/app/logdate/client/database/encryption/DatabasePassphraseProvider.kt)
and
[`PassphraseBackupStore`](../../client/database/src/androidMain/kotlin/app/logdate/client/database/encryption/PassphraseBackupStore.kt).

At startup the app collects candidate keys in this order: the key in secure storage, the key file,
and a complete key in the `.part` file. It tries each against the existing database. Only the key
that opens the database is written back to both stores. A new key is generated only when there is
no key, no key file and no database. If a database or key file exists and no key opens it, startup
stops, the app shows a recovery surface, and nothing is replaced. During a restore from another
device the same logic runs against alternate key and file names.
[Native Android backup](./native-android-backup.md) describes that flow and the recovery files
under `identity_recovery/`.

A database that begins with the 16-byte SQLite header `SQLite format 3\0` is a plaintext database.
It is migrated once:

1. The app attaches a new encrypted file, `logdate.sqlcipher-migrating`, and copies everything into
   it with `sqlcipher_export`, then copies the schema version.
2. It opens the encrypted file read-only with the key and runs a query against it.
3. It moves the original and its `-wal`, `-shm` and `-journal` files to
   `logdate.plaintext-backup-<milliseconds since epoch>` and moves the encrypted file into place.
4. If any step fails, it deletes the temporary file and moves the original back.

No code deletes the `logdate.plaintext-backup-*` files afterwards. Nothing changes the key of an
existing database.

### iOS database

The database is the file `logdate` in the app's Documents directory. The app applies no encryption
of its own to it. When dependency injection creates the database object, the app sets the
`NSFileProtectionKey` attribute of that file to `NSFileProtectionComplete`
([`DatabaseModule.ios.kt`](../../client/database/src/iosMain/kotlin/app/logdate/client/database/DatabaseModule.ios.kt),
[`Database.ios.kt`](../../client/database/src/iosMain/kotlin/app/logdate/client/database/Database.ios.kt)).

| Property                        | Value                                                                                               |
| ------------------------------- | --------------------------------------------------------------------------------------------------- |
| When the attribute is set       | Once per launch, immediately after the database object is built and before any connection is opened |
| Error handling                  | None. The call passes no error pointer and ignores the result.                                      |
| Files covered                   | `logdate` only. The `-wal` and `-shm` files beside it are not set.                                  |
| File missing when the call runs | The attribute is set only if `logdate` exists when the call runs.                                   |

The recovery phrase is also stored in a second Keychain item, under the service
`app.logdate.identity-recovery`, that is marked synchronizable so that iCloud Keychain carries it to
the person's other devices
([`IosIdentityKeyBackupStore`](../../client/device/src/iosMain/kotlin/app/logdate/client/device/crypto/IosIdentityKeyBackupStore.kt)).

### Desktop database

| Property                         | Value                                                                                                               |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Device key                       | 32 random bytes in `~/.logdate/secure-storage.key`, with permissions `rw-------` where the filesystem supports them |
| Database while the app runs      | `logdate`, unencrypted, with permissions `rw-------` where supported                                                |
| Database while the app is closed | `logdate.enc`                                                                                                       |
| `logdate.enc` layout             | A 12-byte nonce followed by AES-256-GCM ciphertext of the whole database file, with no AAD                          |
| Staging files                    | `logdate.unsealing` while decrypting, `logdate.enc.sealing` while encrypting                                        |

Sources:
[`DesktopDatabaseVault`](../../client/database/src/jvmMain/kotlin/app/logdate/client/database/DesktopDatabaseVault.kt)
and
[`DesktopSecureStorage`](../../client/device/src/desktopMain/kotlin/app/logdate/client/device/storage/DesktopSecureStorage.kt).

The database directory depends on the operating system:

| Operating system | Directory                                 |
| ---------------- | ----------------------------------------- |
| macOS            | `~/Library/Application Support/Haystack/` |
| Windows          | `%APPDATA%\Haystack\`                     |
| Linux and others | `~/.local/share/haystack/`                |

At launch, if `logdate` is absent and `logdate.enc` exists, the vault decrypts the sealed copy to
the staging file and moves it into place. A sealed copy that fails authentication stops startup and
is left unchanged. If `logdate` already exists, the vault uses it and does not touch the sealed
copy.

At exit, a shutdown hook closes the database and seals it. Sealing is skipped, and the database is
left unencrypted, if a non-empty `-wal` file remains, because those pages have not reached the main
file yet. Otherwise the vault encrypts to the staging file, moves it over `logdate.enc`, and only
then deletes `logdate` and its `-wal` and `-shm` files. A device key file that exists and is not
exactly 32 bytes stops startup, because replacing it would make everything already encrypted
unreadable. Nothing changes the key of an existing vault.
