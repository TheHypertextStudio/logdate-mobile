# Identity publishing runbook

Production publication requires explicit, informed consent for one person's exact
AT Protocol operation. There is no approved all-account publishing procedure.
Keep automatic publication disabled. Do not activate the production event stream
or publish lexicon DNS records as part of sign-in or identity adoption.

This runbook supersedes the former procedure that enabled publication globally and
published every account. The [opt-in design](../superpowers/specs/2026-10-30-identity-publication-opt-in-design.md)
is the target contract; its implementation and acceptance are prerequisites to
real publication. This document does not authorize a production write.

## Rehearse publishing

Use synthetic accounts and a disposable PLC directory restricted to loopback.
Existing `identity:rehearse` tooling exercises the older publishing mechanics,
including automatic and batch publication. Those mechanics are not the approved
product flow. A passing rehearsal cannot qualify the new opt-in feature.

Builds and disposable services must use the installed resource guard for their
complete lifecycle. Keep them headless, stop owned services on exit, and retain
compact evidence. Never point a rehearsal at a real DID or public PLC directory.

The opt-in rehearsal must additionally exercise one-person consent, a changed
proposal, stale passkey proof, lost responses, cancellation before dispatch,
concurrent adoption/deletion and directory readback of the exact operation.

## Confirm the safeguards

Before proposing any real publication, inspect the exact release and account:

- The account remains private until its own verified publication completes.
- Unauthenticated discovery, repository reads, blob reads and event announcements
  cannot reveal any other private identity or private journal content.
- The proposed operation names the exact reviewed DID, handle, PDS, repository key
  and ordered rotation keys. No identity is provisioned or substituted during review.
- Consent and fresh passkey authorization bind the proposal digest and account's
  current identity epoch. Changed values require another review.
- Adoption, recovery, account deletion and publication cannot race to change authority.
- Private journal access remains available if publication is declined or fails.
- Public repository exports contain only deliberately public records. An authenticated
  private-repository export is not a valid public export.
- Durable operation status distinguishes not sent, possibly sent and externally
  confirmed. A network timeout must never be described as proof nothing was published.

Read-only inspection of a public directory still sends the queried DID to that
service. Use local evidence and synthetic rehearsals before the person authorizes
contact concerning an unpublished identity. Keep identifying operational evidence
private and keep credentials and recovery secrets out of reports.

<a id="turn-publishing-on"></a>

## Automatic publication is retired

`ATPROTO_PLC_PUBLISH_ENABLED` is retired: enabled or unrecognized values refuse
startup. Signup and backfill never submit genesis operations. A global setting
cannot represent individual consent. The old deployment-contract setting,
CLI confirmation and operator access checks do not satisfy the opt-in contract.

<a id="publish-every-existing-account"></a>

## Batch publication is retired

Do not use the legacy bulk publisher to publish existing production accounts.
Filtering a bulk run by handle is also insufficient: it does not bind informed
consent and fresh account authorization to the exact proposed operation.

## Monitor publishing

Once the per-person capability is implemented and explicitly authorized, follow
that operation's durable status and independently verify its exact accepted
operation in the directory. Only then may LogDate record public consent for the
current DID and expose that identity through its discovery surfaces.

Do not equate a request being sent, a provider response, a local receipt or a green
rehearsal with complete end-to-end acceptance. Keep the source proposal and
external readback associated with the same operation without recording secrets.

## Stop publishing

Cancel a prepared operation before its dispatch claim. Once dispatch may have
started, preserve its uncertain state and reconcile that exact operation rather
than claiming cancellation erased it or automatically sending another operation.
Stopping a server or turning a flag off cannot reverse accepted public history.

## Recover from a bad publish

Preserve the exact attempted operation and directory evidence. Do not automatically
reconcile to whatever the server currently stores, rotate keys, tombstone the DID
or migrate its PDS. Each corrective public operation requires a separately reviewed
proposal and explicit authorization. Account deletion cannot erase PLC history.

For existing Atmosphere identities, preserve the provider's authority and the
person's independently held keys. A recovery-key offer is separate from adoption;
registration, replacement and recovery are separate public operations.
