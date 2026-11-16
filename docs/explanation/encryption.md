# Encryption

LogDate holds private memories: entries, recordings, photos, places and location history. This page
explains how that data is protected. It starts with what is protected and from whom, then describes
the keys and where each one comes from, how content is encrypted before it leaves a device, how it
is protected while it rests on each platform, and what the server can and cannot see. It also says
plainly what leaves the device for AI features.

This page explains the design. For exact byte layouts, key-derivation parameters and configuration
values, see the [encryption formats reference](../reference/encryption-formats.md). Three neighbouring
topics have their own pages and are only summarised here:

- [Private storage in sealed personal spaces](./private-storage.md) explains the store where every
  record is sealed, and [personal space sync](../reference/personal-space-sync.md) describes its
  transport.
- [Encrypted account-key recovery](../reference/account-key-vault.md) describes how an account's keys
  are wrapped so that a passkey can unlock them on another device.
- [User-held recovery keys](./recovery-keys.md) explains the signing key that the recovery phrase
  also produces, which protects the account's identity instead of its content.

## What is protected, and from whom

Encryption in LogDate answers three different threats, and it is easier to follow the design once
they are separated.

| Threat                                                                                             | What protects against it                                                                            | What it does not cover                                                                                                                               |
| -------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| Someone reads the server's database or storage bucket, or the server operator looks at stored data | Content is encrypted on the device with keys the server never receives                              | Account details, sync metadata and anything published on purpose                                                                                     |
| Someone intercepts traffic between a device and the server                                         | TLS for the connection, and the same device-side encryption underneath it                           | Traffic analysis: sizes and timing remain visible                                                                                                    |
| Someone gets a copy of the files on a device, for example from a lost phone or a copied disk       | The local database is encrypted or file-protected, and secrets sit in the platform's secure storage | A device that is unlocked and in use. The optional app lock covers part of that case; see the app lock and the limits listed for each platform below |

The design rests on these assumptions:

- A device is trusted while its owner is using it. Content is decrypted there, so malware running
  as the app, or anyone holding the unlocked device, can read it.
- The platform's cryptography and secure storage (Android Keystore, the iOS Keychain) behave as
  documented.
- The person keeps their recovery phrase safe. Anyone who learns the phrase can derive every content
  key for the account.

Two terms are used throughout. **End-to-end encryption** means that data is encrypted on one of the
owner's devices and can only be decrypted on another of the owner's devices, so that nothing in
between, including LogDate's own server, can read it. **Encryption at rest** means that stored bytes
are encrypted by whoever stores them. It protects a stolen disk or a leaked bucket. It does not
protect against the party that holds the key. LogDate uses both: devices encrypt content end to end,
and the server adds its own at-rest layer for anything that still arrives unencrypted.

## The recovery phrase is the root of every content key

Every account has a **recovery phrase**: twelve words chosen from the standard BIP-39 word list,
which together encode 128 bits of randomness and a 4-bit checksum. BIP-39 is the same scheme
cryptocurrency wallets use to turn a random secret into words a person can write down, and LogDate
uses it for the same reason. A person can copy twelve words onto paper, and typing them back on any
device reproduces the same secret exactly.

The phrase is stretched into a 64-byte seed with PBKDF2, as BIP-39 specifies. The first 32 bytes of
that seed are the **identity key**
([`IdentityKeyManager`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/IdentityKeyManager.kt)).
The identity key never encrypts anything directly. Every key that does encrypt something is derived
from it with HKDF-SHA256, a standard way of turning one secret into many independent ones. Each
derivation mixes in a label, so a key made for one purpose is useless for another
([`KeyDerivation`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/KeyDerivation.kt)).

| Key                                      | Derived from                                           | What it protects                                                                                 |
| ---------------------------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| Identity key                             | The recovery phrase                                    | Nothing directly. It is the starting point for every key below.                                  |
| Field key, one per synced field          | The identity key and the field's identifier            | One text value: an entry's text, a caption, a journal title, a location-history record and so on |
| Media key                                | The identity key                                       | Every photo, video and recording uploaded by the account                                         |
| File key, one per media file             | The media key and a random salt stored in the file     | The chunks of one media file                                                                     |
| Cloud archive key                        | The identity key                                       | The whole-journal backup archive that Android uploads                                            |
| Personal space content key and index key | The identity key                                       | Sealed records in the account's personal space, and the opaque keys they are stored under        |
| Identity recovery signing key            | The full 64-byte seed, on a separate branch            | The account's identity, not its content. See [user-held recovery keys](./recovery-keys.md).      |
| Database key (Android)                   | Nothing. It is random and unique to each installation. | The local database file                                                                          |

