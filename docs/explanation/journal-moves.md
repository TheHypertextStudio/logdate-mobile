# Moving journals into personal spaces

Accounts created before personal spaces keep their journals in legacy storage, where the server can
see each record's kind, ID, timestamps and relationships even though the apps encrypt its text.
Moving a journal puts every record into the account's sealed personal space instead, where the
server stores only envelopes it can't open. This page follows one account through that move, from
the first upload to the day its legacy copy is deleted, and explains why each step works the way
it does. The [journal moves runbook](../runbook/journal-moves.md) covers rehearsing and operating
moves, and the [journal moves reference](../reference/journal-moves.md) lists the settings, calls,
codes and signals involved.

## The device does the work

Suppose Maya has used LogDate for two years on her phone and her laptop. Her journal holds a few
thousand entries, a few hundred photos and recordings, some drafts, a handful of marked moments
and her location history. The server can't convert any of it, because the identity key that seals a
personal space never leaves Maya's devices. The move therefore runs on one of her devices, and the
server's part is limited to keeping both stores consistent while it happens.

Her phone starts the move after an ordinary sync once three things are true: the server offers the
move, the phone has downloaded everything legacy storage holds and has nothing waiting to upload,
and the phone has her identity key. Starting from a complete local copy matters, because that copy is
what gets sealed. A phone that still had entries to download would seal a journal missing them.

The phone first claims the move with the server. Only one device moves an account at a time, so if
Maya's laptop reaches the same point while the phone is working, the laptop reports that another
device is moving the journal and waits. A claim lapses after an hour without word from its device,
so a phone that is lost or reset partway through doesn't block the laptop forever.

## Uploading the journal

The phone builds each record the same way personal-space sync builds it, seals it and writes it to
the personal space. Nothing in legacy storage changes, and Maya keeps writing in the app the whole
time: her new entries go to legacy storage as usual and are caught up later.

The upload saves a checkpoint on the phone every 25 records. If Maya closes the app or loses her
connection, the next sync resumes from the checkpoint instead of starting over. Rewriting a record
that is already there is harmless, because every write replaces the record whole.

Media files take one of two paths. Most of Maya's photos were encrypted by an app before they were
uploaded, so the bytes in storage are already sealed with her media key. For those, the phone asks
the server to copy the stored object into the personal space. The server checks that the file
belongs to Maya and that the bytes it copied hash to the identifier it records, and nothing is
uploaded again. A few of her oldest photos were encrypted by the server instead, in a format whose
key the server holds. The server refuses to copy those, and the phone downloads each one, encrypts
it with Maya's media key and uploads it. A file that isn't on the phone at all, such as a photo she
took on her laptop and never opened on her phone, comes from its legacy copy the same way.

## Checking the copy

Before the account switches, the phone proves that the personal space holds exactly Maya's journal.
It does this in three comparisons, and any difference stops the move with a specific problem.

The first comparison is between the personal space and the phone. The phone downloads every record
it wrote, opens each envelope and compares its content hash with the record it would write now.
A record that differs or is missing is written again, and the phone reads the space back once more.
Media gets the same treatment: the phone downloads each file it moved, decrypts it and compares it
with the file on the phone where it has one. If a difference survives three rounds of repair, the
move stops and Settings says which kind of data didn't match and how many items.

The second comparison is between legacy storage and the phone. Here the phone has a problem: Maya
may write an entry on her laptop while the check runs, and legacy storage would then hold an entry
the phone never sealed. To rule that out, the phone asks the server to hold legacy writes. From
then on, any app that tries to write to legacy storage gets a `503` with `Retry-After` and keeps its
change queued; reads keep working. The hold starts counting after two minutes, longer than any
request can run, so that a write which passed its checks just before the hold has finished. The
phone then downloads anything new and compares, kind by kind, the IDs legacy storage holds with the
IDs on the phone. A change the phone has queued but not uploaded counts as expected rather than as a
difference, because it goes to the personal space after the switch.

The third comparison is the switch itself. The phone sends the server what it checked: a watermark
and counts for legacy storage, and the personal space's last sequence number, record count and a
digest of every record key with its content identifier. The server reads both stores again and
compares. If either changed, it refuses, and the phone checks again. If both match, the server
changes the account's storage mode from `legacy` to `personalSpaceV1` and records the switch in the
same database transaction, using a compare-and-set so that two requests can't both switch.

A hold that is never released ends on its own after 30 minutes, so a phone that dies during the
check can't stop Maya's other devices from writing for longer than that.

## After the switch

When the switch succeeds, the phone settles onto the personal space. It records how far into the
space its own upload went, so it doesn't download its own records back, and it resets the sync
versions it kept for legacy storage, which count differently from the space's sequence numbers.
Changes Maya made during the move are still queued, and they now upload to the personal space.

Maya's laptop learns about the switch the next time it syncs, because every sync starts by reading
the account. It settles onto the personal space the same way and downloads what the phone wrote.

An app version that doesn't know about personal spaces at all keeps calling the legacy routes. The
server answers each of them with `403 SYNC_FORMAT_UPGRADE_REQUIRED`. A `409` would have read more
naturally, but released apps treat a `409` on an upload as a conflict, file the change for review
and stop sending it. A `403` leaves the change queued, so it uploads once Maya updates that app. A
current app with personal spaces turned off reads the storage mode before it syncs, pauses sync and
tells Maya to update LogDate, with an Update button where the app can update itself.

## Switching back

For 30 days the server keeps the legacy copy exactly as it was at the switch, and Maya can switch
back from Settings. Switching back mirrors the move. Her phone asks the server to hold writes to
the personal space, downloads everything in it and queues every change since the switch for
legacy storage. The server then moves the storage mode back with the same compare-and-set, refusing
if the personal space changed after the phone downloaded it. The phone uploads the queued changes to
legacy storage, and Maya's other devices follow on their next sync.

## Deleting the legacy copy

Thirty days after the switch, the server's maintenance job deletes the legacy copy. It first marks
the move as being cleaned, which closes the window for switching back, and then deletes the media
objects, the legacy sync index, location history, media and blob records, [journal
merge](../feature-design/journal-merge.md) records and the account's AT Protocol repository, so the account starts again with an empty public repository. No
relay or outside app has a copy of the old repository, because no account's DID has been published
yet, so starting it again loses nothing anyone else depends on.

The job only deletes a copy when the account is still in its personal space. If it finds a move
marked as switched whose account isn't, it leaves the copy in place and logs that it did. A run that
fails partway leaves the move marked as being cleaned, and the next run finishes it.
