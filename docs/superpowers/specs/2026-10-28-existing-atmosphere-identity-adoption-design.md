# Adopt an existing Atmosphere identity

Status: approved for implementation and non-publication delivery. Keep runtime
capabilities off until their safety and acceptance requirements are met. AT Protocol
publication, including optional recovery-key registration, requires separate approval.

## Intent

A person who already has a private hosted LogDate identity can make an existing
Atmosphere DID their LogDate identity while keeping their journal, passkeys,
devices, account keys and account UUID. The external PDS remains where it is.
LogDate Cloud continues to store the sealed private journal. The unused hosted
DID becomes permanently ineligible for publication.

Native SwiftUI in `logdate-apple` is the first client. Android parity uses the
existing account surfaces and Managed Devices. This work does not modify KMP iOS.
The preceding confidential sign-in work must reach its acceptance gates before
real adoption is enabled.

## Decisions and approach

The handoff requires one canonical DID and retirement of the unused hosted DID.
Keeping the old DID as the permanent active space owner violates that requirement.
Changing only the account row also fails: sealed-record authenticated data binds
both the space URI and author DID. Server-side conversion would require journal
plaintext or keys that the server must never receive.

The approach uses a device-prepared destination, followed by a verified,
atomic server cutover. The original remains authoritative until that cutover.
This uses the existing migration pattern of copy, hold, verify and switch, with
new identity-specific authorization and transactional fencing. Existing legacy
storage migration endpoints cannot authorize this operation unchanged.

The first implementation pauses cloud writes for the bounded final
verification and cutover, while devices retain local edits. Copying most data
before that pause reduces interruption. No automatic rollback changes identity
after commit. These choices preserve local edits while the server changes authority.

## Eligibility and consent

Begin from the existing LogDate account with an unlocked journal. Require a fresh
registered passkey assertion for a dedicated adoption purpose, and separate OAuth
proof of the proposed external DID, bound to that account and operation. Ordinary
sign-in, disconnect proof, email, handles and a caller-supplied account ID cannot
substitute for either proof.

The hosted source must never have been published. Check both durable publication
history and current consent, not just a nullable current flag. Accounts without
provable publication history are ineligible until that history is reconciled.
An already-public hosted DID needs a separate design. The target must not belong
to another account or another active reservation. Reserve it transactionally
against external signup and adoption; cancellation releases only that operation's
reservation. A target already belonging to this account returns its durable
receipt rather than creating a new migration.

Use the existing Account identity surface. Show the verified current and proposed
identity, that the PDS stays unchanged, that private storage stays in LogDate
Cloud, and that the original hosted identity cannot later be published. Explain
the temporary cloud-sync pause and retained local edits before confirmation.
No second account-creation or passkey-enrollment flow is introduced.

## Durable operation

Persist an operation bound to account UUID, source DID, target DID, initiating
device, source identity epoch and revision. The server generates the operation
identifier. Requests carry an expected revision. At most one active adoption may
exist per account, and one account may reserve a target DID.

| Phase     | Authority and permitted work                                                                                     |
| --------- | ---------------------------------------------------------------------------------------------------------------- |
| Preparing | Source remains canonical. Initiating device copies into an operation-private destination.                        |
| Fenced    | Source writes and destination changes are fenced while the device completes the final delta and verification.    |
| Verified  | Exact source and destination inventories and initiating-device verification are bound to the operation revision. |
| Committed | Canonical DID and identity epoch have switched; receipt is authoritative.                                        |
| Canceled  | No cutover occurred; source remains canonical. Only owned staging output is eligible for cleanup.                |

A lease can bound how long an uncommitted operation holds cloud writes. Lease
expiry releases the hold and invalidates verification; it never implies a
successful move. A competing device may resume only after explicit confirmation,
fresh account proof and a revision change that invalidates the old initiator.
No ambient provider session is sufficient to take over a migration.

## Copy and verification

