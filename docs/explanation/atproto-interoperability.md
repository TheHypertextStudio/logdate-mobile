# AT Protocol interoperability

LogDate is built on the AT Protocol, the open protocol behind Bluesky and a growing set of other
apps, which its community calls the Atmosphere. LogDate uses the protocol for identity, for the
schemas of everything it stores, and for the small amount of data a person deliberately makes
public. It does not use the protocol's public, follower-driven model for the journal itself, which
stays private and end-to-end encrypted. This page explains how those two things fit together: what
LogDate publishes, what it never publishes, why data flows in and not out, and why a public identity is
each person's own choice. It also records the choices that become permanent once the first identity
is public, so that nobody makes one of them by accident.

The pages around this one go deeper on single topics.
[Sharing and circles](../feature-design/social-model.md) defines the product rules for sharing.
[LogDate lexicons](../reference/lexicons.md) maps every kind of data to a schema.
[Private storage](private-storage.md) and [encryption](encryption.md) explain how private records
are sealed. [Identity publishing](identity-publishing.md),
[handle resolution](handle-resolution.md) and [identity custody](atproto-identity-custody.md) cover
identities and their keys, and the
[repository event stream reference](../reference/repository-event-stream.md) lists exactly what the
server shows to other services.

## The protocol terms this page uses

An AT Protocol account is identified by a DID, a permanent identifier such as `did:plc:…`, and
carries a handle, a readable name such as `alice.logdate.app` that can change. The account's data
is hosted by a personal data server, or PDS. LogDate's server is a PDS for the accounts it hosts.
A PDS keeps each account's public records in a repository, a signed tree of records that any other
service may fetch, and it announces changes to relays, which pass them on to the rest of the
network. Every record has a type described by a Lexicon, a published schema named by an NSID such
as `app.logdate.actor.profile`. Apps other than the one that hosts an account sign the person in
with OAuth and then read and write records on their behalf.

The protocol's repository is public by design. Anything written to it can be copied by any service
and cannot be taken back. That single property drives most of the decisions below.

## Each account has a public repository and private spaces

LogDate stores an account's data in up to three kinds of place, and only the first is public.

| Place             | What it holds                                                                                        | Who can read it                       | Encrypted                   |
| ----------------- | ---------------------------------------------------------------------------------------------------- | ------------------------------------- | --------------------------- |
| Public repository | The profile, the keys other people encrypt to, and stories the person chose to publish               | Anyone, including relays              | No                          |
| Personal space    | The journal: entries, journals, places, people, Rewinds, drafts and everything else the person keeps | Only the owner's devices              | Yes, end to end             |
| Shared spaces     | Items shared with a circle of people or with a group                                                 | Only the people they were shared with | Yes, to those people's keys |

All three use the same family of schemas, so an entry is the same record whether it is private,
shared or published. The public repository uses the protocol's signed repository format. The
private spaces use a separate store that follows the layout of the AT Protocol's Spaces proposal
and wraps every record whole in a sealed envelope, so the server sees neither a record's type nor
its timestamps. [Private storage](private-storage.md) explains why the journal moved out of the
repository and into that store.

The place a space's records are kept is called its data home. The apps reach one through a single
interface, `DataHome` in
[`shared/space`](../../shared/space/src/commonMain/kotlin/app/logdate/space/DataHome.kt), and a
data home only ever receives sealed envelopes and encrypted blobs. Sync code depends on that
interface and not on LogDate's hosted service, which is what lets a self-hosted LogDate server be
a data home.

The server stores only each account's own personal space, so shared spaces have no storage there.

## What LogDate publishes

Four collections make up the public repository. The list is written in code in
[`PublicRepoPolicy`](../../server/src/main/kotlin/app/logdate/server/atproto/firehose/PublicRepoPolicy.kt),
which also treats as public any collection opened to other apps through OAuth. No collection is
open to other apps, so the four below are the whole list:

- `app.logdate.actor.profile`, the person's name, description and photo;
- `app.logdate.crypto.keyDeclaration`, a public key a device publishes so that other people can
  encrypt shared items to it;
