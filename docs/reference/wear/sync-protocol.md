# Watch and phone sync protocol

This page describes how the LogDate Wear OS app and the LogDate Android phone app exchange data as
the code works: which transports they use, how the phone links a watch, which device owns
each piece of shared state, how features are switched on, where a capture made on the watch goes
next and how long each step waits, how the phone's library reaches the watch, and how audio is
streamed. It gives the exact paths, constants and timings and names the class that defines each one.

For what the watch is for and why it was designed this way, see
[The watch companion](../../explanation/wear-companion.md). For the server routes a linked watch
calls, see the [device sessions API](../../../server/docs/device-sessions-api.md). For backup between
a device and the server, which the watch reuses, see
[Sync architecture](../sync-architecture.md).

## Two protocols run side by side

The watch and the phone talk through the Wear OS Data Layer, a Google Play services API that moves
small pieces of data between a watch and the phone it is paired with. No LogDate server is involved
in that exchange. A device on the Data Layer is called a _node_ and has a node id that Play services
assigns.

Two protocols use the Data Layer in this codebase, and both are compiled into every build.

**Version 1** copies notes, journals, links between journals and
notes, and health snapshots as individual data items under `/logdate/notes`, `/logdate/journals`,
`/logdate/associations` and `/logdate/health`. It has no notion of an account on the watch and no
record of what the phone did with a capture beyond one acknowledgement message for audio notes.

**Version 2** adds a verified link between one watch and one phone, a durable record of where each
capture stands, a bounded copy of the phone's library on the watch, byte-range audio streaming, and
the option for the watch to back up to the LogDate server by itself. It runs only when a feature
flag is on and both devices have said they support it. Until then both devices use version 1, and
even with version 2 active, journals, journal links and health snapshots still travel over
version 1.

Version 2 paths were chosen so that a build without version 2 never wakes for one: none starts
with a prefix a version 1 listener filters on (`/logdate/notes`, `/logdate/journals`,
`/logdate/associations`, `/logdate/health`, `/logdate/camera`, `/logdate/sync`).

### Where each protocol lives in the code

| What                           | Version 1                                                                                                                                                                        | Version 2                                                                                                                                                                                                                           |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Wire types shared by both apps | [`client/sync/.../datalayer/`][datalayer]: `NoteDataMapper`, `JournalDataMapper`, `AssociationDataMapper`, `HealthSnapshotDataMapper`, `NoteAckPayload`, `WearAudioRequestPaths` | [`client/sync/.../datalayer/v2/`][datalayer-v2]: `WearPaths`, `WearProtocol`, `WearFeatures`, `WearProtocolGate`, `RecordEnvelope`, `Receipts`, `LinkMessages`, `WindowMessages`, `MediaMessages`, `WindowBodyMessages`, `WearJson` |
| Logic shared by both apps      | none                                                                                                                                                                             | [`client/sync/.../peer/`][peer]: `state/` (state documents), `link/` (the exchange and the account install), `delivery/` (the router, the ledger, envelope bodies), `removal/`                                                      |
| Watch                          | [`app/wear/.../sync/`][wear-sync]: `WearDataLayerSyncManager`, `WearLegacyDelivery`, `WearSyncRetryPolicy`, `WearPhoneMessageHandler`, `SyncedAudioCache`                        | [`app/wear/.../sync/v2/`][wear-sync-v2]: `link/`, `delivery/`, `window/`, `media/`, `cloud/`, `removal/`, plus `WatchStateMirror` and `WatchPeerLink`                                                                               |
| Phone                          | [`app/compose-main/.../client/sync/`][phone-sync]: `WatchNoteIngestor`, `WatchJournalIngestor`, `PhoneWearSyncBridge`, `WatchNoteAcknowledgement`                                | [`app/compose-main/.../client/sync/v2/`][phone-sync-v2]: `link/`, `receipts/`, `window/`, `media/`, `continuity/`, `removal/`, plus `PhoneStatePublisher` and `PhonePeerLink`                                                       |
| Dependency wiring              | `WearDataModule` (watch), `AndroidWearSyncModule` (phone)                                                                                                                        | `WearSyncV2Module`, `WearWatchWindowModule`, `WearPhoneMediaModule`, `WearCloudSyncModule` (watch); `PhoneWatchSyncV2Module`, `PhoneWatchWindowModule`, `PhoneMomentSyncModule` (phone)                                             |

Both protocols enter each app through one listener service:
[`WearDataLayerListenerService`][WearDataLayerListenerService] on the watch and
[`PhoneDataLayerListenerService`][PhoneDataLayerListenerService] on the phone. Play services delivers
only the events whose path appears in that service's manifest filters, so a new path needs a filter
in [`app/wear/src/main/AndroidManifest.xml`][wear-manifest] or
[`app/android-main/src/main/AndroidManifest.xml`][phone-manifest] as well as a handler.

On the watch, [`WatchSyncFacade`][WatchSyncFacade] is the single `SyncManager` the rest of the app
sees. Every call that moves data goes to `WearDataLayerSyncManager`, which hands notes and marked
moments to version 2 delivery when it is active and otherwise sends them itself over version 1.

## Transports

The Data Layer offers five primitives. Each has a different delivery guarantee, and the protocol
picks the one that matches what the data needs.

| Primitive                             | Guarantee                                                                                                                                                       | Used for                                                                                                                             |
| ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Data item (`DataClient`)              | A small key-value record stored by Play services and copied to the other device, including after a reconnect. A put that matches the stored item sends nothing. | State documents, version 1 records, version 2 capture envelopes, library window items and removal markers                            |
| Message (`MessageClient.sendMessage`) | One-way and best effort. It is lost if the other device is out of reach.                                                                                        | Version 1 acknowledgements and sync requests, pushed receipts, live recording and playback state                                     |
| Request (`MessageClient.sendRequest`) | A message with an answer. It fails when the other device does not answer in time.                                                                               | The link exchange, receipt queries, window resync, media descriptors and tickets, live commands, reminders, presence and diagnostics |
| Channel (`ChannelClient`)             | A byte stream between two nodes.                                                                                                                                | Audio files in both directions and library bodies too large for a data item                                                          |
| Capability (`CapabilityClient`)       | A named flag a node advertises. Other nodes can list who advertises it and whether those nodes are reachable.                                                   | Finding the other app and announcing that version 2 is on                                                                            |

Three facts about the Data Layer shape the protocol. Data items and channels are not ordered against
each other, so a recording's bytes can arrive before or after its metadata. A device hears its own
data item writes as events, so both listeners act only on a change another node made: the event
must be `TYPE_CHANGED` and the item's URI host, which is the node that wrote it, must differ from
the local node id. The watch's check is `isChangeFromAnotherDevice` in
[`DataEventFilter.kt`][DataEventFilter], and the phone's is the method of the same name in
[`PhoneDataLayerSnapshotEvent.kt`][PhoneDataLayerSnapshotEvent]. When a listener cannot read its
own node id, it acts on every changed event. An ordinary data item may be delayed
by Play services, while an _urgent_ one goes out at once, so anything a person is waiting on is
written as urgent.

A node is _nearby_ when it is connected directly, normally over Bluetooth, and reachable but not
nearby when it is reached only through Google's cloud relay.

## When version 2 is active

Version 2 is gated three times. All three must be true before a version 2 surface does anything.

1. **The feature flag.** `FeatureFlag.WATCH_EXPERIENCE_V2` (stored key
   `watch_experience_v2_enabled`) is switched on the phone. Its default is off unless the build
   sets the `watchExperienceDefault` override: building with the Gradle property
   `logdate.watchExperienceDefault=true` writes the manifest value
   `app.logdate.watchExperienceDefault`, and the flag store in
   [`DatastoreModule.kt`][DatastoreModule] then reads the flag as on until a value is stored for
   it. The phone owns the value: it publishes it in its state document whatever the value is, and
   [`WatchStateMirror`][WatchStateMirror] sets the watch's copy to match. The watch never writes it
   back. This is why the phone publishes its state document even while the flag is off.
2. **The capability.** While its flag is on, each device adds a dynamic capability with
   `CapabilityClient.addLocalCapability` and removes it when the flag goes off.
3. **The feature list.** Each device lists the version 2 features its build implements in its state
   document. A surface is used only when the other device lists the feature it needs.

[`WearProtocolGate`][WearProtocolGate] combines these: `isV2Active()` is the flag and the other
device's capability, and `isFeatureActive(feature)` also requires the feature name in the other
device's latest state document.

### Capabilities

| Capability              | Advertised by | How                              | Meaning                             |
| ----------------------- | ------------- | -------------------------------- | ----------------------------------- |
| `logdate_phone_app`     | Phone         | Static, in `res/values/wear.xml` | LogDate is installed on this phone. |
| `logdate_watch_app`     | Watch         | Static, in `res/values/wear.xml` | LogDate is installed on this watch. |
| `logdate_phone_sync_v2` | Phone         | Dynamic, while the flag is on    | The phone speaks version 2.         |
| `logdate_watch_sync_v2` | Watch         | Dynamic, while the flag is on    | The watch speaks version 2.         |

The appearance of `logdate_watch_sync_v2` is also the phone's cue to link that watch: the phone's
listener receives the capability change and asks `PhoneLinkInitiator` to reconcile, even if the
phone app was not running.

### Features

