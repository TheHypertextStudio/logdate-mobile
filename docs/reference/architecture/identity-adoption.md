# Identity adoption boundaries

Adoption preserves the account UUID, passkeys, device registrations and account-root keys. It changes
the canonical DID and advances its identity epoch only after a device has prepared and independently
opened the destination. The original remains authoritative until atomic cutover. The external PDS
does not move, and the server never receives journal plaintext or account-root private keys.

The capability must remain disabled until the server mutation fence, proof-bearing staging and
commit endpoints, complete device conversion and recovery, and compatible clients are available.
The presence of persistence types or successful unit tests does not enable adoption.

## Publication and retirement evidence

A stored PLC CID can be computed locally and does not establish publication. New hosted operations
record whether directory publication was attempted; older rows retain unknown status. Identical
stored retries of the same signed genesis are one fact, while conflicting genesis history is refused.

Every directory submission records an attempt before making the request, including submissions for
which no operation row exists yet. A failed or uncertain request cannot subsequently become evidence
that an identity stayed private. Publishing without a supported history store fails before submission.

The DID-level attempt ledger survives account deletion. It retains only a DID and the first recorded
attempt timestamp, with no account link, journal, credentials or keys. This minimal irreversible fact
prevents account recreation from disguising a prior attempt. Signed operation rows and account-owned
private material remain subject to account deletion. The ledger is not proof that a request succeeded;
uncertain publication history still requires reconciliation before adoption.

Retirement is a separate immutable local prohibition. It survives account deletion and restoration;
it does not submit a PLC tombstone for the old unpublished DID. A profile update cannot restore hosted
authority or reset the identity epoch.

## Required transaction order

The complete mutation fence must follow one order across instances. Acquire an account credential
coordinator, when needed, before OAuth DID coordination and sorted DID-claim locks. Acquire the account
ID claim for account insertion/upsert or deletion, then the account row before operation, space-state,
record, blob and storage-total rows. Identity transfer and provider
HTTP calls must not hold a database transaction open.

| Mutation                                        | Required database ordering and boundary                                                                                                                            |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| DID reservation and enrollment                  | Sorted DID claims, account-ID claim for creation, then account and operation ownership; account/passkey creation is atomic.                                        |
| Record put or delete                            | Account and expected epoch, space state, record, sorted referenced blobs, storage total.                                                                           |
| Upload reservation                              | Account and expected epoch before storage total and reservation.                                                                                                   |
| Blob finalization and receipts                  | Account and expected epoch before receipt coordination, blob and storage total; recheck after transfer.                                                            |
| Tombstone purge                                 | Resolve the candidate owner, then account before space state and tombstones; retained source namespaces are protected.                                             |
| Blob purge                                      | Account before blob and storage total; uncertain object deletion must not make its path reusable.                                                                  |
| Legacy storage switch                           | Account epoch and hold checks in the same transaction as storage-mode and migration-version changes.                                                               |
| Legacy object cleanup                           | Establish current account/epoch ownership before external deletion, rather than checking only the final SQL erasure.                                               |
| Account deletion                                | Outer credential coordination, account-ID claim, then account before dependent rows; deletion overrides staging and captures durable object cleanup.               |
| Hosted signing-key mutation                     | Account and originating epoch before signing-key rows; refuse adoption holds, inactive/moderated owners, external kind and retired DID.                            |
| Hosted provisioning or publication              | Recheck account authority, retirement and active identity holds before mutation or submission.                                                                     |
| Legacy media, backup and ATProto blob metadata  | Account and originating epoch before media, backup or blob rows. Composite repositories forward the same epoch to each store.                                      |
| Legacy sync records and retention               | Account before content, journal, association and tombstone rows; global maintenance must select owners and fence each owner separately.                            |
| Repository-backed collections                   | Account before collection state, records, repository head and commits; the journal-merge advisory coordinator does not substitute for this row lock.               |
| Legacy collection metadata and Moment retention | Account at epoch zero and retired canonical/preferred DID checks before collection state/version or record rows; global retention processes each owner separately. |
| Resource pointers                               | Originating account UUID and epoch before pointer rows; upsert preserves the first owner atomically, and delete includes that original account in its predicate.   |
| External media and backup deletion              | Persist a claim bound to the account, epoch and exact physical path before object I/O; pending claims exclude the final adoption hold.                             |

Do not invoke a suspending public store API from an already-open account transaction. Atomic promotion
uses transaction-local operations rather than acquiring an outer coordinator from inside that transaction.
A timer is not evidence that an earlier mutation or external delete finished.

Mutation fences require READ_COMMITTED isolation. An older repeatable-read snapshot can miss an
operation inserted after that snapshot even after taking an unchanged account row's lock. The fence
rejects unsupported isolation before mutation. Read-only inventory snapshots must not wrap ordinary
mutation APIs.

Unversioned legacy adapters represent epoch zero. They reject an explicit nonzero epoch rather than
discarding it. A caller must not substitute a freshly read server epoch for a request's originating
epoch. Unknown identity kinds, unknown storage modes and retired current DIDs refuse writes; their
stored values remain intact for a compatible client or explicit recovery.

