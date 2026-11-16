# Private storage in sealed personal spaces

Almost everything a person keeps in LogDate is private: entries, drafts, journals, recordings,
places and location history. LogDate stores it in the account's **personal space**, a store that
only the owner's devices can read, where every record is encrypted on the device before it is
uploaded. The server keeps the ciphertext, puts changes in order and checks who is asking. It
cannot tell an entry from a location sample.

Two limits apply to that statement. First, not every account is stored this way. An account
created before personal spaces existed, or on a server whose operator has not turned them on, is
still kept in the older storage described below until one of its devices moves it. Second, the
personal space holds only the kinds of data that sync through it, which are listed in
[Personal space sync](../reference/personal-space-sync.md#records). The
[lexicon reference](../reference/lexicons.md) assigns more private data to the personal space than
that, such as records about people, and those kinds are not synced there.

This page explains why private data is stored that way instead of in the account's public AT
Protocol repository, how a sealed record is built and why each part of it exists, what the server
can still observe, and what the design costs.

For exact values and behavior, see these references:

- [Personal space sync](../reference/personal-space-sync.md) describes how an app moves data to and
  from its space.
- [Encryption formats](../reference/encryption-formats.md) and [Encryption](./encryption.md) cover
  the key hierarchy and the other wire formats.
- [LogDate lexicons](../reference/lexicons.md) lists every record type and how each kind of data
  maps onto records.
- [Moving journals into personal spaces](./journal-moves.md) follows an existing account from the
  older storage into its space.

## One account, two kinds of storage

LogDate is built on the AT Protocol, the open protocol behind Bluesky. In that protocol an account
is named by a DID, a permanent identifier such as `did:plc:abcdefghijklmnopqrstuvwx`, and its data
is a set of **records**. A record is a small structured document. Its type is called its
**collection** and is named by an NSID such as `app.logdate.journal.entry`, and it is stored under
a **record key** (rkey) that is unique within that collection.

The protocol's standard home for records is the **repository**: one per account, public by design,
followed by relays that pass every change on to any app that listens. That is the right home for
what a person chooses to publish and the wrong home for a journal. LogDate therefore gives each
account two stores.

| Store             | What it holds                                                                                                                                    | Who can read it                                  |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------ |
| Public repository | The profile, the public keys a device publishes so others can share with it, and the publications and stories the person deliberately published. | Anyone, once the account's identity is published |
| Personal space    | Every private record LogDate syncs, each one sealed                                                                                              | Only the owner's devices                         |

The [repository event stream reference](../reference/repository-event-stream.md) lists exactly
which collections are public. Everything else is private, and a new record type starts private
until it is named as public.

A space is named the way the AT Protocol's Spaces work names one, by three parts: the DID that
controls it, a space type and a space key. The personal space of an account is always
`at://{account DID}/space/app.logdate.space.personal/self`
([`SpaceRef`](../../shared/space/src/commonMain/kotlin/app/logdate/space/SpaceRef.kt)). The server
serves only the personal space type and answers a request for any other space as if it did not
exist. Spaces is the
protocol's own effort to carry data that is not public. It gives access control, not
confidentiality: a server that hosts a space reads its records in plaintext. LogDate follows the
Spaces model for naming and for the shape of its methods, and adds sealing on top so that the host
reads nothing.

## Why private records are not stored in the repository structure

LogDate does not keep private data in the repository structure, for concrete reasons.

A repository is a Merkle search tree (MST): a tree whose nodes are content-addressed blocks, keyed
by `collection/rkey`. Every write builds new tree nodes and appends a **commit**, a small block
that names the new root and is signed with the account's signing key. Anyone holding the account's
public key can then verify that the tree is exactly what the account wrote. Relays and other apps
depend on that, which is why the public repository keeps it.

For private data, each property of that structure is a cost with no matching benefit.

- **A signature for every save.** A journal is written in small, frequent steps. A draft is saved
  as the person types, and a device uploads new location samples every few minutes. In the
  repository each of those saves costs a signature with the account's signing key and a new chain
  of tree nodes. The signature exists so that strangers can verify the write, and no stranger ever
  reads a private record.
- **Writes have to queue.** A repository write reads the whole tree, rebuilds it and replaces the
  head. Two writes in flight at once each build a tree that is missing the other's record, and the
  later head wins. The older sync path uploads one kind of data after another for exactly this
  reason. A space store has no shared tree, so a write touches one row.
- **Deleted content stays on disk.** The repository engine keeps every block it has written for as
  long as the account exists, because old commits still refer to them. That is acceptable for
  public posts, which relays have already copied. For a journal it means an entry the person
  deleted remains in the database indefinitely. In a space store, deleting a record removes its
  ciphertext immediately and leaves only a deletion marker, which is itself purged after a
  retention period.
- **The tree's shape describes the data.** Tree keys are `collection/rkey`, so even with the text
  of each record encrypted the store reveals how many entries, journals and drafts an account has,
  which entry belongs to which journal, and when each record was created. The older storage had
  exactly this leak: field values were encrypted, while each record's kind, identifier, timestamps
  and relationships were readable.
- **The envelope already proves authorship.** A sealed record can only be produced by a device
  that holds the account's identity key, and it fails to open if it is altered or moved. A signed
  commit on top would prove the same thing a second time, to a server that cannot read the
  record anyway.
- **Spaces does not use the tree either.** Each author's part of a space is a plain set of
  records, summarized by a hash, with no MST.

A space store is therefore a table of sealed records keyed by space, author and record key, with a
sequence number the server assigns to each change. The public repository still uses the MST engine
and signed commits, because that is what the rest of the network verifies.

## The sealed envelope

Every record in a personal space is stored as an `app.logdate.crypto.sealed` record, called the
**envelope**. The real record, its collection and its record key are inside the ciphertext.
[`SealedRecordCodec`](../../shared/space/src/commonMain/kotlin/app/logdate/space/SealedRecordCodec.kt)
builds and opens envelopes.

| Part                                              | Can the server read it? | What it is                                                                 |
| ------------------------------------------------- | ----------------------- | -------------------------------------------------------------------------- |
| Space and author                                  | Yes                     | Which space the record is in and whose part of it                          |
| Sealed record key                                 | Yes, but it is opaque   | A 26-character key derived from the inner collection and key with a secret |
| `version`                                         | Yes                     | The envelope format, `1`                                                   |
| `keyId`                                           | Yes                     | Which of the owner's content keys sealed the record                        |
| `nonce`                                           | Yes                     | 12 random bytes, fresh for every write                                     |
| `ciphertext`                                      | Only as bytes           | The padded payload, encrypted with AES-256-GCM                             |
| Inner collection, inner record key and the record | No                      | They exist only inside the ciphertext                                      |

The keys come from the account's **identity key**, a 32-byte secret that is created on a device and
never sent to the server ([`SealingKeys`](../../shared/space/src/commonMain/kotlin/app/logdate/space/SealingKeys.kt)).
Two keys are derived from it with HKDF-SHA256, a standard way to turn one secret into several
independent ones: a **content key** that encrypts records, and an **index key** that derives their
opaque record keys. Using two keys means that learning how record keys are derived would reveal
nothing about how records are encrypted. The `keyId` is a short fingerprint of the content key. It
is in the envelope so that key rotation can add content keys without changing the format.

Sealing replaces the field-by-field encryption the older storage used. Encrypting fields one at a
time leaves the record's shape readable. Sealing encrypts the whole record, so nothing about its
shape is left.

The same format is implemented by other LogDate clients in other languages, so it is pinned by
test vectors that every client runs, with a second, independent implementation checking them. See
the [client conformance suite](../../conformance/README.md).

### The envelope is bound to its place

AES-GCM can authenticate extra data that is not encrypted, called additional authenticated data.
The envelope uses it to tie the ciphertext to the one place it belongs:

```text
app.logdate.crypto.sealed|1|{space URI}|{author DID}|{sealed record key}
```

A record opens only when the reader supplies the same space, author and key the writer used.
Without this binding a server could copy a valid envelope over a different record, replay it into
another account, or swap two records, and each would still decrypt. With it, any such move makes
the envelope fail authentication, and the device reports the record as unreadable instead of
showing the wrong content. As a second check, after decrypting, the device derives the sealed key
again from the collection and key it found inside and refuses the record if they differ.

The binding has a consequence that is easy to miss. The space URI contains the DID that controls
the space, and for a personal space the author is that same DID. The owner's identifier is
therefore part of what every envelope authenticates, twice. See
[The owner's identifier is part of every envelope](#the-owners-identifier-is-part-of-every-envelope).

## Why sealed record keys are derived instead of generated

The sealed record key is the first 26 characters, in base32, of an HMAC-SHA256 over
`{collection}/{rkey}` under the index key. HMAC is a keyed hash: the same input always gives the
same output, and without the key the output says nothing about the input.

The AT Protocol's usual record key is a TID, an identifier built from the time of creation. The
sealed key is not a TID, because a TID is a timestamp that would tell the server when every record
was first written, and hiding that is one of the points of sealing. It is not a random key either,
because each device would then have to learn and remember which sealed key holds which record, and
a device that lost that table could not update or delete anything without downloading and opening
the whole space.

Deriving the key gives three properties the sync design relies on.

- **Any device can address any record.** A device that knows it wants to change entry `X`
  computes where `X` is stored. Updates and deletes need no index.
- **Two devices creating the same thing collide cleanly.** Some records are keyed by what they
  connect, such as a journal membership keyed by its journal and entry. Two devices that add the
  same entry to the same journal while offline derive the same sealed key, so the second write
  meets the first as a conflict on one record instead of creating a duplicate.
- **The server's version checks work per record.** Because one inner record is always one stored
  row, the server can enforce "replace this only if it is still the version I last saw" without
  knowing what the record is.

The cost is that the sealed key is stable: the server can tell that the same record changed again,
and how often. That is part of the accepted cost described below.

## Padding buckets and the accepted leak of media sizes

Encryption hides content but not length, and length alone separates kinds of record. A location
sample batch, a one-line note and a long transcript have very different sizes. Before encryption
the payload is therefore given a 4-byte prefix that records its real length, and the two together
are padded with zeros up to a fixed bucket
([`SealedPadding`](../../shared/space/src/commonMain/kotlin/app/logdate/space/SealedPadding.kt)):

- up to 64 KiB, the bucket is the next power of two, starting at 512 bytes;
- above 64 KiB, the bucket is the next multiple of 64 KiB.

The rule applies to the payload plus its length prefix, so a 510-byte payload needs 514 bytes and
lands in the 1,024-byte bucket. The stored ciphertext is 16 bytes larger than the bucket, because
AES-GCM appends an authentication tag.

Powers of two keep the number of distinct sizes small where most records live, so a short note and
a small journal record look the same. Above 64 KiB a power of two would waste up to half of every
large record, so the step becomes linear. The server checks that every envelope it accepts is
exactly a bucket size once the tag is subtracted, which stops a faulty client from storing unpadded records without anyone
noticing. An envelope holds at most 4,000,000 bytes of ciphertext. A record too large to seal is
refused, never trimmed.

Media is the exception, and it is a deliberate one. Photos, videos and recordings are not stored
inside envelopes. Each file is encrypted on the device in 64 KiB chunks with the account's media
key and uploaded as a **blob**, and the sealed record points at the blob by its content
identifier. The server sees neither the file's name nor its type: the type is stored inside the
sealed record, and the blob itself is opaque bytes. It does see the blob's size, to within a
chunk. A long recording is visibly larger than a photo.

Media is not padded. Hiding the difference between a photo and a long video would mean padding
every file toward the size of the largest ones, which multiplies what each account stores and what
each device uploads over a mobile connection. The leak is accepted.

## What the server still sees and what it no longer sees

This section describes an account that is stored in its personal space. An account that was moved
there from the older storage is in between for a while: the server keeps the older copy, as
readable as it ever was, until it deletes that copy 30 days after the move.
[Moving journals into personal spaces](./journal-moves.md) explains why the copy is kept that long.

For sync to work at all, the server has to know a small set of things:

- which account is calling, and that the space it names is that account's own;
- the sealed record key, so it can replace, version-check and delete one record;
- a sequence number it assigns to each change, so devices can ask for "everything after N";
- the padded size of each record and the size of each blob, to enforce a storage quota;
- which blobs a record refers to, so that files nothing refers to can be removed;
- when each file was uploaded and when each record was deleted, so that unreferenced files and old
  deletion markers can be purged after their retention periods.

The server does not store a time for an ordinary record write, only its sequence number. It can
still observe when requests arrive, which is part of the cost described below. A device can verify
each record it receives, but nothing lets it detect a record the server withholds.

It no longer sees any of the following, all of which the older storage exposed:

- what kind of record something is, or how many of each kind an account has;
- a record's own identifier, or its creation and edit timestamps;
- which entry belongs to which journal;
- recording lengths, photo layout, device identifiers and capture details;
- media file names and media types;
- anything in location history, including record types and timestamps.

The rule that keeps it this way is the **data-home boundary**. A data home is wherever a space's
records live: LogDate's hosted service, or a LogDate server someone runs themselves. A data home
stores opaque records and blobs, assigns sequence numbers and authorizes requests. It never holds a
key, never reads a record and never resolves a conflict. Everything above that line runs on
devices, behind one interface,
[`DataHome`](../../shared/space/src/commonMain/kotlin/app/logdate/space/DataHome.kt), which lives in
shared code so that the server's tests exercise the same definitions the apps use.

## Server features that moved onto devices

A server that cannot read records cannot do anything that depends on their contents. These
features run on the devices, because the server holds only ciphertext.

| The older storage did this on the server                 | In a personal space                                                                                                                                                                                                                                                                                       |
| -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A separate change feed for each kind of data             | One feed for the whole space. A device pulls every change after its cursor, opens each one and routes it by the collection it finds inside                                                                                                                                                                |
| Counts of items by kind, for sync status                 | Counted on the device from its own data                                                                                                                                                                                                                                                                   |
| Journal merges, rewriting links and drafts               | The device that merges rewrites the memberships it knows about and writes the merged journal as a record that names the surviving one. Other devices apply the same redirect when they read it                                                                                                            |
| Field-by-field merges when two devices edited one record | Done on the device by the same conflict resolvers. The server only replaces a whole record if it is still the version the device last saw                                                                                                                                                                 |
| A storage quota per kind of data                         | The server enforces one total per account. Any breakdown by kind is computed on the device                                                                                                                                                                                                                |
| Looking up any record by its identifier to build a link  | The server holds no identifiers for the records in a personal space, so a private record there cannot be found this way. The lookup still searches the older storage, so it still finds the journals and entries of an account that has not moved, and of a moved account until its older copy is deleted |

A server can be replaced without understanding LogDate's data: a data home needs to implement
storage, ordering and authorization, and nothing else.

## What the design costs

**What the server still learns.** The server knows how much an account stores, how many records it
has, how often each record changes, and roughly when the account's devices are active. It also
sees media sizes, as described above. Hiding activity timing would take cover traffic, meaning
writes sent on a schedule whether or not anything changed. LogDate sends none, because it spends
battery and mobile data on every device, all the time, to hide something a server can infer from
network traffic anyway.

**What the server can no longer do for the person.** It cannot search, summarize, transcribe or
repair private data, and it cannot recover it. If every device and every recovery method is lost,
the ciphertext on the server is useless. [Recovery keys](./recovery-keys.md) explains what the
person holds to prevent that.

**What the apps take on.** Every feature in the table above is client code that has to
behave the same way on every platform. Converting an existing account cannot be done by the
server either, because it has no keys: a device has to seal the whole journal and prove the copy
is complete, which is why [journal moves](./journal-moves.md) are as careful as they are.

**What a record cannot hold.** A sealed record is replaced whole on every change and has a size
limit. Data that grows continuously has to be split into records that stay small, which is why
location samples are stored as one record per device per hour rather than one per day.

## The owner's identifier is part of every envelope

The additional authenticated data of an envelope contains the space URI and the author, and for a
personal space both contain the account's DID. Three things follow.

- A device computes its space as `SpaceRef.personal(did)` from the DID the server reports for the
  account, and opens each record with that value. If the DID it is given differs from the one a
  record was sealed with, the record fails authentication.
- The server stores each record under its space URI and admits a request only for the space named
  by the account's current DID. If an account's DID changed in the accounts table, its existing
  rows would be filed under a space the account can no longer name.
- The keys do not depend on the DID. The content key, the index key and therefore every sealed
  record key come from the identity key alone, and media is encrypted with the media key. Only the
  authentication of each envelope, and the address of each row, involve the DID.

So a DID change is survivable in principle, because nothing has to be re-keyed, but it is not
free: an account cannot start using a different owner identifier and keep reading its space. The
server assigns a DID once, when it provisions the account's identity, and nothing in the apps
changes it afterwards.
