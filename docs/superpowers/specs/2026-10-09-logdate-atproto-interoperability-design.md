# LogDate on the AT Protocol

## Goal

**LogDate is a home to document, reflect on, and share memories.** The governing
[product positioning](../../product-info/product-positioning.md) treats journaling as functionality,
not the primary market pitch. This design develops sharing and interoperability in service of
that broader purpose, and defines how LogDate should relate to the AT Protocol network, which
its community calls the Atmosphere.

The direction has three parts:

- **Small-world sharing.** People share with circles of people they choose, never with the public.
- **One data model.** Every LogDate record is described by an open, published schema, called a Lexicon.
- **Pull from the Atmosphere.** LogDate takes identity, friends and activity in from the Atmosphere without sending people's lives back out to Bluesky.

This document is the umbrella design for the "LogDate on the AT Protocol" initiative in Docket. Each project in the initiative gets its own spec before implementation starts. This document records the decisions those specs must respect.

## Problem

### LogDate has no social functionality

Sharing today is one-way export. A note, a Rewind card or a journal QR code goes out through the system share sheet or to Instagram, and nothing comes back.

The groundwork for something more is only partly there:

- **The Audience model is a stub.** `shared/model/.../Audience.kt` defines `LimitedAudience` with example labels "Friends", "Family", "Hiking Buddies" and "Besties". Only one UI component uses it.
- **The public journal link has nothing behind it.** The journal share screen promises "Anyone with the link can view this journal.", but no backend serves a shared journal.
- **People are private notes, not accounts.** The `PEOPLE` feature models the people in a person's life as private records about them. None of those records is a user account.

### The Atmosphere has a user experience gap

The AT Protocol ecosystem is growing:

- an IETF working group was chartered in April 2026;
- Bluesky reports more than 1,000 apps in weekly use;
- open shared schemas exist, such as standard.site for long-form writing.

Most of those apps lead with protocol features rather than with how they feel to use. Bluesky's own app has about 46 million registered accounts but about 10 million monthly active users. That is roughly half its late-2024 peak, according to Similarweb mobile estimates. Its public, follower-driven network is also where much of the hostility LogDate's users want to avoid comes from.

LogDate can be the app that makes the Atmosphere's benefits feel effortless:

- an identity people own;
- data that is not locked in;
- friends they already have.

It can do that without inheriting the public broadcast model.

### LogDate already speaks AT Protocol, unevenly

Most of the AT Protocol work landed in March 2026. Its current state:

- **The server is an embedded PDS.** A PDS (personal data server) hosts AT Protocol accounts.
  - Every account gets a `did:plc` identity and a `{username}.logdate.app` handle.
  - PLC publishing is off in production, so those identities can't be resolved by anyone else.
  - The server holds the only rotation key, so it can change any account's identity by itself.
- **The MST repo engine is the canonical store for first-party sync.**
  - Records live under `studio.hypertext.logdate.*` lexicons.
  - Those lexicons don't follow the Lexicon spec: they are `object` types rather than `record` types, store integer timestamps, and reference media by plain CID strings instead of `blob`.
- **The in-house library has gaps.** `studio.hypertext.atproto` (0.1.0) covers syntax, identity, PLC, repositories and CAR files. Its lexicon code generator doesn't handle `record`, `union`, `blob` or `cid-link`. It has no OAuth client and no firehose.
- **The identity settings screen was removed** on 2026-09-23. The recovery-key derivation behind it still exists, and throws on iOS.
- **Private repos are readable by anyone.** The XRPC read endpoints (`getRepo`, `listRecords`, `getRecord`, `getBlob`) need no authentication. Record bodies are encrypted on the client, but a username is enough to list:
  - when someone journals;
  - which entry types they use;
  - how long their recordings are;
  - which devices they use.

  This is tracked as a separate fix and is the first task of the identity safeguards project.

## Principles