The device derives existing personal-space keys from the account identity key.
The DID does not change that derivation. Open each source envelope under the old
space and author, and seal it with a fresh nonce under the target space and
author. Preserve collection, record key, relationships, extension fields and
deletions. Preserve unknown records losslessly when their authenticated payload
can be handled; otherwise block cutover and retain them explicitly.

Media encryption is separate from sealed-record authority. Reuse encrypted media
bytes only after verifying their CID and authenticated content and establishing
destination ownership and retention. Copy references and any required embedded
space identifiers deliberately. Do not rewrite arbitrary user text that resembles
a DID. The conversion must inventory every schema field whose meaning binds it
to the old space. A missing media object blocks verification.

Use an operation-private staging store, not the target's ordinary live namespace.
Every staging request checks the current operation, account, device, revision and
target reservation. It cannot write public repository records, write arbitrary
spaces, or become available through ordinary XRPC reads. Account quotas include
staged bytes; declare the temporary duplicate-storage requirement before starting.
Insufficient capacity leaves the source authoritative.

Verification binds the final source sequence, live-record and tombstone inventory,
destination ciphertext inventory, media-reference inventory and a device-created
authenticated conversion manifest. The server verifies byte-level completeness
and currentness; only the unlocked device can verify plaintext equivalence.
The device must download the prepared destination and open every envelope and
required media object before reporting verification. Merely counting records or
comparing opaque ciphertext digests cannot prove semantic equivalence.

The held source census includes a required, narrow storage-authority row. It
contains the account DID, identity epoch and storage mode, plus the existing
storage migration receipt's ID, phase, version, epoch and completion fields. It
never exposes the account's credentials or signing material. Receipt changes
participate in the same canonical commitment as journal source changes.

Personal-space mode alone does not prove older journal representations were
migrated. Only a switched or cleaned receipt at the exact source identity epoch
permits known legacy journal representations to remain recovery history without
being restored as current entries. Unknown content types, unknown collections and
current non-journal repository selectors still require conversion. Selectors are
classified independently when their stored bytes share a CID. Missing or malformed
authority evidence blocks the copy; it never implies permission to retire a source.
History disposition preserves the source bytes and does not authorize deletion.

A source record may produce multiple destination records, such as an audio entry
and its transcript. The manifest orders these by source collection/key and then
destination collection/key. Repeated source keys must have the same source version
and digest; destination keys remain unique. Separate origin evidence binds every
physical source and repository selector to its destination, including multiple
sources with equivalent content sharing one destination. Coverage must account
for every current logical repository record even when their bytes share a CID.
These origins survive private promotion in durable receipts. Counts of physical
blocks alone cannot establish logical record coverage.

Local pending edits, conflict versions and sync queues are captured durably before
verification. Edits made afterward remain pending and are reconciled after cutover;
they are never marked uploaded by copying server state. Preserve the original
local journal and its keys until the new authority opens successfully.

## Write fencing and commit

Every personal-space mutation, deletion, blob registration/finalization, purge and
account-deletion path must participate in the identity-epoch fence. Check the
expected epoch inside its database transaction; a route-level check followed by
an independent store transaction is insufficient. Introduce the same fence into
all relevant instances before enabling adoption. Ordinary requests that race the
fence either commit before the final source inventory or fail without an applied
mutation. A timer-based grace period is not proof that writes drained.

Use a consistent account/identity/space lock order. The implementation plan must
map that order against current space-state, blob, storage-total and credential
locks and prove cross-instance exclusion without holding a database transaction
open during device transfer or provider HTTP calls.

One database transaction revalidates the operation revision, fence, target
reservation, account standing, never-published source and verified inventories;
promotes the prepared private space; updates canonical DID and identity kind to
external; clears public consent and hosted signing authority; increments identity
epoch; records permanent retirement of the source DID; and writes the commit
receipt. A failure changes none of these. Do not delete historical source bytes
or key material needed to open retained backups in that transaction.

