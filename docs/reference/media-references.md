# Media References

> How LogDate names the photos, videos and recordings it keeps, on every platform.

Every photo, video and voice note in LogDate is a file on the device, and every entry that shows one stores a
string pointing at that file. This page describes that string: what it looks like, how each platform turns it
back into a file, and the rules code must follow so media never goes missing or gets deleted by mistake.

## Why not just store the file path?

Earlier builds stored absolute paths such as:

```text
file:///var/mobile/Containers/Data/Application/0A1B2C3D-…/Documents/media/1f2e-IMG_0001.jpg   (iOS)
file:/data/user/0/studio.hypertext.logdate/files/media/objects/sha256/9f/9f86d081….jpg       (Android)
file:///Users/me/.logdate/media/1f2e-IMG_0001.jpg                                            (desktop)
```

An absolute path describes where the operating system put the app's storage _at the moment the file was
written_. That location is not permanent:

- **iOS** gives the app a new container directory, with a new UUID in its path, when a backup is restored to
  another phone, when the app is reinstalled, and after some system updates. The files move with the
  container, but every stored path still names the old one.
- **Android** puts app data under a different directory for another user or work profile
  (`/data/user/10/…`), and older code saw the same directory spelled `/data/data/…`.
- **Desktop** keeps media under the home directory, which changes when an account is renamed or migrated to a
  new computer.

When the location moves, the file is still there, but every entry that pointed at it shows missing media.

## The format

LogDate stores a reference that names the file by its **role in LogDate** and its **path inside that role's
directory**, never by where the operating system keeps that directory:

```text
logdate-media://<collection>/<path>
```

```text
logdate-media://library/1f2e-IMG%200001.jpg
logdate-media://library/objects/sha256/9f/9f86d081….jpg
logdate-media://recordings/recording_9a8b….m4a
```

- **Scheme** is always `logdate-media`. It is separate from `logdate://`, which is the deep-link scheme for
  opening screens (`logdate://journal/<id>`).