- `site.standard.publication` and `site.standard.document`, the publication and story records
  defined by the Standard.site schemas, written only when a person publishes a story.

Stories use the Standard.site schemas instead of a LogDate schema because other apps already
render them, and because those schemas are published and maintained outside LogDate, so using
them commits LogDate to nothing of its own. Publishing a story in a shape LogDate invented
would make it readable only by LogDate.

A collection is public only by being named in that class. A collection added to LogDate later is
private until someone changes the list on purpose, and the class refuses any `app.logdate.*` or
`studio.hypertext.logdate.*` collection that is not the profile or the key declaration.

Naming a collection public is not enough for the server to show an account's repository. The
account must also be active, keep its journal in its personal space, and have an identity its owner
made public. The reference page lists those conditions under
[Which accounts are shown](../reference/repository-event-stream.md#which-accounts-are-shown).

## What LogDate never publishes

Everything that is not in the list above stays private, and several rules make that hold even when
other parts of the system change.

Journal data never goes into a public repository. An account that syncs through
`studio.hypertext.logdate.*` records keeps them in the same repository tree as everything else, so
the server never shows that account's repository at all, not even its public records. A repository
is exported as a tree that names every record in it, so one private record in the tree would leak
that record's name.

Other apps cannot reach journal data through OAuth. `OAuthScopes.collectionsOpenToApps` in
[`OAuthScopes`](../../server/src/main/kotlin/app/logdate/server/oauth/OAuthScopes.kt) lists the
collections an app signed in through OAuth may read and write. It is empty, and the same file
refuses to open any collection under `app.logdate.` or `studio.hypertext.logdate.`.

Records about other people are never public. A person record, a mention, a circle and a circle's
membership describe someone other than the author, and that person never agreed to be described.
The [lexicon classification](../reference/lexicons.md#how-to-read-the-classification) marks each
kind of data as private only, shareable or publishable, and only publishable data can ever reach
the public repository.

LogDate never writes another app's records. It reads `app.bsky.*` records such as
follows and blocks, and writes none. Where LogDate needs to attach its own data to a record whose
schema belongs to someone else, it writes a separate record in a LogDate collection and never adds
fields to the record it does not own. This page calls that separate record a sidecar. The drafted
schemas contain one: `app.logdate.event.annotation` holds LogDate's own details about a
`community.lexicon.calendar.event` record and names that event in its required `event` field.

## Data is pulled in and never pushed out

The rule is that LogDate reads from the rest of the network and writes to it only when a person
publishes one specific piece. Writing covers the profile, the sharing keys, and a story the person
pressed Publish on. Nothing else a person makes in LogDate appears in any other app, and LogDate
never follows, posts or announces anything on someone's behalf.

The reason is the difference between a journal and a feed. People write differently when they
believe something might be seen, and a product that quietly mirrored activity into a public
network would make every entry feel like a post. A network that only ever flows inward lets
LogDate use what a person already has elsewhere without turning their private life into content for that network. The
[social model](../feature-design/social-model.md#pull-in-never-push-flow-by-flow) states what each
flow reads and writes.

The server does not depend on the OAuth client library in
[`shared/atproto-oauth-client`](../../shared/atproto-oauth-client/README.md), so it has no session
with any other server to write through, and nothing reads from or writes to another service.

## A public identity is each person's choice

Every LogDate account gets a DID and a handle the first time it needs them, usually at sign-up, but
that identity is private by default. The handle does not resolve for anyone outside LogDate, and
the server never confirms to an outside caller that the account exists. Making the identity public
is the person's own explicit, informed decision, and the rule is that nothing is ever sent to the
public PLC directory without it. Sending is off unless an operator sets
`ATPROTO_PLC_PUBLISH_ENABLED`, and once that setting is on, two places in the server still send
without asking.

Accounts do not become public identities automatically, for two reasons. Publishing to the PLC
directory is permanent: the directory only appends, and an identity sent there by mistake can be corrected but
never removed. And a public handle confirms to anyone who asks that a particular person has a
LogDate account, which is a fact about someone's private life that they did not choose to share.
A journal that most people use entirely in private should not announce its users.

The server records the choice per account in the `accounts.public_identity_did` column, which holds
the exact DID the owner made public. Handle resolution, the DID document, `describeRepo` and the
public repository all require that column to equal the account's current DID
([`AtprotoIdentityService`](../../server/src/main/kotlin/app/logdate/server/identity/AtprotoIdentityService.kt),
[`PublicRepoGate`](../../server/src/main/kotlin/app/logdate/server/atproto/firehose/PublicRepoGate.kt)).
The column is necessary and not sufficient. All four also require the account to be active. The
public repository further requires that the account keeps its journal in its personal space, that
no moderation action applies to it, and, for a `did:plc` identity, that the server is configured to
send operations to the PLC directory.
A server-wide setting cannot stand in for the column, and the migration that added it deliberately
backfilled nothing, because provisioning an identity never established consent. Storing the DID
and not a yes-or-no flag means consent given for one identity does not carry over if the account's
identity ever changes.

Sign-in is passkey-only for the same reason identities are guarded. An identity that other services
can reach must not be protected by a secret someone can guess or reuse, so the server's
`com.atproto.server.createSession` and `createAccount` methods refuse every request before reading
it. [Passkey OAuth sign-in](../reference/atproto-oauth-sign-in.md) describes the page other apps
send people to instead.

## People who already have an identity on another server

A person who already has an AT Protocol identity, for example a Bluesky account, links it to their
LogDate account and does not get a second identifier. These rules describe how that link works.

LogDate's server acts as a confidential OAuth client, a client that holds its own secret key and
runs the sign-in on the server, as a backend-for-frontend, and hands the app a LogDate session at
the end. Private data goes to LogDate Cloud, sealed, with the linked DID as the space's owner, in
exactly the way it does for a hosted identity. Public records go to the person's own server
through the OAuth session, and LogDate shows no repository, event stream or handle of its own for
that account. The identity LogDate would have hosted for the account is retired and never
published.

The OAuth session with the other server is used only to write public records, so private sync never
depends on it, and an expired session costs only the ability to publish until the person signs in
again. A new account that arrives through an external sign-in creates its LogDate passkey after
that first sign-in and before any data syncs, and every later sign-in uses the passkey. LogDate
publishes nothing for this path, because the story and publication records use the Standard.site
schemas, which are maintained outside LogDate.

## Published stories are links, not something to follow

A published story is a page with an address that the author hands to people. It is not an account
that others subscribe to. LogDate offers no way to follow a person's stories on the AT Protocol.

Following is what turns a set of pages into an audience. Once readers can subscribe, the author
has a follower count, readers expect a next post, and the product has a feed in everything but
name. LogDate's first principle is that it has no public feed, no follower counts and no
algorithmic discovery, and that principle would not survive a subscribe button. Publishing exists
so that one piece of writing can be read by people outside LogDate, and a link does that without
creating a standing relationship.

Every LogDate record type also has a defined Activity Streams 2.0 shape, listed in
[`lexicons/activity-streams.json`](../../lexicons/activity-streams.json) and explained in the
[lexicon reference](../reference/lexicons.md#activity-streams-mapping). The mapping exists so that
exports are legible outside the AT Protocol. Defining a mapping never means delivering
anything: only deliberately published records could ever be sent to another server.

## The interface uses no protocol words and offers nothing that does not work

Two principles govern how any of this appears to people.

Protocol words stay out of the product. Terms such as DID, PDS, Lexicon, repository and AT Protocol
never appear in the interface. An identifier appears only where a task needs it, such as a handle
when someone is finding a friend. Interoperability is never presented as a feature named after the
protocol. The social model's
[word list](../feature-design/social-model.md#words) gives the terms the interface uses instead.

The product never advertises something that does not work. A button that fails is worse than no
button, so every action that depends on the server is offered only when the connected server says
it supports it. The server lists its abilities in `ServerDescriptor.protocolFeatures`
([`ServerDescriptor.kt`](../../shared/model/src/commonMain/kotlin/app/logdate/shared/model/ServerDescriptor.kt)),
and the apps check that list before showing the action. Sharing a journal as a link is the clearest
case: the apps offer it only when the server advertises `journalShareLinksV1`
([`JournalLinkSharingAvailability`](../../client/sharing/src/commonMain/kotlin/app/logdate/client/sharing/JournalLinkSharingAvailability.kt)),
and no server advertises it, because nothing serves a shared journal. Identity features follow
the same rule through `atprotoPlcPublishingV1` and `atprotoRecoveryKeysV1`. The rule matters most
for self-hosted servers, which may run an older version or have a feature turned off.

## One schema family describes private and public data alike

Lexicons are the schema for everything LogDate stores, private data included, even though private
records are never seen by anyone who could use the schema. One definition per kind of record then
serves the Kotlin models, the sync format, the export archive and the shared test cases that the
Mac and Linux apps also run, and a record needs no conversion when it moves from the
personal space to a shared space or to the public repository.

New records use the `app.logdate.*` namespace, which matches the product's domain. Community
schemas are reused as they are where they exist, such as `community.lexicon.location.*` for
coordinates and addresses and `community.lexicon.calendar.event` for events, so that LogDate's
places and events have the same shape as other apps'. The rules for writing a schema, including
keeping unions open and never making a breaking change under an existing name, are in
[Writing LogDate lexicons](../reference/lexicon-style.md), and the generator that turns schemas
into Kotlin is described in the [AT Protocol library reference](../reference/atproto-library.md#lexicon-code-generation).

## Password-only apps cannot sign in to a passkey-only server

Passkey-only sign-in has one interoperability cost that is worth knowing before debugging it. An
app can sign a person in to a LogDate-hosted identity only through OAuth, where the person
confirms with a passkey on LogDate's own page. An app that signs in only by sending a handle and
password to `com.atproto.server.createSession` cannot use a LogDate-hosted identity at all, however
complete the rest of the server is, because the server refuses that method for every request. Apps
that sign in with OAuth are not affected. Whether a given app offers OAuth sign-in for servers
other than its own is that app's decision, so check the app's behavior
before concluding that the server is at fault.

Sign-in is also not the whole of compatibility. The protocol defines more for a PDS than sign-in
and record storage, and general-purpose apps are written against those parts: an answer from
`getSession` for an OAuth token, stored preferences, short-lived tokens for other services,
forwarding of a request to the service that builds the app's views (the `atproto-proxy` request
header), batched writes, handle changes, and the methods for moving an account between servers.
The server answers only the methods listed in
[the repository event stream reference](../reference/repository-event-stream.md) together with its
repository, server and handle-resolution methods, and its OAuth scopes are `atproto` and
`transition:generic`. An app that calls a method outside that set fails against a LogDate-hosted
identity even when its OAuth sign-in works, so check the method list before suspecting the identity
or the sign-in.

## Choices that become permanent once an identity is public

Before any identity or record is public, almost everything can be renamed or moved. After
the first one, other services hold copies, caches and signed history, and the following become
permanent or expensive to change. Treat a change to any of them as a migration, not an edit.

**Names inside sealed records are frozen.** Every sealed record is encrypted with
additional authenticated data of the form
`app.logdate.crypto.sealed|1|<space URI>|<author DID>|<sealed record key>`
([`SealedRecordCodec`](../../shared/space/src/commonMain/kotlin/app/logdate/space/SealedRecordCodec.kt)),
and the space URI is `at://<owner DID>/space/app.logdate.space.personal/self`. The space type
`app.logdate.space.personal` is also the salt from which the space's keys are derived
([`SealingKeys`](../../shared/space/src/commonMain/kotlin/app/logdate/space/SealingKeys.kt)), and
the key a record is stored under is a keyed hash of its collection name and record key. Renaming
`app.logdate.crypto.sealed`, `app.logdate.space.personal`, or the collection of a record that
already exists therefore makes existing records fail to open or impossible to find. The only way
to change one is for a device, which holds the keys, to re-seal every record. This is true whether
or not anything is public.

**The owner's DID is part of every sealed record.** Because the space URI and the author both
contain the DID, an account cannot simply switch to a different DID: every record would fail to
open. The only way to change it is for a device that holds the keys to download the space, seal
every record again under the new DID, check them, and switch, in the way a
[move into a personal space](journal-moves.md) seals a journal.

An account's owner DID therefore cannot change without re-sealing. Linking an account to an AT
Protocol identity that another server hosts makes that identifier the owner of the personal space,
so a device re-seals every record under it, and the hosted identity LogDate created for the account
is not kept behind the scenes as the space's owner. The owner DID is part of the authenticated data
of every record, which is why a hidden second owner could not be swapped out later.

**A published identity's history is permanent.** The PLC directory keeps every operation,
including the first, which names the handle and server address the account had when it was created.
A mistake can be corrected by a later operation and never removed, so an identity's whole operation
chain needs review before it is published.

**Hostnames end up in other services' records.** The PDS address (`ATPROTO_PDS_SERVICE_URL`) is
written into every identity's DID document, so moving it later takes a new operation for every
published identity. The OAuth issuer is compared exactly by other apps, so changing it ends every
session they hold. The handle domain (`ATPROTO_HANDLE_DOMAIN`) appears in other apps' caches and in
links people have shared. The server's own DID is `did:web:` followed by the handle domain
([`AtprotoIdentityConfig`](../../server/src/main/kotlin/app/logdate/server/identity/AtprotoIdentityConfig.kt)),
and it is the audience that tokens from other services are addressed to. The server answers with
that DID's document at `/.well-known/did.json` only for requests whose host is the handle domain
itself, so the DID resolves only where that hostname is routed to the server and not to another
site.

**Identifier formats are chosen once.** Hosted identities are `did:plc` by default. A `did:web`
identity, which the server can also issue, is tied to its hostname and can never move to another
host, so it suits throwaway test accounts and nothing else. Rotation keys are listed with the
person's recovery key first and the server's key second, which is what lets a person overrule the
server. Signing keys are K-256 by default, and the key algorithm can be changed later with an
identity operation. Record keys are fixed by each schema: the profile's is always `self`, and key
declarations use a TID, a timestamp identifier.

**Record keys already written stay in published repositories.** A record's key is part of its
address, and other services keep that address once they have copied the record, so a key cannot be
renamed after publication. Key declarations, publications and documents are keyed by TID. A TID
combines a timestamp in microseconds with a clock identifier from 0 to 1023, which exists so that
two processes generating keys at the same instant still produce different ones
([`Tid`](../../shared/atproto-syntax/src/commonMain/kotlin/studio/hypertext/atproto/syntax/Tid.kt)).
The server passes no clock identifier, so every server process uses 0
([`LogDateRepoStore`](../../server/src/main/kotlin/app/logdate/server/atproto/LogDateRepoStore.kt)),
and two instances writing to the same collection of the same repository in the same microsecond
would produce the same key. The library's repository engine, when a caller gives it no key, falls
back to `record-<seconds>`, which is not a TID
([`DefaultRepoEngine`](../../shared/atproto-repo/src/commonMain/kotlin/studio/hypertext/atproto/repo/DefaultRepoEngine.kt)).
Keys written this way remain in the repository afterwards.

**The lexicon namespace and its authority are public commitments.** An NSID's authority is its
domain in reverse, so `app.logdate.actor.profile` is governed by a DNS record at
`_lexicon.actor.logdate.app`, with one such record for each group of schemas. Once a schema is
published, it can only change in compatible ways, which the lint enforces for every schema listed
in [`lexicons/published.json`](../../lexicons/published.json). That file is empty, so every
`app.logdate.*` schema is a draft. The names of permission sets, such as
`app.logdate.authFull` and `app.logdate.authShare`, become fixed as soon as another app asks for
one by name.

**Blob contents cannot be recalled.** A blob's identifier is a hash of its bytes, and once another
service has copied a published image, nothing removes it. Anything that should not be public, such
as location metadata inside a photo, has to be stripped before upload.