Three properties follow from this shape.

**One secret recovers everything that syncs.** A new device that learns the identity key can derive
every field key, the media key and the personal space keys without the server sending it any key
material. This is why the server never needs to hold a key.

**One compromised key does not expose the rest.** A field key is derived from the field's own
identifier, so learning the key for one entry reveals nothing about another. HKDF is one-way, so no
derived key leads back to the identity key.

**The identity key exists before the person has seen their phrase.** Sync encrypts every value from
the first upload, so `IdentityKeyManager.ensureIdentityKey` creates a phrase and a key as soon as
one is needed. Showing the phrase to the person and asking them to confirm it is a separate step in
settings. The reverse order would mean either blocking the first sync on a ceremony or uploading
plaintext until the ceremony was done.

Two keys in the table deserve a note.

The **media key** is derived from the identity key. A device that holds a random per-device media
key instead keeps using it, because media already encrypted under it can be read in no other way,
and the account key vault carries that key alongside the identity key for the same reason
([`MediaPayloadKeyProvider`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/crypto/MediaPayloadKeyProvider.kt)).

The **database key** is deliberately not derived from the recovery phrase. It protects one
installation's local file, and it is generated before any account exists. The consequence is
important: the recovery phrase recovers what was synced, not a local database whose key was lost.
[Native Android backup](../reference/native-android-backup.md) describes how that key is carried
across a device transfer.

Not every device has a phrase. A device that receives the identity key from another device stores
the key and no phrase, and a linked watch never creates an identity of its own
(`IdentityMintPolicy.NEVER`). It only ever uses the key its phone installed.

## How content is encrypted before it leaves a device

All content encryption uses AES-256-GCM. AES-GCM is an authenticated cipher: besides hiding the
content it produces a 16-byte tag, and decryption fails if a single bit of the ciphertext, the nonce
or the bound context was changed. A device therefore never mistakes damaged or substituted data for
real content.

### Text values

Each private text value is encrypted on its own
([`SyncPayloadCipher`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/crypto/SyncPayloadCipher.kt)
and
[`ContentEncryptionService`](../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/ContentEncryptionService.kt)).
The device builds a field identifier such as `sync:note:<id>:text`, derives that field's key,
encrypts the value under a fresh random nonce and uploads a string that begins with `LDSE2:`. The
values encrypted this way are an entry's text or handwriting document, captions, audio transcripts,
the place attached to an entry, the capture device and capture activity, journal titles and
descriptions, drafts, marked moments, and every location-history record.

Deriving the key from the field identifier does two jobs. It gives every value its own key, and it
ties a ciphertext to its place: a value copied from one entry onto another decrypts with the wrong
key and fails.

Each envelope also carries a short **fingerprint** of the identity key that made it. Without it, a
record encrypted under a different key and a record damaged in transit look the same, because both
simply fail to decrypt. With it, a device recognises "this was made with a key this device does not
have" before attempting decryption. That is the usual situation after a restore or reinstall
replaced the device's key, and the app can explain it instead of reporting corruption. A value a
device cannot read is set aside and tried again on later downloads. It is never applied and never
dropped.

### Media

Photos, videos and recordings are encrypted in independent 64 KiB chunks in a format tagged `LDCE2`
([`ChunkedMediaEncryption.kt`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/crypto/ChunkedMediaEncryption.kt)).
Android's AES-GCM implementation buffers every input byte until the operation finishes, so sealing
a whole file in one operation costs the whole file in memory. Chunking keeps memory use to one
chunk on both sides. Each file gets
its own key, derived from the media key and a random salt, so nonces never repeat across files. A
chunk counter stops chunks being reordered, a "last chunk" flag stops a file being cut short at a
chunk boundary, and the header is authenticated so it cannot be altered. Devices read the
whole-file format `LDCE1` but do not write it.

### Whole-journal archives

