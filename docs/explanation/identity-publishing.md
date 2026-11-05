# Identity publishing

LogDate identities are private by default. Publishing is a separate, explicit choice
by the person who owns an identity. Sign-up, passkey sign-in, journal sync, account
recovery and identity adoption do not grant permission to publish.

An identity published to the PLC directory becomes part of its public history.
Removing an app or closing a LogDate account cannot erase that history. The review
must explain this before the person authorizes an exact operation.

## Private accounts

A private LogDate account can use its journal and account recovery without making
its identity discoverable to other AT Protocol apps. Handle resolution, DID
responses, repository listings and announcements must honor the same private state.
A private repository is never made public as a side effect of publishing identity
metadata. Private journal records and media stay in authenticated private storage.

## Existing Atmosphere identities

Someone with an existing Atmosphere identity adopts that DID instead of publishing
a second identity. Adoption preserves the LogDate account and its keys, devices and
journal while retiring its unpublished DID. The existing PDS remains in place;
changing PDS is an optional, separately reviewed operation.

An externally hosted DID may already be public. Signing in with it does not permit
LogDate to publish journal records, change its DID document, register a recovery key
or replace its hosting provider. External authorization ending must leave the local
journal available. See the [adoption design](../superpowers/specs/2026-10-28-existing-atmosphere-identity-adoption-design.md).

## Publishing an unpublished LogDate identity

The existing native Account surface should offer an explicit review, outside
sign-up. It identifies the exact DID, handle, PDS address and public key changes;
explains the permanent public history; and requires a fresh passkey confirmation.
A durable per-person consent record must bind those reviewed values to one operation.
An operator flag, a deployment or an earlier sign-in is not that consent.

The [opt-in design](../superpowers/specs/2026-10-30-identity-publication-opt-in-design.md)
defines the intended flow and verification requirements. It is a design, not an
implemented publication capability or permission to perform a real publication.

## Recovery keys

Offering an independently held recovery key is a separate action. For an adopted
identity, LogDate must inspect the provider's current management requirements,
request the narrow authorization needed for that operation, show the exact proposed
key and priority changes, and preserve the current handle, PDS and repository key.
Declining recovery-key registration cannot undo adoption or prevent journal access.

The recovery phrase and private key stay on the device. Registering its public key
on an already public identity changes public AT Protocol data and requires explicit
publication authorization, followed by independent directory readback.

## Legacy operator tooling

The checkout retains an older bulk publishing command and its
[technical reference](../reference/identity-publishing.md). Its automatic publication
setting and all-account publishing workflow predate the per-person opt-in decision.
They must not be used to publish production identities. In particular, keep
`ATPROTO_PLC_PUBLISH_ENABLED` disabled; shipping it would enable the wrong model.

The [runbook](../runbook/identity-publishing.md) describes the current operational
boundary. A successful loopback rehearsal proves mechanics only. It does not prove
consent, production privacy, provider compatibility or readiness to publish.
