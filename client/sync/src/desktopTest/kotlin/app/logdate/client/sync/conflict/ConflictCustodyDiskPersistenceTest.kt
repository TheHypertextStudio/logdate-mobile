package app.logdate.client.sync.conflict

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.logdate.client.datastore.DataStoreKeyValueStorage
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.ChangesPage
import app.logdate.client.sync.DownloadStrategy
import app.logdate.client.sync.SyncDownloadEngine
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadInboxTest
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.recovery.WireDownload
import app.logdate.client.sync.test.FakeSyncMetadataService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConflictCustodyDiskPersistenceTest {
    @Test
    fun `real file retains exact versions scope timestamps and dedup after recreation`() =
        runTest {
            for (pending in listOf(false, true)) {
                withFixture(pending) { fixture ->
                    assertTrue(fixture.download().success)
                    assertEquals(fixture.local, fixture.canonical)
                    val first = fixture.store.list().single()
                    assertOriginals(fixture, first)
                    fixture.close()
                    assertTrue(fixture.file.isFile)
                    assertTrue(fixture.file.length() > 0)

                    fixture.open()
                    assertEquals(listOf(first), fixture.store.list())
                    assertOriginals(fixture, fixture.store.list().single())
                    fixture.replay()
                    assertTrue(fixture.download().success)
                    fixture.store.add(first.copy(id = Uuid.random().toString()))
                    assertEquals(listOf(first), fixture.store.list())
                    assertEquals(fixture.local, fixture.canonical)
                    assertEquals(if (pending) 1 else 0, fixture.metadata.getPendingUploads(EntityType.NOTE).size)

                    fixture.close()
                    fixture.open()
                    assertEquals(listOf(first), fixture.store.list())
                }
            }
        }

    @Test
    fun `corrupt real file fails closed without changing writing pending custody or bytes`() =
        runTest {
            withFixture(pending = true) { fixture ->
                assertTrue(fixture.download().success)
                val first = fixture.store.list().single()
                fixture.close()
                val validSeed = fixture.file.readBytes()
                assertTrue(validSeed.isNotEmpty())
                // An incomplete protobuf tag, written only after the prior DataStore has stopped.
                val corruptSeed = byteArrayOf(0x80.toByte())
                fixture.file.writeBytes(corruptSeed)
                fixture.remote = fixture.remote.copy(content = "Another synthetic remote revision.", syncVersion = 3)
                fixture.replay()
                val pendingBefore = fixture.metadata.getPendingUploads(EntityType.NOTE)
                val inboxBefore = fixture.database.rows.single()
                fixture.open()

                // Prove that the real serializer, rather than a fault-injection wrapper, fails.
                assertFailsWith<CorruptionException> { fixture.storage.getStringOrThrow(CONFLICT_KEY) }
                assertFailsWith<CorruptionException> { fixture.store.list() }
                assertFailsWith<CorruptionException> { fixture.store.add(first.copy(id = Uuid.random().toString())) }
                assertEquals(fixture.local, fixture.canonical)
                assertEquals(pendingBefore, fixture.metadata.getPendingUploads(EntityType.NOTE))
                assertEquals(listOf(inboxBefore), fixture.database.rows)
                assertContentEquals(corruptSeed, fixture.file.readBytes())

                assertFalse(fixture.download().success)
                assertEquals(fixture.local, fixture.canonical)
                assertEquals(pendingBefore, fixture.metadata.getPendingUploads(EntityType.NOTE))
                assertFalse(fixture.inbox.isAppliedVersion("NOTE", fixture.local.uid.toString(), 3))
                val retained = fixture.database.rows.single()
                // Existing retry diagnostics may change; every original inbox custody field must survive.
                assertEquals(
                    inboxBefore,
                    retained.copy(
                        state = inboxBefore.state,
                        attempts = inboxBefore.attempts,
                        nextAttemptAt = inboxBefore.nextAttemptAt,
                        reason = inboxBefore.reason,
                    ),
                )
                assertContentEquals(corruptSeed, fixture.file.readBytes())
                fixture.close()
                assertContentEquals(corruptSeed, fixture.file.readBytes())

                // Restoring a saved synthetic fixture seed is test setup, not a repair API.
                fixture.file.writeBytes(validSeed)
                fixture.open()
                assertEquals(listOf(first), fixture.store.list())
                fixture.now = retained.nextAttemptAt + 1
                assertTrue(fixture.download().success)
                val pairs = fixture.store.list()
                assertEquals(2, pairs.size)
                assertEquals(first, pairs.first { it.id == first.id })
                assertOriginals(fixture, pairs.single { it.remoteVersion == 3L })
                assertEquals(fixture.local, fixture.canonical)
                assertEquals(pendingBefore, fixture.metadata.getPendingUploads(EntityType.NOTE))
                fixture.close()
                fixture.open()
                assertEquals(pairs, fixture.store.list())
            }
        }

    private fun assertOriginals(
        fixture: Fixture,
        record: SyncConflictRecord,
    ) {
        assertEquals(UploadScope("synthetic-owner", "https://sync.example"), record.scope)
        assertEquals(fixture.local.uid.toString(), record.entityId)
        assertEquals(fixture.local.syncVersion, record.localVersion)
        assertEquals(fixture.remote.syncVersion, record.remoteVersion)
        val pair = assertNotNull(record.textVersions)
        assertEquals(fixture.local, pair.local)
        assertEquals(fixture.remote, pair.remote)
        assertEquals(Instant.fromEpochMilliseconds(fixture.local.syncVersion).toString(), pair.localTimestamp)
        assertEquals(Instant.fromEpochMilliseconds(fixture.remote.syncVersion).toString(), pair.remoteTimestamp)
    }

    private suspend fun withFixture(
        pending: Boolean,
        block: suspend (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("conflict-custody-audit")
        val fixture = Fixture(root.resolve("conflicts.preferences_pb").toFile(), pending)
        try {
            fixture.open()
            block(fixture)
        } finally {
            fixture.close()
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(
        val file: File,
        private val pending: Boolean,
    ) {
        private var storeJob: Job? = null
        lateinit var storage: DataStoreKeyValueStorage
            private set
        lateinit var store: KeyValueSyncConflictStore
            private set
        val database = DownloadInboxTest.Database()
        var now = 1L
        val inbox = DownloadInbox(database, database, { DownloadScope("synthetic-owner", "https://sync.example") }, { now })
        val metadata =
            FakeSyncMetadataService(
                trackOperationIdentity = true,
                currentScope = { UploadScope("synthetic-owner", "https://sync.example") },
            )
        private val instant = Instant.fromEpochMilliseconds(1000)
        val local =
            JournalNote.Text(
                uid = Uuid.random(),
                creationTimestamp = instant,
                lastUpdated = instant,
                content = "Synthetic local writing beside the river.",
                syncVersion = 1,
            )
        var remote =
            local.copy(
                content = "Synthetic remote writing beneath the trees.",
                syncVersion = 2,
                lastUpdated = Instant.fromEpochMilliseconds(2000),
            )
        var canonical = local
        private var prepared = false

        fun open() {
            check(storeJob == null) { "Close and join the prior DataStore before recreating it" }
            val job = SupervisorJob()
            storeJob = job
            val disk =
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + job),
                    produceFile = { file },
                )
            storage = DataStoreKeyValueStorage(disk)
            store = KeyValueSyncConflictStore(storage)
        }

        suspend fun close() {
            storeJob?.cancelAndJoin()
            storeJob = null
        }

        suspend fun replay() {
            inbox.stage(
                "NOTE",
                remote.syncVersion,
                listOf(
                    WireDownload(local.uid.toString(), remote.syncVersion, false, "encrypted-synthetic-record"),
                ),
            )
        }

        suspend fun download(): SyncResult {
            if (!prepared) {
                if (pending) metadata.enqueuePending(local.uid.toString(), EntityType.NOTE, PendingOperation.UPDATE)
                replay()
                prepared = true
            }
            return SyncDownloadEngine(
                database,
                metadata,
                store,
                { SyncResult(false) },
                { _, _ -> SyncResult(false) },
                downloadInbox = inbox,
            ).download(
                DownloadStrategy<JournalNote>(
                    entityType = EntityType.NOTE,
                    logLabel = "note",
                    fetchChanges = { _, _ ->
                        Result.success(
                            ChangesPage(
                                inbox.pending("NOTE").map { remote },
                                emptyList(),
                                remote.lastUpdated,
                                false,
                            ),
                        )
                    },
                    localItem = { id -> canonical.takeIf { it.uid == id } },
                    idOf = { it.uid },
                    syncVersionOf = { it.syncVersion },
                    lastUpdatedOf = { it.lastUpdated },
                    conflictResolver = JournalNoteConflictResolver(),
                    applyCreate = { canonical = it as JournalNote.Text },
                    applyReplace = { _, next -> canonical = next as JournalNote.Text },
                    applyDelete = { error("Unexpected deletion") },
                ),
                "synthetic-token",
                Instant.fromEpochMilliseconds(0),
            )
        }
    }

    private companion object {
        const val CONFLICT_KEY = "sync_conflict_queue"
    }
}