On Android, cloud backup uploads a ZIP archive of the whole journal. The archive is encrypted with a
key derived from the identity key, in 1 MiB chunks in a format tagged `LDCB2`
([`CloudArchiveCipher`](../../client/feature/core/src/androidMain/kotlin/app/logdate/feature/core/export/CloudArchiveCipher.kt)).
The archive ends with an authenticated empty chunk so that truncation is detected.
[Sync architecture](../reference/sync-architecture.md) covers how backup and sync relate.

### Sealed personal spaces

In a personal space the device goes further and encrypts the whole record, not just its text
fields, so the server cannot tell an entry from a location sample. The content key and the index
key for a space are derived from the identity key, records are padded to fixed sizes, and each
record is stored under a key the server cannot link to the record inside.
[Private storage in sealed personal spaces](./private-storage.md) explains that design and its
costs. Media in a space is encrypted exactly as described above.

## How keys reach another device

Content is only useful on a second device if that device can obtain the identity key. There are
five ways, and none of them gives the key to the server in a readable form.

1. **Typing the recovery phrase.** The device derives the same identity key. On a signed-in device
   it first checks that the phrase opens encrypted content the account already holds, and it
   replaces no local key until that check passes.
2. **Unlocking the account key vault with a passkey.** The identity key and media key are wrapped in
   an envelope that only a secret produced by the person's passkey can open. The server stores the
   envelope and cannot open it. See
   [encrypted account-key recovery](../reference/account-key-vault.md).
3. **Approving a new device from one that is already signed in.** The new device shows a one-time
   public key in a connection code, and the approving device encrypts the keys to it.
4. **Linking a watch to its phone.** The phone sends the identity key over an authenticated key
   exchange. See the [watch sync protocol](../reference/wear/sync-protocol.md).
5. **Android's own backup and device transfer.** A copy of the recovery material travels in
   Android's encrypted backup. See [native Android backup](../reference/native-android-backup.md)
   and [recovery custody on Android](../reference/native-android-recovery-custody.md).

Content keys are not derived from a passkey or a biometric. A passkey can be lost or replaced, and
each one produces a different secret, so tying content keys to one would make them as fragile as the
credential. The passkey only unlocks a wrapped copy of keys that already exist, and the recovery
phrase remains the way back in when every other route is gone.

## Protection at rest on each platform

Encrypting content for the server does not protect the copy on the device, which the app has to
read in order to show anything. That copy is protected separately, and differently on each platform.

| Platform | Local database                                                                                                                                                                          | Where keys and secrets are stored                                              |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| Android  | Encrypted with SQLCipher under a random 32-byte key unique to the installation                                                                                                          | `EncryptedSharedPreferences`, whose master key is held in the Android Keystore |
| iOS      | Not encrypted by the app. The app marks the file `NSFileProtectionComplete`, a class of the operating system's data protection that makes a file unreadable while the device is locked. | Keychain items                                                                 |
| Desktop  | Encrypted as a whole file while the app is closed, and decrypted into place while it runs                                                                                               | A key file in the user's home directory, readable only by that user            |

Two rules about key handling hold on every platform. Key material and recovery phrases are never
written to logs: the code that handles them logs that something happened, such as an identity being
restored, and never the value involved. And on a device a key is kept only in the places this
section names: the platform's secure storage, the desktop key file, and the Android recovery files.
The last two hold keys in readable form, protected by file permissions and the operating system's
storage encryption instead of a keystore. The sections below describe each one and what it costs.

### Android

The database is opened through SQLCipher, which encrypts every page of the SQLite file
([`Database.android.kt`](../../client/database/src/androidMain/kotlin/app/logdate/client/database/Database.android.kt)).
Its key is random and is kept in two places
([`DatabasePassphraseProvider`](../../client/database/src/androidMain/kotlin/app/logdate/client/database/encryption/DatabasePassphraseProvider.kt)).
The primary copy is in secure storage, which is bound to the device's Keystore. A second copy is a
file in the app's private storage.

The second copy is a deliberate trade. The Keystore does not survive a device-to-device transfer or
a cloud restore, and a database without its key is lost for good. The file copy survives both. It
is protected by the app sandbox, by Android's file-based encryption and by the requirement that
cloud backup be client-side encrypted, and it gives up the Keystore's hardware backing. Losing that
extra layer was judged better than a journal that cannot be opened after moving to a new phone.