The legacy `DbSyncRepository` content, journal and association mutation helpers and per-owner
retention require epoch-zero legacy storage and check persisted migration holds before dependent
rows. Their shared migration configuration also applies to location history: its upload takes the
account fence before allocating or locking a location version, so a refused batch cannot advance a
cursor or save a partial result. Reads remain available for inventory and reconciliation. A location
upload refused at the final database check returns a stable account conflict or unauthorized response
without exposing the internal exception. Global retention discovers candidate owners first, then
processes each in a separate guarded transaction. A refused owner retains its inventory while other
owners remain eligible for cleanup. Account-scoped archive and media helpers retain their separate
epoch/path fences; a legacy collection-format rule must not silently block format-independent storage.
Collection metadata remains a legacy-format store: after locking the account at epoch zero it also
requires legacy storage and checks the persisted migration phase and hold under that same lock. A
storage switch can occur without advancing the identity epoch, so checking the epoch alone is
insufficient. An active uploading hold refuses a write; an expired hold or uploading without a hold
permits it. Unknown phases and inconsistent migration epochs refuse without rewriting stored state.
The store shares the migration configuration with the migration service. Its writable preflight uses
the same guard, and each actual mutation repeats it. It does not accept an inferred current epoch to
resume writes after adoption. The target personal space has its own mutation boundary. Its retention
leaves held, moved or retired source metadata intact while eligible legacy owners can still be
processed. Hosted public-repository writes use a separate boundary and remain available for
personal-space accounts. Resource
pointers are format-independent but require an explicit epoch for an advanced account, and deletion
cannot infer authority from whichever account currently owns a resource ID.

Repository writes carry an immutable originating account UUID and epoch in the server coroutine
context. The PostgreSQL repository transaction locks that original account before its body, then each
head, block, commit or clear operation rechecks hosted authority and the requested DID binding.
Unbound calls cannot infer authority from the current DID owner. A nested transaction cannot replace
the origin or attach itself to an unrelated database transaction. First-party legacy callers use epoch
zero; upgraded tokens must supply their authenticated epoch rather than copy a fresh account value.
The authenticated PDS service keeps the complete mutation inside this transaction and unwraps a
failed result before leaving it, so partial writes roll back. Reads do not acquire write authority.

Content-addressed blocks must hash to their CID, and an existing block cannot acquire different bytes
through either the database or the warm cache. A stored commit revision is immutable, and its header
must name the repository receiving it. These storage checks complement the repository engine's
signature and tree validation; storage-level test fixtures are not cryptographic acceptance.

Remaining transfer and migration cleanup paths, legacy-format transition checks and upgraded
request/session authority still require the ordering above before adoption can be enabled. A metadata
guard invoked after a repository write does not prove account-first ordering for an encompassing
operation that bypasses the owned transaction.
Legacy media copying treats a failed metadata acknowledgement as an uncertain commit. It first
preserves a candidate already named by the space's canonical blob row. Other candidate cleanup uses
the global reference census and durable exact-path deletion claim, including duplicate-copy losers
and readback mismatches. A failed lookup or refused cleanup retains bytes and preserves the original
copy result or cancellation. Confirmed unreferenced candidates are retired before provider deletion;
failed deletions remain discoverable for maintenance. This does not substitute for pre-transfer
admission or late-account-deletion recovery, which must cover the whole upload lifecycle.

A metadata refusal after an object has already been deleted cannot protect an
inventory. The journal-merge coordinator also requires a provider-safe connection lifetime: a raw
advisory transaction on another connection is not detected by the Exposed ambient-transaction check.

## Quarantined object deletion

Legacy media deletion, backup deletion and backup retention persist an exact account, epoch,
resource and physical-path claim before object I/O. A pending claim prevents the final adoption hold
and replacement of that resource. Registration in the media, backup, ATProto, personal-space and
legacy sync stores refuses claimed physical paths. Path coordination and a reference census prevent
deleting a path still registered to another resource. The logical resource can be removed only when
the same claim and metadata are completed atomically.

Callbacks run outside SQL transactions. Entering deletion from an ambient transaction is refused.
Failure or cancellation retains the claim; a retry must retain the original epoch, path and resource.
The storage operation must confirm deletion or absence. The older Boolean delete method conflates
absence and provider failure, so it cannot resolve an uncertain deletion acknowledgement safely.
Completed claims retain physical-path retirement: a delayed callback must never delete a newly
registered copy. This costs retained path metadata; removing it requires a separately proven cleanup
policy. Account deletion keeps its existing durable object-cleanup obligation and does not erase
these claims or permit the deleted account UUID to return.

An upload exception does not prove its metadata failed to commit. Backup rollback, rejected or
losing media candidates, and superseded media cleanup therefore use an unreferenced-path claim,
with no metadata-removal action. Every registered store participates in the reference census. A
registered path, an identity hold, a stale epoch or an uncertain database read prevents physical
deletion. This preserves a committed archive even when the acknowledgement of its metadata write
was lost. An unreferenced candidate can still be removed without abandoning the retirement ledger.
Candidate claims use the same durable barrier and path-retirement rules as registered deletion.
Failed candidate cleanup needs maintenance recovery: retrying the upload creates a different object
and does not retry the previous claim. That recovery is a rollout prerequisite.