1. **Small world first.** LogDate has no public feed, no follower counts, no public likes and no algorithmic discovery. Sharing goes to people the author chose.
2. **Pull in, never push.** LogDate reads from the Atmosphere: identity, follows, blocks, and records from other apps. Nothing a person makes in LogDate appears in Bluesky or any other app unless that person explicitly publishes that single piece.
3. **Private by default, encrypted when shared.** Journals stay end-to-end encrypted. Sharing with a circle keeps content encrypted to the circle's members.
4. **No protocol words in the product.** Terms like DID, PDS, Lexicon, repo and AT Protocol never appear in the UI. Identifiers appear only where a task needs them, such as a handle when finding a friend.
5. **Never advertise what does not work.** Every Atmosphere-facing action is gated on a working server capability (see `ServerDescriptor.protocolFeatures`). A button that fails is worse than no button.
6. **Fun is the product.** Interoperability earns its place by making LogDate better to use:
   - friends found instantly;
   - a day that fills itself in with what someone listened to and read;
   - a Rewind sent to the people who were there.

   It is never a feature in its own right.

## Strategic posture

The internal posture toward Bluesky is embrace, extend, extinguish:

- **Embrace.** Speak the protocol natively. A Bluesky account can sign in to LogDate. A person's follows help them find friends who are already here. Their blocks and mute lists protect them here too. Records they create in other Atmosphere apps can appear in their days.
- **Extend.** Offer what the Atmosphere does not:
  - private, end-to-end encrypted small-world sharing;
  - life-logging schemas nobody else has published;
  - an experience held to a consumer-app bar.
- **Extinguish.** Make LogDate circles the place where people's close relationships actually live. Bluesky becomes an on-ramp for identity and the social graph, not a destination LogDate sends people back to.

This posture stays internal. Product copy never mentions Bluesky as a competitor.

## Social model: circles

Circles follow the Google+ circles model, which is also the model behind Instagram's Close Friends and share lists. They replace the `Audience` stub and match the original Figma intent for journal audiences.

- **A circle belongs to its owner.** Its name and membership are visible only to the owner. A member sees that something was shared with them, never which circle it came through or who else received it.
- **Circles are one-directional.** Adding someone to a circle does not require them to add you back.
- **Items are shared, not broadcast.** Every share goes to specific circles or people. A person can share:
  - an entry, a day, a journal, a Rewind, a postcard or a sticker;
  - with a circle, several circles or individual people.
- **Recipients see what was shared with them.** Shared items appear in a "Shared with you" place. They never appear in a feed mixed with strangers.
- **Unsharing is real.** Removing a person or ending a share stops future access. Content keys rotate, so later changes don't reach them.

The social model project writes the full specification. It covers reactions and replies, blocking, safety, empty states and copy, and reconciles everything with the Figma journal-audience designs.

## Architecture

### Data homes

An account's data home is where its private and shared records live. It starts with the obvious choice and stays flexible:

| Home | When | Status |
|---|---|---|
| LogDate Cloud | Default for everyone, including people who sign in with an existing Atmosphere account | First |
| A self-hosted LogDate server | Already possible: the server is self-hostable and is a PDS | Supported through "Move to another server" |
| Another hosted PDS, or a person's own PDS | Once Atproto Spaces is stable and the end-to-end encryption layer works on top of it | Later |

All homes store the same lexicon-typed records. Moving between homes uses AT Protocol account migration: export the repositories as CAR files, import them, and update the identity's service endpoint. Sync code depends on a data-home interface rather than on LogDate Cloud directly.

### Three kinds of repository per account

| Repository | Holds | Served publicly | Encrypted |
|---|---|---|---|
| Public repo | Atmosphere-visible records only: profile, published stories, records other apps write for the person | Yes, including the firehose | No |
| Personal space | The private journal: entries, journals, places, people, Rewinds, drafts | Never | Yes, end to end |
| Circle spaces | Items shared with a circle | Only to circle members | Yes, to circle members' keys |

All three use the same MST repo engine and the same lexicons. Private records are wrapped in a sealed envelope, so the server sees neither the inner record type nor its timestamps. This closes today's metadata leak by design rather than only by access control.

The circle space is declared as an AT Protocol space type (`"type": "space"`), so the data layout already matches Atproto Spaces. Circles first ship on LogDate's own encrypted sync. They move to the Spaces sync protocol once it stabilizes, with LogDate's encryption layer on top, because Spaces provides access control, not confidentiality.

### Identity