The account's recovery material has file copies for the same reason. A device that holds a recovery
phrase keeps a copy of the phrase. A device that was given its keys by another device has no phrase,
so it keeps a copy of the identity key instead, recorded against the account it belongs to. A
separate file keeps media keys, each recorded against the identity it belongs to.
[Recovery custody on Android](../reference/native-android-recovery-custody.md) describes those files
and the order in which they are trusted.

Startup never replaces a key it could not use. It offers every key it holds to SQLCipher, and only
after one opens the retained database does it copy that key back into both stores. It creates a new
key only when there is no database and no retained key at all.

Installations that predate database encryption are migrated on first launch. The plaintext database
is exported into an encrypted file, the encrypted file is opened with the new key to prove it is
readable, and only then is the original moved aside and the encrypted file put in its place. A
failure at any step restores the original.

### iOS

The app does not encrypt the database itself on iOS. It relies on the operating system's data
protection, which encrypts every file on the device and decides when each file's key is available.
When dependency injection creates the database object, the app asks for the strictest class,
`NSFileProtectionComplete`, on the database file
([`DatabaseModule.ios.kt`](../../client/database/src/iosMain/kotlin/app/logdate/client/database/DatabaseModule.ios.kt),
[`Database.ios.kt`](../../client/database/src/iosMain/kotlin/app/logdate/client/database/Database.ios.kt)).
A file in that class is unreadable while the device is locked and readable to the app while it is
unlocked.

That request is made once per launch, and its result is not checked. Creating the database object
does not open a connection, so on the first launch after installation the file may not exist yet
when the request is made. In that case the request does nothing, and the file keeps the protection
class the operating system gives new files until a later launch finds it and applies the stricter
one.

Secrets are stored as Keychain items. The recovery phrase is additionally kept in a Keychain item
that syncs through iCloud Keychain, so it survives a move to a new device. Unlike Android, iOS keeps
no file copy of any key.

### Desktop

SQLCipher is not used on desktop. Instead
[`DesktopDatabaseVault`](../../client/database/src/jvmMain/kotlin/app/logdate/client/database/DesktopDatabaseVault.kt)
keeps an encrypted copy of the database while the app is closed. At launch the vault decrypts that
copy into place, and at exit it encrypts the closed database and then removes the unencrypted file.
Neither step replaces data it could not read: an encrypted copy that fails to decrypt stops
startup, and a database that cannot be encrypted is left as it is.

### Media files

Photos, videos and recordings are kept as files outside the database on every platform. Storing
them inside it would make the database large and slow to open and would force every media read
through SQLite. Local media files are not encrypted by the app. They rely on the app sandbox and on
the operating system's storage encryption. [Android media storage](../reference/android-media-storage.md)
describes where they live.

### The app lock

None of the protection above helps when someone else is holding the device unlocked. For that case
the app has a lock of its own, which is a screen and not a layer of encryption. A person can turn on
an app lock in privacy settings, and the app then asks for the device's own check, a fingerprint, a
face or the device passcode, before it shows any content. Separately, the app always asks for that
check before it reveals the recovery phrase
([`BiometricGatekeeper`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/BiometricGatekeeper.kt)).
Both exist on Android and iOS. Desktop has no such check, and its implementation treats every
request as already satisfied.

The lock decides what the app displays. It is not tied to any key: the database key and the
identity key are available to the app process whether or not the check has passed. It deters
someone who picks up an unlocked phone. It adds nothing against someone who can read the app's
files or run code as the app.

### Limits to be aware of

- **Desktop protection is weaker than mobile.** The device key is an ordinary file in the user's
  home directory, protected only by file permissions, not by an operating system keychain. The
  database is unencrypted on disk for as long as the app is running, and it stays unencrypted after
  a crash, because sealing happens in a shutdown hook.
- **The Android migration leaves the old plaintext database behind.** The original file is moved to
  a timestamped backup beside the encrypted one, and no code removes it. Only installations that
  were created before database encryption existed are affected.
- **iOS has no app-level database encryption.** Protection depends on the device having a passcode.
  The app requests the protection class on the main database file only, not on SQLite's write-ahead
  log beside it, it does not check that the request succeeded, and on a first launch the request
  may run before the file exists.
- **Local media relies on the operating system** on every platform.

## What the server can and cannot see

What the server sees depends on which of two stores an account uses. [Moving journals into personal
spaces](./journal-moves.md) covers moving an account from the older store to the newer one.

