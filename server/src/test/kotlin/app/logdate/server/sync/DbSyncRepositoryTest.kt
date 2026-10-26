package app.logdate.server.sync

import app.logdate.server.database.support.withH2Database
import app.logdate.shared.model.sync.DeviceId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive integration tests for [DbSyncRepository], ensuring correct persistence
 * and synchronization behavior for the core LogDate data model.
 *
 * This suite verifies the full lifecycle of content records, journals, associations,
 * and media, including server-side versioning, pagination of changes, user isolation,
 * and tombstone management for deleted data.
 */
class DbSyncRepositoryTest {
    @Test
    fun `database retains opaque transcript when an older record omits it`() {
        withRepository { repository ->
            val owner = UUID.randomUUID()
            val audio = ContentRecord("voice", "AUDIO", null, "audio.m4a", 10, 1, 2, 0, DeviceId("device"), transcript = "LDSE2:opaque")
            repository.upsertContent(owner, audio)
            repository.upsertContent(owner, audio.copy(transcript = null, lastUpdated = 3))
            assertEquals("LDSE2:opaque", repository.getContent(owner, audio.id)?.transcript)
            assertEquals(
                "LDSE2:opaque",
                repository
                    .contentChanges(owner, 0, 10)
                    .changes
                    .single()
                    .transcript,
            )
            repository.upsertContent(owner, audio.copy(transcript = null, durationMs = 20, lastUpdated = 4))
            assertNull(repository.getContent(owner, audio.id)?.transcript)
            repository.upsertContent(owner, audio)
            assertNull(repository.upsertContent(owner, audio.copy(transcript = null, mediaUri = "replacement.m4a")).transcript)
            assertNull(repository.getContent(owner, audio.id)?.transcript)
            repository.upsertContent(owner, audio)
            assertNull(repository.upsertContent(owner, audio.copy(transcript = null, type = "VIDEO")).transcript)
        }
    }

    @Test
    fun `content journal and association changes include updates and tombstones`() {
        withRepository { repository ->
            val userId = UUID.randomUUID()
            val otherUser = UUID.randomUUID()

            // Insert + update content to cover both upsert branches.
            val createdContent =
                repository.upsertContent(
                    userId,
                    ContentRecord(
                        id = "content-1",
                        type = "TEXT",
                        content = "hello",
                        mediaUri = null,
                        durationMs = 0,
                        createdAt = 10,
                        lastUpdated = 10,
                        serverVersion = 0,
                        deviceId = DeviceId("device-a"),
                    ),
                )
            val updatedContent =
                repository.upsertContent(
                    userId,
                    createdContent.copy(content = "updated", lastUpdated = 20),
                )
            assertTrue(updatedContent.serverVersion >= createdContent.serverVersion)

            // Insert second content item to validate pagination behavior.
            repository.upsertContent(
                userId,
                ContentRecord(
                    id = "content-2",
                    type = "TEXT",
                    content = "two",
                    mediaUri = null,
                    durationMs = null,
                    createdAt = 12,
                    lastUpdated = 12,
                    serverVersion = 0,
                    deviceId = DeviceId("device-a"),
                ),
            )

            // Other user data must not leak into this user.
            repository.upsertContent(
                otherUser,
                ContentRecord(
                    id = "content-other",
                    type = "TEXT",
                    content = "private",
                    mediaUri = null,
                    durationMs = null,
                    createdAt = 1,
                    lastUpdated = 1,
                    serverVersion = 0,
                    deviceId = DeviceId("device-z"),
                ),
            )

            val contentPage = repository.contentChanges(userId, since = 0, limit = 1)
            assertEquals(1, contentPage.changes.size)
            assertTrue(contentPage.hasMore)

            repository.deleteContent(userId, "content-1", deletedAt = 40)
            assertNull(repository.getContent(userId, "content-1"))

            val contentAfterDelete = repository.contentChanges(userId, since = 0, limit = 10)
            assertTrue(contentAfterDelete.deletions.any { it.id == "content-1" })
            assertTrue(contentAfterDelete.changes.any { it.id == "content-2" })
            assertFalse(contentAfterDelete.changes.any { it.id == "content-other" })

            // Journal lifecycle.
            val createdJournal =
                repository.upsertJournal(
                    userId,
                    JournalRecord(
                        id = "journal-1",
                        title = "Journal",
                        description = "desc",
                        createdAt = 100,
                        lastUpdated = 100,
                        serverVersion = 0,
                        deviceId = DeviceId("device-b"),
                    ),
                )
            repository.upsertJournal(
                userId,
                createdJournal.copy(title = "Journal updated", description = "desc2", lastUpdated = 101),
            )
            val journal = repository.getJournal(userId, "journal-1")
            assertNotNull(journal)
            assertEquals("Journal updated", journal.title)

            repository.deleteJournal(userId, "journal-1", deletedAt = 120)
            assertNull(repository.getJournal(userId, "journal-1"))
            val journalChanges = repository.journalChanges(userId, since = 0, limit = 10)
            assertTrue(journalChanges.deletions.any { it.id == "journal-1" })

            // Association lifecycle.
            repository.upsertAssociations(
                userId,
                listOf(
                    AssociationRecord(
                        journalId = "journal-a",
                        contentId = "content-2",
                        createdAt = 200,
                        serverVersion = 0,
                        deviceId = DeviceId("device-c"),
                    ),
                ),
            )
            repository.upsertAssociations(
                userId,
                listOf(
                    AssociationRecord(
                        journalId = "journal-a",
                        contentId = "content-2",
                        createdAt = 201,
                        serverVersion = 0,
                        deviceId = DeviceId("device-c"),
                    ),
                ),
            )

            val associationChanges = repository.associationChanges(userId, since = 0, limit = 10)
            assertTrue(associationChanges.changes.any { it.journalId == "journal-a" && it.contentId == "content-2" })

            repository.deleteAssociations(
                userId,
                keys = listOf(AssociationKey("journal-a", "content-2")),
                deletedAt = 250,
            )
            val associationDeletions = repository.associationChanges(userId, since = 0, limit = 10)
            assertTrue(
                associationDeletions.deletions.any {
                    it.key.journalId == "journal-a" && it.key.contentId == "content-2"
                },
            )

            val status = repository.status(userId)
            assertTrue(status.contentCount >= 2)
            assertTrue(status.journalCount >= 1)
            assertTrue(status.associationCount >= 1)
        }
    }