- **Every LogDate account becomes a public Atmosphere identity, only after the safeguards.** The account's `did:plc` is published and `alice.logdate.app` works as a handle in other Atmosphere apps. The safeguards come first:
  1. private repos are no longer publicly served;
  2. the AT Protocol signing keys have their own key-encryption key, and the OAuth signing key persists across restarts;
  3. people can hold their own recovery key on every platform. Today the server alone controls each identity.
- **The PDS becomes complete for the public repo.** That means `subscribeRepos`, `requestCrawl` to relays, and public blobs. Without them, other apps never see records written to a LogDate-hosted account.
- **Continue with Bluesky.** People who already have an Atmosphere account can sign in with it.
  - LogDate's server acts as a confidential OAuth client (a backend-for-frontend). It reads the social graph on the server, and confidential clients avoid the two-week session cap that public clients have.
  - The external identity is linked to the LogDate account, and the data home stays LogDate Cloud.
  - Passkeys still protect devices and keys.

## Lexicons

Lexicons become the canonical schema for everything LogDate stores, private data included. That gives one definition per object for:

- the Kotlin models;
- sync payloads;
- the export archive;
- the shared test vectors that keep the Swift and Rust clients compatible.

### Namespace

New records use `app.logdate.*`, which matches the product domain and the Kotlin packages. Authority for each group is published with a DNS TXT record at `_lexicon.<group>.logdate.app`. The existing `studio.hypertext.logdate.*` records move to the new namespace as part of the sync migration. They need rewriting anyway to become valid `record` types.

### New LogDate lexicons

Final names and fields come from the lexicon family project. The starting set:

| Object | Notes |
|---|---|
| Journal entry | Ordered blocks in an open union: text (Markdown), image, video, audio. Includes time zone, location and place reference. Targets the planned `JournalEntry` aggregate rather than today's one-table-per-note-type layout |
| Transcript | Sidecar record for an audio block, with segments and speakers |
| Journal | Title, description, cover, membership, audience (circles) |
| Place and visit | Uses `community.lexicon.location.*` objects for coordinates and addresses |
| Person and mention | Private records about people in a person's life. Never public |
| Rewind | Story panels and the person's prompt replies |
| Postcard and sticker | Coordinated with the "Make LogDate Fun" initiative |
| Profile | LogDate's own profile, separate from `app.bsky.actor.profile` |
| Circle, circle membership, share grant | Never written to the public repo |
| Circle space type | `"type": "space"`, listing the collections a circle can hold |
| Key declaration | Public record that publishes a person's sharing keys, similar to Germ's MLS declaration |
| Permission sets | `app.logdate.authFull` and narrower sets for other apps |

The share grant builds on an earlier proposal from logdate-web (`docs/share-lexicon-proposal.md` in that repository). That proposal defines a `share` record separate from the content it points to. The record names an audience: `public`, `link` or `limited`, where `limited` lists the DIDs (account identifiers) allowed to read. Revoking a share deletes only that record, and the content stays untouched. The idea of sharing as its own record, revocable without touching the content, carries over. Two parts change:

- Circle shares live in a circle space and are encrypted to the circle's members. The proposal assumed the server could read shared content.
- Timestamps become RFC 3339 strings.

### Lexicons reused as they are

| Lexicon | Use |
|---|---|
| `community.lexicon.location.geo` / `address` / `hthree` / `fsq` | Location objects inside entries and places |
| `community.lexicon.calendar.event` / `rsvp` | Events, so Smoke Signal RSVPs and LogDate events share a shape |
| `com.atproto.repo.strongRef` | References between records |
| `site.standard.publication` / `document` | Only when a person explicitly publishes a story or journal. Bluesky renders these as rich link cards |
| `community.lexicon.app.profile` | Declares which lexicons LogDate reads and writes |
| `community.lexicon.preference.ai` | Respected for any AI processing of imported public data |

### Lexicons LogDate only reads

| Lexicon | Use |
|---|---|
| `app.bsky.graph.follow`, `app.bsky.actor.profile` | Finding friends who are already on LogDate |
| `app.bsky.graph.block`, mute and moderation lists | Protection carried over from Bluesky |
| `fm.teal.alpha.feed.play` | What someone listened to |
| `buzz.bookhive.book`, `social.popfeed.*` | What someone read or watched |
| `community.lexicon.calendar.rsvp` | Events someone attended |
| `social.grain.*` | Photo galleries from Grain |