Before removing an unreferenced encrypted object, maintenance locks the account and blob, checks the
current epoch and namespace, and stores a purge operation and its epoch on the blob. It then commits
before contacting object storage. Completion checks that same account, epoch, namespace, operation
and physical path before deleting metadata and reducing storage usage.

A failed or timed-out object request leaves the quarantine intact. The blob remains charged against
quota, but downloads, upload receipts, new references and upload deduplication cannot reuse it. Uploads
answer a temporary-unavailability response and remove their own unsaved candidate. Maintenance retries
the same owned path even when a newer retention cutoff would not select it as a fresh candidate.

An adoption hold refuses while an unresolved deletion remains. A timeout never proves that an earlier
callback stopped: after confirmed cleanup, a new upload uses a fresh physical path, so a late callback
can affect only the retired object. Destination staging must likewise allocate fresh physical paths;
reusing authenticated ciphertext or its CID does not authorize aliasing the source's storage object.

Tombstone/cursor cleanup and reservation expiry take the same account fence. A trusted maintenance
run uses current account authority to expire old quota handles; an old upload request retains its
original epoch and cannot complete or release a reservation under a newer authority. Retired source
namespaces remain protected even when a request acknowledges the new account epoch.

These guarantees require compatible guards on every serving instance. Persistence migrations alone
do not make an older server participate in the fence. Provisioning, publication, account deletion and
legacy storage changes must share the boundary before adoption can be enabled.

## Legacy storage and cleanup

Existing legacy-storage moves retain their originating identity epoch. Migration compare-and-set and
storage-mode switching check that epoch and account standing inside the account-row transaction,
then match the prior migration ID, version and epoch. Learning a newer epoch cannot relabel a stored
move or authorize resuming its old cleanup after identity cutover.

The existing CLEANED phase with no completion timestamp is a durable cleanup claim. It blocks the
final adoption hold, including after a worker restarts or only some object deletions succeeded.
Erasure requires that exact unfinished claim, the original epoch, and personal-space storage mode
before reading paths or contacting storage, and again before deleting legacy SQL rows. A missing
storage provider preserves the sole path inventory. No database transaction remains open during
object calls, and a changed authority never becomes a completed-cleanup receipt.

Normal cleanup does not select old-epoch legacy copies after adoption. Their later deletion needs
the separately reviewed retention and recovery policy; an old maintenance job cannot supply it.

## Retained source and interruption

An unrenewed final hold lasts at most 120 seconds. Renewal requires the current operation revision.
Expiry returns the operation to preparation, advances its revision and invalidates verification;
it does not claim success or silently release the target DID to another signup.

Cancellation releases only that operation's reservation. A generic phase transition cannot manufacture
verification or a commit receipt. A lost response is recovered through the same durable operation and
receipt, with account, device and epoch checks before a client changes its local scope.

The source remains an inaccessible recovery copy after commit. Ordinary maintenance must not destroy
it by age alone. Cleanup requires a separately reviewed policy and verified recovery coverage; account
deletion retains its explicit authority to remove both namespaces and their encrypted objects.

## Deleted account IDs

Deleting a cloud account writes an immutable, domain-separated SHA-256 digest of its UUID and the
first deletion timestamp in the same transaction as the deletion. The ledger contains no raw UUID,
DID, profile, credentials, keys or journal content. Creation, enrollment and missing-row profile
upserts serialize on the same account-ID claim and refuse a deleted ID. This prevents a delayed
request from recreating an owner whose tokens or cleanup work still reference that ID.

The deleted cloud ID cannot be reused; restoring journal data uses a current or new account.
Identity adoption does not delete the account or add its UUID to this ledger: it preserves the
existing UUID while changing the DID and epoch. Ordinary profile saves also preserve stored account
activation state; only the explicit account lifecycle operation deactivates it.

## Hosted profile and key writes

Ordinary profile updates preserve DID, handle, hosted signing/recovery keys, identity kind, epoch,
publication consent and activation state. This applies to an account whose identity has not yet
been provisioned. Identity provisioning and deliberate key changes use a separate atomic conditional
write. Rotation and import must report a conflict when that write loses; another complete identity
is not evidence that their requested key was installed.

Signing-key creation, replacement and revocation carry an originating epoch. Legacy callers mean
zero, and cannot write after the account advances. The account row is locked and standing, hold,
identity kind and retirement are checked inside the same transaction as the key-row write. Key
ciphertext rewrapping reads ownership without a key-row lock, then acquires the account before its
conditional key update. Refusal never revokes or overwrites the existing key.

These local guards do not alone make a provider operation and account/key update atomic. Durable
coordination across provisioning/publication, and reconciliation of partial provider outcomes, remain
required before adoption can be enabled.

## Hosted PLC history and incomplete metadata

PLC operation rows retain the epoch that created them. Append, publication marking and CID updates
lock the operation's DID claim before its account, then check the originating epoch, account standing,
hosted kind, adoption hold, DID ownership and retirement. A late callback cannot supply a newer
account epoch to relabel an older operation. CID/attempt updates resolve ownership without locking
history before its account. The independent DID-level publication-attempt ledger remains evidence
and does not grant permission to submit a provider request.

Provisioning can initialize an account with no DID. Once a DID exists, missing handle or signing-key
metadata requires reconciliation; provisioning cannot replace that DID or create another key/history
as a repair shortcut. Complete metadata can still be normalized through the conditional identity API.
This preserves established authority at the cost of refusing incomplete accounts until their metadata
can be reconciled from authoritative history.

