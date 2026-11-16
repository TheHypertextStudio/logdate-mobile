# Android recovery credential custody

Account authentication, journal decryption, and installation trust have separate recovery paths.
A successful Restore Credentials assertion establishes account access. It does not supply the
SQLCipher database passphrase, the journal identity key, or an independent media key.

## Material and ownership

| Material | Custody and native transport | Recovery and invalidation |
| --- | --- | --- |
| Database passphrase | The journal owns a random key. Its secure primary uses `db_encryption_key`; `files/db_recovery/passphrase_backup` is its portable copy. Encrypted native cloud backup and direct transfer include the committed copy. | Startup validates retained candidates by opening the existing database before committing a recovered key. A missing or conflicting key preserves the database. Neither account sign-in nor the identity phrase derives this key. |
| Journal identity key | `IdentityKeyManager` owns the root and its account binding. The secure primary is device-bound. `files/identity_recovery/phrase_backup` or `account_key_backup` provides portable recovery under the native encryption policy. | A matching retained phrase or account-bound backup recovers the existing identity. Importing a key through a supported account/device recovery flow does not imply possession of its phrase. An incompatible owner or malformed backup cannot authorize a new identity over retained content. |
| Independent media key | The secure primary uses `media_payload_key_v1`. Portable material is paired with the root in account backup version 2 and retained by identity binding in `files/identity_recovery/media_key_backup`. | Recovery selects the key belonging to the recovered identity. A root change cannot reuse an unrelated cached media key. A phrase alone cannot reconstruct an independently generated media key omitted from all retained copies. |
| Restore Credentials private key | Credential Manager's provider owns the private key and its delivery. The selected LogDate server owns the registered public key and challenge/replay state. The app's file backup does not export the provider private key. | A fresh accepted assertion issues an account session. Sign-out durably disables automatic restoration and queues provider cleanup. Server-side credential revocation and local provider deletion are different operations; local sign-out does not revoke other installations' credentials. |
| Access and refresh tokens | The canonical `SecureSessionStorage` vault owns tokens for an exact server origin and account. Its Keystore-bound backing and Android account state are excluded from file backup. Legacy token copies are removed from ordinary preferences. | A successful restore assertion or explicit sign-in creates a new guarded session. Sign-out, account changes, and origin changes invalidate earlier installation work. Copied account metadata cannot authenticate a replacement. |
| Peer pair keys | `SecureStoragePairKeyVault` owns local peer authentication keys. The secure store is excluded from native backup; copied successful link records are insufficient authority. | A replacement completes a fresh handshake and creates its own pair key. Forget/relink invalidates exchanges in flight. The source phone retains its own key and counters. |
| Installation identifier | `AndroidInstallationIdProvider` owns `no_backup/.logdate_installation_id`. The canonical journal owner is separate and remains in the transferred preferences. | An ordinary upgrade or in-place restore keeps its identifier. Restore onto a fresh replacement creates a new installation identifier even if copied `device.id` cleanup is interrupted. The identifier is not a credential and never replaces authentication or a peer handshake. |

Portable key files use app-private storage and Android file-based encryption; they are not
independently encrypted merely because their contents are Base64 encoded. The cloud extraction
policy therefore requires client-side encrypted transport. Direct transfer has its own platform
transport policy. Pending atomic-write files are excluded from both transports.

Deleting a local key or signing out cannot erase copies already delivered to another device or
an earlier backup. Native backup snapshots have no app-controlled remote revocation mechanism.
Authentication revocation must not be described as cryptographic erasure of journal backups.

## Restore Credentials and Block Store

LogDate does not integrate Google Block Store. Account authentication uses Restore Credentials;
portable database, identity, and media recovery material travels with the native journal backup.
Peer keys and sessions are deliberately recreated on the replacement. These requirements do not
need a second opaque provider-held copy of the same secrets.

Block Store would require its own ownership, encryption, deletion, provider availability, and
compatibility contract. Adding it does not make an account assertion recover a database key, nor
does it repair a backup that omitted the only usable decryption key. A future integration needs
a specific recovery requirement that the existing paths cannot meet.

## Recovery without a provider credential

A readable transferred journal opens offline without account sign-in. Authentication can remain
pending while local content is available. When account recovery is unavailable or a credential is
expired or revoked, explicit sign-in authenticates the account through the selected server; it
does not acknowledge unresolved content or key recovery.

