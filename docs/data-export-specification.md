# LogDate Data Export

An export is a single `.zip` file that contains a copy of what LogDate stores for a person. This document
explains what is in it, how to read it, and how the app builds it. It describes three layouts:

- **Format 2.1**, the current format written by the shared export pipeline. Readers also accept 2.0.
- **Format 1.x**, the legacy import-only layout. Restore continues to read versions 1.0 through 1.2.
- **Format 3.0**, a variant of the 2.x layout that the native Apple app writes and that Android code can
  write and verify but no export or restore feature uses. See [Format 3.0](#format-30-native-transfer).

If you only want to open an export, read [Reading an archive](#reading-an-archive). If you are
changing what gets exported, read [Adding a new kind of data](#adding-a-new-kind-of-data).

## What an export has to be

An export is the person's data leaving the app, so it has to work without the app. Format 2.x is
designed around these promises:

| Promise                       | What it means in practice                                                                                                                                                                               |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Self-contained**            | Nothing needs the app, an account, the network, or another file. The documentation, the schemas and the checksums are inside the zip, and every link inside it points at a file that is also inside it. |
| **Honest about completeness** | If something is not in the export, the export says so and says why. It never looks complete when it is not.                                                                                             |
| **Readable by a person**      | A README explains the archive in plain language. File names describe themselves.                                                                                                                        |
| **Readable by a program**     | Every data file is JSON with a published schema. Times are RFC 3339 in UTC, ids are UUIDs, text is Markdown.                                                                                            |
| **Free of device internals**  | No content URI, file path, original file name, sync counter or device id is ever written. A file is only ever referred to by its place inside the archive.                                              |
| **Verifiable**                | `SHA256SUMS` lets anyone confirm that no file was damaged.                                                                                                                                              |

## Layout of a 2.x archive

All files sit at the top level of the zip. Readers should also accept an archive that was unzipped and
zipped again inside one folder, as Finder and Explorer do; restore already does this for 1.x.

```
README.txt                  Plain-language guide.
manifest.json               What this archive is and contains. Programs start here.
schema/                     JSON Schemas (draft 2020-12), one per data file.
data/
  journals.json             The journals.
  notes.json                Every entry: text, photos, videos and voice notes.
  drafts.json               Unfinished drafts from the older draft store (see Completeness).
  places.json               Saved places (only when there are any).
  profile.json              The profile (only when one was filled in).
  location-history.jsonl    One location sample per line (only when there are any).
  media.json                Every media file with its type, size and SHA-256.
media/
  photos/2026/2026-09-17_21-30-05.jpg
  videos/…    audio/…       Named for when they were captured, in local time.
SHA256SUMS                  Last file. A checksum for every other file.
```

`README.txt`, `manifest.json` and `SHA256SUMS` are the only fixed names outside `data/`, `schema/` and
`media/`.
The manifest defines a `readableIndex` role for human-readable copies of the journal. The exporter
writes none, so a program must not expect one.

### Why the files are in this order

The media is written first. The README, the manifest, the data files and the media inventory come
after it, and `SHA256SUMS` is written last. Nothing depends on the order to be read, because zip
readers use the central directory at the end of the file, and Finder and Explorer list the top-level
files alphabetically.

The order exists because the README, the manifest and the notes must state exactly which media files
the archive contains. A media file can become unreadable between the moment the export plans it and the
moment it is copied, for example when the person deletes a photo or a cloud provider removes its local
copy. Because the files that describe the archive are written after the copy, that file is reported as
omitted and the export still finishes. A file that fails halfway through being read cannot be taken
back out of the zip, so that case ends the export with an error.

## Reading an archive

### As a person

Open `README.txt`. It says what each folder is, what is missing and why, how times are shown, and how
to check the files. Photos, videos and voice recordings are in `media/`, named for when they were
captured. Everything in `data/` is plain text that any editor opens, but it is written for programs.

### As a program

1. Read `manifest.json`. Check `format` is `logdate-export` and `schemaVersion` starts with `2.`
   (see [Versioning](#versioning)).
2. Use `contents` to find each file. Each entry gives the file's `role`, its `path`, its
   `mediaType`, and the `schema` that describes it. Never guess a name.
3. Validate a file against its schema if you want to. The schemas are permissive about extra keys,
   because a minor version only adds fields.
4. Read `scope` before trusting the data to be everything. See [Completeness](#completeness).

LogDate restore follows the same rule: it detects 2.x through `manifest.json` and resolves every data
file by its manifest role, so valid custom paths are supported. If there is no manifest, restore falls
back to the 1.x `metadata.json` layout. Both formats may be at the ZIP root or inside one wrapper
directory. Every manifest-listed data file must exist before restore starts writing to repositories.

Restore currently applies journals, notes and their memberships, drafts, profile, saved places,
location history and portable media references. Included media is copied through the platform media
importer. An omitted or unavailable original retains its note or draft attachment for later recovery;
a source-device path is never retained as a fallback.

### Conventions in every data file

- **Keys** are `camelCase`. A key that is not in a record means the value is not known; `null` is never
  written.
- **Times** are RFC 3339 in UTC with a trailing `Z`, such as `2026-09-18T03:30:05.123Z`.
- **Ids** are lowercase hyphenated UUIDs.
- **Text** is stored exactly as the person wrote it. `textFormat` says how to read it; today it is
  always `markdown`.
- **Files** are referred to by an archive path: relative, forward slashes, no drive letter or URI
  scheme, no `..`. A path that breaks these rules makes the file unreadable, since an archive is
  untrusted input.
- **Coordinates** are decimal degrees with named `latitude` and `longitude` keys, never a pair whose
  order has to be remembered.

### Notes

`data/notes.json` contains `{"notes": [...]}`. Each note has an `id`, a `type` (`text`, `image`, `video` or
`audio`), `createdAt` and `updatedAt`, and:

- `text` and `textFormat` for text notes, `caption` for media, and `durationMs` for audio or video.
- `locationDetails`, when one was recorded; `location` retains the earlier display projection.
- `journalIds`: the journals the note appears in. A note can be in several journals or in none, so
  membership lives on the note.
- `media`: where the file is. See below.
- `timeZone` and `createdAtLocal`: see below.
- `capturedOn`, the kind of device the note was captured on (`phone` or `watch`), and `capturedDuring`,
  what the person was doing then (`moving` or `in_vehicle`), when they were recorded. Restoring reads a
  value it does not know as not recorded.

**Media.** `media.status` is `included` or `omitted`. An included file has a `path` and a `mediaType`.
An omitted file has an `omittedReason` and no path, so nothing ever points at a file that is not
there. `data/media.json` lists every included file with its size and SHA-256.

**Transcripts.** `transcription` preserves legacy text exactly, independently of `transcript`.
The structured document retains segments, words, speakers, recognition provenance, sound tags,
and source creation/update instants. Importers must not manufacture a finalized document from
legacy text or replace one representation with the other. A known empty media caption or raw audio
transcription is encoded as an empty string; absence in an older archive remains unknown. Text notes
do not acquire a media caption. Unknown video duration stays absent.

**Locations.** In 2.1, `locationDetails.coordinates` retains the measured point and optional altitude
and accuracy. Its independent `place` retains the semantic place UUID, optional name, and optional
center. A known place ID needs neither a name nor coordinates; a center needs both coordinates.
Importers use `locationDetails` when present. They must not substitute the place center for the
measured point, mint a replacement place identity, or turn an unknown point into zero coordinates.
The older `location` field is a display projection for 2.0 readers, which lack this distinction.

Android persists the source location and legacy transcription in the note's database record. A place
reference can survive before its matching saved-place record arrives. Ordinary local references
continue to follow known place renames; archive imports retain known source name/center fields when
the destination already has different metadata for the same UUID. Missing source fields may be
filled from that matching place. Caption, transcript, and media-recovery updates retain this source
preservation behavior, and neither path substitutes semantic coordinates for measured coordinates.

Draft video blocks may also contain an optional `thumbnail` using the same media-reference shape.
The original and preview have independent inclusion status and inventory entries. A readable preview
does not establish that the original video was included.

Restoring an omitted or unreadable original retains its entry or draft block, including captions,
duration, transcription, block order, and journal selections. A missing original remains distinct
from an unfinished draft block with no attachment. Android records unresolved attachments separately
and offers attachment-only recovery from another archive, preserving edits made after import.

**Time zones.** A note stores an instant in UTC. When the device recorded the zone the note was written
in, the note also has `timeZone` (an IANA name) and `createdAtLocal`, the same moment as local time
with its offset. A note that was written before zones were recorded has neither; the export does not
guess. The readable README and any readable copy show such notes in `exportTimeZone`, the zone the
export was made in, which is what the app's timeline shows too.

## Completeness

`manifest.scope` answers whether the archive is everything:

- `complete` is true only when nothing the app stores is left out and no date range was applied.
- `dateRange` is present when the export was limited by date.
- `omitted` lists each missing category with a reason: `notRequested` (the person left it out),
  `outsideDateRange`, `unreadable` (it could not be read while exporting) or `notYetSupported` (this
  version of the app does not export it yet).

The README shows the same list in words. Every category in `ArchiveCoverage.notYetSupported`
(`ArchiveManifestBuilder.kt`) is written to `omitted` with the reason `notYetSupported`, and while any
such category exists `complete` is false, so an export never claims more than it contains. Read that
constant for the current list. It matters for drafts: `drafts.json` is built from the older draft
store, so a draft that a person has open in the entry editor is not in the archive.

`ExportCoverageTest` in `client/database` enforces this from the other side: every Room table must be
classified as exported, planned, derived or bookkeeping, so a new kind of data cannot be added to the
app without deciding what happens to it in the export.

## Versioning

`schemaVersion` is `major.minor`.

- A **minor** change only adds optional fields or new enum values. Readers ignore keys they do not
  know, so an older reader still reads a newer minor version.
- A **major** change renames, removes or retypes something, or changes the layout. A reader that
  supports major `N` refuses major `N + 1` instead of guessing.

Format 2.0 is a major change from 1.x: the files, the field names, the media paths and the way media is
linked all differ. Restore can still read 1.x archives.

## Verifying files

`SHA256SUMS` has one line per file, `<sha256>  <path>`, sorted by path, in the format that
`shasum -a 256 -c` and `sha256sum -c` read:

```bash
shasum -a 256 -c SHA256SUMS
```

It lists every file except itself. It shows that files are unchanged since the export, not who made
them.

Restore admits 2.x metadata through `RestoreArchiveReader.readBytes`, using the original
uncompressed ZIP bytes. It verifies the manifest and each listed metadata document before accepting
a recovery source or writing destination records. Checksum paths, content roles, inventory paths,
JSON object keys, and ZIP entry names must be unambiguous. Included originals and previews must have
matching inventory and checksum records. The text reader is a compatibility adapter for exact UTF-8
documents; platform ZIP readers must use the byte reader.

Media is verified when it is imported. `copyArchiveMedia` streams into private staging and checks the
inventory's exact byte count and SHA-256 before the platform commits an original. Failed staging is
removed. A damaged or absent original leaves the entry recoverable through the existing pending-media
mechanism, with its expected digest retained. A usable local original remains available during repair.
Legacy 1.x archives remain readable without inventing checksum guarantees they do not contain.

Keep admission and persistence tests together: repack changed, parseable metadata with a valid ZIP
CRC and stale SHA-256; mutate an original without changing its length; and include a verified control.
Assert that rejected metadata creates no recovery record or destination writes, rejected media never
reaches durable storage, and valid originals survive the production repository round trip.

## How the app builds an archive

The code is in `client/domain/src/commonMain/kotlin/app/logdate/client/domain/export/archive/`.

`ExportArchiveUseCase` works in two passes.

1. **Read and resolve.** `ArchiveSnapshotReader` reads the app's data once, so the archive describes one
   moment. `MediaResolver` then opens every media file the entries refer to, works out what it is from
   its first bytes, gives it a path, and records whether it is included or why it is not. Because this
   finishes before anything is written, the manifest, the README and every note can state exactly what
   is and is not in the archive.
2. **Write.** `ArchiveWriter` copies the media into an `ArchiveContainer`, then writes the README, the
   manifest, the schemas and the data files. A media file that cannot be opened at this point is dropped
   from the plan and reported as omitted. Every byte passes through a hashing sink into a `HashLedger`,
   so no whole file is loaded into memory, and `SHA256SUMS` and `data/media.json` are both drawn from the
   same ledger and cannot disagree. The work runs on the IO dispatcher because zip writes, hashing and
   media reads block.

The pieces that stop device internals from entering the archive:

- `ArchivePath` is the only way a file is referred to. It cannot contain a URI, an absolute path or `..`.
- The archive's records (`ArchiveNote`, `ArchiveJournal` and so on) are their own types, never the
  app's domain classes, so a change inside the app cannot leak into the archive.
- Media is named by `MediaFileNamer` from its capture time, in the zone it was captured in, filed by
  kind and year. Repeats get `_2`, `_3`. The type comes from `MediaTypeSniffer`, which reads magic
  bytes, because a content URI has no extension and an extension can be wrong.

Each platform supplies two things the archive cannot know: a `MediaSourceOpener`, which opens whatever
reference that platform stored, and an `ArchiveContainer`, which puts files in a zip. The Android worker
and the desktop launcher use `ZipStreamArchiveContainer`. If an export fails or is cancelled, the
launcher deletes the partly written file.

Each platform also decides how an export starts and where the file goes:

- **Android** asks the person for a destination with the system file-save dialog, then enqueues
  `ExportWorker` through WorkManager as a unique, expedited job (`AndroidExportLauncher`). The unique
  job uses `ExistingWorkPolicy.REPLACE`, so starting a second export cancels one that is still running.
  The worker promotes itself to a foreground service of type `dataSync`, which WorkManager's
  `SystemForegroundService` declares in the app's manifest. An export that must outlive the screen
  depends on that declaration.
- **iOS** stages the archive in temporary files and hands the finished ZIP to the system share sheet
  (`UIActivityViewController`).
- **Desktop** shows a save dialog (`java.awt.FileDialog`) and appends `.zip` to a chosen name that
  lacks it.

### Current writing behavior

Format 2.1 is the only format the Export feature and cloud backup write. Android and desktop stream it into a ZIP. The shared iOS client stages
entries in temporary files before assembling and sharing the ZIP, and removes staging data after
success, cancellation or failure. Cloud backup builds the same v2 ZIP, encrypts it on the device and
uploads it with a short format-and-encryption string as its manifest; the archive's own `manifest.json`
stays inside the encrypted payload and is not sent separately
([Archive backup to the server](reference/sync-architecture.md#archive-backup-to-the-server)). Cloud
restore remains format-neutral, so downloaded 1.x and 2.x backups use the shared reader. The separate native Apple client has its own importer, so
passing shared Kotlin tests does not show that it reads an archive.

## Adding a new kind of data

1. Add a read API the export use case can call. Domain code cannot reach DAOs, so data that only a DAO
   exposes needs a repository interface, an implementation in `client/data`, and DI on each platform.
2. Add a record type in `ArchiveData.kt` and a schema for it in `ArchiveSchemas`. The tests validate a
   maximal sample against the schema and against a strict copy, so a field the schema forgets fails.
3. Map it in `ArchiveRecords.kt`, write it in `ExportArchiveUseCase`, and add it to the manifest's
   `contents`.
4. Remove its category from `ArchiveCoverage.notYetSupported` and move its table from planned to
   exported in `ExportCoverageTest`.
5. Add its label to the README template.
6. If media is involved, ask the `MediaResolver` for it instead of writing a path.

## The 1.x layout

Format 1.x is retained for import compatibility only; the app never creates a new 1.x export. Its
files are at the top level:

`metadata.json`, `journals.json`, `notes.json`, `journal_notes.json`, `drafts.json`, and when present
`profile.json`, `places.json`, `location_history.json`, `media_manifest.json` and `export_issues.txt`,
plus a flat `media/` folder named by note id. Its main problems are the reason for 2.0: media is
linked by the raw path the device stored, there is no README or checksum, and the current editor's
drafts are not included.

## Format 3.0: native transfer

Format 3.0 is the layout the native Apple app writes when it carries a journal, and the layout that
Android code can write and verify. The schema, `NativeArchive3Writer`, `NativeArchive3SnapshotWriter`
and the admission reader `NativeArchive3Bundle.read` live in `client/domain`, package
`restore.archive3`. `NativeJournalTransferSnapshot.writeArchive3` in `client/data` can feed the writer
from a pinned snapshot of the Android database, but no feature calls it. The Export feature, cloud
backup and shared restore neither write nor read 3.0: a reader that supports major `2` refuses it, as
[Versioning](#versioning) requires. The tests in `client/domain` read archives the native Apple app
produced, kept as fixtures, so that Android agrees with its exact bytes.

The layout is the 2.x one: `README.txt`, `manifest.json`, `schema/`, `data/` and `media/`, with the
same checksum file and media inventory, and the same media-reference shape. The differences exist so
that the receiving app can keep what the sending device knew:

- The manifest's `schemaVersion` is `3.0`, `exportTimeZone` is `UTC`, and `scope.complete` is always
  true. The writer refuses to produce an archive when a category that holds data cannot be carried,
  instead of listing it as omitted.
- Each note has a `nativePrimaryJournalId` (a journal ID, or `null`) and may carry the sending app's
  own values: `nativeDateSeconds`, `nativeUpdatedAt`, `nativeText`, `nativeMediaReference`,
  `nativeMediaRevision`, `nativeOriginalFileName`, and `nativeSourceMetadataPresent` with its
  `sourceMedia`. A note whose creation time was never known has `createdAt` of `null` and
  `nativeDateSeconds` instead. A draft can carry `nativeEditedText`, edited text keyed by block ID.
- Media is written in sorted order and each file's size and SHA-256 must match what the snapshot
  recorded, so a file that changed while being copied fails the export.
- After writing, the writer reads the staged files back and checks them against the checksums
  (`NativeArchive3Bundle.read`) before the archive is handed over. A failure discards the staging
  directory.

The schemas are embedded in the archive, as in 2.x.

## Checking the implementation

```bash
./gradlew :client:domain:jvmTest --tests 'app.logdate.client.domain.export.archive.*'
./gradlew :client:database:jvmTest --tests 'app.logdate.client.database.ExportCoverageTest'
./gradlew :client:domain:iosSimulatorArm64Test --tests 'app.logdate.client.domain.export.archive.*'
```

`ExportArchiveUseCaseTest` builds an archive from fixtures that contain planted device references and
checks that none reach the output, that every link resolves, that the checksums match the written
bytes, and that cancellation stops the export. `ExportArchiveConformanceTest` validates every file in a
real archive against the schema stored in that same archive.

### Native importer fixtures

`ArchiveInteropFixtureTest` runs the actual shared exporter with deterministic synthetic journals,
shared note membership, nanosecond timestamps, independent coordinates/place identity, raw and
structured transcription, sound tags, and nullable video metadata. It validates the resulting files
against their embedded schemas and checks every checksum. Its PNG and four-second PCM WAV are valid
files; its unavailable video is explicitly marked omitted. The fixture covers archive metadata
compatibility only; it does not move a complete journal between systems.

To give another platform the actual exported bytes, choose a new, absolute output directory whose
parent exists, then run the test locally:

```bash
LOGDATE_ARCHIVE_INTEROP_OUTPUT=/absolute/path/to/new-fixture \
  ./gradlew :client:domain:jvmTest \
  --tests 'app.logdate.client.domain.export.archive.ArchiveInteropFixtureTest' --rerun-tasks
```

The test refuses an existing output directory. Keep the whole output together, resolve files by
manifest role, and validate `SHA256SUMS` before consuming it. Native consumer tests should read these
bytes, compare source metadata after database close/reopen, and retain unresolved attachments.
Do not replace the exporter with hand-authored JSON: a fixture that no real export produced says
nothing about what the exporter writes.
