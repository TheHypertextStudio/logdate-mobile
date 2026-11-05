# Identity adoption confirmation

Adoption confirmation authorizes a private preparation operation under the existing LogDate account.
It does not change the account DID, move PDS hosting, publish a repository or PLC operation, register a
recovery key, or replace a journal session. Those actions have separate contracts and authority.
The HTTP routes remain disabled until the complete adoption service is available.

## Account and operation binding

The originating backend accepts a full LogDate account access token. Linked-device, provider OAuth,
and AT Protocol session credentials cannot authorize this action. The account UUID comes from the
verified token, never the request body. Moderated accounts are refused.

`POST /api/v1/account/identity-adoption/{operationId}/confirmation` accepts the saved source DID,
target DID, source identity epoch, device identifier and expected revision, plus the device public key
and backend origin. All expected fields must match the account's current PREPARING proposal.
The origin must equal the server's configured canonical public origin. The key must be an uncompressed
P-256 public key encoded as canonical Base64URL without padding.

The response binds a server-issued decision UUID, registered-passkey options, expiry and device key to
the exact operation and revision. A browser return cannot substitute for this registered passkey.
Decision challenges expire after five minutes. Crypto runs outside the account mutation transaction;
persistence rechecks source eligibility and current operation state before granting authority.

## Confirmation proof

`POST /api/v1/account/identity-adoption/{operationId}/confirm` accepts the same expected operation,
decision UUID, passkey challenge and assertion, and a P-256 device signature. The device message frames
the operation UUID, account UUID, backend origin, device identifier, source and target DIDs, source
epoch, decision UUID, revision, challenge and public key with the domain defined by
`IdentityAdoptionDeviceProof`. Altering any field invalidates the device proof.

Adoption transports the standard nullable WebAuthn user handle without substituting an empty value.
A missing handle does not establish ownership: the registered credential and cryptographic assertion
must still verify for the original account. Existing internal callers adapt to this canonical proof
without changing unrelated step-up wire contracts.

Successful confirmation returns the matching proposal status without a commit receipt. Durable
authorization is separate from the short-lived decision. A retry with the same bound decision,
challenge and device signature can recover an interrupted successful response without creating a
second authorization. Stale epochs, revisions, decisions and signatures cannot acquire authority.

## Transport limits

Responses use `Cache-Control: no-store`. JSON bodies are limited to 64 KiB before decoding; assertion
fields, identifiers, keys and signatures have individual bounds. Confirmation requests are rate limited
per account. Error bodies contain fixed product messages and never include raw provider, passkey or
storage diagnostics. Source records, journal keys and private key material are absent from this API.

## Restart recovery

Protected operation status includes optional `authorization` metadata for a nonterminal operation:
the revision originally authorized, device public key and canonical backend origin, and authorization
time. This record outlives the five-minute passkey/provider proofs. It is read in the same SQL
statement as the operation and receipt, checked against the original immutable binding digest, and
never renews a hold. Terminal operations omit this metadata; committed recovery uses the receipt.
Inconsistent stored authorization is an unavailable response rather than a claim of authority.

The native app validates this metadata against the saved operation and backend, then compares its
public key with the existing private device key. A missing or different local key blocks continuation;
checking status never creates a replacement key or changes the pending operation. These read-only
metadata checks are insufficient to mutate data. Every subsequent action still needs a fresh,
operation-bound nonce and a signature from the registered device key, with server-side epoch,
revision, lifecycle and lease checks at the mutation boundary.

## Hold, renewal and cancellation

Once a proposal exists, `POST /api/v1/account/identity-adoption/{operationId}/action-challenge`
issues a short-lived nonce for HOLD, RENEW or CANCEL under the complete expected operation binding
and phase. The lifecycle payload is empty, so its body digest is always SHA-256 of zero bytes.
`POST .../{operationId}/lifecycle` consumes that nonce with the original device key's signature.
Unsupported actions and payload digests are rejected before consumption. Commit uses its separate
verified-cutover contract and cannot run through this endpoint.

A successful action advances the revision. Replaying its old request conflicts rather than repeating
the mutation; the client reads protected status to recover a lost response. Issuing a renewal against
an expired hold releases that hold and invalidates the old revision, then requires a fresh observation.
A renewal advances a verified operation back to FENCED so verification can bind the new revision.
These actions preserve the account's canonical DID, passkeys and journal keys.

