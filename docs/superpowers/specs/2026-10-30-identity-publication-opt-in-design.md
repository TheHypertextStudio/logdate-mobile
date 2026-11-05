# Publish one identity by explicit choice

## Purpose and scope

A person whose unpublished identity LogDate hosts can choose to use it in other
AT Protocol apps. The existing native SwiftUI Account surface owns this choice.
Sign-up, journal sync, adoption and recovery do not start publication. Private
journals and media remain private throughout the flow.

This design follows the owner's private-by-default decision and authorization to
prepare nonpublication work without further approval gates. It does not authorize
an actual PLC submission, recovery-key registration, public repository write,
lexicon DNS publication or production event-stream activation. Those actions still
require explicit authorization. No runtime publication capability is added by this
document or its mockups.

The earlier global automatic/batch publishing procedure is retired. Keep
`ATPROTO_PLC_PUBLISH_ENABLED` disabled in production. A global emergency stop may
disable writes; it must never make another person's identity eligible to publish.

## Entry and account types

Use the existing Account section and add its identity row there. Avoid a second account-creation flow,
an onboarding interruption or a permanent promotion banner. The row is available
only when the capability is implemented and the server confirms eligibility.

| Account state                                                   | Account behavior                                                                                                                            |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Hosted, unpublished `did:plc`, complete local operation history | Offer “Use in other apps.” Open a review; perform no public write.                                                                          |
| Existing Atmosphere identity                                    | Show that identity and its current PDS. Adoption does not publish another DID or move hosting. Public record publishing has its own review. |
| Already published identity                                      | Show verified identity details. Do not offer an initial-publication action again.                                                           |
| Possibly published, missing history or conflicting authority    | Show a recoverable status. Preserve the original identity and reconcile evidence; never generate a replacement DID.                         |
| Other DID method                                                | Require a method-specific publication contract. Do not send a `did:web` identity to the PLC directory.                                      |
| Adoption, deletion or authority change in progress              | Keep publication unavailable until the operation resolves. Preserve journal access.                                                         |

## Review and copy

The review presents the exact handle and permanent DID, the PDS hostname and the
ordered public rotation-key fingerprints. Public key details expand in place;
private keys and the recovery phrase are never sent to the server or shown in
this review. Preserve the existing PDS and signed operation history.

The primary copy is concise:

> Publish your identity
>
> Your handle and identity details will be public. This history cannot be erased.
>
> Your journal stays private.

Use “Cancel” and “Publish identity.” The latter starts a fresh, purpose-specific
passkey confirmation for the exact reviewed proposal. Returning from OAuth,
signing in earlier or holding an unrelated step-up grant cannot authorize it.
The permanence statement must remain visible with the final action, including
large text and a scrolled details section. Do not preselect consent or hide it
behind a generic “Continue.”

The [screen mockups](../../design/identity-publication-opt-in.html) specify the
Account entry, review, uncertain result and verified result. They are synthetic
design artifacts and cannot send requests. Rendered visual acceptance is required
before using them as implementation references.

## Exact proposal and consent

Create the proposal from the stored, existing identity without provisioning or
changing its DID. Bind account UUID, DID, identity epoch, destination directory,
ordered signed operation bytes and their CIDs, handle, PDS endpoint, repository
public key, ordered rotation keys and proposal expiry. Bind the whole chain when
publishing more than its genesis is necessary; do not silently publish a newer
key, address or handle than the person reviewed.

Preflight can validate local signatures and synthetic-directory behavior before
contacting a public service about an unpublished DID. Directory reads disclose the
queried DID to that service; the review must encompass this contact. The final
consent record and one-use passkey proof bind the proposal digest and current
account authority. Changed proposal bytes, expiry, account moderation/deletion or
an identity-epoch change refuse dispatch and require a fresh review.

Consent is per person and per operation. A handle filter on the old bulk command,
deployment permission, a completed adoption or a server feature flag cannot
substitute for it. In implementation, use a publication claim coordinated with
the identity/adoption/deletion locks. Provider I/O must run outside SQL locks.

## Durable dispatch and uncertain results

| State                            | Meaning and permitted behavior                                                                                                                      |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Prepared                         | Local proposal exists; no public dispatch is authorized. Cancellation discards the proposal.                                                        |
| Authorized                       | Exact consent and fresh passkey proof accepted. A durable, single dispatch claim may proceed.                                                       |
| Sending or unknown               | Dispatch may have happened. Preserve the exact bytes, claim and operation identifier. Reconcile them; do not create another proposal automatically. |
| Confirmed                        | Independent directory readback matches the accepted operation and expected resulting identity. Record the durable receipt for that DID/epoch.       |
| Refused before dispatch          | No dispatch claim was taken. Keep the identity private and offer the relevant correction.                                                           |
| Conflict after possible dispatch | Preserve evidence and journal access. Any corrective public operation needs its own review and authorization.                                       |

App restoration renders saved status and checks the existing operation. It does
not start OAuth, prompt for a passkey, authorize a new proposal or retry with
different bytes. A previously authorized server operation may finish after the
app closes. Cancel is offered only while the server can prove dispatch has not
started. “Done” remains available during reconciliation so the person can return
to the journal.

Use “Checking publication” for an uncertain response and “Identity published” only
after external confirmation. Do not say publication failed or nothing was shared
merely because the request timed out. Expose sanitized failures and useful actions;
keep provider errors, tokens, operation payloads and keys out of user-facing copy.

## Discovery, repositories and recovery

After external confirmation, atomically record public consent for the current DID
and epoch. Discovery and any eligible announcements must use that same evidence.
A crash between external acceptance and local recording remains an uncertain
operation that can be reconciled without resubmitting a different operation.
The publicly accepted history may already be visible during that interval; the
interface must not promise that local private status can reverse it.

Publishing identity metadata does not expose an authenticated private journal
repository or turn all record types into public records. Public repository and
blob exports require a separate, complete public-data boundary. Production stream
activation and lexicon publication remain separate release actions. Compatibility
credentials for another app may be offered only after verified public status,
through their own explicit flow.

Independent recovery-key registration is optional and separate. On an adopted
identity, obtain narrowly scoped management authorization and review the exact
proposed key priority while preserving its current PDS, handle and repository key.
Declining or failing registration must not invalidate adoption or journal access.
Never overwrite another provider's keys to make the current local state match.

## Acceptance requirements

- Every private discovery, read and announcement surface remains private before
  publication and for every account that did not consent.
- A fresh passkey proof authorizes only its exact account, epoch and proposal;
  substitution, replay, broad grants and expired proof produce zero dispatches.
- Default-off startup, sign-up, sign-in, passive restoration, adoption and recovery
  produce zero public writes and no publication worker authority.
- Concurrent adoption, deletion, key rotation and publication cannot produce a
  stale accepted operation or grant consent to a replacement identity.
- Lost responses and restart reconcile the same possible dispatch without losing
  evidence, misreporting cancellation or automatically correcting public history.
- Real directory acceptance and independent readback remain separate evidence
  from local tests and the loopback rehearsal. A production write is performed
  only after the owner explicitly authorizes that concrete operation.
- Populated native phone and Mac flows are inspected in light/dark appearances,
  large text, narrow layouts, VoiceOver and reduced motion, including cancellation,
  changed proposals, offline status, uncertain dispatch and completed publication.
  The journal remains usable through every failure and optional-key decision.

The current bulk publisher, a synthetic mockup and a successful build do not meet
these requirements. They remain prerequisites for future implementation and release.