LogDate never writes `app.bsky.*` records.

### Rules for LogDate lexicons

- **Extend with sidecars.** Never add fields to another app's record. Add a sidecar record with the same record key in a LogDate collection.
- **Write for evolution.** Prefer `knownValues` over closed enums, and keep unions open.
- **Use the protocol's native types.** Datetimes are RFC 3339 strings, media are `blob`, and references are `strongRef`.
- **Breaking changes need a new NSID.** CI runs lint and a breaking-change diff on every lexicon change.
- **Generate the code.** Kotlin models are generated from the lexicons, never written by hand alongside them.

## Project map

The initiative groups its projects into six tracks. Arrows show dependencies.

**Product foundations**
- A1 Social model: circles and small worlds

**Data foundation**
- B1 LogDate lexicon family
- B2 Lexicon toolchain (← B1)
- B3 Private and shared repos (← B1)
- B4 Migrate sync and export to the lexicons (← B2, B3)
- B5 Publish LogDate lexicons (← B1)

**Identity**
- C1 Identity safeguards
- C2 LogDate accounts as Atmosphere identities (← C1, B3)
- C3 Continue with Bluesky (← C1, F1)

**Social**
- D1 Circles (← A1, D2, B4)
- D2 End-to-end encrypted sharing (← B1, B3)
- D3 Friends from the Atmosphere (← C3)
- D4 Circle conversations (← D1)
- D5 Safety for small worlds (← D1)

**Pull in and publish deliberately**
- E1 Your Atmosphere life in your days (← C2 or C3, B2)
- E2 Publish a story (← C2, B5)
- E3 Adopt Atproto Spaces (← D2, B3)

**Ecosystem and housekeeping**
- F1 AT Protocol Kotlin library 1.0
- F2 Retire stale AT Protocol docs

Every user-visible project ships behind a feature flag until its acceptance criteria pass, following the repository's commit discipline for `main`.

## Measures of success

- **Security tests.** No private record or blob is readable without authorization, enforced by route tests.
- **Lexicon coverage.** Every persisted record type has a published lexicon that passes lint and the breaking-change diff in CI.
- **Push audit.** No LogDate content appears in Bluesky without an explicit Publish action, checked by an outbound-write audit.
- **Product signals:**
  - time from install to a first circle share;
  - the share of shares that go to circles rather than out through the share sheet;
  - friends found through the Atmosphere.
- **Ecosystem signal.** Other apps read or write `app.logdate.*` lexicons, as seen on lexicon.garden.

## Open questions

- **Reaction and reply scope.** How far should reactions and replies go inside a circle before they feel like a feed? (A1, D4)
- **Encryption design.** MLS or per-share content keys wrapped to device keys? (D2)
- **Sign-in label.** "Continue with Bluesky" or a broader Atmosphere label? (C3)
- **Handle forwarding.** Whether logdate-web forwards `/.well-known/atproto-did` for `*.logdate.app` handles, or the server serves them directly. (C1)
- **Moderation services.** How much of Bluesky's moderation-label ecosystem LogDate should use for imported identities. (D5)

## Decisions log

| Date | Decision |
|---|---|
| 2026-10-09 | Pull in, never push: LogDate reads from Bluesky and the Atmosphere, and publishes only on an explicit, per-item action |
| 2026-10-09 | Circles, following Google+ circles and Instagram share lists, are the social model |
| 2026-10-09 | Lexicons are the canonical schema for all LogDate data, private data included |
| 2026-10-09 | The private data home starts as LogDate Cloud, behind an abstraction that allows self-hosted and external PDS homes later |
| 2026-10-09 | Every LogDate account becomes a public Atmosphere identity once the safeguards are in place |
| 2026-10-09 | Circles ship on LogDate-native end-to-end encrypted sync first and adopt Atproto Spaces once it stabilizes |
| 2026-10-09 | New lexicons use the `app.logdate.*` namespace |
| 2026-10-10 | Every LogDate record has a defined Activity Streams 2.0 shape. ActivityPub, if added, publishes only deliberately published records, generated from the same lexicon records |
| 2026-10-10 | Published stories are not followable on either protocol: they are links people hand out, and LogDate rejects follow and subscription requests |