**In the older per-kind store**, each kind of data has its own endpoint and the private text is
encrypted field by field. The server stores those fields as opaque `LDSE2:` strings and encrypted
media as opaque `LDCE2` blobs. It can still read the structure around them: record identifiers,
record types, creation and edit timestamps, version numbers, which entry belongs to which journal,
recording lengths, photo layout, the device that uploaded a record, and media file names, types and
sizes. [The sync API](../../server/docs/sync-v1-api.md) lists every field.

**In a personal space**, the server sees a sealed record, an opaque key, a padded size and a
sequence number. The list of what remains visible, and why each item has to be, is in
[private storage](./private-storage.md#what-the-server-still-sees-and-what-it-no-longer-sees).

**In both stores** the server knows the account itself: its handle and identifier, profile details,
sign-in credentials, registered devices, when each device syncs and how much storage it uses. It
also holds everything the person deliberately publishes, which is public by design.
[AT Protocol interoperability](./atproto-interoperability.md) lists what LogDate publishes and what
it never publishes.

The server never receives the recovery phrase, the identity key, the media key or any key derived
from them in a form it can read. It does hold two sealed copies of those keys, and it can open
neither. The first is the wrapped envelope in the
[account key vault](../reference/account-key-vault.md), which only a secret produced by the
person's passkey can unwrap. The second is the device enrollment envelope: when one device approves
another, the server relays the encrypted keys and holds them until the new device collects them or
the request expires. Only the new device holds the private key that opens it.

The server also holds keys of its own. These are generated by the server or supplied by its
operator, they are not derived from the recovery phrase, and none of them can decrypt content a
device encrypted. They are the key for the server's own storage layer and the private keys it signs
with, including the signing key it creates for each account's AT Protocol identity. Both are
described in the next section.

One qualification matters. In the older store the server does not require every text field to be
encrypted. It rejects unencrypted location history, marked moments and draft blocks, but it accepts
an entry's text or a journal's title in whatever form a client sends. The clients in this
repository encrypt them. Confidentiality of those fields therefore rests on the client, not on a
server check.
The personal space closes this gap, because the server accepts only sealed records there.

## The server's own layer and its two modes

The server has an encryption layer of its own for media and backup payloads
([`EncryptionService`](../../server/src/main/kotlin/app/logdate/server/crypto/EncryptionService.kt)).
It exists for defence in depth. The server does not assume that what arrives is ciphertext, and
encrypting on arrival means a leaked storage bucket exposes nothing readable either way.

The layer runs in one of two modes, chosen by the `ENCRYPTION_MODE` setting.

- **`AT_REST_ONLY`**, the default, accepts anything. Payloads that a device already encrypted are
  recognised by their tag and stored unchanged. Anything else is encrypted with the server's own key
  before it is stored and decrypted when it is read back.
- **`E2EE_REQUIRED`** refuses any payload that a device has not encrypted. The policy recognises only
  the media tags as device-encrypted, so it treats an `LDCB2` cloud archive as plaintext and refuses
  it, and a refusal is reported as a server error, not a clear "encryption required" response.

Any other value for the setting stops the server from starting.

In a production deployment this layer cannot be turned off. Startup is refused unless
`SERVER_ENCRYPTION_ENABLED` is `true` and a key and a key identifier are supplied
([`ProductionConfigValidator`](../../server/src/main/kotlin/app/logdate/server/config/ProductionConfigValidator.kt)).
Outside production the layer can be disabled, or run with a fixed all-zero key, which is how tests
and local development work.

The server's key protects only against someone who obtains the stored bytes without the key. It is
not end-to-end encryption, because the server holds that key.

The server also holds private keys for its own work: the signing key it creates for each account's
AT Protocol identity, and the keys it signs OAuth tokens with. It creates an account's signing key
the first time the account needs an identity, whether or not the person has chosen to make that
identity public. These keys are encrypted in the database under a separate deployment secret. They
sign records and tokens. They are not derived from the recovery phrase and they encrypt nothing in
the journal. [Signing-key custody](./atproto-identity-custody.md) explains who holds which key and
why.

## AI features and encrypted content

End-to-end encryption and cloud AI pull in opposite directions. A model can only summarise text it
can read, so any content sent to a cloud model has left the encrypted boundary for the duration of
that request. This section describes what is sent.

**Which service.** AI requests go from the device directly to OpenAI's chat completions API. They do
not pass through the LogDate server
([`OpenAiClient`](../../client/intelligence/src/commonMain/kotlin/app/logdate/client/intelligence/generativeai/openai/OpenAiClient.kt)).

**Whether any request is made at all** depends on whether the app process has an API key. The key
is read at runtime from an `OPENAI_API_KEY` environment variable or JVM system property on Android
and desktop, and the iOS loader always returns nothing. No build script places a key into a packaged
app. An app started without that variable uses a client that answers every request with "missing
credentials" and sends nothing
([`ClientsModule`](../../client/intelligence/src/commonMain/kotlin/app/logdate/client/intelligence/di/ClientsModule.kt)).

**What is sent when a key is present.** The content is sent as written. It is not anonymised,
redacted or filtered. Several rows concern Rewind, which is LogDate's recap of a period: a short
story of one week, or of one year, assembled from that period's entries, photos and recordings.

| Feature                     | Content included in the request                                                                                                                                                                                                                  |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Day summary on the timeline | The text of every text entry for that day, with each entry's date                                                                                                                                                                                |
| People mentioned in a day   | The text of every text entry for that day, in one request                                                                                                                                                                                        |
| Moments within a day        | Each entry's identifier, timestamp and type, entry text, photo and video captions, place names, recording lengths and detected ambient sound labels                                                                                              |
| People for a weekly Rewind  | The full text of each text entry in the week, one request per entry                                                                                                                                                                              |
| Weekly Rewind narrative     | Entry text and audio transcripts (each cut to 500 characters), photo and video captions in full, video and recording durations, timestamps, the identifiers of entries, photos, videos and recordings, and the names of people found in the week |
| Yearly Rewind narrative     | For each week of the year, values taken from its saved weekly Rewind: the dates, the title, detected activities, narrative passages, quoted lines from entries, noticed patterns, people and milestones                                          |
| Naming an event             | The place name, time of day, photo and note counts, and the start of the first note                                                                                                                                                              |
| Onboarding introduction     | The name and the short description the person typed                                                                                                                                                                                              |

**Which Rewind requests depend on the account's plan.** Only one does. While it builds a weekly
Rewind, the app reads the account's entitlement, which is the plan the server reports for the
account, and picks one of two behaviours
([`EntitlementRewindAIAvailability`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/rewind/EntitlementRewindAIAvailability.kt)).
An account on the `PRO` or `UNLIMITED` tier, an account on a self-hosted server, or an account for
which the server has switched on the `rewind_narrative_full` feature gets the weekly narrative
written by the model. Every other account gets a narrative assembled on the device, and so does an
account that is signed out, cancelled, or whose entitlement could not be read. For those accounts
the weekly narrative request is never made.

The local narrative is not the same as "no AI request", however. The people requests in the table
run before the plan is consulted
([`GenerateBasicRewindUseCase`](../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/rewind/GenerateBasicRewindUseCase.kt)),
so a weekly Rewind on any plan sends the text of each text entry for name extraction when a key is
present. The yearly narrative does not consult the plan at all.

**What limits a request.** The checks differ by feature.

- The day summary, people, moments and event-naming requests are skipped when the device is
  offline, when the operating system's Data Saver setting is restricting the app's data use, or
  when the input is longer than 100,000 characters.
- The weekly and yearly Rewind narratives are skipped when the device is offline or Data Saver is
  on. They have no length limit of their own. The weekly input is bounded only by the 500-character
  cut applied to each entry and transcript.
- The onboarding introduction checks only that the device is online.

Responses to every request except the onboarding introduction are cached on the device, so the same
content is not sent twice while the cached answer is still valid.

**What the person controls.** No setting turns these requests off. The one AI-related preference is
a switch called AI recall in the recommendation settings, meant to let a model choose which past
memory to bring back. It is off by default and has no effect, because nothing implements
the provider it would enable.

Two related facts complete the picture. Audio transcripts are produced on the device. The server
can issue sessions for a cloud transcription service when it is configured with a key, and the
client has an API class for requesting one, but no feature calls it, so recordings are not sent
anywhere for transcription. Separately, a weekly Rewind that takes the model-written path, and has
location history for the week, asks a public weather archive for historical weather. It sends the
average latitude and longitude of that week's location history and the week's date range. That
request is made whether or not an AI key is present. It is not AI, but it is a location leaving the
device.
