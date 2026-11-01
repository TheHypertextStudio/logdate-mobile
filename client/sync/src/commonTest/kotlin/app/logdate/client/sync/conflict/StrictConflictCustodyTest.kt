package app.logdate.client.sync.conflict

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.InMemoryKeyValueStorage
import app.logdate.client.sync.SyncStorageUnavailableException
import app.logdate.client.sync.TextConflictVersions
import app.logdate.client.sync.metadata.UploadScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

class StrictConflictCustodyTest {
    @Test
    fun `read failure cannot replace existing custody with an apparently empty queue`() =
        runTest {
            val storage = FailureStorage()
            val store = KeyValueSyncConflictStore(storage)
            val first = record()
            store.add(first)
            val before = storage.backing.values.toMap()
            val writesBefore = storage.writeCalls
            storage.failRead = true
            assertFailsWith<IllegalStateException> { store.add(newer(first)) }
            assertEquals(before, storage.backing.values)
            assertEquals(writesBefore, storage.writeCalls)
            storage.failRead = false
            assertEquals(listOf(first), KeyValueSyncConflictStore(storage).list())
        }

    @Test
    fun `failed strict write preserves earlier originals and retry persists both pairs`() =
        runTest {
            val storage = FailureStorage()
            val store = KeyValueSyncConflictStore(storage)
            val first = record()
            val second = newer(first)
            store.add(first)
            val before = storage.backing.values.toMap()
            storage.failWrite = true
            assertFailsWith<SyncStorageUnavailableException> { store.add(second) }
            assertEquals(before, storage.backing.values)
            storage.failWrite = false
            store.add(second)
            val restarted = KeyValueSyncConflictStore(storage)
            assertEquals(listOf(first, second), restarted.list())
            restarted.add(second.copy(id = Uuid.random().toString()))
            assertEquals(listOf(first, second), KeyValueSyncConflictStore(storage).list())
        }

    @Test
    fun `failed strict read cannot hide or remove retained versions`() =
        runTest {
            val storage = FailureStorage()
            val store = KeyValueSyncConflictStore(storage)
            val first = record()
            store.add(first)
            val before = storage.backing.values.toMap()
            val writesBefore = storage.writeCalls
            storage.failRead = true
            assertFailsWith<IllegalStateException> { store.list() }
            assertFailsWith<IllegalStateException> { store.remove(first.id) }
            assertEquals(before, storage.backing.values)
            assertEquals(writesBefore, storage.writeCalls)
            storage.failRead = false
            assertEquals(listOf(first), KeyValueSyncConflictStore(storage).list())
        }

    @Test
    fun `strict write cancellation propagates without discarding either original`() =
        runTest {
            val storage = FailureStorage()
            val store = KeyValueSyncConflictStore(storage)
            val first = record()
            store.add(first)
            val before = storage.backing.values.toMap()
            storage.cancelWrite = true
            assertFailsWith<CancellationException> { store.add(newer(first)) }
            assertEquals(before, storage.backing.values)
            storage.cancelWrite = false
            assertEquals(listOf(first), KeyValueSyncConflictStore(storage).list())
        }

    private fun record(): SyncConflictRecord {
        val created = Instant.fromEpochMilliseconds(1000)
        val local = JournalNote.Text(Uuid.random(), created, created, "Original local prose", syncVersion = 1)
        val remote = local.copy(content = "Original remote prose", lastUpdated = Instant.fromEpochMilliseconds(2000), syncVersion = 2)
        return SyncConflictRecord(
            id = Uuid.random().toString(),
            entityType = "NOTE",
            entityId = local.uid.toString(),
            localVersion = 1,
            remoteVersion = 2,
            localUpdatedAt = 1000,
            remoteUpdatedAt = 2000,
            reason = "Synthetic divergent writing",
            detectedAt = 3000,
            scope = UploadScope("synthetic-owner", "https://sync.example"),
            textVersions = TextConflictVersions(local, remote, created.toString(), remote.lastUpdated.toString()),
        )
    }

    private fun newer(first: SyncConflictRecord): SyncConflictRecord {
        val pair = requireNotNull(first.textVersions)
        val remote =
            pair.remote.copy(
                content = "Later distinct remote prose",
                syncVersion = 3,
                lastUpdated = Instant.fromEpochMilliseconds(3000),
            )
        return first.copy(
            id = Uuid.random().toString(),
            remoteVersion = 3,
            remoteUpdatedAt = 3000,
            textVersions = pair.copy(remote = remote, remoteTimestamp = remote.lastUpdated.toString()),
        )
    }

    private class FailureStorage(
        val backing: InMemoryKeyValueStorage = InMemoryKeyValueStorage(),
    ) : KeyValueStorage by backing {
        var failRead = false
        var failWrite = false
        var cancelWrite = false
        var writeCalls = 0

        // Model the production distinction: ordinary reads/writes may conceal storage failure.
        override suspend fun getString(key: String): String? = if (failRead) null else backing.getString(key)

        override suspend fun putString(
            key: String,
            value: String,
        ) {
            writeCalls++
            if (cancelWrite) throw CancellationException("Synthetic cancelled save")
            if (!failWrite) backing.putString(key, value)
        }

        override suspend fun getStringOrThrow(key: String): String? {
            check(!failRead) { "Synthetic durable read failure" }
            return backing.getString(key)
        }

        override suspend fun putStringOrThrow(
            key: String,
            value: String,
        ) {
            writeCalls++
            if (cancelWrite) throw CancellationException("Synthetic cancelled save")
            check(!failWrite) { "Synthetic durable write failure" }
            backing.putString(key, value)
        }
    }
}
