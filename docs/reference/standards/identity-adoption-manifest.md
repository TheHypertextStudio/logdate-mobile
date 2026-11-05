# Identity adoption manifest v1

The initiating device describes its verified source and destination with canonical
DAG-CBOR evidence. Operation authorization, an authenticated device assertion,
current source and destination inventories, and atomic cutover remain separate
requirements. A manifest hash alone cannot establish permission or completeness.
The authenticated verification request must bind the manifest digest to the current
operation revision and lease or challenge. Renewing an operation invalidates an
older request even when its source sequence and manifest bytes are unchanged.

The shared Kotlin implementation lives in `shared/space/.../adoption`. Native
Swift uses `LogDate/SharedCore/IdentityAdoption` in `logdate-apple`. Both consume
the synthetic vectors in `conformance/adoption/manifest-v1.json`; the native test
fixture embeds the same JSON. These vectors describe encoding and commitments.
Their synthetic blob evidence is not authenticated media.

## Canonical document

Map keys sort by UTF-8 byte length, then unsigned UTF-8 bytes. Values use the AT
data model: signed 64-bit integers, booleans, null, text, bytes, links, arrays and
maps. Fractional numbers, invalid Unicode, duplicate keys, nonminimal encodings,
noncanonical ordering and trailing values are refused. Payload parsing is bounded
to the existing sealed-record byte limit and 128 nesting levels before decoding
can allocate declared containers.

| Field                                     | Value                                                                                                                                        |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `version`                                 | Integer `1`.                                                                                                                                 |
| `operationId`, `accountId`                | Lowercase UUID strings.                                                                                                                      |
| `backendOrigin`                           | HTTPS origin with lowercase host, default port 443 and a root slash omitted. Credentials, non-root paths, queries and fragments are refused. |
| `deviceId`                                | Nonempty device identifier, at most 128 UTF-8 bytes, without control characters.                                                             |
| `sourceSpace`, `targetSpace`              | Distinct personal `self` space URIs.                                                                                                         |
| `sourceEpoch`                             | Nonnegative identity epoch below the maximum signed 64-bit integer.                                                                          |
| `sourceSequence`                          | Nonnegative final source sequence.                                                                                                           |
| `records`                                 | Map containing `count`, `live`, `tombstones`, and a 32-byte `digest`. Live and tombstone counts sum exactly to the total without overflow.   |
| `blobs`                                   | Map containing `count`, ciphertext `bytes`, and a 32-byte `digest`. An empty inventory has zero bytes.                                       |
| `pendingCommitment`, `conflictCommitment` | Separate 32-byte keyed commitments to the captured local snapshots.                                                                          |

The manifest digest is SHA-256 of this canonical document. Record ciphertext,
journal text, local edits, conflict contents, account keys and provider credentials
are not included in the document.

## Inventory framing

Each inventory begins with its UTF-8 domain followed by one NUL byte:

- Records: `app.logdate.identity-adoption.records|1`.
- Blobs: `app.logdate.identity-adoption.blobs|1`.

Append every evidence item as an unsigned eight-byte big-endian byte length,
followed by that item's canonical DAG-CBOR bytes. The inventory digest is SHA-256
of the complete framed stream. A rejected item must not alter the accepted stream
or its counts.

Record evidence contains `sourceCollection`, `sourceRkey`, `targetCollection`,
`targetRkey`, `sourceVersion`, `sourceDigest`, `targetDigest`, and `deleted`. Source
tuples strictly increase by collection and record key, using unsigned UTF-8 order;
duplicate source tuples and duplicate destination tuples are refused. Digests are
32-byte values and versions are nonnegative. Tombstones participate explicitly.

Source and destination digests bind complete stored canonical records. A wire
adapter must account for every envelope field, including fields absent from its
typed model, or refuse that envelope before staging. The typed codec alone cannot
prove a complete wire inventory. Decrypted payload conversion separately retains
unknown payload and record fields, or refuses an unsupported authority-dependent
structure; it never removes one from the source count.

Blob evidence contains `cid`, `size`, and a 32-byte `digest` of ciphertext. CIDs
strictly increase. The CID must be CIDv1/raw/SHA-256 for that exact digest, sizes
are positive, and byte totals cannot overflow. This evidence must be accompanied
by authenticated decoding of both copies and explicit destination ownership and
reference registration before encrypted media can be reused.

The Kotlin streaming inventory closes its hash state when finalized and caches
the immutable result. Resume rebuilds it from persisted ordered evidence. Native
value inventories can inspect a copied hash state. Neither approach requires
re-sealing a completed ciphertext item to reconstruct its digest. Durable per-item
storage and authoritative snapshot capture belong to the adoption host.

## Record conversion and wire preservation

The shared Kotlin adoption adapter receives complete raw JSON envelope objects.
It checks the exact v1 envelope field set before using the generated sealed-record
model. Unknown envelope fields, unsupported versions and malformed typed fields
refuse conversion and readback. A typed model reconstructed from a stored record
is insufficient input: the host must retain the original raw object. All source
and target digests cover the complete canonical wire record.

Conversion changes only schema-defined self identity references in people,
graph and share records. Peer identities, user text, unknown payload fields and
same-space collection/key relationships remain unchanged. Personal share audiences
rebind their typed person subject; other owners' group audiences remain intact.
Retiring-owner groups and unknown audience variants require a dedicated adapter.
Nested sealed records, key declarations, wrapped keys and space authority records
are refused because copying their ciphertext or signatures does not establish
valid authority under the adopted identity.

Readback authenticates both envelopes and compares the entire converted payload,
including unknown fields. It binds the source key, version and digest to expected
evidence and requires every blob reference, including extension fields, to match
the declared set. Tombstones retain their complete canonical marker. These
per-record checks do not establish authoritative inventory completeness, durable
resume, media authentication or target ownership; the operation host must verify
those separately before producing an accepted adoption proof.

## Media readback

The Kotlin sync adapter authenticates source and destination streams with the
captured media key using the existing LDCE2 decoder and bounded LDCE1 fallback.
It rejects cleartext and unknown encryption formats even though ordinary media
downloads may support cleartext. A matching content identifier alone is not
proof that encrypted media can still be opened.

Each stream is hashed as the decoder reads it, with exact captured byte count,
SHA-256 digest and CIDv1/raw identifier checks. Decrypted output is discarded;
no plaintext file is created. Input reads are bounded, total ciphertext is limited
to 100 MiB, and the existing 31 MiB whole-file legacy limit still applies. Streams
close on success, refusal and cancellation. Evidence is returned only after both
copies authenticate. Destination registration, retention and authoritative
inventory completeness remain requirements of the surrounding operation.

## Local snapshot commitments

Derive a dedicated 32-byte key with HKDF-SHA256:

- Input key material: the existing personal-space index key.
- Salt: `app.logdate.identity-adoption`.
- Info: `local-state-commitment v1`.

For each local snapshot, compute HMAC-SHA256 over its UTF-8 domain plus a NUL byte,
followed by the same eight-byte length framing and canonical value encoding:

- Pending edits: `app.logdate.identity-adoption.pending|1`.
- Conflicts: `app.logdate.identity-adoption.conflicts|1`.

Preserve snapshot order. The key and domain separation prevent unkeyed plaintext
hashes from exposing predictable local contents. The host must capture actual
pending edits and conflict versions durably; the serializer cannot supply that
guarantee. Edits made after capture stay in a separate pending generation.