`POST .../{operationId}/abandon` can discard an unconfirmed PREPARING proposal using the existing
full account session and expected binding. This deliberately lets a session abandon a proposal that
has not acquired device authority; it cannot discard an authorized operation. The absence of durable
authorization and cancellation are checked under the same account lock, so authorization racing the
request cannot bypass the signature requirement. Repeating an already completed unconfirmed abandon
returns its canceled status without another transition. Native storage still uses compare-and-remove
against the saved operation, so a late cancellation cannot clear a newer pending flow.

## Cancellation before the exchange response arrives

`POST /api/v1/account/identity-adoption/cancel-browser` accepts the original proposal UUID, attempt ID,
app PKCE verifier, source DID/epoch and device identifier. The target DID is unnecessary, so the app
can cancel even if the browser exchange response was lost before the target was saved. Full account
authentication and the original app proof are required. The proof is bound to an immutable,
account-owned browser-proposal record registered before provider authorization begins.

Start, exchange allocation and cancellation serialize on the proposal advisory lock. Cancellation
records an irreversible marker and, if exchange already created an unconfirmed PREPARING operation,
cancels it and releases its reservation in the same transaction. An already-read provider proof
cannot allocate an operation after that marker. A passkey-authorized active operation refuses this
path and still requires device-signed cancellation. Source account/epoch/device and proof checks
precede all changes; an expired or deleted provider-attempt row does not remove the original
cancellation proof binding.

The cancellation receipt contains the original source binding and the persisted cancellation time.
Retries return that same persisted timestamp, including database precision. Native code validates
every binding field, then removes only the exact saved pending snapshot. A failed request retains
the proof, and a late receipt cannot remove a newer flow. A local task cancellation or one not-found
observation cannot establish this server completion barrier.

Cancellation markers remain account-owned until account deletion. They must not be removed with
short-lived provider attempts: doing so could allow a previously read, delayed proof to allocate the
same proposal later. The registry stores hashed attempt/app-challenge identifiers, never the raw app
verifier, journal keys or provider tokens. Account deletion cascades these private control records.

## Copy before the write hold

The private staging adapters accept device-signed record writes, media admission/readback and
candidate reads in PREPARING. The source remains canonical and ordinary cloud writes continue.
Verification remains restricted to FENCED; COMMIT remains restricted to VERIFIED. Granting copy
permission does not grant either transition or create a target-space alias.

A PREPARING candidate uses its operation revision as its positive copy generation. Held candidates
use the hold generation, which remains stable across renewals. The existing staging column named
`hold_generation` stores either value; neither is the account's identity epoch. Source inventory
pages still explicitly identify whether their census was fenced. An unfenced census cannot be used
as final completeness evidence.

A newer current source version may replace its PREPARING candidate in one transaction. The source
sequence, complete ciphertext digest, deletion state and owned media references are checked before
the old candidate is removed. The same source version cannot choose a different target ciphertext.
Quota failure rolls back both the replacement and nonce consumption. All retained candidates remain
charged, including copies belonging to earlier holds or canceled operations.

Reuse under the final hold requires a fresh, current device signature. Record reuse signs a
`STAGE_RECORDS` body containing `version`, `sourceGeneration`, `rkey` and `targetDigest`; it rechecks
the source bytes/version/references and moves the identical candidate into the current hold
generation. Media reuse signs a `BEGIN_BLOB` body containing `version`, `sourceGeneration` and `cid`;
it requires completed server readback and rechecks the same source object path, CID, size, MIME type,
ownership and retention. The account lock and object-path lock protect the pin transfer. This avoids
repeating the bulk transfer under the hold without accepting a stale or unverified copy. The move
leaves one charged record and one media pin, and retries with fresh proofs return the same result.

Ordinary candidate reads never implicitly reuse an earlier generation. Source edits or deletions,
a changed media object, and missing readback evidence prevent reuse. The device must reconcile the
final delta and verify its authenticated conversion manifest against the final fenced census before
a canonical identity switch is permitted.

## Complete source commitments

