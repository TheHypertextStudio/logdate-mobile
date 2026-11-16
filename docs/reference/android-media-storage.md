# Android Media Storage

Where the Android app keeps the photos, videos and recordings that entries show, and how they appear in the
device's photo library. Entries name these files with portable `logdate-media://` references, described in
[Media References](./media-references.md).

## Where files live

- **Photos and videos** that LogDate saves (`saveMedia`, `saveMediaFromFile`) go into a private,
  content-addressed store under `filesDir/media/objects/sha256/<first two hex digits>/<sha256>.<extension>`.
  Two entries that hold identical bytes share one file. An entry stores it as
  `logdate-media://library/objects/sha256/...`.
- **Voice recordings** are written to `filesDir/audio_notes` and stored as `logdate-media://recordings/<file>`.
- **Unsupported media types** are not stored; the manager refuses them rather than writing a file nothing can open.

## How a file is written

Every photo, video and restored audio file reaches the private store through `AndroidCanonicalMediaStore`. The
store is built so that a reference to a stored file never points at bytes that are incomplete, replaced or still
owned by something outside the app. The steps, in order:

1. **Choose the extension from the MIME type.** The caller's MIME type is lower-cased and stripped of parameters,
   then mapped to an extension from a fixed table. An unsupported type is refused with an error. The source's file
   name is never used, so a stored name cannot carry an untrusted path or a misleading extension.
2. **Stream to a staging file.** The bytes are copied once into `filesDir/media/staging/<uuid>.tmp` while a SHA-256
   digest is computed, and the file is flushed to disk. If the number of bytes copied differs from the size the
   caller declared, the write fails before anything is published.
3. **Take the publication locks.** A process-wide mutex and a file lock on `media/staging/.publication.lock` are held
   together, so two processes that write identical bytes serialize on one destination. When the store is built with
   publication tickets, as the app's dependency injection does, the shared claim fence is held as well; a store built
   without tickets takes only the mutex and the file lock. Only this short section is serialized; the copy in step 2 is not.
4. **Publish under the digest name.** The destination is `<digest>.<extension>` in the shard directory. If a file is
   already there and is a regular file (not a symlink) whose length and digest both match, it is reused, which is how
   identical bytes share one object. If the file is there but does not match, it is left untouched and the write moves
   on to `<digest>.retained-<n>.<extension>`. If no file is there, the staged bytes are copied into the destination with
   an exclusive create, flushed to disk, and checked for length.
5. **Flush the directory.** The parent directory is synced so the new entry survives a power loss. Only then does the
   write return, and the staging file is deleted whether or not the write succeeded.

The final step copies instead of renaming or hard-linking. Android denies hard links to app-private files, and a rename
would silently replace a file that another entry still references, whereas an exclusive create can never replace one.
That choice gives the guarantees the rest of the app relies on:

- A completed write has its bytes and its directory entry on disk before the caller receives a reference.
- An existing object is never overwritten, and a file that fails verification is never reused or deleted by a later
  write. A crash during step 4 can leave a partial file at the digest path; later writes simply publish beside it.
- A crash before step 4 leaves only a staging file. Nothing removes staging files automatically, because the age of a
  file cannot show that its writer is gone: Android can suspend a process in the middle of a copy.
- Cancellation stops the copy and removes the staging file, and the cancellation is rethrown.

### Imports from the photo picker

Photo Picker grants last only a short time, so an import never keeps the picker's URI. `AndroidManagedMediaImportSource`
first copies the content URI into a flushed file under `filesDir/managed_media_imports` (the class defaults to `cacheDir`,
but the app wires it to `filesDir` so the system cannot evict a staged copy mid-import), then `ManagedMediaImporter`
hands that file to `MediaManager.saveMediaFromFile`. The importer refuses a result that equals the source URI. `import` always discards the staged
copy when it finishes, whether or not it succeeded. `importAndClaim` discards it only after the destination has claimed the
reference; if the claim is refused or the save throws, the staged copy stays in custody. Camera capture follows the
same shape: CameraX writes into `filesDir/camera-captures`, and the finished file is saved through `saveMediaFromFile`
without creating a `MediaStore` row.

Between the write and the moment a note, draft or cover stores the reference, the object has no owner. The importer
records that interval in a ticket ledger under `noBackupFilesDir/media-reclamation/tickets`: it reserves a ticket before
saving, attaches the returned reference, and acknowledges the ticket once the destination has durably claimed the
reference. The staged source stays in custody until the acknowledgement. Each ticket is a checksummed record written with
an atomic rename.

## What the store guarantees

- Media saved or imported now is always stored as a private file reference, never as a picker or provider URI. Entries
  saved by older builds may still hold `content://` references, which keep working.
- Saving, importing and restoring never insert into `MediaStore`. Publishing to the device library happens only in
  `addToDefaultCollection` and in the one-time backfill described below.
- Original `content://`, raw-path, `audio_notes`, `user_media` and earlier `file://` references stay readable.
  `MediaManager.exists` accepts all of them, including a bare name that resolves under `media/` or `user_media/`.
- Reading, playing, sharing, exporting and uploading a stored file need no network.
- Sharing converts a private file reference into a `FileProvider` URI (`<applicationId>.provider`) before it leaves the
  app; `content://` references pass through unchanged.

## Deletion and retention

A URI returned by a managed import is not an exclusive deletion right: an identical original may
already be used by another entry, editor draft, or journal cover. If an import aborts, discard its
unique staging file but retain the canonical object. Explicit note deletion checks the remaining
notes, drafts (including pending recordings), and covers. Draft removal deletes its record before
considering cleanup. On Android, neither note deletion nor draft cleanup physically unlinks
persisted photos, videos, or recordings: another writer can claim the same file during a reference
check. Nothing reclaims unreferenced objects, and any code that does must hold the claim fence from the start of
publication through the durable claim commit, then re-check the durable Room and DataStore claims, not a process-local
snapshot, before unlinking. The user's original photo-library items are never deletion candidates.

Objects from aborted imports and deleted notes therefore stay on the device. The
backup rules keep the live media roots out of cloud backup and out of the default device-transfer traversal; during a
direct device transfer the native backup agent sends only the originals that live records name, so an orphaned original
is not copied to another phone (see [Native Android backup](./native-android-backup.md)). Cloud backup excludes original
media. Do not promise physical erasure or bounded local retention based only on live entries.

## The device photo library

- `addToDefaultCollection` publishes an entry's image or video to the system `MediaStore` under `Pictures/LogDate`
  or `Movies/LogDate`, so it appears in the device library and the editor picker.
- `getRecentMedia` and `queryMediaByDate` query `MediaStore` directly.
- Files that older builds kept in `filesDir/user_media` are published to `MediaStore` the first time the library
  is read. The backfill is idempotent, does not delete `user_media`, and uses `DATE_TAKEN` when it is available
  and `DATE_ADDED` for older rows.
- Existing `content://` and legacy file URIs still open.

## Validation

The Android regression tests for this behavior are
`app/android-main/src/androidTest/kotlin/app/logdate/client/media/AndroidMediaManagerTest.kt` and
`app/android-main/src/androidTest/kotlin/app/logdate/client/e2e/PortableMediaReferencesE2ETest.kt`. Run them on a
Gradle Managed Device, never on a connected physical device:

```bash
./gradlew :app:android-main:smokeDevicesGroupDebugAndroidTest -Plogdate.androidTestClass=app.logdate.client.media.AndroidMediaManagerTest
```