The names are constants in [`WearFeatures`][WearProtocol]. The lists each build advertises are
`phoneWatchFeatures` in [`PhoneWatchSyncV2Module.kt`][PhoneWatchSyncV2Module] and
`watchSyncFeatures` in [`WearSyncV2Module.kt`][WearSyncV2Module].

| Feature                  | Phone lists it | Watch lists it | What depends on it                                                                                                                                                                                                  |
| ------------------------ | -------------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `receipts.v1`            | Yes            | Yes            | The phone accepts version 2 captures, answers receipt queries and pushes receipts only for a watch that lists it.                                                                                                   |
| `receipts.operations.v1` | Yes            | Yes            | The watch sends notes over version 2 only to a phone that lists it. Marked moments need it too.                                                                                                                     |
| `window.v1`              | Yes            | Yes            | The phone publishes the library window to a watch that lists it, and the watch applies a window only from a phone that lists it.                                                                                    |
| `media.range.v1`         | Yes            | Yes            | The watch asks for audio by byte range only from a phone that lists it.                                                                                                                                             |
| `moments.v1`             | Yes            | Yes            | Marked moments cross only when the other device lists this and `receipts.operations.v1`.                                                                                                                            |
| `live.v1`                | Yes            | Yes            | The phone offers playback continuity only to a watch that lists it.                                                                                                                                                 |
| `link.cloud.v1`          | Yes            | Yes            | The phone links a watch to its account only if the watch lists it; otherwise it pairs without an account.                                                                                                           |
| `phone.playback.v1`      | Yes            | No             | The watch offers "play on phone" only when the phone lists it.                                                                                                                                                      |
| `prefs.v1`               | Yes            | No             | Listed by the phone; no code checks it.                                                                                                                                                                             |
| `reminders.activity.v1`  | No             | Yes            | Tells the phone the watch can read the reminder triggers for an activity that just ended.                                                                                                                           |
| `reminders.v1`           | No             | No             | Coordinating the day's reminder between the two devices. The phone delivers a reminder on a watch, and the watch claims the day's reminder with the phone, only when the other device lists it. Neither build does. |
| `phone.settingsLink.v1`  | No             | No             | The watch setting that opens the phone's watch page uses the phone node only while the phone lists this. The phone does not list it.                                                                                |

A feature name is added to a list only when that build implements the feature, because the other
device treats the name as a promise. Reading the name from the latest state document rather than
from a fixed version number lets either app be updated first.

### Remembered negotiation

Three watch-side checks remember that a feature was negotiated with a specific link, so that losing
sight of the phone does not make the watch fall back to version 1 halfway through:
`WatchCaptureProtocol` (notes), `WatchMomentProtocol` (marked moments) and `WindowProtocol` (the
library window). Each stores the link's identity once the phone's document lists the feature and
keeps answering "active" for that same link while the phone is away. The rule exists because a
fallback would let an old version 1 delete or a late acknowledgement bypass the version 2 checks.

## Message format and versioning rules

Every version 2 message is JSON written through [`WearJson`][WearJson]. The rules are the same for
all of them.

- Unknown keys are ignored, so a newer build can add an optional field inside a schema.
- Each message has an integer `schema`. A missing `schema` reads as `1`.
- Decoding returns one of three results. `Supported` contains the value. `Unsupported` means the
  schema is newer than this build reads; the reader leaves what it has unchanged, logs that it
  skipped the message, and answers `REJECTED_UNSUPPORTED` where the message expects an answer.
  `Malformed` means the bytes are not a message of a known schema.
- Enums inside a schema are closed. A reader that meets an enum value it does not know reports the
  message as malformed, so adding an enum value requires a schema bump. Where a newer device must be
  able to add values without breaking an older one, the field is a string instead (for example
  `WatchState.linkProblem` and the activity kinds).

| Schema          | Constant                       | Value |
| --------------- | ------------------------------ | ----- |
| Record envelope | `WearProtocol.ENVELOPE_SCHEMA` | 1     |
| State document  | `WearProtocol.STATE_SCHEMA`    | 1     |
| Receipts        | `WearProtocol.RECEIPTS_SCHEMA` | 1     |
| Link messages   | `WearProtocol.LINK_SCHEMA`     | 1     |
| Live messages   | `WearProtocol.LIVE_SCHEMA`     | 1     |
| Library window  | `WearProtocol.WINDOW_SCHEMA`   | 2     |

Two size limits come from the Data Layer's data item limit. A state document is at most
`WearProtocol.MAX_STATE_BYTES` (8 × 1024 bytes); the encoder throws for a larger one. A record body
of more than `WearProtocol.MAX_INLINE_BODY_BYTES` (90 × 1024 bytes) is not written inline.

## State documents

Each device publishes one _state document_: a small JSON record that tells the other device what
this one is, what it supports and what both have agreed. It is written as an urgent data item at
`/logdate/state` under the key `doc`. Because Play services stores one item per writing node at
that path, the phone's document and the watch's document coexist, and each device reads the other's
by listing `wear://*/logdate/state` and skipping its own node. The types are in
[`StateDocument.kt`][StateDocument].

A state document replaces a stream of small messages. It survives the other app not running: each
app reads the other's stored document when it starts (`catchUpWithWatches` on the phone,
`catchUpWithPhone` on the watch). The 8 KiB cap keeps it a single cheap item; anything larger
belongs at its own path.

### Who owns which field

Every field has exactly one owner. [`StateMerger`][StateMerger] takes the `phone` section only from
a document whose `role` is `PHONE` and the `watch` section only from a document whose `role` is
`WATCH`, so neither device can overwrite the other's facts.

| Section          | Owner | Fields                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ---------------- | ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `phone`          | Phone | `flags` (feature flag values by key; `watch_experience_v2_enabled` always, `activity_awareness_enabled` only while on), `cloud` (`NO_ACCOUNT`, `SIGNED_IN` or `UNKNOWN`), `campfire` (the streak, meaning the run of consecutive days with something logged, as the phone computed it; the watch draws it as a campfire), `dayStartHour`, `zone` (time zone id), `features`, `reminderHours` (waking hours, present only while reminders are on), `activity` (present only while activity awareness is on), `openMarks` |
| `watch`          | Watch | `link` (`UNLINKED`, `PAIRED`, `LINKED` or `NEEDS_RELINK`), `linkId`, `sessionId`, `linkProblem`, `features`, `activity`, `outboxCount`, `deadLetterCount`, `freeBytes`, `presence`                                                                                                                                                                                                                                                                                                                                      |
| `shared`         | Both  | A map from preference name to `{ value, at }`, where `at` is the writer's clock in epoch milliseconds                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `lastReminderAt` | Both  | When this device last delivered a reminder                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

The campfire and the day-start hour are phone-owned because the watch stores only a short window of
notes and cannot compute a long streak or know a setting made on the phone.

### How shared entries merge

Shared preferences are edited on either device, so they merge by last writer:

- For each key, the entry with the later `at` wins.
- A stamp from the future counts as now, so a device whose clock runs ahead cannot win against a
  later real write.
- A tie goes to the phone.
- `lastReminderAt` is the later of the two values, so the limit of one reminder a day applies
  whichever device delivered it.
- A watch `presence` observed more than 30 minutes ago (`StateMerger.PRESENCE_STALE_AFTER_MS`) reads
  as `UNKNOWN`.

The phone adopts a watch's shared entries only when the watch's document names the link the phone
currently has with that node, so a watch that was removed or belongs to another exchange cannot
change the phone's preferences.

The shared keys are defined in [`SharedPreferenceKey`][SharedPreferenceKey]. A name never changes
once shipped, and a value that cannot be read or is outside what this build allows reads as the
default.

| Key                          | Default            | Allowed values                                                |
| ---------------------------- | ------------------ | ------------------------------------------------------------- |
| `reminders.enabled`          | `false`            | Boolean                                                       |
| `capture.places`             | `false`            | Boolean                                                       |
| `capture.moodAfterRecording` | `true`             | Boolean                                                       |
| `window.days`                | `14`               | `7`, `14` or `30`                                             |
| `window.onThisDay`           | `true`             | Boolean                                                       |
| `window.rewind`              | `true`             | Boolean                                                       |
| `window.audioDownloads`      | `wifi_or_charging` | `wifi_or_charging`, `wifi_only`, `charging_only`, `on_demand` |