A source census reads every registered representation and every page under one account lock. Its
commitment includes the operation/account/device/source/target binding, source epoch, revision,
phase and hold generation. Each representation contributes presence, count and an ordered digest
of complete keys, metadata and binary size/digest evidence. An absent optional historical relation
has a different commitment from a present empty relation. A PREPARING census remains explicitly
unfenced and cannot establish final currentness.

Metadata evidence preserves unknown fields recursively. JSON values carry type tags, and number
tokens are committed as text within a number tag, preserving decimal values without conversion to
floating point. SQL-null columns are recorded independently: PostgreSQL row-to-JSON output otherwise
conflates SQL NULL with a JSONB null value. Binary bodies contribute their complete digest and size;
large media transport and authenticated readback remain separate requirements.

The repository census checks all source-owned block links, including unreferenced and raw blocks.
CID verification and canonical DAG-CBOR round trips precede tree traversal. Current and historical
commits are matched against their stored metadata, tree record counts and previous-commit links.
Tree prefix encoding, key order, subtree ranges, record-object references and graph depth are
checked. Shared immutable subtrees retain metadata for reuse rather than retaining every historical
body. Records in unknown or unindexed collections remain in the current logical inventory.

Current commits, historical commits, current/historical tree nodes and records, raw blocks and
unreferenced blocks receive explicit roles. Headless history retains its complete block census.
Each PostgreSQL block read still requires source ownership; a matching global block from another
repository cannot fill an ownership gap. These are integrity and reachability checks. The dedicated
account/passkey/device proof and target OAuth proof establish adoption authority separately.

Commit still requires a complete conversion mapping, destination readback and the authenticated
conversion manifest. Every active source representation needs a destination or an explicit,
permitted disposition, including overlapping legacy representations and unknown records. Echoing
source counts or the census digest alone cannot establish destination completeness or plaintext
equivalence. Retained historical bytes remain subject to the independent recovery and retention
policy and cannot stand in for a converted active journal.

## Durable conversion mappings

A private source mapping binds one exact representation kind and source key to its complete
source-evidence digest, proposed disposition, ordered target record keys and ciphertext digests,
and verified media CIDs. Its canonical DAG-CBOR body is signed with the current device challenge.
The server re-reads the exact owned source row and the actual staged destination bytes before
accepting it; a supplied digest is not evidence that those bytes exist. Each media reference must
have completed server readback in the current copy generation.

Active records require a nonempty converted destination with matching live or deleted state.
Structural metadata and retained backup history use explicit targetless dispositions. Media and
record-to-media references require their respective verified objects and live destination records.
Repository block dispositions are provisional during staging: final verification must apply the
actual graph roles so a current record cannot be classified as structural or retained history.
Mappings do not themselves authorize verification or commit.

Mappings are immutable database rows charged to the account by their key and body byte lengths.
Before the write hold, changed current source evidence can replace its earlier mapping in the
same transaction. An unchanged source cannot select a different mapping in that generation.
Replacement quota failure restores the earlier row, accounting and unused challenge. Under a
hold, a fresh signed request may move an identical earlier mapping after the target records and
media have been reconciled into the current generation. Ordinary reads never perform that move.
A complete final source census, complete mapping coverage and authenticated destination equivalence
are still required together; an individual accepted mapping establishes none of those globally.

## Final coverage boundary

Final coverage runs only for the current held or verified operation, under the same account lock
and JDBC transaction as its complete source census. It visits every representation and page,
recomputes each source commitment, and requires an exact immutable mapping for every source key.
The current generation must have no extra mappings. Repository block dispositions use the graph's
actual current, historical, structural and raw roles rather than the staging proposal's claim.

Every selected destination is read from its actual staged canonical bytes. The selected keys must
match the complete current staged-record set, and every destination media reference must belong
to the selected verified-media set. That media set must match the current staged-blob set. Media
pins are revalidated against their still-owned source objects, retention status, paths, sizes and
content types. The commitment binds the source census, ordered canonical mapping bodies, exact
record/media counts and verified media identity. Earlier generations do not enter this result.

This evidence proves private byte coverage, not plaintext conversion or permission to switch the
account. The initiating device's authenticated conversion manifest must additionally establish
lossless conversion under the unchanged root, including local pending edits and conflicts. Its
signed verification envelope and the atomic promotion transaction must bind the exact current
coverage result; computing coverage alone neither changes phase nor grants commit authority.