## Pre-submit publication admission

All PLC submissions pass the account UUID, exact DID and originating epoch to a lifecycle admission
operation. It takes the DID claim and account lock, validates standing, kind, ownership, retirement
and any adoption hold, and records irreversible attempt evidence in the same transaction. Only after
that transaction commits may the provider call start. Legacy callers mean epoch zero; stored-genesis
publication carries the original row's epoch through marking and confirmation.

If the adoption hold wins, publication is refused before contacting the provider. If publication
admission wins, the durable attempt makes that source ineligible for adoption, including while HTTP
is waiting or after failure/restart. A timer cannot erase this evidence. The conservative cost is
that cancellation after admission but before bytes reach the provider still disqualifies the source.
The separate evidence-recording API grants no admission, and lifecycle admission itself establishes
no user consent: the explicit publication workflow remains a separate requirement.

No SQL transaction spans the provider call. This is a publication/adoption exclusion boundary, not an
atomic transaction across the provider, local key store and account. Account-less in-memory history
can only model local claim/epoch binding and cannot advertise database adoption capability.

## Initial provisioning across server instances

First-key creation checks for an existing active key while holding the account row, and inserts only
when none exists. It returns the winning key to concurrent callers. Multiple existing active keys
are an explicit reconciliation error: provisioning does not choose one or add another. A missing
key after account authority or PLC history exists also requires recovery rather than replacement.
Existing key material is preserved; there is no automatic duplicate-key cleanup.

A signed genesis candidate is claimed durably under the account lock before publication. Retries
reuse the stored signed operation and derived DID, including after a restart or configuration change.
Account assignment takes the handle and signing authority from that operation, rather than combining
its DID with a newly requested handle. Inconsistent history or a mismatch with the active key stops
provisioning for reconciliation. Exact historical duplicate genesis retries remain readable; retries
through the new claim path do not add duplicate rows.

Unassigned genesis history also reserves its DID against other accounts' enrollment or adoption.
Candidate mutation takes its DID claim before the account. Reusing the already-owned canonical row
reads it under the account lock without taking another DID lock afterward; publication and account
assignment subsequently revalidate their own DID claim. Provider HTTP remains outside transactions.
The cost is preserving the first canonical configuration until an explicit identity update, rather
than silently minting another identity to match newer configuration.

## Imported keys and private session continuity

A signing-key import validates the public-key encoding, declared curve and private-key parameters,
then signs and verifies a fresh random local challenge with the existing crypto implementation.
Preparation fails before activation when the pair does not match. Claiming the current public key
cannot make unrelated private material an unchanged-key import. Valid P-256 and K-256 pairs retain
their existing import/recovery behavior; no private material or challenge leaves the server.

A private LogDate session reads current account authority by UUID. A valid already-owned hosted DID
can identify sealed journal storage even when its handle or signing metadata needs repair. Account
read/profile and journal operations do not create a replacement hosted identity in that state.
Unsupported, missing or inactive accounts remain unavailable; incomplete moderated accounts do not
use this metadata-degradation path. Malformed stored identity values still require reconciliation.
External identity state is retained as stored, and public identity management keeps its stricter
metadata, consent and lifecycle checks. This boundary does not bypass mutation epochs or holds.

## External upload admission

PDS uploads retain the authenticated account UUID and identity epoch. Looking up the
repository DID may confirm that binding, but cannot replace it with a new owner or
refresh its epoch after provider I/O. Each upload uses a fresh physical object path;
the content identifier continues to name the blob's bytes.

Before a PDS write, the database adapter locks and checks hosted account authority
and repository ownership. Legacy media instead checks epoch-zero authority, legacy
storage mode and migration holds. Backups check the captured account epoch without
requiring legacy storage mode, so a move to personal-space storage does not remove
backup support. Every admitted write persists its scope and exact planned provider
path as `WRITING`.
Path planning performs no provider I/O. Pending intents prevent the adoption fence
from being acquired. The provider writes outside SQL transactions. A confirmed
provider completion advances the intent to `STORED`; metadata registration and the
`ATTACHED` transition share the same transaction and recheck the original authority.
The intent has no cascading account foreign key, so deletion cannot erase the only
record of a write that is still in flight.

If registration is refused after a confirmed upload, cleanup first checks every
registered storage reference and durably retires the exact candidate path. It then
deletes outside SQL and records completion. Failed deletion remains `DELETE_PENDING`
for a subsequent worker. Retirement prevents that path being registered again while
a delayed deletion can still occur. An attached canonical object is never treated
as an orphan merely because its acknowledgement was lost. This narrowly scoped
cleanup can finish for the deleted original owner without borrowing a current
account's authority.

An exception or cancellation from the provider leaves its write outcome uncertain.
Such an intent remains an adoption barrier; elapsed time alone does not establish
that a remote write stopped. Reconciliation of uncertain writes, scheduling pending
cleanup, and collection of superseded completed objects are maintenance requirements.
Personal-space streaming uses this durable admission lifecycle. Migration copies
still need operation-bound admission before adoption can be enabled. Non-database
servers do not advertise durable adoption support.