    @Test
    fun `media operations support update retrieval and tombstone purge`() {
        withRepository { repository ->
            val userId = UUID.randomUUID()
            val now = System.currentTimeMillis()

            val created =
                repository.upsertMedia(
                    userId,
                    MediaRecord(
                        mediaId = "media-1",
                        contentId = "content-1",
                        userId = userId,
                        fileName = "one.jpg",
                        mimeType = "image/jpeg",
                        sizeBytes = 3,
                        data = byteArrayOf(1, 2, 3),
                        storagePath = null,
                        createdAt = now,
                        serverVersion = 0,
                        deviceId = DeviceId("device-a"),
                    ),
                )
            val updated =
                repository.upsertMedia(
                    userId,
                    created.copy(
                        fileName = "two.jpg",
                        sizeBytes = 4,
                        data = byteArrayOf(9, 8, 7, 6),
                        storagePath = "users/retained/media",
                    ),
                )
            assertTrue(updated.serverVersion >= created.serverVersion)

            val fetched = repository.getMedia(userId, "media-1")
            assertNotNull(fetched)
            assertEquals("two.jpg", fetched.fileName)

            repository.deleteMedia(userId, "media-1", deletedAt = now + 1)
            assertNull(repository.getMedia(userId, "media-1"))

            val purgeNone = repository.purgeTombstones(userId, olderThan = now)
            assertEquals(0, purgeNone.mediaPurged)

            val purgeSome = repository.purgeTombstones(userId, olderThan = now + 10_000)
            assertEquals(1, purgeSome.mediaPurged)
            val retained = repository.listAllMediaForUser(userId).single()
            assertEquals("users/retained/media", retained.storagePath)
            assertTrue(retained.data.isEmpty())
            assertEquals(0, repository.purgeTombstones(userId, olderThan = now + 10_000).mediaPurged)
        }
    }

    @Test
    fun `backup operations create list lookup and delete`() {
        withRepository { repository ->
            val userId = UUID.randomUUID()
            val backupId = UUID.randomUUID()
            val createdAt = System.currentTimeMillis()

            repository.createBackupRecord(
                userId,
                BackupRecord(
                    id = backupId,
                    userId = userId,
                    deviceId = "device-1",
                    manifest = "{\"v\":1}",
                    storagePath = "users/$userId/backups/$backupId.bin",
                    createdAt = createdAt,
                    sizeBytes = 1024,
                ),
            )

            val fetched = repository.getBackupRecord(userId, backupId)
            assertNotNull(fetched)
            assertEquals(1024, fetched.sizeBytes)

            val listed = repository.listBackups(userId)
            assertEquals(1, listed.size)
            assertEquals(backupId, listed.first().id)

            repository.deleteBackup(userId, backupId)
            assertNull(repository.getBackupRecord(userId, backupId))
            assertTrue(repository.listBackups(userId).isEmpty())
        }
    }