## Generic record origins and destination sequences

A generic copy signs the complete source kind/key/evidence digest, optional current repository
collection and record key, target key, sealed target envelope or deletion, and ordered media CIDs.
The server reads the owned source again and derives its real version. Collection, sync and location
rows use their stored server version; personal records use their source sequence. A repository copy
must identify a logical current record belonging to that exact owned CID, and uses the current
repository snapshot revision. Structural rows, media and retained history use their distinct
mapping paths rather than pretending to be converted records.

Each generic destination retains immutable, quota-charged origin evidence separately from its
ciphertext. That evidence preserves the complete source address and digest, actual source version,
logical manifest source key, original deletion time when recorded, destination key, destination
sequence and ciphertext digest. Equivalent overlapping representations may attach distinct origins
to identical destination bytes. Their semantic equivalence still requires authenticated device
conversion; identical target bytes alone do not prove that two sources meant the same thing.

The destination sequence is separate from the original source version. It is allocated above the
current source space high-water mark and staged destination versions under the account lock.
Deletion markers bind that allocated destination sequence. A recorded source deletion time is
preserved. When a legacy representation records no deletion time, the origin retains that absence
and the target marker uses the first copy instant; it does not claim this is a recovered source
timestamp. Final verification must bind the exact origins and unique destination sequences, and
must reject a collision caused by intervening source activity until the held copy is reconciled.

Unfenced replacements require changed, current source evidence. Rebinding unchanged evidence to a
different target key or ciphertext is refused. A replacement cannot discard other attached origins.
Quota failure rolls back candidate/origin removal, accounting and the action challenge together.
A signed recopy under a later hold may transfer an unchanged sole-origin candidate without another
retained ciphertext charge. Historical or shared candidates that cannot be transferred safely
remain charged until their owned retention cleanup is eligible.

## Signed verification

The verification envelope is canonical DAG-CBOR with `version: 1`, purpose
`app.logdate.identity-adoption.verify`, `manifestDigest` and `coverageDigest`. Both digests are
lowercase SHA-256 hex strings. The manifest digest covers the unchanged cross-platform manifest
v1 bytes; coverage binds complete source evidence, mappings, origins, destination sequences and
verified media. The initiating device signs this envelope through a fresh `VERIFY` challenge bound
to the current FENCED operation revision and phase. A device identifier alone is insufficient.

The server accepts at most 16 KiB of canonical manifest bytes. The shared decoder performs bounded
validation before the general CBOR decoder, then reconstructs the typed manifest and compares its
canonical bytes exactly. Unknown versions or fields, nonminimal encodings, trailing values and
malformed commitments are refused without rewriting them into an accepted older format.

Verification compares the complete operation, account, device, authorized backend origin, source
and target spaces and source epoch. It independently streams the actual staged records into the
existing shared inventory framing and reconstructs blob summaries from verified current-generation
pins. Counts and digests must match the submitted manifest and complete coverage. An empty inventory
is valid only when the complete source/disposition evidence establishes that no active source record
needs a destination.

The manifest's source sequence is a conservative maximum of the actual registered source record
versions and cursor watermarks. Independent source namespaces keep their own genuine versions in
origin evidence; this aggregate is not a replacement for those versions or a cursor that can be
applied to every source. Destination sequences remain independent and are bound separately by
coverage. Legacy manifest source identifiers use `app.logdate.adoption.` followed by the source-kind
name lowercased without underscores, with the SHA-256 of the canonical source-key array as the
record key. These identify private evidence, not published AT Protocol collections. Personal and
repository sources retain their actual logical source tuples.

The final lease check uses the clock after inventory work. Failure, including expiry during that
work or insufficient quota for the durable manifest, rolls back nonce consumption, proof storage
and state together. Successful verification atomically stores immutable, quota-charged manifest
history and changes FENCED revision N to VERIFIED revision N+1 without extending the lease or
changing the canonical DID, identity epoch, keys, passkeys or journal authority. Renewal returns to
FENCED at a new revision and requires a fresh verification; historical proof is not current authority.

The complete coverage hash includes its operation phase and revision. A later commit must read the
actual current VERIFIED operation, recompute its data coverage under current authority, and then
purely rebind that evidence to the original signed FENCED context for comparison. Rebinding never
supplies an older operation to an authorization or database reader. Altered source, origin,
destination or media evidence still changes the rebound commitment. Atomic promotion and native
receipt installation remain separate requirements from recording this verification.

