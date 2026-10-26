package app.logdate.server.sync

import app.logdate.shared.model.sync.DeviceId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [InMemorySyncRepository], providing a lightweight validation of the
 * sync repository's core logic without requiring a database.
 *
 * It ensures that the repository correctly handles user isolation, CRUD operations,
 * pagination of change sets, and the management of tombstone markers for deletions.
 */
class InMemorySyncRepositoryTest {
    @Test
    fun `older clients preserve same media transcript and clear it for replaced audio or type`() {
        val repository = InMemorySyncRepository()
        val owner = UUID.randomUUID()
        val audio = ContentRecord("voice", "AUDIO", null, "audio.m4a", 10, 1, 2, 0, DeviceId("device"), transcript = "LDSE2:opaque")
        repository.upsertContent(owner, audio)
        assertEquals("LDSE2:opaque", repository.upsertContent(owner, audio.copy(transcript = null, lastUpdated = 3)).transcript)
        assertNull(repository.upsertContent(owner, audio.copy(transcript = null, durationMs = 20)).transcript)
        repository.upsertContent(owner, audio)
        assertNull(repository.upsertContent(owner, audio.copy(transcript = null, mediaUri = "replacement.m4a")).transcript)
        assertNull(repository.getContent(owner, audio.id)?.transcript)
        repository.upsertContent(owner, audio)
        assertNull(repository.upsertContent(owner, audio.copy(transcript = null, type = "VIDEO")).transcript)
    }

    @Test
    fun `repository enforces user isolation and supports CRUD flows`() {
        val repository = InMemorySyncRepository()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()

        val contentA =
            repository.upsertContent(
                userA,
                ContentRecord(
                    id = "c1",
                    type = "TEXT",
                    content = "hello",
                    mediaUri = null,
                    durationMs = 0,
                    createdAt = 1,
                    lastUpdated = 1,
                    serverVersion = 0,
                    deviceId = DeviceId("dev-a"),
                ),
            )
        assertTrue(contentA.serverVersion > 0)
        assertNotNull(repository.getContent(userA, "c1"))
        assertNull(repository.getContent(userB, "c1"))

        repository.deleteContent(userA, "c1", deletedAt = 10)
        val contentChanges = repository.contentChanges(userA, since = 0, limit = 1)
        assertEquals(0, contentChanges.changes.size)
        assertEquals(1, contentChanges.deletions.size)

        val journal =
            repository.upsertJournal(
                userA,
                JournalRecord(
                    id = "j1",
                    title = "Title",
                    description = "Desc",
                    createdAt = 2,
                    lastUpdated = 2,
                    serverVersion = 0,
                    deviceId = DeviceId("dev-a"),
                ),
            )
        assertTrue(journal.serverVersion > 0)
        assertNotNull(repository.getJournal(userA, "j1"))

        repository.deleteJournal(userA, "j1", deletedAt = 20)
        val journalChanges = repository.journalChanges(userA, since = 0, limit = 10)
        assertEquals(0, journalChanges.changes.size)
        assertEquals(1, journalChanges.deletions.size)

        val association =
            AssociationRecord(
                journalId = "j1",
                contentId = "c1",
                createdAt = 3,
                serverVersion = 0,
                deviceId = DeviceId("dev-a"),
            )
        repository.upsertAssociations(userA, listOf(association))
        val associationChanges = repository.associationChanges(userA, since = 0, limit = 10)
        assertEquals(1, associationChanges.changes.size)

        val key = AssociationKey("j1", "c1")
        repository.deleteAssociations(userA, listOf(key), deletedAt = 30)
        val associationAfterDelete = repository.associationChanges(userA, since = 0, limit = 10)
        assertEquals(0, associationAfterDelete.changes.size)
        assertEquals(1, associationAfterDelete.deletions.size)

        val media =
            repository.upsertMedia(
                userA,
                MediaRecord(
                    mediaId = "",
                    contentId = "c2",
                    userId = userA,
                    fileName = "photo.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 4,
                    data = byteArrayOf(1, 2, 3, 4),
                    createdAt = 4,
                    serverVersion = 0,
                    deviceId = DeviceId("dev-a"),
                ),
            )
        assertTrue(media.mediaId.isNotBlank())
        assertNotNull(repository.getMedia(userA, media.mediaId))
        repository.deleteMedia(userA, media.mediaId, deletedAt = 40)
        assertNull(repository.getMedia(userA, media.mediaId))

        val backupId = UUID.randomUUID()
        val backup =
            BackupRecord(
                id = backupId,
                userId = userA,
                deviceId = "dev-a",
                manifest = "{}",
                storagePath = "users/$userA/backups/$backupId.enc",
                createdAt = 50,
                sizeBytes = 100,
            )
        repository.createBackupRecord(userA, backup)
        assertNotNull(repository.getBackupRecord(userA, backupId))
        assertEquals(1, repository.listBackups(userA).size)
        repository.deleteBackup(userA, backupId)
        assertNull(repository.getBackupRecord(userA, backupId))

        val statusA = repository.status(userA)
        val statusB = repository.status(userB)
        assertTrue(statusA.lastTimestamp > 0)
        assertEquals(0, statusB.contentCount)
        assertEquals(0, statusB.journalCount)
        assertEquals(0, statusB.associationCount)
    }

