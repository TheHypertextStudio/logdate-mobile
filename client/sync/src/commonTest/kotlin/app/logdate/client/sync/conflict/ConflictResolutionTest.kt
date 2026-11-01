package app.logdate.client.sync.conflict

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.shared.model.Journal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for conflict resolution strategies.
 */
class ConflictResolutionTest {
    private val resolver = LastWriteWinsResolver<TestEntity>()
    private val journalResolver = JournalConflictResolver()
    private val noteResolver = JournalNoteConflictResolver()

    /**
     * A simple entity used to test conflict resolution logic.
     */
    data class TestEntity(
        val id: String,
        val content: String,
    )

    @Test
    fun `remote wins when remote timestamp is newer`() {
        val local = TestEntity("1", "Local Content")
        val remote = TestEntity("1", "Remote Content")
        val localTimestamp = Instant.fromEpochMilliseconds(1000)
        val remoteTimestamp = Instant.fromEpochMilliseconds(2000)

        val result = resolver.resolve(local, remote, localTimestamp, remoteTimestamp)

        assertTrue(result is ConflictResolution.KeepRemote)
        assertEquals("Remote Content", result.value.content)
    }

    @Test
    fun `local wins when local timestamp is newer`() {
        val local = TestEntity("1", "Local Content")
        val remote = TestEntity("1", "Remote Content")
        val localTimestamp = Instant.fromEpochMilliseconds(2000)
        val remoteTimestamp = Instant.fromEpochMilliseconds(1000)

        val result = resolver.resolve(local, remote, localTimestamp, remoteTimestamp)

        assertTrue(result is ConflictResolution.KeepLocal)
        assertEquals("Local Content", result.value.content)
    }

    @Test
    fun `local wins on equal timestamps`() {
        val local = TestEntity("1", "Local Content")
        val remote = TestEntity("1", "Remote Content")
        val timestamp = Instant.fromEpochMilliseconds(1000)

        val result = resolver.resolve(local, remote, timestamp, timestamp)

        assertTrue(result is ConflictResolution.KeepLocal)
        assertEquals("Local Content", result.value.content)
    }

    @Test
    fun `handles very close timestamps correctly`() {
        val local = TestEntity("1", "Local Content")
        val remote = TestEntity("1", "Remote Content")
        val localTimestamp = Instant.fromEpochMilliseconds(1000)
        val remoteTimestamp = Instant.fromEpochMilliseconds(1001) // 1ms difference

        val result = resolver.resolve(local, remote, localTimestamp, remoteTimestamp)

        assertTrue(result is ConflictResolution.KeepRemote)
    }

    @Test
    fun `handles large timestamp differences`() {
        val local = TestEntity("1", "Local Content")
        val remote = TestEntity("1", "Remote Content")
        val localTimestamp = Instant.fromEpochMilliseconds(0) // Epoch
        val remoteTimestamp = Instant.fromEpochMilliseconds(Long.MAX_VALUE / 2) // Very far in future

        val result = resolver.resolve(local, remote, localTimestamp, remoteTimestamp)

        assertTrue(result is ConflictResolution.KeepRemote)
    }

    @Test
    fun `resolver is stateless across multiple calls`() {
        val entity1 = TestEntity("1", "Entity 1")
        val entity2 = TestEntity("2", "Entity 2")
        val olderTime = Instant.fromEpochMilliseconds(1000)
        val newerTime = Instant.fromEpochMilliseconds(2000)

        // First call: remote wins
        val result1 = resolver.resolve(entity1, entity2, olderTime, newerTime)
        assertTrue(result1 is ConflictResolution.KeepRemote)

        // Second call with reversed timestamps: local wins
        val result2 = resolver.resolve(entity1, entity2, newerTime, olderTime)
        assertTrue(result2 is ConflictResolution.KeepLocal)

        // Resolver should give consistent results regardless of call order
        val result3 = resolver.resolve(entity1, entity2, olderTime, newerTime)
        assertTrue(result3 is ConflictResolution.KeepRemote)
    }

    @Test
    fun `journal resolver merges when only description differs`() {
        val now = Instant.fromEpochMilliseconds(1000)
        val journalId = Uuid.random()
        val local =
            Journal(
                id = journalId,
                title = "Travel Notes",
                description = "",
                isFavorited = false,
                created = now,
                lastUpdated = now,
                syncVersion = 1,
            )
        val remote =
            local.copy(
                description = "Remote update",
                lastUpdated = Instant.fromEpochMilliseconds(2000),
                syncVersion = 2,
            )

        val result = journalResolver.resolve(local, remote, local.lastUpdated, remote.lastUpdated)

        assertTrue(result is ConflictResolution.Merge)
        val merged = result.merged
        assertEquals("Travel Notes", merged.title)
        assertEquals("Remote update", merged.description)
        assertEquals(2, merged.syncVersion)
    }

    @Test
    fun `journal resolver requires manual when title and description conflict`() {
        val now = Instant.fromEpochMilliseconds(1000)
        val journalId = Uuid.random()
        val local =
            Journal(
                id = journalId,
                title = "Local Title",
                description = "Local description",
                isFavorited = false,
                created = now,
                lastUpdated = now,
                syncVersion = 1,
            )
        val remote =
            local.copy(
                title = "Remote Title",
                description = "Remote description",
                lastUpdated = Instant.fromEpochMilliseconds(2000),
                syncVersion = 2,
            )

        val result = journalResolver.resolve(local, remote, local.lastUpdated, remote.lastUpdated)

        assertTrue(result is ConflictResolution.RequiresManualResolution)
    }

