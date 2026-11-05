@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.sync

import app.logdate.server.database.AccountsTable
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.database.support.withH2Database
import app.logdate.shared.model.sync.LocationHistoryUpload
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LocationHistoryRepositoryTest {
    @Test
    fun `isolates accounts and retries without advancing version`() {
        verifyRetries(InMemoryLocationHistoryRepository())
    }

    @Test
    fun `paginates versions and preserves tombstones against stale uploads`() {
        verifyTombstones(InMemoryLocationHistoryRepository())
    }

    @Test
    fun `database retries and tombstones survive repository recreation`() {
        withH2Database(AccountsTable, LocationHistoryTable, LocationHistoryVersionsTable) {
            verifyRetries(DbLocationHistoryRepository()) { createTestAccount(it) }
            verifyTombstones(DbLocationHistoryRepository()) { createTestAccount(it) }
            val user = UUID.fromString(Uuid.random().toString())
            createTestAccount(user)
            val saved = DbLocationHistoryRepository().upload(user, listOf(record("durable")))
            assertEquals(saved, DbLocationHistoryRepository().changes(user, 0, 10).records)
        }
    }

    private fun verifyRetries(
        repository: LocationHistoryRepository,
        prepareOwner: (UUID) -> Unit = {},
    ) {
        val user = UUID.fromString(Uuid.random().toString())
        val other = UUID.fromString(Uuid.random().toString())
        prepareOwner(user)
        prepareOwner(other)
        val upload = record("one")
        val first = repository.upload(user, listOf(upload))
        assertEquals(first, repository.upload(user, listOf(upload)))
        assertEquals(first, repository.upload(user, listOf(upload.copy(payload = "LDSE2:fresh-nonce"))))
        assertTrue(repository.changes(other, 0, 10).records.isEmpty())
        val otherStored = repository.upload(other, listOf(upload.copy(payload = "LDSE2:other")))
        assertEquals("LDSE2:other", otherStored.single().payload)
        assertEquals(
            "LDSE2:ciphertext",
            repository
                .changes(user, 0, 10)
                .records
                .single()
                .payload,
        )
        assertFailsWith<LocationHistoryConflictException> {
            repository.upload(user, listOf(upload.copy(recordType = "place")))
        }
        assertEquals("LDSE2:ciphertext", first.single().payload)
        assertFailsWith<LocationHistoryConflictException> {
            repository.upload(user, listOf(record("two"), upload.copy(payload = "LDSE2:changed", deviceVersion = 2)))
        }
        assertEquals(1, repository.changes(user, 0, 10).records.size)
    }

    private fun verifyTombstones(
        repository: LocationHistoryRepository,
        prepareOwner: (UUID) -> Unit = {},
    ) {
        val user = UUID.fromString(Uuid.random().toString())
        prepareOwner(user)
        val first = repository.upload(user, listOf(record("one"), record("two")))
        val page = repository.changes(user, 0, 1)
        assertTrue(page.hasMore)
        assertEquals(first[0].serverVersion, page.nextCursor)
        val second = repository.changes(user, page.nextCursor, 1)
        assertEquals("two", second.records.single().id)
        assertFalse(second.hasMore)
        val deletion =
            record("one").copy(
                payload = null,
                deleted = true,
                deviceVersion = 2,
                expectedServerVersion = first[0].serverVersion,
            )
        val tombstone = repository.upload(user, listOf(deletion)).single()
        assertNull(tombstone.payload)
        assertEquals(listOf(tombstone), repository.upload(user, listOf(deletion)))
        assertFailsWith<LocationHistoryConflictException> { repository.upload(user, listOf(record("one"))) }
        assertFailsWith<LocationHistoryConflictException> {
            repository.upload(user, listOf(record("one").copy(expectedServerVersion = tombstone.serverVersion)))
        }
        assertEquals(listOf(tombstone), repository.changes(user, second.nextCursor, 10).records)
        repository.upload(user, listOf(record("never-uploaded").copy(payload = null, deleted = true)))
        assertFailsWith<LocationHistoryConflictException> {
            repository.upload(user, listOf(record("never-uploaded")))
        }
    }

    private fun record(id: String) =
        LocationHistoryUpload(
            id,
            "observation",
            "LDSE2:ciphertext",
            deviceId = "device",
            deviceVersion = 1,
        )
}