    @Test
    fun `changes pagination and tombstone purge return expected metadata`() {
        val repository = InMemorySyncRepository()
        val userId = UUID.randomUUID()

        repeat(3) { idx ->
            repository.upsertContent(
                userId,
                ContentRecord(
                    id = "n$idx",
                    type = "TEXT",
                    content = "v$idx",
                    mediaUri = null,
                    durationMs = 0,
                    createdAt = idx.toLong(),
                    lastUpdated = idx.toLong(),
                    serverVersion = 0,
                    deviceId = DeviceId("dev"),
                ),
            )
        }
        val limited = repository.contentChanges(userId, since = 0, limit = 2)
        assertEquals(2, limited.changes.size)
        assertTrue(limited.hasMore)

        repository.deleteContent(userId, "n0", deletedAt = 100)
        repository.deleteJournal(userId, "missing-journal", deletedAt = 101)
        repository.deleteAssociations(userId, listOf(AssociationKey("j", "c")), deletedAt = 102)

        val perUserPurge = repository.purgeTombstones(userId, olderThan = 200)
        assertEquals(0, perUserPurge.contentPurged)
        assertEquals(0, perUserPurge.journalPurged)
        assertEquals(0, perUserPurge.associationPurged)

        repository.deleteContent(userId, "n1", deletedAt = 300)
        repository.deleteJournal(userId, "j2", deletedAt = 301)
        repository.deleteAssociations(userId, listOf(AssociationKey("j2", "c2")), deletedAt = 302)

        val globalPurge = repository.purgeTombstonesOlderThan(400)
        assertEquals(0, globalPurge.contentPurged)
        assertEquals(0, globalPurge.journalPurged)
        assertEquals(0, globalPurge.associationPurged)
        assertEquals(0, globalPurge.mediaPurged)
    }

    @Test
    fun `sorting comparators are exercised for content journals associations and backups`() {
        val repository = InMemorySyncRepository()
        val userId = UUID.randomUUID()

        repository.upsertContent(
            userId,
            ContentRecord(
                id = "c1",
                type = "TEXT",
                content = "1",
                mediaUri = null,
                durationMs = 0,
                createdAt = 1,
                lastUpdated = 1,
                serverVersion = 0,
                deviceId = DeviceId("dev"),
            ),
        )
        repository.upsertContent(
            userId,
            ContentRecord(
                id = "c2",
                type = "TEXT",
                content = "2",
                mediaUri = null,
                durationMs = 0,
                createdAt = 2,
                lastUpdated = 2,
                serverVersion = 0,
                deviceId = DeviceId("dev"),
            ),
        )
        repository.deleteContent(userId, "c1", deletedAt = 10)
        repository.deleteContent(userId, "c2", deletedAt = 11)
        assertTrue(repository.contentChanges(userId, since = 0, limit = 10).deletions.size >= 2)

        repository.upsertJournal(
            userId,
            JournalRecord(
                id = "j1",
                title = "j1",
                description = "",
                createdAt = 1,
                lastUpdated = 1,
                serverVersion = 0,
                deviceId = DeviceId("dev"),
            ),
        )
        repository.upsertJournal(
            userId,
            JournalRecord(
                id = "j2",
                title = "j2",
                description = "",
                createdAt = 2,
                lastUpdated = 2,
                serverVersion = 0,
                deviceId = DeviceId("dev"),
            ),
        )
        assertTrue(repository.journalChanges(userId, since = 0, limit = 10).changes.size >= 2)
        repository.deleteJournal(userId, "j1", deletedAt = 20)
        repository.deleteJournal(userId, "j2", deletedAt = 21)
        val journalChanges = repository.journalChanges(userId, since = 0, limit = 10)
        assertTrue(journalChanges.deletions.size >= 2)

        repository.upsertAssociations(
            userId,
            listOf(
                AssociationRecord("jA", "cA", createdAt = 1, serverVersion = 0, deviceId = DeviceId("dev")),
                AssociationRecord("jB", "cB", createdAt = 2, serverVersion = 0, deviceId = DeviceId("dev")),
            ),
        )
        assertTrue(repository.associationChanges(userId, since = 0, limit = 10).changes.size >= 2)
        repository.deleteAssociations(
            userId,
            listOf(AssociationKey("jA", "cA"), AssociationKey("jB", "cB")),
            deletedAt = 30,
        )
        val associationChanges = repository.associationChanges(userId, since = 0, limit = 10)
        assertTrue(associationChanges.deletions.size >= 2)

        val backupA = UUID.randomUUID()
        val backupB = UUID.randomUUID()
        repository.createBackupRecord(
            userId,
            BackupRecord(
                id = backupA,
                userId = userId,
                deviceId = "dev",
                manifest = "{}",
                storagePath = "a",
                createdAt = 100,
                sizeBytes = 1,
            ),
        )
        repository.createBackupRecord(
            userId,
            BackupRecord(
                id = backupB,
                userId = userId,
                deviceId = "dev",
                manifest = "{}",
                storagePath = "b",
                createdAt = 200,
                sizeBytes = 1,
            ),
        )
        val listed = repository.listBackups(userId)
        assertEquals(2, listed.size)
        assertTrue(listed.first().createdAt >= listed.last().createdAt)
    }
}
