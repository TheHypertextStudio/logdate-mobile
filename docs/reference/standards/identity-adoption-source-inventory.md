# Identity adoption source inventory

Adoption inventories the complete stored source before accepting a converted destination.
A live-record count, a typed list response or a local journal listing is insufficient. The
account UUID and existing keys stay fixed; only an explicitly typed self-authority reference
may change. Original ciphertext and unknown data remain retained until their conversion and
recovery requirements have been satisfied.

## Personal spaces

The source is the exact personal `self` space and author named by the operation binding.
`SpaceTables.kt` defines its state, records, record-to-blob references and blob metadata.

| Source                    | Required evidence                                                                                                                                                            |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `space_states`            | Original account owner, space URI, author, `last_seq` and `purged_through_seq`. An absent state cannot disguise existing record rows.                                        |
| `space_records` live row  | Full stored canonical DAG-CBOR bytes, CID, record key and sequence. Check byte count and CID before interpreting the envelope.                                               |
| `space_records` tombstone | Record key, sequence, deletion flag and complete deletion timestamp; require null record/CID and zero live size. Preserve the marker without reconstructing a live envelope. |
| `space_record_blobs`      | Every reference for each live record, including exact CID ownership. A tombstone has no live references.                                                                     |
| `space_blobs`             | Every owned blob, including unreferenced retained bytes, size, MIME type, physical path and pending purge state. Missing bytes or unresolved deletion prevent verification.  |

The v1 sealed envelope has exactly `$type`, `version`, `keyId`, `nonce` and `ciphertext`.
The strict envelope check must run on the complete raw object. Reconstructing a generated
`Sealed` value would omit future fields. Unknown envelopes are retained and block cutover
until supported; they are never removed from inventory counts. Decrypted payload extensions
have their own lossless conversion policy.

The canonical personal-space tombstone marker contains `deleted: true`, `rkey`, `seq` and
`deletedAt` as a UTC timestamp. The operation supplies its space, author and account binding.
Preserving this marker requires target sequence and timestamp semantics to remain explicit;
an adapter cannot hash a source marker and later silently substitute different metadata.

## Legacy repository collections

`RepoBackedLogDateCollectionsRepository` stores live bodies in the repository block store and
keeps versions, deletion markers and the original repository DID in
`logdate_collection_states` and `logdate_collection_records`. Read the raw repository records
and their metadata together. The typed `toLogDateEntry`, `toLogDateJournal` and related
conversions supply defaults and project known fields; they do not establish lossless input.

| Collection                                                                         | Fields and relationships to preserve                                                                                                                                                                                                           |
| ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `studio.hypertext.logdate.content` and entry-form `studio.hypertext.logdate.entry` | Complete raw object, `id`, `type`, nullable `content`/`mediaUri`, duration, creation/update times, device ID, photo presentation, caption, location, transcript, and captured-on/during fields. Distinguish absent fields from explicit nulls. |
| `studio.hypertext.logdate.journal`                                                 | ID, title, description, creation/update times, device ID and extensions.                                                                                                                                                                       |
| `studio.hypertext.logdate.association`                                             | Journal/content IDs, creation time and device ID. Preserve the `journalId~contentId` key relationship; do not remap a user-visible relationship by list order.                                                                                 |
| `studio.hypertext.logdate.draft`                                                   | ID, content, block types, journal IDs, encrypted-block version and bytes, creation/update times, device ID and extensions. A text-only projection is insufficient.                                                                             |
| `studio.hypertext.logdate.moment`                                                  | ID, captured/update times, device ID, full payload and payload schema.                                                                                                                                                                         |
| Collection metadata tombstones                                                     | Original collection and key, server version, deletion flag and deletion time, even when no live block exists.                                                                                                                                  |

Walk the existing repository inventory as well as its indexed kinds. An unindexed record,
unknown collection, missing referenced block or disagreement between index and body needs an
explicit retained disposition. Enumerating only the known live metadata rows cannot establish
completeness. Historical namespace names are source formats, not a request to publish new
lexicons or write to the external PDS.

Inventory reads must not call the provisioning fallback of `canonicalRepoDid`: that fallback
can invoke `ensureIdentity`. Use the existing stored repository binding and read-only evidence.
An absent or conflicting binding is a recovery condition, never permission to create another
identity during inventory.

## Legacy sync tables and auxiliary data

`SyncTables.kt` retains an earlier source representation. Account isolation is required even
where a table's primary key is not account-scoped. Compare overlapping repository and sync
representations explicitly; do not silently choose one and discard the other.

