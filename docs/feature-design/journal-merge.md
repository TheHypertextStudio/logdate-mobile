# Journal merge

Merging a journal into another journal combines them into one. The journal being merged is the
**source** and the journal that keeps existing is the **destination**. Afterwards the destination
holds every entry that was in either journal, and the source is gone. Nothing is deleted: entries and
media stay, the destination's title, cover and other details do not change, and no one's access
changes. A merge cannot be undone.

A merge works offline. The device records it locally at once and sends it to the server when it can,
so a person can merge on a plane and the result reaches their other devices later.

## What the person does

1. From a journal's detail screen, the person chooses **Merge into…**.
2. They search a list of their other journals, newest first, each with its entry count, and pick the
   one to keep.
3. A review shows both journals, how many entries the source has, how many appear in both, and how
   many the destination will hold. The person confirms with **Merge journals**.
4. The app shows the result and opens the destination.

If something goes wrong the review says so and keeps the person where they are. A failure leaves
everything unchanged. Failure feedback stays inside the visible area (the review scrolls it into view, including at 200% text) and is announced politely to assistive technology. If either journal changed since the review was drawn, the review redraws with
the new counts and asks for confirmation again, with the changed counts shown straight away in the review summaries. If a journal is no longer available, the person
chooses another destination.

The action appears only when the `JOURNAL_MERGE` flag is on (key `journal_merge_enabled`, on by
default) and the server the app is configured for advertises the `journalMergeV1` protocol feature in
its descriptor. The descriptor must belong to the configured server, so switching servers never
borrows another server's answer (`journalMergeAvailability`).

## Memberships, not entries

A journal's contents are a set of membership links between the journal and entry identifiers. A merge
moves those links. It does not copy entries. This matters because a device may hold a link to an
entry it has not downloaded, and the entry must still end up in the destination. The review's counts
and the merge itself both use the raw set of identifiers, whether or not the entry is on this device.

Entries are never edited by a merge. Overlap is harmless: an entry in both journals is simply in the
destination once.

## How a device records a merge

`MergeJournalsUseCase` runs the workflow: it pauses sync, asks the journal repository to commit the
merge, and starts a sync afterwards. A failure to upload never turns a committed local merge into a
local failure; the merge stays queued and waits for sync.

The commit itself is `OfflineFirstJournalRepository.merge`, and it happens in one database
transaction, so a crash leaves either the whole merge or none of it:

1. It reads the journals again and compares them with the preview the person confirmed. If the
   identifiers or either journal differ, it returns the new preview (`ReviewChanged`) instead of
   merging. A stale preview can never authorize a different set of entries than the person saw.
   It also refuses a source that is the destination, or a journal that was already merged away.
2. It adds the source's links to the destination.
3. It stores a row in `journal_merges` that serves two purposes: a **redirect** from the source's ID
   to the destination's ID, and a pending **operation** holding the operation ID, the identifiers
   being moved, and the titles for display.
4. It queues the upload, discards any not-yet-sent changes for the source journal and its links, and
   deletes the source journal.
5. It checks that the signed-in account and server are still the ones the merge began under, and
   rolls back if they are not.

Every row belongs to an account and a server (`ownerId` and `serverOrigin`), and so does the upload
queue entry. A device that signs in to another account, or points at another server, never uploads a
merge that belongs to the first one.

### Redirects keep old references working

The redirect row stays after the merge, and `resolveJournalId` follows a chain of redirects to the
surviving journal (a cycle is an error). Everything that might still hold the old ID resolves it:
opening a link to the removed journal shows the destination, adding an entry to the removed journal
adds it to the destination, drafts that selected the removed journal save to the destination, and
uploads and downloads aimed at it are retargeted.

### Retrying safely

The operation ID is chosen once per confirmation and saved with the screen's state, so tapping
confirm twice, or confirming again after the app was killed, repeats the same operation instead of
making a second one. A repeated request with a known operation ID returns the stored result, and
reusing an ID for a different source or destination is an error. The server repeats the same check.
A response that is lost on the way back therefore cannot duplicate or reverse a merge.

## Sending it

How a merge reaches other devices depends on where the account's data lives.