The blob route returns fixed error text rather than provider or database exception
messages. A held account returns `IdentityAdoptionInProgress` with HTTP 409; changed
authority returns `IdentityChanged` with HTTP 409; an unavailable account returns
`AccountUnavailable` with HTTP 401. Invalid input uses a fixed HTTP 400 response,
and other failures use a fixed HTTP 500 response. Clients must preserve pending
local work and reconcile account state before retrying an identity conflict.

Storage adapters used for this path must provide an exact pure path planner.
Unsupported adapters fail before admission or upload rather than recording an
assumed destination that could differ from the provider's actual object.

Legacy media and backup routes use the database coordinator in the production
runtime. Non-database route fixtures retain their direct storage behavior. A
write-once receipt can resolve to an earlier winner during final registration;
that transaction leaves the losing candidate unattached, and cleanup retires only
its path. The response identifies the canonical stored content rather than the
losing request's content identifier.

The journal distinguishes repository, media and backup authority explicitly. It
never invents a repository DID for a media or backup write. Cleanup tickets preserve
their original scope and epoch across a restart, including a null repository DID.
Legacy HTTP routes currently retain epoch-zero authority. Issued credentials and
route claim extraction must carry verified epochs before identity cutover is
exposed; reading a newer account epoch after I/O is not a substitute.

Personal-space admission additionally binds the exact personal-space URI to the
captured account and epoch. Both admission and final registration check reverse
migration holds. The space store owns finalization: its transaction updates the
intent together with blob metadata, receipts and quota. A suspending store call
must never run inside the generic upload coordinator's transaction. If an existing
receipt or CID wins, the new candidate stays unattached and only its path becomes
a cleanup candidate.

