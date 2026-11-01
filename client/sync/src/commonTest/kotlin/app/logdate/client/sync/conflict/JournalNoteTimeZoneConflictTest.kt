package app.logdate.client.sync.conflict

import app.logdate.client.repository.journals.JournalNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A note's capture time zone is written once, on the device that captured it. A copy that lacks it
 * (an older app version, or a server that predates the field) must never erase it.
 */
class JournalNoteTimeZoneConflictTest {
    private val resolver = JournalNoteConflictResolver()
    private val noteId = Uuid.random()
    private val created = Instant.fromEpochMilliseconds(1_710_000_000_000)
    private val later = Instant.fromEpochMilliseconds(1_710_000_100_000)

    private fun text(
        content: String,
        timeZoneId: String?,
    ) = JournalNote.Text(
        uid = noteId,
        creationTimestamp = created,
        lastUpdated = created,
        content = content,
        timeZoneId = timeZoneId,
    )

    private fun resolve(
        local: JournalNote,
        remote: JournalNote,
    ) = resolver.resolve(local, remote, created, later)

    @Test
    fun aRemoteCopyWithoutAZoneKeepsTheLocalZoneWhenTheContentMatches() {
        val resolution = resolve(text("Walk", "America/Denver"), text("Walk", null))

        val kept = assertIs<ConflictResolution.KeepRemote<JournalNote>>(resolution)
        assertEquals("America/Denver", kept.value.timeZoneId)
    }

    @Test
    fun differentTextRemainsSeparateWhenTheRemoteCopyHasNoZone() {
        val resolution = resolve(text("Walk", "America/Denver"), text("Walk by the river", null))

        val review = assertIs<ConflictResolution.RequiresManualResolution<JournalNote>>(resolution)
        assertEquals("Walk", (review.local as JournalNote.Text).content)
        assertEquals("Walk by the river", (review.remote as JournalNote.Text).content)
        assertEquals("America/Denver", review.local.timeZoneId)
        assertEquals(null, review.remote.timeZoneId)
    }

    @Test
    fun differentTextRemainsSeparateWhenTheLocalCopyHasNoZone() {
        val resolution = resolve(text("Walk by the river", null), text("Walk", "Europe/Paris"))

        val review = assertIs<ConflictResolution.RequiresManualResolution<JournalNote>>(resolution)
        assertEquals("Walk by the river", (review.local as JournalNote.Text).content)
        assertEquals(null, review.local.timeZoneId)
        assertEquals("Europe/Paris", review.remote.timeZoneId)
    }

    @Test
    fun divergentCopiesKeepTheirOwnCaptureZonesForReview() {
        val withLocalZone = resolve(text("Morning", "America/Denver"), text("Evening", null))
        val withRemoteZone = resolve(text("Morning", null), text("Evening", "Asia/Kolkata"))

        assertEquals("America/Denver", assertIs<ConflictResolution.RequiresManualResolution<JournalNote>>(withLocalZone).local.timeZoneId)
        assertEquals("Asia/Kolkata", assertIs<ConflictResolution.RequiresManualResolution<JournalNote>>(withRemoteZone).remote.timeZoneId)
    }

    @Test
    fun aMediaNoteKeepsItsLocalZoneWhenTheRemoteCopyHasNone() {
        val local =
            JournalNote.Image(
                uid = noteId,
                creationTimestamp = created,
                lastUpdated = created,
                mediaRef = "media/a.jpg",
                timeZoneId = "Pacific/Auckland",
            )
        val remote = local.copy(timeZoneId = null)

        val kept = assertIs<ConflictResolution.KeepRemote<JournalNote>>(resolve(local, remote))
        assertEquals("Pacific/Auckland", kept.value.timeZoneId)
    }
}