Retirement is a local durable prohibition checked by provisioning, publication,
restore, discovery and command paths. Do not send a PLC operation or tombstone for
the unpublished source. Profile updates cannot reactivate it. Historical backup
metadata must not recreate its account authority.

## Devices, sessions and interruption

Keep the account UUID, passkeys, account-root keys, encrypted key envelopes and
device registrations. Refresh first-party sessions to the new DID and epoch;
an old DID claim cannot authorize a target-space write. Devices read a durable
adoption receipt through existing account authority before changing local scope.

Compatible devices preserve their old sync state, pull and verify the new space,
then switch their active sync scope atomically. They use a new cursor namespace.
Reconcile queued edits against their recorded base versions; surface conflicts
rather than overwriting the new destination or dropping edits. An older client
receives a bounded update-required response and retains its queue. The server
never silently serves the old DID as a second active identity.

Persist local prepared state before requesting commit. If the response is lost,
read operation status and the matching receipt. A committed operation cannot be
canceled by an old request. A precommit cancellation clears only that operation's
staging authority. Network failure, app termination and device lock preserve the
original journal and the resumable operation. Account deletion overrides adoption
through the same fence and cannot resurrect either namespace afterward.

Retain the source as an inaccessible recovery copy after commit. Cleanup requires
an explicit retention policy and verified destination/device recovery coverage;
automatic time-based deletion alone is not acceptance. Historical backups remain
readable but restoration targets the canonical DID through verified conversion.

## Optional recovery key

After successful adoption, offer adding the recovery phrase's public key to the
existing identity separately. Never transmit the phrase or derived private key.
The current authentication-only `atproto` grant cannot authorize an identity
update. Request explicit narrowly appropriate authority through the PDS-supported
identity-management route, with its required additional confirmation.

For did:plc, independently inspect the current operation, preserve the PDS,
repository signing key, handle and existing rotation keys, show the proposed key
priority, verify the signed operation before submission and verify its resulting
directory state afterward. Registration is incomplete until externally verified.
Do not move hosting as a side effect. did:web has no PLC rotation list; report the
actual method limitation and keep adoption successful without a false recovery-key
claim. Declining or failing this optional action does not undo journal adoption.

Protocol references: [OAuth](https://atproto.com/specs/oauth),
[permissions](https://atproto.com/specs/permission),
[account recovery](https://atproto.com/guides/account-recovery), and
[account migration](https://atproto.com/guides/account-migration).

## Verification and rollout

Keep adoption disabled until all server instances and compatible client versions
implement the epoch fence, status/receipt contract and recovery behavior. Source
defaults and deployed descriptor gates must both remain off during implementation.
No DID publication, broad OAuth scope, event-stream activation or physical-device
interaction is implied.

Acceptance covers real PostgreSQL competing signup/adoption, same-account device
takeover, write/purge/deletion races and atomic rollback; Kotlin/Swift cross-codec
fixtures with extension fields, relationships, media and tombstones; unsupported
records and missing media; lost commit response; restart at each phase; expired
leases; stale callbacks and sessions; queued offline edits from a second device;
and restoration from a historical backup without reviving the retired DID.

Inspect the complete native Account review, progress, interruption, cancellation
and completion states, including large text and accessibility. Android acceptance
uses matching populated Managed Device scenarios. Real adoption requires separately
authorized deployment and explicit confirmation on the selected account, followed
by journal/device continuity checks. Synthetic fixtures are not proof of that move.

## Work left to the implementation plan

Choose concrete type names and endpoint suffixes; enumerate every mutation and
schema reference affected by epoch fencing and DID conversion; define bounded
batch sizes, lease durations and quota reservations; specify manifest encoding
and authenticated-device binding; and define retention defaults with no automatic
source destruction before recovery coverage. These choices must preserve the
invariants above and receive tests before production implementation.