**Server accounts.** `JournalMergeUploader` sends one request per pending merge to
`POST /journals/{sourceId}/merge` with the operation ID, the destination ID and the sorted list of
identifiers. It sends merges in dependency order: if journal A was merged into B and B into C while
offline, B goes into C first, so an entirely offline chain needs only the last destination to exist
on the server. The response must echo the operation and source, and the device then applies the
redirect and settles the operation. The server answers `MERGE_DESTINATION_MISSING` when the
destination was never uploaded or has since been deleted. The device then marks the operation as
needing a new destination. The account menu and sync settings then show a recovery item, and the
person picks another journal to keep. Choosing one retargets the same operation
under a new operation ID, remembers the old one so lookups by either find it, and moves the held
identifiers to the new destination. A linked watch cannot merge journals (`DEVICE_SCOPE_DENIED`).

**Personal spaces.** The device writes the merged-away journal's own record again with `mergedInto`
naming the survivor, and every device that reads it applies the same merge. The server's merge route
is not used. [Personal-space sync](../reference/personal-space-sync.md) and the
[lexicon reference](../reference/lexicons.md) describe the record.

**Other devices.** A device that did not merge learns about it from the journal change feed, where
the source's deletion carries `mergedIntoJournalId`. It applies the redirect: any link it still holds
in the source moves to the destination, and the source is removed. A device that was offline for a
long time therefore keeps its entries.

## What the server guarantees

`JournalMergeService` coordinates the work and `MergeAwareLogDateCollectionsRepository` wraps the
collections repository. Operations persist per account in `journal_merge_operations` (server
migration V37). Everything for one account runs under an account lock, so a merge never interleaves
with another write to the same journals.

- **The full set is saved first.** Before any canonical write, the server stores the complete union:
  the source's memberships on the server, the identifiers the device sent, and the identifiers of any
  earlier operation it replaces. The merge then adds missing memberships to the destination, removes
  the source's memberships, deletes the source journal with a timestamp later than anything recorded,
  and marks the operation complete with the tombstone's version. Each step is saved, so an interrupted
  merge resumes where it stopped. Before it retires a source, it finishes any earlier
  merge that is still moving entries into that source, so those entries follow it to the survivor.
- **Retries are exact.** The same operation ID with the same request returns the completed result. The
  same ID with a different request returns `MERGE_CONFLICT`. A source that already has another
  operation that never started is replaced by the new one. A competing operation that started
  returns `MERGE_CONFLICT` when it completed or its destination journal still exists; otherwise the
  new operation replaces it and the replaced ID is refused from then on.
  Merging a journal into itself, directly or through a chain, is also `MERGE_CONFLICT`.
- **Late writes land in the survivor.** A device that has not heard about the merge may keep writing
  to the source. Memberships it adds are redirected to the destination, and if the merge has not
  completed they are added to the operation's identifiers so they cannot be lost. Memberships it
  removes from the source are ignored. Saving the source journal's metadata is rejected with
  `JOURNAL_MERGED`, and the error's `details.destinationId` names the survivor. Reads and the change
  feed hide the source except for its deletion with `mergedIntoJournalId`.
- **Tombstones age out.** The maintenance job that purges other deletion tombstones also clears a
  merge's tombstone version once it is old enough. The operation stays, so the redirect still
  resolves. When a legacy copy of an account is deleted after a [journal move](../explanation/journal-moves.md),
  its merge records are deleted with it.

## Export

Merge redirects and upload intents are bookkeeping (the `journal_merges` table in
`ExportCoverageTest`). The archive exports the canonical journals and their memberships, which already
reflect every merge.

## Verifying a deployment

The Cloud Run deployment workflow runs `scripts/journal-merge-smoke.py` with the deployment's
disposable account. Before traffic moves to a new revision it merges two fixture journals, retries
the same operation, and checks the result. After promotion it repeats the same operation against the
live service and checks again. The checks are that the destination's details are unchanged, the source
is gone, its tombstone names the destination, and both the identifier the server held and the
identifier the device submitted are in the destination. An account in a personal space is checked
through its records instead.

Deploy the server before a client that offers merging. The capability check above keeps the action
hidden until the configured server advertises `journalMergeV1`.