    @Test
    fun `global cleanup scrubs payloads and preserves deletion delivery across users`() {
        withRepository { repository ->
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            val oldTs = 1L
            val newTs = 5_000L

            repository.upsertContent(
                userA,
                ContentRecord(
                    id = "old-content",
                    type = "TEXT",
                    content = "a",
                    mediaUri = null,
                    durationMs = null,
                    createdAt = 1,
                    lastUpdated = 1,
                    serverVersion = 0,
                    deviceId = DeviceId("a"),
                ),
            )
            repository.upsertJournal(
                userA,
                JournalRecord(
                    id = "old-journal",
                    title = "j",
                    description = "d",
                    createdAt = 1,
                    lastUpdated = 1,
                    serverVersion = 0,
                    deviceId = DeviceId("a"),
                ),
            )
            repository.upsertAssociations(
                userA,
                listOf(
                    AssociationRecord(
                        journalId = "old-journal",
                        contentId = "old-content",
                        createdAt = 1,
                        serverVersion = 0,
                        deviceId = DeviceId("a"),
                    ),
                ),
            )

            repository.deleteContent(userA, "old-content", oldTs)
            repository.deleteJournal(userA, "old-journal", oldTs)
            repository.deleteAssociations(userA, listOf(AssociationKey("old-journal", "old-content")), oldTs)

            repository.upsertContent(
                userB,
                ContentRecord(
                    id = "new-content",
                    type = "TEXT",
                    content = "b",
                    mediaUri = null,
                    durationMs = null,
                    createdAt = 1,
                    lastUpdated = 1,
                    serverVersion = 0,
                    deviceId = DeviceId("b"),
                ),
            )
            repository.deleteContent(userB, "new-content", newTs)

            val purge = repository.purgeTombstonesOlderThan(olderThan = 100L)
            assertTrue(purge.contentPurged >= 1)
            assertTrue(purge.journalPurged >= 1)
            assertEquals(0, purge.associationPurged)

            assertTrue(repository.contentChanges(userA, 0, 10).deletions.any { it.id == "old-content" })
            assertTrue(repository.journalChanges(userA, 0, 10).deletions.any { it.id == "old-journal" })
            assertTrue(repository.associationChanges(userA, 0, 10).deletions.any { it.key.contentId == "old-content" })
            assertEquals(0, repository.purgeTombstonesOlderThan(olderThan = 100L).contentPurged)
            assertEquals(0, repository.purgeTombstonesOlderThan(olderThan = 100L).journalPurged)

            // Newer tombstones and cleaned older markers both remain available.
            val userBChanges = repository.contentChanges(userB, since = 0, limit = 10)
            assertTrue(userBChanges.deletions.any { it.id == "new-content" })
        }
    }

    @Test
    fun `a write older than a delete is rejected and the tombstone stays`() {
        withRepository { repository ->
            val owner = UUID.randomUUID()
            val note = ContentRecord("note-1", "TEXT", "original", null, null, 1, 1, 0, DeviceId("device"))
            repository.upsertContent(owner, note)
            repository.deleteContent(owner, "note-1", deletedAt = 10)

            assertFailsWith<StaleWriteException> {
                repository.upsertContent(owner, note.copy(content = "stale edit", lastUpdated = 5))
            }
            assertNull(repository.getContent(owner, "note-1"))
        }
    }

    @Test
    fun `a future dated write cannot resurrect a deleted record`() {
        withRepository { repository ->
            val owner = UUID.randomUUID()
            val note = ContentRecord("note-1", "TEXT", "original", null, null, 1, 1, 0, DeviceId("device"))
            repository.upsertContent(owner, note)
            repository.deleteContent(owner, "note-1", deletedAt = 10)

            assertFailsWith<StaleWriteException> {
                repository.upsertContent(owner, note.copy(content = "newer edit", lastUpdated = Long.MAX_VALUE))
            }
            assertNull(repository.getContent(owner, "note-1"))
        }
    }

    @Test
    fun `deleting an unknown note leaves a tombstone that rejects a late write`() {
        withRepository { repository ->
            val owner = UUID.randomUUID()
            repository.deleteContent(owner, "never-seen", deletedAt = 10)

            assertFailsWith<StaleWriteException> {
                repository.upsertContent(
                    owner,
                    ContentRecord("never-seen", "TEXT", "late", null, null, 1, 5, 0, DeviceId("device")),
                )
            }
        }
    }

    private fun withRepository(testBody: (DbSyncRepository) -> Unit) {
        withH2Database(
            ContentSyncTable,
            JournalSyncTable,
            AssociationSyncTable,
            MediaSyncTable,
            BackupSyncTable,
        ) {
            testBody(DbSyncRepository())
        }
    }
}