A failed stream must stop writing before cleanup may retire its object. Writers
provide a non-destructive close for this purpose. Filesystem writers remove their
owned temporary file; committed objects remain subject to the reference census.
The Google Cloud writer closes the channel before integrity verification and never
deletes a candidate merely because verification failed. Google documents that
buffered writes may become visible on close in its [WriteChannel contract](https://docs.cloud.google.com/java/docs/reference/google-cloud-core/latest/com.google.cloud.WriteChannel).
If close fails, the writer preserves that uncertainty on later calls and the
intent remains `WRITING`. This deliberately retains bytes and blocks adoption
rather than assuming a failed close stopped a remote write. Provider reconciliation
must resolve that barrier; a timeout alone cannot authorize deletion or cutover.

## Fresh adoption passkey proof

Adoption uses its own `identity_adoption` passkey challenge purpose. Ordinary
sign-in, recovery grants, OAuth authorization and disconnect challenges cannot
answer it, and an adoption challenge cannot be spent through those actions.
The ceremony lists only the account's registered credentials and refuses to begin
when there are none. It requires cryptographic verification even when the server's
development profile would otherwise permit lightweight passkey fixtures.

The challenge binds a fresh server decision identifier. Verification checks both
that exact decision and the registered credential's account, then spends the
challenge once. Durable challenges retain purpose, binding, expiry and consumption
across server instances. A different decision cannot consume the matching one;
a malformed or foreign-account assertion for the matching decision cannot become
a usable grant on retry.

The durable decision binds the account, operation and revision, source epoch,
initiating device, source DID and proposed target DID through a framed,
domain-separated digest. It retains only a digest of the challenge, never the
assertion or a provider credential. A five-minute expiry bounds confirmation and
consumption. Repeating a request cannot extend that expiry, rebind the decision,
or clear authorization or consumption. SQL constraints enforce these invariants.

The proof service verifies the registered passkey outside SQL transactions. It
then rechecks current account authority, operation revision, target reservation
and never-published source history before recording authorization. A lost
confirmation response can return the same authorized decision without spending
the WebAuthn assertion again. Changed or uncertain publication history, account
deletion and changed epochs refuse further use.

Consumption locks the target identity claim before the account and executes the
local metadata mutation on the same connection as the consumption marker. A
failed mutation rolls both effects back. A second confirmation identifier cannot
repeat an action for the same operation revision: the transaction checks prior
consumption and a database uniqueness constraint enforces the same boundary.
Callbacks must not perform provider I/O or call another suspending repository.

A preparing proposal and its reservation alone confer no staging, cloud-write
hold or cutover authority. The protected service must independently verify the
operation-bound OAuth identity and authenticated device, then consume the fresh
passkey decision while durably authorizing the operation. Five-minute proof
expiry does not terminate an already authorized migration; recovery uses durable
operation state and receipts, never reuses a consumed assertion. Capability gating,
private staging and atomic identity cutover remain required before exposing
adoption. No general sign-in grant substitutes for this separate action, and none
of these local proofs authorizes an AT Protocol directory or repository write.

## Existing-identity browser return

Adoption OAuth attempts use a separate encrypted purpose. The original account,
source DID and epoch, initiating device label, server proposal identifier and app
PKCE challenge are bound before provider authorization. The callback observes
that original source without acquiring an account lock after the attempt lock.
The app exchange takes the account fence before the attempt row and rechecks
private-source eligibility. Ordinary enrollment and reconnect cannot consume an
adoption attempt, or vice versa. No provider token is returned to the app.

Successful exchange retains the same verified identity and original deadline for
an exact retry. It cannot extend authorization or mint a different proposal.
The proposal service checks that deadline before allocating the operation, and
the operation transaction rechecks source authority and publication history.
The server proposal identifier is also the durable operation identifier: a
canceled proposal stays canceled, a competing proposal cannot borrow the active
operation, and an exact committed proposal returns its original metadata.
Proposal locking precedes the target identity claim and account locks.

These exchanges create only a preparing reservation. They do not authorize
staging or identity changes, issue journal credentials, replace a session, or
prove possession of a device key. Protected routes still require independent
device authorization and the fresh adoption passkey decision before granting
migration authority.

## Accepted migration intent

The authenticated exchange records the original provider proof deadline with
the exact proposal binding in the same transaction as proposal creation. Retry
does not renew or rebind that proof. Repository metadata created without this
verified handoff remains unprivileged, even if someone subsequently confirms a
registered passkey for it. An expired provider proof cannot be replaced by only
a fresh passkey assertion.

A confirmation decision binds a device-local P-256 public key and the canonical
LogDate backend origin. Its signature covers the account, operation, source and
target DID, source epoch, device label, revision, decision and passkey challenge.
The key and origin enter the decision's binding digest and cannot be changed
after issuance. A wrong device signature is rejected before spending WebAuthn.
P-256 verification accepts standard ECDSA signature forms produced by CryptoKit;
these are private LogDate confirmation signatures, not AT Protocol operations.

The authorization service checks current proposal eligibility before verifying
the real passkey and device proofs. It then consumes the decision and inserts
one immutable operation authorization on the same database connection. An insert
failure rolls consumption back. Concurrent instances recover the same committed
authorization rather than creating or replacing another key. Account ownership
comes from the referenced operation, avoiding a second mutable owner field.

This accepted intent outlives its initial five-minute proofs. A lost confirmation
response can recover it using the original decision, challenge and device
signature without spending WebAuthn again. Reads recheck source authority,
publication history, reservation and binding; canceled, deleted or changed
identities cannot reuse it. Operation revisions and holds may advance without
erasing the original confirmation. Authorization metadata reads do not thaw an
expired hold or exempt any ordinary request from its write fence.

The authorization is not a journal token, lease or commit receipt. Staging and
cutover must still check their own current revision, device request proof, hold,
complete inventories and manifest. No OAuth callback, proposal or local
authorization marker alone changes canonical identity or publishes data.

## Account response generation

Authenticated account responses carry the canonical `identityEpoch`, including zero
before a hosted identity is adopted. Profile updates preserve the server-owned
value. Shared account decoding and conversion retain a supplied generation; an
older server response leaves it unknown rather than implying zero.

An account refresh is observation, not migration approval. Clients still require
the matching operation receipt, verified destination and current journal binding
before installing the target identity. In particular, obtaining a fresh token or
seeing the target DID does not establish that local pending edits were reconciled.

## Legacy copy migration binding

A legacy file copy captures the original storage-migration identifier, revision
and identity epoch before transfer. Its durable upload admission retains these
claims alongside its original account, personal space and physical object path.
Admission and metadata attachment take the account lock first, then require the
same uploading migration revision and legacy storage mode. A newer revision,
switch or account authority cannot authorize completion of an older copy.

Upload bindings are immutable in PostgreSQL. Blob metadata and the admission's
attached phase commit together. Cleanup compares the exact original ticket and
checks all object references; it does not require the migration to remain active.
This permits safe retirement of an unattached stale copy while preserving an
attached destination or an uncertain provider write.

## Legacy journal merge coordination

The merge coordinator's advisory transaction serializes merge operations across
instances. It does not authorize later metadata writes. Each save, replacement,
removal or tombstone purge takes the account identity fence on the same JDBC
connection as its mutation. These legacy rows retain epoch-zero authority;
reading a newer session does not grant permission to modify the retained source.
Global retention checks each owner independently and skips held or changed owners.

The outer advisory transaction is represented in coroutine context as well as
JDBC. Upload admission refuses that context across dispatcher changes, since
Exposed's current-transaction check alone cannot observe a raw JDBC transaction.
Provider writes remain outside both kinds of transaction.

## Private operation status

Operation status and its durable receipt are observed in one PostgreSQL statement.
A concurrent commit therefore returns either the earlier operation without a
receipt or the committed operation with its receipt. A committed phase without a
matching account, source, target and next-epoch receipt is inconsistent and is
refused. Status reads do not renew holds, expire them or grant staging authority.

The protected HTTP contract uses a full first-party account session. Linked
sync devices, moderated accounts and foreign account identifiers cannot read it.
Responses prevent caching; authenticated reads are bounded by the existing sign-in
request policy. Storage failures return a fixed error without private diagnostics.
The shared Kotlin DTOs and native client use the same explicit identity epoch,
revision, phase and receipt fields.

The status route component remains disabled by default and is not registered in
the production composition until the complete adoption service is available.
Production rollout must keep recovery reads distinct from permission to begin a
new adoption, so disabling new moves cannot strand a committed receipt.

## Device-signed operation actions

After confirmation, each private mutation requires a fresh server challenge and
signature by the operation's original device-local key. The action message has a
separate purpose from passkey confirmation. Its length-framed fields include the
validated immutable confirmation binding, current operation phase and revision,
challenge identifier and nonce, action name, and lowercase SHA-256 request-body
digest. A confirmation signature cannot authorize staging, renewal or cancellation.
The native client reopens its existing bound key; signing never creates a missing
key or changes the active journal scope.

The server retains only the nonce digest. A challenge lasts at most thirty seconds
and never outlives an active hold. Each operation has at most thirty-two outstanding
unexpired challenges. Issuing a challenge never renews a lease or grants a write exception. For a
matching expired hold, the explicit request first returns the operation to preparing
and advances its revision without issuing a nonce. The client must refresh status
and request a fresh signed hold or cancellation at that revision. This permits
recovery without an unrelated cloud write and invalidates earlier verification;
passive status remains unchanged. Challenge binding fields are immutable, and
consumption is one-way.

Request adapters evaluate lease and challenge deadlines using the server clock after
acquiring the account and operation locks. A queued request whose deadline elapsed
while waiting is refused; it cannot renew the elapsed hold generation. The runtime
supplies that clock to the signed-action and lifecycle storage adapters. Copy,
source readback, verification and promotion also recheck validity after their work;
expiration rolls back partial SQL output and nonce consumption together.

Consumption checks the original eligible account, current revision and phase,
reservation and accepted device-key binding under account-first locks. The nonce
marker and private SQL mutation use the same transaction. Failed SQL rolls both
back; concurrent instances cannot repeat the mutation. Changed publication history,
account deletion, foreign devices, expired challenges and stale revisions refuse
before entering the mutation. Raw JDBC context prevents provider upload admission
inside that callback. External transfers require separate admission and finalization.

Hold, renewal and cancellation have no extra payload, so their body digest is
SHA-256 of the empty byte string. Their binding and expected revision are already
signed. Renewal returns to the fenced phase, invalidating any previous verification;
record conversion and uploaded candidate bytes remain independently resumable.
These lifecycle actions never replace the canonical DID or keys. The account-first
action executor refuses commit: canonical cutover requires its own sorted DID-claim
locks before the account, complete inventory verification and atomic receipt.

## Private record staging

A private candidate belongs to the original account, operation and uninterrupted
hold generation. Beginning or restarting a hold sets that generation to the new
operation revision; renewal preserves it. A restarted hold cannot read or replace
an earlier generation's candidates. Those retained bytes stay accounted for until
owned cleanup is permitted.

The personal-space record adapter checks the full canonical source bytes, CID,
size, version, digest, deletion marker and exact owned blob references under the
same account and operation locks as device-proof consumption. It accepts the raw
v1 sealed envelope without a generated-model round trip. Unsupported or changed
source data refuses the copy. Legacy repository and sync formats require their
own complete source adapters; the personal-space adapter does not establish their
acceptance. See [Source inventory](../standards/identity-adoption-source-inventory.md).

The signed write body is canonical DAG-CBOR with `version: 1`, `rkey`,
`sourceVersion`, lowercase hexadecimal `sourceDigest`, `record` and `blobs`.
`record` is the complete sealed envelope, or null for a tombstone; blob CIDs are
unique and sorted. Immutable detached bytes bind the exact request before any
suspension. The server determines the hold generation and source tuple itself.
A retry can return the same candidate, but cannot replace its ciphertext in that
generation. Normal SpaceStore and XRPC reads never consult the staging table.

Private readback requires the distinct `READ_STAGED` action, available only while
fenced or verified. Its signed canonical body is `{version: 1, rkey}`. The original
operation key, current revision and a fresh single-use nonce protect the read.
A wrong device, changed key or another generation cannot borrow that proof. The
server recomputes the candidate's byte digest and live CID before returning it;
a damaged copy refuses readback and rolls back nonce consumption.

Deletion readback includes the complete original marker, rather than only null
record/CID fields, so the device can independently hash and compare it. The
returned live envelope and deletion marker are mutually exclusive. Neither a
readback response nor a staged candidate changes canonical account authority.
Complete source/destination inventory, authenticated device verification and the
separate atomic cutover remain mandatory.

Private candidate bytes count in the existing account space total. SQL triggers
add them on insertion and remove them on owned deletion in the same transaction.
This makes paused or canceled copies visible to existing quota checks, including
ordinary writers after a hold is released. Counter updates cannot recreate an
account or its usage row during deletion cascades. These tables and adapters alone
do not register HTTP mutation routes, enable rollout or allocate a migration worker.

## Retaining and verifying source media

A `BEGIN_BLOB` request signs the canonical body `{version:1,cid}` with the original
accepted operation key. Its private SQL admission binds the account, operation,
hold generation, source space, CID, physical path, size and media type. The source
must be an owned, non-purging personal-space object. Admission and nonce consumption
commit together before any provider read. The binding is immutable, and verification
can advance only once. Retries reuse the same admission within that generation.

The media service opens the actual source object between its admission and
acknowledgement transactions. It hashes a bounded stream, checks the exact declared
and observed sizes and canonical raw SHA-256 CID, and closes the stream before
capturing acknowledgement time. It accepts at most 100 MiB with a fixed 64 KiB
buffer. Cancellation and provider failures cannot manufacture verification evidence.
Neither Exposed nor a raw JDBC adoption context may perform this provider read.

Only the server reader can construct the evidence accepted by acknowledgement.
Acknowledgement rechecks the active account, original authorization, reservation,
source metadata, current hold and its generation. Renewal may advance the revision
within the same generation; expiration, cancellation or a restarted hold cannot
adopt an earlier read. Capturing a fresh server time after the read prevents a slow
transfer from borrowing the request's earlier lease validity.

The private admission retains the existing source object, whose bytes are already
charged through its normal metadata. It creates neither another physical copy nor
a live target-space alias. Ordinary space purge and legacy physical-path cleanup
recognize these references even after a pause or cancellation. Account deletion
removes its private admissions through their ownership constraints. Owned staging
expiry and postcommit source cleanup still need their separate maintenance policy;
these references are not permission for automatic source deletion.

Ciphertext integrity does not prove authenticated media content or conversion
equivalence. The unlocked device must independently reopen required media before
manifest verification. Complete legacy-source adapters, destination ownership and
atomic promotion remain required before adoption can be enabled.

## Ownership and durability of newly uploaded private media

New private candidates bind an opaque provider owner token to their durable upload
ticket. Verification uses an owner-aware reader, rather than ordinary path lookup.
Matching bytes, size and CID under another owner cannot verify a ticket, including
a WRITING ticket recovering from an ambiguous provider acknowledgement. The final
SQL transaction also checks that the stored owner, physical object and upload
binding still match. Unsupported storage adapters refuse owner-aware reads.

GCS checks live owner metadata and opens the exact returned object generation with
a generation precondition. The filesystem provider checks live owner attributes
under its shared publication lock, persists the payload and owner metadata before
link publication, and synchronizes the containing directory. An owner-aware read
repeats those persistence checks before opening an inode-bound, no-follow stream,
so a failed or lost publication acknowledgement cannot make cached-only bytes
valid cutover evidence. Persistence failures leave the ticket unverified. These
checks add filesystem synchronization work in exchange for crash-safe evidence.

Ordinary canonical-source reuse remains separate: its account, source namespace
and retained metadata establish source ownership before ordinary source readback.
That path cannot stand in for the provider ownership proof of a new private upload.
Provider ownership and ciphertext integrity still do not establish plaintext
equivalence; independent device authentication remains mandatory.

### Recovering ordinary unattached uploads

The server sweeps durable upload intents independently of identity-adoption and tombstone-purge
settings. Completed writes that remain unattached receive two minutes to finish metadata attachment.
Recovery rechecks their phase and settlement time after locking the account and intent; a losing
attachment that leaves its candidate unattached refreshes this grace. Older rows receive a full grace
period when the settlement timestamp migration runs.

Cleanup first proves the exact path has no canonical or staged references and records its permanent
retirement barrier. Provider deletion runs outside the database transaction. Failed deletions remain
retryable after restart, and bounded cursor pages prevent one failure from starving later candidates.
The application owns one worker and joins it at shutdown.

The grace bounds how long a completed orphan can block adoption. An attachment delayed beyond it
may lose its unused candidate and must follow the existing failed-upload path. It cannot acquire a
retired path or lose an already committed canonical object. A `WRITING` intent has an uncertain
provider outcome and never becomes deletion authority merely because time passes; it retains its
adoption barrier until that outcome can be established safely.

### Superseded copies after commit

A completed adoption retains its committed recovery receipts and final verification manifest,
source mappings, and shared blob pins. The receipt's verification revision, manifest and coverage
digests must match the operation's final hold generation before maintenance can discard earlier
preparation generations. Unanchored historical states retain their evidence.

After seven days from commit, bounded cleanup removes only older temporary records, origins,
mappings, pins and verification manifests. Origins are removed before their records so cascading
deletes cannot turn a bounded page into an unbounded operation. Existing accounting triggers release
only the discarded rows' quota. Canonical journal data and source-history retention are unchanged;
physical private-upload retirement still requires its separate ownership and reference checks.

### Sessions and ordinary space writes

A first-party space request retains the verified token's DID and compares it with the account's
current canonical DID. Tokens issued before DID claims remain compatible only at identity epoch
zero. After adoption, retired-DID and unbound legacy tokens cannot read or write the target space;
the existing account-authority status and receipt routes remain available for device reconciliation.
A refreshed canonical session uses the adopted DID without replacing the account or journal keys.

Record writes, deletes, quota reservations, upload admission and blob finalization carry the epoch
captured with that authenticated account. They do not reread a newer epoch to rescue an old request.
The database fence checks the captured authority inside the mutation transaction, so an identity
change between authentication and storage cannot turn an old request into a target-space write.

### Backup mutations and retained archives

Backup uploads and deletions keep their full-account scope requirement. A linked sync-only device
session cannot acquire archive authority. Production routes also validate the token's canonical DID
and capture the account epoch before parsing or transferring the archive. Both upload admission and
archive deletion check that captured epoch in their existing database transactions. A request that
races cutover is refused rather than silently rebound to the newer identity.

Existing full-account archive reads remain available for recovery of historical sources. Reading an
archive does not reactivate its recorded DID or change the account's canonical identity. Non-database
route fixtures retain epoch-zero behavior; production supplies canonical account authentication and
never infers a newer epoch from an incoming archive or a late provider response.