The `window.*` keys shape [the library window](#the-library-window), the part of the phone's
library the watch stores, and `window.audioDownloads` controls
[background prefetch](#background-prefetch) of its audio. What each setting means to a person is
described in [The watch companion](../../explanation/wear-companion.md#shared-preferences).

## Linking a watch to a phone

A _link_ is a verified exchange between one phone node and one watch node. Every later version 2
message names the link it belongs to, so a message from another phone, from before a relink, or for
another account is ignored. There are two modes:

- `PAIR` shares state between the two devices with no account. The phone uses it when it is not
  signed in, when its session belongs to a different server than the one the app is currently
  configured for, when its server does not advertise `deviceLinkingV1`, when it has no identity key
  it can use for the account, or when the watch does not list `link.cloud.v1`.
- `CLOUD` also gives the watch the account's keys and its own session with the LogDate server, so
  it can back up by itself. There is no sign-in on the watch.

[`CloudLinkBundleSource`][CloudLinkBundleSource] makes this choice in two steps, because the
account can change while an exchange is running. `plan` picks the mode before the offer is sent,
and falls back to `PAIR` if the session, the configured server or the server's description changes
while it is deciding. `bundle` builds the contents after the watch accepts the offer and checks
again. It does not build a `CLOUD` bundle while the phone's feature flag is off
(`WATCH_CANNOT_LINK`), after the session or the server changed (`SIGNED_OUT`), when the identity or
media key cannot be read (`KEYS_UNAVAILABLE`), or when the server does not issue a link code
(`DEVICE_LIMIT_REACHED` when the account has too many devices, otherwise `LINK_CODE_UNAVAILABLE`).
The exchange then ends with that failure and is tried again on the schedule in
[When the phone links again](#when-the-phone-links-again).

The phone always opens the exchange and the watch only answers. This leaves the decision with the
device that is signed in to the account and has a full screen for anything that needs a person, and
it means the watch never has to discover which phone to ask.

### The exchange

[`PhoneLinkInitiator`][PhoneLinkInitiator] runs the phone's side, one exchange at a time, and
[`WatchLinkCoordinator`][WatchLinkCoordinator] answers. The cryptography is in
[`LinkHandshake.kt`][LinkHandshake] with the Android implementation in
[`BouncyCastleLinkHandshake`][BouncyCastleLinkHandshake]; the message types are in
[`LinkMessages.kt`][LinkMessages]. Keys, nonces, IVs and MACs travel as standard Base64.

1. **Offer.** The phone generates a single-use X25519 key pair and a 16-byte random nonce and sends
   a `LinkOffer` with `MessageClient.sendRequest` to `/logdate/link/offer`. The offer has the mode,
   the phone's node id, public key and nonce, `issuedAt` and `expiresAt`. An offer is valid for at
   most 60 seconds (`WearProtocol.LINK_OFFER_TTL_MS`); one that claims a longer window is never
   valid.
2. **Accept.** The watch checks the schema, the validity window, that the key is 32 bytes and the
   nonce 16 bytes, and that it has not accepted this nonce before (`LinkNonceLedger`). It generates
   its own key pair and nonce and answers with a `LinkAccept`.
3. **Transcript.** Both sides hash everything they saw. The transcript is the ASCII label
   `LDLINK1`, then each of the phone node id, watch node id, phone public key, watch public key,
   phone nonce and watch nonce as a 4-byte big-endian length followed by its bytes, then the schema
   as a 4-byte big-endian integer. The transcript hash is the SHA-256 of those bytes. The length
   prefixes keep two different field splits from encoding to the same bytes. Each side puts the
   node id Play services reported for the other device into the transcript, not the id the message
   claims, so an answer relayed from a third node produces a different hash and cannot open the
   bundle.
4. **Keys.** Each side computes the X25519 shared secret (a result of all zeros is refused) and
   derives three 32-byte keys with HKDF-SHA256, using the shared secret as input key material, the
   transcript hash as salt, and one label each as info: `LDLINK1 bundle`, `LDLINK1 confirm` and
   `LDLINK1 pair`. Salting with the transcript means keys from one exchange never open anything
   from another.
5. **Bundle.** The phone encrypts a `LinkBundle` with AES-256-GCM under the bundle key, with a
   random 12-byte IV, a 128-bit tag and the transcript hash as associated data, and sends the
   resulting `SealedLinkBundle` to `/logdate/link/bundle`.
6. **Confirm.** The watch opens the bundle, checks that its mode matches the offer, and answers
   with a `LinkConfirm` whose `mac` is HMAC-SHA256 under the confirm key over the label
   `LDLINK1 confirm` followed by the transcript hash. The phone compares it in constant time.

Each request times out after 10 seconds. A refusal is a `LinkRefused` with one of `EXPIRED_OFFER`,
`REPLAYED_NONCE`, `BOUND_TO_ANOTHER_ACCOUNT` or `UNSUPPORTED`.

Nothing in the exchange is ever written to a data item. Data items are stored by Play services and
copied to every paired node, while a request exists only for its round trip.

`BouncyCastleLinkHandshakeTest` pins the key agreement and key derivation against the RFC 7748
(X25519) and RFC 5869 (HKDF) test vectors, and checks that a changed ciphertext, initialization
vector or transcript fails to open the bundle.

### The link identity

The Base64 of the transcript hash is the _link id_. It is public: it contains no key material. Both
devices store it, the watch publishes it in its state document, and it appears in every later
message inside a [`PeerDeliveryContext`][PeerDeliveryContext]:

```json
{
  "linkId": "<Base64 of the 32-byte transcript hash>",
  "phoneNode": "<phone node id>",
  "watchNode": "<watch node id>",
  "mode": "CLOUD",
  "accountId": "<account id, CLOUD only>",
  "serverOrigin": "<server origin, CLOUD only>"
}
```

A context is valid only if the link id decodes to 32 bytes, the two node ids differ, and the account
fields are both present for `CLOUD` and both absent for `PAIR`. Where a link id appears in a path it
is spelled URL-safe: `+` becomes `-`, `/` becomes `_` and the trailing `=` is dropped, giving 43
characters.

On each device a context is _current_ only while it still matches the stored link record, the
local node id, the signed-in session and the configured server (`PhoneDeliveryPeers` on the phone,
`WatchDeliveryPeer` on the watch). Code rechecks this before and after every step that waits, such
as a network call or a database write, because the account or the link can change while it waits.

The pair key is saved in secure storage on both devices after the exchange. No message is signed
with it; see [Known limits](#known-limits).

### What the bundle contains

For `PAIR` the bundle has only the mode and the flag values. For `CLOUD` it also has the account
id, the server origin and descriptor, a single-use link code and its expiry, the account's identity
key with its fingerprint, and the media key.

The bundle never contains the recovery phrase. The phrase can recover the account itself, and a
watch must not have that power. The watch can only ever use the key the phone installed: on the
watch the sync engine never creates an identity key, and a watch without one waits to be linked.
See [Encryption](../../explanation/encryption.md) for what those keys protect.

### Installing an account link

After the watch confirms a `CLOUD` bundle, [`LinkedDeviceInstaller`][LinkedDeviceInstaller] turns
it into keys, a session, an owner and a server, writing a journal entry before each step so a
stopped process can resume: install the identity and media keys, redeem the link code at the
account's server for the watch's own session, save the session, move what the watch recorded under
its earlier owner to the account, then adopt the owner and server. The link code is never written
down. A process stopped before the code is redeemed abandons the install, and the phone links again
with a new code. The server issues each code with a 120-second lifetime
(`WearProtocol.LINK_CODE_TTL_MS` records the same value) and it can be redeemed once.

Before it confirms, the watch checks that it can take the bundle at all. It refuses with
`BOUND_TO_ANOTHER_ACCOUNT` when it already belongs to a different account or has a different
identity or media key, and it refuses a `PAIR` offer once it has account setup, so a link can never
silently move a watch between accounts or strip its account.

The watch reports the outcome in its state document: `link` becomes `LINKED`, or `NEEDS_RELINK`
with a `linkProblem` naming why (`INCOMPLETE_BUNDLE`, `LINK_CODE_EXPIRED`, `LINK_CODE_INVALID`,
`DEVICE_LIMIT_REACHED`, `BOUND_TO_ANOTHER_ACCOUNT`, `KEY_CONFLICT`, `SERVER_REFUSED`, `NETWORK`,
`INTERRUPTED` or `SESSION_REVOKED`).

### When the phone links again

[`PhoneLinkDecision`][PhoneLinkDecision] decides per watch. The phone runs a new exchange when it
has no record for the watch, when the plan changed (mode, account or server), when the stored link
id is unusable, when the last attempt failed and its retry time has come, or when a fresh watch
document reports `UNLINKED` or `NEEDS_RELINK` at least two minutes after the last attempt
(`RELINK_GRACE_MS`; a report written sooner is the watch's state from before the exchange).

A failed attempt is retried after 60 seconds, doubling each time to a ceiling of six hours
(`PhoneWatchLinkStore.FIRST_WAIT_MS`, `MAX_DOUBLINGS`, `MAX_WAIT_MS`).

The phone tells the person about linking through [`WatchLinkNotifier`][WatchLinkNotifier], and
only in two situations.

- **One confirmation when a link works.** For a `PAIR` link the phone posts it as soon as the
  exchange succeeds. For a `CLOUD` link it waits until a fresh watch document reports `LINKED`
  under the link id the phone stored, because only then has the watch installed the account. Each
  link is confirmed once.
- **The three failures a person can fix.** The watch belongs to another account, the watch has a
  conflicting key, or the account's device limit is reached. Each is shown once, and can be shown
  again only after the link has been confirmed or the plan (mode, account or server) has changed.

Every other failure is logged and recorded as state, with no notification. All link notifications
for one watch use the same notification slot, so a newer one replaces the older.

When the watch is paired with a different phone, Play services calls `onNodeMigrated`; the watch
marks its link stale, reports `NEEDS_RELINK` and waits for the new phone to link it.

## Sending captures from the watch to the phone

A _capture_ is a note or a marked moment made on the watch. The _outbox_ is a device's local queue
of changes that have not been delivered yet. The watch queues each change in its outbox as an
_operation_ with its own operation id, so two edits with the same timestamp are still told apart. Delivery is implemented by [`WatchNoteDelivery`][WatchNoteDelivery] and
[`WatchMomentDelivery`][WatchMomentDelivery]; the routing rules are the pure function
[`WatchSyncRouter`][WatchSyncRouter].

### The envelope

A capture crosses as a [`RecordEnvelope`][RecordEnvelope], written as an urgent data item with two
keys: `envelope` (the JSON) and `sentAt` (epoch milliseconds). `sentAt` exists because the Data
Layer drops a put identical to the stored item; changing it turns a resend into a real change.

```json
{
  "schema": 1,
  "kind": "NOTE",
  "id": "<record UUID>",
  "contentVersion": 1760000000000,
  "syncVersion": 0,
  "origin": "WATCH",
  "priority": "URGENT",
  "body": { "…": "the note, in the same JSON version 1 sends" },
  "media": [{ "sha256": "<64 hex>", "sizeBytes": 123456, "mimeType": "audio/mp4" }],
  "context": { "…": "the PeerDeliveryContext" },
  "operationId": "<operation id>"
}
```

- `contentVersion` is the record's `lastUpdated` in epoch milliseconds. Receipts name it, so a
  receipt for an older version never clears a newer edit.
- `syncVersion` is the LogDate server's version of the record, or `0` when it has none yet.
- `kind` is one of `NOTE`, `JOURNAL`, `ASSOCIATION` or `MOMENT`. Only `NOTE` and `MOMENT` are sent.
- A deletion is an envelope with `removed: true`, `contentVersion` 0 and no body.
- `enc` is reserved for end-to-end encrypted envelopes. Schema 1 never sets it, and a schema 1
  reader answers an envelope that does as unsupported.
- An envelope has its body inline or by reference (`bodyRef`), never both.

The paths, built by [`WearPaths`][WearPaths]:

| Path                                                         | Primitive                 | Content               |
| ------------------------------------------------------------ | ------------------------- | --------------------- |
| `/logdate/v2/w2p/record/<kind>/<id>/<link>/op/<operationId>` | Data item, watch to phone | The envelope          |
| `/logdate/v2/w2p/media/note/<id>/<link>/op/<operationId>`    | Channel, watch to phone   | The recording's bytes |

`<kind>` is `note`, `journal`, `association` or `moment`, `<link>` is the URL-safe link id, and an
operation id matches `[A-Za-z0-9_-]{1,128}`. The phone validates the source node, the link in the
path and the context in the envelope before it stores anything, and it stores a recording only once
its size and SHA-256 match the envelope's `media` entry.

### The delivery ledger

Both devices keep a row per capture in the Room table `peer_delivery`, through
[`PeerDeliveryLedger`][PeerDeliveryLedger]. The ledger makes each routing decision survive the
process being stopped. A row only ever moves forward, and a newer version of a record starts over.

| State              | Meaning                                                                    |
| ------------------ | -------------------------------------------------------------------------- |
| `NEW`              | Not sent yet.                                                              |
| `SENT`             | Written to the Data Layer; no receipt yet.                                 |
| `ON_PHONE`         | The phone stored it.                                                       |
| `BACKED_UP`        | The LogDate server has it. Final.                                          |
| `CLOUD_PENDING`    | Released to the watch's own cloud engine.                                  |
| `DELETED_ON_PHONE` | The phone no longer has it. Final.                                         |
| `REJECTED`         | The phone cannot read it; the watch still has it and may upload it itself. |

A row that is waiting names what is missing in `holdReason`, so status can say what to fix:
`PHONE_UNREACHABLE`, `AWAITING_RECEIPT`, `PHONE_BACKING_UP`, `NOT_LINKED`, `SERVER_TOO_OLD`,
`NO_INTERNET`, `BLUETOOTH_ONLY`, `AUDIO_WAITS_FOR_WIFI`, `PHONE_CANNOT_READ`, `EDIT_NEEDS_PHONE` or
`SEND_FAILED`.

The ledger scope is `link:<linkId>`, so history from one link never settles a capture under
another. The watch's rows have direction `TO_PHONE`; the phone's have `FROM_WATCH`.

### Receipts

A _receipt_ is the phone's statement of what it did with one version of one operation. The watch
asks with a `ReceiptsRequest` to `/logdate/v2/receipts` at the start of each delivery run, and the
phone also pushes a `ReceiptsResponse` as a message to `/logdate/v2/receipts/push` when a capture
is stored or backed up. Pushes are best effort; a lost one is recovered by the next query.

| Receipt state          | Meaning                                                                                                                                                                  |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ON_PHONE`             | Stored on the phone.                                                                                                                                                     |
| `BACKED_UP`            | Stored, the phone is signed in, the record has a server version greater than zero, and nothing for it waits in the phone's outbox. The receipt includes `serverVersion`. |
| `DELETED_ON_PHONE`     | The phone received it and no longer has it.                                                                                                                              |
| `REJECTED_UNSUPPORTED` | The phone cannot read this kind or schema.                                                                                                                               |

A response also says whether the phone backs up at all (`cloud`: `NO_ACCOUNT`, `SIGNED_IN` or
`UNKNOWN`), which tells the watch whether `ON_PHONE` is final. The watch applies a receipt only
when it comes from the linked phone node, names the current context, has a `cloud` value consistent
with the link mode, and is for the captured version or a later one. A `BACKED_UP` receipt without a
positive server version is ignored.

### Routing rules

The phone is the preferred path. The watch uploads to the server itself only as a fallback, and only
creations and deletions.

| Ledger state                    | Decision                                                                                                                                                                                                                                      |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `BACKED_UP`, `DELETED_ON_PHONE` | Settle: remove the operation from the outbox and delete the watch's data item.                                                                                                                                                                |
| `NEW`                           | Phone nearby: send to the phone. Phone reachable only by relay: send to the phone, unless the capture has audio and a direct upload is allowed, in which case upload directly. Phone unreachable: upload directly if allowed, otherwise wait. |
| `SENT`                          | Wait up to 30 seconds for a receipt, then decide again as for `NEW`.                                                                                                                                                                          |
| `ON_PHONE`                      | Phone has no account: settle. Phone's account state unknown: wait for a receipt. Phone signed in: wait 15 minutes for the phone to back it up, then upload directly if allowed, otherwise go on waiting for the phone's backup.               |
| `CLOUD_PENDING`                 | Upload directly if allowed, otherwise wait.                                                                                                                                                                                                   |
| `REJECTED`                      | Upload directly if allowed, otherwise wait with `PHONE_CANNOT_READ`.                                                                                                                                                                          |

A direct upload is allowed only when all of these are true. They are checked in this order, and
the first one that fails is the _blocker_: the reason the direct upload is refused.

1. The operation is a creation or a deletion. An edit always waits for the phone
   (`EDIT_NEEDS_PHONE`), because only the phone resolves edits against the server.
2. The link is a `CLOUD` link (`NOT_LINKED`).
3. The server advertises both `deviceLinkingV1` and `conditionalCreateV1` (`SERVER_TOO_OLD`).
   Marked moments also require `momentsV1`. Conditional create is what lets the watch upload without
   overwriting a copy the phone uploaded first.
4. The watch has a network the system has validated (`NO_INTERNET`). An unvalidated network may be
   a captive portal and counts as none.
5. The network is not the Bluetooth proxy through the phone (`BLUETOOTH_ONLY`). That route is slow
   and uses the phone's data connection, and if the phone is that close it is the better path
   anyway.
6. For a capture with audio on a metered network, the watch is charging
   (`AUDIO_WAITS_FOR_WIFI`).

The blocker is not always the hold reason the row records. Which reason the router reports depends
on the row's state and on whether the phone is in reach:

| Situation                                                                       | Hold reason recorded                                                                            |
| ------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `CLOUD_PENDING` row with a blocker                                              | The blocker itself                                                                              |
| `NEW` or timed-out `SENT` row, phone unreachable, blocker is `NOT_LINKED`       | `PHONE_UNREACHABLE`, because a watch without an account link can deliver only through its phone |
| `NEW` or timed-out `SENT` row, phone unreachable, any other blocker             | The blocker itself                                                                              |
| `SENT` row within the 30-second receipt wait                                    | `AWAITING_RECEIPT`                                                                              |
| `ON_PHONE` row, phone's account state unknown                                   | `AWAITING_RECEIPT`                                                                              |
| `ON_PHONE` row, phone signed in, within the 15-minute grace or with any blocker | `PHONE_BACKING_UP`                                                                              |
| `REJECTED` row with any blocker                                                 | `PHONE_CANNOT_READ`                                                                             |

While the phone is nearby or reachable by relay, a `NEW` or timed-out `SENT` row is sent to the
phone whatever the blocker is, so no hold reason comes from the router. `SEND_FAILED` is recorded
by delivery when a send to the phone fails, not by the router.

When the phone has no account, the watch settles a capture as soon as it has a fresh `ON_PHONE`
receipt for that exact operation in the same run. A remembered "no account" without that proof is
treated as unknown.

### Timings

| What                                                   | Value                                        | Constant                          |
| ------------------------------------------------------ | -------------------------------------------- | --------------------------------- |
| Wait for a receipt after sending                       | 30 seconds                                   | `WatchSyncRouter.RECEIPT_WAIT_MS` |
| Wait for the phone to back up before the watch uploads | 15 minutes                                   | `WatchSyncRouter.BACKUP_GRACE_MS` |
| Retry a failed send                                    | 30 seconds, doubling to a ceiling of 6 hours | `HoldTimes.sendRetryDelay`        |
| Request timeout to the phone                           | 10 seconds                                   | `LinkedDataLayerTransportV2`      |

A row that is waiting is looked at again at a time that depends on its hold reason
(`HoldTimes.recheckAt`):

| Hold reason                                             | Looked at again                                                                                                  |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `AWAITING_RECEIPT`                                      | 30 seconds after the last send, or after the row last changed state if it was never sent                         |
| `PHONE_BACKING_UP`                                      | 15 minutes after the row became `ON_PHONE`                                                                       |
| `NO_INTERNET`, `BLUETOOTH_ONLY`, `AUDIO_WAITS_FOR_WIFI` | 15 minutes from now (`HoldTimes.RECHECK_MS`)                                                                     |
| `SEND_FAILED`                                           | After the send retry delay                                                                                       |
| Every other reason                                      | No timer. The row is looked at again on the next delivery run, for example when the phone comes back into reach. |

When a receipt or backup deadline has already passed and the row is still waiting, the next look
is 15 minutes from now (`HoldTimes.RECHECK_MS`).

[`WatchSyncScheduler`][WatchSyncScheduler] schedules one unique WorkManager job, `watch_sync`, for
the earliest of these deadlines, so a retry happens on time even when nothing else starts the app.
WorkManager restores the job after a reboot.

### What the phone does when the watch got there first

A linked watch may have uploaded a capture before the phone tries to. The phone then finds the
record already on the server. [`WatchCaptureCollisionPolicy`][WatchCaptureCollisionPolicy] tells the
phone's uploader that a note it received from a watch was created elsewhere, so the refused
creation is settled instead of retried as a failure. An edit the phone makes after receiving the
note goes up as an ordinary edit, so the phone's newer edit becomes the server's latest.

### Captures the phone cannot take

A body over the inline limit cannot be written as a capture envelope. The watch moves that capture
to `REJECTED`; a linked watch then uploads it itself, and a paired watch waits with
`PHONE_CANNOT_READ`.

## The watch's own cloud engine

A watch with a `CLOUD` link runs the same sync engine the phone uses,
`DefaultSyncManager`, configured with [`SyncProfile.LinkedWatch`][SyncProfile] in
[`WearCloudSyncModule.kt`][WearCloudSyncModule]. The profile changes the engine's behavior in these
ways:

| Behavior                         | Linked watch                         | Reason                                                                                                                                                                                                                                                                                           |
| -------------------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Create an identity key           | Never                                | The watch uses only the key its phone installed. Without one, sync pauses with `NEEDS_DEVICE_LINK`.                                                                                                                                                                                              |
| Queue everything on first sync   | No                                   | A phone's first sync with a server queues all it has stored. The watch does not: a capture enters the outbox when it is made, and the router releases captures to the engine one by one.                                                                                                         |
| Upload edits                     | No                                   | Edits go through the phone.                                                                                                                                                                                                                                                                      |
| Upload creations                 | As create-if-absent                  | A record the phone already uploaded is never overwritten. If the server already has the record, the watch counts its creation as done and leaves its local version alone. If the server answers that the record was deleted, the watch removes its own copy instead of bringing the record back. |
| Download media with records      | No                                   | Media arrives through the library window.                                                                                                                                                                                                                                                        |
| Sync drafts and location history | No                                   | The watch has neither.                                                                                                                                                                                                                                                                           |
| Store a note only the server has | Only if captured in the last 14 days | The phone's library window supplies the rest. This applies to downloads, which the watch does not run.                                                                                                                                                                                           |

The engine sees only the operations the router released to it: [`ReleasedToCloud`][ReleasedToCloud]
answers "eligible" for an operation only while its ledger row is `CLOUD_PENDING` with no hold
reason, for the exact content version captured in the current run, under the current link.

The engine's stores live under the key prefix `watch_cloud.`, apart from version 1's. Its status is
combined with the phone path's by `WatchSyncFacade`: the worse problem is reported, both paths count
the same outbox so the larger count is the number waiting, and "last synced" is the later of the two.

The watch calls only `uploadPendingChanges()` on this engine, plus one account read after
linking. It never runs a download.

When the server reports that the watch's session was revoked, the engine's revocation listener
erases the watch's LogDate data. Removal and erase are described in
[The watch companion](../../explanation/wear-companion.md).

## The library window

The _library window_ is the bounded part of the phone's library that the watch stores: recent
notes, starred notes, "on this day" notes and the notes behind this week's Rewind. A _Rewind_ is
the weekly summary of a person's notes that the phone builds and saves. The phone owns
it completely. The watch never decides what is in it and never edits a window note in place; a
change made on the watch is a capture that goes through delivery.

### What the phone chooses

[`WatchWindowPolicy`][WatchWindowPolicy] picks the members. Days are _logical days_: a day runs
from the phone's day-start hour to the same hour the next morning in the phone's time zone, the
same way streaks count days.

A note qualifies for one or more reasons:

| Reason        | Rule                                                                                                                                                                                                                                   |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RECENT`      | Captured within the last `window.days` logical days, counting today (default 14).                                                                                                                                                      |
| `ON_THIS_DAY` | Captured on the same month and day in an earlier year, or within three days either side of the date one year ago. On February 28 of a year without a February 29, notes from February 29 also count. Controlled by `window.onThisDay`. |
| `STARRED`     | The note is a favorite.                                                                                                                                                                                                                |
| `REWIND`      | The note is a source of the saved Rewind for the current week. Controlled by `window.rewind`. The watch never generates a Rewind.                                                                                                      |

The budget is 400 records and 8 MiB of note bodies (`WindowBudget`). When it runs out, members are
chosen in the order recent, starred, on this day, Rewind, and within a tier the newest first, because
that is the order in which someone is most likely to look for them on the wrist. A note that does
not fit is skipped and counted, and smaller notes after it are still considered. Media does not
count toward the budget.

### How the phone publishes

[`WatchWindowPublisher`][WatchWindowPublisher] writes one data item per member and a removal marker
(a _tombstone_) for each record that left.

| Path                                           | Content                                                                                                                               |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `/logdate/v2/p2w/window/<kind>/<id>/<link>`    | Keys `envelope`, `sentAt` and `windowRevision`. The envelope has `origin: "PHONE"`, the reasons, and the body inline or by reference. |
| `/logdate/v2/p2w/tombstone/<kind>/<id>/<link>` | Keys `removedAt`, `context`, `windowRevision`, and `deletedAt` only when the record was deleted rather than moved out of the window.  |

Every write takes the next _revision_, a counter the phone keeps per link. Revisions let the watch
order an item against a tombstone for the same record without trusting arrival order.

The phone publishes two seconds after any change to its notes, marks, favorites, Rewinds, link
records, session, server or window preferences, or to a watch's state document, and again at each
day start, when the window shifts with no new notes. An unchanged member is not rewritten: its
`sentAt` is its content version, so a repeat is byte-identical and the Data Layer sends nothing.
Window items are not urgent; tombstones are. A failed publish is retried after 60 seconds.

The phone records what it published in `WindowManifestStore`. A tombstone stays in the Data Layer
for seven days (`TOMBSTONE_KEEP_MS`) and is then deleted. A week is long enough for a watch that
was briefly away to see it; a watch away longer learns the difference from a full resync.

### How the watch applies it

[`WindowApplier`][WindowApplier] applies live items and tombstones as they arrive, and
[`WindowReconciler`][WindowReconciler] compares the watch against the whole window.

The watch asks for the whole window with a `WindowResyncRequest` to `/logdate/v2/window/resync`.
The phone publishes first and then answers with a `WindowResyncResponse` listing every member as
`{ kind, id, contentVersion, revision }` along with the window's current `revision`. The watch
resyncs two seconds after it starts and after any change to its link, the phone's state document,
the feature flag, its session, its server or its outbox. It also resyncs after any batch of window
events and when the phone comes back into reach.

These rules protect what the watch has stored:

- **A partial view never deletes.** The response can arrive before its data items. If any listed
  member is neither in the items the watch can read nor already stored at that exact revision and
  version, the watch applies nothing and waits for the next resync.
- **Local work wins.** A record with a pending operation in the watch's outbox is neither
  overwritten nor removed by the window.
- **The window only replaces what it put there.** The watch remembers a fingerprint of each note it
  stored from the window. It overwrites or removes a local note only if the note still matches a
  remembered fingerprint, so a capture made on the watch is never adopted or deleted as if it were
  a window copy.
- **Older revisions lose.** An item or tombstone at or below a revision the watch already applied
  for that record is ignored.
- **Bounds are checked.** A response with more than 400 members, a duplicate member, a member
  revision above the response's revision, or a revision lower than the last snapshot is refused.
- **Everything is tied to the link.** Each item must come from the linked phone node, have the
  link id in its path and the current context in its envelope.

Once negotiated, the window replaces version 1's phone-to-watch note copies: the watch ignores
version 1 note and note-delete data items while the window is active, and the phone answers a
version 1 sync request from that watch by publishing the window instead of every note.

### Bodies too large for a data item

A window body over the 90 × 1024 byte inline limit is published by reference: the envelope has a
`bodyRef` with the body's SHA-256 and size and no inline body. The watch fetches it with a
`WindowBodyRequest` to `/logdate/v2/window/body`, the phone answers with a `WindowBodyTicket`
naming a channel under `/logdate/v2/p2w/window-body/`, and the bytes arrive over that channel
through the same verified range client that audio uses. A body is at most 8 MiB
(`WindowBodyPaths.MAX_BODY_BYTES`).

A member whose body could not be fetched and verified stays unreadable for that pass. Because of the
partial-view rule above, a snapshot with an unreadable member cannot authorize removing anything.

## Audio streaming and prefetch

Window notes arrive without their audio. The watch gets a recording's bytes from the phone in one of
two ways: when someone plays the note, or ahead of time in the background. Both go through
[`WatchPhoneMedia`][WatchPhoneMedia] on the watch and [`PhoneMediaServer`][PhoneMediaServer] on the
phone, and both store into the same cache.

### The exchange

1. The watch sends a `PhoneMediaInfoRequest` (context, note id, content version) to
   `/logdate/v2/media/info`. The phone answers with a `PhoneMediaDescriptor`: the same fields plus
   the file's `sizeBytes`, `sha256` and logical `mediaRef`.
2. The watch sends a `PhoneMediaRangeRequest` (the descriptor and a byte `offset`) to
   `/logdate/v2/media/range`. The phone answers with a `PhoneMediaRangeTicket` naming a one-time
   channel path `/logdate/v2/p2w/media/<random UUID>`.
3. The watch opens that channel, and the phone sends the file from the offset with
   `ChannelClient.sendFile`.

A request payload is at most 8192 bytes. A ticket is valid for 60 seconds and can be used once, and
the phone allows at most 128 outstanding tickets.

### Invariants

- **The watch never names a file.** A request identifies a note and a version. The phone resolves
  the file from its own note record, so a remote path is never used as a local path.
- **A stream is pinned to one publication.** The descriptor fixes the note version, the byte length
  and the digest. The watch rejects a channel whose identity, source node or starting offset
  differs from what it asked for, bytes beyond the published size, a stream that ends early, and a
  complete file whose SHA-256 does not match.
- **Both sides recheck around every wait.** The phone checks the link and the note when it
  describes, when it issues a ticket, when the channel opens and while it sends. The watch checks
  that the link is current and that the note still has the same version and media reference before
  and after each network step.
- **An interrupted transfer resumes.** The watch reopens at the offset it has received, at most
  twice per transfer. It gives up after 15 seconds with no progress, including a stalled open or
  read, and closes the channel on success, failure, mismatch and cancellation.
- **No fallback to an unverified source.** With version 2 on, audio for a note the window manages
  is fetched only from the linked phone through this exchange. It never falls back to another
  nearby phone or to version 1's unscoped audio request. Only a recording the watch itself made,
  that the window does not manage, plays through the version 1 resolver.
- **Playing does not edit.** Playback reads through a `logdate-watch-media://<token>` URI that maps
  to the pinned identity. The note keeps its logical media reference and stays owned by the window,
  so the phone can still update or remove it. A token is valid for six hours, and the watch tracks
  at most 64 at a time.
- **Downloads never crowd out recordings.** [`SyncedAudioWatchMediaCache`][WatchMediaCache] stores
  phone audio only while more than 200 MiB are free (`DEFAULT_RESERVE_BYTES`), checks the remaining
  budget before every chunk, and keeps downloaded audio in a directory separate from the watch's own
  recordings so crash recovery never mistakes a download for an interrupted recording.
- **Eviction cancels downloads.** Removing a note's audio invalidates every download of it that is
  running or queued, including one with no completed file yet. Concurrent downloads never share a
  temporary file, and a download is written to a `.part` file and renamed only once complete.
- **A cached file is re-verified.** Before reusing a stored file the watch checks its length and
  SHA-256 against the saved descriptor.

### Background prefetch

[`WatchMediaPrefetchScheduler`][WatchMediaPrefetchScheduler] schedules hourly WorkManager jobs
according to the shared preference `window.audioDownloads`:

| Setting                      | Jobs          | Runs when                                                                         |
| ---------------------------- | ------------- | --------------------------------------------------------------------------------- |
| `wifi_or_charging` (default) | Both          | On a validated unmetered network, or while charging with any validated connection |
| `wifi_only`                  | Unmetered job | On a validated unmetered network                                                  |
| `charging_only`              | Charging job  | While charging with any validated connection                                      |
| `on_demand`                  | None          | Never in the background; tapping play still works                                 |

WorkManager stops a job when its constraint is lost, and the prefetcher reads the conditions again
before each note and at each chunk, so a change of network cost or unplugging stops the download.
While Data Saver is on, a metered connection does not count as a connection for this purpose. The
prefetcher downloads audio only for notes the current window lists, most recently updated first,
skips a note with pending local work, and stops when the link, the flag or the window membership
changes. Running out of room
ends the run without an error, to preserve space for recordings.

## Marked moments

A _marked moment_, or mark, is a one-tap placeholder made on the watch: a time, optionally a place
and a heart rate, to be filled in later. Marks are stored in their own table and synced as their
own record kind (`MOMENT`). They are never encoded as notes, because a mark changes state on both
devices and needs a merge that a note does not.

A mark has one of three states, ordered `OPEN` < `DISMISSED` < `FILLED`, a caption, and the set of
notes that resolved it (`resolvedNoteIds`).

### The merge

Every path that brings a copy of a mark onto a device merges it through
[`MarkedMomentConflictResolver`][MarkedMomentConflictResolver], so the result is the same whichever
path a copy arrives by. The resolver has two callers. The mark repository
(`OfflineFirstMarkedMomentRepository.applyFromSync`) uses it for a mark the phone receives from the
watch, for a window member the watch receives from the phone, for both cloud download paths and
for the copy the server returns when an upload collides. The personal-space record kind
(`markedMomentSpaceKind`) uses it to decide between a local and a remote record.

- Two copies merge only if they are the same capture: `id`, `capturedAt` and `originDeviceId` must
  match. Otherwise the merge is refused.
- The state is the higher of the two. An older `OPEN` copy can never reopen a mark that was
  dismissed or filled.
- `resolvedNoteIds` is the union, so notes attached on two devices at once both remain.
- `syncVersion` is the higher of the two.
- `capturedDuring` is what the person was doing when they made the mark, recorded once at capture
  while activity awareness was on. A copy that has it keeps it. If two copies differ, the value
  declared first in `CaptureActivity` wins, which gives the same answer in either order.
- The scalar fields (caption, time zone, location, heart rate) are taken together from the copy
  with the later `lastUpdated`. A tie is broken by comparing those fields in a fixed order, so both
  devices pick the same copy. A newer full copy can therefore clear an optional field on purpose.

The state, the resolved notes, the sync version and `capturedDuring` are joined independently and
never influence which copy's scalars win. That separation is what makes the merge commutative,
associative and idempotent: copies can arrive in any order, any number of times, and every device
converges.

The peer envelope body and the repository store clocks at millisecond precision. The encrypted
cloud payload keeps the precise clock, and copies are normalized to milliseconds before the capture
identity is compared.

### How marks travel

- **Watch to phone.** `WatchMomentDelivery` sends a mark as an envelope of kind `MOMENT` at
  `/logdate/v2/w2p/record/moment/<id>/<link>/op/<operationId>`, with the same ledger, receipts and
  router as notes. A mark has no media. Queued marks wait with `PHONE_CANNOT_READ` until the phone
  lists both `moments.v1` and `receipts.operations.v1`.
- **Phone to watch.** The phone publishes marks as window members of kind `MOMENT`, chosen by the
  same recency and "on this day" rules as notes and using whatever is left of the note window's
  count and byte budget. This is how the watch receives the phone's merged copy and learns that a
  mark was filled or dismissed.
- **Deletion versus eviction.** A tombstone with `deletedAt` means the mark was deleted on the
  phone, and the watch deletes it and replaces any pending work for it with a deletion. A tombstone
  without `deletedAt` means the mark only left the window, and the watch keeps its durable capture
  row and any pending work.
- **To the cloud.** Marks upload and download through the sync engine as encrypted payloads; the
  server limits are in the [device sessions API](../../../server/docs/device-sessions-api.md). A
  linked watch uploads only creations and deletions, as for notes.

Marks have two cloud download paths, and [`MomentDownloader`][MomentDownloader] picks one per
run. It reads from the account's personal space when that transport is active for marks on this
device, and from the server's moment route otherwise. What makes the transport active is described
in [Personal space sync](../personal-space-sync.md).

- **The server's moment route.** A deletion is applied only from an explicit deletion entry that
  states the record's version and deletion time; an entry without either is reported as a server
  error and skipped. A remote mark this build cannot read stays in the download inbox for a later
  attempt. A missing or unreadable mark is therefore never treated as a deletion on this path.
- **The personal space.** A deletion arrives as a staged deletion of the mark's record. It
  contains only the mark's id, so the device uses the time the page was read as the deletion time
  and the version of its own stored copy (at least 1) as the version. A record the device cannot
  read is staged as unreadable and tried again later, not deleted. When the device's cursor is too
  old for the server's change feed, the device lists the whole space again and stages a deletion
  for each record the complete listing no longer contains; the rules are in
  [Personal space sync](../personal-space-sync.md#when-the-cursor-is-too-old).

On both paths a mark that was merged with local content the server does not have yet is queued for
upload again, and a deleted mark stays deleted: a later copy of it is not applied.

## Compatibility with version 1

Version 1 is the protocol for journals, journal links and health snapshots, the fallback for
notes whenever version 2 is not negotiated, and the only protocol an older build of either app
speaks. Its wire format must stay readable by every build that is still installed.

### Paths

| Path                                                                                                                           | Primitive and direction    | Content                                                                                                                                                 |
| ------------------------------------------------------------------------------------------------------------------------------ | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/logdate/notes/<id>`                                                                                                          | Data item, both directions | Keys `uid`, `noteType`, `jsonPayload`; for an audio note from the watch also `sentAt`, `wearTransferId`, and the optional `audioSize` and `audioSha256` |
| `/logdate/notes/<id>/delete`                                                                                                   | Data item, watch to phone  | Keys `uid` and `noteType` = `DELETE`                                                                                                                    |
| `/logdate/notes/<id>/audio`                                                                                                    | Channel, both directions   | The audio file                                                                                                                                          |
| `/logdate/notes/<id>/audio/request`                                                                                            | Message, watch to phone    | Asks the phone to stream a note's audio                                                                                                                 |
| `/logdate/notes/<id>/ack` and `/logdate/notes/<id>/ack/<transferId>`                                                           | Message, phone to watch    | The phone stored the note                                                                                                                               |
| `/logdate/journals/<id>`, `/logdate/journals/<id>/delete`                                                                      | Data item, watch to phone  | A journal, or its deletion                                                                                                                              |
| `/logdate/associations/<journalId>::<contentId>`, with `/delete`                                                               | Data item, watch to phone  | A link between a journal and a note                                                                                                                     |
| `/logdate/health/<id>`                                                                                                         | Data item, watch to phone  | A health snapshot                                                                                                                                       |
| `/logdate/sync/request`                                                                                                        | Message, both directions   | To the watch: send what is waiting. To the phone: publish notes to this watch.                                                                          |
| `/logdate/camera/open`, `/logdate/camera/capture`, `/logdate/camera/switch`, `/logdate/camera/select`, `/logdate/camera/close` | Message, watch to phone    | Controls the phone's camera from the watch                                                                                                              |
| `/logdate/camera/devices`, `/logdate/camera/capture-result`                                                                    | Data item, phone to watch  | The phone's list of cameras, and whether a capture was saved                                                                                            |

### Delivery semantics

A full version 1 run sends journals, then notes, then journal links, then health snapshots, so a
parent exists on the phone before anything that refers to it. Notes and note deletions from the
watch and the phone's two camera items are written as urgent data items; journals, journal links,
health snapshots and the note copies the phone publishes to the watch are ordinary ones.

A text note, a journal and a journal link are settled on the watch as soon as the put succeeds.
An audio note stays in the outbox until the phone acknowledges it, and is sent again after 30
seconds without an acknowledgement (`ACK_TIMEOUT_MS`). The phone stores an audio note only once it
has both the metadata and the file, in whichever order they arrive, and acknowledges only then. A
redelivered note or file is acknowledged again and never stored twice. The watch deletes an
acknowledged audio note's data item so Play services stops storing and copying it.

Deletion is an explicit `/delete` data item instead of deleting the original item, because a
Data Layer deletion event contains only a URI and because items and channels are unordered: the
phone records that the watch deleted a note so that metadata or audio arriving afterwards is not
stored.

A failed send is retried after 5 seconds, doubling to a ceiling of 5 minutes
(`RETRY_BASE_DELAY_MS`, `RETRY_MAX_DELAY_MS`). After five failures (`MAX_RETRY_ATTEMPTS`) the item
is recorded as a retained failure. It stays in the outbox and is not retried automatically; a
person retries or discards it from the watch's sync issues. Discarding is the only way a capture
leaves the outbox without reaching the phone. Retry bookkeeping is bound to the operation that was
attempted, so a change queued while an attempt is in flight is neither lost nor charged with the
failure.

### The compatibility contract

Two fields of version 1 are optional on the wire, and their absence selects the old behavior. Any
change to version 1 must follow the same rule.

**Audio digest keys.** A watch adds `audioSize` (decimal byte length) and `audioSha256` (lowercase
hex) to an audio note's data item (`NoteDataMapper.KEY_AUDIO_SIZE`, `KEY_AUDIO_SHA256`).

- A phone that knows the keys stores the note only if its copy of the file has that length and
  digest. On a mismatch it removes the file and the metadata, stores nothing and sends no
  acknowledgement, so the watch's next resend replaces both.
- A phone that predates the keys ignores them and trusts the file.
- A watch that predates the keys sends neither, and the phone trusts the file, as every phone did
  before digests existed.
- A value that is present but unreadable gives a digest no file matches, so a damaged key never lets
  a short file through.
- The phone also checks `wearAudioSha256` when a data item has it. That key is a digest with no
  length, and no watch code writes it.

**The acknowledgement payload.** The phone's acknowledgement message contains
`{"lastUpdated": <epoch milliseconds>}`: the version of the note it stored
([`NoteAckPayload`][NoteAckPayload]).

- A watch that knows the payload clears the note from its outbox only if its own copy has that
  exact `lastUpdated`, so an acknowledgement that arrives late cannot clear an edit made since.
- An acknowledgement settles only the operation that was sent. The watch remembers which outbox
  operation each audio delivery belongs to, and if the operation now queued for the note is a
  different one, for example a deletion queued after the recording was sent, the acknowledgement
  leaves it in the outbox.
- A phone that predates the payload sends an empty message, which decodes to "unversioned".
- A watch that predates the payload ignores it.
- A payload the watch cannot read is logged as an error and handled as unversioned.

Newer watches also add a `wearTransferId` to an audio note's data item, and the phone echoes it in
the acknowledgement path. A receipt then settles only the exact snapshot that was sent. The watch
accepts an acknowledgement without a transfer id only for the one case an old phone can produce
unambiguously: the first creation of a note that has no operation id and no account scope. Once
notes are being delivered over version 2, the watch ignores version 1 acknowledgements altogether.

These optional fields are why version 1 cannot be removed until every older phone and watch build
is no longer supported.

### Finding the other device

The watch sends version 1 messages and files to a reachable node that advertises
`logdate_phone_app`, preferring a nearby one; if no node advertises it, the watch falls back to a
connected node ([`PhoneNodeSelector`][PhoneNodeSelector]). That fallback covers messages and files
only. A version 1 upload run starts only when a reachable node advertises `logdate_phone_app`
(`isPhoneConnected`), so the watch does not write its data items for a phone build that advertises
nothing. That check counts any connected node only when the capability lookup itself fails. The phone messages every reachable node that advertises `logdate_watch_app`, each on its
own, so one unreachable watch does not stop the message reaching another.

Version 2 never falls back like this. Its requests and files go only to the linked phone node, and
only while that node advertises `logdate_phone_sync_v2`.

## Unsupported formats and "update the app"

Both apps can meet data written by a newer build. The rule is the same everywhere: data this build
cannot read is preserved, reported as a reason that names the fix, and never counted as a failure
that blocks other work.

On the Data Layer, a message with a newer schema decodes as `Unsupported`. A capture the phone
cannot read is answered with a `REJECTED_UNSUPPORTED` receipt covering every version of that
record. The watch keeps the capture, moves its ledger row to `REJECTED`, and either uploads it
directly or waits with `PHONE_CANNOT_READ`. An unreadable state document is ignored until the
device updates, and the last readable one stays in effect.

In backup between any client and the server, a remote record of a kind or payload version this
build does not know is classified as `DiagnosticReason.UNSUPPORTED_FORMAT`, whose action is
`DiagnosticAction.UPDATE_APP`. The download inbox keeps the record and offers it again after an
update. Meanwhile it does not fail the sync run, does not leave backup in an error state, is never
parked as a permanent failure, and is left out of the inbox's count of unfinished work. Without this
rule one entry from a newer app would keep sync in error on every older device until each was
updated. This applies on Android, iOS and desktop, because the code is in the shared sync module:
see [`SyncDownloadEngine`][SyncDownloadEngine] and
[`DownloadInboxFailureRecorder`][DownloadInboxFailureRecorder].

## Opening the app on the other device

Either app can open LogDate, or its store listing, on the other device with
`RemoteActivityHelper.startRemoteActivity`. That API starts exactly one shape of intent on a remote
device: `ACTION_VIEW` with a data URI and `CATEGORY_BROWSABLE`. Any other shape, including
`ACTION_MAIN` with the launcher category, fails with `IllegalArgumentException` before anything is
sent. Both apps therefore build the intent in one place: `watchLinkIntent` in
[`AndroidWatchConnectionManager.kt`][AndroidWatchConnectionManager] on the phone and
`phoneViewIntent` in [`PhoneHandoff.kt`][PhoneHandoff] on the watch.

```kotlin
Intent(Intent.ACTION_VIEW)
    .addCategory(Intent.CATEGORY_BROWSABLE)
    .setData(Uri.parse(link))
```

Because the intent must be a browsable view of a URI, the receiving app needs an activity with a
matching `VIEW` filter, and "open the app" has to be expressed as a link.

| From  | Link                                                      | Opens                                   |
| ----- | --------------------------------------------------------- | --------------------------------------- |
| Phone | `studio.hypertext.logdate://watch/today`                  | The watch app at Today, its home screen |
| Phone | `studio.hypertext.logdate://watch/moment/<id>`            | A moment on the watch                   |
| Phone | `market://details?id=<package>`                           | The Play listing on the watch           |
| Watch | `studio.hypertext.logdate://settings/watch`               | The phone's watch settings              |
| Watch | `studio.hypertext.logdate://handoff/<id>?target=<target>` | A moment on the phone                   |
| Watch | `market://details?id=<package>`                           | The Play listing on the phone           |

The phone waits 15 seconds for the watch to accept the intent and the watch waits 10 seconds for
the phone. On the phone, "install" targets a connected watch without LogDate first and falls back
to one that already has it, so the same action also reaches the listing's update button. The phone
reports a failure as one of `NO_WATCH_CONNECTED`, `NOT_INSTALLED_ON_WATCH` or
`WATCH_DID_NOT_RESPOND`.

## Other version 2 paths

These paths belong to features described in
[The watch companion](../../explanation/wear-companion.md).

| Path                              | Primitive                             | Purpose                                                                                                                                            |
| --------------------------------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/logdate/live/recording`         | Message, watch to phone               | Recording state while the watch records. Messages are unordered, so the receiver admits only a sequence higher than the last one for that session. |
| `/logdate/live/recording/command` | Request, phone to watch               | Stop, pause or resume a recording                                                                                                                  |
| `/logdate/live/playback/command`  | Request, both directions              | Playback control and handing playback to the other device                                                                                          |
| `/logdate/reminders/claim`        | Request, both directions              | Claim the day's reminder before delivering it                                                                                                      |
| `/logdate/reminders/deliver`      | Request, phone to watch               | Deliver a reminder on the watch                                                                                                                    |
| `/logdate/presence`               | Request, phone to watch               | Ask whether the watch is on the wrist                                                                                                              |
| `/logdate/diag/ping`              | Request, phone to watch               | Read the watch's notification and microphone permission state                                                                                      |
| `/logdate/v2/device/removal`      | Request and data item, phone to watch | Remove a watch and erase its data                                                                                                                  |

## Known limits

- **The pair key authenticates nothing.** Both devices store the pair key, and the phone treats
  a link without a 32-byte pair key as unusable, but no message is signed or verified with it.
  Later messages are authorized by the node id Play services reports and by the link context.
- **Some watch state fields are not filled in.** The watch's state document declares `outboxCount`,
  `deadLetterCount`, `freeBytes` and `presence`, but the installed watch does not supply them, so
  they are published as `0`, `0`, absent and `UNKNOWN`. The phone asks for presence with a
  `/logdate/presence` request instead. Likewise the phone's `openMarks` is always `0` in the
  document.
- **State documents are remembered in memory.** `PeerStateStore` stores the other device's latest
  readable document only while the process runs. Each app reads the stored documents again when it
  starts.
- **A state document is rewritten at every app start**, because its `writtenAt` changes. The other
  device treats it as a no-op when nothing else changed.
- **A changed day-start hour reaches the watch late.** The phone reads the day-start hour only when
  it rebuilds its document for another reason, such as a session, campfire or activity change. The
  window itself reads the hour on every publish.
- **A resumed install can record an empty phone node.** If an account install resumes after the
  process was stopped and the watch has no link record naming its phone, the finished link is saved
  with an empty phone node id and no link id. Version 2 delivery then stays inactive until a new
  exchange with the phone completes.
- **The window does not manage notes it did not put there.** A phone note that version 1 copied to
  the watch before the window was negotiated has no window record on the watch. The window neither
  updates nor removes it, because unknown ownership never grants permission to change local data.
- **Old version 1 note items stay in the Data Layer.** A note data item the phone wrote before the
  window existed is never deleted by the phone. The watch does not apply it again while the window
  is active.
- **The ledger's attempt count includes every send.** A failure after several successful resends
  therefore waits longer than a first failure would.
- **The phone does not expire its ledger rows.** The watch forgets a `TO_PHONE` row once the
  capture's data item has been deleted. No code removes by age the `FROM_WATCH` rows the phone
  writes for the captures it receives, so the phone keeps one for every capture a watch sent.
- **A finished sync job is not recorded as succeeded.** The `watch_sync` job schedules its
  successor as its last step, and scheduling replaces the job that is still finishing, which stops
  it. WorkManager's own history therefore does not show completed runs as successes. The sync
  itself has already run to the end by then.
- **Version 1 only moves journals one way.** The phone publishes notes to the watch and nothing
  else. It never tells the watch that a journal or a journal link was deleted on the phone, so a
  later edit of that journal on the watch creates it again on the phone.
- **Version 1 text notes, journals and journal links are never deleted from the Data Layer.** Only
  an acknowledged audio note's data item is removed, apart from a watch that is removed erasing
  every item it wrote.
- **The phone does not apply a newer version of an audio note it already stored over version 1.**
  A redelivered audio note is acknowledged only if it matches the stored one.
- **Version 1 has no receipt for text notes.** A text note is settled when its put succeeds, not
  when the phone stores it.
- **A removed device keeps sync access briefly on other server instances.** The server-side detail
  is in the [device sessions API](../../../server/docs/device-sessions-api.md).

[datalayer]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/
[datalayer-v2]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/
[peer]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/peer/
[wear-sync]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/
[wear-sync-v2]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/
[phone-sync]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/
[phone-sync-v2]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/v2/
[wear-manifest]: ../../../app/wear/src/main/AndroidManifest.xml
[phone-manifest]: ../../../app/android-main/src/main/AndroidManifest.xml
[WearDataLayerListenerService]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/WearDataLayerListenerService.kt
[PhoneDataLayerListenerService]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/PhoneDataLayerListenerService.kt
[WatchSyncFacade]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/WatchSyncFacade.kt
[DataEventFilter]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/DataEventFilter.kt
[PhoneDataLayerSnapshotEvent]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/PhoneDataLayerSnapshotEvent.kt
[DatastoreModule]: ../../../client/datastore/src/commonMain/kotlin/app/logdate/client/di/DatastoreModule.kt
[CloudLinkBundleSource]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/v2/link/CloudLinkBundleSource.kt
[WatchLinkNotifier]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/v2/link/WatchLinkNotifier.kt
[MomentDownloader]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/moments/MomentDownloader.kt
[WatchStateMirror]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/WatchStateMirror.kt
[WearProtocolGate]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/WearProtocolGate.kt
[WearProtocol]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/WearProtocol.kt
[WearPaths]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/WearPaths.kt
[WearJson]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/WearJson.kt
[RecordEnvelope]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/RecordEnvelope.kt
[LinkMessages]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/LinkMessages.kt
[PeerDeliveryContext]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/v2/PeerDeliveryContext.kt
[NoteAckPayload]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/datalayer/NoteAckPayload.kt
[StateDocument]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/peer/state/StateDocument.kt
[StateMerger]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/peer/state/StateMerger.kt
[SharedPreferenceKey]: ../../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/watch/SharedPreferenceKey.kt
[PhoneWatchSyncV2Module]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/di/PhoneWatchSyncV2Module.kt
[WearSyncV2Module]: ../../../app/wear/src/main/kotlin/app/logdate/wear/di/WearSyncV2Module.kt
[WearCloudSyncModule]: ../../../app/wear/src/main/kotlin/app/logdate/wear/di/WearCloudSyncModule.kt
[PhoneLinkInitiator]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/v2/link/PhoneLinkInitiator.kt
[PhoneLinkDecision]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/v2/link/PhoneLinkDecision.kt
[WatchLinkCoordinator]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/link/WatchLinkCoordinator.kt
[LinkHandshake]: ../../../client/device/src/commonMain/kotlin/app/logdate/client/device/crypto/link/LinkHandshake.kt
[BouncyCastleLinkHandshake]: ../../../client/device/src/androidMain/kotlin/app/logdate/client/device/crypto/link/BouncyCastleLinkHandshake.kt
[LinkedDeviceInstaller]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/peer/link/LinkedDeviceInstaller.kt
[WatchNoteDelivery]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/delivery/WatchNoteDelivery.kt
[WatchMomentDelivery]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/delivery/WatchMomentDelivery.kt
[ReleasedToCloud]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/delivery/ReleasedToCloud.kt
[WatchSyncRouter]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/peer/delivery/WatchSyncRouter.kt
[PeerDeliveryLedger]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/peer/delivery/PeerDeliveryLedger.kt
[WatchSyncScheduler]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/WatchSyncScheduler.kt
[WatchCaptureCollisionPolicy]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/v2/receipts/WatchCaptureCollisionPolicy.kt
[SyncProfile]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/SyncProfile.kt
[WatchWindowPolicy]: ../../../client/domain/src/commonMain/kotlin/app/logdate/client/domain/watch/WatchWindowPolicy.kt
[WatchWindowPublisher]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/v2/window/WatchWindowPublisher.kt
[WindowApplier]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/window/WindowApplier.kt
[WindowReconciler]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/window/WindowReconciler.kt
[WatchPhoneMedia]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/media/WatchPhoneMedia.kt
[PhoneMediaServer]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/sync/v2/media/PhoneMediaServer.kt
[WatchMediaCache]: ../../../app/wear/src/main/kotlin/app/logdate/wear/playback/WatchMediaCache.kt
[WatchMediaPrefetchScheduler]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/v2/media/WatchMediaPrefetchScheduler.kt
[MarkedMomentConflictResolver]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/conflict/MarkedMomentConflictResolver.kt
[PhoneNodeSelector]: ../../../app/wear/src/main/kotlin/app/logdate/wear/sync/PhoneNodeSelector.kt
[SyncDownloadEngine]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/SyncDownloadEngine.kt
[DownloadInboxFailureRecorder]: ../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/recovery/DownloadInboxFailureRecorder.kt
[AndroidWatchConnectionManager]: ../../../app/compose-main/src/androidMain/kotlin/app/logdate/client/watch/AndroidWatchConnectionManager.kt
[PhoneHandoff]: ../../../app/wear/src/main/kotlin/app/logdate/wear/v2/phone/PhoneHandoff.kt
