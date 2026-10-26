package app.logdate.server.logdate

import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.shared.model.sync.DeviceId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the collections repository implementation backed by the
 * synchronization system.
 *
 * This suite verifies the full lifecycle of log entries, journals, and associations
 * when persisted through the sync layer. It ensures that the repository correctly
 * handles incremental updates (change feeds), conflict-aware upserts, tombstone-based
 * deletions, and maintenance-driven data purging.
 */
class SyncBackedLogDateCollectionsRepositoryTest {
    @Test
    fun `entries round trip through snapshots changes and deletions`() =
        runTest {
            val repository = SyncBackedLogDateCollectionsRepository(InMemorySyncRepository())
            val userId = UUID.randomUUID()
            val created =
                repository.upsertEntry(
                    userId = userId,
                    entry =
                        LogDateEntry(
                            id = "entry-1",
                            type = "TEXT",
                            content = "hello",
                            mediaUri = null,
                            durationMs = 0L,
                            createdAt = 10L,
                            lastUpdated = 10L,
                            version = 0L,
                            deviceId = DeviceId("device-a"),
                        ),
                )

            val fetched = repository.getEntry(userId = userId, id = "entry-1")
            val snapshot = repository.listEntries(userId)
            val changeSet = repository.entryChanges(userId = userId, since = 0L, limit = 20)
            assertNotNull(fetched)

            assertEquals("entry-1", fetched.id)
            assertEquals("TEXT", fetched.type)
            assertEquals("hello", fetched.content)
            assertEquals(created.version, fetched.version)
            assertEquals(listOf(fetched), snapshot)
            assertEquals(listOf(fetched), changeSet.changes)
            assertTrue(changeSet.deletions.isEmpty())

            val deletedAt = changeSet.lastTimestamp + 1L
            repository.deleteEntry(userId = userId, id = "entry-1", deletedAt = deletedAt)

            val deleted = repository.entryChanges(userId = userId, since = created.version, limit = 20)
            assertNull(repository.getEntry(userId = userId, id = "entry-1"))
            assertTrue(repository.listEntries(userId).isEmpty())
            assertEquals(
                listOf(LogDateEntryDeletion(id = "entry-1", deletedAt = deletedAt)),
                deleted.deletions,
            )
        }

    @Test
    fun `journals preserve their own internal shape and status counts`() =
        runTest {
            val repository = SyncBackedLogDateCollectionsRepository(InMemorySyncRepository())
            val userId = UUID.randomUUID()
            val stored =
                repository.upsertJournal(
                    userId = userId,
                    journal =
                        LogDateJournal(
                            id = "journal-1",
                            title = "Travel",
                            description = "Trip notes",
                            createdAt = 30L,
                            lastUpdated = 40L,
                            version = 0L,
                            deviceId = DeviceId("device-b"),
                        ),
                )

            val changes = repository.journalChanges(userId = userId, since = 0L, limit = 20)
            val status = repository.status(userId)

            val snapshot = repository.listJournals(userId)
            val fetched = repository.getJournal(userId, "journal-1")
            assertNotNull(fetched)
            assertEquals(listOf(fetched), snapshot)
            assertEquals(snapshot, changes.changes)
            assertEquals(0, status.entryCount)
            assertEquals(1, status.journalCount)
            assertEquals(0, status.associationCount)
            assertTrue(status.lastTimestamp > 0L)
            assertEquals(stored.version, fetched.version)
        }

    @Test
    fun `associations stay independent from entry and journal snapshots`() =
        runTest {
            val repository = SyncBackedLogDateCollectionsRepository(InMemorySyncRepository())
            val userId = UUID.randomUUID()
            val stored =
                repository
                    .upsertAssociations(
                        userId = userId,
                        associations =
                            listOf(
                                LogDateAssociation(
                                    journalId = "journal-1",
                                    entryId = "entry-1",
                                    createdAt = 50L,
                                    version = 0L,
                                    deviceId = DeviceId("device-c"),
                                ),
                            ),
                    ).single()

            val snapshot = repository.listAssociations(userId)
            val changes = repository.associationChanges(userId = userId, since = 0L, limit = 20)

            assertEquals(listOf(stored), snapshot)
            assertEquals(listOf(stored), changes.changes)

            val deletedAt = changes.lastTimestamp + 1L
            repository.deleteAssociations(
                userId = userId,
                associations = listOf(LogDateAssociationRef(journalId = "journal-1", entryId = "entry-1")),
                deletedAt = deletedAt,
            )

            val deleted = repository.associationChanges(userId = userId, since = stored.version, limit = 20)
            assertTrue(repository.listAssociations(userId).isEmpty())
            assertEquals(
                listOf(
                    LogDateAssociationDeletion(
                        association = LogDateAssociationRef(journalId = "journal-1", entryId = "entry-1"),
                        deletedAt = deletedAt,
                    ),
                ),
                deleted.deletions,
            )
        }

    @Test
    fun `tombstone purge maps sync counts onto the internal collection model`() =
        runTest {
            val repository = SyncBackedLogDateCollectionsRepository(InMemorySyncRepository())
            val userId = UUID.randomUUID()

            repository.upsertEntry(
                userId = userId,
                entry =
                    LogDateEntry(
                        id = "entry-2",
                        type = "TEXT",
                        content = "purge me",
                        mediaUri = null,
                        durationMs = 0L,
                        createdAt = 10L,
                        lastUpdated = 10L,
                        version = 0L,
                        deviceId = DeviceId("device-d"),
                    ),
            )
            repository.deleteEntry(userId = userId, id = "entry-2", deletedAt = 100L)
            repository.deleteJournal(userId = userId, id = "journal-missing", deletedAt = 101L)
            repository.deleteAssociations(
                userId = userId,
                associations = listOf(LogDateAssociationRef(journalId = "journal-2", entryId = "entry-2")),
                deletedAt = 102L,
            )

            val purged = repository.purgeTombstones(userId = userId, olderThan = 200L)

            assertEquals(0, purged.entryPurged)
            assertEquals(0, purged.journalPurged)
            assertEquals(0, purged.associationPurged)
            assertEquals(200L, purged.cutoff)
        }
}
