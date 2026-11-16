# LogDate lexicons

LogDate is moving to a lexicon-first data model: every kind of record LogDate stores is described
once, as an AT Protocol [Lexicon](https://atproto.com/specs/lexicon) schema, and the app's models,
sync format and export archive are generated from those schemas. This reference is the working map
for that move. The reasoning behind it is in
[AT Protocol interoperability](../explanation/atproto-interoperability.md),
and the sharing rules the schemas must support are in the
[social model](../feature-design/social-model.md).

The schemas are drafted in the [`lexicons/`](../../lexicons) folder and haven't been published.
Nothing reads or writes them yet: today's sync still uses the older `studio.hypertext.logdate.*`
lexicons, which cover only part of the data and don't follow the Lexicon specification. This
document records which data becomes which record, and where each record is allowed to live. How to
write and change a schema is in [Writing LogDate lexicons](./lexicon-style.md).

## How to read the classification

Every piece of data gets three labels.

**Who can ever see it** (sensitivity):

- **Private only**: only the owner, ever. It can't be shared, even on purpose.
- **Shareable**: private by default, and can be shared with connections, circles or groups when the
  owner chooses.
- **Publishable**: private by default, and the owner can also publish it as a public story.

**Where it comes from** (origin):

- **Authored**: something the person made or decided: an entry, a journal, a correction.
- **Derived**: something LogDate computed from other data: a transcript, an inferred person, a
  Rewind.
- **Bookkeeping**: state the app needs to run, which isn't anyone's content.

**Where it lives** (storage, from the design's three kinds of repository):

- **Personal space**: the owner-only, end-to-end encrypted repository. Records are sealed, so the
  server sees neither their type nor their timestamps.
- **Circle or group space**: a copy encrypted to the people it was shared with.
- **Public repository**: visible to the whole AT Protocol network. Only the profile, public sharing
  keys and deliberately published stories go here.
- **Device only**: not a record at all. It stays on one device (or in the server's own tables) and
  never becomes a lexicon.

Derived data is a record when it is expensive or impossible to recompute (a transcript, a Rewind),
when the person has acted on it (confirming an inferred person), or when every device needs to
agree on it (inferred people). Otherwise it stays device-only and is recomputed.

## Client data

Classified from the latest Room schema. When the schema changes,
`ExportCoverageTest` forces a decision about every new table; this table should change with it.

| Table                                                                                 | Becomes                                                                                                 | Who can see it                                           | Origin                                                                    | Lives in       |
| ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------- | -------------- |
| `text_notes`, `image_notes`, `video_notes`, `audio_notes`                             | `app.logdate.journal.entry`. Each note becomes one entry with a single block                            | Publishable                                              | Authored                                                                  | Personal space |
| `media_captions`                                                                      | The `caption` field of the entry's image, video or audio block                                          | Publishable                                              | Authored                                                                  | Personal space |
| `transcriptions`, `transcription_segments`                                            | `app.logdate.journal.transcript`, attached to an audio block                                            | Shareable, with its audio                                | Derived, costly                                                           | Personal space |
| `audio_tags`                                                                          | Part of the transcript                                                                                  | Shareable, with its audio                                | Derived                                                                   | Personal space |
| `journals`                                                                            | `app.logdate.journal.journal`                                                                           | Shareable (a shared journal keeps reaching its audience) | Authored                                                                  | Personal space |
| `journal_content_links`                                                               | `app.logdate.journal.membership`                                                                        | Follows the journal                                      | Authored                                                                  | Personal space |
| `journal_merges`                                                                      | Not a record. The merged-away journal's record gets `mergedInto` instead                                | —                                                        | Bookkeeping                                                               | Device only    |
| `marked_moments`                                                                      | `app.logdate.journal.markedMoment`                                                                      | Private only (it can hold a heart rate)                  | Authored                                                                  | Personal space |
| `user_places`                                                                         | `app.logdate.place.place`                                                                               | Private only                                             | Authored                                                                  | Personal space |
| `history_records`                                                                     | One record type per kind of history record; see [Location history](#location-history)                   | Private only                                             | Derived from sensors, with authored places, corrections, links and visits | Personal space |
| `location_logs`                                                                       | `app.logdate.place.sample`, one record per device per hour                                              | Private only                                             | Derived from sensors                                                      | Personal space |
| `location_activity`                                                                   | The `transitions` of `app.logdate.place.sample`                                                         | Private only                                             | Derived from sensors                                                      | Personal space |
| `history_cursors`                                                                     | Not a record                                                                                            | —                                                        | Bookkeeping                                                               | Device only    |
| `places`                                                                              | Not a record. Nothing writes it; drop it with the migration.                                            | —                                                        | —                                                                         | —              |
| `events`                                                                              | Reused `community.lexicon.calendar.event`, plus `app.logdate.event.annotation` for LogDate's own fields | Shareable                                                | Derived from calendars, edited by the person                              | Personal space |
| `event_note_links`                                                                    | Part of `app.logdate.event.annotation`                                                                  | Follows the event                                        | Authored                                                                  | Personal space |
| `people`                                                                              | `app.logdate.people.person`                                                                             | Private only                                             | Authored or confirmed                                                     | Personal space |
| `person_links`                                                                        | `app.logdate.people.mention`                                                                            | Private only                                             | Authored or confirmed                                                     | Personal space |
| `person_resolution_decisions`                                                         | `app.logdate.people.decision`                                                                           | Private only                                             | Authored                                                                  | Personal space |
| `inferred_person_clusters`, `inferred_person_evidence`                                | `app.logdate.people.inference`, with its evidence                                                       | Private only                                             | Derived                                                                   | Personal space |
| `rewinds`, `rewind_text_content`, `rewind_image_content`, `rewind_video_content`      | `app.logdate.rewind.rewind`, with its panels                                                            | Publishable                                              | Derived, costly                                                           | Personal space |
| `rewind_prompt_responses`                                                             | `app.logdate.rewind.promptResponse`                                                                     | Shareable                                                | Authored                                                                  | Personal space |
| `rewind_generation_requests`                                                          | Not a record                                                                                            | —                                                        | Bookkeeping                                                               | Device only    |
| `postcards`                                                                           | `app.logdate.craft.postcard`                                                                            | Publishable                                              | Authored                                                                  | Personal space |
| `stickers`                                                                            | `app.logdate.craft.sticker`                                                                             | Publishable                                              | Authored                                                                  | Personal space |
| `health_snapshots`                                                                    | `app.logdate.health.snapshot`                                                                           | Private only                                             | Derived from sensors                                                      | Personal space |
| `indexed_media_images`, `indexed_media_videos`, `media_exif_metadata`                 | Not records. They index the device's own photo library.                                                 | —                                                        | Derived                                                                   | Device only    |
| `sync_cursors`, `pending_uploads`, `sync_download_inbox`, `sync_download_checkpoints` | Not records                                                                                             | —                                                        | Bookkeeping                                                               | Device only    |
| `search_index_metadata`, `storage_metadata`                                           | Not records                                                                                             | —                                                        | Bookkeeping                                                               | Device only    |
| `user_devices`                                                                        | Not a record. The server's device registry.                                                             | —                                                        | Bookkeeping                                                               | Server         |
| `journal_notes`, `media_images`                                                       | Not records. Legacy tables nothing reads; drop them.                                                    | —                                                        | —                                                                         | —              |

Some data lives outside Room and also becomes records:

| Data                               | Becomes                                                                                                                          | Who can see it                      | Origin               | Lives in          |
| ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- | -------------------- | ----------------- |
| Profile (display name, photo, bio) | `app.logdate.actor.profile`                                                                                                      | Public                              | Authored             | Public repository |
| Birthday                           | `app.logdate.actor.settings`, not the profile                                                                                    | Private only                        | Authored             | Personal space    |
| Editor drafts                      | `app.logdate.journal.draft`                                                                                                      | Private only                        | Authored, unfinished | Personal space    |
| Journal favorites                  | The `favorite` field of `app.logdate.journal.journal`, left out of shared copies                                                 | Private only                        | Authored             | Personal space    |
| Places named in entries            | `location.place` and `location.placeDetails` of an entry, left out of shared copies unless the person chooses to share the place | Private only, like the place itself | Authored             | Personal space    |

## How today's synced data becomes records

Today's sync sends notes, journals, journal links, drafts, marked moments and location history
records. Each piece of that data has a field in the new records, so switching sync over loses
nothing. This section is the field-by-field map for that switch.

### Notes become entries

Each note becomes one `app.logdate.journal.entry` with a single block. The entry's record key and
the block's `id` are both the note's id. Notes aren't grouped today: each block in the editor is
saved as its own note, with its own time, location and capture details, so merging notes into one
entry would mix those up. Entries with several blocks stay possible for later.

| Note field                                    | Entry field                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `creationTimestamp`, `lastUpdated`            | `createdAt`, `updatedAt`                                                                                                                                                                                                                                                                                                                                                                                                                               |
| Text `content`                                | `textBlock.text`, with `format` left out (markdown, which is what the editor writes)                                                                                                                                                                                                                                                                                                                                                                   |
| Photo, video or audio file                    | The block's `media`, with the `mimeType` the upload already reads from the file. Left out when the file is unavailable (see below)                                                                                                                                                                                                                                                                                                                     |
| Photo, video and audio `caption`              | The block's `caption`                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Photo `presentation` (`EdgeToEdge`, `Framed`) | `imageBlock.presentation` (`edgeToEdge`, `framed`)                                                                                                                                                                                                                                                                                                                                                                                                     |
| Audio `durationMs`                            | `audioBlock.durationMs`                                                                                                                                                                                                                                                                                                                                                                                                                                |
| Audio `transcript`                            | An `app.logdate.journal.transcript` keyed `<entry>:<block>`, referred to from `audioBlock.transcript`                                                                                                                                                                                                                                                                                                                                                  |
| Location coordinates and accuracy             | `location.geo` (latitude, longitude, altitude) and `location.accuracyCentimeters`                                                                                                                                                                                                                                                                                                                                                                      |
| Location place                                | `location.place`, plus `location.placeDetails` with the place's name and center, so the location still reads correctly where the place record is missing. When the place record is available, its name (after label corrections) and center are shown instead of the copy. A location can have a place without coordinates. Places are private only, so a shared copy of the entry leaves both fields out unless the person chooses to share the place |
| `timeZoneId`                                  | `timeZone`. Today's sync leaves it out; the entry carries it                                                                                                                                                                                                                                                                                                                                                                                           |
| `capturedOn` (`phone`, `watch`)               | `capturedOn`, with the same values                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `capturedDuring` (`moving`, `in_vehicle`)     | `capturedDuring` (`moving`, `inVehicle`)                                                                                                                                                                                                                                                                                                                                                                                                               |

Some notes have a file no device has: notes restored from an archive without their media, whose
reference starts with `logdate-unavailable://archive/` or `logdate-unavailable://native/`, and notes
with an empty media reference. They still become entries. The block has no `media`, and keeps its
caption, length, transcript and the entry's time and location. Apps show the block with a note
that the file is missing.

The app doesn't limit how long an entry's text or a caption is, so captions share the text block's
limits: 1,000,000 bytes and 100,000 characters. The same goes for journal titles and descriptions,
place names and descriptions, and the labels in corrections. A value longer than that can't be
stored, at any time: while data is first moved over, and on every sync write after that, because
every write goes through the same conversion. The device never cuts the text short. It refuses
that one record and shows a sync error naming the field that's too long, so the person can shorten
it; the rest of their data keeps syncing.

### Transcripts

A transcript's record key is the entry's key and the audio block's id joined by a colon
(`<entry>:<block>`), so an entry with several recordings can have a transcript for each. For an
entry made from a note, both parts are the note's id.

A transcript keeps everything the apps store about it: every segment with its id, timing, text,
words, speaker, confidence, recognition pass and whether it's final; the speakers; the engine's
name, model and version; detected sounds; and the revision. A confidence from 0 to 1 becomes
thousandths: times 1000, rounded to the nearest whole number with halves away from zero, and kept
between 0 and 1000. A NaN or infinite confidence is left out.

Speaker colors aren't stored. The apps can assign a color to each speaker without one, and nothing
sets one today.

The status covers both the transcript's own lifecycle and the transcription job's:

| Today                                                                                  | `status`     | Read back as                                                             |
| -------------------------------------------------------------------------------------- | ------------ | ------------------------------------------------------------------------ |
| Job `PENDING`                                                                          | `pending`    | Job `PENDING`, with a transcript in `LISTENING` that has no segments yet |
| Job `IN_PROGRESS`, transcript `LISTENING` (the first pass is still producing segments) | `inProgress` | Job `IN_PROGRESS`, transcript `LISTENING`                                |
| Transcript `REFINING` (a second pass is improving a transcript that already exists)    | `refining`   | Job `IN_PROGRESS`, transcript `REFINING`                                 |
| Job `COMPLETED`, transcript `FINAL`                                                    | `complete`   | Job `COMPLETED`, transcript `FINAL`                                      |
| `FAILED`                                                                               | `failed`     | Job `FAILED`, transcript `FAILED`                                        |

Segment sources keep their order of authority: `imported`, `localLive`, `cloudLive`,
`localRefinement`, `cloudRefinement`.

### Journals and memberships

A journal's `isFavorited` becomes `favorite`. When the person merges journals, the merged-away
journal isn't deleted. Its record is rewritten with `mergedInto` naming the surviving journal, so a
device that comes back online with entries or drafts still in the old journal can move them over.
The device that merges also rewrites the memberships and drafts it knows about.

That record is deleted when the surviving journal is deleted, and never before: a device can be
offline for any length of time and still needs the redirect when it returns. If the surviving
journal is itself merged later, every record that names it in `mergedInto` is updated to the new
survivor, so a redirect is always one step.

Two devices can merge journals into each other while offline, so that following `mergedInto` goes
in a circle. The journal in the circle with the smallest record key, compared byte by byte,
survives: the device that finds the circle removes that journal's `mergedInto` and points the
others at it.

A membership's record key is `<journal>:<entry>`, the same pair today's journal links are keyed by.
Its `createdAt` is optional. Two devices that add the same entry to the same journal write the same
key, so each writes it create-only (`createOnly`) and leaves a membership that's already there as
it is. The first `createdAt` written is then the one every device sees. Entry links are written the
same way.

### Drafts

A draft keeps each block's own start time and location, because each block becomes its own entry
when the draft is saved. `journals` lists every journal the person picked. A video block keeps its
thumbnail. An audio block keeps its length and the text recognized so far, in `transcriptText`,
because a draft has no entry for a transcript record to refer to. The editor's camera block is its
own kind, `cameraBlock`, because it holds a photo or a video and the file's `mimeType` says which.
Blocks whose file hasn't been picked or recorded yet have no `media`.

### Marked moments

A moment marked on the watch becomes an `app.logdate.journal.markedMoment`. Its states become
`open`, `dismissed` and `filled`, and its resolved note ids become `resolvedEntries`. It's private
only: it records a place and can record a heart rate, and it's a reminder, not something to share.

### Location history

Today's location history is a set of `history_records`, each with a kind. Each kind becomes its own
record type:

| History record kind                 | Becomes                                                               | Record key                                                                                                                             |
| ----------------------------------- | --------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `observation` (a location sample)   | A point in `app.logdate.place.sample`                                 | The hour's record: `<UTC hour>:<device>`, such as `2026-10-19T14:<device>`, then `:2`, `:3` and so on for further parts of a full hour |
| `activity` (an activity change)     | A transition in `app.logdate.place.sample`                            | The same hour's record                                                                                                                 |
| `place`                             | `app.logdate.place.place`, merged with the saved place of the same id | The place's id                                                                                                                         |
| `correction`                        | `app.logdate.place.correction`                                        | The correction's id                                                                                                                    |
| `memory-link`                       | `app.logdate.place.entryLink`                                         | `<entry>:<SHA-256 of the evidence id, in lowercase hexadecimal>`                                                                       |
| `manual` (a visit the person added) | `app.logdate.place.visit`                                             | The visit's id                                                                                                                         |

Deleting a sample or an activity change is no longer a deleted record of its own. The device
removes it from the part of the hour that holds it and lists its id in that same part's
`deletedSampleIds` or `deletedTransitionIds`, never in another part. The deleted location is then
gone from the stored record, and the device that recorded it never adds it back.

That means a device can rewrite an hour another device recorded: deleting a visit deletes its
samples, whichever device took them. The first write of an hour, or of a new part, is create-only
(`createOnly`), because there's no version to compare with yet. Every later write is conditional on
the version the device last read (`swapCid`). When a write is refused because the record exists or
changed, the device reads it again, merges, and retries. Merging two versions is always the same:

- `samples` is every sample in either version, minus every id in either version's
  `deletedSampleIds`.
- `transitions` is every transition in either version, minus every id in either version's
  `deletedTransitionIds`.
- Each deletion list is everything in either version. Deletion lists only grow.

A part of an hour holds at most 5,000 samples and deleted sample ids together, and at most 500
activity changes and deleted activity ids together. The schema's separate list limits allow up to
twice that, and even then, with every field at its longest, a record is about 3.6 MB, which fits in
one sealed record (about 3.99 MB of payload). The foreground stream, the busiest source, takes about one sample a second, or 3,600 an
hour.

New samples and activity changes always go into the hour's last part. The device starts the next
part (`part` 2, keyed `:2`, and so on) as soon as the last part's samples plus `deletedSampleIds`
reach 5,000, or its transitions plus `deletedTransitionIds` reach 500. A sample can only move from
its part's `samples` to the same part's deletion list, so the two together never grow past 5,000
and a deletion always fits.

Visits and journeys that LogDate works out from samples aren't records. Every device works them
out again. `app.logdate.place.visit` holds only visits the person added, and
`app.logdate.place.journey` is reserved: nothing writes it yet.

Corrections and entry links name what they apply to with an evidence id, exactly as today: a sample
id, a visit's record key, `place:<place key>`, or the `derived:journey:` id of a journey inferred
between two visits. A correction's value moves from one string into a field of its own type:
`label`, `place`, `activity`, `startsAt` or `endsAt`. A `delete` correction has no value.

Today each correction lists every earlier correction to the same field in `supersedes`. A
correction only needs to list the ones not already replaced by another correction: the older ones
stay replaced through the correction that replaced them, and corrections are never deleted. Moving
corrections over keeps only those, which keeps `supersedes` under its limit and loses nothing.

An entry link's record key is the entry's key and the SHA-256 of the evidence id, so linking the
same entry to the same visit twice, on any device, writes one record. The evidence id is hashed
because it can be too long for a record key or hold characters a key can't.

How the person moved keeps every value it has today:

| `TravelMode`                             | `activity` and `mode`                    |
| ---------------------------------------- | ---------------------------------------- |
| `STILL`, `WALKING`, `RUNNING`, `CYCLING` | `still`, `walking`, `running`, `cycling` |
| `VEHICLE` (a vehicle of unknown kind)    | `vehicle`                                |
| `DRIVING`, `BUS`, `TRAIN`                | `driving`, `bus`, `train`                |
| `UNKNOWN`                                | `unknown`                                |

Activity changes keep the device's own activity names (`still`, `walking`, `running`, `onFoot`,
`onBicycle`, `inVehicle`, `unknown`) and whether the person started (`enter`) or stopped (`exit`).

The apps keep places in two tables that share ids: the places the person saved (`user_places`,
with a radius, a description and timestamps) and the places in location history (with an address,
a map directory id and whether the person confirmed it). Each id becomes one
`app.logdate.place.place` with every field from both:

- When both have a name, coordinates or radius, the saved place's wins. Renaming or moving a saved
  place changes it in place, so it holds the person's latest choice.
- Label corrections that target `place:<id>` decide the name shown, exactly as today. The current
  ones are those no other correction lists in `supersedes`. If they all give the same label, it's
  shown and the place counts as confirmed. If they disagree, the place's `name` is shown and the
  person is offered the conflict to settle. Nothing depends on which correction is newest, because
  moved-over corrections have no `createdAt`. The same rule applies to every corrected field.
- A saved place is always `userConfirmed`. `createdAt` comes from the saved place; places that only
  came from history have none.
- Deleting the record deletes both. Today, deleting the history place already hides the saved one.

Fractional measurements become integers in smaller units: accuracy and radius in centimeters, speed
in centimeters per second, and bearing in hundredths of a degree. Each follows the conversion rule
in [Writing LogDate lexicons](./lexicon-style.md#conventions), with `tools/lexicon-check/convert.mjs`
as the reference and `conversion-vectors.json` as the shared test cases. The arithmetic is 64-bit
double: Android's `Float` values are widened to `Double` first. A NaN or infinite value is left out.
Otherwise the value times 100 is rounded to the nearest whole number, with halves away from zero,
and a result too large to represent exactly (beyond 2^53 − 1) is left out too, never saturated. A
negative accuracy, speed or radius becomes 0. A bearing is first reduced into 0 to 360° with
`fmod` on the double, so any finite bearing is kept: 359.996° becomes 0 and −360.5° becomes 35,950.
Digits below a centimeter, which a float can carry, are dropped: they're finer than any location
sensor measures.

### Records too large to seal

A sealed record holds at most about 3.99 MB once encoded. Location samples are the only records
built to stay under that: an hour is split into parts. Any other record can still be too large,
most likely a transcript of a recording many hours long (word timings take room), or a draft or
entry with many long blocks or captions.

Such a record is refused, never trimmed or split. The device doesn't write it, shows a sync error
for that record (the server's `RecordTooLarge`, or the same check on the device before sending), and
keeps the record as it is locally. Everything else keeps syncing. For an entry or draft, the person
can shorten it or split it into several. A transcript that's too large stays on the device that
made it; the recording itself still syncs. Splitting transcripts into parts the way samples are
split would mean a second way to store them for a rare case, so it waits until real recordings
need it.

## Data the sharing features add

These don't exist yet. They come from the [social model](../feature-design/social-model.md).

| Data                        | Becomes                                                                                                                                                                                                                                                     | Who can see it                       | Lives in              |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ | --------------------- |
| Connections                 | `app.logdate.graph.connection`                                                                                                                                                                                                                              | Each side sees only their own        | Personal space        |
| Mutes                       | `app.logdate.graph.mute`                                                                                                                                                                                                                                    | Only the owner                       | Personal space        |
| Blocks                      | `app.logdate.graph.block`                                                                                                                                                                                                                                   | Only the owner                       | Personal space        |
| Circles and their members   | `app.logdate.graph.circle`, `app.logdate.graph.circleMember`                                                                                                                                                                                                | Only the owner                       | Personal space        |
| Groups                      | `app.logdate.space.groupProfile`, with an `app.logdate.space.member` record per member                                                                                                                                                                      | The group's members                  | Group space           |
| A shared item               | `app.logdate.share.share`, the author's record of what they shared and with whom                                                                                                                                                                            | Only the author                      | Personal space        |
| What recipients receive     | `app.logdate.share.item`, plus copies of the shared records under their usual types. A copy leaves out private-only parts: a journal's `favorite`, and an entry's `location.place` and `location.placeDetails` unless the person chooses to share the place | The people it was shared with        | Circle or group space |
| Reactions and replies       | `app.logdate.share.reaction`, `app.logdate.share.reply`                                                                                                                                                                                                     | The author and the person responding | Circle or group space |
| Content keys for recipients | `app.logdate.crypto.wrappedKey`                                                                                                                                                                                                                             | The recipient                        | Circle or group space |
| Sharing keys                | `app.logdate.crypto.keyDeclaration`                                                                                                                                                                                                                         | Public, so others can encrypt to you | Public repository     |

The social graph is deliberately stored as private records. On most AT Protocol apps follows are
public; in LogDate nobody can see your connections, circles, mutes or blocks.

## Server data that never becomes a lexicon

The server also stores data that isn't anyone's content. It stays in the server's own tables:
accounts, sessions, passkeys, account key envelopes, device enrollments, billing, diagnostic
reports, OAuth state, signing keys, and the sync and repository indexes.

## The schema family

All new schemas use the `app.logdate.*` namespace. Each group below is its own authority under
`logdate.app` when published.

| Group                 | Records                                                                                                                        |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `app.logdate.defs`    | `recordRef`, `media`, `aspectRatio`, `location` (shared definitions, not records)                                              |
| `app.logdate.journal` | `entry`, `transcript`, `journal`, `membership`, `draft`, `markedMoment`                                                        |
| `app.logdate.place`   | `place`, `visit`, `correction`, `entryLink`, `sample`, and `journey` (reserved: nothing writes it yet)                         |
| `app.logdate.people`  | `person`, `mention`, `decision`, `inference`                                                                                   |
| `app.logdate.event`   | `annotation`                                                                                                                   |
| `app.logdate.rewind`  | `rewind`, `promptResponse`                                                                                                     |
| `app.logdate.craft`   | `postcard`, `sticker`                                                                                                          |
| `app.logdate.health`  | `snapshot`                                                                                                                     |
| `app.logdate.actor`   | `profile`, `settings`                                                                                                          |
| `app.logdate.graph`   | `connection`, `mute`, `block`, `circle`, `circleMember`                                                                        |
| `app.logdate.share`   | `share`, `item`, `reaction`, `reply`                                                                                           |
| `app.logdate.space`   | `groupProfile`, `member`, the `personal`, `circle` and `group` space declarations, and the personal-space methods listed below |
| `app.logdate.crypto`  | `keyDeclaration`, `sealed` (the envelope every private record is stored in), `wrappedKey`                                      |
| Permission sets       | `app.logdate.authFull`, `app.logdate.authShare`                                                                                |

### Space declarations

AT Protocol Spaces names every space by three parts: the DID that controls it (its authority), a
space type and a space key, as in `at://{authority}/space/{spaceType}/{skey}`. A space type is
itself a lexicon, a _space declaration_: a file whose `main` definition has `"type": "space"`. It
gives the kind of space a name people see when an app asks for access, says how its spaces are
keyed, and lists the record collections apps should expect inside. Spaces is still an alpha in the
AT Protocol, so these follow its proposal as it stands and may change with it.

| Space type                   | Name people see          | Space key                       | Collections                 | What it holds                                                                            |
| ---------------------------- | ------------------------ | ------------------------------- | --------------------------- | ---------------------------------------------------------------------------------------- |
| `app.logdate.space.personal` | Your LogDate journal     | `literal:self`: one per account | `app.logdate.crypto.sealed` | Everything private, sealed                                                               |
| `app.logdate.space.circle`   | Things shared in LogDate | `any`: a random key per share   | `app.logdate.crypto.sealed` | Something shared with a circle or with people, and the recipients' reactions and replies |
| `app.logdate.space.group`    | LogDate groups           | `any`: a random key per group   | `app.logdate.crypto.sealed` | Everything shared into a group, its `groupProfile` and its `member` records              |

Every record in these spaces is sealed, so each declaration lists only `app.logdate.crypto.sealed`.
The records inside the envelopes, such as entries, share items and group profiles, are not
collections the server or another app can see. Where a recipient's `app.logdate.crypto.wrappedKey`
records live is decided by End-to-end encrypted sharing. If they go into circle or group spaces
unsealed, adding them to those declarations' collections is a compatible change.

The group's own description used to be the record `app.logdate.space.group`. That name is now the
group space type, so the description record is `app.logdate.space.groupProfile`.

**Who may write a group's profile and members.** Every member writes into their own repository in
the group space, and any member's app could write a record that claims to be the group's profile
or someone's membership. Apps therefore accept `app.logdate.space.groupProfile` and
`app.logdate.space.member` records only from the repository of the group space's authority, the
person who started the group, and ignore copies found in any other member's repository.

### Permission sets

A permission set bundles the access an app asks for into one OAuth scope, `include:<set NSID>`,
with a title and a summary that the account's server shows when someone approves the app (see
the [permission specification](https://atproto.com/specs/permission)). A set may only grant access
inside its own namespace, so LogDate's sets can't grant anything outside `app.logdate.*`; an app
that also wants, say, `site.standard.*` for published stories asks for that separately.

Each grant follows least privilege along two lines:

- **Records** in circle and group spaces under any authority (`authority: "*"`), because what other
  people share with you is stored in their spaces. These permissions have no `manage`.
- **Managing spaces** (`manage`) only under the default authority, `self`: the spaces the person
  controls. No set can change or delete a space someone else started.

**Why leaving out `collection` is safe.** Both sets leave out a space permission's `collection` and
`action`, so they grant reading and writes to the collections the space declaration lists:

- reading ignores `collection` entirely, because Spaces grants read access to a whole space or
  nothing;
- every record in these spaces is an `app.logdate.crypto.sealed` envelope, so the only collection a
  write can target is the sealed one, and what an app can do with it depends on the keys it holds;
- the boundary that matters is the space type plus the authority, which every permission names.

A collection added to a declaration reaches every session holding a set without anyone approving
the app again. That makes a change to a declaration's collections a change to the sets too, and it
needs the same review. `./run lint:lexicons` reports any collection added to the circle or group
declaration as widening `app.logdate.authShare` until the reviewed list in
`tools/lexicon-check/declarations.mjs` is updated.

**`app.logdate.authFull`, "Use LogDate"**, is everything LogDate's own apps need:

- writing the public profile (`app.logdate.actor.profile`) and the public sharing keys
  (`app.logdate.crypto.keyDeclaration`) to the account's public repository;
- the account's own personal space, and creating it (`manage: ["create"]`). It can't `update` the
  personal space, which could change who may read it, or `delete` it, which would destroy the
  journal;
- records in circle and group spaces under any authority;
- creating, changing and deleting the person's own circle and group spaces.

**`app.logdate.authShare`, "Share with your people"**, is for another app that lets people share
through LogDate, such as a camera app. It grants only what sharing needs:

- records in circle spaces under any authority: sharing something, seeing what others shared with
  you, and reacting or replying;
- creating the person's own circle spaces and changing them to add recipients
  (`manage: ["create", "update"]`), but not deleting them;
- records in group spaces under any authority: seeing and sharing into groups you're in.

It deliberately leaves out:

- **the personal space**, so the app can never read the journal;
- **the public profile and the sharing keys**, because replacing someone's sharing keys would let
  an app read what is shared with them later. Reading other people's keys to encrypt to them needs
  no permission, because they are public;
- **deleting shares**, which stays in LogDate's own apps;
- **creating and running groups**, which stay in LogDate's own apps, because a group's owner
  decides who is in it.

`./run lint:lexicons` refuses any version of `app.logdate.authShare` that names the personal space,
a `repo` permission, the personal-space methods or `manage` on groups.

Neither set includes `rpc` permissions. The personal-space methods below only accept LogDate's own
access tokens today, and once LogDate moves onto Spaces those calls are covered by the space
permissions.

### Personal-space methods

`app.logdate.space` also defines the six XRPC methods a LogDate device calls to store and read its
owner's sealed records. They are methods, not records, so they don't appear in the classification
tables above. Every record stored through them is an `app.logdate.crypto.sealed` envelope, so the
server stores ciphertext and never learns what a record says. Only the owner's own LogDate devices
can call them: the server accepts a LogDate access token and nothing else, and a request for
anyone else's space gets the same answer as a space that doesn't exist.

| Method                           | What it does                                                                                                                                                                                                                                                                   |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `app.logdate.space.putRecord`    | Creates or replaces one sealed record, optionally only if it still has the CID the caller last saw (`swapCid`)                                                                                                                                                                 |
| `app.logdate.space.deleteRecord` | Deletes one sealed record and leaves a deletion marker, so other devices learn it is gone                                                                                                                                                                                      |
| `app.logdate.space.getRecord`    | Reads one sealed record by its key                                                                                                                                                                                                                                             |
| `app.logdate.space.listRepoOps`  | Lists changes after a sequence number, deletions included, one page at a time. A device starts from `since=0` to download everything. If the server has already purged deletions after the `since` a device holds, it answers `CursorTooOld` and the device lists again from 0 |
| `app.logdate.space.uploadBlob`   | Uploads one encrypted file and returns the blob reference to put in a sealed record                                                                                                                                                                                            |
| `app.logdate.space.getBlob`      | Downloads one encrypted file by its CID                                                                                                                                                                                                                                        |

A server only answers these methods when its operator has turned on
[`LOGDATE_PERSONAL_SPACE_ENABLED`](../../server/docs/environment-variables.md#logdate_personal_space_enabled).
Until then they answer `501 MethodNotImplemented`.

Every method can also answer `RateLimitExceeded` when an account sends far more requests than a
syncing device does; the device waits the seconds in the `Retry-After` header and tries again.
`putRecord` and `uploadBlob` answer `QuotaExceeded` when storing what was sent would take the
account past its storage quota. `uploadBlob` and `getBlob` answer `ServerBusy` when the server is
already moving as many files as it can hold; the device waits the `Retry-After` seconds and tries
again. An `uploadBlob` request must carry a `Content-Length` header, because the server holds the
file's room against the quota before it receives a byte; without one it answers `LengthRequired`. An
upload that sends nothing for too long is ended with `UploadStalled` and can be sent again.

**Reused as they are:** `community.lexicon.location.*` inside places and entries,
`community.lexicon.calendar.event` and `rsvp` for events, `com.atproto.repo.strongRef` for
references, and `site.standard.publication` / `document` for published stories only.

**Read only, never written:** Bluesky's follows, profiles, blocks and lists, and other apps'
records for the "Your Atmosphere life in your days" imports (teal.fm, Bookhive, Popfeed, Smoke
Signal, Grain).

## Activity Streams mapping

Every LogDate record also has a defined [Activity Streams 2.0](https://www.w3.org/TR/activitystreams-core/)
shape. Activity Streams is the vocabulary ActivityPub and the fediverse use. Defining the mapping
keeps LogDate's data legible outside AT Protocol, gives the export archive a standard
representation, and means full ActivityPub support later is a projection of existing records, not a
second data model.

The rules:

- **A mapping is a view, not a second store.** The lexicon record is the source of truth. The
  Activity Streams form is generated from it, never stored separately or edited directly.
- **The mapping describes decrypted content.** A sealed or encrypted record has no Activity Streams
  form on the wire. The mapping applies after the owner, or someone it was shared with, decrypts it.
- **Defining a mapping never means federating.** Only publishable records may ever be delivered to
  other servers, and only when published. Everything else uses the mapping for export and
  interoperability alone.
- **Standard terms first, LogDate terms when needed.** Fields with no Activity Streams equivalent go
  under a LogDate JSON-LD context (`https://logdate.app/ns#`) instead of being dropped.
- **Deletions become `Tombstone`s**, so an export or a remote copy can tell "deleted" from "never
  existed".

The Kotlin types for the vocabulary already exist in `shared/activitypub`; the serializer doesn't
yet.

| Record                                         | Activity Streams shape                                                                                                                                                                                                                               |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `journal.entry`                                | `Note`, with `content` (Markdown rendered to HTML, the source kept in `source`), `attachment` for each photo, video and audio block (`Image`, `Video`, `Audio`, each with `mediaType` and `name` as alt text), `published`, `updated` and `location` |
| `journal.transcript`                           | A `Document` (`mediaType: text/vtt`) attached to its `Audio`                                                                                                                                                                                         |
| `journal.journal`                              | `OrderedCollection` with `name` and `summary`; its entries are the `orderedItems`                                                                                                                                                                    |
| `journal.membership`                           | An item in the journal's collection (`Add` with the journal as `target` when expressed as an activity)                                                                                                                                               |
| `journal.draft`                                | None; drafts are unfinished and never leave the owner's devices in any other form                                                                                                                                                                    |
| `journal.markedMoment`                         | None; a reminder to write something later, not content                                                                                                                                                                                               |
| `place.place`                                  | `Place`, with `name`, `latitude`, `longitude`, and `radius` and `units` when it has a radius                                                                                                                                                         |
| `place.visit`                                  | `Arrive`, with the `Place` as `location` and the visit's start as `published`; the end time uses a LogDate term                                                                                                                                      |
| `place.journey`                                | `Travel`, with `origin` and `target` places                                                                                                                                                                                                          |
| `place.correction`                             | `Update` of the visit, journey or place it corrects                                                                                                                                                                                                  |
| `place.entryLink`                              | None; it connects two private records and has no meaning on its own                                                                                                                                                                                  |
| `place.sample`                                 | None; hourly location records use the lexicon form (and GeoJSON in exports)                                                                                                                                                                          |
| `people.person`                                | `Person` as an object, not an actor: `name`, `summary`, `icon`. It describes someone; it doesn't make them an account.                                                                                                                               |
| `people.mention`                               | A `Mention` in the entry's `tag`                                                                                                                                                                                                                     |
| `people.decision`                              | None; it records an answer to a question LogDate asked, not content                                                                                                                                                                                  |
| `people.inference`                             | None; an unconfirmed guess is not something to show outside LogDate                                                                                                                                                                                  |
| Reused `calendar.event` and `event.annotation` | `Event`, with `name`, `startTime`, `endTime` and `location`; linked entries in `context`                                                                                                                                                             |
| `rewind.rewind`                                | `OrderedCollection` of its panels; an `Article` when published                                                                                                                                                                                       |
| `rewind.promptResponse`                        | `Note` with `inReplyTo` pointing at the prompt                                                                                                                                                                                                       |
| `craft.postcard`                               | `Image` (the rendered postcard), with its parts under a LogDate term                                                                                                                                                                                 |
| `craft.sticker`                                | `Image`                                                                                                                                                                                                                                              |
| `health.snapshot`                              | LogDate terms only; Activity Streams has no health vocabulary                                                                                                                                                                                        |
| `actor.profile`                                | `Person` as the actor, with `name`, `summary`, `icon` and `preferredUsername`                                                                                                                                                                        |
| `actor.settings`                               | None; private account details                                                                                                                                                                                                                        |
| `graph.connection`                             | `Relationship` (`subject`, `object`, `relationship`)                                                                                                                                                                                                 |
| `graph.mute`                                   | `Ignore`                                                                                                                                                                                                                                             |
| `graph.block`                                  | `Block`                                                                                                                                                                                                                                              |
| `graph.circle`                                 | `Collection` of actors; always private                                                                                                                                                                                                               |
| `graph.circleMember`                           | `Add` of the actor to the circle's collection; always private                                                                                                                                                                                        |
| `space.groupProfile`                           | `Group`; invitations are `Invite`                                                                                                                                                                                                                    |
| `space.member`                                 | `Join` (and `Leave` when removed)                                                                                                                                                                                                                    |
| `share.share`                                  | `Create` of the shared object, with recipients in `to`; only the author has this form                                                                                                                                                                |
| `share.item`                                   | `Create` of the shared object as a recipient sees it, with no other recipients listed                                                                                                                                                                |
| `share.reaction`                               | `Like`, with the reaction in `content`                                                                                                                                                                                                               |
| `share.reply`                                  | `Note` with `inReplyTo` pointing at the shared item                                                                                                                                                                                                  |
| Unsharing, removing                            | `Delete` with a `Tombstone`, and `Undo` of the `Relationship`                                                                                                                                                                                        |
| `crypto.keyDeclaration`                        | None yet; Activity Streams has no end-to-end encryption vocabulary. Exports use LogDate terms on the actor                                                                                                                                           |
| `crypto.sealed`, `crypto.wrappedKey`           | None; they're encryption envelopes, not content                                                                                                                                                                                                      |
| Published stories (`site.standard.document`)   | `Article`                                                                                                                                                                                                                                            |

The machine-readable version of this table is `lexicons/activity-streams.json`. `./run
lint:lexicons` fails if a record type is missing from it; `null` means a record has no Activity
Streams form.

## Decisions

| Date       | Decision                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-10-10 | **One entry type.** `entry` holds ordered blocks (text, photo, video, audio), following the planned multi-block journal entry. The sync migration converts today's separate note tables into entries.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| 2026-10-10 | **One sealed collection.** Every private record is stored inside a single `app.logdate.crypto.sealed` collection, so the server can't tell which kinds of records someone has.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| 2026-10-10 | **Daily location records.** Raw location samples are batched into one `place.sample` record per day instead of one record per sample. _Superseded on 2026-10-18 by hourly location records, one per device._                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| 2026-10-10 | **Inferred people sync as regular records.** Unconfirmed inferences are `people.inference` records in the personal space, synced like any other content, so every device shows the same suggestions and works from the same underlying data. They stay private and never become shareable until confirmed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| 2026-10-10 | **Shared copies are ordinary records.** A shared entry, journal, Rewind, postcard or sticker is copied into the circle or group space under its usual type, and an `app.logdate.share.item` lists the copies. The AT Protocol reference implementation doesn't allow one record type to be embedded inside another, and keeping one type per kind of content means apps read shared and private entries the same way.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| 2026-10-10 | **References inside private spaces use `recordRef`.** Records in personal, circle and group spaces point at each other by type and key (`app.logdate.defs#recordRef`), because they have no public AT URI or CID.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 2026-10-10 | **Encrypted media keeps its real type separately.** `app.logdate.defs#media` stores the blob, which is ciphertext in private spaces, next to the decrypted file's `mimeType`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 2026-10-10 | **No floats.** Measurements are integers with the unit in the field name, confidences are in thousandths, and coordinates are decimal strings, matching the AT Protocol data model and the community location schema.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| 2026-10-18 | **Space kinds are space declarations.** `app.logdate.space.personal`, `circle` and `group` are AT Protocol space declarations instead of tokens, and the group's description record is `app.logdate.space.groupProfile`. Each declaration lists only `app.logdate.crypto.sealed`, because every record in those spaces is sealed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 2026-10-18 | **Sharing apps never get the journal or the keys.** `app.logdate.authShare` grants circle and group spaces only. The personal space, the profile, the sharing keys, deleting shares and running groups stay with `app.logdate.authFull`, LogDate's own apps.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| 2026-10-18 | **Managing spaces only under your own authority.** Both sets grant `manage` only for the person's own spaces, and `app.logdate.authFull` can only create the personal space, never change or delete it. Records in other people's spaces are reached through separate permissions without `manage`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| 2026-10-18 | **Group profiles and members come from the group's authority.** Apps accept `groupProfile` and `member` records only from the repository of the person who started the group.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 2026-10-18 | **The schemas carry everything LogDate syncs today.** Every field of today's synced notes, transcripts, journals, journal links, drafts, marked moments and location history has a place in an `app.logdate.*` record, so switching sync over loses nothing. The field-by-field map is in [How today's synced data becomes records](#how-todays-synced-data-becomes-records).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 2026-10-18 | **One note becomes one single-block entry.** The entry's record key and its block's id are the note's id. Each note has its own time, location and capture details today, so merging notes into one entry would mix them up. Multi-block entries stay supported.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| 2026-10-18 | **Capture details belong to the entry.** `timeZone`, `capturedOn` and `capturedDuring` are entry fields, so the local time is known even without a location. `app.logdate.defs#location` holds only where: coordinates, a place, or both.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| 2026-10-18 | **A place stays private in shared entries by default.** An entry's location can name a saved place and copy its name and center (`placeDetails`), so the location reads correctly without the place record. Places are private only, so a shared copy of an entry leaves out `place` and `placeDetails` unless the person chooses to share the place. Where the place record is available, its current name and center are shown rather than the copy.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| 2026-10-18 | **Media is encrypted with the account's media key.** Blobs are encrypted on the device in 64 KiB chunks with the account-wide media key (`LDCE2`), not with the content key of the record that refers to them. `mimeType` comes from the file when it's uploaded.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 2026-10-18 | **A missing file doesn't lose the block.** `media` is optional on an entry's photo, video and audio blocks. Its absence means no device has the file, such as a note restored from an archive without its media, and the block keeps its caption, length and transcript.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| 2026-10-18 | **Long text is refused, never cut short.** The app doesn't limit an entry's text, captions, journal titles and descriptions, place names and descriptions, or correction labels, so they all share the text block's limits (1,000,000 bytes, 100,000 characters). On any sync write, during the first move or later, a record with a longer value isn't written: the device shows a sync error naming the field, and keeps syncing everything else. Cutting text short would lose the person's words without telling them.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| 2026-10-18 | **Records too large to seal are refused.** Apart from location samples, which are split into parts, a record that's larger than a sealed record can hold (about 3.99 MB) is never trimmed or split. The device doesn't write it, shows a `RecordTooLarge` sync error for that record, keeps it locally, and keeps syncing everything else. The likeliest cases are a transcript of a recording many hours long and a draft or entry with many long blocks.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| 2026-10-18 | **Transcripts keep full detail.** Segment ids, words, speakers, the recognition pass of each segment and the engine are all stored, because a transcript is costly to produce again. Speaker colors aren't: they're a display choice nothing sets. The first recognition pass maps to `inProgress` and the second to `refining`. A transcript is keyed `<entry>:<block>`, so an entry can hold a transcript for each recording.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| 2026-10-18 | **Merged journals leave a redirect.** The merged-away journal stays as a record with `mergedInto`, so every device can move what it still has in that journal, however long it was offline. It's deleted with the surviving journal, and points at the newest survivor when merges chain. In a circle of merges, the journal with the smallest record key survives.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| 2026-10-18 | **Records that two devices may both create have keys built from what they connect.** Memberships are keyed `<journal>:<entry>`, entry links `<entry>:<SHA-256 of the evidence id>` and transcripts `<entry>:<block>`, so the same fact is always one record. They are written create-only (`createOnly`), and an existing record is left as it is, so a membership's optional `createdAt` is the first one written and both devices settle on the same bytes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 2026-10-18 | **One record per place.** A saved place and the location-history place with the same id become one `place.place` with every field from both. The saved place's name, coordinates and radius win, because it changes in place when the person edits it.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| 2026-10-18 | **Marked moments are their own record.** `app.logdate.journal.markedMoment` is private only. A mark isn't an entry: it's left out of Rewinds and the library, and it can hold a heart rate.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| 2026-10-18 | **Stored location history is samples plus what the person did.** Samples and activity changes, places, corrections, entry links and visits the person added are records. Visits and journeys LogDate works out are recomputed on every device, so `app.logdate.place.journey` is reserved and nothing writes it yet. Corrections and entry links name their target with today's evidence ids, and each correction stores its new value in a field of the right type.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| 2026-10-18 | **Hourly location records, one per device.** Raw location samples and activity changes are batched into one `place.sample` record per device per UTC hour, keyed `<UTC hour>:<device>`, instead of one record per day. A sealed record is replaced whole on every change, and a device uploads new samples every few minutes. A day can hold tens of thousands of samples (the foreground stream takes about one a second), so a daily record would be encrypted and uploaded again at several megabytes, many times a day, and could outgrow a sealed record. An hourly record stays small, and an hour that has ended only changes again when the person deletes something. Only the recording device adds samples, so devices don't conflict over new ones. Another device may delete samples, so the first write of a record is create-only (`createOnly`), every later write uses `swapCid`, and a refused write is merged and retried: samples from either version minus deleted ids from either, with deletion lists that only grow. A deletion is listed in the part that held the sample. An hour's lists are capped so a record fits in a sealed record with every field at its longest; a new part, keyed `:2`, `:3`, starts once a part's samples plus deleted ids reach 5,000 (or its transitions plus deleted ids reach 500), so a deletion always fits. Keying by UTC hour means a time zone change or a daylight saving change never puts two hours under one key. |
| 2026-10-18 | **Fractional measurements use smaller units.** Accuracy and radius are in centimeters, speed in centimeters per second and bearing in hundredths of a degree. Every client converts the same way, in 64-bit double arithmetic with floats widened first, so Kotlin and Swift write identical integers: a NaN or infinite value is left out; a bearing is first reduced into 0 to 360° with `fmod`; the value times 100 (1000 for confidences) is rounded to the nearest whole number with halves away from zero; a result beyond 2^53 − 1 is left out rather than saturated. Negative accuracy, speed and radius become 0, a bearing of 36,000 becomes 0, and confidences are kept between 0 and 1000. `tools/lexicon-check/conversion-vectors.json` pins the cases. Sub-centimeter digits are dropped, since they're finer than any location sensor measures.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