An archive supplies only the entries, originals, and recovery material its format actually carries.
It does not unlock an unrelated retained encrypted database. A missing database key requires a
complete native backup or another surviving copy of that database's key. Startup retains the
encrypted journal while explaining how to repeat the transfer from an updated source phone or
restore a complete backup. It must not create an empty replacement journal to conceal the failure.

Account-bound identity recovery without a phrase requires a surviving portable key or a supported
key-provisioning flow. An authenticated session or an administrator's access to account metadata does not by itself
establish possession of a user's content key. A supported sign-in can additionally provision
the root and media keys through the encrypted account-key envelope when its unlock secret is
available. That key-provisioning step is separate from accepting the account assertion. If every
usable copy of a required key is lost, the encrypted content cannot be declared recovered.

## Retained journals and a working recovery journal

When no surviving key opens the existing SQLCipher database, the explicit Open recovery
options action retains the original database, SQLite sidecars, preferences, recovery material, and
managed originals before opening a separate working journal. Retention must be durable and
verified before detaching canonical database files. Interrupted preparation resumes through the
same state machine. A readable working journal does not mean the original journal was recovered.
The working passphrase has a separate secure alias and portable file; a later source key must
never replace it merely because its length or encoding looks valid.

Local recovery authority controls activation. A transferred WORKING marker alone cannot redirect
a fresh destination's native files. Preserve an existing local working admission before replacing
its authority token for another recovery generation. Native callbacks finish locally authorized
pending retention before selecting a destination; an orphaned local intent must never permit raw
writes over the original journal. Once a working journal is locally accepted, later native
callbacks retain incoming bytes before preference replay, owner reset, or canonical writes. Each
callback batch has its own generation. An abandoned partial batch and a later complete batch
remain separate; database, WAL, SHM, and rollback journal files must come from one coherent batch.
An unrelated truncated file must not hide complete, authenticated key candidates or canonical
media objects in that batch. Keep competing revisions separate, validate keys against the actual
SQLCipher source, and validate canonical media against its full digest. These independent inputs
never authorize an incomplete database or sidecar cohort.

Reconciliation opens a verified source copy with source-only key candidates, then uses scoped
repository mutations to recover content. It preserves working edits, deletions, and outbox intent.
Conflicting versions receive stable identities, and memberships follow those identities. Owner
and normalized server origin must match the source anchor; a completed history-owner adoption
is the only permitted bridge between different local and account owners. Missing media references
remain unavailable until verified original bytes arrive. Cross-generation media recovery requires
a canonical content digest and a matching byte checksum; matching filenames or existing URIs
are insufficient.

A readable working attachment at a canonical media path may contain bytes different from the
incoming source's digest. Import must keep those working bytes and their reference unchanged.
Publish the verified incoming bytes under a separate, digest-checked retained variant and use
that returned reference for the source note or draft. Creation of either object must never
replace an existing path, including across processes. A late-media callback may be acknowledged
only after its imported reference is readable and digest-verified, and any required row update
has committed. Acknowledgement binds the exact source path and sealed file to that returned
reference; another callback with identical bytes cannot stand in for a failed row update.
A source-authenticated unreferenced original may instead remain as a portable
private object. A readable working row whose attachment differs from the incoming source needs
an explicit Keep both choice before that incoming callback is acknowledged. Retained variants
are part of the native media inventory and device transfer.

The reconciliation mutex serializes worker instances and scratch cleanup. Resolve and open the
working database and its repository graph before taking the native delivery gate: database
initialization takes that same non-reentrant gate. Admit the canonical media root before taking
the gate as well; the first media save otherwise
tries to acquire it recursively. After taking it, revalidate the workspace,
owner, server, and open database before capturing sources or mutating content. Hold it across
source capture and merge so recovery activation cannot detach a database beneath the worker.
Release it before reporting job completion. Callback cleanup must release the delivery gate even
when custody metadata is corrupt.

Checkpoint records are atomic and bounded per entity, rather than one growing JSON object.
Persist a target identity before its mutation and record application after durable success.
An interrupted retry recognizes an already written target. Applied rows must not be resurrected
or repeatedly reimported ahead of unfinished work. Attachment repair receipts identify the
repaired projection, allowing separate arrivals to repair different attachments in one draft.
A bounded worker timeout preserves work and requests another pass.