    @Test
    fun `the identical complete version never demands review`() {
        val instant = Instant.fromEpochMilliseconds(1000)
        val note = JournalNote.Text(Uuid.random(), instant, instant, "Unchanged writing", syncVersion = 5)
        val result = noteResolver.resolve(note, note.copy(), instant, instant)
        assertTrue(result is ConflictResolution.KeepRemote)
        assertEquals(note, result.value)
    }

    @Test
    fun `identical writing with a later acknowledged revision needs no manual merge`() {
        val instant = Instant.fromEpochMilliseconds(1000)
        val local = JournalNote.Text(Uuid.random(), instant, instant, "Unchanged writing", syncVersion = 5)
        val remote = local.copy(lastUpdated = Instant.fromEpochMilliseconds(2000), syncVersion = 6)
        val result = noteResolver.resolve(local, remote, local.lastUpdated, remote.lastUpdated)
        assertTrue(result is ConflictResolution.KeepRemote)
        assertEquals(remote, result.value)
        assertEquals(local.content, (result.value as JournalNote.Text).content)
    }

    @Test
    fun `identical prose does not erase a genuinely different location`() {
        val instant = Instant.fromEpochMilliseconds(1000)
        val local =
            JournalNote.Text(
                Uuid.random(),
                instant,
                instant,
                "Same prose",
                location = NoteLocation(coordinates = NoteCoordinates(1.0, 2.0)),
            )
        val remote = local.copy(location = NoteLocation(coordinates = NoteCoordinates(3.0, 4.0)))
        val result = noteResolver.resolve(local, remote, instant, instant)
        assertTrue(result is ConflictResolution.RequiresManualResolution)
        assertEquals(local, result.local)
        assertEquals(remote, result.remote)
    }

    @Test
    fun `divergent text requires review without changing either body`() {
        val created = Instant.fromEpochMilliseconds(1000)
        val local =
            JournalNote.Text(
                uid = Uuid.random(),
                creationTimestamp = created,
                lastUpdated = Instant.fromEpochMilliseconds(1500),
                content = "Local content",
            )
        val remote =
            local.copy(
                lastUpdated = Instant.fromEpochMilliseconds(2000),
                content = "Remote content",
                syncVersion = 2,
            )

        val result = noteResolver.resolve(local, remote, local.lastUpdated, remote.lastUpdated)

        assertTrue(result is ConflictResolution.RequiresManualResolution)
        assertEquals(local, result.local)
        assertEquals(remote, result.remote)
        assertEquals("Local content", (result.local as JournalNote.Text).content)
        assertEquals("Remote content", (result.remote as JournalNote.Text).content)
    }

    @Test
    fun `substring overlap does not establish a common edit base`() {
        val instant = Instant.fromEpochMilliseconds(1000)
        val local =
            JournalNote.Text(
                uid = Uuid.random(),
                creationTimestamp = instant,
                lastUpdated = instant,
                content = "A quiet walk beside the river",
            )
        val remote = local.copy(content = "A quiet walk", syncVersion = 2)
        for ((first, second) in listOf(local to remote, remote to local)) {
            val result = noteResolver.resolve(first, second, first.lastUpdated, second.lastUpdated)
            assertTrue(result is ConflictResolution.RequiresManualResolution)
            assertEquals(first, result.local)
            assertEquals(second, result.remote)
        }
    }

    @Test
    fun `authored conflict headings and separators are never stripped`() {
        val instant = Instant.fromEpochMilliseconds(1000)
        val authored = "---\nMerged conflict\nLocal: a fictional narrator\nRemote: another narrator\n---\nA story."
        val local = JournalNote.Text(uid = Uuid.random(), creationTimestamp = instant, lastUpdated = instant, content = authored)
        val equal = noteResolver.resolve(local, local.copy(syncVersion = 2), instant, instant)
        assertTrue(equal is ConflictResolution.KeepRemote)
        assertEquals(authored, (equal.value as JournalNote.Text).content)
        val remote = local.copy(content = "A different ending. \uD83C\uDF0A", syncVersion = 2)
        val changed = noteResolver.resolve(local, remote, instant, instant)
        assertTrue(changed is ConflictResolution.RequiresManualResolution)
        assertEquals(authored, (changed.local as JournalNote.Text).content)
        assertEquals(remote.content, (changed.remote as JournalNote.Text).content)
    }

    @Test
    fun `note resolver requires manual when media refs differ`() {
        val created = Instant.fromEpochMilliseconds(1000)
        val local =
            JournalNote.Image(
                uid = Uuid.random(),
                creationTimestamp = created,
                lastUpdated = Instant.fromEpochMilliseconds(1500),
                mediaRef = "local-media",
            )
        val remote =
            local.copy(
                lastUpdated = Instant.fromEpochMilliseconds(2000),
                mediaRef = "remote-media",
                syncVersion = 2,
            )

        val result = noteResolver.resolve(local, remote, local.lastUpdated, remote.lastUpdated)

        assertTrue(result is ConflictResolution.RequiresManualResolution)
    }
}