| Source                                          | Required fields                                                                                                                                                                                                                                                             |
| ----------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `sync_content`                                  | ID/account, type, nullable content/media URI/transcript/duration, creation/update times, server version, device ID and deletion state/time.                                                                                                                                 |
| `sync_journals`                                 | ID/account, title, description, creation/update times, server version, device ID and deletion state/time.                                                                                                                                                                   |
| `sync_associations`                             | Account, journal/content IDs, creation time, server version, device ID and deletion state/time.                                                                                                                                                                             |
| `sync_media` and `logdate_media_records`        | Account/media/content IDs, file name, MIME type, exact ciphertext size and inline bytes or physical path, version, device, creation/deletion state, and encryption version/key/mode. Compare duplicate representations instead of counting either as authoritative by name. |
| `location_history` and its version row          | Account/ID, record type, full payload, payload schema version, device ID/version, server version and deletion flag. Tombstones have no separate deletion-time column; do not invent one.                                                                                    |
| Backup registries and AT Protocol blob metadata | Preserve account ownership, encryption metadata, immutable object references and existing recovery access. Their data is not implicitly a journal record to re-seal.                                                                                                        |

Native `SyncFieldCipher` understands LDSE1 and fingerprinted LDSE2 field envelopes using the
unchanged identity key and field identifier. Field AAD does not contain the DID. Known legacy
cleartext remains distinguishable from encrypted envelopes; unsupported future LDSE versions
must fail without dropping a record. Authentication of a field is separate from converting its
schema and re-sealing a complete destination record. Neither a successful UTF-8 decode nor a
media CID proves decryptability.

`LegacyInventoryReader` is a storage-migration live-ID/count aid. Its filtered live IDs and
watermark do not include all tombstones, raw extension fields, repository orphans or every
retained media representation. It cannot serve as the complete adoption inventory on its own.

## Raw source pages

`PostgreSQLIdentityAdoptionSourceInventory` provides internal, account-bound raw
pages for all nineteen source kinds listed above. Each page carries the operation
revision, hold generation, total row count, ordered composite keys and an explicit
relation-presence bit. Preparation pages have generation zero and do not establish
a stable source. Fenced pages require the current accepted authorization and lease;
a changed revision, device, source DID or epoch cannot select another account.
This reader is not a public endpoint or a substitute for the initiating device's
signed transport and verification request.

Metadata preserves every stored SQL field, including future JSON fields and nulls,
instead of projecting typed models. Known binary columns are separate:
record/repository inputs retain their raw bytes, while inline media contributes its
actual byte count and SHA-256 without embedding ciphertext in JSON. Media still
requires a separate authenticated stream/readback path. Repository inventory includes
all blocks linked to the existing source DID, including retained unindexed blocks;
unowned blocks in the global content-addressed store are excluded. Inventory reads
never provision an identity or change an existing repository binding.

Pages contain at most 32 items and 8 MiB of returned metadata and binary input.
Individual raw record/block bodies and metadata are limited to 2 MiB each; oversized
or unsupported binary fields refuse the page without truncating data or reducing
the total count. PostgreSQL fetches one row at a time. Composite seek cursors compare
text components under the `C` collation, rather than joining keys with a delimiter.

The historical `sync_backups` relation is absent in a fresh migrated database but
can exist in older installations. Its absence is reported explicitly without
creating a table; if present, every owned row and extension field participates.
Missing required relations are refused. A missing historical relation and an empty
existing relation are distinct source evidence.

Raw pages are census inputs, not an accepted conversion proof. CID/canonical-CBOR
validation, MST reachability and per-item dispositions, device-side decryption,
complete legacy-schema conversion, destination ownership and verified readback
remain separate requirements. Neither a row count nor an inline ciphertext hash
proves that the journal will open after cutover.

## Verification boundary

A hold generation identifies one uninterrupted source fence. Renewal may retain completed
candidates; a new hold after expiry uses a new generation. The verifier compares all source
records, deletion markers, blob references and object evidence to the complete converted
inventory, including unknown or unsupported items. It also verifies full destination readback
and the initiating device's manifest signature before atomic cutover.

Fixtures must include overlapping legacy representations, extension fields, unsupported
versions, deletions, drafts with encrypted blocks, locations, media, missing objects and
records outside the usual typed list. Passing personal-space conversion tests does not
establish legacy-format acceptance.