- **Collection** (the host part) says what the file is for. See [Collections](#collections).
- **Path** is the file's location inside the collection's directory, using `/` between folders. Each segment
  is percent-encoded the way a URL is: letters, digits and `-._~!$&'()*+,;=:@` are written as they are, and
  everything else (spaces, `%`, `#`, `?`, non-ASCII letters) becomes `%XX` UTF-8 escapes. So
  `IMG 0001 café.jpg` is written `IMG%200001%20caf%C3%A9.jpg`.

A reference never has a query (`?…`) or fragment (`#…`), never has an empty segment, and never contains a
`.` or `..` segment, even an escaped one. Parsers refuse anything else, so a reference can never point
outside its collection.

The scheme and collection are matched without regard to case, as URLs are, but LogDate always writes them in
lower case. The path is case-sensitive.

In code, a reference is a `LocalMediaRef` (in `:client:media`, package `app.logdate.client.media.storage`):

```kotlin
val ref = LocalMediaRef(MediaCollection.Library, "1f2e-IMG 0001.jpg")
ref.toString()                           // "logdate-media://library/1f2e-IMG%200001.jpg"
LocalMediaRef.parse(ref.toString())      // the same LocalMediaRef again
```

### A reference names a file in this install

A `logdate-media://` reference is portable across _locations_, not across _devices_. The same string on two
phones names whatever file each phone keeps at that path, which may differ or not exist. Sync, export and
the watch transfer bytes and keep their own mapping from a local reference to a remote copy; they never
assume another device can open this device's reference.

## Collections

| Collection   | What it holds                                                                                     |
| ------------ | ------------------------------------------------------------------------------------------------- |
| `library`    | Photos and videos LogDate keeps for entries: imported, captured, downloaded by sync, or restored. |
| `recordings` | Voice recordings LogDate captured on this device or received from a paired watch.                 |

Both collections are durable: the operating system must not delete them to free space. Caches (thumbnails,
waveforms, image caches) are never referenced by a stored string, so they have no collection.

Where each platform keeps them:

| Collection   | Android phone and Wear | iOS                                       | Desktop                  |
| ------------ | ---------------------- | ----------------------------------------- | ------------------------ |
| `library`    | `filesDir/media`       | `Documents/media`                         | `~/.logdate/media`       |
| `recordings` | `filesDir/audio_notes` | `Library/Application Support/audio_notes` | `~/.logdate/audio_notes` |

Paths inside a collection are chosen by the platform that wrote the file. Android's library is
content-addressed (`objects/sha256/<first two hex digits>/<sha256>.<ext>`), while iOS and desktop name
files `<uuid>-<original name>`. Readers treat the path as opaque.

## Resolving a reference

Code that needs an actual file asks a `MediaFileResolver`, which every platform provides through Koin:

```kotlin
class AudioDurationReader(private val mediaFiles: MediaFileResolver) {
    fun duration(uri: String): Duration? {
        val path = mediaFiles.filePath(uri) ?: return null   // null: not a local file (Photos, content://, https://)
        return readDuration(path)
    }
}
```

`MediaFileResolver.filePath` accepts any stored string, not only `logdate-media://` references, so readers
call it first and handle `null` as "not a local file":

| Stored string                                        | `filePath` returns                            |
| ---------------------------------------------------- | --------------------------------------------- |
| `logdate-media://library/a.jpg`                      | `<library directory>/a.jpg`                   |
| `file:` URI or absolute path in this install         | that path                                     |
| `file:` URI or absolute path from an earlier install | the same file in this install, if it is there |
| `ph://…`, `content://…`, `https://…`, anything else  | `null`                                        |

`MediaReference.parse(string)` gives the same classification as a value (`Owned`, `LocalFile` or
`External`) for code that needs to branch on the kind of reference.

### The `MediaDirectories` interface

The resolver is shared code. The only platform-specific part is `MediaDirectories`, which each platform
implements with its own storage APIs:

```kotlin
interface MediaDirectories {
    /** Absolute directory holding [collection] in this install, written with `/` separators. */
    fun directory(collection: MediaCollection): String

    /** [path] spelled the way [directory] spells it, with symlinks and aliases resolved. */
    fun canonicalPath(path: String): String = path

    /** Where a file that an earlier install of LogDate wrote at [path] is in this install, or null. */
    fun pathInCurrentInstall(path: String): String? = null
}
```

| Platform | Implementation            | Directories from                | Earlier installs it recognizes                         |
| -------- | ------------------------- | ------------------------------- | ------------------------------------------------------ |
| iOS      | `IosMediaDirectories`     | `NSFileManager.URLForDirectory` | any `…/Containers/Data/Application/<id>/…` container   |
| Android  | `AndroidMediaDirectories` | `Context.filesDir`              | `/data/data/<package>/…`, `/data/user/<n>/<package>/…` |
| Desktop  | `DesktopMediaDirectories` | `user.home`                     | any `<home>/.logdate/…`                                |

Every implementation must pass the shared contract suite, `MediaDirectoriesContract` in `:client:media`
`commonTest`, which each platform's test source set runs against its own implementation. The contract
requires that:

1. every collection maps to an absolute directory written with `/` separators and no trailing slash;
2. those directories are already canonical (`canonicalPath(directory) == directory`);
3. no collection's directory is inside another's, so every file belongs to at most one collection;
4. a reference resolved to a path and stored again gives back the same reference;
5. a path already in this install is never relocated somewhere else.

## Rules for code that stores references

1. **Store the canonical form.** `MediaFileResolver.storedReference(string)` turns any spelling of a file in
   a collection into its `logdate-media://` reference and leaves everything else as it was. Repositories take
   it as the narrow `StoredMediaReferences` interface (which `MediaFileResolver` implements) and call it on
   every media reference they persist, so the database never holds two spellings of one file. The notes
   repository does this on create, on notes arriving from sync, and when a media reference is repaired.
2. **Compare canonical forms.** Before deciding a file is unused (when an entry is deleted or a draft is
   discarded), compare `storedReference` values, never the raw strings. Two spellings of one file must count
   as the same file, or deleting one entry can remove a photo another entry still shows. Sync does the same
   when it decides whether a note's media was already uploaded, so a note whose reference was rewritten is not
   uploaded a second time.
3. **Never invent a reference for a missing file.** A legacy path whose file is not in this install keeps its
   original string, so the entry keeps reporting missing media instead of pointing at the wrong place.
4. **Do not store caches.** A file in a cache directory can disappear at any time. Copy it into the library
   first and store the library reference.

### Reclaiming local originals

On Android, deleting a note or draft does not currently unlink its managed original.
`AndroidMediaManager.deleteOwnedMedia` and `AndroidMediaCleaner` remain disabled because a reference check after
one deletion is not exclusive ownership: Room notes and covers, DataStore drafts, recording checkpoints, and
restore work can acquire a claim concurrently. The native transfer snapshot selects files named by live records,
so an orphaned original is not sent to another device.

The canonical store's publication lock ends when an object is durable, before its caller commits a Room or DataStore
reference. A collector that checks for zero references in that interval could unlink the object immediately before
the new reference is saved. Every producer must therefore hold one cross-process claim fence from the
start of publication through durable claim commit, and a collector must hold it through its final check and unlink.
That final check must read the durable Room and DataStore claims rather than a process-local flow snapshot.

`MediaReclamationJournal` can durably record a possible orphan before a repository releases a claim. Its Android
implementation stores fsynced, idempotent candidates under `noBackupFilesDir`; a candidate is only a request to
recheck ownership, never permission to delete. Production injection stays on `MediaReclamationJournal.None` until
every claim writer shares a cross-store fence and a resumable collector has verified all live references under that
fence. When adding a new media-bearing record, include its claims in that protocol before enabling collection. A
collector must resolve legacy path spellings, refuse files outside managed storage or reached through symlinks,
recheck claims immediately before unlink, and keep a candidate pending if verification or unlink is interrupted.

## Rules for code that reads references

1. **Resolve before opening.** Never strip `file://` by hand or pass a stored string straight to a player,
   decoder or image loader. Call `MediaFileResolver.filePath` (or use the platform's image loader, which does it
   for you through a registered mapper).
2. **Expect every spelling.** Stored strings include `logdate-media://` references, `file:///` and `file:/`
   URIs (encoded or not), bare absolute paths, and external URIs. `filePath` handles all of them.
3. **Verify originals before completing recovery.** Use `media.isRecoveredOriginal(reference, mediaReferences)`.
   File existence, a provider query, or a readable first byte cannot establish completeness. The shared
   check reads bounded chunks, verifies length, and compares canonical objects with the SHA-256 in their
   path. The helper owns the platform IO boundary, including opening and closing the stream;
   callers may invoke it from Main. Keep corruption, late read failure, cancellation, and bounded-buffer cases in
   [the integrity tests](../../client/media/src/commonTest/kotlin/app/logdate/client/media/storage/RecoveredMediaIntegrityTest.kt).
   [The dispatcher test](../../client/media/src/androidHostTest/kotlin/app/logdate/client/media/storage/RecoveredMediaDispatcherTest.kt)
   covers file operations off Main and resumption on the caller's dispatcher.

## Legacy references and migration

References written before this format existed are still read: `filePath` opens them, relocating files from an
earlier install when the file is present.

When the app is in use, `StoredMediaReferenceMigrationLauncher` starts `StoredMediaReferenceMigration` once per
process, in the background. It rewrites image, audio and video notes and journal covers whose stored reference is
still a local file path into `logdate-media://` references when the file is in a collection of this install. The
migration is idempotent and only reads rows that still hold a local file path, so it costs almost nothing once it
has finished. It updates a row only while the row still holds the value it read, and it does not queue a sync
upload, because the media itself has not changed. A transcript is bound to the media reference of its audio note,
so rewriting an audio note's reference rebinds its transcript too. Rows whose file is missing keep their original
string. If the migration fails, it logs the error and tries again the next time the app starts.

The migration rewrites only references that spell a local file path. A `content://` reference to an item in the device
photo library is left as it is: it keeps working while that item exists in the library, and it is not copied into the
app's own collection.

Earlier iOS builds also stored media outside every collection:

- Photos picked from the Photos library were stored as paths in `Library/Caches/photo-library-renderable`, which iOS
  can empty at any time and does not restore from a backup.
- Camera captures and journal cover images were stored in `Documents/imports`, which no reference names.

The migration passes any reference it could not rewrite to a `MediaRescuer`. On iOS, `IosOutOfLibraryMediaRescuer`
moves such a file into the library while it still exists, and a second reference to a file it already moved gets the
same library reference. A photo iOS has already purged from the cache is gone from the device, so its note keeps the
old reference. New picks are copied into the library when they are chosen.

Two kinds of stored string are deliberately left as they were written, because they work through the readers' fallback
for files from an earlier install:

- **Drafts.** A draft is short-lived, and the editor compares the recorder's live file path literally to recognise an
  unfinished recording, so rewriting a draft's paths could break recovery. Media a draft still names is opened through
  `filePath`, and discarding a draft compares references in their canonical spelling.
- **The sync upload cache** (`MediaSyncRef.localUri`). Sync compares it with a note's reference through
  `StoredMediaReferences`, so either spelling counts as the same file.