## Atomic private cutover

Commit acquires the source and target DID claim locks in sorted order before locking the account.
It requires the exact current VERIFIED revision, owned target reservation, authorized device key,
fresh COMMIT challenge and signature, durable verification proof and a newly computed complete
coverage commitment rebound to the signed FENCED context. Pending provider mutations prevent cutover.
A second clock check after promotion must still satisfy the hold; failure rolls back promotion,
nonce consumption, quota accounting and all receipts together.

The destination starts in an empty private namespace. Sealed bytes, record keys, independent target
sequences, tombstone deletion times and verified media references become ordinary personal-space
rows. The original account UUID, passkeys, devices, application sessions and root-key envelopes stay
unchanged. Only then does the account adopt the external DID and existing personal-space storage
mode, advance its identity epoch once, clear hosted discovery/signing/PLC/publication-consent fields,
and revoke its old hosted signing keys and OAuth grants. No remote repository, PDS, PLC directory,
DNS or firehose write is part of this transaction.

Immutable record and origin recovery receipts preserve source versions, source and destination
commitments and destination sequences without retaining redundant staged ciphertext. Their bytes
remain charged. Canonical record and media rows use ordinary logical-row accounting; retained old
and new media aliases each count until explicit source-history cleanup, even when they share a
physical object. Committed media pins continue protecting those shared paths. Uncommitted-copy
retention must never delete committed recovery receipts or media pins.

The source DID retirement survives account deletion and cannot be undone by restoring old account
rows. The immutable commit receipt also records the verified revision, manifest and coverage
commitments and hashes of the original challenge nonce and raw signature. An exact original signed
request may recover the same receipt after lease expiry without consuming another challenge or
advancing the epoch again. A changed request, device binding or current canonical identity/epoch
cannot borrow this retry authority. Legacy receipts without this proof remain historical observations.

The protected finalization transport accepts only full LogDate sessions. Challenges bind either
VERIFY in FENCED or COMMIT in VERIFIED to both commitments. Verification transports at most 16 KiB
of canonical manifest bytes as unpadded canonical Base64URL; ordinary account JSON body limits still
apply. Responses are not cacheable and never echo manifest bytes, signatures or private key material.
The native client must persist the exact signed commit request before sending it, and preserve it
through uncertain results until it has a protected receipt or an authoritative terminal outcome.

## Protected source-copy transport

Source reads use full LogDate account sessions and the initiating device's authorized key. A fresh
READ_STAGED challenge binds the registered source kind, exact cursor tuple and page limit under
`app.logdate.identity-adoption.source-page` version 1. Both challenge and read check the current
account, device, source epoch, operation revision and phase. A claimed device identifier alone
cannot retrieve a source page. PREPARING reads leave ordinary journal writes available. FENCED
and VERIFIED reads recheck the real lease clock after reading; expiry rolls back challenge use.

The source response carries canonical typed lexical evidence and any returned binary separately
as unpadded canonical Base64URL. The evidence is the exact preimage of the source digest used in
staging and coverage. Numbers retain their original decimal tokens as strings tagged `number`;
SQL-null columns remain distinct from JSON-null values. Binary evidence commits the original size
and SHA-256, and returned bytes must match both. No JSON floating-point conversion may intervene.
All nineteen registered source kinds participate, including explicitly absent optional tables.

Ordinary source pages have a soft 4 MiB aggregate budget for evidence and binary bytes. One valid
larger item travels alone. A shortened wire page resumes from the last included source key, so
packing cannot discard an unseen row. Repository blocks retain their existing 2 MiB bound; private
space records retain the existing sealed-record size allowance. The native client checks source
kind, phase, generation, ordering, first-page totals and exact continuation keys before accepting a
page. The ordinary space-read rate budget supports bulk copy without borrowing the sign-in budget.

Typed metadata framing may be larger and deeper than a sealed journal payload. The native codec
therefore uses a separate bounded adoption-evidence profile; ordinary sealed-record byte and depth
limits remain unchanged. Large source response decoding and binary verification run away from the
main actor, with cancellation and a final owner check before returning results to the active flow.