Automatic retained recovery uses dedicated repository entry points. A pending DELETE is not
lasting deletion evidence: sync can settle it before a late backup arrives. Record scoped deletion
evidence with the row removal and outbox mutation, and check both the original and mapped target
identities inside the recovery transaction. Deleting an association or draft follows the same
rule. Keep explicit archive recovery separate so an intentional recovery can restore deleted work.
Owned media cleanup runs after the outer transaction commits.
Consented owner adoption carries deletion decisions for that exact origin before binding the
account. Its durable transfer receipt must prevent a later retry from reintroducing a decision
that an explicit save already cleared. Deletion intent does not authorize adopting draft bodies.

Audio recovery preserves typed transcription rows and segments, including their original status,
revision, timestamps, and speaker metadata. Create the audio row, transcription, segments, outbox
mutation, and application receipt in one transaction. Do not first synthesize a normalized
transcription through the ordinary note importer. A deleted transcription stays deleted until an
explicit transcription request; conflicting transcripts require a stable separate audio identity.

Retained custody remains available after a merge. Nonempty tables without typed recovery handlers
prevent a complete-recovery claim. Cloud backup must not replace a complete older snapshot with
an unresolved working-only journal. Direct transfer includes verified retained generations.

Acceptance fixtures visit the real first-launch Activity before seeding a journal, so privacy
initialization follows the production path. Never forge initialization or accepted-working
receipts to make a test pass. A native callback test that shares a process with other callbacks
must distinguish its new completion token from an earlier token. Policy tests, actual SQLCipher
copy tests, scheduled-worker tests, rendered recovery, and cold-process verification establish
different parts of this contract.

## Unfinished drafts across authentication changes

Draft ownership follows the retained journal, independently of whether its account session is
available. Resolve the canonical owner and wait for the persisted server configuration before
choosing a draft storage scope. Signing out and signing back into the same account must retain
the latest offline edit. Another authenticated owner must not see or adopt that local work.

Older anonymous or legacy storage does not establish a server origin. A surviving pending draft
operation for the exact owner and origin can establish upload custody. Copy the complete draft
and a durable recovery receipt before repairing its pending upload or removing its unchanged
source. Divergent versions with the same identifier need stable separate identifiers so an
interrupted retry neither overwrites one version nor produces repeated duplicates.
For the first account sign-in, a completed, consented history-owner adoption can connect the
previous local owner to the authenticated owner. Both the adoption's exact server origin and a
surviving pending operation for that draft under the previous owner are required; an adoption
receipt alone does not establish an older draft's server custody.

When the original pending operation has already been settled, keep the draft visible locally to
its canonical owner without automatically assigning it to a server. Continuing and saving the
draft establishes the selected server scope. Local display and sync enumeration therefore have
different contracts: visibility alone never authorizes an upload. A missing scoped record cannot
settle a pending upload while an unclaimed local copy remains. Storage failures must propagate
before a caller acknowledges a save or deletion.

An ordinary draft edit or deletion records its original account, server, and upload intent in the
same durable value as the changed draft. Repairing that intent must not overwrite a newer queued
mutation. Clear the local intent only after the outer SQL transaction commits, using
`SyncTransactionManager.afterCommit`; returning from a nested transaction is insufficient.
Rolled-back nested work must discard its own cleanup callbacks even when the enclosing operation
catches that failure and commits other work. Always acquire the SQL transaction before the shared
draft storage mutex, and run post-commit cleanup after releasing that mutex.

## Implementation references

The authoritative implementations are [DatabasePassphraseProvider](../../client/database/src/androidMain/kotlin/app/logdate/client/database/encryption/DatabasePassphraseProvider.kt),
[AndroidIdentityKeyBackupStore](../../client/device/src/androidMain/kotlin/app/logdate/client/device/crypto/AndroidIdentityKeyBackupStore.kt),
[SecureSessionStorage](../../client/device/src/commonMain/kotlin/app/logdate/client/device/storage/SecureSessionStorage.kt),
[BackgroundRestoreCredentialRecovery](../../client/data/src/commonMain/kotlin/app/logdate/client/data/account/BackgroundRestoreCredentialRecovery.kt),
[AccountKeyUnlockCoordinator](../../client/data/src/commonMain/kotlin/app/logdate/client/data/account/AccountKeyUnlockCoordinator.kt),
and [SecureStoragePairKeyVault](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/peer/link/PairKeyVault.kt).
[Native Android backup and restore](native-android-backup.md) defines extraction rules, callback
boundaries, failure handling, and emulator acceptance. Real provider delivery, encryption/lock
conditions, expiry/revocation, offline setup, and sign-in fallback require provider acceptance
with a dedicated account and matching app/server identity.
